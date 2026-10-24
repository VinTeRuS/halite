package io.github.vinterus.halite.client.render.chunk.region;

import io.github.vinterus.halite.client.gl.SharedQuadIndexBuffer;
import io.github.vinterus.halite.client.render.chunk.RenderSection;

import java.util.ArrayList;
import java.util.List;

/**
 * A block of 8 x 4 x 8 sections sharing a coordinate frame and vertex buffers, after Sodium's render regions.
 * Vertices are stored relative to the region's center, so drawing a region needs one matrix setup, and sections
 * stored next to each other in a page are drawn together.
 */
public final class RenderRegion {
    public static final int WIDTH = 8, HEIGHT = 4, LENGTH = 8;
    private static final int WIDTH_SHIFT = 3, HEIGHT_SHIFT = 2, LENGTH_SHIFT = 3;

    /** Lower bound for a region's first page, which is sized after the first mesh since many regions stay sparse. */
    private static final int MIN_FIRST_PAGE_QUADS = 512;
    /**
     * Size of later pages: half the index buffer's reach, which halves the unused tail of a region's last page
     * for a few more draw calls.
     */
    private static final int PAGE_QUADS = SharedQuadIndexBuffer.MAX_QUADS / 2;

    private final RenderRegionManager manager;
    private final int x, y, z;

    @SuppressWarnings("unchecked")
    private final List<BufferPage>[] pages = new List[RenderSection.PASS_COUNT];

    RenderRegion(RenderRegionManager manager, int x, int y, int z) {
        this.manager = manager;
        this.x = x;
        this.y = y;
        this.z = z;
        for (int i = 0; i < this.pages.length; i++) {
            this.pages[i] = new ArrayList<>();
        }
    }

    public static int regionX(int sectionX) {
        return sectionX >> WIDTH_SHIFT;
    }

    public static int regionY(int sectionY) {
        return sectionY >> HEIGHT_SHIFT;
    }

    public static int regionZ(int sectionZ) {
        return sectionZ >> LENGTH_SHIFT;
    }

    /** Offset of a section's origin from its region's center, in blocks. */
    public static float sectionOffsetX(int sectionX) {
        return (sectionX & (WIDTH - 1)) * 16 - WIDTH * 8;
    }

    public static float sectionOffsetY(int sectionY) {
        return (sectionY & (HEIGHT - 1)) * 16 - HEIGHT * 8;
    }

    public static float sectionOffsetZ(int sectionZ) {
        return (sectionZ & (LENGTH - 1)) * 16 - LENGTH * 8;
    }

    public RenderRegionManager getManager() {
        return this.manager;
    }

    public double getCenterX() {
        return ((this.x << WIDTH_SHIFT) + WIDTH / 2) * 16.0;
    }

    public double getCenterY() {
        return ((this.y << HEIGHT_SHIFT) + HEIGHT / 2) * 16.0;
    }

    public double getCenterZ() {
        return ((this.z << LENGTH_SHIFT) + LENGTH / 2) * 16.0;
    }

    /** Finds room for {@code quads} quads (at most one page's worth) in this region's pages for a pass. */
    MeshPart allocate(int pass, int quads, io.github.vinterus.halite.client.render.chunk.ChunkMesh mesh, int meshOffset) {
        List<BufferPage> list = this.pages[pass];
        for (BufferPage page : list) {
            int start = page.allocate(quads);
            if (start >= 0) {
                return new MeshPart(page, start, quads, mesh, meshOffset);
            }
        }

        int capacity = PAGE_QUADS;
        if (list.isEmpty()) {
            capacity = Math.min(capacity, Integer.highestOneBit(Math.max(MIN_FIRST_PAGE_QUADS, quads * 2) - 1) << 1);
        }
        capacity = Math.max(capacity, quads);

        BufferPage page = new BufferPage(this, pass, capacity);
        list.add(page);
        int start = page.allocate(quads);
        return new MeshPart(page, start, quads, mesh, meshOffset);
    }

    void release(MeshPart part) {
        BufferPage page = part.page;
        page.release(part.firstQuad, part.quadCount);
        if (page.isEmpty()) {
            page.delete();
            this.pages[page.getPass()].remove(page);
            if (this.isEmpty()) {
                this.manager.remove(this);
            }
        }
    }

    boolean isEmpty() {
        for (List<BufferPage> list : this.pages) {
            if (!list.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    long key() {
        return RenderRegionManager.key(this.x, this.y, this.z);
    }

    void delete() {
        for (List<BufferPage> list : this.pages) {
            for (BufferPage page : list) {
                page.delete();
            }
            list.clear();
        }
    }
}
