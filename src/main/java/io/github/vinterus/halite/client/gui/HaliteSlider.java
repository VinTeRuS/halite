package io.github.vinterus.halite.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.lwjgl.opengl.GL11;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * A slider over a fixed list of integer values. Works with touch input as well: the value follows the pointer
 * while it is held down.
 */
public class HaliteSlider extends ButtonWidget {
    private final int[] values;
    private final IntConsumer setter;
    private final IntFunction<String> label;
    private int index;
    private boolean dragging;

    public HaliteSlider(int id, int x, int y, int width, int[] values, int initial, IntConsumer setter, IntFunction<String> label) {
        super(id, x, y, width, 20, "");
        this.values = values;
        this.setter = setter;
        this.label = label;

        for (int i = 0; i < values.length; i++) {
            if (values[i] == initial) {
                this.index = i;
            }
        }
        this.text = label.apply(values[this.index]);
    }

    private void setFromMouse(int mouseX) {
        float position = (float) (mouseX - (this.x + 4)) / (this.width - 8);
        position = Math.max(0.0f, Math.min(1.0f, position));
        int index = Math.round(position * (this.values.length - 1));

        if (index != this.index) {
            this.index = index;
            this.setter.accept(this.values[index]);
            this.text = this.label.apply(this.values[index]);
        }
    }

    @Override
    protected int getYImage(boolean hovered) {
        return 0;
    }

    @Override
    protected void renderBackground(Minecraft minecraft, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        if (this.dragging) {
            this.setFromMouse(mouseX);
        }

        float position = this.values.length > 1 ? (float) this.index / (this.values.length - 1) : 0.0f;
        int knobX = this.x + (int) (position * (this.width - 8));

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.drawTexture(knobX, this.y, 0, 66, 4, 20);
        this.drawTexture(knobX + 4, this.y, 196, 66, 4, 20);
    }

    @Override
    public boolean isMouseOver(Minecraft minecraft, int mouseX, int mouseY) {
        if (super.isMouseOver(minecraft, mouseX, mouseY)) {
            this.setFromMouse(mouseX);
            this.dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }
}
