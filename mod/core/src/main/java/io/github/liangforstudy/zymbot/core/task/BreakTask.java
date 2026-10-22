package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.DroppedItem;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Break one chosen block like a player, then pick up what fell (PHASE3.md build step 2): walk
 * until it's within reach and in sight, hold the best hotbar tool (bare hands if none), dig until
 * it's gone, then walk over the drops nearby until they're picked up or {@link #COLLECT_TICKS}
 * pass. Zymbot picks the block; the pathfinder never breaks or places anything itself.
 * <p>
 * Attack is let go on every way out — done, failed, cancelled — and the Fabric hands also let go
 * by themselves on any tick {@link Hands#mine} isn't called.
 */
public final class BreakTask implements Task {
    /** Longest dig allowed: bare-handed stone is ~7.5 s, a log ~3 s. */
    public static final int BREAK_TICKS = 30 * 20;
    /** How long to look for the drops: 5 s. */
    public static final int COLLECT_TICKS = 5 * 20;
    /** Drops farther than this from the broken block aren't ours. */
    static final double DROP_RADIUS = 6;
    /** Hunger at or below this: too hungry to work (at 6 you can't sprint; eat first). */
    public static final int TOO_HUNGRY = 6;
    /** Stand this close (GoalNear) — well inside the ~4.5-block reach. */
    static final int WALK_WITHIN = 2;
    /** Walk again this many times if the block slips out of reach while digging. */
    static final int MAX_WALKS = 3;
    static final Set<String> EMPTY = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:water", "minecraft:lava");

    private enum Stage { START, WALK, BREAK, COLLECT }

    private final Hands hands;
    private final BlockPos target;
    private final String why;
    private final BiConsumer<String, String> log;
    private Stage stage = Stage.START;
    private String expected;
    private WalkTask walk;
    private int walks;
    private int breakTicks;
    private boolean toolChosen;
    private boolean mining;
    private int collectTicks;
    private Map<String, Integer> before;
    private DroppedItem chasing;
    private int sinceGoal;
    private String failure = "";
    private boolean collect = true;
    private BreakTask clearing;
    private int cleared;
    /** Soft blocks broken out of the line of sight, at most, before giving up on the block. */
    static final int MAX_CLEARS = 6;

    /**
     * Quick to break by hand and nobody's build: vines, leaves, grass, ferns, dead bushes, snow. What
     * stands between the bot and a log is cleared rather than the log given up on (owner, 2026-10-14:
     * "if there is a vine blocking the way, it is ignored").
     */
    static boolean soft(String id) {
        return id.contains("vine") || id.endsWith("_leaves") || id.endsWith("short_grass") || id.endsWith("tall_grass")
                || id.endsWith("fern") || id.endsWith("dead_bush") || id.equals("minecraft:snow") || id.endsWith("hanging_roots")
                || id.endsWith("glow_lichen");
    }

    /**
     * Done as soon as the block is gone — no walk over the drops. For felling several logs and
     * sweeping them up once at the end (ChopTask): the drops of a tree land all around it.
     */
    public BreakTask withoutCollect() {
        this.collect = false;
        return this;
    }

    /** @param log (what, why) — the decision log */
    public BreakTask(Hands hands, BlockPos target, String why, BiConsumer<String, String> log) {
        this.hands = hands;
        this.target = target;
        this.why = why;
        this.log = log;
    }

    @Override
    public Status tick(WorldView world) {
        return switch (stage) {
            case START -> start(world);
            case WALK -> walk(world);
            case BREAK -> dig(world);
            case COLLECT -> collect(world);
        };
    }

    private Status start(WorldView world) {
        expected = world.blockAt(target);
        if (expected.equals("unloaded")) return fail("the block at " + where() + " isn't loaded");
        if (EMPTY.contains(expected)) return fail("nothing to break at " + where() + " (" + expected + ")");
        if (world.hunger() <= TOO_HUNGRY) return fail("too hungry to work (hunger " + world.hunger() + ") — eat first");
        log.accept("breaking " + expected + " at " + where(), why);
        stage = Stage.WALK;
        return walk(world);
    }

    private Status walk(WorldView world) {
        String now = world.blockAt(target);
        if (!now.equals(expected)) return fail("the block changed while walking there: " + expected + " → " + now);
        if (clearing != null) {                                 // a vine or leaves in the line of sight: that first
            Status c = clearing.tick(world);
            if (c == Status.RUNNING) return Status.RUNNING;
            clearing = null;
            if (c == Status.FAILED) return fail("couldn't clear what's in the way of " + where());
        }
        if (!world.canReach(target)) {
            var blocker = world.inTheWay(target);
            if (blocker.isPresent() && soft(world.blockAt(blocker.get())) && cleared < MAX_CLEARS) {
                cleared++;
                if (walk != null) walk.cancel();
                walk = null;
                clearing = new BreakTask(hands, blocker.get(), "in the way of " + shortName(expected) + " at " + where(), log).withoutCollect();
                return Status.RUNNING;
            }
        }
        if (world.canReach(target)) {
            if (walk != null) walk.cancel();
            walk = null;
            stage = Stage.BREAK;
            return dig(world);
        }
        if (walk == null) {
            if (++walks > MAX_WALKS) return fail("out of reach — walked there " + MAX_WALKS + " times and still can't reach " + where());
            walk = new WalkTask(hands.paths(), target, false, WALK_WITHIN).dryOnly();
        }
        Status s = walk.tick(world);
        if (s == Status.FAILED) return fail("out of reach and no path to " + where() + " — " + walk.failure());
        if (s == Status.DONE) {
            walk = null;
            boolean softBlocker = cleared < MAX_CLEARS && world.inTheWay(target).map(p -> soft(world.blockAt(p))).orElse(false);
            if (!world.canReach(target) && !softBlocker) {
                String what = world.inTheWay(target).map(p -> " — " + shortName(world.blockAt(p)) + " in the way").orElse(" (too high, or too far)");
                return fail("out of reach — standing by " + where() + " but can't see it" + what);
            }
        }
        return Status.RUNNING;
    }

    private Status dig(WorldView world) {
        String now = world.blockAt(target);
        if (!now.equals(expected)) {
            letGo();
            if (breakTicks == 0 || !EMPTY.contains(now)) {   // gone before we dug: not ours
                return fail("the block changed: " + expected + " → " + now);
            }
            log.accept("broke " + expected + " at " + where(), "took " + String.format(java.util.Locale.ROOT, "%.1f", breakTicks / 20.0) + " s");
            if (!collect) return Status.DONE;
            stage = Stage.COLLECT;
            return collect(world);
        }
        if (!world.canReach(target)) {                      // pushed away, or something moved in between
            letGo();
            stage = Stage.WALK;
            return Status.RUNNING;
        }
        if (++breakTicks > BREAK_TICKS) {
            letGo();
            return fail("took too long — " + BREAK_TICKS / 20 + " s digging " + expected + " and it's still there");
        }
        if (!toolChosen) {
            toolChosen = true;
            before = counts(world);                          // before the drops, to see what we picked up
            int slot = world.bestToolSlot(target);
            if (slot >= 0 && slot != world.selectedSlot()) {
                hands.selectSlot(slot);
                log.accept("holding " + heldName(world, slot), "the best tool for " + expected);
                return Status.RUNNING;                       // the switch reaches the server next tick
            }
        }
        mining = hands.mine(target);
        return Status.RUNNING;
    }

    private Status collect(WorldView world) {
        Vec3 c = new Vec3(target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
        Vec3 me = world.position();
        List<DroppedItem> drops = world.droppedItems().stream()
                .filter(d -> dist(d.pos(), c) <= DROP_RADIUS)
                .sorted(Comparator.comparingDouble(d -> dist(d.pos(), me)))
                .toList();
        // no drops yet is not "no drops": wait for the item entity (or the pickup) to actually arrive, not a
        // guessed delay — lag and the network decide when it comes. Bot1 broke grass and logged "picked up
        // nothing" the same tick (2026-09-27). A block that drops nothing waits out COLLECT_TICKS (leaves:
        // tree felling should skip the collect per leaf).
        collectTicks++;
        if (drops.isEmpty()) return collectTicks > COLLECT_TICKS || picked(world) ? finish(world, "") : Status.RUNNING;
        if (collectTicks > COLLECT_TICKS) {
            return finish(world, " — left " + drops.size() + " item stack(s) after " + COLLECT_TICKS / 20 + " s");
        }
        DroppedItem next = drops.get(0);
        sinceGoal++;
        if (chasing == null || chasing.id() != next.id() || (!hands.paths().busy() && sinceGoal > 10)) {
            chasing = next;
            sinceGoal = 0;
            hands.paths().goTo(BlockPos.of(next.pos()), false, 0, false);
        }
        return Status.RUNNING;
    }

    /** Something new in the inventory already: the drops came and went (picked up standing still). */
    private boolean picked(WorldView world) {
        return counts(world).entrySet().stream().anyMatch(e -> e.getValue() > before.getOrDefault(e.getKey(), 0));
    }

    private Status finish(WorldView world, String note) {
        hands.paths().stop();
        Map<String, Integer> after = counts(world);
        StringBuilder got = new StringBuilder();
        for (var e : after.entrySet()) {
            int d = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (d > 0) got.append(got.isEmpty() ? "" : ", ").append(d).append(' ').append(shortName(e.getKey()));
        }
        if (got.isEmpty()) log.accept("picked up nothing", "no drops reached the inventory" + note);
        else log.accept("picked up " + got, "the drops of " + shortName(expected) + note);
        return Status.DONE;
    }

    private static Map<String, Integer> counts(WorldView world) {
        Map<String, Integer> m = new HashMap<>();
        for (ItemView i : world.inventory()) m.merge(i.id(), i.count(), Integer::sum);
        return m;
    }

    private static String heldName(WorldView world, int slot) {
        return world.inventory().stream().filter(i -> i.slot() == slot).map(i -> shortName(i.id())).findFirst().orElse("slot " + slot);
    }

    private static String shortName(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static double dist(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void letGo() {
        hands.stopMining();
        mining = false;
    }

    private Status fail(String why) {
        letGo();
        if (walk != null) walk.cancel();
        if (stage == Stage.COLLECT) hands.paths().stop();
        failure = why;
        return Status.FAILED;
    }

    @Override
    public void cancel() {
        if (clearing != null) clearing.cancel();
        letGo();
        if (walk != null) walk.cancel();
        if (stage == Stage.COLLECT) hands.paths().stop();
    }

    @Override public String failure() { return failure; }

    private String where() { return target.x() + " " + target.y() + " " + target.z(); }

    @Override
    public String describe() {
        String what = expected == null ? "the block" : shortName(expected);
        return switch (stage) {
            case START, WALK -> "walking to break " + what + " at " + where();
            case BREAK -> "breaking " + what + " at " + where();
            case COLLECT -> "picking up the drops of " + what;
        };
    }
}
