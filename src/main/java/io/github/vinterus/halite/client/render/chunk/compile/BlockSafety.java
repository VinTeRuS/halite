package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.minecraft.block.Block;
import net.minecraft.world.BlockView;
import net.modificationstation.stationapi.api.client.model.block.BlockWithWorldRenderer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Decides which blocks may be meshed off the render thread.
 *
 * <p>Beta keeps a block's bounding box in mutable fields on the shared {@link Block} instance, and several
 * renderers (stairs, fences, levers, doors) and {@code updateBoundingBox} overrides (snow layers, trapdoors,
 * rails, ...) rewrite it while rendering. The render thread reads and writes the same fields for collision and ray
 * casts. Vanilla blocks of that kind are rendered on workers through a per-thread copy of the block object, so the
 * writes land in the copy ({@link #MODE_CLONE}). Modded ones and blocks with custom StationAPI world renderers,
 * whose code is unknown, stay on the render thread ({@link #MODE_RENDER_THREAD}).
 */
public final class BlockSafety {
    public static final byte MODE_RENDER_THREAD = 0;
    public static final byte MODE_WORKER = 1;
    public static final byte MODE_CLONE = 2;

    /** Render types whose vanilla renderers never touch the block's bounds. */
    private static final boolean[] SAFE_RENDER_TYPES = new boolean[32];
    /** Render types whose vanilla renderers only change the bounds of the block being rendered. */
    private static final boolean[] CLONEABLE_RENDER_TYPES = new boolean[32];

    static {
        for (int type : new int[]{0, 1, 2, 3, 4, 5, 6, 8, 9, 13, 14, 15}) {
            SAFE_RENDER_TYPES[type] = true;
            CLONEABLE_RENDER_TYPES[type] = true;
        }
        // doors, stairs, fences, levers; pistons (16, 17) are left out, their rendering is tied to block entities
        for (int type : new int[]{7, 10, 11, 12}) {
            CLONEABLE_RENDER_TYPES[type] = true;
        }
    }

    private static final Map<Class<?>, Boolean> MUTATING_CLASSES = new IdentityHashMap<>();
    private static Method[] boundsMethods;

    private static byte[] modes = new byte[0];
    private static Block[] blocksSnapshot;

    private BlockSafety() {
    }

    /** Meshing mode per block id; recomputed when the block registry array changes. Render thread only. */
    public static byte[] getModeTable() {
        Block[] blocks = Block.BLOCKS;
        if (blocks != blocksSnapshot || modes.length != blocks.length) {
            byte[] table = new byte[blocks.length];
            int renderThread = 0, cloned = 0;
            for (int id = 0; id < blocks.length; id++) {
                Block block = blocks[id];
                table[id] = block == null ? MODE_WORKER : modeOf(block);
                if (table[id] == MODE_RENDER_THREAD) {
                    renderThread++;
                } else if (table[id] == MODE_CLONE) {
                    cloned++;
                }
            }
            modes = table;
            blocksSnapshot = blocks;
            BlockCopies.invalidateAll();
            HaliteClientMod.logger().info("Block meshing: {} types on workers through per-thread copies, {} on the render thread",
                    cloned, renderThread);
        }
        return modes;
    }

    private static byte modeOf(Block block) {
        int type = block.getRenderType();
        if (type < 0 || type >= SAFE_RENDER_TYPES.length || block instanceof BlockWithWorldRenderer) {
            return MODE_RENDER_THREAD;
        }

        boolean mutatesBounds = !SAFE_RENDER_TYPES[type]
                || MUTATING_CLASSES.computeIfAbsent(block.getClass(), BlockSafety::overridesBoundsUpdate);
        if (!mutatesBounds) {
            return MODE_WORKER;
        }

        // only vanilla code is known to touch nothing but the rendered block's own bounds
        boolean vanilla = block.getClass().getName().startsWith("net.minecraft.");
        return vanilla && CLONEABLE_RENDER_TYPES[type] && BlockCopies.canCopy(block) ? MODE_CLONE : MODE_RENDER_THREAD;
    }

    private static boolean overridesBoundsUpdate(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != Block.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (isBoundsMethod(method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBoundsMethod(Method candidate) {
        for (Method method : getBoundsMethods()) {
            if (method.getName().equals(candidate.getName())
                    && java.util.Arrays.equals(method.getParameterTypes(), candidate.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds {@code Block.updateBoundingBox(BlockView, int, int, int)} by signature, which works whatever the
     * runtime method names are (development names, intermediary, or anything else).
     */
    private static Method[] getBoundsMethods() {
        if (boundsMethods == null) {
            java.util.List<Method> found = new java.util.ArrayList<>();
            for (Method method : Block.class.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (method.getReturnType() == void.class && !Modifier.isStatic(method.getModifiers())
                        && params.length == 4 && params[0] == BlockView.class
                        && params[1] == int.class && params[2] == int.class && params[3] == int.class) {
                    found.add(method);
                }
            }
            if (found.size() != 1) {
                HaliteClientMod.logger().warn("Expected one bounds update method on Block, found {}; treating all as such", found.size());
            }
            boundsMethods = found.toArray(new Method[0]);
        }
        return boundsMethods;
    }
}
