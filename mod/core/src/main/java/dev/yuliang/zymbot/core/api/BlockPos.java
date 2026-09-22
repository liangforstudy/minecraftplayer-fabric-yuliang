package dev.yuliang.zymbot.core.api;

/** A block position. The brain's own type — Minecraft's never crosses into core/. */
public record BlockPos(int x, int y, int z) {
    public static BlockPos of(Vec3 v) {
        return new BlockPos((int) Math.floor(v.x()), (int) Math.floor(v.y()), (int) Math.floor(v.z()));
    }
}
