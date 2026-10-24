package io.github.vinterus.halite.client.render;

import io.github.vinterus.halite.client.HaliteClientMod;
import io.github.vinterus.halite.client.gl.GlCompat;
import io.github.vinterus.halite.client.config.HaliteOptions;
import io.github.vinterus.halite.client.render.chunk.RenderSectionManager;
import io.github.vinterus.halite.client.render.chunk.compile.ChunkBuilder;
import io.github.vinterus.halite.client.render.viewport.Viewport;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Culler;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;

/**
 * Replacement for the terrain half of the vanilla world renderer. The vanilla class delegates to this through
 * mixins; everything else (sky, entities, clouds, ...) stays vanilla.
 */
public class HaliteWorldRenderer {
    private static HaliteWorldRenderer instance;

    private final Minecraft client;

    private World world;
    private RenderSectionManager sections;
    private ChunkBuilder chunkBuilder;
    private int chunkBuilderThreads;
    private Viewport viewport;

    public HaliteWorldRenderer(Minecraft client) {
        this.client = client;
        instance = this;

        if (!GlCompat.isInitialized()) {
            GlCompat.init(HaliteClientMod.options().useVertexBufferObjects);
        }
    }

    public void setWorld(World world) {
        if (this.sections != null) {
            this.sections.destroy();
            this.sections = null;
        }

        this.world = world;
        this.viewport = null;

        if (this.chunkBuilder != null) {
            // queued snapshots belong to the old sections
            this.chunkBuilder.drainPending();
        }

        if (world != null) {
            this.updateChunkBuilder();
            this.sections = new RenderSectionManager(world);
        }
    }

    public void reload() {
        if (this.world == null) {
            return;
        }
        // throw away all GPU state and start over (fancy graphics, texture pack and render distance changes)
        this.setWorld(this.world);
    }

    public int getRenderDistance() {
        return HaliteClientMod.options().getEffectiveRenderDistance(this.client.options.viewDistance);
    }

    public void updateVisibility(Culler culler, float tickDelta) {
        LivingEntity camera = this.client.camera;
        if (this.sections == null || camera == null) {
            return;
        }

        double x = camera.lastTickX + (camera.x - camera.lastTickX) * tickDelta;
        double y = camera.lastTickY + (camera.y - camera.lastTickY) * tickDelta;
        double z = camera.lastTickZ + (camera.z - camera.lastTickZ) * tickDelta;

        this.viewport = new Viewport(culler, x, y, z);
        this.sections.update(this.viewport, this.getRenderDistance());
    }

    public boolean buildChunks() {
        if (this.sections == null) {
            return true;
        }
        long budget = HaliteClientMod.options().chunkBuildBudgetMs * 1_000_000L;
        return this.sections.buildChunks(budget, this.chunkBuilder);
    }

    /** Starts or stops the worker pool to match the options. Only called while no world is loaded. */
    private void updateChunkBuilder() {
        HaliteOptions options = HaliteClientMod.options();
        int wanted = !options.useAsyncChunkBuilding ? 0
                : options.chunkBuilderThreads > 0 ? options.chunkBuilderThreads : ChunkBuilder.defaultThreadCount();

        if (wanted == this.chunkBuilderThreads && (wanted == 0 || this.chunkBuilder != null)) {
            return;
        }

        if (this.chunkBuilder != null) {
            this.chunkBuilder.shutdown();
            this.chunkBuilder = null;
        }

        this.chunkBuilderThreads = wanted;
        if (wanted > 0) {
            this.chunkBuilder = ChunkBuilder.create(wanted);
        }
    }

    public String getChunkBuilderDescription() {
        return this.chunkBuilder == null ? "render thread" : this.chunkBuilder.getThreadCount() + " thread(s)";
    }

    public int renderLayer(int layer) {
        if (this.sections == null || this.viewport == null) {
            return 0;
        }
        return this.sections.renderLayer(layer, this.viewport);
    }

    public void markDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (this.sections != null) {
            this.sections.markDirty(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    public void onAmbientDarknessChanged() {
        if (this.sections != null) {
            this.sections.markSkyLightSectionsDirty();
        }
    }

    /** The renderer of the running client, or null before the world renderer exists. */
    public static HaliteWorldRenderer instance() {
        return instance;
    }

    /** Frustum test against the last frame's camera; true when no frame has been culled yet. */
    public boolean isPointVisible(double x, double y, double z, double radius) {
        Viewport viewport = this.viewport;
        return viewport == null || viewport.isBoxVisible(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
    }

    public boolean isEntityVisible(Box box) {
        if (this.sections == null || !HaliteClientMod.options().useEntityCulling) {
            return true;
        }
        return this.sections.isBoxVisible(box);
    }

    /** Block entities to render this frame: those in visible sections, or all of them with culling off. */
    public List<BlockEntity> getBlockEntitiesToRender() {
        if (this.sections == null) {
            return List.of();
        }
        return HaliteClientMod.options().useBlockEntityCulling
                ? this.sections.getVisibleBlockEntities()
                : this.sections.getAllBlockEntities();
    }

    public boolean isIdle() {
        return this.sections == null || this.sections.isIdle();
    }

    public String getDebugString() {
        return this.sections == null ? "Halite: no world" : this.sections.getDebugString();
    }

    public void destroy() {
        this.setWorld(null);
    }
}
