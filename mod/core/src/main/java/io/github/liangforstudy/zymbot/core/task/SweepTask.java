package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.DroppedItem;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Walk over every item lying within {@code radius} of a spot until they're picked up, nearest first
 * (PHASE3.md decision D: "sweep the area for dropped items"). A drop it can't get to in
 * {@link #CHASE_TICKS} (up on the leaves, across water) is left; the whole sweep stops after
 * {@link #SWEEP_TICKS}. Items still falling are waited for a moment before "nothing here" counts.
 * Says what reached the inventory, counted from {@code before} — what the inventory held before
 * the work that made the drops.
 */
public final class SweepTask implements Task {
    public static final int SWEEP_TICKS = 25 * 20;
    static final int CHASE_TICKS = 6 * 20;
    /** Nothing seen yet: wait this long for drops still falling out of a tree. */
    static final int SETTLE_TICKS = 2 * 20;

    private final Hands hands;
    private final Vec3 centre;
    private final double radius;
    private final Map<String, Integer> before;
    private final String what;
    private final BiConsumer<String, String> log;
    private final Set<Integer> given = new HashSet<>();
    private int ticks;
    private DroppedItem chasing;
    private int chaseTicks;
    private int sinceGoal;
    private int chaseLimit = CHASE_TICKS, sweepLimit = SWEEP_TICKS;

    /** Give one drop this long to reach before moving on (a tear 30 blocks off takes longer than a log). */
    public SweepTask chaseFor(int seconds) {
        this.chaseLimit = seconds * 20;
        return this;
    }

    private String description;

    /** What the decision log calls it (owner's wording for a tear: "walking to ghast tear"). */
    public SweepTask describedAs(String description) {
        this.description = description;
        return this;
    }

    /** Stop the whole sweep after this long. */
    public SweepTask sweepFor(int seconds) {
        this.sweepLimit = seconds * 20;
        return this;
    }

    /** @param before the inventory's counts before the drops were made ({@link #counts}) */
    public SweepTask(Hands hands, BlockPos around, double radius, Map<String, Integer> before, String what,
                     BiConsumer<String, String> log) {
        this.hands = hands;
        this.centre = new Vec3(around.x() + 0.5, around.y() + 0.5, around.z() + 0.5);
        this.radius = radius;
        this.before = before;
        this.what = what;
        this.log = log;
    }

    @Override
    public Status tick(WorldView world) {
        ticks++;
        Vec3 me = world.position();
        List<DroppedItem> drops = world.droppedItems().stream()
                .filter(d -> !given.contains(d.id()) && flat(d.pos(), centre) <= radius)
                .sorted(Comparator.comparingDouble(d -> dist(d.pos(), me)))
                .toList();
        if (drops.isEmpty()) {
            if (ticks < SETTLE_TICKS) return Status.RUNNING;
            return finish(world, "");
        }
        if (ticks > sweepLimit) return finish(world, " — left " + drops.size() + " item stack(s) after " + sweepLimit / 20 + " s");
        DroppedItem next = drops.get(0);
        if (chasing == null || chasing.id() != next.id()) {
            chasing = next;
            chaseTicks = 0;
            sinceGoal = 0;
            hands.paths().goTo(BlockPos.of(next.pos()), false, 0, false);
            return Status.RUNNING;
        }
        if (++chaseTicks > chaseLimit) {                       // can't get to that one: the next
            given.add(next.id());
            chasing = null;
            return Status.RUNNING;
        }
        if (!hands.paths().busy() && ++sinceGoal > 10) {        // stopped short: ask again
            sinceGoal = 0;
            hands.paths().goTo(BlockPos.of(next.pos()), false, 0, false);
        }
        return Status.RUNNING;
    }

    private Status finish(WorldView world, String note) {
        hands.paths().stop();
        Map<String, Integer> after = counts(world);
        StringBuilder got = new StringBuilder();
        for (var e : after.entrySet()) {
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d > 0) got.append(got.isEmpty() ? "" : ", ").append(d).append(' ').append(shortName(e.getKey()));
        }
        String skipped = given.isEmpty() ? "" : "; couldn't reach " + given.size();
        if (got.isEmpty()) log.accept("picked up nothing", "swept around " + what + note + skipped);
        else log.accept("picked up " + got, "swept around " + what + note + skipped);
        return Status.DONE;
    }

    public static Map<String, Integer> counts(WorldView world) {
        Map<String, Integer> m = new HashMap<>();
        for (ItemView i : world.inventory()) m.merge(i.id(), i.count(), Integer::sum);
        return m;
    }

    private static String shortName(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static double flat(Vec3 a, Vec3 b) { return a.horizontalDistance(b); }

    private static double dist(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override public void cancel() { hands.paths().stop(); }

    @Override
    public String describe() { return description != null ? description : "picking up what fell around " + what; }
}
