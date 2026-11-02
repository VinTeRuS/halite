package io.github.vinterus.halite.mixin.compat.stationapi;

import io.github.vinterus.halite.world.gen.TerrainPregen;
import net.modificationstation.stationapi.impl.world.chunk.FlattenedWorldChunkLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.io.File;

/** Tells background terrain generation where saved chunks live, so it never generates one that is already saved. */
@Mixin(value = FlattenedWorldChunkLoader.class, remap = false)
public class FlattenedWorldChunkLoaderMixin implements TerrainPregen.RegionDirectory {
    @Shadow @Final protected File dimFolder;

    @Override
    public File halite$getRegionDir() {
        return this.dimFolder;
    }
}
