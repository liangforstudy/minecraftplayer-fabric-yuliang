package dev.yuliang.zymbot.core.ladder;

import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Objective;
import dev.yuliang.zymbot.core.brain.Planner;
import dev.yuliang.zymbot.core.task.Task;
import java.util.List;
import java.util.Optional;

/**
 * The milestone ladder (PHASE3.md step 4): the lowest unmet rung gets worked on, one step at a time;
 * the brain asks again when a step ends. Regroup sits above it in the bot's planner — a bot far from
 * its team walks back first.
 */
public final class Ladder implements Planner {
    private final List<Rung> rungs;
    private Rung.Step current;
    private Rung currentRung;

    public Ladder(List<Rung> rungs) {
        this.rungs = List.copyOf(rungs);
    }

    @Override
    public Optional<Objective> best(WorldView world) {
        for (Rung r : rungs) {
            if (r.met(world)) continue;
            Optional<Rung.Step> next = r.next(world);
            if (next.isEmpty()) return Optional.empty();       // lowest unmet first: nothing else jumps it
            Rung.Step s = next.get();
            current = s;
            currentRung = r;
            return Optional.of(Objective.of(r.id() + " " + r.name() + ": " + s.what(),
                    r.id() + " step " + s.number() + "/" + s.of() + " — " + s.why(), (w, h) -> {
                        Task t = s.task().apply(w, h);
                        return new Task() {                     // tell the rung how it ended
                            public Status tick(WorldView world) {
                                Status st = t.tick(world);
                                if (st != Status.RUNNING) {
                                    if (current == s) current = null;
                                    r.finished(s, st == Status.DONE, t.failure());
                                }
                                return st;
                            }
                            public void cancel() { t.cancel(); }
                            public boolean failedUpfront() { return t.failedUpfront(); }
                            public String failure() { return t.failure(); }
                            public String describe() { return t.describe(); }
                        };
                    }));
        }
        current = null;
        return Optional.empty();
    }

    /** Some rung is unmet and has a step it could take now — worth staying for. */
    public boolean hasWork(WorldView world) {
        for (Rung r : rungs) {
            if (r.met(world)) continue;
            return r.next(world).isPresent();
        }
        return false;
    }

    /** For /zbot status: "ladder: M0 HAS_TOOL — step 3/5 crafting table (because no tool, 2 logs)". */
    public String describe(WorldView world) {
        if (world == null) return "ladder: —";
        for (Rung r : rungs) {
            if (r.met(world)) continue;
            String head = "ladder: " + r.id() + " " + r.name() + " — ";
            if (current != null && currentRung == r) {
                return head + "step " + current.number() + "/" + current.of() + " " + current.what() + " (because " + current.why() + ")";
            }
            String w = r.waiting();
            return head + (w.isEmpty() ? "not started" : "waiting: " + w);
        }
        Rung top = rungs.get(rungs.size() - 1);
        return "ladder: all met (up to " + top.id() + " " + top.name() + ")";
    }
}
