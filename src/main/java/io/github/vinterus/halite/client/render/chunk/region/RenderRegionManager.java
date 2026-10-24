package io.github.vinterus.halite.client.render.chunk.region;

import io.github.vinterus.halite.client.render.chunk.ChunkMesh;
import io.github.vinterus.halite.client.render.chunk.RenderSection;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.nio.ByteBuffer;

/**
 * Owns the render regions of a world and hands out space in their buffer pages.
 */
public final class RenderRegionManager {
    private static final int QUAD_BYTES = 64;

    private final Long2ObjectMap<RenderRegion> regions = new Long2ObjectOpenHashMap<>();
    private long allocatedBytes;
    private int pageCount;

    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
    }

    public RenderRegion getOrCreate(RenderSection section) {
        int x = RenderRegion.regionX(section.getChunkX());
        int y = RenderRegion.regionY(section.getChunkY());
        int z = RenderRegion.regionZ(section.getChunkZ());
        long key = key(x, y, z);

        RenderRegion region = this.regions.get(key);
        if (region == null) {
            region = new RenderRegion(this, x, y, z);
            this.regions.put(key, region);
        }
        return region;
    }

    /**
     * Stores a mesh's vertex data in its region, split into page-sized parts if needed.
     *
     * @param data vertex data in {@link io.github.vinterus.halite.client.render.chunk.ChunkVertexFormat}
     */
    public MeshPart[] store(RenderRegion region, int pass, ChunkMesh mesh, ByteBuffer data, int quadCount) {
        int maxQuads = io.github.vinterus.halite.client.gl.SharedQuadIndexBuffer.MAX_QUADS;
        int partCount = (quadCount + maxQuads - 1) / maxQuads;
        MeshPart[] parts = new MeshPart[partCount];

        for (int i = 0; i < partCount; i++) {
            int offset = i * maxQuads;
            int quads = Math.min(maxQuads, quadCount - offset);
            MeshPart part = region.allocate(pass, quads, mesh, offset);
            this.upload(part, data, offset);
            parts[i] = part;
        }
        return parts;
    }

    /** Re-uploads a part from mesh data (used after translucency sorting). */
    public void upload(MeshPart part, ByteBuffer data, int meshOffset) {
        ByteBuffer slice = data.duplicate();
        slice.limit((meshOffset + part.quadCount) * QUAD_BYTES).position(meshOffset * QUAD_BYTES);
        part.page.upload(part.firstQuad, slice);
    }

    public void release(MeshPart part) {
        part.page.getRegion().release(part);
    }

    void remove(RenderRegion region) {
        this.regions.remove(region.key());
    }

    void onPageAllocated(long bytes) {
        this.allocatedBytes += bytes;
        this.pageCount++;
    }

    void onPageFreed(long bytes) {
        this.allocatedBytes -= bytes;
        this.pageCount--;
    }

    public long getAllocatedBytes() {
        return this.allocatedBytes;
    }

    public int getPageCount() {
        return this.pageCount;
    }

    public int getRegionCount() {
        return this.regions.size();
    }

    public void delete() {
        for (RenderRegion region : this.regions.values()) {
            region.delete();
        }
        this.regions.clear();
    }
}
