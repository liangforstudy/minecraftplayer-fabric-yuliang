package io.github.liangforstudy.zymbot.core.api;

import java.util.List;
import java.util.Optional;

/**
 * The open crafting screen, in the brain's terms (PHASE3.md step 3): the player's own inventory with
 * its 2×2 grid, or a crafting table's 3×3. Slot numbers are the screen's own — what a click names.
 *
 * @param carried      the stack on the cursor, or null
 * @param carriedCount how many are on the cursor
 */
public record MenuView(Kind kind, List<Slot> slots, String carried, int carriedCount) {
    public enum Kind {
        /** The inventory screen (E): a 2×2 grid. */
        INVENTORY(2),
        /** A crafting table's screen: a 3×3 grid. */
        CRAFTING_TABLE(3),
        /** Anything else (a chest, a furnace): no grid of ours. */
        OTHER(0);

        public final int gridWidth;
        Kind(int gridWidth) { this.gridWidth = gridWidth; }
    }

    public enum Role { RESULT, GRID, PLAYER, OTHER }

    /**
     * @param index the screen's slot number
     * @param ref   GRID: the cell, row by row (0..3 or 0..8); PLAYER: the inventory slot (0–8 hotbar, 9–35); else -1
     * @param id    the item there, or null when empty
     */
    public record Slot(int index, Role role, int ref, String id, int count) {
        public boolean empty() { return id == null || count <= 0; }
    }

    public int gridWidth() { return kind.gridWidth; }

    public Optional<Slot> result() { return slots.stream().filter(s -> s.role() == Role.RESULT).findFirst(); }

    public Optional<Slot> cell(int ref) {
        return slots.stream().filter(s -> s.role() == Role.GRID && s.ref() == ref).findFirst();
    }

    /** The screen slot showing this inventory slot. */
    public Optional<Slot> player(int inventorySlot) {
        return slots.stream().filter(s -> s.role() == Role.PLAYER && s.ref() == inventorySlot).findFirst();
    }

    public List<Slot> grid() { return slots.stream().filter(s -> s.role() == Role.GRID).toList(); }

    public boolean cursorEmpty() { return carried == null || carriedCount <= 0; }
}
