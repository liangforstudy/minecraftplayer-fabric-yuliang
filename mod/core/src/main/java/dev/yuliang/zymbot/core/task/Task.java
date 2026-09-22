package dev.yuliang.zymbot.core.task;

/**
 * Everything the bot does is a task: ticked once per game tick, cancellable at any moment so an
 * interrupt can take over cleanly, and always able to say what it is (FOUNDATION.md decision 3).
 */
public interface Task {
    enum Status { RUNNING, DONE, FAILED }

    Status tick();

    void cancel();

    /** Shown by the status command, e.g. "walking to shelter". */
    String describe();

    /** Why it failed, when it did. */
    default String failure() { return ""; }

    static Task failed(String what, String why) {
        return new Task() {
            public Status tick() { return Status.FAILED; }
            public void cancel() {}
            public String describe() { return what; }
            public String failure() { return why; }
        };
    }

    /** Runs once, then is done. */
    static Task once(String what, Runnable action) {
        return new Task() {
            boolean done;
            public Status tick() {
                if (!done) { done = true; action.run(); }
                return Status.DONE;
            }
            public void cancel() { done = true; }
            public String describe() { return what; }
        };
    }
}
