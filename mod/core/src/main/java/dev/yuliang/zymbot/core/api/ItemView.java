package dev.yuliang.zymbot.core.api;

/**
 * One inventory stack. {@code slot} 0–8 is the hotbar, 9–35 the main inventory. {@code food} is
 * null for anything that can't be eaten.
 */
public record ItemView(int slot, String id, int count, Food food) {
    public boolean edible() { return food != null; }
    public boolean inHotbar() { return slot >= 0 && slot < 9; }
}
