package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.minecraft.block.Block;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shallow copies of block objects, one set per chunk builder thread. A copy shares every reference with the
 * original block but has its own primitive fields, in particular its own bounding box, so renderers that rewrite
 * the bounds while meshing don't disturb the render thread's collision checks.
 *
 * <p>Copies are made without running constructors (which would register the block again).
 */
public final class BlockCopies {
    private static final Unsafe UNSAFE = findUnsafe();
    private static final Map<Class<?>, Field[]> FIELDS = new IdentityHashMap<>();

    private static volatile int generation;

    private Block[] copies = new Block[0];
    private int copiesGeneration;

    /** Drops every thread's copies, so the next use copies the current field values again. */
    public static void invalidateAll() {
        generation++;
    }

    /** The copy of {@code block} for this thread, made on first use. */
    public Block get(Block block, int id) {
        int current = generation;
        if (this.copiesGeneration != current) {
            this.copies = new Block[0];
            this.copiesGeneration = current;
        }
        if (id >= this.copies.length) {
            this.copies = java.util.Arrays.copyOf(this.copies, Math.max(id + 1, this.copies.length * 2));
        }
        Block copy = this.copies[id];
        if (copy == null || copy.getClass() != block.getClass()) {
            copy = copy(block);
            this.copies[id] = copy;
        }
        return copy;
    }

    /** Whether copies of this block can be made at all. Render thread. */
    static boolean canCopy(Block block) {
        try {
            return UNSAFE != null && fields(block.getClass()) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Block copy(Block block) {
        try {
            Block copy = (Block) UNSAFE.allocateInstance(block.getClass());
            for (Field field : fields(block.getClass())) {
                long offset = UNSAFE.objectFieldOffset(field);
                Class<?> type = field.getType();
                if (type == double.class) {
                    UNSAFE.putDouble(copy, offset, UNSAFE.getDouble(block, offset));
                } else if (type == float.class) {
                    UNSAFE.putFloat(copy, offset, UNSAFE.getFloat(block, offset));
                } else if (type == int.class) {
                    UNSAFE.putInt(copy, offset, UNSAFE.getInt(block, offset));
                } else if (type == long.class) {
                    UNSAFE.putLong(copy, offset, UNSAFE.getLong(block, offset));
                } else if (type == boolean.class) {
                    UNSAFE.putBoolean(copy, offset, UNSAFE.getBoolean(block, offset));
                } else if (type == byte.class) {
                    UNSAFE.putByte(copy, offset, UNSAFE.getByte(block, offset));
                } else if (type == short.class) {
                    UNSAFE.putShort(copy, offset, UNSAFE.getShort(block, offset));
                } else if (type == char.class) {
                    UNSAFE.putChar(copy, offset, UNSAFE.getChar(block, offset));
                } else {
                    UNSAFE.putObject(copy, offset, UNSAFE.getObject(block, offset));
                }
            }
            return copy;
        } catch (InstantiationException e) {
            throw new IllegalStateException("Cannot copy " + block, e);
        }
    }

    private static synchronized Field[] fields(Class<?> clazz) {
        return FIELDS.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field field : k.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        list.add(field);
                    }
                }
            }
            return list.toArray(new Field[0]);
        });
    }

    private static Unsafe findUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            HaliteClientMod.logger().warn("sun.misc.Unsafe unavailable; bound-mutating blocks stay on the render thread", e);
            return null;
        }
    }
}
