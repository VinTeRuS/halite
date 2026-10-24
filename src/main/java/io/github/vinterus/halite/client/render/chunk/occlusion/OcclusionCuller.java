package io.github.vinterus.halite.client.render.chunk.occlusion;

import io.github.vinterus.halite.client.render.chunk.RenderSection;
import io.github.vinterus.halite.client.render.chunk.SectionStorage;
import io.github.vinterus.halite.client.render.viewport.Viewport;

/**
 * Breadth-first search over sections starting at the camera, only walking outwards and only through faces that
 * the section's visibility data connects. Adapted from Sodium 0.5 (LGPLv3).
 */
public class OcclusionCuller {
    // Section half-size, plus one block for models extending outside their volume, plus an epsilon.
    private static final float SECTION_EXTENT = 8.0f + 1.0f + 0.125f;

    private final SectionStorage sections;

    private RenderSection[] queue = new RenderSection[4096];
    private RenderSection[] nextQueue = new RenderSection[4096];
    private int queueSize, nextQueueSize;

    public OcclusionCuller(SectionStorage sections) {
        this.sections = sections;
    }

    public void findVisible(Visitor visitor, Viewport viewport, float searchDistance, boolean useOcclusionCulling,
                            int frame, int bottomSection, int topSection) {
        this.queueSize = 0;
        this.nextQueueSize = 0;

        this.init(visitor, viewport, searchDistance, useOcclusionCulling, frame, bottomSection, topSection);

        while (this.flip()) {
            for (int i = 0; i < this.queueSize; i++) {
                RenderSection section = this.queue[i];
                this.queue[i] = null;
                this.process(visitor, viewport, searchDistance, useOcclusionCulling, frame, section);
            }
        }
    }

    private boolean flip() {
        RenderSection[] tmp = this.queue;
        this.queue = this.nextQueue;
        this.nextQueue = tmp;
        this.queueSize = this.nextQueueSize;
        this.nextQueueSize = 0;
        return this.queueSize > 0;
    }

    private void enqueue(RenderSection section) {
        if (this.nextQueueSize == this.nextQueue.length) {
            this.nextQueue = java.util.Arrays.copyOf(this.nextQueue, this.nextQueue.length * 2);
        }
        this.nextQueue[this.nextQueueSize++] = section;
    }

    private void process(Visitor visitor, Viewport viewport, float searchDistance, boolean useOcclusionCulling,
                         int frame, RenderSection section) {
        boolean visible = isSectionVisible(section, viewport, searchDistance);
        visitor.visit(section, visible);

        if (!visible) {
            return;
        }

        int connections;
        if (useOcclusionCulling) {
            connections = VisibilityEncoding.getConnections(section.getVisibilityData(), section.getIncomingDirections());
        } else {
            connections = GraphDirectionSet.ALL;
        }

        connections &= getOutwardDirections(viewport, section);
        this.visitNeighbors(section, connections, frame);
    }

    private void visitNeighbors(RenderSection section, int outgoing, int frame) {
        if (outgoing == GraphDirectionSet.NONE) {
            return;
        }

        for (int dir = 0; dir < GraphDirection.COUNT; dir++) {
            if (GraphDirectionSet.contains(outgoing, dir)) {
                RenderSection adjacent = section.getAdjacent(dir);
                if (adjacent != null) {
                    this.visitNode(adjacent, GraphDirectionSet.of(GraphDirection.opposite(dir)), frame);
                }
            }
        }
    }

    private void visitNode(RenderSection section, int incoming, int frame) {
        if (section.getSearchFrame() != frame) {
            section.setSearchFrame(frame);
            section.setIncomingDirections(GraphDirectionSet.NONE);
            this.enqueue(section);
        }

        section.addIncomingDirections(incoming);
    }

    private static int getOutwardDirections(Viewport viewport, RenderSection section) {
        int planes = 0;

        planes |= section.getChunkX() <= viewport.chunkX ? 1 << GraphDirection.WEST : 0;
        planes |= section.getChunkX() >= viewport.chunkX ? 1 << GraphDirection.EAST : 0;

        planes |= section.getChunkY() <= viewport.chunkY ? 1 << GraphDirection.DOWN : 0;
        planes |= section.getChunkY() >= viewport.chunkY ? 1 << GraphDirection.UP : 0;

        planes |= section.getChunkZ() <= viewport.chunkZ ? 1 << GraphDirection.NORTH : 0;
        planes |= section.getChunkZ() >= viewport.chunkZ ? 1 << GraphDirection.SOUTH : 0;

        return planes;
    }

    private static boolean isSectionVisible(RenderSection section, Viewport viewport, float maxDistance) {
        return isWithinRenderDistance(viewport, section, maxDistance) && isWithinFrustum(viewport, section);
    }

    private static boolean isWithinRenderDistance(Viewport camera, RenderSection section, float maxDistance) {
        int ox = section.getOriginX() - camera.intX;
        int oy = section.getOriginY() - camera.intY;
        int oz = section.getOriginZ() - camera.intZ;

        float dx = nearestToZero(ox, ox + 16) - camera.fracX;
        float dy = nearestToZero(oy, oy + 16) - camera.fracY;
        float dz = nearestToZero(oz, oz + 16) - camera.fracZ;

        // cylindrical distance, matching vanilla fog
        return (((dx * dx) + (dz * dz)) < (maxDistance * maxDistance)) && (Math.abs(dy) < maxDistance);
    }

    @SuppressWarnings("ManualMinMaxCalculation")
    private static int nearestToZero(int min, int max) {
        int clamped = 0;
        if (min > 0) {
            clamped = min;
        }
        if (max < 0) {
            clamped = max;
        }
        return clamped;
    }

    public static boolean isWithinFrustum(Viewport viewport, RenderSection section) {
        double cx = section.getOriginX() + 8.0, cy = section.getOriginY() + 8.0, cz = section.getOriginZ() + 8.0;
        return viewport.isBoxVisible(cx - SECTION_EXTENT, cy - SECTION_EXTENT, cz - SECTION_EXTENT,
                cx + SECTION_EXTENT, cy + SECTION_EXTENT, cz + SECTION_EXTENT);
    }

    private void init(Visitor visitor, Viewport viewport, float searchDistance, boolean useOcclusionCulling,
                      int frame, int bottomSection, int topSection) {
        if (viewport.chunkY < bottomSection) {
            this.initOutsideWorldHeight(viewport, searchDistance, frame, bottomSection, GraphDirection.DOWN);
        } else if (viewport.chunkY >= topSection) {
            this.initOutsideWorldHeight(viewport, searchDistance, frame, topSection - 1, GraphDirection.UP);
        } else {
            this.initWithinWorld(visitor, viewport, useOcclusionCulling, frame);
        }
    }

    private void initWithinWorld(Visitor visitor, Viewport viewport, boolean useOcclusionCulling, int frame) {
        RenderSection section = this.sections.get(viewport.chunkX, viewport.chunkY, viewport.chunkZ);
        if (section == null) {
            return;
        }

        section.setSearchFrame(frame);
        section.setIncomingDirections(GraphDirectionSet.NONE);

        visitor.visit(section, true);

        int outgoing = useOcclusionCulling
                ? VisibilityEncoding.getConnections(section.getVisibilityData())
                : GraphDirectionSet.ALL;

        this.visitNeighbors(section, outgoing, frame);
    }

    // Enqueues sections in a diamond spiral so the innermost ones come first, without sorting.
    private void initOutsideWorldHeight(Viewport viewport, float searchDistance, int frame, int height, int direction) {
        int ox = viewport.chunkX, oz = viewport.chunkZ;
        int radius = (int) Math.floor(searchDistance / 16.0f);

        this.tryVisitNode(ox, height, oz, direction, frame, viewport);

        for (int layer = 1; layer <= radius; layer++) {
            for (int z = -layer; z < layer; z++) {
                int x = Math.abs(z) - layer;
                this.tryVisitNode(ox + x, height, oz + z, direction, frame, viewport);
            }
            for (int z = layer; z > -layer; z--) {
                int x = layer - Math.abs(z);
                this.tryVisitNode(ox + x, height, oz + z, direction, frame, viewport);
            }
        }

        for (int layer = radius + 1; layer <= 2 * radius; layer++) {
            int l = layer - radius;

            for (int z = -radius; z <= -l; z++) {
                this.tryVisitNode(ox - z - layer, height, oz + z, direction, frame, viewport);
            }
            for (int z = l; z <= radius; z++) {
                this.tryVisitNode(ox + z - layer, height, oz + z, direction, frame, viewport);
            }
            for (int z = radius; z >= l; z--) {
                this.tryVisitNode(ox + layer - z, height, oz + z, direction, frame, viewport);
            }
            for (int z = -l; z >= -radius; z--) {
                this.tryVisitNode(ox + layer + z, height, oz + z, direction, frame, viewport);
            }
        }
    }

    private void tryVisitNode(int x, int y, int z, int direction, int frame, Viewport viewport) {
        RenderSection section = this.sections.get(x, y, z);
        if (section == null || !isWithinFrustum(viewport, section)) {
            return;
        }
        this.visitNode(section, GraphDirectionSet.of(direction), frame);
    }

    public interface Visitor {
        void visit(RenderSection section, boolean visible);
    }
}
