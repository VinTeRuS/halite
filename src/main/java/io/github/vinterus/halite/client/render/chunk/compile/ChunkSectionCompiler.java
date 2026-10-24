package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.render.chunk.RenderSection;
import io.github.vinterus.halite.client.render.chunk.occlusion.VisGraph;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.world.BlockView;

import java.util.List;

/**
 * Meshes a section by running the regular block renderers (vanilla and StationAPI's) against a block view and
 * capturing what they write into a tessellator. Every block model and modded block renderer keeps working exactly
 * as with vanilla display lists, while the output goes into compact vertex buffers.
 *
 * <p>The same loop serves three cases: a full build on the render thread, a build of the thread-safe blocks on a
 * chunk builder thread (the rest are recorded), and the render-thread pass over those recorded blocks.
 */
public final class ChunkSectionCompiler {
    private ChunkSectionCompiler() {
    }

    /**
     * @param view          blocks to mesh, covering the section plus a margin
     * @param tessellator   the tessellator the block renderers on this thread write to
     * @param builders      one builder per render pass
     * @param visGraph      receives opaque blocks, or null to skip
     * @param blockEntities receives block entities that have a renderer, or null to skip
     * @param safeTable     when not null, only blocks marked safe are rendered; the others go to {@code deferred}
     * @param deferred      receives the section-local index of skipped blocks
     */
    public static void meshSection(BlockView view, int minX, int minY, int minZ, int bottomY, int topY,
                                   Tessellator tessellator, ChunkMeshBuilder[] builders, VisGraph visGraph,
                                   List<BlockEntity> blockEntities, boolean[] safeTable, IntArrayList deferred) {
        BlockRenderManager blockRenderer = new BlockRenderManager(view);
        TessellatorCapture capture = (TessellatorCapture) tessellator;

        int maxX = minX + 16, maxZ = minZ + 16;
        int fromY = Math.max(minY, bottomY);
        int toY = Math.min(minY + 16, topY);

        for (int pass = 0; pass < RenderSection.PASS_COUNT; pass++) {
            boolean hasLaterPass = false;

            capture.halite$setCaptureTarget(builders[pass]);
            try {
                tessellator.startQuads();
                tessellator.setOffset(-minX, -minY, -minZ);

                for (int y = fromY; y < toY; y++) {
                    for (int z = minZ; z < maxZ; z++) {
                        for (int x = minX; x < maxX; x++) {
                            int id = view.getBlockId(x, y, z);
                            if (id <= 0) {
                                continue;
                            }

                            Block[] blocks = Block.BLOCKS;
                            Block block = id < blocks.length ? blocks[id] : null;
                            if (block == null) {
                                continue;
                            }

                            boolean safe = safeTable == null || (id < safeTable.length && safeTable[id]);

                            if (pass == 0) {
                                collectStatic(view, visGraph, blockEntities, id, x, y, z, minX, minY, minZ);
                                if (!safe) {
                                    deferred.add(localIndex(x - minX, y - minY, z - minZ));
                                }
                            }

                            int layer = block.getRenderLayer();
                            if (layer != pass) {
                                hasLaterPass |= layer > pass;
                                continue;
                            }

                            if (safe) {
                                blockRenderer.render(block, x, y, z);
                            }
                        }
                    }
                }

                tessellator.draw();
            } finally {
                finish(tessellator, capture);
            }

            if (!hasLaterPass) {
                break;
            }
        }
    }

    /** Renders blocks recorded by a worker build on the render thread. */
    public static void meshDeferred(BlockView view, int minX, int minY, int minZ, Tessellator tessellator,
                                    ChunkMeshBuilder[] builders, IntArrayList deferred) {
        BlockRenderManager blockRenderer = new BlockRenderManager(view);
        TessellatorCapture capture = (TessellatorCapture) tessellator;

        for (int pass = 0; pass < RenderSection.PASS_COUNT; pass++) {
            capture.halite$setCaptureTarget(builders[pass]);
            try {
                tessellator.startQuads();
                tessellator.setOffset(-minX, -minY, -minZ);

                for (int i = 0; i < deferred.size(); i++) {
                    int index = deferred.getInt(i);
                    int x = minX + (index & 15), z = minZ + ((index >> 4) & 15), y = minY + (index >> 8);

                    int id = view.getBlockId(x, y, z);
                    Block[] blocks = Block.BLOCKS;
                    Block block = id > 0 && id < blocks.length ? blocks[id] : null;
                    if (block != null && block.getRenderLayer() == pass) {
                        blockRenderer.render(block, x, y, z);
                    }
                }

                tessellator.draw();
            } finally {
                finish(tessellator, capture);
            }
        }
    }

    private static void finish(Tessellator tessellator, TessellatorCapture capture) {
        if (capture.halite$isDrawing()) {
            // a block renderer threw mid-way; flush into the builder so the tessellator stays usable
            tessellator.draw();
        }
        tessellator.setOffset(0.0, 0.0, 0.0);
        capture.halite$setCaptureTarget(null);
    }

    static int localIndex(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    private static void collectStatic(BlockView view, VisGraph visGraph, List<BlockEntity> blockEntities, int id,
                                      int x, int y, int z, int minX, int minY, int minZ) {
        if (visGraph != null) {
            boolean[] opaque = Block.BLOCKS_OPAQUE;
            if (id < opaque.length && opaque[id]) {
                visGraph.setOpaque(x - minX, y - minY, z - minZ);
            }
        }

        if (blockEntities != null) {
            boolean[] withEntity = Block.BLOCKS_WITH_ENTITY;
            if (id < withEntity.length && withEntity[id]) {
                BlockEntity blockEntity = view.getBlockEntity(x, y, z);
                if (blockEntity != null && BlockEntityRenderDispatcher.INSTANCE.hasRenderer(blockEntity)) {
                    blockEntities.add(blockEntity);
                }
            }
        }
    }
}
