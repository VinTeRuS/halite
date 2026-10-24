package io.github.vinterus.halite.client.render.chunk.region;

import io.github.vinterus.halite.client.render.chunk.ChunkMesh;

/**
 * A contiguous range of quads of one section's mesh inside a buffer page. Meshes larger than a page are split
 * into several parts.
 */
public final class MeshPart {
    final BufferPage page;
    final int firstQuad;
    final int quadCount;
    final ChunkMesh mesh;
    // quad offset of this part within the mesh
    final int meshOffset;

    MeshPart(BufferPage page, int firstQuad, int quadCount, ChunkMesh mesh, int meshOffset) {
        this.page = page;
        this.firstQuad = firstQuad;
        this.quadCount = quadCount;
        this.mesh = mesh;
        this.meshOffset = meshOffset;
    }

    public BufferPage getPage() {
        return this.page;
    }

    public int getFirstQuad() {
        return this.firstQuad;
    }

    public int getQuadCount() {
        return this.quadCount;
    }

    public ChunkMesh getMesh() {
        return this.mesh;
    }

    public int getMeshOffset() {
        return this.meshOffset;
    }
}
