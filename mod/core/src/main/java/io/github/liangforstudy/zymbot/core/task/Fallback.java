package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.ArrayList;
import java.util.List;

/**
 * Try each task in turn: when one fails, the next takes over (cover, else dig in, else back off).
 * Fails only when the last one does.
 */
public final class Fallback implements Task {
    private final List<Task> tasks;
    private final List<String> failures = new ArrayList<>();
    private final java.util.function.BiConsumer<String, String> note;
    private int at;

    public Fallback(Task... tasks) {
        this((what, why) -> {}, tasks);
    }

    /** @param note each hand-over goes here (the decision log) */
    public Fallback(java.util.function.BiConsumer<String, String> note, Task... tasks) {
        this.note = note;
        this.tasks = List.of(tasks);
    }

    @Override
    public Status tick(WorldView world) {
        while (true) {
            Status s = tasks.get(at).tick(world);
            if (s == Status.DONE) note.accept("done: " + tasks.get(at).describe(), "");
            if (s != Status.FAILED || at == tasks.size() - 1) return s;
            failures.add(tasks.get(at).failure());
            note.accept(tasks.get(at + 1).describe(), tasks.get(at).describe() + " gave up: " + tasks.get(at).failure());
            at++;
        }
    }

    @Override public void cancel() { tasks.get(at).cancel(); }
    @Override public String failure() { return tasks.get(at).failure(); }
    @Override public String describe() {
        String d = tasks.get(at).describe();
        return failures.isEmpty() ? d : d + " (" + String.join("; ", failures) + ")";
    }
}
