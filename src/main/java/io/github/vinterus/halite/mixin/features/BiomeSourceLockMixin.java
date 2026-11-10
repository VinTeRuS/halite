package io.github.vinterus.halite.mixin.features;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.vinterus.halite.world.gen.TerrainPregen;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Biome lookups end in StationAPI's global biome provider, which keeps scratch state; the game thread and terrain
 * threads take turns there.
 */
@Mixin(BiomeSource.class)
public class BiomeSourceLockMixin {
    @WrapMethod(method = "getBiomesInArea([Lnet/minecraft/world/biome/Biome;IIII)[Lnet/minecraft/world/biome/Biome;")
    private Biome[] halite$lockBiomeLookup(Biome[] biomes, int x, int z, int width, int depth, Operation<Biome[]> original) {
        synchronized (TerrainPregen.BIOME_LOCK) {
            return original.call(biomes, x, z, width, depth);
        }
    }
}
