package dev.yuliang.zymbot.core.brain;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.task.Task;
import java.util.List;
import java.util.Optional;

/**
 * The decision loop (FOUNDATION.md decision 5), ticked once per game tick on the client thread:
 * survival interrupts first, in priority order; otherwise the best objective from the planner.
 * Switching always cancels the old task, and always records why.
 */
public final class Brain {
    public static final String IDLE = "idle";
    public static final String NO_OBJECTIVES = "no objectives";

    private final List<Interrupt> interrupts;
    private final Planner planner;
    private final DecisionLog log;
    private Task current;
    private String currentWhy = NO_OBJECTIVES;
    private String activeInterrupt;

    public Brain(List<Interrupt> interrupts, Planner planner, DecisionLog log) {
        this.interrupts = List.copyOf(interrupts);
        this.planner = planner;
        this.log = log;
    }

    public void tick(WorldView world, Hands hands) {
        for (Interrupt i : interrupts) {
            if (i.triggered(world)) {
                if (!i.name().equals(activeInterrupt)) {
                    activeInterrupt = i.name();
                    switchTo(i.respond(world, hands), i.why(world));
                }
                runCurrent();
                return;
            }
        }
        activeInterrupt = null;

        if (current != null && runCurrent() == Task.Status.RUNNING) return;

        Optional<Objective> next = planner.best(world);
        if (next.isPresent()) {
            switchTo(next.get().start(world, hands), next.get().why());
        } else if (current != null || !NO_OBJECTIVES.equals(currentWhy)) {
            switchTo(null, NO_OBJECTIVES);
        }
    }

    /** Stop whatever is running (bot stopped, or a human took the controls). */
    public void halt(String why) {
        if (current != null) switchTo(null, why);
        activeInterrupt = null;
    }

    public String describe() {
        return (current == null ? IDLE : current.describe()) + " — " + currentWhy;
    }

    private Task.Status runCurrent() {
        if (current == null) return Task.Status.DONE;
        Task.Status s = current.tick();
        if (s == Task.Status.FAILED) log.record("failed: " + current.describe(), current.failure());
        if (s != Task.Status.RUNNING) current = null;
        return s;
    }

    private void switchTo(Task task, String why) {
        if (current != null && current != task) current.cancel();
        current = task;
        currentWhy = why;
        log.record(task == null ? IDLE : task.describe(), why);
    }
}
