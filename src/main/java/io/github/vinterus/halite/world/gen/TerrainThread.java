package io.github.vinterus.halite.world.gen;

import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.OverworldChunkGenerator;

/**
 * A background terrain generation thread. Each one has its own generator and biome source, so the vanilla code it
 * runs only ever touches state of its own; see {@link TerrainPregen} for what is shared and how that is guarded.
 */
public final class TerrainThread extends Thread {
    World world;
    OverworldChunkGenerator generator;
    BiomeSource biomeSource;
    final WorkerNoiseHeights noiseHeights = new WorkerNoiseHeights();

    TerrainThread(Runnable task, String name) {
        super(task, name);
        this.setDaemon(true);
        this.setPriority(Thread.NORM_PRIORITY - 1);
    }

    /** This thread's biome source while it generates terrain for {@code world}, otherwise null. */
    public static BiomeSource biomeSourceFor(World world) {
        return Thread.currentThread() instanceof TerrainThread thread && thread.world == world ? thread.biomeSource : null;
    }

    public static boolean isCurrent() {
        return Thread.currentThread() instanceof TerrainThread;
    }

    public static WorkerNoiseHeights noiseHeights() {
        return ((TerrainThread) Thread.currentThread()).noiseHeights;
    }

    void prepare(World world) {
        if (this.world != world) {
            this.world = world;
            // the biome source first: the generator's constructor may already ask the world for it
            this.biomeSource = new BiomeSource(world);
            this.generator = new OverworldChunkGenerator(world, world.getSeed());
            this.noiseHeights.reset();
        }
    }
}
