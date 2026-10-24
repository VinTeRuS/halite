package io.github.vinterus.halite.client.render.chunk.compile;

import io.github.vinterus.halite.client.HaliteClientMod;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.modificationstation.stationapi.impl.world.chunk.ChunkSection;
import net.modificationstation.stationapi.impl.world.chunk.FlattenedChunk;

/**
 * Detects all-air sections from StationAPI's flattened chunk storage so they can skip meshing entirely; most of a
 * world's sections are empty sky. Uses StationAPI internals, so any linkage problem simply disables the shortcut.
 */
final class SectionEmptinessCheck {
    private static boolean available = true;

    private SectionEmptinessCheck() {
    }

    static boolean isEmpty(World world, int chunkX, int chunkY, int chunkZ) {
        if (!available) {
            return false;
        }

        try {
            Chunk chunk = world.getChunk(chunkX, chunkZ);
            if (!(chunk instanceof FlattenedChunk flattened)) {
                return false;
            }

            int index = chunkY - world.getBottomSectionCoord();
            ChunkSection[] sections = flattened.sections;
            if (index < 0 || index >= sections.length) {
                return true;
            }
            return ChunkSection.isEmpty(sections[index]);
        } catch (LinkageError e) {
            available = false;
            HaliteClientMod.logger().warn("StationAPI chunk sections not accessible, empty-section skipping disabled", e);
            return false;
        }
    }
}
