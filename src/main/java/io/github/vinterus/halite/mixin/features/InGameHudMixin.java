package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.client.HaliteStats;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.ScreenScaler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Adds Halite's statistics to the right side of the vanilla F3 screen. With BetterF3 installed they go into its
 * "Halite" module instead.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin extends DrawContext {
    @Unique
    private static final boolean BETTER_F3 = FabricLoader.getInstance().isModLoaded("betterf3");

    @Shadow private Minecraft minecraft;

    @Inject(method = "render", at = @At("TAIL"))
    private void halite$renderStats(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) {
        if (BETTER_F3 || !this.minecraft.options.debugHud || this.minecraft.world == null) {
            return;
        }

        TextRenderer text = this.minecraft.textRenderer;
        int width = new ScreenScaler(this.minecraft.options, this.minecraft.displayWidth, this.minecraft.displayHeight)
                .getScaledWidth();

        // below the vanilla memory lines
        int y = 26;
        List<String> lines = HaliteStats.lines();
        for (String line : lines) {
            this.drawTextWithShadow(text, line, width - text.getWidth(line) - 2, y, 0xE0E0E0);
            y += 10;
        }
    }
}
