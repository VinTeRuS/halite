package io.github.vinterus.halite.client.render.chunk;

import io.github.vinterus.halite.client.gl.GlCompat;
import io.github.vinterus.halite.client.render.chunk.compile.ChunkMeshBuilder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Uploaded geometry of one section for one render pass. Lives either in a buffer object or, when the driver has
 * none, in a direct buffer used through client-side vertex arrays.
 */
public class ChunkMesh {
    private final int quadCount;
    private final int[] groupStart;
    private final int[] groupCount;
    private final float[] planeBounds;

    private int bufferId;
    private ByteBuffer clientData;

    // translucent meshes keep a CPU copy so they can be re-sorted back to front
    private final float[] quadCenters;
    private ByteBuffer sortSource;
    private float lastSortX = Float.NaN, lastSortY, lastSortZ;

    public ChunkMesh(ChunkMeshBuilder.BuiltMesh mesh) {
        this.quadCount = mesh.quadCount();
        this.groupStart = mesh.groupStart();
        this.groupCount = mesh.groupCount();
        this.planeBounds = mesh.planeBounds();
        this.quadCenters = mesh.quadCenters();

        if (this.quadCenters != null) {
            this.sortSource = mesh.vertexData();
        }

        this.upload(mesh.vertexData());
    }

    private void upload(ByteBuffer data) {
        if (GlCompat.useVbo()) {
            if (this.bufferId == 0) {
                this.bufferId = GlCompat.genBuffer();
            }
            GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, this.bufferId);
            GlCompat.bufferData(GlCompat.ARRAY_BUFFER, data, GlCompat.STATIC_DRAW);
            GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, 0);
        } else {
            this.clientData = data;
        }
    }

    public int getQuadCount() {
        return this.quadCount;
    }

    public int getGroupStart(int facing) {
        return this.groupStart[facing];
    }

    public int getGroupCount(int facing) {
        return this.groupCount[facing];
    }

    public int getBufferId() {
        return this.bufferId;
    }

    public ByteBuffer getClientData() {
        return this.clientData;
    }

    public boolean isTranslucent() {
        return this.quadCenters != null;
    }

    /**
     * Whether the faces in a facing group can possibly be front-facing for a camera at the given section-local
     * position.
     */
    public boolean isGroupVisible(int facing, float camX, float camY, float camZ) {
        if (facing == ModelQuadFacing.UNASSIGNED) {
            return true;
        }

        float cam = switch (ModelQuadFacing.axis(facing)) {
            case 0 -> camX;
            case 1 -> camY;
            default -> camZ;
        };

        float plane = this.planeBounds[facing];
        return ModelQuadFacing.isPositive(facing) ? cam > plane - 0.01f : cam < plane + 0.01f;
    }

    /**
     * Re-orders quads from back to front for the given section-local camera position. Only re-sorts when the
     * camera moved far enough to make a difference.
     */
    public void sortTranslucent(float camX, float camY, float camZ) {
        if (this.quadCenters == null || this.quadCount < 2) {
            return;
        }

        float dx = camX - this.lastSortX, dy = camY - this.lastSortY, dz = camZ - this.lastSortZ;
        if (!Float.isNaN(this.lastSortX) && dx * dx + dy * dy + dz * dz < 1.0f) {
            return;
        }
        this.lastSortX = camX;
        this.lastSortY = camY;
        this.lastSortZ = camZ;

        int n = this.quadCount;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            float x = this.quadCenters[i * 3] - camX;
            float y = this.quadCenters[i * 3 + 1] - camY;
            float z = this.quadCenters[i * 3 + 2] - camZ;
            float dist = x * x + y * y + z * z;
            // farthest first: sort ascending by negated distance bits (distances are non-negative)
            keys[i] = ((long) ~Float.floatToRawIntBits(dist) << 32) | i;
        }
        java.util.Arrays.sort(keys);

        int quadBytes = 4 * ChunkVertexFormat.STRIDE;
        ByteBuffer sorted = ByteBuffer.allocateDirect(n * quadBytes).order(ByteOrder.nativeOrder());
        ByteBuffer src = this.sortSource.duplicate().order(ByteOrder.nativeOrder());

        for (int i = 0; i < n; i++) {
            int quad = (int) keys[i];
            src.limit((quad + 1) * quadBytes).position(quad * quadBytes);
            sorted.put(src);
            src.limit(src.capacity());
        }
        sorted.flip();

        this.upload(sorted);
    }

    public void delete() {
        if (this.bufferId != 0) {
            GlCompat.deleteBuffer(this.bufferId);
            this.bufferId = 0;
        }
        this.clientData = null;
        this.sortSource = null;
    }
}
