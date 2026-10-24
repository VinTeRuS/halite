package io.github.vinterus.halite.client.render.chunk;

/**
 * Geometry inside a section is grouped by the direction its quads face, so whole groups that point away from the
 * camera can be skipped without submitting them (the GPU would back-face cull them anyway).
 */
public final class ModelQuadFacing {
    public static final int POS_X = 0;
    public static final int NEG_X = 1;
    public static final int POS_Y = 2;
    public static final int NEG_Y = 3;
    public static final int POS_Z = 4;
    public static final int NEG_Z = 5;
    public static final int UNASSIGNED = 6;

    public static final int COUNT = 7;

    private static final float EPSILON = 1.0e-4f;

    private ModelQuadFacing() {
    }

    /**
     * Classifies a quad from its winding normal. Quads that are not exactly axis-aligned are left unassigned and
     * are always drawn.
     */
    public static int fromNormal(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);

        if (ay < EPSILON && az < EPSILON && ax > EPSILON) {
            return nx > 0 ? POS_X : NEG_X;
        }
        if (ax < EPSILON && az < EPSILON && ay > EPSILON) {
            return ny > 0 ? POS_Y : NEG_Y;
        }
        if (ax < EPSILON && ay < EPSILON && az > EPSILON) {
            return nz > 0 ? POS_Z : NEG_Z;
        }

        return UNASSIGNED;
    }

    public static boolean isPositive(int facing) {
        return (facing & 1) == 0;
    }

    /** Axis index (0 = x, 1 = y, 2 = z) for an assigned facing. */
    public static int axis(int facing) {
        return facing >> 1;
    }
}
