package dev.yuliang.zymbot.core.api;

/** An item lying on the ground (an item entity). {@code item} is the registry id, e.g. "minecraft:oak_log". */
public record DroppedItem(int id, String item, int count, Vec3 pos) {}
