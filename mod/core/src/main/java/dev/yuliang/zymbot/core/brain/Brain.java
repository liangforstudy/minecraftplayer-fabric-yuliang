package dev.yuliang.zymbot.core.brain;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.task.Task;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The decision loop (FOUNDATION.md decision 5), ticked once per game tick on the client thread:
 * <ol>
 *   <li>survival interrupts, in priority order — the first that fires and can act takes over;</li>
 *   <li>otherwise a player's order (/zbot goto, follow, eat...), resumed after any interrupt;</li>
 *   <li>otherwise the planner's best objective.</li>
 * </ol>
 * Switching always cancels the old task, and always records why.
 */
public final class Brain {
    public static final String IDLE = "idle";
    public static final String NO_OBJECTIVES = "no objectives";
    /** An interrupt whose response failed (no food, no path) steps aside this long. */
    public static final int FAILED_COOLDOWN_TICKS = 30 * 20;
    /** Between two responses to the same interrupt (a second bite), so they don't spin. */
    public static final int REPEAT_DELAY_TICKS = 20;
    /** A reflex that couldn't help looks again this often — the situation may have changed. */
    public static final int RECHECK_TICKS = 20;

    public enum Source { INTERRUPT, ORDER, PLANNER }

    private final List<Interrupt> interrupts;
    private final Planner planner;
    private final DecisionLog log;
    private Task current;
    private Source source;
    private String currentWhy = NO_OBJECTIVES;
    private String activeInterrupt;
    private Objective order;
    private long ticks;
    private final Map<String, Long> holdUntil = new HashMap<>();   // after DONE, still triggered: wait
    private final Map<String, Long> skipUntil = new HashMap<>();   // its task FAILED: let others run
    private final Map<String, Long> benchedUntil = new HashMap<>(); // couldn't help: recheck, quietly

    public Brain(List<Interrupt> interrupts, Planner planner, DecisionLog log) {
        this.interrupts = List.copyOf(interrupts);
        this.planner = planner;
        this.log = log;
    }

    public void tick(WorldView world, Hands hands) {
        ticks++;
        for (Interrupt i : interrupts) {
            if (skipUntil.getOrDefault(i.name(), 0L) > ticks || !i.triggered(world)) continue;
            boolean benched = benchedUntil.getOrDefault(i.name(), 0L) > ticks;
            if (benched && ticks % RECHECK_TICKS != 0) continue;
            boolean mine = source == Source.INTERRUPT && i.name().equals(activeInterrupt);
            if (!mine || current == null) {
                if (holdUntil.getOrDefault(i.name(), 0L) > ticks) return;
                Task response = i.respond(world, hands);
                if (response.failedUpfront()) {                // can't help: say so (once), don't interrupt
                    if (!benched) {
                        log.record("failed: " + response.describe() + " (" + i.why(world) + ")", response.failure());
                        benchedUntil.put(i.name(), ticks + FAILED_COOLDOWN_TICKS);
                    }
                    continue;
                }
                benchedUntil.remove(i.name());                 // something changed: it can help now
                activeInterrupt = i.name();
                switchTo(response, i.why(world), Source.INTERRUPT);
            }
            Task.Status s = runCurrent(world);
            if (s == Task.Status.FAILED) skipUntil.put(i.name(), ticks + FAILED_COOLDOWN_TICKS);
            if (s == Task.Status.DONE) holdUntil.put(i.name(), ticks + REPEAT_DELAY_TICKS);
            return;
        }
        activeInterrupt = null;

        // a reflex that's no longer needed still finishes what it started (don't spit out a bite)
        if (current != null && source == Source.INTERRUPT && runCurrent(world) == Task.Status.RUNNING) return;

        if (order != null) {
            if (current == null || source != Source.ORDER) switchTo(order.start(world, hands), order.why(), Source.ORDER);
            Task.Status s = runCurrent(world);
            if (s == Task.Status.DONE) {
                log.record("done: " + order.name(), order.why());
                order = null;
            } else if (s == Task.Status.FAILED) {
                order = null;
            }
            if (order == null) currentWhy = NO_OBJECTIVES;
            return;
        }

        if (current != null && source == Source.PLANNER && runCurrent(world) == Task.Status.RUNNING) return;

        Optional<Objective> next = planner.best(world);
        if (next.isPresent()) {
            switchTo(next.get().start(world, hands), next.get().why(), Source.PLANNER);
        } else if (current != null || !NO_OBJECTIVES.equals(currentWhy)) {
            switchTo(null, NO_OBJECTIVES, null);
        }
    }

    /** A player's order. Replaces any earlier order; runs as soon as no interrupt needs the body. */
    public void order(Objective o) {
        if (source == Source.ORDER && current != null) {
            current.cancel();
            current = null;
        }
        order = o;
    }

    /** Forget the order (and stop it if it's running). */
    public void cancelOrder(String why) {
        if (order == null) return;
        order = null;
        if (source == Source.ORDER && current != null) switchTo(null, why, null);
        else log.record("cancelled order", why);
    }

    /**
     * Stop whatever is running (bot paused or stopped, or died). A pending order is kept — it
     * resumes when the brain ticks again — unless the caller cancels it too.
     */
    public void halt(String why) {
        if (current != null) switchTo(null, why, null);
        activeInterrupt = null;
    }

    /** Working on the planner's own objective (not an order, not a reflex) — the leash applies. */
    public boolean autonomous() {
        return current != null && source == Source.PLANNER;
    }

    public boolean hasOrder() { return order != null; }

    public String describe() {
        return (current == null ? IDLE : current.describe()) + " — " + currentWhy
                + (order != null && source != Source.ORDER ? " (then: " + order.name() + ")" : "");
    }

    private Task.Status runCurrent(WorldView world) {
        if (current == null) return Task.Status.DONE;
        Task.Status s = current.tick(world);
        if (s == Task.Status.FAILED) log.record("failed: " + current.describe(), current.failure());
        if (s != Task.Status.RUNNING) current = null;
        return s;
    }

    private void switchTo(Task task, String why, Source from) {
        if (current != null && current != task) current.cancel();
        current = task;
        source = from;
        currentWhy = why;
        log.record(task == null ? IDLE : task.describe(), why);
    }
}
