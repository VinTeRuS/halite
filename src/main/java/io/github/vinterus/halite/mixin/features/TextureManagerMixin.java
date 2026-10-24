package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.client.render.texture.AnimatedSprites;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.client.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Skips simulating and uploading animated block textures that are not on screen.
 */
@Mixin(TextureManager.class)
public class TextureManagerMixin {
    @Shadow private List dynamicTextures;

    @Unique
    private boolean halite$skipCurrent;

    @Inject(method = "tick", at = @At("HEAD"))
    private void halite$refreshTracked(CallbackInfo ci) {
        AnimatedSprites.refresh(this.dynamicTextures);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/texture/DynamicTexture;tick()V"))
    private void halite$tickIfVisible(DynamicTexture texture) {
        this.halite$skipCurrent = !AnimatedSprites.shouldAnimate(texture);
        if (!this.halite$skipCurrent) {
            texture.tick();
        }
    }

    /** The upload loop runs {@code replicate} squared times; zero skips the upload of a frozen texture. */
    @Redirect(method = "tick", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/render/texture/DynamicTexture;replicate:I"))
    private int halite$skipUpload(DynamicTexture texture) {
        return this.halite$skipCurrent ? 0 : texture.replicate;
    }

    @Inject(method = "reload", at = @At("TAIL"))
    private void halite$onReload(CallbackInfo ci) {
        AnimatedSprites.invalidate();
    }
}
