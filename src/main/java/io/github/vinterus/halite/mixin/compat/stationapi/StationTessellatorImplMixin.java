package io.github.vinterus.halite.mixin.compat.stationapi;

import io.github.vinterus.halite.client.render.chunk.compile.ChunkBuilderThread;
import io.github.vinterus.halite.client.render.chunk.compile.ThreadAwareRenderer;
import net.minecraft.client.render.Tessellator;
import net.modificationstation.stationapi.api.client.render.model.BakedQuad;
import net.modificationstation.stationapi.impl.client.render.StationTessellatorImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forwards StationAPI's quad emission on the global tessellator to the chunk builder thread's own one. */
@Mixin(value = StationTessellatorImpl.class, remap = false)
public abstract class StationTessellatorImplMixin implements ThreadAwareRenderer {
    @Shadow @Final private Tessellator self;

    @Inject(method = "quad", at = @At("HEAD"), cancellable = true)
    private void halite$forwardQuad(BakedQuad quad, float x, float y, float z, int colour0, int colour1, int colour2,
                                    int colour3, float normalX, float normalY, float normalZ, boolean spreadUV,
                                    CallbackInfo ci) {
        Tessellator target = ChunkBuilderThread.redirect(this.self);
        if (target != null) {
            target.quad(quad, x, y, z, colour0, colour1, colour2, colour3, normalX, normalY, normalZ, spreadUV);
            ci.cancel();
        }
    }

    @Inject(method = "ensureBufferCapacity", at = @At("HEAD"), cancellable = true)
    private void halite$forwardEnsureCapacity(int criticalCapacity, CallbackInfo ci) {
        Tessellator target = ChunkBuilderThread.redirect(this.self);
        if (target != null) {
            target.ensureBufferCapacity(criticalCapacity);
            ci.cancel();
        }
    }
}
