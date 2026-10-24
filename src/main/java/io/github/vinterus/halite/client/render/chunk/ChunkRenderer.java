package io.github.vinterus.halite.client.render.chunk;

import io.github.vinterus.halite.client.gl.GlCompat;
import io.github.vinterus.halite.client.gl.SharedQuadIndexBuffer;
import io.github.vinterus.halite.client.render.chunk.region.BufferPage;
import io.github.vinterus.halite.client.render.chunk.region.MeshPart;
import io.github.vinterus.halite.client.render.chunk.region.RenderRegion;
import io.github.vinterus.halite.client.render.viewport.Viewport;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draws section meshes with fixed-function client state, which every target (desktop GL, gl4es on GLES 2) handles.
 *
 * <p>Opaque geometry is drawn page by page: the visible parts of a page are sorted by position and parts that sit
 * next to each other in the buffer are submitted as one draw call. Translucent geometry has to be drawn back to
 * front, so it goes section by section.
 */
public class ChunkRenderer {
    private static final float POSITION_DECODE = 1.0f / ChunkVertexFormat.POSITION_SCALE;
    private static final float TEXTURE_DECODE = 1.0f / ChunkVertexFormat.TEXTURE_SCALE;
    private static final Comparator<MeshPart> BY_POSITION = Comparator.comparingInt(MeshPart::getFirstQuad);

    // Gaps up to this many quads between visible parts are drawn through (as hidden or zeroed geometry) rather than
    // starting a new draw call; draw calls are far more expensive than vertices on mobile drivers.
    private static final int MAX_BRIDGED_GAP = 1024;
    // Lone sections bigger than this are still split by facing to skip back faces.
    private static final int FACE_CULL_MIN_QUADS = 4096;

    private final SharedQuadIndexBuffer indexBuffer = new SharedQuadIndexBuffer();
    private final boolean vbo = GlCompat.useVbo();
    private final List<BufferPage> pages = new ArrayList<>();

    private int frame;
    private int drawCalls;
    private BufferPage boundPage;
    private RenderRegion boundRegion;

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
        try {
            if (pass == RenderSection.PASS_TRANSLUCENT) {
                this.renderTranslucent(sections, viewport, sort);
            } else {
                this.renderBatched(sections, pass, viewport, faceCulling);
            }
        } finally {
            this.end();
        }

        return sections.size();
    }

    private void renderBatched(List<RenderSection> sections, int pass, Viewport viewport, boolean faceCulling) {
        int frame = ++this.frame;
        this.pages.clear();

        // pages in the order their nearest section was visited, so near geometry still tends to go first
        for (RenderSection section : sections) {
            ChunkMesh mesh = section.getMesh(pass);
            if (mesh == null || mesh.getParts() == null) {
                continue;
            }
            for (MeshPart part : mesh.getParts()) {
                BufferPage page = part.getPage();
                if (page.visibleFrame != frame) {
                    page.visibleFrame = frame;
                    page.visibleParts.clear();
                    this.pages.add(page);
                }
                page.visibleParts.add(part);
            }
        }

        for (BufferPage page : this.pages) {
            List<MeshPart> parts = page.visibleParts;
            parts.sort(BY_POSITION);
            this.bindPage(page, viewport);

            int i = 0;
            while (i < parts.size()) {
                MeshPart first = parts.get(i);
                int start = first.getFirstQuad();
                int end = start + first.getQuadCount();
                int j = i + 1;
                while (j < parts.size() && parts.get(j).getFirstQuad() - end <= MAX_BRIDGED_GAP) {
                    MeshPart next = parts.get(j);
                    end = next.getFirstQuad() + next.getQuadCount();
                    j++;
                }

                if (j == i + 1 && faceCulling && first.getQuadCount() >= FACE_CULL_MIN_QUADS
                        && first.getMesh().getParts().length == 1) {
                    // a lone section: still worth skipping the faces that point away
                    this.drawCulled(first, viewport);
                } else {
                    this.drawRange(page, start, end - start);
                }
                i = j;
            }
            parts.clear();
        }
        this.pages.clear();
    }

    private void renderTranslucent(List<RenderSection> sections, Viewport viewport, boolean sort) {
        for (int i = sections.size() - 1; i >= 0; i--) {
            ChunkMesh mesh = sections.get(i).getMesh(RenderSection.PASS_TRANSLUCENT);
            if (mesh == null || mesh.getParts() == null) {
                continue;
            }

            if (sort && mesh.sortTranslucent((float) (viewport.x - mesh.getOriginX()),
                    (float) (viewport.y - mesh.getOriginY()), (float) (viewport.z - mesh.getOriginZ()))) {
                // the upload rebound the array buffer
                this.boundPage = null;
            }

            for (MeshPart part : mesh.getParts()) {
                this.bindPage(part.getPage(), viewport);
                this.drawRange(part.getPage(), part.getFirstQuad(), part.getQuadCount());
            }
        }
    }

    private void drawCulled(MeshPart part, Viewport viewport) {
        ChunkMesh mesh = part.getMesh();
        float camX = (float) (viewport.x - mesh.getOriginX());
        float camY = (float) (viewport.y - mesh.getOriginY());
        float camZ = (float) (viewport.z - mesh.getOriginZ());

        // facing groups are stored back to back, so adjacent visible groups merge into one draw
        int base = part.getFirstQuad();
        int rangeStart = -1, rangeEnd = -1;

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            int count = mesh.getGroupCount(facing);
            if (count == 0 || !mesh.isGroupVisible(facing, camX, camY, camZ)) {
                continue;
            }

            int start = base + mesh.getGroupStart(facing);
            if (rangeEnd == start) {
                rangeEnd += count;
            } else {
                if (rangeStart >= 0) {
                    this.drawRange(part.getPage(), rangeStart, rangeEnd - rangeStart);
                }
                rangeStart = start;
                rangeEnd = start + count;
            }
        }

        if (rangeStart >= 0) {
            this.drawRange(part.getPage(), rangeStart, rangeEnd - rangeStart);
        }
    }

    private void bindPage(BufferPage page, Viewport viewport) {
        if (page == this.boundPage) {
            return;
        }

        RenderRegion region = page.getRegion();
        if (region != this.boundRegion) {
            if (this.boundRegion != null) {
                GL11.glPopMatrix();
            }
            GL11.glPushMatrix();
            GL11.glTranslatef((float) (region.getCenterX() - viewport.x), (float) (region.getCenterY() - viewport.y),
                    (float) (region.getCenterZ() - viewport.z));
            GL11.glScalef(POSITION_DECODE, POSITION_DECODE, POSITION_DECODE);
            this.boundRegion = region;
        }

        if (this.vbo) {
            GlCompat.bindBuffer(GlCompat.ARRAY_BUFFER, page.getBufferId());
            GL11.glVertexPointer(3, GL11.GL_SHORT, ChunkVertexFormat.STRIDE, ChunkVertexFormat.POSITION_OFFSET);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, ChunkVertexFormat.STRIDE, ChunkVertexFormat.TEXTURE_OFFSET);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, ChunkVertexFormat.STRIDE, ChunkVertexFormat.COLOR_OFFSET);
        } else {
            ByteBuffer data = page.getClientData();
            GL11.glVertexPointer(3, GL11.GL_SHORT, ChunkVertexFormat.STRIDE, slice(data, ChunkVertexFormat.POSITION_OFFSET));
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, ChunkVertexFormat.STRIDE, slice(data, ChunkVertexFormat.TEXTURE_OFFSET));
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, ChunkVertexFormat.STRIDE, slice(data, ChunkVertexFormat.COLOR_OFFSET));
        }
        this.boundPage = page;
    }

    private void drawRange(BufferPage page, int firstQuad, int quadCount) {
        if (quadCount <= 0) {
            return;
        }
        int indices = quadCount * SharedQuadIndexBuffer.INDICES_PER_QUAD;
        if (this.vbo) {
            GL11.glDrawElements(GL11.GL_TRIANGLES, indices, GL11.GL_UNSIGNED_SHORT,
                    (long) firstQuad * SharedQuadIndexBuffer.INDICES_PER_QUAD * 2);
        } else {
            ShortBuffer buffer = this.indexBuffer.getClientIndices().duplicate();
            int offset = firstQuad * SharedQuadIndexBuffer.INDICES_PER_QUAD;
            buffer.limit(offset + indices).position(offset);
            GL11.glDrawElements(GL11.GL_TRIANGLES, buffer);
        }
        this.drawCalls++;
    }

    private static ByteBuffer slice(ByteBuffer data, int offset) {
        ByteBuffer dup = data.duplicate();
        dup.position(offset);
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
        this.boundPage = null;
        this.boundRegion = null;
    }

    private void end() {
        if (this.boundRegion != null) {
            GL11.glPopMatrix();
            this.boundRegion = null;
        }
        this.boundPage = null;

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
