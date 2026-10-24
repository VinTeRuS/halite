package io.github.vinterus.halite.client.gui;

import io.github.vinterus.halite.client.HaliteClientMod;
import io.github.vinterus.halite.client.config.HaliteOptions;
import io.github.vinterus.halite.client.gl.GlCompat;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Halite's settings page, opened from the vanilla Video Settings screen.
 */
public class HaliteOptionsScreen extends Screen {
    private static final int DONE_ID = 200;

    private static final int[] RENDER_DISTANCES = {0, 2, 4, 6, 8, 10, 12, 16, 20, 24, 32};
    private static final int[] BUILD_BUDGETS = {1, 2, 4, 8, 16};

    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();

    public HaliteOptionsScreen(Screen parent) {
        this.parent = parent;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void init() {
        HaliteOptions o = HaliteClientMod.options();
        this.entries.clear();

        this.cycle("Render Distance", o.renderDistance, RENDER_DISTANCES, v -> o.renderDistance = v,
                v -> v == 0 ? "Vanilla" : v + " chunks");
        this.toggle("Occlusion Culling", o.useOcclusionCulling, v -> o.useOcclusionCulling = v);
        this.toggle("Face Culling", o.useFaceCulling, v -> o.useFaceCulling = v);
        this.toggle("Entity Culling", o.useEntityCulling, v -> o.useEntityCulling = v);
        this.toggle("Block Entity Culling", o.useBlockEntityCulling, v -> o.useBlockEntityCulling = v);
        this.toggle("Particle Culling", o.useParticleCulling, v -> o.useParticleCulling = v);
        this.toggle("Translucency Sorting", o.useTranslucencySorting, v -> o.useTranslucencySorting = v);
        this.cycle("Build Budget", o.chunkBuildBudgetMs, BUILD_BUDGETS, v -> o.chunkBuildBudgetMs = v,
                v -> v + " ms");
        this.toggle("VBOs (restart)", o.useVertexBufferObjects, v -> o.useVertexBufferObjects = v);

        for (int i = 0; i < this.entries.size(); i++) {
            Entry entry = this.entries.get(i);
            entry.button = new ButtonWidget(i, this.width / 2 - 155 + i % 2 * 160, this.height / 6 - 12 + 24 * (i >> 1),
                    150, 20, entry.label());
            this.buttons.add(entry.button);
        }

        this.buttons.add(new ButtonWidget(DONE_ID, this.width / 2 - 100, this.height / 6 + 168, "Done"));
    }

    private void toggle(String name, boolean initial, Consumer<Boolean> setter) {
        boolean[] value = {initial};
        this.entries.add(new Entry(
                () -> {
                    value[0] = !value[0];
                    setter.accept(value[0]);
                },
                () -> name + ": " + (value[0] ? "ON" : "OFF")));
    }

    private void cycle(String name, int initial, int[] values, Consumer<Integer> setter, Function<Integer, String> format) {
        int[] index = {0};
        for (int i = 0; i < values.length; i++) {
            if (values[i] == initial) {
                index[0] = i;
            }
        }
        this.entries.add(new Entry(
                () -> {
                    index[0] = (index[0] + 1) % values.length;
                    setter.accept(values[index[0]]);
                },
                () -> name + ": " + format.apply(values[index[0]])));
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (!button.active) {
            return;
        }

        if (button.id == DONE_ID) {
            HaliteClientMod.options().save();
            this.minecraft.setScreen(this.parent);
            return;
        }

        if (button.id >= 0 && button.id < this.entries.size()) {
            Entry entry = this.entries.get(button.id);
            entry.action.run();
            button.text = entry.label();
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        this.drawCenteredTextWithShadow(this.textRenderer, "Halite Settings", this.width / 2, 15, 0xFFFFFF);
        this.drawCenteredTextWithShadow(this.textRenderer,
                "GL: " + GlCompat.getRenderer() + (GlCompat.useVbo() ? " (VBO)" : " (client arrays)"),
                this.width / 2, this.height / 6 + 150, 0xA0A0A0);
        super.render(mouseX, mouseY, delta);
    }

    private static final class Entry {
        final Runnable action;
        final java.util.function.Supplier<String> labeler;
        ButtonWidget button;

        Entry(Runnable action, java.util.function.Supplier<String> labeler) {
            this.action = action;
            this.labeler = labeler;
        }

        String label() {
            return this.labeler.get();
        }
    }
}
