package dev.yuliang.zymbot.core.api;

/**
 * The bot was just hurt. {@code type} is the damage type ("minecraft:mob_attack", "minecraft:fall");
 * the attacker fields are null when nothing caused it (falling, drowning) or it isn't loaded.
 */
public record Damage(String type, String attacker, Vec3 attackerPos) {}
