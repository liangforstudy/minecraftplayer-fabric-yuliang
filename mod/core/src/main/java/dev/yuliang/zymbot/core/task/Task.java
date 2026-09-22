package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.WorldView;

/**
 * Everything the bot does is a task: ticked once per game tick with that tick's senses,
 * cancellable at any moment so an interrupt can take over cleanly, and always able to say what
 * it is (FOUNDATION.md decision 3). Tasks get their Hands when they're built.
 */
public interface Task {
    enum Status { RUNNING, DONE, FAILED }

    Status tick(WorldView world);

    void cancel();

    /** Shown by the status command, e.g. "walking to shelter". */
    String describe();

    /** Why it failed, when it did. */
    default String failure() { return ""; }

    /** Known to fail before it starts (no food, nothing to do) — the brain won't let it interrupt anything. */
    default boolean failedUpfront() { return false; }

    static Task failed(String what, String why) {
        return new Task() {
            public boolean failedUpfront() { return true; }
            public Status tick(WorldView world) { return Status.FAILED; }
            public void cancel() {}
            public String describe() { return what; }
            public String failure() { return why; }
        };
    }

    /** Runs once, then is done. */
    static Task once(String what, Runnable action) {
        return new Task() {
            boolean done;
            public Status tick(WorldView world) {
                if (!done) { done = true; action.run(); }
                return Status.DONE;
            }
            public void cancel() { done = true; }
            public String describe() { return what; }
        };
    }
}
