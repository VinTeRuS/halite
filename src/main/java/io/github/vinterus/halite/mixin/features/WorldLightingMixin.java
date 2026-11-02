package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.minecraft.world.World;
import net.minecraft.world.chunk.light.LightUpdate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Works through the light update queue for a time budget instead of vanilla's fixed 500 updates per call.
 *
 * <p>The client drains the queue once per frame. New chunks queue sky light updates faster than 500 a frame while
 * travelling, so the queue grows to hundreds of thousands of entries and new terrain keeps dark patches for a long
 * time. Most entries are cheap no-ops, so a few milliseconds clear far more of them. Updates are still processed one
 * by one, newest first, exactly as vanilla does, so the resulting light is the same.
 */
@Mixin(World.class)
public class WorldLightingMixin {
    private static final int VANILLA_UPDATES_PER_CALL = 500;

    @Shadow private List<LightUpdate> lightingQueue;
    @Shadow private int lightingUpdateCount;

    @Inject(method = "doLightingUpdates", at = @At("HEAD"), cancellable = true)
    private void halite$timedLightUpdates(CallbackInfoReturnable<Boolean> cir) {
        int budgetMs = HaliteClientMod.options().lightUpdateBudgetMs;
        if (budgetMs <= 0) {
            return;
        }
        if (this.lightingUpdateCount >= 50) {
            cir.setReturnValue(false);
            return;
        }

        this.lightingUpdateCount++;
        try {
            long deadline = System.nanoTime() + budgetMs * 1_000_000L;
            List<LightUpdate> queue = this.lightingQueue;
            World world = (World) (Object) this;
            int done = 0;
            while (!queue.isEmpty()) {
                // never fewer than vanilla; the clock is read only every 64 updates
                if (++done >= VANILLA_UPDATES_PER_CALL && (done & 63) == 0 && System.nanoTime() >= deadline) {
                    cir.setReturnValue(true);
                    return;
                }
                queue.remove(queue.size() - 1).updateLight(world);
            }
            cir.setReturnValue(false);
        } finally {
            this.lightingUpdateCount--;
        }
    }
}
