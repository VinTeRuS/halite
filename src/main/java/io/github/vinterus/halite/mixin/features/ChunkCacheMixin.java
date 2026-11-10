package io.github.vinterus.halite.mixin.features;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.vinterus.halite.world.gen.TerrainPregen;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkCache;
import net.minecraft.world.chunk.ChunkSource;
import net.minecraft.world.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/** Chunk lookups for the single player world: prepared terrain, and a cache in front of the chunk map. */
@Mixin(ChunkCache.class)
public class ChunkCacheMixin implements TerrainPregen.ChunkCacheAccess {
    @Shadow private ChunkSource generator;
    @Shadow private ChunkStorage storage;
    @Shadow private World world;

    @WrapOperation(method = "loadChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/ChunkSource;getChunk(II)Lnet/minecraft/world/chunk/Chunk;"))
    private Chunk halite$takePreparedTerrain(ChunkSource generator, int chunkX, int chunkZ, Operation<Chunk> original) {
        TerrainPregen pregen = TerrainPregen.active();
        if (pregen != null && generator == this.generator) {
            Chunk prepared = pregen.take(this.world, chunkX, chunkZ);
            if (prepared != null) {
                return prepared;
            }
        }
        return original.call(generator, chunkX, chunkZ);
    }

    // A small cache in front of chunkByPos, a HashMap with boxed keys that every block, light and height lookup in
    // the world goes through, mostly for the same few chunks in a row (light updates hit one chunk thousands of
    // times). Keyed by the same hash as the map; cleared whenever the map changes. Game thread only, like the map.
    @Unique private final int[] halite$keys = new int[16];
    @Unique private final Chunk[] halite$chunks = new Chunk[16];

    @Unique
    private static int halite$slot(int chunkX, int chunkZ) {
        return (chunkX & 3) | (chunkZ & 3) << 2;
    }

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void halite$cachedChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        int slot = halite$slot(chunkX, chunkZ);
        Chunk chunk = this.halite$chunks[slot];
        if (chunk != null && this.halite$keys[slot] == ChunkPos.hashCode(chunkX, chunkZ)) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "getChunk", at = @At("RETURN"))
    private void halite$rememberChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        int slot = halite$slot(chunkX, chunkZ);
        this.halite$keys[slot] = ChunkPos.hashCode(chunkX, chunkZ);
        this.halite$chunks[slot] = cir.getReturnValue();
    }

    @Inject(method = "isChunkLoaded", at = @At("HEAD"), cancellable = true)
    private void halite$cachedLoaded(int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir) {
        int slot = halite$slot(chunkX, chunkZ);
        if (this.halite$chunks[slot] != null && this.halite$keys[slot] == ChunkPos.hashCode(chunkX, chunkZ)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = {"loadChunk", "tick"}, at = {
            @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;")
    }, require = 2)
    private void halite$forgetChunks(CallbackInfoReturnable<?> cir) {
        java.util.Arrays.fill(this.halite$chunks, null);
    }

    @Override
    public ChunkSource halite$getGenerator() {
        return this.generator;
    }

    @Override
    public File halite$getRegionDir() {
        return this.storage instanceof TerrainPregen.RegionDirectory directory ? directory.halite$getRegionDir() : null;
    }
}
