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
 * renderers (stairs, fences, pistons, levers) and {@code updateBoundingBox} overrides (snow layers, doors,
 * trapdoors, rails, ...) rewrite it while rendering. The render thread reads and writes the same fields for
 * collision and ray casts, so those blocks are always meshed on the render thread. So are blocks with custom
 * StationAPI world renderers, whose code is unknown.
 */
public final class BlockSafety {
    /** Render types whose vanilla renderers never touch the block's bounds. */
    private static final boolean[] SAFE_RENDER_TYPES = new boolean[32];

    static {
        for (int type : new int[]{0, 1, 2, 3, 4, 5, 6, 8, 9, 13, 14, 15}) {
            SAFE_RENDER_TYPES[type] = true;
        }
    }

    private static final Map<Class<?>, Boolean> MUTATING_CLASSES = new IdentityHashMap<>();
    private static Method[] boundsMethods;

    private static boolean[] safe = new boolean[0];
    private static Block[] blocksSnapshot;

    private BlockSafety() {
    }

    /** Table indexed by block id; recomputed when the block registry array changes. Render thread only. */
    public static boolean[] getSafeTable() {
        Block[] blocks = Block.BLOCKS;
        if (blocks != blocksSnapshot || safe.length != blocks.length) {
            boolean[] table = new boolean[blocks.length];
            int unsafeCount = 0;
            for (int id = 0; id < blocks.length; id++) {
                Block block = blocks[id];
                table[id] = block == null || isSafe(block);
                if (!table[id]) {
                    unsafeCount++;
                }
            }
            safe = table;
            blocksSnapshot = blocks;
            HaliteClientMod.logger().info("{} of {} block types are meshed on the render thread", unsafeCount, blocks.length);
        }
        return safe;
    }

    private static boolean isSafe(Block block) {
        int type = block.getRenderType();
        if (type < 0 || type >= SAFE_RENDER_TYPES.length || !SAFE_RENDER_TYPES[type]) {
            return false;
        }
        if (block instanceof BlockWithWorldRenderer) {
            return false;
        }
        return !MUTATING_CLASSES.computeIfAbsent(block.getClass(), BlockSafety::overridesBoundsUpdate);
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
