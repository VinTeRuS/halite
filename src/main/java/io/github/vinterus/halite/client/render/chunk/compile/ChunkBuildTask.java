package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.render.chunk.RenderSection;
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
    final boolean[] safeTable;
    final int bottomY, topY;

    final ChunkMeshBuilder[] builders = new ChunkMeshBuilder[RenderSection.PASS_COUNT];
    final List<BlockEntity> blockEntities = new ArrayList<>();
    final IntArrayList deferred = new IntArrayList();
    long visibilityData;
    Throwable error;

    public ChunkBuildTask(Object owner, RenderSection section, int version, WorldSlice slice, boolean[] safeTable,
                          int bottomY, int topY) {
        this.owner = owner;
        this.section = section;
        this.version = version;
        this.slice = slice;
        this.safeTable = safeTable;
        this.bottomY = bottomY;
        this.topY = topY;
    }

    /** Runs on a chunk builder thread. */
    void run(Tessellator tessellator) {
        for (int i = 0; i < this.builders.length; i++) {
            this.builders[i] = new ChunkMeshBuilder();
        }

        VisGraph visGraph = new VisGraph();
        ChunkSectionCompiler.meshSection(this.slice,
                this.section.getOriginX(), this.section.getOriginY(), this.section.getOriginZ(), this.bottomY, this.topY,
                tessellator, this.builders, visGraph, this.blockEntities, this.safeTable, this.deferred);
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
}
