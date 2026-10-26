package io.github.vinterus.halite.client.gui;

import io.github.vinterus.halite.client.HaliteClientMod;
import io.github.vinterus.halite.client.config.HaliteOptions;
import io.github.vinterus.halite.client.gl.GlCompat;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Halite's settings page, opened from the vanilla Video Settings screen. Numeric settings are sliders, and the
 * setting under the pointer (or the last one touched, on touch screens) is explained below the grid.
 */
public class HaliteOptionsScreen extends Screen {
    private static final int DONE_ID = 200;
    private static final int TAB_QUALITY_ID = 300, TAB_PERFORMANCE_ID = 301;
    private static final int PAGE_QUALITY = 0, PAGE_PERFORMANCE = 1;
    private static final int GRID_TOP = 46;

    private static final int[] ENTITY_DISTANCES = {0, 16, 24, 32, 48, 64, 96};
    private static final int[] BLOCK_ENTITY_DISTANCES = {0, 16, 24, 32, 48};

    // remembered while the game runs, so reopening the screen shows the last page
    private static int page = PAGE_QUALITY;
    private int slotLeft, slotRight;

    private static final int[] RENDER_DISTANCES = {0, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 32};
    private static final int[] BUILD_BUDGETS = {1, 2, 3, 4, 6, 8, 12, 16};

    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    private boolean needsReload;
    private Entry described;

    public HaliteOptionsScreen(Screen parent) {
        this.parent = parent;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void init() {
        HaliteOptions o = HaliteClientMod.options();
        this.entries.clear();
        this.described = null;

        int left = this.width / 2 - 155, right = this.width / 2 + 5;
        this.slotLeft = left;
        this.slotRight = right;

        if (page == PAGE_QUALITY) {
            this.slider(RENDER_DISTANCES, o.renderDistance, v -> o.renderDistance = v,
                    v -> "Render Distance: " + (v == 0 ? "Vanilla" : v + " chunks"),
                    "How far terrain is drawn, in chunks. Vanilla follows the Render Distance button. Large values cost memory and chunk loading time.");
            this.slider(ENTITY_DISTANCES, o.entityRenderDistance, v -> o.entityRenderDistance = v,
                    v -> "Entity Distance: " + (v == 0 ? "Vanilla" : v + " blocks"),
                    "Mobs, animals and dropped items further away than this are not drawn. Vanilla draws them up to about 64 blocks times their size.");
            this.slider(BLOCK_ENTITY_DISTANCES, o.blockEntityRenderDistance, v -> o.blockEntityRenderDistance = v,
                    v -> "Block Entity Dist.: " + (v == 0 ? "Vanilla" : v + " blocks"),
                    "Chests, signs, mob spawners and pistons further away than this are not drawn. Sign text is expensive to draw. Vanilla is 64 blocks.");
            this.toggle("Fast Clouds", o.useCachedClouds, v -> o.useCachedClouds = v,
                    "Draws fancy clouds from cached geometry instead of rebuilding them every frame. Looks the same.");
            this.toggle("Translucency Sorting", o.useTranslucencySorting, v -> o.useTranslucencySorting = v,
                    "Sorts water back to front so overlapping water surfaces blend correctly.");
            this.toggle("Animate Visible Only", o.useAnimationCulling, v -> o.useAnimationCulling = v,
                    "Only animates water, lava, fire and portals that are on screen. Saves CPU time and texture uploads, a lot on phones.");
            this.toggle("VBOs", o.useVertexBufferObjects, v -> o.useVertexBufferObjects = v,
                    "Keeps chunk geometry in GPU memory. Turn off only if terrain is invisible or flickers on your device. Needs a restart.");
        } else {
            this.slider(BUILD_BUDGETS, o.chunkBuildBudgetMs, v -> o.chunkBuildBudgetMs = v, v -> "Build Budget: " + v + " ms",
                    "Time per frame the game thread spends on chunk updates. Higher loads chunks faster, lower keeps the frame rate steadier.");
            this.toggle("Async Chunk Builds", o.useAsyncChunkBuilding, v -> {
                o.useAsyncChunkBuilding = v;
                this.needsReload = true;
            }, "Builds chunk meshes on background threads, which removes most stutter while chunks load. Turn off if a mod's blocks render wrong.");
            this.toggle("Occlusion Culling", o.useOcclusionCulling, v -> o.useOcclusionCulling = v,
                    "Skips chunks hidden behind terrain, such as caves under your feet. The biggest frame rate win; turn off only to debug missing chunks.");
            this.toggle("Face Culling", o.useFaceCulling, v -> o.useFaceCulling = v,
                    "Skips block faces pointing away from the camera before they reach the GPU.");
            this.toggle("Entity Culling", o.useEntityCulling, v -> o.useEntityCulling = v,
                    "Skips mobs and items in chunks that are not visible.");
            this.toggle("Block Entity Culling", o.useBlockEntityCulling, v -> o.useBlockEntityCulling = v,
                    "Skips chests, signs and other block entities in chunks that are not visible.");
            this.toggle("Particle Culling", o.useParticleCulling, v -> o.useParticleCulling = v,
                    "Skips particles outside the view.");
        }

        for (Entry entry : this.entries) {
            this.buttons.add(entry.button);
        }

        ButtonWidget quality = new ButtonWidget(TAB_QUALITY_ID, this.width / 2 - 102, 20, 100, 20, "Quality");
        ButtonWidget performance = new ButtonWidget(TAB_PERFORMANCE_ID, this.width / 2 + 2, 20, 100, 20, "Performance");
        quality.active = page != PAGE_QUALITY;
        performance.active = page != PAGE_PERFORMANCE;
        this.buttons.add(quality);
        this.buttons.add(performance);

        this.buttons.add(new ButtonWidget(DONE_ID, this.width / 2 - 100, this.height - 28, "Done"));
    }

    private int nextSlotX() {
        return this.entries.size() % 2 == 0 ? this.slotLeft : this.slotRight;
    }

    private int nextSlotY() {
        return GRID_TOP + 24 * (this.entries.size() / 2);
    }

    private void slider(int[] values, int initial, java.util.function.IntConsumer setter,
                        java.util.function.IntFunction<String> label, String description) {
        HaliteSlider slider = new HaliteSlider(this.entries.size(), this.nextSlotX(), this.nextSlotY(), 150, values,
                initial, setter, label);
        this.add(slider, description);
    }

    private void add(HaliteSlider slider, String description) {
        Entry entry = new Entry(null, null, description);
        entry.button = slider;
        this.entries.add(entry);
    }

    private void toggle(String name, boolean initial, Consumer<Boolean> setter, String description) {
        boolean[] value = {initial};
        Entry entry = new Entry(
                () -> {
                    value[0] = !value[0];
                    setter.accept(value[0]);
                },
                () -> name + ": " + (value[0] ? "ON" : "OFF"),
                description);
        entry.button = new ButtonWidget(this.entries.size(), this.nextSlotX(), this.nextSlotY(), 150, 20, entry.label.get());
        this.entries.add(entry);
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (!button.active) {
            return;
        }

        if (button.id == TAB_QUALITY_ID || button.id == TAB_PERFORMANCE_ID) {
            page = button.id == TAB_QUALITY_ID ? PAGE_QUALITY : PAGE_PERFORMANCE;
            this.init(this.minecraft, this.width, this.height);
            return;
        }

        if (button.id == DONE_ID) {
            HaliteClientMod.options().save();
            if (this.needsReload && this.minecraft.worldRenderer != null && this.minecraft.world != null) {
                this.minecraft.worldRenderer.reload();
            }
            this.minecraft.setScreen(this.parent);
            return;
        }

        if (button.id >= 0 && button.id < this.entries.size()) {
            Entry entry = this.entries.get(button.id);
            this.described = entry;
            if (entry.action != null) {
                entry.action.run();
                button.text = entry.label.get();
            }
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        this.renderBackground();
        this.drawCenteredTextWithShadow(this.textRenderer, "Halite Settings", this.width / 2, 6, 0xFFFFFF);
        super.render(mouseX, mouseY, delta);

        for (Entry entry : this.entries) {
            ButtonWidget b = entry.button;
            if (mouseX >= b.x && mouseY >= b.y && mouseX < b.x + 150 && mouseY < b.y + 20) {
                this.described = entry;
            }
        }

        int textTop = GRID_TOP + 24 * ((this.entries.size() + 1) / 2) + 4;
        if (this.described != null) {
            this.drawWrapped(this.described.description, textTop, 0xE0E0E0);
        } else {
            this.drawWrapped("Tap or hover over a setting to see what it does. GPU: " + GlCompat.getRenderer()
                    + (GlCompat.useVbo() ? " (VBO)" : " (client arrays)"), textTop, 0x909090);
        }
    }

    private void drawWrapped(String text, int y, int color) {
        int maxWidth = Math.min(310, this.width - 20);
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (this.textRenderer.getWidth(candidate) > maxWidth && line.length() > 0) {
                this.drawCenteredTextWithShadow(this.textRenderer, line.toString(), this.width / 2, y, color);
                y += 10;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            this.drawCenteredTextWithShadow(this.textRenderer, line.toString(), this.width / 2, y, color);
        }
    }

    private static final class Entry {
        final Runnable action;
        final Supplier<String> label;
        final String description;
        ButtonWidget button;

        Entry(Runnable action, Supplier<String> label, String description) {
            this.action = action;
            this.label = label;
            this.description = description;
        }
    }
}
