package io.github.vinterus.halite.client.render.chunk.compile;

/**
 * Implemented on the vanilla tessellator by mixin. While a capture target is set, {@code draw()} hands the buffered
 * vertices to it instead of submitting them to GL.
 */
public interface TessellatorCapture {
    void halite$setCaptureTarget(ChunkMeshBuilder target);

    boolean halite$isDrawing();
}
