package io.github.vinterus.halite.client.render.chunk.occlusion;

public class VisibilityEncoding {
    public static final long NULL = 0L;
    public static final long ALL;

    static {
        long all = 0L;
        for (int from = 0; from < GraphDirection.COUNT; from++) {
            for (int to = 0; to < GraphDirection.COUNT; to++) {
                all |= 1L << bit(from, to);
            }
        }
        ALL = all;
    }

    public static int bit(int from, int to) {
        return (from * 8) + to;
    }

    // Returns a merged bit-field of the outgoing directions for each incoming direction
    public static int getConnections(long visibilityData, int incoming) {
        return foldOutgoingDirections(visibilityData & createMask(incoming));
    }

    // Returns a merged bit-field of any possible outgoing directions
    public static int getConnections(long visibilityData) {
        return foldOutgoingDirections(visibilityData);
    }

    private static long createMask(int incoming) {
        var expanded = (0b0000001_0000001_0000001_0000001_0000001_0000001L * Integer.toUnsignedLong(incoming));
        return (expanded & 0b00000001_00000001_00000001_00000001_00000001_00000001L) * 0xFF;
    }

    private static int foldOutgoingDirections(long data) {
        long folded = data;
        folded |= folded >> 32; // fold top 32 bits onto bottom 32 bits
        folded |= folded >> 16; // fold top 16 bits onto bottom 16 bits
        folded |= folded >> 8; // fold top 8 bits onto bottom 8 bits

        return (int) (folded & GraphDirectionSet.ALL);
    }
}
