package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;

/**
 * Biome data for a small square area, copied from the world's biome source on the render thread.
 *
 * <p>Block colour code calls {@code getBiomesInArea(x, z, 1, 1)} and then reads the public {@code temperatureMap} /
 * {@code downfallMap} fields of the biome source, which the real source overwrites on every call. Each slice gets
 * its own instance so worker threads never share those arrays.
 */
final class SnapshotBiomeSource extends BiomeSource {
    // Whether index i * size + j of a biome source's maps refers to (x + i, z + j) or (x + j, z + i). The vanilla
    // loop order and the noise sampler's layout are not obviously consistent, so it is detected once at runtime.
    private static volatile int layout = -1;
    private static final int X_MAJOR = 0, Z_MAJOR = 1;

    private final int originX, originZ, size;
    private final double[] temperature;
    private final double[] downfall;
    private final Biome[] biomeGrid;

    private SnapshotBiomeSource(int originX, int originZ, int size, double[] temperature, double[] downfall, Biome[] biomes) {
        this.originX = originX;
        this.originZ = originZ;
        this.size = size;
        this.temperature = temperature;
        this.downfall = downfall;
        this.biomeGrid = biomes;
    }

    static SnapshotBiomeSource capture(World world, int originX, int originZ, int size) {
        BiomeSource source = world.method_1781();
        int count = size * size;

        Biome[] biomes = source.getBiomesInArea(originX, originZ, size, size);
        Biome[] biomeCopy = new Biome[count];
        System.arraycopy(biomes, 0, biomeCopy, 0, count);
        double[] temperature = copy(source.temperatureMap, count);
        double[] downfall = copy(source.downfallMap, count);

        if (layout < 0) {
            detectLayout(source, originX, originZ, size, temperature, downfall);
        }

        return new SnapshotBiomeSource(originX, originZ, size, temperature, downfall, biomeCopy);
    }

    private static double[] copy(double[] src, int count) {
        double[] out = new double[count];
        if (src != null) {
            System.arraycopy(src, 0, out, 0, Math.min(count, src.length));
        }
        return out;
    }

    private static void detectLayout(BiomeSource source, int originX, int originZ, int size, double[] temperature, double[] downfall) {
        // a point where x and z offsets differ distinguishes the two layouts
        int dx = 1, dz = size - 2;
        source.getBiomesInArea(originX + dx, originZ + dz, 1, 1);
        double t = source.temperatureMap[0], d = source.downfallMap[0];

        double xMajorError = Math.abs(temperature[dx * size + dz] - t) + Math.abs(downfall[dx * size + dz] - d);
        double zMajorError = Math.abs(temperature[dz * size + dx] - t) + Math.abs(downfall[dz * size + dx] - d);

        layout = xMajorError <= zMajorError ? X_MAJOR : Z_MAJOR;
        HaliteClientMod.logger().debug("Biome map layout: {}", layout == X_MAJOR ? "x-major" : "z-major");
    }

    private int index(int x, int z) {
        int i = Math.max(0, Math.min(this.size - 1, x - this.originX));
        int j = Math.max(0, Math.min(this.size - 1, z - this.originZ));
        return layout == Z_MAJOR ? j * this.size + i : i * this.size + j;
    }

    @Override
    public Biome[] getBiomesInArea(Biome[] biomes, int x, int z, int width, int depth) {
        int count = width * depth;
        if (biomes == null || biomes.length < count) {
            biomes = new Biome[count];
        }
        if (this.temperatureMap == null || this.temperatureMap.length < count) {
            this.temperatureMap = new double[count];
        }
        if (this.downfallMap == null || this.downfallMap.length < count) {
            this.downfallMap = new double[count];
        }

        int out = 0;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < depth; j++) {
                int src = layout == Z_MAJOR ? this.index(x + j, z + i) : this.index(x + i, z + j);
                this.temperatureMap[out] = this.temperature[src];
                this.downfallMap[out] = this.downfall[src];
                biomes[out] = this.biomeGrid[src];
                out++;
            }
        }
        return biomes;
    }

    @Override
    public double getTemperature(int x, int z) {
        return this.temperature[this.index(x, z)];
    }

    @Override
    public double[] create(double[] map, int x, int z, int width, int depth) {
        int count = width * depth;
        if (map == null || map.length < count) {
            map = new double[count];
        }
        int out = 0;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < depth; j++) {
                int src = layout == Z_MAJOR ? this.index(x + j, z + i) : this.index(x + i, z + j);
                map[out++] = this.temperature[src];
            }
        }
        return map;
    }
}
