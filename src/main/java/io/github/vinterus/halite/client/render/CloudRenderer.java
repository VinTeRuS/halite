package io.github.vinterus.halite.client.render;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * Fancy clouds from cached display lists. Vanilla re-tessellates every cloud face of the 6x6 tile grid twice per
 * frame (about 2.5k quads through client arrays in 72 draws). The geometry itself only depends on whether the
 * camera is below, inside or above the cloud layer; scrolling, the camera's fractional offset and the texture
 * offset are applied through the matrices, and colour through {@code glColor}.
 */
public class CloudRenderer {
    private static final float TILE_SCALE = 12.0f;
    private static final float THICKNESS = 4.0f;
    private static final int TILE_SIZE = 8;
    private static final int RADIUS = 3;
    private static final float TEX_SCALE = 0.00390625f;
    private static final float EPSILON = 9.765625E-4f;

    // face groups, each drawn with its own shade of the cloud colour
    private static final int BOTTOM = 0, TOP = 1, SIDES_X = 2, SIDES_Z = 3;
    private static final float[] SHADES = {0.7f, 1.0f, 0.9f, 0.8f};

    private int listBase;

    public void render(double cameraX, double cameraZ, float relativeHeight, int ticks, float tickDelta,
                       float red, float green, float blue, boolean anaglyph) {
        double x = (cameraX + (ticks + tickDelta) * 0.03f) / TILE_SCALE;
        double z = cameraZ / TILE_SCALE + 0.33f;
        x -= Math.floor(x / 2048.0) * 2048;
        z -= Math.floor(z / 2048.0) * 2048;

        float texOffsetX = (float) Math.floor(x) * TEX_SCALE;
        float texOffsetZ = (float) Math.floor(z) * TEX_SCALE;
        float fracX = (float) (x - Math.floor(x));
        float fracZ = (float) (z - Math.floor(z));

        boolean bottom = relativeHeight > -THICKNESS - 1.0f;
        boolean top = relativeHeight <= THICKNESS + 1.0f;

        if (this.listBase == 0) {
            this.compile();
        }

        // vanilla leaves this scale applied to the modelview matrix
        GL11.glScalef(TILE_SCALE, 1.0f, TILE_SCALE);

        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        GL11.glTranslatef(texOffsetX, texOffsetZ, 0.0f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        GL11.glPushMatrix();
        GL11.glTranslatef(-fracX, relativeHeight, -fracZ);

        for (int pass = 0; pass < 2; pass++) {
            if (pass == 0) {
                GL11.glColorMask(false, false, false, false);
            } else if (anaglyph) {
                if (GameRenderer.activeEye == 0) {
                    GL11.glColorMask(false, true, true, true);
                } else {
                    GL11.glColorMask(true, false, false, true);
                }
            } else {
                GL11.glColorMask(true, true, true, true);
            }

            for (int group = 0; group < 4; group++) {
                if ((group == BOTTOM && !bottom) || (group == TOP && !top)) {
                    continue;
                }
                float shade = SHADES[group];
                GL11.glColor4f(red * shade, green * shade, blue * shade, 0.8f);
                GL11.glCallList(this.listBase + group);
            }
        }

        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }

    private void compile() {
        this.listBase = GL11.glGenLists(4);
        for (int group = 0; group < 4; group++) {
            GL11.glNewList(this.listBase + group, GL11.GL_COMPILE);
            this.tessellate(group);
            GL11.glEndList();
        }
    }

    /** Same faces as vanilla's fancy clouds, in tile-local space without the per-frame offsets and colour. */
    private void tessellate(int group) {
        Tessellator t = Tessellator.INSTANCE;
        t.startQuads();

        for (int tx = -RADIUS + 1; tx <= RADIUS; tx++) {
            for (int tz = -RADIUS + 1; tz <= RADIUS; tz++) {
                float x0 = tx * TILE_SIZE;
                float z0 = tz * TILE_SIZE;
                float x1 = x0 + TILE_SIZE;
                float z1 = z0 + TILE_SIZE;

                switch (group) {
                    case BOTTOM -> {
                        t.vertex(x0, 0.0f, z1, x0 * TEX_SCALE, z1 * TEX_SCALE);
                        t.vertex(x1, 0.0f, z1, x1 * TEX_SCALE, z1 * TEX_SCALE);
                        t.vertex(x1, 0.0f, z0, x1 * TEX_SCALE, z0 * TEX_SCALE);
                        t.vertex(x0, 0.0f, z0, x0 * TEX_SCALE, z0 * TEX_SCALE);
                    }
                    case TOP -> {
                        float y = THICKNESS - EPSILON;
                        t.vertex(x0, y, z1, x0 * TEX_SCALE, z1 * TEX_SCALE);
                        t.vertex(x1, y, z1, x1 * TEX_SCALE, z1 * TEX_SCALE);
                        t.vertex(x1, y, z0, x1 * TEX_SCALE, z0 * TEX_SCALE);
                        t.vertex(x0, y, z0, x0 * TEX_SCALE, z0 * TEX_SCALE);
                    }
                    case SIDES_X -> {
                        if (tx > -1) {
                            for (int i = 0; i < TILE_SIZE; i++) {
                                float px = x0 + i;
                                float u = (x0 + i + 0.5f) * TEX_SCALE;
                                t.vertex(px, 0.0f, z1, u, z1 * TEX_SCALE);
                                t.vertex(px, THICKNESS, z1, u, z1 * TEX_SCALE);
                                t.vertex(px, THICKNESS, z0, u, z0 * TEX_SCALE);
                                t.vertex(px, 0.0f, z0, u, z0 * TEX_SCALE);
                            }
                        }
                        if (tx <= 1) {
                            for (int i = 0; i < TILE_SIZE; i++) {
                                float px = x0 + i + 1.0f - EPSILON;
                                float u = (x0 + i + 0.5f) * TEX_SCALE;
                                t.vertex(px, 0.0f, z1, u, z1 * TEX_SCALE);
                                t.vertex(px, THICKNESS, z1, u, z1 * TEX_SCALE);
                                t.vertex(px, THICKNESS, z0, u, z0 * TEX_SCALE);
                                t.vertex(px, 0.0f, z0, u, z0 * TEX_SCALE);
                            }
                        }
                    }
                    default -> {
                        if (tz > -1) {
                            for (int i = 0; i < TILE_SIZE; i++) {
                                float pz = z0 + i;
                                float v = (z0 + i + 0.5f) * TEX_SCALE;
                                t.vertex(x0, THICKNESS, pz, x0 * TEX_SCALE, v);
                                t.vertex(x1, THICKNESS, pz, x1 * TEX_SCALE, v);
                                t.vertex(x1, 0.0f, pz, x1 * TEX_SCALE, v);
                                t.vertex(x0, 0.0f, pz, x0 * TEX_SCALE, v);
                            }
                        }
                        if (tz <= 1) {
                            for (int i = 0; i < TILE_SIZE; i++) {
                                float pz = z0 + i + 1.0f - EPSILON;
                                float v = (z0 + i + 0.5f) * TEX_SCALE;
                                t.vertex(x0, THICKNESS, pz, x0 * TEX_SCALE, v);
                                t.vertex(x1, THICKNESS, pz, x1 * TEX_SCALE, v);
                                t.vertex(x1, 0.0f, pz, x1 * TEX_SCALE, v);
                                t.vertex(x0, 0.0f, pz, x0 * TEX_SCALE, v);
                            }
                        }
                    }
                }
            }
        }

        t.draw();
    }

    public void delete() {
        if (this.listBase != 0) {
            GL11.glDeleteLists(this.listBase, 4);
            this.listBase = 0;
        }
    }
}
