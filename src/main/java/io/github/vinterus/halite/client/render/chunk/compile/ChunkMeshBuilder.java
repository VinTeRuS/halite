package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.render.chunk.ChunkVertexFormat;
import io.github.vinterus.halite.client.render.chunk.ModelQuadFacing;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Collects the geometry that block renderers push through the vanilla tessellator while a section is being
 * meshed, re-encodes it into {@link ChunkVertexFormat} and buckets quads by facing.
 */
public class ChunkMeshBuilder {
    // vanilla tessellator layout: 8 ints per vertex
    private static final int TESS_STRIDE = 8;

    private final GroupBuffer[] groups = new GroupBuffer[ModelQuadFacing.COUNT];

    /** Per-facing extreme plane coordinate, in section-local block units. */
    private final float[] planeBounds = new float[ModelQuadFacing.COUNT];

    private final float[] px = new float[4], py = new float[4], pz = new float[4];
    private final float[] tu = new float[4], tv = new float[4];
    private final int[] col = new int[4];

    public ChunkMeshBuilder() {
        for (int i = 0; i < this.groups.length; i++) {
            this.groups[i] = new GroupBuffer();
        }
        this.reset();
    }

    public void reset() {
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

        GroupBuffer group = this.groups[facing];
        group.ensureCapacity(4 * ChunkVertexFormat.STRIDE);
        ByteBuffer buf = group.buffer;

        for (int i = 0; i < 4; i++) {
            buf.putShort(ChunkVertexFormat.encodePosition(this.px[i]));
            buf.putShort(ChunkVertexFormat.encodePosition(this.py[i]));
            buf.putShort(ChunkVertexFormat.encodePosition(this.pz[i]));
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

    /**
     * Packs all groups into a single buffer, facing groups first and unassigned last.
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

        ByteBuffer out = ByteBuffer.allocateDirect(totalQuads * 4 * ChunkVertexFormat.STRIDE).order(ByteOrder.nativeOrder());
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

        return new BuiltMesh(out, totalQuads, starts, counts, Arrays.copyOf(this.planeBounds, this.planeBounds.length), centers);
    }

    public record BuiltMesh(ByteBuffer vertexData, int quadCount, int[] groupStart, int[] groupCount,
                            float[] planeBounds, float[] quadCenters) {
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
