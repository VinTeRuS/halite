package io.github.vinterus.halite.client.render.chunk.occlusion;

public class GraphDirection {
    public static final int DOWN    = 0;
    public static final int UP      = 1;
    public static final int NORTH   = 2;
    public static final int SOUTH   = 3;
    public static final int WEST    = 4;
    public static final int EAST    = 5;

    public static final int COUNT   = 6;

    private static final int[] X = {0, 0, 0, 0, -1, 1};
    private static final int[] Y = {-1, 1, 0, 0, 0, 0};
    private static final int[] Z = {0, 0, -1, 1, 0, 0};

    public static int opposite(int direction) {
        return direction ^ 1;
    }

    public static int x(int direction) {
        return X[direction];
    }

    public static int y(int direction) {
        return Y[direction];
    }

    public static int z(int direction) {
        return Z[direction];
    }
}
