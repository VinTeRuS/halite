package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.render.chunk.RenderSection;
import io.github.vinterus.halite.client.render.chunk.RenderSectionManager;
import io.github.vinterus.halite.client.render.chunk.occlusion.VisGraph;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.Tessellator;

import java.util.ArrayList;
import java.util.List;

/**
 * One section rebuild handed to a chunk builder thread. Inputs are immutable; outputs are written by the worker
 * and read by the render thread after the task comes back through the completion queue.
 */
public final class ChunkBuildTask {
    final Object owner;
    final RenderSection section;
    final int version;
    final WorldSlice slice;
    final byte[] modeTable;
    final int bottomY, topY;

    final ChunkMeshBuilder[] builders = new ChunkMeshBuilder[RenderSection.PASS_COUNT];
    final List<BlockEntity> blockEntities = new ArrayList<>();
    final IntArrayList deferred = new IntArrayList();
    long visibilityData;
    Throwable error;

    public ChunkBuildTask(Object owner, RenderSection section, int version, WorldSlice slice, byte[] modeTable,
                          int bottomY, int topY) {
        this.owner = owner;
        this.section = section;
        this.version = version;
        this.slice = slice;
        this.modeTable = modeTable;
        this.bottomY = bottomY;
        this.topY = topY;
    }

    /** Runs on a chunk builder thread. */
    void run(ChunkBuilderThread thread) {
        for (int i = 0; i < this.builders.length; i++) {
            this.builders[i] = ChunkMeshBuilder.acquire();
        }
        RenderSectionManager.setRegionOffset(this.builders, this.section);

        this.slice.prepare();
        VisGraph visGraph = thread.getVisGraph();
        visGraph.reset();
        ChunkSectionCompiler.meshSection(this.slice,
                this.section.getOriginX(), this.section.getOriginY(), this.section.getOriginZ(), this.bottomY, this.topY,
                thread.getTessellator(), this.builders, visGraph, this.blockEntities, this.modeTable, thread.getBlockCopies(),
                this.deferred);
        this.visibilityData = visGraph.resolve();
    }

    /** Renders the blocks the worker had to skip; runs on the render thread. */
    public void finishOnRenderThread() {
        if (!this.deferred.isEmpty()) {
            ChunkSectionCompiler.meshDeferred(this.slice,
                    this.section.getOriginX(), this.section.getOriginY(), this.section.getOriginZ(),
                    Tessellator.INSTANCE, this.builders, this.deferred);
        }
    }

    /** The section manager that submitted this task; results for a discarded manager are dropped. */
    public Object getOwner() {
        return this.owner;
    }

    public RenderSection getSection() {
        return this.section;
    }

    public int getVersion() {
        return this.version;
    }

    public Throwable getError() {
        return this.error;
    }

    public ChunkMeshBuilder[] getBuilders() {
        return this.builders;
    }

    public List<BlockEntity> getBlockEntities() {
        return this.blockEntities;
    }

    public long getVisibilityData() {
        return this.visibilityData;
    }

    public boolean usesSkyLight() {
        return this.slice.usesSkyLight();
    }

    /** Hands the slice's arrays and the mesh builders back for reuse once the result has been applied. Render thread. */
    public void release() {
        this.slice.release();
        for (int i = 0; i < this.builders.length; i++) {
            if (this.builders[i] != null) {
                ChunkMeshBuilder.release(this.builders[i]);
                this.builders[i] = null;
            }
        }
    }
}
