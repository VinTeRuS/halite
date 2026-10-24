package io.github.vinterus.halite.client.gl;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * A single index buffer turning runs of four vertices into two triangles. GLES has no GL_QUADS and no base-vertex
 * draws, so meshes are drawn in batches of at most {@link #MAX_QUADS} quads with the vertex pointers rebased to the
 * start of each batch; 16-bit indices are enough for that.
 */
public final class SharedQuadIndexBuffer {
    public static final int MAX_QUADS = 65536 / 4;
    public static final int INDICES_PER_QUAD = 6;

    private final ByteBuffer data;
    private final ShortBuffer shorts;
    private int bufferId;

    public SharedQuadIndexBuffer() {
        this.data = BufferUtils.createByteBuffer(MAX_QUADS * INDICES_PER_QUAD * 2);
        this.shorts = this.data.asShortBuffer();

        for (int quad = 0; quad < MAX_QUADS; quad++) {
            int v = quad * 4;
            this.shorts.put((short) v);
            this.shorts.put((short) (v + 1));
            this.shorts.put((short) (v + 2));
            this.shorts.put((short) (v + 2));
            this.shorts.put((short) (v + 3));
            this.shorts.put((short) v);
        }
        this.shorts.flip();

        if (GlCompat.useVbo()) {
            this.bufferId = GlCompat.genBuffer();
            GlCompat.bindBuffer(GlCompat.ELEMENT_ARRAY_BUFFER, this.bufferId);
            GlCompat.bufferData(GlCompat.ELEMENT_ARRAY_BUFFER, this.data, GlCompat.STATIC_DRAW);
            GlCompat.bindBuffer(GlCompat.ELEMENT_ARRAY_BUFFER, 0);
        }
    }

    public int getBufferId() {
        return this.bufferId;
    }

    /** Client-side indices, used when buffer objects are unavailable. */
    public ShortBuffer getClientIndices() {
        return this.shorts;
    }

    public void delete() {
        if (this.bufferId != 0) {
            GlCompat.deleteBuffer(this.bufferId);
            this.bufferId = 0;
        }
    }
}
