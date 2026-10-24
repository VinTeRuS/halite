package io.github.vinterus.halite.mixin.compat.stationapi;

import io.github.vinterus.halite.client.render.chunk.compile.ChunkBuilderThread;
import io.github.vinterus.halite.client.render.chunk.compile.ThreadAwareRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.modificationstation.stationapi.api.block.BlockState;
import net.modificationstation.stationapi.api.client.render.model.BakedModel;
import net.modificationstation.stationapi.api.client.render.model.BakedModelRenderer;
import net.modificationstation.stationapi.impl.client.arsenic.renderer.render.BakedModelRendererImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * StationAPI's baked model renderer is a singleton with scratch state (lighting caches, a Random). Block
 * rendering on a chunk builder thread is forwarded to that thread's own instance.
 */
@Mixin(value = BakedModelRendererImpl.class, remap = false)
public abstract class BakedModelRendererImplMixin implements ThreadAwareRenderer {
    private BakedModelRenderer halite$forward() {
        if (Thread.currentThread() instanceof ChunkBuilderThread worker
                && worker.getBakedModelRenderer() instanceof BakedModelRenderer own && own != (Object) this) {
            return own;
        }
        return null;
    }

    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void halite$forwardRenderBlock(BlockState state, BlockPos pos, BlockView world, boolean cull, Random random,
                                           CallbackInfoReturnable<Boolean> cir) {
        BakedModelRenderer own = this.halite$forward();
        if (own != null) {
            cir.setReturnValue(own.renderBlock(state, pos, world, cull, random));
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void halite$forwardRender(BlockView world, BakedModel model, BlockState state, BlockPos pos, boolean cull,
                                      Random random, long seed, CallbackInfoReturnable<Boolean> cir) {
        BakedModelRenderer own = this.halite$forward();
        if (own != null) {
            cir.setReturnValue(own.render(world, model, state, pos, cull, random, seed));
        }
    }
}
