package io.github.vinterus.halite.client.render.texture;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.minecraft.client.render.texture.DynamicTexture;
import net.modificationstation.stationapi.api.client.texture.atlas.Atlas;
import net.modificationstation.stationapi.api.client.texture.binder.StaticReferenceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tracks which animated block textures are on screen, so the animations of the others can be skipped.
 *
 * <p>Every animated texture tick re-simulates the animation on the CPU and writes the frame into the block atlas.
 * On mobile GPUs a write to a texture that is in use can force the driver to copy the whole atlas. Meshes record
 * which tracked sprites their quads use (by texture coordinates); the sprites of visible sections are animated,
 * the rest are frozen until they come into view.
 *
 * <p>Only the block animations StationAPI ships (water, lava, fire, portal) are tracked. Other animated textures
 * live in the same atlas as items, which may be on screen in the hand or a GUI without any chunk showing them.
 */
public final class AnimatedSprites {
    private static final Set<String> TRACKED_BINDERS = Set.of(
            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render.binder.ArsenicStillWater",
            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render.binder.ArsenicFlowingWater",
            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render.binder.ArsenicStillLava",
            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render.binder.ArsenicFlowingLava",
            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render.binder.ArsenicFire",
            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render.binder.ArsenicPortal");
    private static final String FIRE_BINDER =
            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render.binder.ArsenicFire";

    /** An animated sprite's area in the atlas and its bit in visibility masks. */
    public record Tracked(DynamicTexture binder, float minU, float minV, float maxU, float maxV, int bit, boolean fire) {
        public boolean contains(float u, float v) {
            return u >= this.minU && u < this.maxU && v >= this.minV && v < this.maxV;
        }
    }

    private static volatile Tracked[] tracked = new Tracked[0];
    private static List<?> trackedSource;
    private static int trackedSourceSize = -1;

    // -1: nothing is known yet, animate everything
    private static int visibleMask = -1;
    private static boolean entitiesOnFire;
    private static boolean failed;

    private AnimatedSprites() {
    }

    /** The sprites to look for while meshing; safe to read from any thread. */
    public static Tracked[] current() {
        return tracked;
    }

    /** Re-reads sprite positions when the binder list changes. Render thread. */
    public static void refresh(List<?> dynamicTextures) {
        if (failed || (dynamicTextures == trackedSource && dynamicTextures.size() == trackedSourceSize)) {
            return;
        }
        trackedSource = dynamicTextures;
        trackedSourceSize = dynamicTextures.size();
        rebuild();
    }

    /** Call after a resource reload, which moves sprites around in the atlas. */
    public static void invalidate() {
        trackedSourceSize = -1;
        visibleMask = -1;
    }

    private static void rebuild() {
        try {
            List<Tracked> list = new ArrayList<>();
            for (Object entry : trackedSource) {
                if (!(entry instanceof DynamicTexture binder) || !(entry instanceof StaticReferenceProvider provider)
                        || !TRACKED_BINDERS.contains(entry.getClass().getName()) || list.size() >= 31) {
                    continue;
                }
                Atlas.Sprite sprite = provider.getStaticReference();
                list.add(new Tracked(binder, (float) sprite.getStartU(), (float) sprite.getStartV(),
                        (float) sprite.getEndU(), (float) sprite.getEndV(), 1 << list.size(),
                        FIRE_BINDER.equals(entry.getClass().getName())));
            }
            tracked = list.toArray(new Tracked[0]);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            tracked = new Tracked[0];
            HaliteClientMod.logger().warn("Could not track animated textures; all of them will keep animating", e);
        }
    }

    /** Bits of the tracked sprites a quad with this texture coordinate uses. */
    public static int maskFor(Tracked[] sprites, float u, float v) {
        int mask = 0;
        for (Tracked sprite : sprites) {
            if (sprite.contains(u, v)) {
                mask |= sprite.bit();
            }
        }
        return mask;
    }

    /** "animated/tracked" for the debug overlay. */
    public static String describe() {
        Tracked[] sprites = tracked;
        int animated = 0;
        for (Tracked sprite : sprites) {
            if (shouldAnimate(sprite.binder())) {
                animated++;
            }
        }
        return animated + "/" + sprites.length;
    }

    public static void setVisibleMask(int mask) {
        visibleMask = mask;
    }

    public static void setEntitiesOnFire(boolean onFire) {
        entitiesOnFire = onFire;
    }

    /** Whether a binder should be ticked and uploaded this tick. */
    public static boolean shouldAnimate(DynamicTexture binder) {
        if (visibleMask == -1 || !HaliteClientMod.options().useAnimationCulling) {
            return true;
        }
        for (Tracked sprite : tracked) {
            if (sprite.binder() == binder) {
                return (visibleMask & sprite.bit()) != 0 || (sprite.fire() && entitiesOnFire);
            }
        }
        // not tracked: always animate
        return true;
    }
}
