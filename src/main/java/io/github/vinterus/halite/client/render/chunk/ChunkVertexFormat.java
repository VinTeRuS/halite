package io.github.vinterus.halite.client.render.chunk;

/**
 * Compact terrain vertex layout, 16 bytes per vertex (half of the vanilla tessellator's 32):
 *
 * <pre>
 *  0  short x, y, z   position relative to the section center, in 1/1024 block units
 *  6  short           padding
 *  8  short u, v      texture coordinates in 1/32767 units
 * 12  ubyte r, g, b, a
 * </pre>
 *
 * Only plain GL 1.1 attribute types are used so the format works through fixed-function client state on
 * desktop drivers as well as on GLES translation layers (gl4es) used by mobile launchers. The scale factors are
 * undone with the modelview and texture matrices at draw time.
 */
public final class ChunkVertexFormat {
    public static final int STRIDE = 16;

    public static final int POSITION_OFFSET = 0;
    public static final int TEXTURE_OFFSET = 8;
    public static final int COLOR_OFFSET = 12;

    public static final float POSITION_SCALE = 1024.0f;
    public static final float POSITION_ORIGIN = 8.0f;
    public static final float TEXTURE_SCALE = 32767.0f;

    private ChunkVertexFormat() {
    }

    public static short encodePosition(float v) {
        return clampShort(Math.round((v - POSITION_ORIGIN) * POSITION_SCALE));
    }

    public static short encodeTexture(float v) {
        return clampShort(Math.round(v * TEXTURE_SCALE));
    }

    private static short clampShort(int v) {
        if (v > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (v < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) v;
    }
}
