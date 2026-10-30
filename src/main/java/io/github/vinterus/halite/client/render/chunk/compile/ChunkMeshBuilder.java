package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.render.chunk.ChunkVertexFormat;
import io.github.vinterus.halite.client.render.chunk.ModelQuadFacing;
import io.github.vinterus.halite.client.render.texture.AnimatedSprites;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects the geometry that block renderers push through the vanilla tessellator while a section is being
 * meshed, re-encodes it into {@link ChunkVertexFormat} and buckets quads by facing.
 */
public class ChunkMeshBuilder {
    // vanilla tessellator layout: 8 ints per vertex
    private static final int TESS_STRIDE = 8;

    private final GroupBuffer[] groups = new GroupBuffer[ModelQuadFacing.COUNT];

    // section origin relative to the render region's center; vertices are stored region-relative
    private float regionOffsetX, regionOffsetY, regionOffsetZ;

    // animated sprites to look for, and the ones found
    private AnimatedSprites.Tracked[] animatedSprites = new AnimatedSprites.Tracked[0];
    private int animatedMask;

    /** Per-facing extreme plane coordinate, in section-local block units. */
    private final float[] planeBounds = new float[ModelQuadFacing.COUNT];

    private final float[] px = new float[4], py = new float[4], pz = new float[4];
    private final float[] tu = new float[4], tv = new float[4];
    private final int[] col = new int[4];

    // Builders keep their grown group buffers, so pooled ones skip both the allocation and the zeroing.
    private static final int POOL_SIZE = 24;
    private static final ConcurrentLinkedQueue<ChunkMeshBuilder> POOL = new ConcurrentLinkedQueue<>();

    /** A reset builder, from the pool when one is free. */
    public static ChunkMeshBuilder acquire() {
        ChunkMeshBuilder builder = POOL.poll();
        if (builder == null) {
            return new ChunkMeshBuilder();
        }
        builder.reset();
        return builder;
    }

    /** Returns a builder whose mesh has been built and uploaded. */
    public static void release(ChunkMeshBuilder builder) {
        if (POOL.size() < POOL_SIZE) {
            POOL.offer(builder);
        }
    }

    public ChunkMeshBuilder() {
        for (int i = 0; i < this.groups.length; i++) {
            this.groups[i] = new GroupBuffer();
        }
        this.reset();
    }

    /** Sets where the section sits relative to its region's center, see {@code RenderRegion}. */
    public void setRegionOffset(float x, float y, float z) {
        this.regionOffsetX = x;
        this.regionOffsetY = y;
        this.regionOffsetZ = z;
    }

    public void setAnimatedSprites(AnimatedSprites.Tracked[] sprites) {
        this.animatedSprites = sprites;
    }

    public void reset() {
        this.animatedMask = 0;
        for (GroupBuffer group : this.groups) {
            group.reset();
        }
        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            this.planeBounds[facing] = ModelQuadFacing.isPositive(facing) ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
        }
    }

    public boolean isEmpty() {
        for (GroupBuffer group : this.groups) {
            if (group.quads > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Consumes a batch flushed from the tessellator.
     *
     * @param data          the tessellator's raw int buffer
     * @param vertexCount   number of vertices actually stored (after quad -> triangle expansion, if any)
     * @param mode          the GL primitive mode the tessellator was started with
     * @param trianglesMode whether the tessellator expanded quads into two triangles while buffering
     */
    public void acceptTessellatorData(int[] data, int vertexCount, int mode, boolean trianglesMode,
                                      boolean hasTexture, boolean hasColor) {
        if (mode == GL11.GL_QUADS) {
            if (trianglesMode) {
                // buffered as v0 v1 v2 v0 v2 v3
                for (int base = 0; base + 6 <= vertexCount; base += 6) {
                    this.loadVertex(data, 0, base, hasTexture, hasColor);
                    this.loadVertex(data, 1, base + 1, hasTexture, hasColor);
                    this.loadVertex(data, 2, base + 2, hasTexture, hasColor);
                    this.loadVertex(data, 3, base + 5, hasTexture, hasColor);
                    this.emitQuad();
                }
            } else {
                for (int base = 0; base + 4 <= vertexCount; base += 4) {
                    for (int i = 0; i < 4; i++) {
                        this.loadVertex(data, i, base + i, hasTexture, hasColor);
                    }
                    this.emitQuad();
                }
            }
        } else if (mode == GL11.GL_TRIANGLES) {
            // degenerate quads keep everything in a single primitive type
            for (int base = 0; base + 3 <= vertexCount; base += 3) {
                this.loadVertex(data, 0, base, hasTexture, hasColor);
                this.loadVertex(data, 1, base + 1, hasTexture, hasColor);
                this.loadVertex(data, 2, base + 2, hasTexture, hasColor);
                this.loadVertex(data, 3, base + 2, hasTexture, hasColor);
                this.emitQuad();
            }
        }
        // other primitive types never show up in terrain and cannot be represented; they are dropped
    }

    private void loadVertex(int[] data, int dst, int vertex, boolean hasTexture, boolean hasColor) {
        int i = vertex * TESS_STRIDE;
        this.px[dst] = Float.intBitsToFloat(data[i]);
        this.py[dst] = Float.intBitsToFloat(data[i + 1]);
        this.pz[dst] = Float.intBitsToFloat(data[i + 2]);
        this.tu[dst] = hasTexture ? Float.intBitsToFloat(data[i + 3]) : 0.0f;
        this.tv[dst] = hasTexture ? Float.intBitsToFloat(data[i + 4]) : 0.0f;
        this.col[dst] = hasColor ? data[i + 5] : 0xFFFFFFFF;
    }

    private void emitQuad() {
        // winding normal from the two diagonals, robust against degenerate edges
        float ax = this.px[2] - this.px[0], ay = this.py[2] - this.py[0], az = this.pz[2] - this.pz[0];
        float bx = this.px[3] - this.px[1], by = this.py[3] - this.py[1], bz = this.pz[3] - this.pz[1];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;

        int facing = ModelQuadFacing.fromNormal(nx, ny, nz);

        if (facing != ModelQuadFacing.UNASSIGNED) {
            // all four vertices lie on the plane; vertex 0 is representative
            float plane = switch (ModelQuadFacing.axis(facing)) {
                case 0 -> this.px[0];
                case 1 -> this.py[0];
                default -> this.pz[0];
            };

            if (ModelQuadFacing.isPositive(facing)) {
                this.planeBounds[facing] = Math.min(this.planeBounds[facing], plane);
            } else {
                this.planeBounds[facing] = Math.max(this.planeBounds[facing], plane);
            }
        }

        if (this.animatedSprites.length > 0) {
            this.animatedMask |= AnimatedSprites.maskFor(this.animatedSprites,
                    (this.tu[0] + this.tu[1] + this.tu[2] + this.tu[3]) * 0.25f,
                    (this.tv[0] + this.tv[1] + this.tv[2] + this.tv[3]) * 0.25f);
        }

        GroupBuffer group = this.groups[facing];
        group.ensureCapacity(4 * ChunkVertexFormat.STRIDE);
        ByteBuffer buf = group.buffer;

        for (int i = 0; i < 4; i++) {
            buf.putShort(ChunkVertexFormat.encodePosition(this.px[i] + this.regionOffsetX));
            buf.putShort(ChunkVertexFormat.encodePosition(this.py[i] + this.regionOffsetY));
            buf.putShort(ChunkVertexFormat.encodePosition(this.pz[i] + this.regionOffsetZ));
            buf.putShort((short) 0);
            buf.putShort(ChunkVertexFormat.encodeTexture(this.tu[i]));
            buf.putShort(ChunkVertexFormat.encodeTexture(this.tv[i]));
            buf.putInt(this.col[i]);
        }

        group.addCenter((this.px[0] + this.px[1] + this.px[2] + this.px[3]) * 0.25f,
                (this.py[0] + this.py[1] + this.py[2] + this.py[3]) * 0.25f,
                (this.pz[0] + this.pz[1] + this.pz[2] + this.pz[3]) * 0.25f);
        group.quads++;
    }

    private static ByteBuffer opaqueScratch;

    /**
     * Packs all groups into a single buffer, facing groups first and unassigned last. Render thread only: for
     * opaque passes the returned data lives in a shared buffer that the next call overwrites.
     *
     * @param translucent whether quad centers should be kept for back-to-front sorting
     */
    public BuiltMesh build(boolean translucent) {
        int totalQuads = 0;
        for (GroupBuffer group : this.groups) {
            totalQuads += group.quads;
        }
        if (totalQuads == 0) {
            return null;
        }

        int bytes = totalQuads * 4 * ChunkVertexFormat.STRIDE;
        ByteBuffer out;
        if (translucent) {
            // kept by the mesh for sorting; heap memory on purpose, see BufferPage#staged
            out = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
        } else {
            // only uploaded and then dropped, so a reused direct buffer saves the allocation and the staging copy
            if (opaqueScratch == null || opaqueScratch.capacity() < bytes) {
                opaqueScratch = ByteBuffer.allocateDirect(Math.max(bytes, 256 * 1024)).order(ByteOrder.nativeOrder());
            }
            out = opaqueScratch.duplicate().order(ByteOrder.nativeOrder());
            out.clear();
        }
        int[] starts = new int[ModelQuadFacing.COUNT];
        int[] counts = new int[ModelQuadFacing.COUNT];
        float[] centers = translucent ? new float[totalQuads * 3] : null;

        int quad = 0;
        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            GroupBuffer group = this.groups[facing];
            starts[facing] = quad;
            counts[facing] = group.quads;

            ByteBuffer src = group.buffer.duplicate();
            src.flip();
            out.put(src);

            if (centers != null) {
                System.arraycopy(group.centers, 0, centers, quad * 3, group.quads * 3);
            }

            quad += group.quads;
        }
        out.flip();

        return new BuiltMesh(out, totalQuads, starts, counts, Arrays.copyOf(this.planeBounds, this.planeBounds.length), centers,
                this.animatedMask);
    }

    public record BuiltMesh(ByteBuffer vertexData, int quadCount, int[] groupStart, int[] groupCount,
                            float[] planeBounds, float[] quadCenters, int animatedMask) {
    }

    private static final class GroupBuffer {
        ByteBuffer buffer = ByteBuffer.allocate(4096).order(ByteOrder.nativeOrder());
        float[] centers = new float[96];
        int quads;

        void reset() {
            this.buffer.clear();
            this.quads = 0;
        }

        void ensureCapacity(int bytes) {
            if (this.buffer.remaining() < bytes) {
                ByteBuffer grown = ByteBuffer.allocate(Math.max(this.buffer.capacity() * 2, this.buffer.position() + bytes))
                        .order(ByteOrder.nativeOrder());
                this.buffer.flip();
                grown.put(this.buffer);
                this.buffer = grown;
            }
        }

        void addCenter(float x, float y, float z) {
            int i = this.quads * 3;
            if (i + 3 > this.centers.length) {
                this.centers = Arrays.copyOf(this.centers, this.centers.length * 2);
            }
            this.centers[i] = x;
            this.centers[i + 1] = y;
            this.centers[i + 2] = z;
        }
    }
}
