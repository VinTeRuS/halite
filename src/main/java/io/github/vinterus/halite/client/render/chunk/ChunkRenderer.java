package io.github.vinterus.halite.client.render.chunk;

import io.github.vinterus.halite.client.gl.GlCompat;
import io.github.vinterus.halite.client.gl.SharedQuadIndexBuffer;
import io.github.vinterus.halite.client.render.viewport.Viewport;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.List;

/**
 * Draws section meshes with fixed-function client state, which every target (desktop GL, gl4es on GLES 2) handles.
 * Sections are drawn with one matrix push each; positions and texture coordinates are decoded by the modelview and
 * texture matrices.
 */
public class ChunkRenderer {
    private static final float POSITION_DECODE = 1.0f / ChunkVertexFormat.POSITION_SCALE;
    private static final float TEXTURE_DECODE = 1.0f / ChunkVertexFormat.TEXTURE_SCALE;

    private final SharedQuadIndexBuffer indexBuffer = new SharedQuadIndexBuffer();
    private final boolean vbo = GlCompat.useVbo();

    private int drawCalls;

    public int getDrawCalls() {
        return this.drawCalls;
    }

    public void resetStats() {
        this.drawCalls = 0;
    }

    public int render(List<RenderSection> sections, int pass, Viewport viewport, boolean faceCulling, boolean sort) {
        if (sections.isEmpty()) {
            return 0;
        }

        this.begin();

        int drawn = 0;
        boolean translucent = pass == RenderSection.PASS_TRANSLUCENT;
        int count = sections.size();

        try {
            for (int i = 0; i < count; i++) {
                // translucent geometry goes back to front
                RenderSection section = sections.get(translucent ? count - 1 - i : i);
                ChunkMesh mesh = section.getMesh(pass);
                if (mesh == null) {
                    continue;
                }

                float camX = (float) (viewport.x - section.getOriginX());
                float camY = (float) (viewport.y - section.getOriginY());
                float camZ = (float) (viewport.z - section.getOriginZ());

                if (translucent && sort) {
                    mesh.sortTranslucent(camX, camY, camZ);
                }

                GL11.glPushMatrix();
                GL11.glTranslatef(ChunkVertexFormat.POSITION_ORIGIN - camX,
                        ChunkVertexFormat.POSITION_ORIGIN - camY,
                        ChunkVertexFormat.POSITION_ORIGIN - camZ);
                GL11.glScalef(POSITION_DECODE, POSITION_DECODE, POSITION_DECODE);

                if (this.vbo) {
                    GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, mesh.getBufferId());
                }

                if (!translucent && faceCulling) {
                    this.drawCulled(mesh, camX, camY, camZ);
                } else {
                    this.drawRange(mesh, 0, mesh.getQuadCount());
                }

                GL11.glPopMatrix();
                drawn++;
            }
        } finally {
            this.end();
        }

        return drawn;
    }

    private void drawCulled(ChunkMesh mesh, float camX, float camY, float camZ) {
        // groups are stored contiguously in facing order, so adjacent visible groups merge into one draw
        int rangeStart = -1, rangeEnd = -1;

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            int groupCount = mesh.getGroupCount(facing);
            if (groupCount == 0) {
                continue;
            }

            int groupStart = mesh.getGroupStart(facing);

            if (!mesh.isGroupVisible(facing, camX, camY, camZ)) {
                continue;
            }

            if (rangeEnd == groupStart) {
                rangeEnd += groupCount;
            } else {
                if (rangeStart >= 0) {
                    this.drawRange(mesh, rangeStart, rangeEnd - rangeStart);
                }
                rangeStart = groupStart;
                rangeEnd = groupStart + groupCount;
            }
        }

        if (rangeStart >= 0) {
            this.drawRange(mesh, rangeStart, rangeEnd - rangeStart);
        }
    }

    private void drawRange(ChunkMesh mesh, int firstQuad, int quadCount) {
        while (quadCount > 0) {
            int batch = Math.min(quadCount, SharedQuadIndexBuffer.MAX_QUADS);
            this.setPointers(mesh, firstQuad * 4);

            if (this.vbo) {
                GL11.glDrawElements(GL11.GL_TRIANGLES, batch * SharedQuadIndexBuffer.INDICES_PER_QUAD, GL11.GL_UNSIGNED_SHORT, 0L);
            } else {
                ShortBuffer indices = this.indexBuffer.getClientIndices().duplicate();
                indices.position(0).limit(batch * SharedQuadIndexBuffer.INDICES_PER_QUAD);
                GL11.glDrawElements(GL11.GL_TRIANGLES, indices);
            }

            this.drawCalls++;
            firstQuad += batch;
            quadCount -= batch;
        }
    }

    private void setPointers(ChunkMesh mesh, int baseVertex) {
        long base = (long) baseVertex * ChunkVertexFormat.STRIDE;

        if (this.vbo) {
            GL11.glVertexPointer(3, GL11.GL_SHORT, ChunkVertexFormat.STRIDE, base + ChunkVertexFormat.POSITION_OFFSET);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, ChunkVertexFormat.STRIDE, base + ChunkVertexFormat.TEXTURE_OFFSET);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, ChunkVertexFormat.STRIDE, base + ChunkVertexFormat.COLOR_OFFSET);
        } else {
            ByteBuffer data = mesh.getClientData();
            GL11.glVertexPointer(3, GL11.GL_SHORT, ChunkVertexFormat.STRIDE, slice(data, base + ChunkVertexFormat.POSITION_OFFSET));
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, ChunkVertexFormat.STRIDE, slice(data, base + ChunkVertexFormat.TEXTURE_OFFSET));
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, ChunkVertexFormat.STRIDE, slice(data, base + ChunkVertexFormat.COLOR_OFFSET));
        }
    }

    private static ByteBuffer slice(ByteBuffer data, long offset) {
        ByteBuffer dup = data.duplicate();
        dup.position((int) offset);
        return dup;
    }

    private void begin() {
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glScalef(TEXTURE_DECODE, TEXTURE_DECODE, 1.0f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        if (this.vbo) {
            GlCompat.bindBuffer(GlCompat.ELEMENT_ARRAY_BUFFER, this.indexBuffer.getBufferId());
        }
    }

    private void end() {
        if (this.vbo) {
            GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, 0);
            GlCompat.bindBuffer(GlCompat.ELEMENT_ARRAY_BUFFER, 0);
        }

        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
    }

    public void delete() {
        this.indexBuffer.delete();
    }
}
