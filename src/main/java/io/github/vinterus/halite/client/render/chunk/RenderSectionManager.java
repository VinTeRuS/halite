package io.github.vinterus.halite.client.render.chunk;

import io.github.vinterus.halite.client.HaliteClientMod;
import io.github.vinterus.halite.client.config.HaliteOptions;
import io.github.vinterus.halite.client.render.chunk.compile.ChunkSectionCompiler;
import io.github.vinterus.halite.client.render.chunk.occlusion.OcclusionCuller;
import io.github.vinterus.halite.client.render.viewport.Viewport;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

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
    private final ChunkSectionCompiler compiler = new ChunkSectionCompiler();
    private final ChunkRenderer renderer = new ChunkRenderer();

    @SuppressWarnings("unchecked")
    private final List<RenderSection>[] renderLists = new List[RenderSection.PASS_COUNT];
    private final List<RenderSection> rebuildQueue = new ArrayList<>();
    private final List<BlockEntity> visibleBlockEntities = new ArrayList<>();

    private int frame;
    private int visibleSections;
    private int builtThisFrame;
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
     * Rebuilds dirty visible sections, nearest first (the search visits them in that order), until the time budget
     * is used up.
     *
     * @return whether every visible section is up to date
     */
    public boolean buildChunks(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        this.builtThisFrame = 0;

        for (int i = 0; i < this.rebuildQueue.size(); i++) {
            RenderSection section = this.rebuildQueue.get(i);
            if (!section.isDirty() || this.storage.get(section.getChunkX(), section.getChunkY(), section.getChunkZ()) != section) {
                continue;
            }

            try {
                this.compiler.compile(this.world, section);
            } catch (RuntimeException e) {
                HaliteClientMod.logger().error("Failed to build {}", section, e);
                section.setBuildResult(new ChunkMesh[RenderSection.PASS_COUNT], List.of(), section.getVisibilityData(), false);
            }

            this.builtThisFrame++;
            this.rebuildQueue.set(i, null);

            if (System.nanoTime() >= deadline) {
                break;
            }
        }

        this.rebuildQueue.removeIf(s -> s == null || !s.isDirty());
        return this.rebuildQueue.isEmpty();
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
        return String.format("C: %d/%d (%d visible) D: %d, B: %d, Q: %d",
                this.renderLists[RenderSection.PASS_SOLID].size() + this.renderLists[RenderSection.PASS_TRANSLUCENT].size(),
                this.storage.size(), this.visibleSections, this.renderer.getDrawCalls(), this.builtThisFrame,
                this.rebuildQueue.size());
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
