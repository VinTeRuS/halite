package io.github.vinterus.halite.client.compat;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * UniTweaks replaces the vanilla render distance button with its own 2-32 chunk slider and keeps the value in its
 * own options, leaving {@code GameOptions.viewDistance} untouched. When that slider is enabled, Halite's "vanilla"
 * render distance follows it instead. Accessed reflectively so UniTweaks stays an optional dependency.
 */
public final class UniTweaksCompat {
    private static Object videoConfig;
    private static Field sliderEnabled;
    private static Method gameRendererChunks;

    static {
        if (FabricLoader.getInstance().isModLoaded("unitweaks")) {
            try {
                Class<?> mod = Class.forName("net.danygames2014.unitweaks.UniTweaks");
                Object uiConfig = mod.getField("USER_INTERFACE_CONFIG").get(null);
                Object video = uiConfig.getClass().getField("videoSettingsConfig").get(uiConfig);
                sliderEnabled = video.getClass().getField("renderDistanceSlider");
                gameRendererChunks = Class.forName("net.danygames2014.unitweaks.util.ModOptions")
                        .getMethod("getGameRendererChunks");
                videoConfig = video;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                HaliteClientMod.logger().warn("UniTweaks found but its render distance could not be read", e);
            }
        }
    }

    private UniTweaksCompat() {
    }

    /** UniTweaks' render distance in chunks, or -1 when UniTweaks is absent or its slider is off. */
    public static int getRenderDistance() {
        if (videoConfig == null) {
            return -1;
        }
        try {
            if (!Boolean.TRUE.equals(sliderEnabled.get(videoConfig))) {
                return -1;
            }
            return (Integer) gameRendererChunks.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            HaliteClientMod.logger().warn("Could not read the UniTweaks render distance, falling back to vanilla", e);
            videoConfig = null;
            return -1;
        }
    }
}
