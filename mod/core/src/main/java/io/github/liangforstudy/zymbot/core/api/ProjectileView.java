package io.github.liangforstudy.zymbot.core.api;

/**
 * Something flying: an arrow, a fireball. {@code type} is the registry id ("minecraft:fireball" is a
 * ghast's), {@code velocity} in blocks a tick, {@code ownerId} the entity id of who shot it, -1 if unknown.
 */
public record ProjectileView(int id, String type, Vec3 pos, Vec3 velocity, int ownerId) {
    public double speed() {
        return Math.sqrt(velocity.x() * velocity.x() + velocity.y() * velocity.y() + velocity.z() * velocity.z());
    }
}
