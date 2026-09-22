package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.PathProvider;
import dev.yuliang.zymbot.core.api.Terrain;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.route.RoutePlanner;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walk somewhere far, choosing where to cross water ourselves (PHASE1.md → Water, Step B): plan
 * over the loaded ground with hunger as the cost, then hand Baritone one leg at a time — dry legs
 * with water banned, each crossing as its own shore-to-shore leg. Past the loaded area it walks to
 * the edge and plans again. Every crossing is logged with why it beat the dry way round.
 * <p>
 * The ground is copied on the game thread (quick); the search runs on {@link #PLANNER}, so a big
 * plan never freezes the game (a 1.9 s one did, in the first live test).
 */
public final class RouteTask implements Task {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    static final int MAX_LAND_LEG = 48;
    /** This close, Baritone walks the rest even if the planner can't reach the exact column (a puddle, a step). */
    static final int FINISH_RANGE = 8;
    /** Replans in a row that bring us no closer before giving up. */
    static final int MAX_FRUITLESS_REPLANS = 3;

    /** Where plans are computed. Tests swap in a same-thread executor. */
    public static volatile Executor PLANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zymbot-route-planner");
        t.setDaemon(true);
        return t;
    });

    private record Plan(Optional<RoutePlanner.Route> route, Optional<RoutePlanner.Route> dry, long ms) {}

    private final PathProvider paths;
    private final BlockPos target;
    private final boolean ignoreY;
    private final int within;
    private final int radius;
    private final double swimCost;
    private final BiConsumer<String, String> log;
    private final String what;
    private CompletableFuture<Plan> planning;
    private Deque<RoutePlanner.Leg> legs;
    private boolean reachesGoal;
    private Task walk;
    private boolean finalApproach;
    private double bestDistance = Double.MAX_VALUE;
    private int fruitless;
    private String failure = "";

    public RouteTask(PathProvider paths, BlockPos target, boolean ignoreY, int radius, double swimCost,
                     BiConsumer<String, String> log) {
        this(paths, target, ignoreY, 0, radius, swimCost, log, null);
    }

    /**
     * @param within done this close (0 = that exact block)
     * @param what   how to describe it ("coming to Bot2"); null = "walking to x z"
     */
    public RouteTask(PathProvider paths, BlockPos target, boolean ignoreY, int within, int radius, double swimCost,
                     BiConsumer<String, String> log, String what) {
        this.paths = paths;
        this.target = target;
        this.ignoreY = ignoreY;
        this.within = Math.max(0, within);
        this.radius = radius;
        this.swimCost = swimCost;
        this.log = log;
        this.what = what;
    }

    @Override
    public Status tick(WorldView world) {
        if (!paths.available()) return fail("no pathfinder: " + paths.name());
        if (within > 0 && horizontal(world) <= within) {       // close enough to the person
            if (walk != null) walk.cancel();
            paths.stop();
            return Status.DONE;
        }
        if (planning != null) {
            if (!planning.isDone()) return Status.RUNNING;
            Plan p = planning.join();
            planning = null;
            if (!apply(p)) return Status.FAILED;
        }
        if (walk == null && !startNext(world)) return planning != null ? Status.RUNNING : Status.FAILED;
        if (walk == null) return Status.RUNNING;             // planning started
        Status s = walk.tick(world);
        if (s == Status.RUNNING) return Status.RUNNING;
        String why = walk.failure();
        walk = null;
        if (s == Status.DONE) return finalApproach ? Status.DONE : Status.RUNNING;
        if (finalApproach) return fail(why);
        legs = null;                                          // a leg failed: plan again from here
        return Status.RUNNING;
    }

    /** Start the next leg — or start planning if there's no plan, or the last one ran out at its edge. */
    private boolean startNext(WorldView world) {
        if (legs == null) return startPlanning(world);
        if (!legs.isEmpty()) {
            RoutePlanner.Leg leg = legs.poll();
            WalkTask w = new WalkTask(paths, leg.end(), true, 1, why -> log.accept("swimming", why));
            walk = leg.swim() ? w.swimFromStart() : w;
            return true;
        }
        if (reachesGoal) {                                    // the last few blocks: the exact target
            finalApproach = true;
            walk = new WalkTask(paths, target, ignoreY, within, why -> log.accept("swimming", why));
            return true;
        }
        legs = null;                                          // at the edge of what we could see
        return startPlanning(world);
    }

    private boolean startPlanning(WorldView world) {
        BlockPos from = BlockPos.of(world.position());
        double distance = Math.hypot(target.x() - from.x(), target.z() - from.z());
        if (distance <= Math.max(FINISH_RANGE, within)) {    // nearly there: Baritone does the last bit
            legs = new ArrayDeque<>();
            reachesGoal = true;
            return startNext(world);
        }
        if (distance < bestDistance - 1) {
            bestDistance = distance;
            fruitless = 0;
        } else if (++fruitless > MAX_FRUITLESS_REPLANS) {
            fail("no progress after " + MAX_FRUITLESS_REPLANS + " plans, " + Math.round(distance) + " blocks short");
            return false;
        }
        long t0 = System.nanoTime();
        Terrain ground = world.terrain().snapshot(from.x(), from.z(), radius);   // game thread: a quick copy
        long copyMs = (System.nanoTime() - t0) / 1_000_000;
        planning = CompletableFuture.supplyAsync(() -> {
            long p0 = System.nanoTime();
            RoutePlanner planner = new RoutePlanner(ground, from.x(), from.z(), radius);
            var route = planner.plan(from, target, swimCost);
            var dry = route.filter(r -> r.waterBlocks() > 0)
                    .flatMap(r -> planner.plan(from, target, 0).filter(d -> d.reachesGoal() == r.reachesGoal()));
            return new Plan(route, dry, (System.nanoTime() - p0) / 1_000_000);
        }, PLANNER).whenComplete((p, e) -> {
            if (e != null) LOG.warn("[zymbot] route planning failed: {}", e.toString());
            else LOG.info("[zymbot] route: {} — copied the ground in {} ms, planned in {} ms (off the game thread)",
                    p.route.map(r -> String.format(Locale.ROOT, "%.0f land + %.0f water blocks%s", r.landBlocks(),
                            r.waterBlocks(), r.reachesGoal() ? "" : " (to the edge of what's loaded)")).orElse("no plan"),
                    copyMs, p.ms);
        });
        return true;
    }

    /** A finished plan arrived: turn it into legs. False if it can't go on. */
    private boolean apply(Plan p) {
        if (p.route.isEmpty()) {                             // no terrain, or boxed in: let Baritone try
            legs = new ArrayDeque<>();
            reachesGoal = true;
            return true;
        }
        var r = p.route.get();
        if (r.waterBlocks() > 0) {
            String because = p.dry.isPresent()
                    ? String.format(Locale.ROOT, "the dry way round is %d blocks longer (swim ≈%.2f food, walk round ≈%.2f)",
                            Math.round(p.dry.get().length() - r.length()), r.food(), p.dry.get().food())
                    : "there's no dry way within " + radius + " blocks";
            log.accept(String.format(Locale.ROOT, "planning to swim %d blocks", Math.round(r.waterBlocks())), because);
        }
        legs = new ArrayDeque<>(RoutePlanner.legs(r, MAX_LAND_LEG));
        reachesGoal = r.reachesGoal();
        if (!reachesGoal && legs.isEmpty()) {
            fail("can't get any closer from here");
            return false;
        }
        return true;
    }

    private double horizontal(WorldView world) {
        BlockPos at = BlockPos.of(world.position());
        return Math.hypot(at.x() - target.x(), at.z() - target.z());
    }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override
    public void cancel() {
        if (walk != null) walk.cancel();
        if (planning != null) planning.cancel(false);
    }

    @Override public String failure() { return failure; }

    @Override
    public String describe() {
        String where = what != null ? what : "walking to " + target.x() + (ignoreY ? "" : " " + target.y()) + " " + target.z();
        String now = planning != null ? " (planning a route)" : walk != null && !finalApproach ? " (" + walk.describe() + ")" : "";
        return where + now;
    }
}
