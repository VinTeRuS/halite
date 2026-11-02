package io.github.vinterus.halite.mixin.features;

import io.github.vinterus.halite.client.render.HaliteWorldRenderer;
import io.github.vinterus.halite.world.gen.TerrainPregen;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Starts and feeds background terrain generation for single player worlds. */
@Mixin(Minecraft.class)
public class MinecraftTerrainMixin {
    @Shadow public World world;
    @Shadow public ClientPlayerEntity player;

    @Unique private int halite$terrainTicks;

    @Inject(method = "setWorld(Lnet/minecraft/world/World;Ljava/lang/String;Lnet/minecraft/entity/player/PlayerEntity;)V",
            at = @At("HEAD"))
    private void halite$stopTerrain(World world, String message, PlayerEntity player, CallbackInfo ci) {
        if (world == null || world.isRemote || world != this.world) {
            TerrainPregen.detach();
        }
    }

    /** The spawn area is loaded chunk by chunk right here, so queue all of it, in the order it will be asked for. */
    @Inject(method = "prepareWorld", at = @At("HEAD"))
    private void halite$startTerrain(String worldName, CallbackInfo ci) {
        if (!(this.world.getChunkSource() instanceof ChunkCache cache)) {
            return;
        }
        TerrainPregen.attach(this.world, cache);
        TerrainPregen pregen = TerrainPregen.active();
        if (pregen == null) {
            return;
        }

        // same area and order as vanilla prepareWorld
        Vec3i spawn = this.world.getSpawnPos();
        int x = spawn.x, z = spawn.z;
        if (this.player != null) {
            x = (int) this.player.x;
            z = (int) this.player.z;
        }
        for (int dx = -128; dx <= 128; dx += 16) {
            for (int dz = -128; dz <= 128; dz += 16) {
                pregen.request(cache, (x + dx) >> 4, (z + dz) >> 4, true);
            }
        }
    }

    /** Keeps the chunks around the player that will be needed for drawing queued, nearest first. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void halite$feedTerrain(CallbackInfo ci) {
        TerrainPregen pregen = TerrainPregen.active();
        if (pregen == null || this.player == null || !(this.world.getChunkSource() instanceof ChunkCache cache)
                || ++this.halite$terrainTicks % 5 != 0) {
            return;
        }

        HaliteWorldRenderer renderer = HaliteWorldRenderer.instance();
        int radius = (renderer != null ? renderer.getRenderDistance() : 8) + 1;
        int cx = (int) Math.floor(this.player.x) >> 4, cz = (int) Math.floor(this.player.z) >> 4;
        pregen.evictOutside(cx, cz, radius + 4);

        for (int ring = 0; ring <= radius; ring++) {
            for (int i = -ring; i <= ring; i++) {
                if (!pregen.request(cache, cx + i, cz - ring, false)
                        || !pregen.request(cache, cx + i, cz + ring, false)) {
                    return;
                }
            }
            for (int i = -ring + 1; i < ring; i++) {
                if (!pregen.request(cache, cx - ring, cz + i, false)
                        || !pregen.request(cache, cx + ring, cz + i, false)) {
                    return;
                }
            }
        }
    }
}
