package io.github.vinterus.halite.client.render.chunk;

import io.github.vinterus.halite.client.render.chunk.occlusion.GraphDirection;
import io.github.vinterus.halite.client.render.chunk.occlusion.VisibilityEncoding;
import net.minecraft.block.entity.BlockEntity;

import java.util.Collections;
import java.util.List;

/**
 * A 16x16x16 section of the world as seen by the renderer: its meshes, visibility data and graph search state.
 */
public class RenderSection {
    public static final int PASS_SOLID = 0;
    public static final int PASS_TRANSLUCENT = 1;
    public static final int PASS_COUNT = 2;

    private final int chunkX, chunkY, chunkZ;

    private final ChunkMesh[] meshes = new ChunkMesh[PASS_COUNT];
    private List<BlockEntity> blockEntities = Collections.emptyList();

    private long visibilityData = VisibilityEncoding.ALL;
    private boolean built;
    private boolean dirty = true;
    private boolean usesSkyLight;

    // bumped on every change so builds started before the change don't mark the section clean
    private int version;
    private boolean building;
    private boolean deleted;

    // graph search state, see OcclusionCuller
    private final RenderSection[] adjacent = new RenderSection[GraphDirection.COUNT];
    private int searchFrame = -1;
    private int incomingDirections;
    private int visibleFrame = -1;

    public RenderSection(int chunkX, int chunkY, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkY = chunkY;
        this.chunkZ = chunkZ;
    }

    public int getChunkX() {
        return this.chunkX;
    }

    public int getChunkY() {
        return this.chunkY;
    }

    public int getChunkZ() {
        return this.chunkZ;
    }

    public int getOriginX() {
        return this.chunkX << 4;
    }

    public int getOriginY() {
        return this.chunkY << 4;
    }

    public int getOriginZ() {
        return this.chunkZ << 4;
    }

    public ChunkMesh getMesh(int pass) {
        return this.meshes[pass];
    }

    public boolean hasGeometry() {
        return this.meshes[PASS_SOLID] != null || this.meshes[PASS_TRANSLUCENT] != null;
    }

    public List<BlockEntity> getBlockEntities() {
        return this.blockEntities;
    }

    public long getVisibilityData() {
        return this.visibilityData;
    }

    public boolean isBuilt() {
        return this.built;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void markDirty() {
        this.dirty = true;
        this.version++;
    }

    public int getVersion() {
        return this.version;
    }

    /** Whether a rebuild of this section is in flight on a chunk builder thread. */
    public boolean isBuilding() {
        return this.building;
    }

    public void setBuilding(boolean building) {
        this.building = building;
    }

    public boolean isDeleted() {
        return this.deleted;
    }

    public boolean usesSkyLight() {
        return this.usesSkyLight;
    }

    public boolean isVisibleInFrame(int frame) {
        return this.visibleFrame == frame;
    }

    public void setVisibleFrame(int frame) {
        this.visibleFrame = frame;
    }

    public int getSearchFrame() {
        return this.searchFrame;
    }

    public void setSearchFrame(int frame) {
        this.searchFrame = frame;
    }

    public int getIncomingDirections() {
        return this.incomingDirections;
    }

    public void setIncomingDirections(int directions) {
        this.incomingDirections = directions;
    }

    public void addIncomingDirections(int directions) {
        this.incomingDirections |= directions;
    }

    public RenderSection getAdjacent(int direction) {
        return this.adjacent[direction];
    }

    void setAdjacent(int direction, RenderSection section) {
        this.adjacent[direction] = section;
    }

    /**
     * Replaces the section's contents with a finished build. Takes ownership of the meshes.
     *
     * @param version the section version the build started from; if the section changed since, it stays dirty
     */
    public void setBuildResult(ChunkMesh[] meshes, List<BlockEntity> blockEntities, long visibilityData,
                               boolean usesSkyLight, int version) {
        this.deleteMeshes();
        System.arraycopy(meshes, 0, this.meshes, 0, PASS_COUNT);
        this.blockEntities = blockEntities.isEmpty() ? Collections.emptyList() : blockEntities;
        this.visibilityData = visibilityData;
        this.usesSkyLight = usesSkyLight;
        this.built = true;
        this.dirty = version != this.version;
    }

    private void deleteMeshes() {
        for (int i = 0; i < PASS_COUNT; i++) {
            if (this.meshes[i] != null) {
                this.meshes[i].delete();
                this.meshes[i] = null;
            }
        }
    }

    public void delete() {
        this.deleted = true;
        this.deleteMeshes();
        this.blockEntities = Collections.emptyList();
        for (int dir = 0; dir < GraphDirection.COUNT; dir++) {
            RenderSection neighbor = this.adjacent[dir];
            if (neighbor != null) {
                neighbor.adjacent[GraphDirection.opposite(dir)] = null;
                this.adjacent[dir] = null;
            }
        }
    }

    public double squaredDistance(double x, double y, double z) {
        double dx = x - (this.getOriginX() + 8);
        double dy = y - (this.getOriginY() + 8);
        double dz = z - (this.getOriginZ() + 8);
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public String toString() {
        return "RenderSection[" + this.chunkX + ", " + this.chunkY + ", " + this.chunkZ + "]";
    }
}
