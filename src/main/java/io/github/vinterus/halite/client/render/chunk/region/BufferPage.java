package io.github.vinterus.halite.client.render.chunk.region;

import io.github.vinterus.halite.client.gl.GlCompat;
import io.github.vinterus.halite.client.gl.SharedQuadIndexBuffer;
import io.github.vinterus.halite.client.render.chunk.ChunkVertexFormat;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A vertex buffer shared by several sections of one region and render pass. Pages hold at most
 * {@link SharedQuadIndexBuffer#MAX_QUADS} quads, so the shared 16-bit quad index buffer can address any quad in the
 * page and several sections that sit next to each other can be drawn with a single call.
 *
 * <p>Space is managed with a first-fit free list in units of quads. GLES 2 can neither copy between buffers nor
 * read them back, so a page never grows or compacts: sections that don't fit go to another page.
 */
public final class BufferPage {
    private static final int QUAD_BYTES = 4 * ChunkVertexFormat.STRIDE;

    private final RenderRegion region;
    private final int pass;
    private final int capacityQuads;

    private int bufferId;
    private ByteBuffer clientData;

    // free ranges as (start, length) pairs sorted by start
    private final IntArrayList free = new IntArrayList();
    private int usedQuads;

    // per-frame list of parts to draw, filled by the chunk renderer
    public final List<MeshPart> visibleParts = new ArrayList<>();
    public int visibleFrame = -1;

    BufferPage(RenderRegion region, int pass, int capacityQuads) {
        this.region = region;
        this.pass = pass;
        this.capacityQuads = capacityQuads;
        this.free.add(0);
        this.free.add(capacityQuads);

        if (GlCompat.useVbo()) {
            // zero-filled: unused space must hold degenerate quads, see release()
            this.bufferId = GlCompat.genBuffer();
            GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, this.bufferId);
            GlCompat.bufferData(GlCompat.ARRAY_BUFFER, zeros(capacityQuads), GlCompat.STATIC_DRAW);
            GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, 0);
        } else {
            this.clientData = ByteBuffer.allocateDirect(capacityQuads * QUAD_BYTES).order(ByteOrder.nativeOrder());
        }
        region.getManager().onPageAllocated((long) capacityQuads * QUAD_BYTES);
    }

    public RenderRegion getRegion() {
        return this.region;
    }

    public int getPass() {
        return this.pass;
    }

    public int getCapacityQuads() {
        return this.capacityQuads;
    }

    public boolean isEmpty() {
        return this.usedQuads == 0;
    }

    public int getBufferId() {
        return this.bufferId;
    }

    public ByteBuffer getClientData() {
        return this.clientData;
    }

    /** @return the first quad of the reserved range, or -1 if no free range is large enough */
    int allocate(int quads) {
        for (int i = 0; i < this.free.size(); i += 2) {
            int start = this.free.getInt(i), length = this.free.getInt(i + 1);
            if (length >= quads) {
                if (length == quads) {
                    this.free.removeElements(i, i + 2);
                } else {
                    this.free.set(i, start + quads);
                    this.free.set(i + 1, length - quads);
                }
                this.usedQuads += quads;
                return start;
            }
        }
        return -1;
    }

    /**
     * Frees a range and overwrites it with zeros. All-zero quads are degenerate and draw nothing, which lets the
     * renderer bridge gaps between visible sections with a single draw call.
     */
    void release(int start, int quads) {
        this.usedQuads -= quads;
        if (!this.deleted) {
            this.upload(start, zeros(quads));
        }

        // insert sorted, then merge with neighbours
        int i = 0;
        while (i < this.free.size() && this.free.getInt(i) < start) {
            i += 2;
        }
        this.free.add(i, quads);
        this.free.add(i, start);

        if (i + 2 < this.free.size() && start + quads == this.free.getInt(i + 2)) {
            this.free.set(i + 1, quads + this.free.getInt(i + 3));
            this.free.removeElements(i + 2, i + 4);
        }
        if (i >= 2 && this.free.getInt(i - 2) + this.free.getInt(i - 1) == start) {
            this.free.set(i - 1, this.free.getInt(i - 1) + this.free.getInt(i + 1));
            this.free.removeElements(i, i + 2);
        }
    }

    /** Writes vertex data for {@code quads} quads starting at {@code firstQuad}. */
    void upload(int firstQuad, ByteBuffer data) {
        ByteBuffer src = data.duplicate();
        if (this.bufferId != 0) {
            if (!src.isDirect()) {
                src = staged(src);
            }
            GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, this.bufferId);
            GlCompat.bufferSubData(GlCompat.ARRAY_BUFFER, (long) firstQuad * QUAD_BYTES, src);
            GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, 0);
        } else {
            ByteBuffer dst = this.clientData.duplicate();
            dst.position(firstQuad * QUAD_BYTES);
            dst.put(src);
        }
    }

    // Meshes are built into heap buffers: a direct buffer per rebuild is only freed by the GC, and running into the
    // direct memory limit (small on Pojav) makes the JVM stall in System.gc(). LWJGL wants direct memory, so uploads
    // go through this one reused buffer. Render thread only.
    private static ByteBuffer stagingBuffer;

    private static ByteBuffer staged(ByteBuffer src) {
        int bytes = src.remaining();
        if (stagingBuffer == null || stagingBuffer.capacity() < bytes) {
            stagingBuffer = ByteBuffer.allocateDirect(Math.max(bytes, 256 * 1024)).order(ByteOrder.nativeOrder());
        }
        ByteBuffer staging = stagingBuffer.duplicate();
        staging.clear();
        staging.put(src).flip();
        return staging;
    }

    private static ByteBuffer zeroBuffer;

    private static ByteBuffer zeros(int quads) {
        int bytes = quads * QUAD_BYTES;
        if (zeroBuffer == null || zeroBuffer.capacity() < bytes) {
            zeroBuffer = ByteBuffer.allocateDirect(Math.max(bytes, 64 * 1024)).order(ByteOrder.nativeOrder());
        }
        ByteBuffer slice = zeroBuffer.duplicate();
        slice.clear().limit(bytes);
        return slice;
    }

    void delete() {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        if (this.bufferId != 0) {
            GlCompat.deleteBuffer(this.bufferId);
            this.bufferId = 0;
        }
        this.clientData = null;
        this.region.getManager().onPageFreed((long) this.capacityQuads * QUAD_BYTES);
    }

    private boolean deleted;
}
