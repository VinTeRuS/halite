package io.github.vinterus.halite.world.gen;

import io.github.vinterus.halite.client.HaliteClientMod;
import io.github.vinterus.halite.mixin.features.ChunkInvoker;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkCache;
import net.minecraft.world.chunk.storage.RegionIo;
import net.minecraft.world.gen.chunk.OverworldChunkGenerator;
import net.modificationstation.stationapi.impl.world.chunk.FlattenedChunk;
import net.modificationstation.stationapi.impl.worldgen.WorldGeneratorImpl;


import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates the terrain of chunks that are about to be needed on background threads, ahead of time.
 *
 * <p>Only the raw terrain (noise, surface, caves) is made here; that is the expensive part and a pure function of the
 * seed and the chunk position. The single player chunk cache still asks for each chunk exactly when vanilla would and
 * then takes the prepared terrain instead of generating it (see ChunkCacheMixin), so loading order, decoration and
 * lighting all happen on the game thread in the vanilla order and the world comes out the same.
 *
 * <p>State the vanilla generator shares through the world is replaced per thread: each {@link TerrainThread} has its
 * own generator and biome source, StationAPI's height shaping runs on per-thread caches ({@link WorkerNoiseHeights}),
 * and the global StationAPI biome provider is only entered under {@link #BIOME_LOCK}. The column light updates that
 * {@code populateHeightMap} queues read neighbouring chunks, so terrain threads skip them and the game thread runs
 * them when it takes the chunk.
 */
public final class TerrainPregen {
    /** Guards biome lookups, which reach StationAPI's shared biome provider. */
    public static final Object BIOME_LOCK = new Object();

    private static final int QUEUED = 0, RUNNING = 1, DONE = 2, CANCELLED = 3;
    private static final int MAX_ENTRIES = 384;

    private static TerrainPregen active;

    private final World world;
    private final File regionDir;
    private final ExecutorService executor;
    private final int maxInFlight;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final LongOpenHashSet onDisk = new LongOpenHashSet();
    private volatile boolean closed;
    private int taken, generatedHere, failures;

    private TerrainPregen(World world, File regionDir, int threads) {
        this.world = world;
        this.regionDir = regionDir;
        this.maxInFlight = threads * 4;
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                task -> new TerrainThread(task, "Halite Terrain " + counter.getAndIncrement()));
    }

    /** Starts background generation for a single player world, or stops it for anything else. Game thread. */
    public static void attach(World world, ChunkCache cache) {
        if (active != null && active.world == world) {
            return;
        }
        detach();

        if (world == null || cache == null || !HaliteClientMod.options().useAsyncTerrainGeneration) {
            return;
        }
        ChunkCacheAccess access = (ChunkCacheAccess) cache;
        if (access.halite$getGenerator() == null || access.halite$getGenerator().getClass() != OverworldChunkGenerator.class) {
            return;
        }
        File regionDir = access.halite$getRegionDir();
        if (regionDir == null || !noiseHookPresent()) {
            HaliteClientMod.logger().warn("Background terrain generation is off: this StationAPI version is not supported");
            return;
        }

        int threads = threadCount();
        active = new TerrainPregen(world, regionDir, threads);
        HaliteClientMod.logger().info("Generating terrain ahead of time on {} thread(s)", threads);
    }

    public static void detach() {
        TerrainPregen pregen = active;
        active = null;
        if (pregen != null) {
            pregen.close();
        }
    }

    public static TerrainPregen active() {
        return active;
    }

    /**
     * Whether StationAPI's height shaping is redirected for terrain threads (an optional compat mixin). Without it the
     * threads would share its static caches with the game thread, so generation stays in place.
     */
    private static boolean noiseHookPresent() {
        for (java.lang.reflect.Method method : WorldGeneratorImpl.class.getDeclaredMethods()) {
            if (method.getName().contains("halite$threadLocalNoise")) {
                return true;
            }
        }
        return false;
    }

    private static int threadCount() {
        int configured = HaliteClientMod.options().terrainThreads;
        if (configured > 0) {
            return configured;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(cores / 3, 3));
    }

    /**
     * Queues a chunk's terrain unless it is loaded, saved on disk or already queued. Game thread.
     *
     * @return false once no more requests are taken this round
     */
    public boolean request(ChunkCache cache, int cx, int cz, boolean ignoreLimit) {
        if (this.closed) {
            return false;
        }
        long key = key(cx, cz);
        if (this.entries.containsKey(key) || this.onDisk.contains(key) || cache.isChunkLoaded(cx, cz)) {
            return true;
        }
        if (this.isOnDisk(cx, cz)) {
            // saved chunks never stop being saved, so they are not looked up again
            this.onDisk.add(key);
            return true;
        }
        if (this.entries.size() >= MAX_ENTRIES || (!ignoreLimit && this.inFlight() >= this.maxInFlight)) {
            return false;
        }

        Entry entry = new Entry(cx, cz);
        this.entries.put(key, entry);
        this.executor.execute(() -> this.generate(entry));
        return true;
    }

    private int inFlight() {
        int count = 0;
        for (Entry entry : this.entries.values()) {
            int state = entry.state.get();
            if (state == QUEUED || state == RUNNING) {
                count++;
            }
        }
        return count;
    }

    private boolean isOnDisk(int cx, int cz) {
        return RegionIo.getRegionFile(this.regionDir, cx, cz).hasChunkData(cx & 31, cz & 31);
    }

    /** Drops prepared terrain far from the player so it doesn't pile up. Game thread. */
    public void evictOutside(int centerX, int centerZ, int radius) {
        this.entries.values().removeIf(entry -> {
            if (Math.abs(entry.cx - centerX) <= radius && Math.abs(entry.cz - centerZ) <= radius) {
                return false;
            }
            // running entries stay until they finish; queued ones are cancelled so no thread picks them up
            return entry.state.compareAndSet(QUEUED, CANCELLED) || entry.state.get() == DONE;
        });
    }

    private void generate(Entry entry) {
        if (this.closed || !entry.state.compareAndSet(QUEUED, RUNNING)) {
            entry.finished.countDown();
            return;
        }
        try {
            TerrainThread thread = (TerrainThread) Thread.currentThread();
            thread.prepare(this.world);
            Chunk chunk = thread.generator.getChunk(entry.cx, entry.cz);
            entry.chunk = chunk instanceof FlattenedChunk ? chunk : null;
        } catch (Throwable t) {
            entry.error = t;
        } finally {
            entry.state.set(DONE);
            entry.finished.countDown();
        }
    }

    /**
     * The prepared terrain for a chunk the cache is about to generate, or null to let vanilla generate it. Waits if
     * the chunk is being generated right now. Game thread.
     */
    public Chunk take(World world, int cx, int cz) {
        if (world != this.world) {
            return null;
        }
        Entry entry = this.entries.remove(key(cx, cz));
        if (entry == null || entry.state.compareAndSet(QUEUED, CANCELLED)) {
            this.generatedHere++;
            return null;
        }

        try {
            entry.finished.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        Chunk chunk = entry.chunk;
        if (chunk == null) {
            if (entry.error != null && this.failures++ < 3) {
                HaliteClientMod.logger().warn("Background terrain generation failed for chunk {}, {}", cx, cz, entry.error);
            }
            this.generatedHere++;
            return null;
        }

        // what populateHeightMap would have queued, now that the chunk joins the world (vanilla column order)
        ChunkInvoker invoker = (ChunkInvoker) chunk;
        for (int i = 0; i < 256; i++) {
            invoker.halite$lightGaps(i & 15, i >> 4);
        }
        this.taken++;
        return chunk;
    }

    public String getDebugString() {
        return this.taken + " ahead, " + this.generatedHere + " in place, " + this.inFlight() + " running";
    }

    private void close() {
        HaliteClientMod.logger().info("Background terrain: {}", this.getDebugString());
        this.closed = true;
        for (Entry entry : this.entries.values()) {
            entry.state.compareAndSet(QUEUED, CANCELLED);
        }
        this.entries.clear();
        this.executor.shutdownNow();
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static final class Entry {
        final int cx, cz;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        final CountDownLatch finished = new CountDownLatch(1);
        volatile Chunk chunk;
        volatile Throwable error;

        Entry(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    /** Implemented on the chunk cache by ChunkCacheMixin. */
    public interface ChunkCacheAccess {
        net.minecraft.world.chunk.ChunkSource halite$getGenerator();

        File halite$getRegionDir();
    }

    /** Implemented on chunk storages whose region folder is known (StationAPI's flattened chunk loader). */
    public interface RegionDirectory {
        File halite$getRegionDir();
    }
}
