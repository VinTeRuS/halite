package io.github.vinterus.halite.mixin.core;

import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Levers are meshed on chunk builder threads, but their handle uses the global {@link Vec3d} pool, which the
 * render thread uses for ray casts at the same time. The handle's eight corners get plain instances instead.
 */
@Mixin(BlockRenderManager.class)
public class BlockRenderManagerMixin {
    @Redirect(method = "renderLever", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/math/Vec3d;createCached(DDD)Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d halite$unpooledVector(double x, double y, double z) {
        return Vec3d.create(x, y, z);
    }
}
