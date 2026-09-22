package dev.yuliang.zymbot.core.api;

/** A precise position. */
public record Vec3(double x, double y, double z) {
    public double horizontalDistance(Vec3 o) {
        double dx = x - o.x, dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
