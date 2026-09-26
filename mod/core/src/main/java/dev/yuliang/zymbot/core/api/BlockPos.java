package dev.yuliang.zymbot.core.api;

/** A block position. The brain's own type — Minecraft's never crosses into core/. */
public record BlockPos(int x, int y, int z) {
    /** The middle of the block's floor: where a player standing on top of the block below would be. */
    public Vec3 center() { return new Vec3(x + 0.5, y, z + 0.5); }

    public static BlockPos of(Vec3 v) {
        return new BlockPos((int) Math.floor(v.x()), (int) Math.floor(v.y()), (int) Math.floor(v.z()));
    }
}
