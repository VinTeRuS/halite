package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.HaliteClientMod;
import io.github.vinterus.halite.mixin.core.TessellatorInvoker;
import net.minecraft.client.render.Tessellator;
import net.modificationstation.stationapi.api.client.render.RendererAccess;
import net.modificationstation.stationapi.impl.client.arsenic.renderer.render.BakedModelRendererImpl;
import net.modificationstation.stationapi.impl.client.render.StationTessellatorImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Pool of chunk builder threads. Tasks are submitted and collected on the render thread.
 */
public final class ChunkBuilder {
    // the vanilla tessellator holds 2M ints; workers flush far more often, so a quarter of that is plenty
    private static final int WORKER_TESSELLATOR_SIZE = 524288;

    private final LinkedBlockingQueue<ChunkBuildTask> pending = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<ChunkBuildTask> completed = new ConcurrentLinkedQueue<>();
    private final List<ChunkBuilderThread> threads = new ArrayList<>();
    private volatile boolean running = true;

    private ChunkBuilder() {
    }

    /**
     * Starts the worker threads, or returns null if blocks cannot be meshed off the render thread with the
     * installed StationAPI version. Must be called on the render thread.
     */
    public static ChunkBuilder create(int threadCount) {
        if (!isSupported()) {
            return null;
        }

        ChunkBuilder builder = new ChunkBuilder();
        for (int i = 0; i < threadCount; i++) {
            Tessellator tessellator = TessellatorInvoker.halite$create(WORKER_TESSELLATOR_SIZE);
            Object modelRenderer = RendererAccess.INSTANCE.hasRenderer() ? new BakedModelRendererImpl() : null;
            ChunkBuilderThread thread = new ChunkBuilderThread(builder::workerLoop, "Halite Chunk Builder " + i,
                    tessellator, modelRenderer);
            builder.threads.add(thread);
        }
        builder.threads.forEach(Thread::start);

        HaliteClientMod.logger().info("Started {} chunk builder thread(s)", threadCount);
        return builder;
    }

    private static boolean isSupported() {
        try {
            if (!ThreadAwareRenderer.class.isAssignableFrom(BakedModelRendererImpl.class)
                    || !ThreadAwareRenderer.class.isAssignableFrom(StationTessellatorImpl.class)) {
                HaliteClientMod.logger().warn("StationAPI renderer hooks are not installed; chunks will be built on the render thread");
                return false;
            }
            if (RendererAccess.INSTANCE.hasRenderer()
                    && !(RendererAccess.INSTANCE.getRenderer().bakedModelRenderer() instanceof BakedModelRendererImpl)) {
                HaliteClientMod.logger().warn("Unknown StationAPI renderer implementation; chunks will be built on the render thread");
                return false;
            }
            return true;
        } catch (LinkageError e) {
            HaliteClientMod.logger().warn("StationAPI renderer internals not found; chunks will be built on the render thread", e);
            return false;
        }
    }

    public static int defaultThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min((cores - 1) / 2, 3));
    }

    private void workerLoop() {
        ChunkBuilderThread self = (ChunkBuilderThread) Thread.currentThread();

        while (this.running) {
            ChunkBuildTask task;
            try {
                task = this.pending.take();
            } catch (InterruptedException e) {
                return;
            }

            try {
                task.run(self);
            } catch (Throwable t) {
                task.error = t;
            }
            this.completed.add(task);
        }
    }

    public void submit(ChunkBuildTask task) {
        this.pending.add(task);
    }

    public ChunkBuildTask pollCompleted() {
        return this.completed.poll();
    }

    /** Drops tasks that have not started; their sections stay dirty. */
    public List<ChunkBuildTask> drainPending() {
        List<ChunkBuildTask> drained = new ArrayList<>();
        this.pending.drainTo(drained);
        return drained;
    }

    public int getThreadCount() {
        return this.threads.size();
    }

    public void shutdown() {
        this.running = false;
        this.pending.clear();
        for (Thread thread : this.threads) {
            thread.interrupt();
        }
        this.threads.clear();
    }
}
