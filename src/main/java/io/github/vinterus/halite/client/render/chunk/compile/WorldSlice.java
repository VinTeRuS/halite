package io.github.vinterus.halite.client.render.chunk.compile;

import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.material.Material;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.WorldRegion;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.modificationstation.stationapi.api.block.BlockState;
import net.modificationstation.stationapi.api.block.States;
import net.modificationstation.stationapi.impl.world.chunk.ChunkSection;
import net.modificationstation.stationapi.impl.world.chunk.FlattenedChunk;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * An immutable copy of the blocks, light and biomes around one section, taken on the render thread so the section
 * can be meshed on a worker thread without touching live chunks.
 *
 * <p>Extends {@link WorldRegion} so block renderers that expect one (or StationAPI's block state view, which is
 * implemented on it) see the type they are used to. Every query is answered from the copied arrays.
 *
 * <p>The copied volume is the section plus a two block margin: block renderers look one block around each block,
 * and the slab/stair/farmland light rule looks one block further.
 */
public final class WorldSlice extends WorldRegion {
    static final int MARGIN = 2;
    static final int SIZE = 16 + MARGIN * 2;
    private static final int VOLUME = SIZE * SIZE * SIZE;

    private final int originX, originY, originZ;
    private final int bottomY, topY;
    private final int skyLightAbove;

    // The arrays are about 110 KB per slice and one slice is taken per rebuild, so they are pooled. Every copy
    // writes every element, so reused arrays need no clearing.
    private static final int POOL_SIZE = 32;
    private static final ConcurrentLinkedQueue<Storage> POOL = new ConcurrentLinkedQueue<>();

    private Storage storage;
    private final int[] ids;
    private final byte[] metas;
    private final byte[] light;
    private final BlockState[] states;
    private final BlockEntity[] blockEntities;

    private final float[] luminance;
    private final SnapshotBiomeSource biomes;
    private boolean usesSkyLight;

    private WorldSlice(World world, int originX, int originY, int originZ) {
        super(world, originX, originY, originZ, originX + SIZE - 1, originY + SIZE - 1, originZ + SIZE - 1);
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.bottomY = world.getBottomY();
        this.topY = world.getTopY();
        this.skyLightAbove = Math.max(0, 15 - world.ambientDarkness);
        this.luminance = world.dimension.lightLevelToLuminance.clone();
        this.biomes = SnapshotBiomeSource.capture(world, originX + 1, originZ + 1, SIZE - 2);

        Storage storage = POOL.poll();
        this.storage = storage != null ? storage : new Storage();
        this.ids = this.storage.ids;
        this.metas = this.storage.metas;
        this.light = this.storage.light;
        this.states = this.storage.states;
        this.blockEntities = this.storage.blockEntities;
    }

    /**
     * Returns the arrays to the pool. The slice must not be used afterwards; render thread, once the build result
     * has been applied.
     */
    public void release() {
        Storage storage = this.storage;
        if (storage != null) {
            this.storage = null;
            if (POOL.size() < POOL_SIZE) {
                POOL.offer(storage);
            }
        }
    }

    private static final class Storage {
        final int[] ids = new int[VOLUME];
        final byte[] metas = new byte[VOLUME];
        final byte[] light = new byte[VOLUME];
        final BlockState[] states = new BlockState[VOLUME];
        final BlockEntity[] blockEntities = new BlockEntity[VOLUME];
    }

    /** Copies the area around a section. Must be called on the render thread. */
    public static WorldSlice create(World world, int sectionX, int sectionY, int sectionZ) {
        WorldSlice slice = new WorldSlice(world, (sectionX << 4) - MARGIN, (sectionY << 4) - MARGIN, (sectionZ << 4) - MARGIN);
        slice.copy(world);
        return slice;
    }

    private void copy(World world) {
        int ambientDarkness = world.ambientDarkness;
        BlockState air = States.AIR.get();
        boolean[] withEntity = Block.BLOCKS_WITH_ENTITY;

        Chunk.hasSkyLight = false;

        int minCx = this.originX >> 4, maxCx = (this.originX + SIZE - 1) >> 4;
        int minCz = this.originZ >> 4, maxCz = (this.originZ + SIZE - 1) >> 4;

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                Chunk chunk = world.getChunk(cx, cz);
                FlattenedChunk flattened = chunk instanceof FlattenedChunk f ? f : null;

                int x0 = Math.max(this.originX, cx << 4), x1 = Math.min(this.originX + SIZE, (cx << 4) + 16);
                int z0 = Math.max(this.originZ, cz << 4), z1 = Math.min(this.originZ + SIZE, (cz << 4) + 16);

                for (int y = this.originY; y < this.originY + SIZE; y++) {
                    if (y < this.bottomY || y >= this.topY) {
                        this.fillRow(x0, x1, y, z0, z1, air, (byte) (y >= this.topY ? this.skyLightAbove : 0));
                    } else if (flattened != null) {
                        this.copyRow(world, flattened, x0, x1, y, z0, z1, ambientDarkness, withEntity);
                    } else {
                        this.copyRowLegacy(chunk, x0, x1, y, z0, z1, ambientDarkness, withEntity, air);
                    }
                }
            }
        }

        this.usesSkyLight |= Chunk.hasSkyLight;
    }

    private void fillRow(int x0, int x1, int y, int z0, int z1, BlockState state, byte light) {
        for (int z = z0; z < z1; z++) {
            int index = this.index(x0, y, z);
            for (int x = x0; x < x1; x++, index++) {
                this.ids[index] = 0;
                this.metas[index] = 0;
                this.states[index] = state;
                this.light[index] = light;
                this.blockEntities[index] = null;
            }
        }
    }

    /**
     * Reads a StationAPI chunk section directly: looking the section up once per layer instead of four times per
     * block. Mirrors {@link FlattenedChunk#getLight(int, int, int, int)}, including its sky light flag.
     */
    private void copyRow(World world, FlattenedChunk chunk, int x0, int x1, int y, int z0, int z1, int ambientDarkness,
                         boolean[] withEntity) {
        int sectionIndex = world.sectionCoordToIndex(y >> 4);
        ChunkSection[] sections = chunk.sections;
        ChunkSection section = sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
        if (section == null) {
            int sky = world.dimension.hasCeiling ? 0 : 15;
            if (sky > 0) {
                this.usesSkyLight = true;
            }
            this.fillRow(x0, x1, y, z0, z1, States.AIR.get(), (byte) Math.max(sky - ambientDarkness, 0));
            return;
        }

        int ly = y & 15;
        for (int z = z0; z < z1; z++) {
            int lz = z & 15;
            int index = this.index(x0, y, z);
            for (int x = x0; x < x1; x++, index++) {
                int lx = x & 15;
                int local = lx << 8 | ly << 4 | lz;

                BlockState state = section.getBlockState(lx, ly, lz);
                int id = state.getBlock().id;
                this.ids[index] = id;
                this.states[index] = state;
                this.metas[index] = (byte) section.getMeta(local);

                int light = section.getLight(LightType.SKY, local);
                if (light > 0) {
                    this.usesSkyLight = true;
                }
                light -= ambientDarkness;
                int blockLight = section.getLight(LightType.BLOCK, local);
                this.light[index] = (byte) Math.max(light, blockLight);

                this.blockEntities[index] = id > 0 && id < withEntity.length && withEntity[id]
                        ? chunk.getBlockEntity(lx, y, lz) : null;
            }
        }
    }

    private void copyRowLegacy(Chunk chunk, int x0, int x1, int y, int z0, int z1, int ambientDarkness,
                               boolean[] withEntity, BlockState air) {
        for (int z = z0; z < z1; z++) {
            int lz = z & 15;
            int index = this.index(x0, y, z);
            for (int x = x0; x < x1; x++, index++) {
                int lx = x & 15;
                int id = chunk.getBlockId(lx, y, lz);
                this.ids[index] = id;
                this.states[index] = id == 0 ? air : chunk.getBlockState(lx, y, lz);
                this.metas[index] = (byte) chunk.getBlockMeta(lx, y, lz);
                this.light[index] = (byte) chunk.getLight(lx, y, lz, ambientDarkness);
                this.blockEntities[index] = id > 0 && id < withEntity.length && withEntity[id]
                        ? chunk.getBlockEntity(lx, y, lz) : null;
            }
        }
    }

    private int index(int x, int y, int z) {
        return ((y - this.originY) * SIZE + (z - this.originZ)) * SIZE + (x - this.originX);
    }

    private boolean contains(int x, int y, int z) {
        return x >= this.originX && x < this.originX + SIZE
                && y >= this.originY && y < this.originY + SIZE
                && z >= this.originZ && z < this.originZ + SIZE;
    }

    /** Whether any copied block was lit by the sky, i.e. the mesh changes with the time of day. */
    public boolean usesSkyLight() {
        return this.usesSkyLight;
    }

    @Override
    public int getBlockId(int x, int y, int z) {
        return this.contains(x, y, z) ? this.ids[this.index(x, y, z)] : 0;
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        if (!this.contains(x, y, z)) {
            return States.AIR.get();
        }
        return this.states[this.index(x, y, z)];
    }

    @Override
    public BlockEntity getBlockEntity(int x, int y, int z) {
        return this.contains(x, y, z) ? this.blockEntities[this.index(x, y, z)] : null;
    }

    @Override
    public int getBlockMeta(int x, int y, int z) {
        return this.contains(x, y, z) ? this.metas[this.index(x, y, z)] & 15 : 0;
    }

    @Override
    public float getNaturalBrightness(int x, int y, int z, int blockLight) {
        int light = this.getRawBrightness(x, y, z);
        if (light < blockLight) {
            light = blockLight;
        }
        return this.luminance[light];
    }

    @Override
    public float method_1782(int x, int y, int z) {
        return this.luminance[this.getRawBrightness(x, y, z)];
    }

    @Override
    public int getRawBrightness(int x, int y, int z) {
        return this.getRawBrightness(x, y, z, true);
    }

    @Override
    public int getRawBrightness(int x, int y, int z, boolean useNeighborLight) {
        if (useNeighborLight) {
            int id = this.getBlockId(x, y, z);
            if (id == Block.SLAB.id || id == Block.FARMLAND.id || id == Block.WOODEN_STAIRS.id || id == Block.COBBLESTONE_STAIRS.id) {
                int light = this.getRawBrightness(x, y + 1, z, false);
                light = Math.max(light, this.getRawBrightness(x + 1, y, z, false));
                light = Math.max(light, this.getRawBrightness(x - 1, y, z, false));
                light = Math.max(light, this.getRawBrightness(x, y, z + 1, false));
                light = Math.max(light, this.getRawBrightness(x, y, z - 1, false));
                return light;
            }
        }

        if (y < this.bottomY) {
            return 0;
        }
        if (y >= this.topY) {
            return this.skyLightAbove;
        }
        if (!this.contains(x, y, z)) {
            // outside the copied volume; only reachable by unusual renderers
            return this.skyLightAbove;
        }
        return this.light[this.index(x, y, z)];
    }

    @Override
    public Material getMaterial(int x, int y, int z) {
        int id = this.getBlockId(x, y, z);
        Block[] blocks = Block.BLOCKS;
        Block block = id > 0 && id < blocks.length ? blocks[id] : null;
        return block == null ? Material.AIR : block.material;
    }

    @Override
    public BiomeSource method_1781() {
        return this.biomes;
    }

    @Override
    public boolean method_1783(int x, int y, int z) {
        Block block = this.block(x, y, z);
        return block != null && block.isOpaque();
    }

    @Override
    public boolean shouldSuffocate(int x, int y, int z) {
        Block block = this.block(x, y, z);
        return block != null && block.material.blocksMovement() && block.isFullCube();
    }

    private Block block(int x, int y, int z) {
        int id = this.getBlockId(x, y, z);
        Block[] blocks = Block.BLOCKS;
        return id > 0 && id < blocks.length ? blocks[id] : null;
    }
}
