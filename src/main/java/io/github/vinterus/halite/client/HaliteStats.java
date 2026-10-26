package io.github.vinterus.halite.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame CPU timings of the main rendering stages plus renderer counters, for the debug overlay (vanilla F3 or
 * the BetterF3 module). Render thread only.
 *
 * <p>Times are CPU time spent issuing work, not GPU time. On mobile drivers and GLES translation layers the CPU side
 * of a draw call is usually what limits the frame rate, so this is the useful number there.
 */
public final class HaliteStats {
    public enum Stage {
        VISIBILITY("Visibility"),
        CHUNK_UPDATES("Chunk updates"),
        TERRAIN("Terrain"),
        ENTITIES("Entities"),
        PARTICLES("Particles"),
        SKY("Sky"),
        CLOUDS("Clouds");

        public final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private static final Stage[] STAGES = Stage.values();
    // exponential moving average over roughly the last 30 frames
    private static final double SMOOTHING = 1.0 / 30.0;

    private static final long[] frameNanos = new long[STAGES.length];
    private static final long[] startNanos = new long[STAGES.length];
    private static final double[] averageMs = new double[STAGES.length];

    // counters, refreshed by the section manager every frame
    public static int renderedSections, visibleSections, totalSections;
    public static int drawCalls;
    public static int builtThisFrame, rebuildQueue, inFlight;
    public static double mainThreadBuildMs;
    public static double meshMegabytes, allocatedMegabytes;
    public static int pages;
    public static String animations = "-";
    public static String builder = "-";

    private HaliteStats() {
    }

    public static void begin(Stage stage) {
        startNanos[stage.ordinal()] = System.nanoTime();
    }

    public static void end(Stage stage) {
        frameNanos[stage.ordinal()] += System.nanoTime() - startNanos[stage.ordinal()];
    }

    /** Folds the finished frame's timings into the averages. Called once at the start of each world frame. */
    public static void nextFrame() {
        for (int i = 0; i < STAGES.length; i++) {
            averageMs[i] += (frameNanos[i] / 1.0e6 - averageMs[i]) * SMOOTHING;
            frameNanos[i] = 0;
        }
    }

    public static double getAverageMs(Stage stage) {
        return averageMs[stage.ordinal()];
    }

    /** One overlay entry; {@code id} names the BetterF3 line and its translation key. */
    public record Entry(String id, String label, String value) {
    }

    /** The overlay entries, shared by the vanilla F3 screen and the BetterF3 module. */
    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("renderer", "Halite", builder + ", "
                + (io.github.vinterus.halite.client.gl.GlCompat.useVbo() ? "VBO" : "client arrays")));
        entries.add(new Entry("sections", "Sections", String.format("%d drawn, %d visible, %d loaded",
                renderedSections, visibleSections, totalSections)));
        entries.add(new Entry("draw_calls", "Draw calls", Integer.toString(drawCalls)));
        entries.add(new Entry("chunk_builds", "Chunk builds", String.format("%d/frame, %d queued, %d in flight, %.2f ms main",
                builtThisFrame, rebuildQueue, inFlight, mainThreadBuildMs)));
        entries.add(new Entry("geometry", "Geometry", String.format("%.1f MB in %.1f MB (%d pages)",
                meshMegabytes, allocatedMegabytes, pages)));
        entries.add(new Entry("animations", "Animations", animations));
        entries.add(new Entry("cpu_world", "CPU ms", stageLine(Stage.TERRAIN, Stage.CHUNK_UPDATES, Stage.VISIBILITY)));
        entries.add(new Entry("cpu_other", "CPU ms", stageLine(Stage.ENTITIES, Stage.PARTICLES, Stage.SKY, Stage.CLOUDS)));
        return entries;
    }

    /** The entries as "label: value" lines. */
    public static List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (Entry entry : entries()) {
            lines.add(entry.label() + ": " + entry.value());
        }
        return lines;
    }

    private static String stageLine(Stage... stages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stages.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(stages[i].label).append(' ').append(String.format("%.2f", getAverageMs(stages[i])));
        }
        return sb.toString();
    }
}
