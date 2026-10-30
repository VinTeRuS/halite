package io.github.vinterus.halite.client.render.chunk.compile;

import net.minecraft.client.render.Tessellator;

/**
 * A chunk meshing worker. Owns a private tessellator: calls that block renderers make on the global
 * {@link Tessellator#INSTANCE} from this thread are forwarded to it (see TessellatorMixin).
 */
public final class ChunkBuilderThread extends Thread {
    private final Tessellator tessellator;
    private final Object bakedModelRenderer;
    private final BlockCopies blockCopies = new BlockCopies();

    ChunkBuilderThread(Runnable task, String name, Tessellator tessellator, Object bakedModelRenderer) {
        super(task, name);
        this.tessellator = tessellator;
        this.bakedModelRenderer = bakedModelRenderer;
        this.setDaemon(true);
        this.setPriority(Thread.NORM_PRIORITY - 1);
    }

    /** This thread's own copies of blocks whose renderers rewrite the block's bounds. */
    public BlockCopies getBlockCopies() {
        return this.blockCopies;
    }

    public Tessellator getTessellator() {
        return this.tessellator;
    }

    /** This thread's private StationAPI baked model renderer, or null if none could be created. */
    public Object getBakedModelRenderer() {
        return this.bakedModelRenderer;
    }

    /** The tessellator that calls made on {@code self} from the current thread should go to, or null for itself. */
    public static Tessellator redirect(Tessellator self) {
        if (self == Tessellator.INSTANCE && Thread.currentThread() instanceof ChunkBuilderThread worker) {
            return worker.tessellator;
        }
        return null;
    }

    /** The tessellator block renderers on the current thread end up writing to. */
    public static Tessellator currentTessellator() {
        return Thread.currentThread() instanceof ChunkBuilderThread worker ? worker.tessellator : Tessellator.INSTANCE;
    }
}
