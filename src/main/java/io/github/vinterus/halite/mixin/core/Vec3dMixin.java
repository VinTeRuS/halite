package io.github.vinterus.halite.mixin.core;

import io.github.vinterus.halite.client.render.chunk.compile.ChunkBuilderThread;
import io.github.vinterus.halite.world.gen.TerrainThread;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The {@link Vec3d} pool is a plain list shared with the game thread, which uses it for ray casts every tick. Code
 * that Halite runs on its own threads uses it too: fluid flow while meshing water, lever handles, and vector math
 * such as {@code add} and {@code normalize}. On those threads vectors are plain instances instead, so the pool is
 * never touched concurrently.
 */
@Mixin(Vec3d.class)
public class Vec3dMixin {
    @Inject(method = "createCached", at = @At("HEAD"), cancellable = true)
    private static void halite$unpooledOffThread(double x, double y, double z, CallbackInfoReturnable<Vec3d> cir) {
        Thread thread = Thread.currentThread();
        if (thread instanceof ChunkBuilderThread || thread instanceof TerrainThread) {
            cir.setReturnValue(Vec3d.create(x, y, z));
        }
    }
}
