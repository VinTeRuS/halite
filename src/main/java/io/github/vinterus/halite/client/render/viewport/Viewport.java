package io.github.vinterus.halite.client.render.viewport;

import net.minecraft.client.render.Culler;
import net.minecraft.client.render.FrustumCuller;
import net.minecraft.util.math.Box;

/**
 * Camera position plus the vanilla frustum for one frame.
 */
public final class Viewport {
    public final double x, y, z;
    public final int intX, intY, intZ;
    public final float fracX, fracY, fracZ;
    public final int chunkX, chunkY, chunkZ;

    private final Culler culler;
    private final FrustumCuller frustum;

    public Viewport(Culler culler, double x, double y, double z) {
        this.culler = culler;
        this.frustum = culler instanceof FrustumCuller fc ? fc : null;
        this.x = x;
        this.y = y;
        this.z = z;
        this.intX = (int) Math.floor(x);
        this.intY = (int) Math.floor(y);
        this.intZ = (int) Math.floor(z);
        this.fracX = (float) (x - this.intX);
        this.fracY = (float) (y - this.intY);
        this.fracZ = (float) (z - this.intZ);
        this.chunkX = this.intX >> 4;
        this.chunkY = this.intY >> 4;
        this.chunkZ = this.intZ >> 4;
    }

    public boolean isBoxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (this.frustum != null) {
            return this.frustum.intersectsFrustum(minX, minY, minZ, maxX, maxY, maxZ);
        }
        return this.culler.isVisible(Box.createCached(minX, minY, minZ, maxX, maxY, maxZ));
    }
}
