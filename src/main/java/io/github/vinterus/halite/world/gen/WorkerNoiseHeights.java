package io.github.vinterus.halite.world.gen;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;

import java.util.function.Function;

/**
 * StationAPI's biome height shaping ({@code WorldGeneratorImpl.updateNoise}) for terrain threads. The original keeps
 * its interpolators and their biome cache in static fields, which threads must not share, so each terrain thread runs
 * this copy with state of its own. The arithmetic is unchanged, so the terrain is the same.
 *
 * <p>Adapted from StationAPI (MIT License, Copyright (c) 2022 Modification Station): WorldGeneratorImpl and
 * BiomeDataInterpolator.
 */
public final class WorkerNoiseHeights {
    private final Long2ReferenceMap<Biome> cache = new Long2ReferenceOpenHashMap<>();
    private BiomeSource cacheSource;
    private final Interpolator minHeight = new Interpolator(Biome::getMinHeight, 16, 4, 8);
    private final Interpolator maxHeight = new Interpolator(Biome::getMaxHeight, 16, 4, 8);

    void reset() {
        this.cache.clear();
        this.cacheSource = null;
        this.minHeight.initiated = false;
        this.maxHeight.initiated = false;
    }

    public void updateNoise(World world, int cx, int cz, double[] data) {
        float min = 0;
        float max = 0;
        float n = 0;
        cx <<= 4;
        cz <<= 4;

        BiomeSource biomeSource = world.method_1781();
        int sideY = (1 << (net.modificationstation.stationapi.api.util.math.MathHelper.ceilLog2(world.getHeight()) - 3)) + 1;
        int bottom = world.getBottomY() >> 3;
        int dx = sideY * 5;

        for (int i = 0; i < data.length; i++) {
            int y = (i % sideY);

            if (y == 0) {
                int x = ((i / dx) << 2) + cx;
                int z = (((i / sideY) % 5) << 2) + cz;
                min = this.minHeight.get(biomeSource, x, z) / 8F;
                max = this.maxHeight.get(biomeSource, x, z) / 8F;
            }

            y += bottom;

            if (y < min) {
                float d = (min - y) * 100 + n * 10;
                data[i] = smoothMax(data[i], d, 1);
            } else if (y > max) {
                float d = (max - y) * 100 + n * 10;
                data[i] = smoothMin(data[i], d, 1);
            }
        }
    }

    private static double smoothMax(double a, double b, double k) {
        return -smoothMin(-a, -b, k);
    }

    private static double smoothMin(double a, double b, double k) {
        double h = Math.max(k - Math.abs(a - b), 0.0) / k;
        return Math.min(a, b) - h * h * k * 0.25;
    }

    private final class Interpolator {
        private final Function<Biome, Number> provider;
        private final float[] data = new float[4];
        private final int bitShift;
        private final int side;
        private final int radius;
        private final int count;
        private final int distance;

        boolean initiated;
        private int lastX;
        private int lastZ;

        Interpolator(Function<Biome, Number> provider, int side, int radius, int distance) {
            this.side = side;
            this.radius = radius;
            this.distance = distance;
            this.count = (radius << 1 | 1) * (radius << 1 | 1);
            this.bitShift = MathHelper.floor(Math.log(side) / Math.log(2));
            this.provider = provider;
        }

        float get(BiomeSource source, int x, int z) {
            if (WorkerNoiseHeights.this.cacheSource != source) {
                WorkerNoiseHeights.this.cache.clear();
                WorkerNoiseHeights.this.cacheSource = source;
            }

            int x1 = x >> this.bitShift;
            int z1 = z >> this.bitShift;

            float dx = (float) (x - (x1 << this.bitShift)) / this.side;
            float dz = (float) (z - (z1 << this.bitShift)) / this.side;

            if (!this.initiated || x1 != this.lastX || z1 != this.lastZ) {
                this.initiated = true;
                this.lastX = x1;
                this.lastZ = z1;

                x1 <<= this.bitShift;
                z1 <<= this.bitShift;

                int x2 = x1 + this.side;
                int z2 = z1 + this.side;

                this.data[0] = this.getInArea(source, x1, z1);
                this.data[1] = this.getInArea(source, x2, z1);
                this.data[2] = this.getInArea(source, x1, z2);
                this.data[3] = this.getInArea(source, x2, z2);
            }

            float a = net.modificationstation.stationapi.api.util.math.MathHelper.lerp(dx, this.data[0], this.data[1]);
            float b = net.modificationstation.stationapi.api.util.math.MathHelper.lerp(dx, this.data[2], this.data[3]);

            return net.modificationstation.stationapi.api.util.math.MathHelper.lerp(dz, a, b);
        }

        private float getInArea(BiomeSource source, int x, int z) {
            Long2ReferenceMap<Biome> cache = WorkerNoiseHeights.this.cache;
            float value = 0;
            for (int dx = -this.radius; dx <= this.radius; dx++) {
                for (int dz = -this.radius; dz <= this.radius; dz++) {
                    final int lookupX = x + dx * this.distance;
                    final int lookupZ = z + dz * this.distance;
                    final long key = ((long) (lookupX) << 32L) | lookupZ;
                    Biome biome = cache.get(key);
                    if (biome == null) {
                        cache.put(key, biome = source.getBiome(lookupX, lookupZ));
                    }
                    value += this.provider.apply(biome).floatValue();
                }
            }
            return value / this.count;
        }
    }
}
