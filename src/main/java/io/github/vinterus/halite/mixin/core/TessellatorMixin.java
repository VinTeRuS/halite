package io.github.vinterus.halite.mixin.core;

import io.github.vinterus.halite.client.render.chunk.compile.ChunkMeshBuilder;
import io.github.vinterus.halite.client.render.chunk.compile.TessellatorCapture;
import net.minecraft.client.render.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Tessellator.class)
public abstract class TessellatorMixin implements TessellatorCapture {
    @Shadow private static boolean TRIANGLE_MODE;
    @Shadow private int[] buffer;
    @Shadow private int vertexCount;
    @Shadow private int mode;
    @Shadow private boolean hasTexture;
    @Shadow private boolean hasColor;
    @Shadow private boolean drawing;

    @Shadow
    protected abstract void reset();

    @Unique
    private ChunkMeshBuilder halite$captureTarget;

    @Override
    public void halite$setCaptureTarget(ChunkMeshBuilder target) {
        this.halite$captureTarget = target;
    }

    @Override
    public boolean halite$isDrawing() {
        return this.drawing;
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void halite$captureDraw(CallbackInfo ci) {
        ChunkMeshBuilder target = this.halite$captureTarget;
        if (target == null) {
            return;
        }

        if (!this.drawing) {
            throw new IllegalStateException("Not tesselating!");
        }

        this.drawing = false;
        if (this.vertexCount > 0) {
            target.acceptTessellatorData(this.buffer, this.vertexCount, this.mode, TRIANGLE_MODE, this.hasTexture, this.hasColor);
        }
        this.reset();
        ci.cancel();
    }
}
