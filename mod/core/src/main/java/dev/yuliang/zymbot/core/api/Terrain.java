package dev.yuliang.zymbot.core.api;

/**
 * The ground, column by column, as far as the client has it loaded — what the route planner
 * reads (PHASE1.md → Water, Step B). Read during the tick it was taken in, like the rest of
 * WorldView.
 */
public interface Terrain {
    enum Kind {
        /** Not loaded — the planner can't see there. */
        UNLOADED,
        /** Something to stand on. */
        LAND,
        /** Open water at the surface. */
        WATER,
        /** Never step there: lava, fire, cactus, magma… */
        BLOCKED
    }

    Kind kind(int x, int z);

    /** Feet height standing on this column (LAND), or the water surface (WATER). */
    int height(int x, int z);

    /**
     * A copy of the square around (ox, oz), read now on the calling thread and safe to read from any
     * other — the route planner runs on its own thread so the game never stalls on it.
     */
    default Terrain snapshot(int ox, int oz, int radius) {
        int side = 2 * radius + 1;
        byte[] kinds = new byte[side * side];
        int[] heights = new int[side * side];
        for (int ix = 0; ix < side; ix++) for (int iz = 0; iz < side; iz++) {
            int x = ox - radius + ix, z = oz - radius + iz, i = ix * side + iz;
            Kind k = kind(x, z);
            kinds[i] = (byte) k.ordinal();
            if (k == Kind.LAND || k == Kind.WATER) heights[i] = height(x, z);
        }
        Kind[] values = Kind.values();
        return new Terrain() {
            private int index(int x, int z) {
                int ix = x - ox + radius, iz = z - oz + radius;
                return ix < 0 || iz < 0 || ix >= side || iz >= side ? -1 : ix * side + iz;
            }
            public Kind kind(int x, int z) { int i = index(x, z); return i < 0 ? Kind.UNLOADED : values[kinds[i]]; }
            public int height(int x, int z) { int i = index(x, z); return i < 0 ? 0 : heights[i]; }
            public Terrain snapshot(int x, int z, int r) { return this; }
        };
    }

    Terrain NONE = new Terrain() {
        public Kind kind(int x, int z) { return Kind.UNLOADED; }
        public int height(int x, int z) { return 0; }
        public Terrain snapshot(int ox, int oz, int radius) { return this; }
    };
}
