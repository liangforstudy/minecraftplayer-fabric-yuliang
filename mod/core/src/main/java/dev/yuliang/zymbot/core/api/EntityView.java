package dev.yuliang.zymbot.core.api;

import java.util.UUID;

/** Something alive near the bot. {@code type} is the registry id, e.g. "minecraft:zombie". */
public record EntityView(int id, UUID uuid, String name, String type, Kind kind, Vec3 pos) {
    public enum Kind { PLAYER, HOSTILE, PASSIVE }
}
