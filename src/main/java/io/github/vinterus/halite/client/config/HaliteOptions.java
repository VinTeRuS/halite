package io.github.vinterus.halite.client.config;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * User options, stored as a plain properties file in the config directory (no extra libraries needed).
 */
public class HaliteOptions {
    /** Render distance in chunks; 0 follows the vanilla "Render Distance" button. */
    public int renderDistance = 0;
    /** Entities further away than this many blocks are not drawn; 0 keeps vanilla's size-based limit only. */
    public int entityRenderDistance = 0;
    /** Block entities further away than this many blocks are not drawn; 0 keeps vanilla's 64 blocks. */
    public int blockEntityRenderDistance = 0;
    public boolean useOcclusionCulling = true;
    public boolean useFaceCulling = true;
    public boolean useEntityCulling = true;
    public boolean useBlockEntityCulling = true;
    public boolean useTranslucencySorting = true;
    public boolean useParticleCulling = true;
    public boolean useCachedClouds = true;
    public boolean useAnimationCulling = true;
    /** Buffer objects; disable to fall back to client-side vertex arrays on broken drivers. */
    public boolean useVertexBufferObjects = true;
    /** Build chunk meshes on background threads. */
    public boolean useAsyncChunkBuilding = true;
    /** Number of chunk builder threads; 0 picks a count from the CPU core count. */
    public int chunkBuilderThreads = 0;
    /** Milliseconds per frame spent rebuilding chunk meshes. */
    public int chunkBuildBudgetMs = 4;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("halite.properties");
    }

    public static HaliteOptions load() {
        HaliteOptions options = new HaliteOptions();
        Path path = path();

        if (Files.exists(path)) {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(path)) {
                props.load(reader);
                options.renderDistance = getInt(props, "renderDistance", options.renderDistance, 0, 32);
                options.entityRenderDistance = getInt(props, "entityRenderDistance", options.entityRenderDistance, 0, 256);
                options.blockEntityRenderDistance = getInt(props, "blockEntityRenderDistance", options.blockEntityRenderDistance, 0, 64);
                options.useOcclusionCulling = getBool(props, "useOcclusionCulling", options.useOcclusionCulling);
                options.useFaceCulling = getBool(props, "useFaceCulling", options.useFaceCulling);
                options.useEntityCulling = getBool(props, "useEntityCulling", options.useEntityCulling);
                options.useBlockEntityCulling = getBool(props, "useBlockEntityCulling", options.useBlockEntityCulling);
                options.useTranslucencySorting = getBool(props, "useTranslucencySorting", options.useTranslucencySorting);
                options.useParticleCulling = getBool(props, "useParticleCulling", options.useParticleCulling);
                options.useCachedClouds = getBool(props, "useCachedClouds", options.useCachedClouds);
                options.useAnimationCulling = getBool(props, "useAnimationCulling", options.useAnimationCulling);
                options.useVertexBufferObjects = getBool(props, "useVertexBufferObjects", options.useVertexBufferObjects);
                options.chunkBuildBudgetMs = getInt(props, "chunkBuildBudgetMs", options.chunkBuildBudgetMs, 1, 50);
                options.useAsyncChunkBuilding = getBool(props, "useAsyncChunkBuilding", options.useAsyncChunkBuilding);
                options.chunkBuilderThreads = getInt(props, "chunkBuilderThreads", options.chunkBuilderThreads, 0, 16);
            } catch (IOException | RuntimeException e) {
                HaliteClientMod.logger().warn("Could not read {}, using defaults", path, e);
            }
        }

        options.save();
        return options;
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("renderDistance", Integer.toString(this.renderDistance));
        props.setProperty("entityRenderDistance", Integer.toString(this.entityRenderDistance));
        props.setProperty("blockEntityRenderDistance", Integer.toString(this.blockEntityRenderDistance));
        props.setProperty("useOcclusionCulling", Boolean.toString(this.useOcclusionCulling));
        props.setProperty("useFaceCulling", Boolean.toString(this.useFaceCulling));
        props.setProperty("useEntityCulling", Boolean.toString(this.useEntityCulling));
        props.setProperty("useBlockEntityCulling", Boolean.toString(this.useBlockEntityCulling));
        props.setProperty("useTranslucencySorting", Boolean.toString(this.useTranslucencySorting));
        props.setProperty("useParticleCulling", Boolean.toString(this.useParticleCulling));
        props.setProperty("useCachedClouds", Boolean.toString(this.useCachedClouds));
        props.setProperty("useAnimationCulling", Boolean.toString(this.useAnimationCulling));
        props.setProperty("useVertexBufferObjects", Boolean.toString(this.useVertexBufferObjects));
        props.setProperty("chunkBuildBudgetMs", Integer.toString(this.chunkBuildBudgetMs));
        props.setProperty("useAsyncChunkBuilding", Boolean.toString(this.useAsyncChunkBuilding));
        props.setProperty("chunkBuilderThreads", Integer.toString(this.chunkBuilderThreads));

        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                props.store(writer, "Halite options. renderDistance=0 follows the vanilla setting.");
            }
        } catch (IOException e) {
            HaliteClientMod.logger().warn("Could not write {}", path, e);
        }
    }

    /** Resolves the effective render distance in chunks. */
    public int getEffectiveRenderDistance(int vanillaViewDistance) {
        if (this.renderDistance > 0) {
            return this.renderDistance;
        }
        return switch (vanillaViewDistance) {
            // matches vanilla's fog distance of 256 >> viewDistance blocks
            case 0 -> 16; // far
            case 1 -> 8;  // normal
            case 2 -> 4;  // short
            default -> 2; // tiny
        };
    }

    private static int getInt(Properties props, String key, int def, int min, int max) {
        String value = props.getProperty(key);
        if (value == null) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean getBool(Properties props, String key, boolean def) {
        String value = props.getProperty(key);
        return value == null ? def : Boolean.parseBoolean(value.trim());
    }
}
