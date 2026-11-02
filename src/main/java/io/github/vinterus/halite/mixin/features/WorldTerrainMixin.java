package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.world.gen.TerrainThread;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Terrain threads get a biome source of their own: generation writes its results into the biome source's fields. */
@Mixin(World.class)
public class WorldTerrainMixin {
    @Inject(method = "method_1781", at = @At("HEAD"), cancellable = true)
    private void halite$threadBiomeSource(CallbackInfoReturnable<BiomeSource> cir) {
        BiomeSource own = TerrainThread.biomeSourceFor((World) (Object) this);
        if (own != null) {
            cir.setReturnValue(own);
        }
    }
}
