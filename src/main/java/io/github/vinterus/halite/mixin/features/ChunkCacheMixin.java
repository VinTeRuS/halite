package io.github.vinterus.halite.mixin.features;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.vinterus.halite.world.gen.TerrainPregen;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkCache;
import net.minecraft.world.chunk.ChunkSource;
import net.minecraft.world.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.io.File;

/** Uses terrain generated ahead of time, at the moment vanilla would generate the chunk itself. */
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

    @Override
    public ChunkSource halite$getGenerator() {
        return this.generator;
    }

    @Override
    public File halite$getRegionDir() {
        return this.storage instanceof TerrainPregen.RegionDirectory directory ? directory.halite$getRegionDir() : null;
    }
}
