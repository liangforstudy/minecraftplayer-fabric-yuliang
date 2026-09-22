package dev.yuliang.zymbot.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The bot's senses: a read-only snapshot of the game for one tick. The Fabric adapter builds it
 * from Minecraft; tests build it from a scripted fake world. The brain can't tell which.
 */
public interface WorldView {
    UUID selfId();
    String selfName();
    Vec3 position();
    float health();          // 0..20
    int hunger();            // 0..20
    boolean isDead();
    long timeOfDay();        // 0..24000
    long day();              // world day number
    /** Nearest known place with this tag ("bed", "village", ...). Empty until the map exists. */
    default Optional<BlockPos> nearest(String placeTag) { return Optional.empty(); }
}
