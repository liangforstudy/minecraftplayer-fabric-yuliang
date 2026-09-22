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
 * Walk somewhere far: <b>Baritone walks the whole way</b> (it's good at long walks), with water
 * banned; our planner only keeps a lookout (PHASE1.md → Water, Step B). Every {@link #LOOK_AHEAD_EVERY}
 * blocks it checks the loaded ground ahead on its own thread; if the cheapest way (hunger as the
 * cost) crosses water, it takes over for just that crossing — to the near shore, across, then hands
 * the rest back to Baritone. Each crossing is logged with why it beat the dry way round. With no
 * dry way at all, the walk's own fallback applies: swim, and say so.
 */
public final class RouteTask implements Task {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    /** Check the ground ahead again after walking this far. */
    static final int LOOK_AHEAD_EVERY = 64;
    /** This close, don't bother looking ahead — just finish. */
    static final int FINISH_RANGE = 8;

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
    private BlockPos lookedFrom;                 // where the last look-ahead started
    private WalkTask cruise;                     // Baritone's own walk to the target
    private Deque<RoutePlanner.Leg> crossing;    // a planned crossing, leg by leg
    private WalkTask leg;
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
        if (within > 0 && horizontal(world, target) <= within) {    // close enough to the person
            cancel();
            paths.stop();
            return Status.DONE;
        }
        if (planning != null && planning.isDone()) {
            Plan p = planning.join();
            planning = null;
            onPlan(p);
        }
        if (leg == null && planning == null && horizontal(world, target) > FINISH_RANGE
                && (lookedFrom == null || horizontal(world, lookedFrom) >= LOOK_AHEAD_EVERY)) {
            lookAhead(world);                                          // in the background; keep walking
        }
        if (leg != null) {
            Status s = leg.tick(world);
            if (s == Status.RUNNING) return Status.RUNNING;
            leg = crossing != null && s == Status.DONE ? nextLeg() : null;
            if (leg == null) {
                crossing = null;
                lookedFrom = null;                                     // across: look ahead again from here
            }
            return Status.RUNNING;
        }
        if (cruise == null) cruise = new WalkTask(paths, target, ignoreY, within, why -> log.accept("swimming", why));
        Status s = cruise.tick(world);
        if (s == Status.FAILED) return fail(cruise.failure());
        return s;
    }

    private void lookAhead(WorldView world) {
        BlockPos from = BlockPos.of(world.position());
        lookedFrom = from;
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
    }

    /** A look-ahead came back. Dry: Baritone carries on. Water worth crossing: take over for the crossing. */
    private void onPlan(Plan p) {
        if (p.route.isEmpty() || p.route.get().waterBlocks() == 0) return;
        var r = p.route.get();
        var legs = RoutePlanner.legs(r, Integer.MAX_VALUE);
        int lastSwim = -1;
        for (int i = 0; i < legs.size(); i++) if (legs.get(i).swim()) lastSwim = i;
        if (lastSwim < 0) return;
        String because = p.dry.isPresent()
                ? String.format(Locale.ROOT, "the dry way round is %d blocks longer (swim ≈%.2f food, walk round ≈%.2f)",
                        Math.round(p.dry.get().length() - r.length()), r.food(), p.dry.get().food())
                : "there's no dry way within " + radius + " blocks";
        log.accept(String.format(Locale.ROOT, "planning to swim %d blocks", Math.round(r.waterBlocks())), because);
        if (cruise != null) cruise.cancel();
        cruise = null;                                                 // Baritone picks up again after
        crossing = new ArrayDeque<>(legs.subList(0, lastSwim + 1));
        leg = nextLeg();
    }

    private WalkTask nextLeg() {
        RoutePlanner.Leg l = crossing.poll();
        if (l == null) return null;
        WalkTask w = new WalkTask(paths, l.end(), true, 1, why -> log.accept("swimming", why));
        return l.swim() ? w.swimFromStart() : w;
    }

    private static double horizontal(WorldView world, BlockPos p) {
        BlockPos at = BlockPos.of(world.position());
        return Math.hypot(at.x() - p.x(), at.z() - p.z());
    }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override
    public void cancel() {
        if (leg != null) leg.cancel();
        if (cruise != null) cruise.cancel();
        if (planning != null) planning.cancel(false);
    }

    @Override public String failure() { return failure; }

    @Override
    public String describe() {
        String where = what != null ? what : "walking to " + target.x() + (ignoreY ? "" : " " + target.y()) + " " + target.z();
        String now = leg != null ? " (crossing: " + leg.describe() + ")" : planning != null ? " (checking the way ahead)" : "";
        return where + now;
    }
}
