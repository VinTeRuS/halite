package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.client.gui.HaliteOptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Adds a "Halite Settings..." button to the vanilla Video Settings screen. */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin extends Screen {
    private static final int HALITE_BUTTON_ID = 210;

    private static final int DONE_BUTTON_ID = 200;

    /**
     * Mods such as UniTweaks add options to this screen, so the button goes in the first free row below them. When
     * that row would touch "Done", the Done row is split in two instead.
     */
    @SuppressWarnings("unchecked")
    @Inject(method = "init", at = @At("TAIL"))
    private void halite$addButton(CallbackInfo ci) {
        List<ButtonWidget> buttons = this.buttons;
        int doneIndex = -1;
        int nextRowY = this.height / 6;
        for (int i = 0; i < buttons.size(); i++) {
            ButtonWidget button = buttons.get(i);
            if (button.id == DONE_BUTTON_ID) {
                doneIndex = i;
            } else {
                nextRowY = Math.max(nextRowY, button.y + 24);
            }
        }

        String label = "Halite Settings...";
        if (doneIndex < 0) {
            buttons.add(new ButtonWidget(HALITE_BUTTON_ID, this.width / 2 - 100, nextRowY, label));
            return;
        }

        ButtonWidget done = buttons.get(doneIndex);
        if (nextRowY + 20 < done.y) {
            buttons.add(new ButtonWidget(HALITE_BUTTON_ID, this.width / 2 - 100, nextRowY, label));
        } else {
            buttons.set(doneIndex, new ButtonWidget(DONE_BUTTON_ID, this.width / 2 - 155, done.y, 150, 20, done.text));
            buttons.add(new ButtonWidget(HALITE_BUTTON_ID, this.width / 2 + 5, done.y, 150, 20, label));
        }
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void halite$openSettings(ButtonWidget button, CallbackInfo ci) {
        if (button.active && button.id == HALITE_BUTTON_ID) {
            this.minecraft.setScreen(new HaliteOptionsScreen(this));
            ci.cancel();
        }
    }
}
