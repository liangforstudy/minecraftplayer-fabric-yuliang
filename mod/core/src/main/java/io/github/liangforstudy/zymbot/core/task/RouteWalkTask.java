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

    @Override
    public Status tick(WorldView world) {
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

    @Override public void cancel() { if (current != null) current.cancel(); current = null; }
    @Override public String failure() { return failure; }
    @Override public String describe() {
        return at < 0 || at >= names.size() ? "walking route " + route : "walking route " + route + " to " + names.get(at);
    }
}
