package io.github.liangforstudy.zymbot.core.ladder;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * One milestone of the ladder (BOT_BEHAVIOUR → Choosing what to do; PHASE3.md step 4), as data: its
 * id and name, who owns it, how many players it takes, a check, and — when unmet — the next step
 * toward it.
 */
public interface Rung {
    /** "M0". */
    String id();

    /** "HAS_TOOL". */
    String name();

    /** Whose milestone it is: "any" for the rungs every player climbs alone. */
    default String owner() { return "any"; }

    default int minPlayers() { return 1; }

    boolean met(WorldView world);

    /**
     * The next step toward it, or empty with {@link #waiting()} saying why there's nothing to do now.
     * Called when the planner is choosing, not every tick of a running step.
     */
    Optional<Step> next(WorldView world);

    /** Why {@link #next} came back empty, last time. */
    String waiting();

    /** {@link #next} came back empty only because a failed step is waiting to be tried again: still work here. */
    default boolean retrying() { return false; }

    /** A step ended: done, or failed with a reason (the rung may avoid it for a while). */
    void finished(Step step, boolean done, String failure);

    /**
     * @param key  what it works on, for remembering failures ("log 4 64 0", "craft planks")
     * @param what what the step does ("punch a log")
     * @param why  why this step, now ("no tool, 0 logs")
     */
    record Step(int number, int of, String key, String what, String why, BiFunction<WorldView, Hands, Task> task) {}
}
