package io.github.vinterus.halite.mixin.core;

import io.github.vinterus.halite.client.render.chunk.compile.ChunkBuilderThread;
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
        Tessellator forward = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (forward != null) {
            forward.draw();
            ci.cancel();
            return;
        }

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

    // Calls made on the global tessellator from a chunk builder thread go to that thread's own tessellator.

    @Inject(method = "start(I)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdStart(int mode, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.start(mode); ci.cancel(); }
    }

    @Inject(method = "startQuads", at = @At("HEAD"), cancellable = true)
    private void halite$fwdStartQuads(CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.startQuads(); ci.cancel(); }
    }

    @Inject(method = "texture(DD)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdTexture(double u, double v, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.texture(u, v); ci.cancel(); }
    }

    @Inject(method = "color(FFF)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdColor3f(float r, float g, float b, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.color(r, g, b); ci.cancel(); }
    }

    @Inject(method = "color(FFFF)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdColor4f(float r, float g, float b, float a, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.color(r, g, b, a); ci.cancel(); }
    }

    @Inject(method = "color(III)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdColor3i(int r, int g, int b, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.color(r, g, b); ci.cancel(); }
    }

    @Inject(method = "color(IIII)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdColor4i(int r, int g, int b, int a, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.color(r, g, b, a); ci.cancel(); }
    }

    @Inject(method = "color(I)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdColorRgb(int rgb, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.color(rgb); ci.cancel(); }
    }

    @Inject(method = "color(II)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdColorRgbA(int rgb, int a, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.color(rgb, a); ci.cancel(); }
    }

    @Inject(method = "vertex(DDDDD)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdVertexUv(double x, double y, double z, double u, double v, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.vertex(x, y, z, u, v); ci.cancel(); }
    }

    @Inject(method = "vertex(DDD)V", at = @At("HEAD"), cancellable = true)
    private void halite$fwdVertex(double x, double y, double z, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.vertex(x, y, z); ci.cancel(); }
    }

    @Inject(method = "disableColor", at = @At("HEAD"), cancellable = true)
    private void halite$fwdDisableColor(CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.disableColor(); ci.cancel(); }
    }

    @Inject(method = "normal", at = @At("HEAD"), cancellable = true)
    private void halite$fwdNormal(float x, float y, float z, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.normal(x, y, z); ci.cancel(); }
    }

    @Inject(method = "setOffset", at = @At("HEAD"), cancellable = true)
    private void halite$fwdSetOffset(double x, double y, double z, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.setOffset(x, y, z); ci.cancel(); }
    }

    @Inject(method = "translate", at = @At("HEAD"), cancellable = true)
    private void halite$fwdTranslate(float x, float y, float z, CallbackInfo ci) {
        Tessellator t = ChunkBuilderThread.redirect((Tessellator) (Object) this);
        if (t != null) { t.translate(x, y, z); ci.cancel(); }
    }
}
