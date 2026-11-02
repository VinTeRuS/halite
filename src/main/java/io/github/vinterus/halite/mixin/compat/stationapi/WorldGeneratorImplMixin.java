package io.github.vinterus.halite.mixin.compat.stationapi;

import io.github.vinterus.halite.world.gen.TerrainThread;
import net.minecraft.world.World;
import net.modificationstation.stationapi.impl.worldgen.WorldGeneratorImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** On terrain threads, StationAPI's height shaping runs on per-thread caches instead of its static ones. */
@Mixin(value = WorldGeneratorImpl.class, remap = false)
public class WorldGeneratorImplMixin {
    @Inject(method = "updateNoise", at = @At("HEAD"), cancellable = true)
    private static void halite$threadLocalNoise(World world, int cx, int cz, double[] data, CallbackInfo ci) {
        if (TerrainThread.isCurrent()) {
            TerrainThread.noiseHeights().updateNoise(world, cx, cz, data);
            ci.cancel();
        }
    }
}
