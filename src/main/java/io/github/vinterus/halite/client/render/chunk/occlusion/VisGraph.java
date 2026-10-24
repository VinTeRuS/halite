package io.github.vinterus.halite.client.render.chunk.occlusion;

import java.util.BitSet;

/**
 * Computes which faces of a 16x16x16 section can see each other through non-opaque blocks. Each connected
 * region of open blocks touching the section boundary connects all the faces it touches.
 */
public class VisGraph {
    private static final int SIZE = 16 * 16 * 16;

    private static final int DX = 1;
    private static final int DZ = 16;
    private static final int DY = 16 * 16;

    private static final int[] BOUNDARY = createBoundaryIndices();

    private final BitSet opaque = new BitSet(SIZE);
    private final int[] queue = new int[SIZE];
    private int filled;

    private static int index(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    public void reset() {
        this.opaque.clear();
        this.filled = 0;
    }

    public void setOpaque(int x, int y, int z) {
        int i = index(x, y, z);
        if (!this.opaque.get(i)) {
            this.opaque.set(i);
            this.filled++;
        }
    }

    public long resolve() {
        // fewer than one full layer of opaque blocks can never separate two faces
        if (this.filled < 256) {
            return VisibilityEncoding.ALL;
        }

        if (this.filled == SIZE) {
            return VisibilityEncoding.NULL;
        }

        BitSet visited = (BitSet) this.opaque.clone();
        long data = 0L;

        for (int start : BOUNDARY) {
            if (visited.get(start)) {
                continue;
            }

            int faces = this.floodFill(visited, start);

            for (int from = 0; from < GraphDirection.COUNT; from++) {
                if ((faces & (1 << from)) == 0) {
                    continue;
                }
                for (int to = 0; to < GraphDirection.COUNT; to++) {
                    if ((faces & (1 << to)) != 0) {
                        data |= 1L << VisibilityEncoding.bit(from, to);
                    }
                }
            }
        }

        return data;
    }

    private int floodFill(BitSet visited, int start) {
        int head = 0, tail = 0;
        int faces = 0;

        this.queue[tail++] = start;
        visited.set(start);

        while (head < tail) {
            int i = this.queue[head++];
            int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;

            faces |= touchedFaces(x, y, z);

            if (x > 0) tail = this.push(visited, i - DX, tail);
            if (x < 15) tail = this.push(visited, i + DX, tail);
            if (z > 0) tail = this.push(visited, i - DZ, tail);
            if (z < 15) tail = this.push(visited, i + DZ, tail);
            if (y > 0) tail = this.push(visited, i - DY, tail);
            if (y < 15) tail = this.push(visited, i + DY, tail);
        }

        return faces;
    }

    private int push(BitSet visited, int i, int tail) {
        if (!visited.get(i)) {
            visited.set(i);
            this.queue[tail++] = i;
        }
        return tail;
    }

    private static int touchedFaces(int x, int y, int z) {
        int faces = 0;
        if (x == 0) faces |= 1 << GraphDirection.WEST;
        if (x == 15) faces |= 1 << GraphDirection.EAST;
        if (y == 0) faces |= 1 << GraphDirection.DOWN;
        if (y == 15) faces |= 1 << GraphDirection.UP;
        if (z == 0) faces |= 1 << GraphDirection.NORTH;
        if (z == 15) faces |= 1 << GraphDirection.SOUTH;
        return faces;
    }

    private static int[] createBoundaryIndices() {
        int[] out = new int[16 * 16 * 16 - 14 * 14 * 14];
        int n = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
                        out[n++] = index(x, y, z);
                    }
                }
            }
        }
        return out;
    }
}
