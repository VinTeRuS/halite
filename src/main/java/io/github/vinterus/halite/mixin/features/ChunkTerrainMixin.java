package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.world.gen.TerrainThread;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Column light gaps look at neighbouring chunks and queue world light updates, so terrain threads leave them out;
 * TerrainPregen runs them on the game thread when the chunk joins the world.
 */
@Mixin(Chunk.class)
public class ChunkTerrainMixin {
    @Inject(method = "lightGaps", at = @At("HEAD"), cancellable = true)
    private void halite$deferLightGaps(int x, int z, CallbackInfo ci) {
        if (TerrainThread.isCurrent()) {
            ci.cancel();
        }
    }
}
