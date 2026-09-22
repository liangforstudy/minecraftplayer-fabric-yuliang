package dev.yuliang.zymbot.core.api;

import dev.yuliang.zymbot.core.task.Task;

/**
 * The bot's hands: the only way the brain acts on the game. Grows phase by phase — adding a
 * method is cheap; the brain never reaches around it.
 */
public interface Hands {
    /** Press "Respawn" on the death screen. */
    void respawn();

    /** A line shown only to the local player (never sent to the server). */
    void notifyLocal(String message);

    /** Walk somewhere. Phase 1 puts Baritone behind this; until then it fails honestly. */
    default Task walkTo(BlockPos target) {
        return Task.failed("walk to " + target.x() + " " + target.y() + " " + target.z(), "no pathfinder yet (phase 1)");
    }
}
