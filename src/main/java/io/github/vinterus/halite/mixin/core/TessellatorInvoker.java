package io.github.vinterus.halite.mixin.core;

import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Tessellator.class)
public interface TessellatorInvoker {
    @Invoker("<init>")
    static Tessellator halite$create(int bufferSize) {
        throw new AssertionError();
    }
}
