package io.github.vinterus.halite.mixin.compat.stationapi;

import net.modificationstation.stationapi.impl.world.chunk.Palette;
import net.modificationstation.stationapi.impl.world.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets world slices spot sections made of a single block state and copy them without per-block lookups. */
@Mixin(value = PalettedContainer.class, remap = false)
public interface PalettedContainerAccessor {
    @Accessor("dataPalette")
    Palette<?> halite$getPalette();
}
