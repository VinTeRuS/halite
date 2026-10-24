package io.github.vinterus.halite.client.render.chunk;

import io.github.vinterus.halite.client.render.chunk.compile.ChunkMeshBuilder;
import io.github.vinterus.halite.client.render.chunk.region.MeshPart;
import io.github.vinterus.halite.client.render.chunk.region.RenderRegion;
import io.github.vinterus.halite.client.render.chunk.region.RenderRegionManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Uploaded geometry of one section for one render pass, stored in its region's shared buffer pages.
 */
public class ChunkMesh {
    private static final int QUAD_BYTES = 4 * ChunkVertexFormat.STRIDE;

    private static long totalBytes;
    private static int totalMeshes;

    /** Vertex memory of all live meshes, for the debug overlay. */
    public static long getTotalBytes() {
        return totalBytes;
    }

    public static int getTotalMeshes() {
        return totalMeshes;
    }

    private final RenderRegionManager regions;
    private final RenderRegion region;
    private final int quadCount;
    private final int[] groupStart;
    private final int[] groupCount;
    private final float[] planeBounds;
    private MeshPart[] parts;
    private final int originX, originY, originZ;

    // translucent meshes keep a CPU copy so they can be re-sorted back to front
    private final float[] quadCenters;
    private ByteBuffer sortSource;
    private ByteBuffer sortTarget;
    private long[] sortKeys;
    private float lastSortX = Float.NaN, lastSortY, lastSortZ;

    public ChunkMesh(ChunkMeshBuilder.BuiltMesh mesh, RenderRegionManager regions, RenderRegion region, int pass,
                     RenderSection section) {
        this.regions = regions;
        this.originX = section.getOriginX();
        this.originY = section.getOriginY();
        this.originZ = section.getOriginZ();
        this.region = region;
        this.quadCount = mesh.quadCount();
        this.groupStart = mesh.groupStart();
        this.groupCount = mesh.groupCount();
        this.planeBounds = mesh.planeBounds();
        this.quadCenters = mesh.quadCenters();

        if (this.quadCenters != null) {
            this.sortSource = mesh.vertexData();
        }

        this.parts = regions.store(region, pass, this, mesh.vertexData(), this.quadCount);

        totalBytes += (long) this.quadCount * QUAD_BYTES;
        totalMeshes++;
    }

    public RenderRegion getRegion() {
        return this.region;
    }

    public int getOriginX() {
        return this.originX;
    }

    public int getOriginY() {
        return this.originY;
    }

    public int getOriginZ() {
        return this.originZ;
    }

    public MeshPart[] getParts() {
        return this.parts;
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
     *
     * @return whether the mesh was re-sorted and uploaded
     */
    public boolean sortTranslucent(float camX, float camY, float camZ) {
        if (this.quadCenters == null || this.quadCount < 2 || this.parts == null) {
            return false;
        }

        // far away sections barely change order as the camera moves, so they re-sort less often
        float cx = camX - 8.0f, cy = camY - 8.0f, cz = camZ - 8.0f;
        float distSq = cx * cx + cy * cy + cz * cz;
        float threshold = Math.max(1.0f, distSq / (16.0f * 16.0f));

        float dx = camX - this.lastSortX, dy = camY - this.lastSortY, dz = camZ - this.lastSortZ;
        if (!Float.isNaN(this.lastSortX) && dx * dx + dy * dy + dz * dz < threshold) {
            return false;
        }
        this.lastSortX = camX;
        this.lastSortY = camY;
        this.lastSortZ = camZ;

        int n = this.quadCount;
        if (this.sortKeys == null) {
            this.sortKeys = new long[n];
            this.sortTarget = ByteBuffer.allocateDirect(n * QUAD_BYTES).order(ByteOrder.nativeOrder());
        }
        long[] keys = this.sortKeys;
        for (int i = 0; i < n; i++) {
            float x = this.quadCenters[i * 3] - camX;
            float y = this.quadCenters[i * 3 + 1] - camY;
            float z = this.quadCenters[i * 3 + 2] - camZ;
            float dist = x * x + y * y + z * z;
            // farthest first: sort ascending by negated distance bits (distances are non-negative)
            keys[i] = ((long) ~Float.floatToRawIntBits(dist) << 32) | i;
        }
        java.util.Arrays.sort(keys);

        ByteBuffer sorted = this.sortTarget;
        sorted.clear();
        ByteBuffer src = this.sortSource.duplicate().order(ByteOrder.nativeOrder());

        for (int i = 0; i < n; i++) {
            int quad = (int) keys[i];
            src.limit((quad + 1) * QUAD_BYTES).position(quad * QUAD_BYTES);
            sorted.put(src);
            src.limit(src.capacity());
        }
        sorted.flip();

        for (MeshPart part : this.parts) {
            this.regions.upload(part, sorted, part.getMeshOffset());
        }
        return true;
    }

    public void delete() {
        if (this.parts != null) {
            for (MeshPart part : this.parts) {
                this.regions.release(part);
            }
            this.parts = null;
            totalBytes -= (long) this.quadCount * QUAD_BYTES;
            totalMeshes--;
        }
        this.sortSource = null;
        this.sortTarget = null;
        this.sortKeys = null;
    }
}
