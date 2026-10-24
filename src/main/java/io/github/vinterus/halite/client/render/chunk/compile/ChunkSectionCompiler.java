package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.render.chunk.ChunkMesh;
import io.github.vinterus.halite.client.render.chunk.RenderSection;
import io.github.vinterus.halite.client.render.chunk.occlusion.VisGraph;
import io.github.vinterus.halite.client.render.chunk.occlusion.VisibilityEncoding;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.world.World;
import net.minecraft.world.WorldRegion;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Meshes a section by running the regular block renderers (vanilla and StationAPI's) against a world region and
 * capturing what they write into the tessellator. This keeps every block model and modded block renderer working
 * exactly as with vanilla display lists, while the output goes into compact vertex buffers.
 *
 * <p>Must run on the render thread: block renderers write into the global tessellator.
 */
public class ChunkSectionCompiler {
    private final ChunkMeshBuilder[] builders = new ChunkMeshBuilder[RenderSection.PASS_COUNT];
    private final VisGraph visGraph = new VisGraph();

    public ChunkSectionCompiler() {
        for (int i = 0; i < this.builders.length; i++) {
            this.builders[i] = new ChunkMeshBuilder();
        }
    }

    public void compile(World world, RenderSection section) {
        int minX = section.getOriginX(), minY = section.getOriginY(), minZ = section.getOriginZ();
        int maxX = minX + 16, maxY = minY + 16, maxZ = minZ + 16;

        if (SectionEmptinessCheck.isEmpty(world, section.getChunkX(), section.getChunkY(), section.getChunkZ())) {
            section.setBuildResult(new ChunkMesh[RenderSection.PASS_COUNT], List.of(), VisibilityEncoding.ALL, false);
            return;
        }

        Chunk.hasSkyLight = false;

        WorldRegion region = new WorldRegion(world, minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);
        BlockRenderManager blockRenderer = new BlockRenderManager(region);

        Tessellator tessellator = Tessellator.INSTANCE;
        TessellatorCapture capture = (TessellatorCapture) tessellator;

        List<BlockEntity> blockEntities = new ArrayList<>();
        this.visGraph.reset();

        int bottomY = world.getBottomY();
        int topY = world.getTopY();
        int fromY = Math.max(minY, bottomY);
        int toY = Math.min(maxY, topY);

        for (ChunkMeshBuilder builder : this.builders) {
            builder.reset();
        }

        for (int pass = 0; pass < RenderSection.PASS_COUNT; pass++) {
            boolean hasLaterPass = false;
            ChunkMeshBuilder builder = this.builders[pass];

            capture.halite$setCaptureTarget(builder);
            try {
                tessellator.startQuads();
                tessellator.setOffset(-minX, -minY, -minZ);

                for (int y = fromY; y < toY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        for (int x = minX; x < maxX; x++) {
                            int id = region.getBlockId(x, y, z);
                            if (id <= 0) {
                                continue;
                            }

                            Block[] blocks = Block.BLOCKS;
                            Block block = id < blocks.length ? blocks[id] : null;
                            if (block == null) {
                                continue;
                            }

                            if (pass == 0) {
                                this.collectStatic(region, blockEntities, id, x, y, z, minX, minY, minZ);
                            }

                            int layer = block.getRenderLayer();
                            if (layer != pass) {
                                hasLaterPass |= layer > pass;
                                continue;
                            }

                            blockRenderer.render(block, x, y, z);
                        }
                    }
                }

                tessellator.draw();
            } finally {
                if (capture.halite$isDrawing()) {
                    // a block renderer threw mid-way; flush into the builder so the tessellator stays usable
                    tessellator.draw();
                }
                tessellator.setOffset(0.0, 0.0, 0.0);
                capture.halite$setCaptureTarget(null);
            }

            if (!hasLaterPass) {
                break;
            }
        }

        ChunkMesh[] meshes = new ChunkMesh[RenderSection.PASS_COUNT];
        for (int pass = 0; pass < RenderSection.PASS_COUNT; pass++) {
            ChunkMeshBuilder.BuiltMesh built = this.builders[pass].build(pass == RenderSection.PASS_TRANSLUCENT);
            if (built != null) {
                meshes[pass] = new ChunkMesh(built);
            }
        }

        section.setBuildResult(meshes, blockEntities, this.visGraph.resolve(), Chunk.hasSkyLight);
    }

    private void collectStatic(WorldRegion region, List<BlockEntity> blockEntities, int id,
                               int x, int y, int z, int minX, int minY, int minZ) {
        boolean[] opaque = Block.BLOCKS_OPAQUE;
        if (id < opaque.length && opaque[id]) {
            this.visGraph.setOpaque(x - minX, y - minY, z - minZ);
        }

        boolean[] withEntity = Block.BLOCKS_WITH_ENTITY;
        if (id < withEntity.length && withEntity[id]) {
            BlockEntity blockEntity = region.getBlockEntity(x, y, z);
            if (blockEntity != null && BlockEntityRenderDispatcher.INSTANCE.hasRenderer(blockEntity)) {
                blockEntities.add(blockEntity);
            }
        }
    }
}
