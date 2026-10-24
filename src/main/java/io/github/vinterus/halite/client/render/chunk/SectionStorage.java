package io.github.vinterus.halite.client.render.chunk;

import io.github.vinterus.halite.client.render.chunk.occlusion.GraphDirection;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.function.Consumer;

/**
 * All render sections within the render distance, keyed by section coordinate. Columns are created and removed as
 * the camera moves; sections keep links to their six neighbors for the graph search.
 */
public class SectionStorage {
    private final Long2ObjectMap<RenderSection> sections = new Long2ObjectOpenHashMap<>();

    private int centerX = Integer.MIN_VALUE, centerZ = Integer.MIN_VALUE;
    private int radius = -1;
    private int bottomSection, topSection;

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF)) | ((long) (z & 0x3FFFFF) << 20);
    }

    public RenderSection get(int x, int y, int z) {
        return this.sections.get(key(x, y, z));
    }

    public int size() {
        return this.sections.size();
    }

    public void forEach(Consumer<RenderSection> consumer) {
        for (RenderSection section : this.sections.values()) {
            consumer.accept(section);
        }
    }

    /**
     * Ensures exactly the columns within {@code radius} (plus one ring so edge sections have neighbors to mesh
     * against) of the center exist.
     */
    public void update(int centerX, int centerZ, int radius, int bottomSection, int topSection) {
        if (centerX == this.centerX && centerZ == this.centerZ && radius == this.radius
                && bottomSection == this.bottomSection && topSection == this.topSection) {
            return;
        }

        boolean heightChanged = bottomSection != this.bottomSection || topSection != this.topSection;
        if (heightChanged) {
            this.clear();
        }

        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.bottomSection = bottomSection;
        this.topSection = topSection;

        // remove out-of-range sections
        ObjectIterator<Long2ObjectMap.Entry<RenderSection>> it = this.sections.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            RenderSection section = it.next().getValue();
            if (!isInRange(section.getChunkX() - centerX, section.getChunkZ() - centerZ, radius)) {
                section.delete();
                it.remove();
            }
        }

        // add missing sections
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!isInRange(dx, dz, radius)) {
                    continue;
                }
                int x = centerX + dx, z = centerZ + dz;
                if (this.sections.containsKey(key(x, bottomSection, z))) {
                    continue;
                }
                for (int y = bottomSection; y < topSection; y++) {
                    this.add(new RenderSection(x, y, z));
                }
            }
        }
    }

    private static boolean isInRange(int dx, int dz, int radius) {
        // circular, with half a chunk of slack so the corners of the fog circle are covered
        return dx * dx + dz * dz <= (radius + 1) * (radius + 1);
    }

    private void add(RenderSection section) {
        int x = section.getChunkX(), y = section.getChunkY(), z = section.getChunkZ();
        this.sections.put(key(x, y, z), section);

        for (int dir = 0; dir < GraphDirection.COUNT; dir++) {
            RenderSection neighbor = this.get(x + GraphDirection.x(dir), y + GraphDirection.y(dir), z + GraphDirection.z(dir));
            if (neighbor != null) {
                section.setAdjacent(dir, neighbor);
                neighbor.setAdjacent(GraphDirection.opposite(dir), section);
            }
        }
    }

    public void clear() {
        for (RenderSection section : this.sections.values()) {
            section.delete();
        }
        this.sections.clear();
        this.centerX = Integer.MIN_VALUE;
        this.centerZ = Integer.MIN_VALUE;
        this.radius = -1;
    }
}
