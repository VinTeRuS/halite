package io.github.vinterus.halite.client.render.chunk;

import io.github.vinterus.halite.client.HaliteClientMod;
import io.github.vinterus.halite.client.config.HaliteOptions;
import io.github.vinterus.halite.client.render.chunk.compile.BlockSafety;
import io.github.vinterus.halite.client.render.chunk.compile.ChunkBuildTask;
import io.github.vinterus.halite.client.render.chunk.compile.ChunkBuilder;
import io.github.vinterus.halite.client.render.chunk.compile.ChunkMeshBuilder;
import io.github.vinterus.halite.client.render.chunk.compile.ChunkSectionCompiler;
import io.github.vinterus.halite.client.render.chunk.compile.SectionEmptinessCheck;
import io.github.vinterus.halite.client.render.chunk.compile.WorldSlice;
import io.github.vinterus.halite.client.render.chunk.occlusion.OcclusionCuller;
import io.github.vinterus.halite.client.render.chunk.occlusion.VisGraph;
import io.github.vinterus.halite.client.render.chunk.occlusion.VisibilityEncoding;
import io.github.vinterus.halite.client.render.viewport.Viewport;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.Tessellator;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.minecraft.world.WorldRegion;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns all render sections of a world: keeps them in range of the camera, runs the visibility search each frame,
 * schedules rebuilds and hands the visible lists to the {@link ChunkRenderer}.
 */
public class RenderSectionManager {
    private final World world;
    private final SectionStorage storage = new SectionStorage();
    private final OcclusionCuller culler = new OcclusionCuller(this.storage);
    private final ChunkMeshBuilder[] syncBuilders = {new ChunkMeshBuilder(), new ChunkMeshBuilder()};
    private final VisGraph syncVisGraph = new VisGraph();
    private final ChunkRenderer renderer = new ChunkRenderer();

    @SuppressWarnings("unchecked")
    private final List<RenderSection>[] renderLists = new List[RenderSection.PASS_COUNT];
    private final List<RenderSection> rebuildQueue = new ArrayList<>();
    private final List<BlockEntity> visibleBlockEntities = new ArrayList<>();

    private int frame;
    private int visibleSections;
    private int builtThisFrame;
    private long buildNanosTotal;
    private long buildCountTotal;
    private int inFlight;
    private int reportedWorkerErrors;
    private Viewport lastViewport;

    public RenderSectionManager(World world) {
        this.world = world;
        for (int i = 0; i < this.renderLists.length; i++) {
            this.renderLists[i] = new ArrayList<>();
        }
    }

    /** Updates the set of loaded sections and finds what is visible this frame. */
    public void update(Viewport viewport, int renderDistance) {
        HaliteOptions options = HaliteClientMod.options();

        this.lastViewport = viewport;
        this.storage.update(viewport.chunkX, viewport.chunkZ, renderDistance,
                this.world.getBottomSectionCoord(), this.world.getTopSectionCoord());

        this.frame++;
        for (List<RenderSection> list : this.renderLists) {
            list.clear();
        }
        this.rebuildQueue.clear();
        this.visibleBlockEntities.clear();
        this.visibleSections = 0;

        final int frame = this.frame;
        this.culler.findVisible((section, visible) -> {
            if (!visible) {
                return;
            }

            section.setVisibleFrame(frame);
            this.visibleSections++;

            if (section.isDirty()) {
                this.rebuildQueue.add(section);
            }

            for (int pass = 0; pass < RenderSection.PASS_COUNT; pass++) {
                if (section.getMesh(pass) != null) {
                    this.renderLists[pass].add(section);
                }
            }

            this.visibleBlockEntities.addAll(section.getBlockEntities());
        }, viewport, renderDistance * 16.0f, options.useOcclusionCulling, frame,
                this.world.getBottomSectionCoord(), this.world.getTopSectionCoord());
    }

    /**
     * Rebuilds dirty visible sections, nearest first (the search visits them in that order). With a chunk builder,
     * finished builds are collected and uploaded and new ones are snapshotted and handed to the workers; without
     * one, sections are meshed right here. Both stop once the time budget is used up.
     *
     * @return whether every visible section is up to date
     */
    public boolean buildChunks(long budgetNanos, ChunkBuilder builder) {
        long deadline = System.nanoTime() + budgetNanos;
        this.builtThisFrame = 0;

        if (builder != null) {
            ChunkBuildTask task;
            while ((task = builder.pollCompleted()) != null) {
                if (task.getOwner() != this) {
                    continue;
                }
                this.inFlight--;
                this.applyTask(task);
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
        }

        boolean[] safeTable = builder != null ? BlockSafety.getSafeTable() : null;
        int maxInFlight = builder != null ? builder.getThreadCount() * 3 : 0;

        for (int i = 0; i < this.rebuildQueue.size(); i++) {
            RenderSection section = this.rebuildQueue.get(i);
            if (!section.isDirty() || section.isBuilding() || section.isDeleted()) {
                continue;
            }

            // keep the workers fed even when the frame budget is spent
            boolean overBudget = System.nanoTime() >= deadline;
            if (overBudget && (builder == null || this.inFlight >= builder.getThreadCount())) {
                break;
            }
            if (builder != null && this.inFlight >= maxInFlight) {
                break;
            }

            long start = System.nanoTime();
            if (this.tryBuildEmpty(section)) {
                continue;
            }

            if (builder != null) {
                WorldSlice slice = WorldSlice.create(this.world, section.getChunkX(), section.getChunkY(), section.getChunkZ());
                builder.submit(new ChunkBuildTask(this, section, section.getVersion(), slice, safeTable,
                        this.world.getBottomY(), this.world.getTopY()));
                section.setBuilding(true);
                this.inFlight++;
            } else {
                this.compileSync(section);
                this.builtThisFrame++;
            }
            this.recordMainThreadTime(start);
        }

        this.rebuildQueue.removeIf(s -> !s.isDirty() || s.isDeleted());
        return this.rebuildQueue.isEmpty() && this.inFlight == 0;
    }

    private boolean tryBuildEmpty(RenderSection section) {
        if (!SectionEmptinessCheck.isEmpty(this.world, section.getChunkX(), section.getChunkY(), section.getChunkZ())) {
            return false;
        }
        section.setBuildResult(new ChunkMesh[RenderSection.PASS_COUNT], List.of(), VisibilityEncoding.ALL, false,
                section.getVersion());
        return true;
    }

    private void applyTask(ChunkBuildTask task) {
        RenderSection section = task.getSection();
        section.setBuilding(false);

        if (section.isDeleted()) {
            return;
        }

        long start = System.nanoTime();
        if (task.getError() != null) {
            // most likely a modded block renderer that isn't safe off-thread; retry on the render thread
            if (this.reportedWorkerErrors++ < 5) {
                HaliteClientMod.logger().warn("Building {} on a worker thread failed, building on the render thread instead",
                        section, task.getError());
            }
            this.compileSync(section);
        } else {
            task.finishOnRenderThread();
            section.setBuildResult(upload(task.getBuilders()), task.getBlockEntities(), task.getVisibilityData(),
                    task.usesSkyLight(), task.getVersion());
        }
        this.builtThisFrame++;
        this.recordMainThreadTime(start);
    }

    private void compileSync(RenderSection section) {
        int version = section.getVersion();
        int minX = section.getOriginX(), minY = section.getOriginY(), minZ = section.getOriginZ();

        try {
            Chunk.hasSkyLight = false;
            WorldRegion region = new WorldRegion(this.world, minX - 1, minY - 1, minZ - 1, minX + 17, minY + 17, minZ + 17);

            for (ChunkMeshBuilder builder : this.syncBuilders) {
                builder.reset();
            }
            this.syncVisGraph.reset();
            List<BlockEntity> blockEntities = new ArrayList<>();

            ChunkSectionCompiler.meshSection(region, minX, minY, minZ, this.world.getBottomY(), this.world.getTopY(),
                    Tessellator.INSTANCE, this.syncBuilders, this.syncVisGraph, blockEntities, null, null);

            section.setBuildResult(upload(this.syncBuilders), blockEntities, this.syncVisGraph.resolve(),
                    Chunk.hasSkyLight, version);
        } catch (RuntimeException e) {
            HaliteClientMod.logger().error("Failed to build {}", section, e);
            section.setBuildResult(new ChunkMesh[RenderSection.PASS_COUNT], List.of(), section.getVisibilityData(),
                    false, version);
        }
    }

    private static ChunkMesh[] upload(ChunkMeshBuilder[] builders) {
        ChunkMesh[] meshes = new ChunkMesh[RenderSection.PASS_COUNT];
        for (int pass = 0; pass < RenderSection.PASS_COUNT; pass++) {
            ChunkMeshBuilder.BuiltMesh built = builders[pass].build(pass == RenderSection.PASS_TRANSLUCENT);
            if (built != null) {
                meshes[pass] = new ChunkMesh(built);
            }
        }
        return meshes;
    }

    private void recordMainThreadTime(long start) {
        this.buildNanosTotal += System.nanoTime() - start;
        this.buildCountTotal++;
    }

    public int renderLayer(int pass, Viewport viewport) {
        HaliteOptions options = HaliteClientMod.options();
        if (pass == RenderSection.PASS_SOLID) {
            this.renderer.resetStats();
        }
        return this.renderer.render(this.renderLists[pass], pass, viewport, options.useFaceCulling,
                options.useTranslucencySorting);
    }

    public void markDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int x0 = minX >> 4, y0 = minY >> 4, z0 = minZ >> 4;
        int x1 = maxX >> 4, y1 = maxY >> 4, z1 = maxZ >> 4;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    RenderSection section = this.storage.get(x, y, z);
                    if (section != null) {
                        section.markDirty();
                    }
                }
            }
        }
    }

    public void markSkyLightSectionsDirty() {
        this.storage.forEach(section -> {
            if (section.usesSkyLight()) {
                section.markDirty();
            }
        });
    }

    public void markAllDirty() {
        this.storage.forEach(RenderSection::markDirty);
    }

    /** Whether any section overlapping the box was visible in the last search. */
    public boolean isBoxVisible(Box box) {
        int x0 = ((int) Math.floor(box.minX - 0.5)) >> 4, x1 = ((int) Math.floor(box.maxX + 0.5)) >> 4;
        int y0 = ((int) Math.floor(box.minY - 0.5)) >> 4, y1 = ((int) Math.floor(box.maxY + 0.5)) >> 4;
        int z0 = ((int) Math.floor(box.minZ - 0.5)) >> 4, z1 = ((int) Math.floor(box.maxZ + 0.5)) >> 4;

        int bottom = this.world.getBottomSectionCoord(), top = this.world.getTopSectionCoord();
        if (y1 < bottom || y0 >= top) {
            // outside the world's sections, nothing to occlude it
            return true;
        }
        y0 = Math.max(y0, bottom);
        y1 = Math.min(y1, top - 1);

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    RenderSection section = this.storage.get(x, y, z);
                    if (section == null || section.isVisibleInFrame(this.frame)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether nothing visible is waiting to be rebuilt. */
    public boolean isIdle() {
        return this.rebuildQueue.isEmpty() && this.inFlight == 0;
    }

    public List<BlockEntity> getVisibleBlockEntities() {
        return this.visibleBlockEntities;
    }

    /** Block entities of every built section, regardless of visibility. */
    public List<BlockEntity> getAllBlockEntities() {
        List<BlockEntity> all = new ArrayList<>();
        this.storage.forEach(section -> all.addAll(section.getBlockEntities()));
        return all;
    }

    public int getRenderedSectionCount(int pass) {
        return this.renderLists[pass].size();
    }

    public String getDebugString() {
        double avgBuildMs = this.buildCountTotal == 0 ? 0.0 : this.buildNanosTotal / 1.0e6 / this.buildCountTotal;
        return String.format("C: %d/%d (%d visible) D: %d, B: %d (%.2f ms main), Q: %d, W: %d",
                this.renderLists[RenderSection.PASS_SOLID].size() + this.renderLists[RenderSection.PASS_TRANSLUCENT].size(),
                this.storage.size(), this.visibleSections, this.renderer.getDrawCalls(), this.builtThisFrame,
                avgBuildMs, this.rebuildQueue.size(), this.inFlight);
    }

    public void destroy() {
        this.storage.clear();
        this.renderer.delete();
        for (List<RenderSection> list : this.renderLists) {
            list.clear();
        }
        this.rebuildQueue.clear();
        this.visibleBlockEntities.clear();
    }
}
