package dev.yuliang.zymbot.core.api;

/**
 * The bot's hands: the only way the brain acts on the game. Grows phase by phase — adding a
 * method is cheap; the brain never reaches around it. Each call is one small, immediate action;
 * anything that takes time is a Task built from these.
 */
public interface Hands {
    /** Press "Respawn" on the death screen. */
    void respawn();

    /** A line shown only to the local player (never sent to the server). */
    void notifyLocal(String message);

    // ------------------------------------------------------------------ body (phase 1)

    /** Walking. {@link PathProvider#NONE} when Baritone isn't installed. */
    default PathProvider paths() { return PathProvider.NONE; }

    default void lookAt(Vec3 point) {}

    /**
     * Turn left/right toward a point only if more than {@code toleranceDegrees} off, and leave the
     * pitch alone — for steering while a human may be looking around (the camera is theirs too).
     */
    default void steerToward(Vec3 point, double toleranceDegrees) { lookAt(point); }

    /** Hold hotbar slot 0–8. */
    default void selectSlot(int hotbarSlot) {}

    /** Swap a main-inventory stack (slot 9–35) into a hotbar slot. */
    default void swapToHotbar(int inventorySlot, int hotbarSlot) {}

    /**
     * Hold forward and/or jump directly, without the pathfinder — for swimming to shore, where a
     * player who presses nothing sinks. Both false lets go.
     */
    default void holdKeys(boolean forward, boolean jump) {}

    /**
     * Hold (true) or let go of (false) the use key with the held item — eating, for now. Called
     * every tick while holding: each call also stops the client starting a second use by itself.
     */
    default void holdUse(boolean down) {}

    default void attack(int entityId) {}

    /** Public chat. Callers go through ChatOut, which rate-limits. */
    default void chat(String message) {}

    /** A server command, without the slash ("msg Bot2 hi"). */
    default void command(String command) {}
}
