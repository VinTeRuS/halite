package io.github.vinterus.halite.mixin.debug;

import net.minecraft.client.InteractionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import io.github.vinterus.halite.client.HaliteClientMod;
import net.minecraft.client.SingleplayerInteractionManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Development aid: with {@code -Dhalite.debug.autoWorld=<name>} the client skips the title screen and opens (or
 * creates) that singleplayer world with the debug overlay enabled. Does nothing unless the property is set.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftDebugMixin {
    @Shadow public World world;
    @Shadow public Screen currentScreen;
    @Shadow public GameOptions options;
    @Shadow public InteractionManager interactionManager;
    @Shadow public net.minecraft.client.render.WorldRenderer worldRenderer;
    @Shadow public String debugText;
    @Shadow public net.minecraft.entity.player.ClientPlayerEntity player;
    @Shadow public int displayWidth;
    @Shadow public int displayHeight;

    @Shadow
    public abstract void startGame(String worldName, String name, long seed);

    @Shadow
    public abstract void setScreen(Screen screen);

    @Unique
    private static final String halite$AUTO_WORLD = System.getProperty("halite.debug.autoWorld");

    @Unique
    private boolean halite$autoWorldStarted;

    @Unique
    private int halite$frames;

    @Unique
    private long halite$lastReport;

    @Inject(method = "tick", at = @At("HEAD"))
    private void halite$autoWorld(CallbackInfo ci) {
        if (halite$AUTO_WORLD == null || this.halite$autoWorldStarted) {
            return;
        }
        if (this.world == null && this.currentScreen instanceof TitleScreen) {
            this.halite$autoWorldStarted = true;
            this.interactionManager = new SingleplayerInteractionManager((Minecraft) (Object) this);
            this.startGame(halite$AUTO_WORLD, halite$AUTO_WORLD, Long.getLong("halite.debug.seed", 1234L));
            this.setScreen(null);
        }
    }

    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void halite$noAutoPause(CallbackInfo ci) {
        // the virtual display used for automated runs never has focus
        if (halite$AUTO_WORLD != null) {
            ci.cancel();
        }
    }

    /** Logs renderer statistics every few seconds and takes screenshots at fixed frame counts. */
    @Inject(method = "handleScreenshotKey", at = @At("HEAD"))
    private void halite$autoReport(CallbackInfo ci) {
        if (halite$AUTO_WORLD == null || this.world == null) {
            return;
        }

        this.halite$frames++;

        // slowly turn around so every direction gets exercised
        if (this.player != null && Boolean.getBoolean("halite.debug.spin")) {
            this.player.prevYaw = this.player.yaw;
            this.player.yaw += 0.6f;
        }
        long now = System.currentTimeMillis();
        if (now - this.halite$lastReport > 5000L) {
            this.halite$lastReport = now;
            HaliteClientMod.logger().info("[debug] {} | {}", this.debugText, this.worldRenderer.getChunkDebugInfo());
        }

        if (this.halite$frames == 200 && Boolean.getBoolean("halite.debug.openSettings")) {
            this.setScreen(new io.github.vinterus.halite.client.gui.HaliteOptionsScreen(null));
        }

        if (this.halite$frames % 300 == 0 && this.halite$frames <= 1800) {
            HaliteClientMod.logger().info("[debug] {}", Screenshot.take(Minecraft.getRunDirectory(), this.displayWidth, this.displayHeight));
        }
    }
}
