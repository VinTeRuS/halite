package io.github.vinterus.halite.mixin.core;

import io.github.vinterus.halite.client.render.HaliteWorldRenderer;
import io.github.vinterus.halite.client.render.HaliteWorldRendererHolder;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Culler;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Routes the vanilla terrain pipeline (chunk lists, culling, compilation, dirty tracking) into Halite.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin implements HaliteWorldRendererHolder {
    @Shadow private Minecraft client;
    @Shadow public List globalBlockEntities;
    @Shadow private int lastViewDistance;
    @Shadow private int entityRenderCooldown;

    @Unique
    private HaliteWorldRenderer halite$renderer;

    @Override
    public HaliteWorldRenderer halite$getRenderer() {
        return this.halite$renderer;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void halite$init(Minecraft minecraft, TextureManager textureManager, CallbackInfo ci) {
        this.halite$renderer = new HaliteWorldRenderer(minecraft);
    }

    @Inject(method = "setWorld", at = @At("HEAD"))
    private void halite$setWorld(World world, CallbackInfo ci) {
        this.halite$renderer.setWorld(world);
    }

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private void halite$reload(CallbackInfo ci) {
        Block.LEAVES.setFancyGraphics(this.client.options.fancyGraphics);
        this.lastViewDistance = this.client.options.viewDistance;
        this.globalBlockEntities.clear();
        this.halite$renderer.reload();
        this.entityRenderCooldown = 2;
        ci.cancel();
    }

    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;ID)I", at = @At("HEAD"), cancellable = true)
    private void halite$renderLayer(LivingEntity camera, int layer, double tickDelta, CallbackInfoReturnable<Integer> cir) {
        if (this.client.options.viewDistance != this.lastViewDistance) {
            ((WorldRenderer) (Object) this).reload();
        }
        cir.setReturnValue(this.halite$renderer.renderLayer(layer));
    }

    @Inject(method = "renderLastChunks", at = @At("HEAD"), cancellable = true)
    private void halite$renderLastChunks(int layer, double tickDelta, CallbackInfo ci) {
        // second half of the fancy-graphics translucent pass: depth was written first, now draw colour
        this.halite$renderer.renderLayer(layer);
        ci.cancel();
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "cullChunks", at = @At("HEAD"), cancellable = true)
    private void halite$cullChunks(Culler culler, float tickDelta, CallbackInfo ci) {
        this.halite$renderer.updateVisibility(culler, tickDelta);

        // only block entities in visible sections get rendered
        this.globalBlockEntities.clear();
        this.globalBlockEntities.addAll(this.halite$renderer.getVisibleBlockEntities());
        ci.cancel();
    }

    @Inject(method = "compileChunks", at = @At("HEAD"), cancellable = true)
    private void halite$compileChunks(LivingEntity camera, boolean force, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.halite$renderer.buildChunks());
    }

    @Inject(method = "blockUpdate", at = @At("HEAD"), cancellable = true)
    private void halite$blockUpdate(int x, int y, int z, CallbackInfo ci) {
        this.halite$renderer.markDirty(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
        ci.cancel();
    }

    @Inject(method = "setBlocksDirty", at = @At("HEAD"), cancellable = true)
    private void halite$setBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        this.halite$renderer.markDirty(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);
        ci.cancel();
    }

    @Inject(method = "notifyAmbientDarknessChanged", at = @At("HEAD"), cancellable = true)
    private void halite$ambientDarknessChanged(CallbackInfo ci) {
        this.halite$renderer.onAmbientDarknessChanged();
        ci.cancel();
    }

    @Inject(method = "getChunkDebugInfo", at = @At("HEAD"), cancellable = true)
    private void halite$debugInfo(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(this.halite$renderer.getDebugString());
    }

    @Redirect(method = "renderEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Culler;isVisible(Lnet/minecraft/util/math/Box;)Z"))
    private boolean halite$cullEntity(Culler culler, Box box) {
        return culler.isVisible(box) && this.halite$renderer.isEntityVisible(box);
    }
}
