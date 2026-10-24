package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.client.gui.HaliteOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Halite Settings..." button to the vanilla Video Settings screen. */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends Screen {
    private static final int HALITE_BUTTON_ID = 210;

    @SuppressWarnings("unchecked")
    @Inject(method = "init", at = @At("TAIL"))
    private void halite$addButton(CallbackInfo ci) {
        this.buttons.add(new ButtonWidget(HALITE_BUTTON_ID, this.width / 2 - 100, this.height / 6 + 120, "Halite Settings..."));
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void halite$openSettings(ButtonWidget button, CallbackInfo ci) {
        if (button.active && button.id == HALITE_BUTTON_ID) {
            this.minecraft.setScreen(new HaliteOptionsScreen(this));
            ci.cancel();
        }
    }
}
