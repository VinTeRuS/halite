package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes fog and the far clip plane follow Halite's render distance instead of the vanilla button.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow private Minecraft client;
    @Shadow private float viewDistance;

    @Inject(method = "renderWorld", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/client/render/GameRenderer;viewDistance:F", shift = At.Shift.AFTER))
    private void halite$applyRenderDistance(float tickDelta, int eye, CallbackInfo ci) {
        this.viewDistance = HaliteClientMod.options().getEffectiveRenderDistance(this.client.options.viewDistance) * 16.0f;
    }
}
