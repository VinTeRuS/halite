package io.github.vinterus.halite.mixin.features;

import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Chunk.class)
public interface ChunkInvoker {
    @Invoker("lightGaps")
    void halite$lightGaps(int x, int z);
}
