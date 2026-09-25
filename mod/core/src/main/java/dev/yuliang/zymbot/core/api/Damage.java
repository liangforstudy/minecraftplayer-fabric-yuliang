package dev.yuliang.zymbot.core.api;

import java.util.UUID;

/**
 * The bot was just hurt. {@code type} is the damage type ("minecraft:mob_attack", "minecraft:fall");
 * the attacker fields are null when nothing caused it (falling, drowning) or it isn't loaded.
 * {@code attackerId} lets the bot keep following the attacker's live position in {@link WorldView#nearby()}.
 */
public record Damage(String type, String attacker, Vec3 attackerPos, UUID attackerId) {
    public Damage(String type, String attacker, Vec3 attackerPos) { this(type, attacker, attackerPos, null); }
}
