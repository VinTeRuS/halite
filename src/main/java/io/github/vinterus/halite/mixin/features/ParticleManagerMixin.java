package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.client.HaliteClientMod;
import io.github.vinterus.halite.client.render.HaliteWorldRenderer;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips particles outside the view frustum. Vanilla tessellates every particle in the world each frame.
 */
@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;render(Lnet/minecraft/client/render/Tessellator;FFFFFF)V"))
    private void halite$cullParticle(Particle particle, Tessellator tessellator, float partialTicks,
                                     float horizontalSize, float verticalSize, float depthSize,
                                     float widthOffset, float heightOffset) {
        HaliteWorldRenderer renderer = HaliteWorldRenderer.instance();
        if (renderer != null && HaliteClientMod.options().useParticleCulling
                && !renderer.isPointVisible(particle.x, particle.y, particle.z, 1.0)) {
            return;
        }
        particle.render(tessellator, partialTicks, horizontalSize, verticalSize, depthSize, widthOffset, heightOffset);
    }
}
