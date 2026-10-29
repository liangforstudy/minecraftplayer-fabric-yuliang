package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.store.BotMemory.Waypoint;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Walk a route's points in order, one {@link RouteTask} a leg, from the point {@code leg} says (read
 * fresh each time it's built: the brain builds a new task after every interrupt). Each point reached
 * is reported, so the walk carries on from the next one after an interrupt, a death or a log-out.
 */
public final class RouteWalkTask implements Task {
    /** Tells the route a point was reached: (its index, how many there are). */
    public interface Reached { void reached(int leg, int of); }

    private final String route;
    private final List<String> names;
    private final List<Waypoint> points;
    private final IntSupplier leg;
    private final Function<Waypoint, Task> walk;
    private final Reached reached;
    private final BiConsumer<String, String> note;
    private Task current;
    private java.util.function.Predicate<WorldView> stopFor = w -> false;
    private java.util.function.Supplier<Task> waitOut;
    private Task waiting;
    private int at = -1;
    private String failure = "";

    /**
     * @param leg  the point to head for now (the route's saved progress)
     * @param walk one leg: a walk to that point (a {@link RouteTask})
     */
    public RouteWalkTask(String route, List<String> names, List<Waypoint> points, IntSupplier leg,
                         Function<Waypoint, Task> walk, Reached reached, BiConsumer<String, String> note) {
        this.route = route;
        this.names = names;
        this.points = points;
        this.leg = leg;
        this.walk = walk;
        this.reached = reached;
        this.note = note;
    }

    /**
     * Anarchy, overworld routes at night (doc/9b9t/roadmap.md): while {@code stop} holds, drop the leg
     * and run {@code waitOut} (dig in until day) instead, then carry on with the same leg. Never
     * stops in water or mid-air: the leg goes on until it's on its feet on dry ground.
     */
    public RouteWalkTask stopFor(java.util.function.Predicate<WorldView> stop, java.util.function.Supplier<Task> waitOut) {
        this.stopFor = stop;
        this.waitOut = waitOut;
        return this;
    }

    @Override
    public Status tick(WorldView world) {
        if (waiting != null) {
            Status w = waiting.tick(world);
            if (w == Status.RUNNING) return Status.RUNNING;
            String why = waiting.failure();
            waiting = null;
            if (w == Status.FAILED || stopFor.test(world)) {
                failure = "stopped for the night" + (why.isEmpty() ? "" : ": " + why);
                return Status.FAILED;
            }
            note.accept("carrying on with route " + route, "day: nothing spawns in the open now");
        }
        int next = current != null ? at : leg.getAsInt();             // nothing left to walk: no wait
        if (waitOut != null && next >= 0 && next < points.size() && stopFor.test(world) && world.onGround() && !world.inWater()) {
            if (current != null) current.cancel();
            current = null;
            waiting = waitOut.get();
            note.accept("stopping route " + route + " for the night", "it gets dark: digging in to wait for day");
            return Status.RUNNING;
        }
        if (current == null) {
            at = leg.getAsInt();
            if (at < 0 || at >= points.size()) return Status.DONE;
            Waypoint p = points.get(at);
            if (!p.dimension.equals(world.dimension())) {
                failure = names.get(at) + " is in another dimension (" + p.dimension + ")";
                return Status.FAILED;
            }
            current = walk.apply(p);
            note.accept("walking to " + names.get(at) + " at " + p.where(), "route " + route + ", point " + (at + 1) + " of " + points.size());
        }
        Status s = current.tick(world);
        if (s == Status.FAILED) {
            failure = "the walk to " + names.get(at) + " failed: " + current.failure();
            current = null;
            return Status.FAILED;
        }
        if (s != Status.DONE) return Status.RUNNING;
        current = null;
        reached.reached(at, points.size());
        if (at + 1 >= points.size()) {
            note.accept("route " + route + " done", "at " + names.get(at));
            return Status.DONE;
        }
        return Status.RUNNING;                                   // the next leg starts next tick
    }

    @Override public void cancel() {
        if (current != null) current.cancel();
        current = null;
        if (waiting != null) waiting.cancel();
        waiting = null;
    }
    @Override public String failure() { return failure; }
    @Override public String describe() {
        if (waiting != null) return waiting.describe() + " (route " + route + ")";
        return at < 0 || at >= names.size() ? "walking route " + route : "walking route " + route + " to " + names.get(at);
    }
}
