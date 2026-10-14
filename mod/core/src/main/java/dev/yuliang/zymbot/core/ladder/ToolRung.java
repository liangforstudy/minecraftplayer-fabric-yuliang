package dev.yuliang.zymbot.core.ladder;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.body.ChatIn;
import dev.yuliang.zymbot.core.craft.CraftTask;
import dev.yuliang.zymbot.core.craft.PlaceTask;
import dev.yuliang.zymbot.core.craft.Recipe;
import dev.yuliang.zymbot.core.survey.Survey;
import dev.yuliang.zymbot.core.survey.SurveyCatalog.Kind;
import dev.yuliang.zymbot.core.task.BreakTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * M0 {@code HAS_TOOL} — the classless bootstrap (PHASE3.md §3, BOT_BEHAVIOUR → Bootstrap): a wooden
 * axe and a wooden hoe in the inventory. Each call to {@link #next} reads the inventory afresh and
 * picks the one step that's missing, so a half-done bootstrap (logs already cut, a table already
 * placed) picks up where it is — and so does one a human helped along:
 * <ol>
 *   <li>punch logs from the nearest natural tree until there's wood enough for the rest</li>
 *   <li>logs → planks (2×2)</li>
 *   <li>planks → a crafting table (2×2) and place it — unless a table is already near (decision E:
 *       a player's table found by the survey is used, not doubled)</li>
 *   <li>planks → sticks (2×2)</li>
 *   <li>a wooden axe, then a wooden hoe (3×3, at the table)</li>
 * </ol>
 * Trees: whole trees of ≥ {@link #MIN_TREE_LOGS} logs within {@link #WORK_RADIUS} of the nearest
 * teammate (of itself, with none in sight), small before huge, never stripped logs — a lone log or a
 * stripped one is a player's build (decision E). A tree that fails (out of reach, no path) is left
 * alone for {@link #SKIP_TREE_MILLIS}.
 */
public final class ToolRung implements Rung {
    public static final String AXE = "minecraft:wooden_axe", HOE = "minecraft:wooden_hoe", TABLE = "minecraft:crafting_table";
    /** Forage/wood radius around the nearest teammate (PHASE3.md §4, decision 5). */
    public static final int WORK_RADIUS = 48;
    /** A crafting table this close counts as ours to use. */
    public static final int TABLE_RADIUS = 24;
    /** Fewer logs than this isn't a tree, it's somebody's build. */
    public static final int MIN_TREE_LOGS = 3;
    /** How far up a trunk from its base the bot looks for the next log it can punch. */
    static final int TRUNK_LOOK = 6;
    public static final long SKIP_TREE_MILLIS = 5 * 60_000;
    /** A step that failed (a craft, the placing) waits this long before it's tried again. */
    public static final long RETRY_STEP_MILLIS = 20_000;
    /** Out of known trees: look around again at most this often. */
    static final long RESURVEY_MILLIS = 60_000;
    static final int STEPS = 5;

    private final Supplier<Optional<Survey>> survey;
    private final Consumer<String> askSurvey;
    private final Function<WorldView, Optional<Vec3>> anchor;
    private final ChatIn chat;
    private final LongSupplier clock;
    private final BiConsumer<String, String> log;
    private final Map<BlockPos, Long> skipTrees = new HashMap<>();
    private final Map<String, Long> retryAt = new HashMap<>();
    private BlockPos ourTable;                                  // the one we placed
    private long lastResurvey = Long.MIN_VALUE / 2;
    private String waiting = "";

    /**
     * @param anchor    the nearest teammate's position, if one is in sight — the work radius is around them
     * @param askSurvey asks the bot to look around again (why)
     */
    public ToolRung(Supplier<Optional<Survey>> survey, Consumer<String> askSurvey, Function<WorldView, Optional<Vec3>> anchor,
                    ChatIn chat, LongSupplier clock, BiConsumer<String, String> log) {
        this.survey = survey;
        this.askSurvey = askSurvey;
        this.anchor = anchor;
        this.chat = chat;
        this.clock = clock;
        this.log = log;
    }

    @Override public String id() { return "M0"; }
    @Override public String name() { return "HAS_TOOL"; }
    @Override public String waiting() { return waiting; }

    @Override
    public boolean met(WorldView world) {
        return count(world, AXE) > 0 && count(world, HOE) > 0;
    }

    /** What the bootstrap still needs, from what's in the inventory now. */
    record Needs(int logs, int planks, int sticks, boolean axe, boolean hoe, boolean tableItem, BlockPos table,
                 int sticksNeeded, int stickCrafts, boolean tableNeeded, int planksNeeded, int logsNeeded) {

        static Needs of(WorldView world, BlockPos table) {
            int logs = count(world, Recipe.LOG), planks = count(world, Recipe.PLANKS), sticks = count(world, Recipe.STICK);
            boolean axe = count(world, AXE) > 0, hoe = count(world, HOE) > 0, tableItem = count(world, TABLE) > 0;
            int sticksNeeded = (axe ? 0 : 2) + (hoe ? 0 : 2);
            int stickCrafts = (Math.max(0, sticksNeeded - sticks) + 3) / 4;
            boolean tableNeeded = table == null && !tableItem;
            int planksNeeded = (axe ? 0 : 3) + (hoe ? 0 : 2) + (tableNeeded ? 4 : 0) + 2 * stickCrafts;
            int logsNeeded = (Math.max(0, planksNeeded - planks) + 3) / 4;
            return new Needs(logs, planks, sticks, axe, hoe, tableItem, table, sticksNeeded, stickCrafts, tableNeeded, planksNeeded, logsNeeded);
        }

        String inventory() {
            List<String> have = new ArrayList<>();
            if (logs > 0) have.add(logs + " logs");
            if (planks > 0) have.add(planks + " planks");
            if (sticks > 0) have.add(sticks + " sticks");
            if (tableItem) have.add("a crafting table");
            if (axe) have.add("the axe");
            if (hoe) have.add("the hoe");
            return have.isEmpty() ? "nothing" : String.join(", ", have);
        }
    }

    @Override
    public Optional<Step> next(WorldView world) {
        Needs n = Needs.of(world, nearestTable(world));
        String tools = !n.axe() && !n.hoe() ? "no axe or hoe" : !n.axe() ? "no axe" : "no hoe";
        String have = tools + "; have " + n.inventory();

        if (n.planks() < n.planksNeeded() && n.logs() < n.logsNeeded()) {
            return gatherWood(world, n, have);
        }
        if (n.planks() < n.planksNeeded()) {
            int times = Math.min(n.logsNeeded(), largestStack(world, Recipe.LOG));
            return step(2, "craft planks", "planks from " + times + " log" + (times == 1 ? "" : "s"),
                    have + " — need " + n.planksNeeded() + " planks",
                    (w, h) -> new CraftTask(h, Recipe.PLANKS_FROM_LOG, times, null, why(2, "planks"), chat, log));
        }
        if (n.tableNeeded()) {
            return step(3, "craft table", "craft a crafting table", have + " — no crafting table within " + TABLE_RADIUS + " blocks",
                    (w, h) -> new CraftTask(h, Recipe.CRAFTING_TABLE, 1, null, why(3, "a table to craft tools at"), chat, log));
        }
        if (n.table() == null) {
            return step(3, "place table", "place the crafting table", have + " — no crafting table within " + TABLE_RADIUS + " blocks",
                    (w, h) -> {
                        PlaceTask t = new PlaceTask(h, TABLE, TABLE, why(3, "the axe and hoe need a 3×3 grid"), log);
                        return remembering(t);
                    });
        }
        if (n.sticks() < n.sticksNeeded()) {
            return step(4, "craft sticks", "craft sticks", have + " — need " + n.sticksNeeded() + " sticks",
                    (w, h) -> new CraftTask(h, Recipe.STICKS, 1, null, why(4, "sticks for the tools"), chat, log));
        }
        BlockPos table = n.table();
        if (!n.axe()) {
            return step(5, "craft axe", "craft a wooden axe at the table", have,
                    (w, h) -> new CraftTask(h, Recipe.WOODEN_AXE, 1, table, why(5, "the axe first: a whole tree per break later"), chat, log));
        }
        return step(5, "craft hoe", "craft a wooden hoe at the table", have,
                (w, h) -> new CraftTask(h, Recipe.WOODEN_HOE, 1, table, why(5, "no hoe, no harvest"), chat, log));
    }

    private Optional<Step> step(int number, String key, String what, String why,
                                java.util.function.BiFunction<WorldView, dev.yuliang.zymbot.core.api.Hands, dev.yuliang.zymbot.core.task.Task> task) {
        long until = retryAt.getOrDefault(key, 0L);
        long now = clock.getAsLong();
        if (until > now) {
            waiting = what + " failed — trying again in " + Math.max(1, (until - now) / 1000) + " s";
            return Optional.empty();
        }
        waiting = "";
        return Optional.of(new Step(number, STEPS, key, what, why, task));
    }

    private static String why(int step, String what) { return "M0 step " + step + "/" + STEPS + ": " + what; }

    // ------------------------------------------------------------------ wood

    private Optional<Step> gatherWood(WorldView world, Needs n, String have) {
        Optional<Survey> s = survey.get();
        if (s.isEmpty()) {
            waiting = "no survey yet";
            return Optional.empty();
        }
        long now = clock.getAsLong();
        skipTrees.values().removeIf(until -> until <= now);
        Vec3 me = world.position();
        Vec3 centre = anchor.apply(world).orElse(me);
        List<Survey.Group> trees = s.get().all(Kind.LOG).stream()
                .filter(t -> t.blocks() >= MIN_TREE_LOGS && !t.id().contains("stripped_"))
                .filter(t -> !skipTrees.containsKey(t.pos()))
                .filter(t -> t.pos().center().horizontalDistance(centre) <= WORK_RADIUS)
                .sorted(Comparator.comparing(Survey.Group::huge)            // small trees first (decision 4)
                        .thenComparingDouble(t -> t.pos().center().horizontalDistance(me)))
                .toList();
        for (Survey.Group t : trees) {
            Optional<BlockPos> log = lowestLog(world, t.pos());
            if (log.isEmpty()) {                               // cut down already (by us, or anyone)
                skipTrees.put(t.pos(), now + SKIP_TREE_MILLIS);
                continue;
            }
            BlockPos l = log.get();
            String id = world.blockAt(l);
            String key = "log " + t.pos().x() + " " + t.pos().y() + " " + t.pos().z();
            return step(1, key, "punch a log", have + " — need " + n.logsNeeded() + " logs for "
                            + n.planksNeeded() + " planks; " + Recipe.shortName(id) + " tree at " + t.pos().x() + " " + t.pos().z()
                            + (t.huge() ? " (huge — nothing smaller in range)" : ""),
                    (w, h) -> new BreakTask(h, l, why(1, "wood for the bootstrap"), this.log));
        }
        if (!s.get().all(Kind.LOG).isEmpty() && now - lastResurvey >= RESURVEY_MILLIS) {
            lastResurvey = now;                                // used up what it knew: look again
            askSurvey.accept("looking for trees");
        }
        waiting = "no tree to punch within " + WORK_RADIUS + " blocks" + (anchor.apply(world).isPresent() ? " of the team" : "");
        return Optional.empty();
    }

    /** The lowest log still standing in the trunk's column, from its base up. */
    static Optional<BlockPos> lowestLog(WorldView world, BlockPos base) {
        for (int dy = 0; dy <= TRUNK_LOOK; dy++) {
            BlockPos p = new BlockPos(base.x(), base.y() + dy, base.z());
            if (Recipe.LOG.test(world.blockAt(p))) return Optional.of(p);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ the table

    /** Placing a table, and remembering where it went — the survey won't know until it looks again. */
    public dev.yuliang.zymbot.core.task.Task remembering(PlaceTask t) {
        return new dev.yuliang.zymbot.core.task.Task() {
            public Status tick(WorldView world) {
                Status s = t.tick(world);
                if (s == Status.DONE) tableAt(t.placed());
                return s;
            }
            public void cancel() { t.cancel(); }
            public String failure() { return t.failure(); }
            public String describe() { return t.describe(); }
        };
    }

    /** A table we put down some other way: use it like our own. */
    public void tableAt(BlockPos p) { if (p != null) ourTable = p; }

    /** The nearest crafting table within {@link #TABLE_RADIUS}: ours, or one the survey found. Checked, not assumed. */
    public BlockPos nearestTable(WorldView world) {
        List<BlockPos> candidates = new ArrayList<>();
        if (ourTable != null) candidates.add(ourTable);
        survey.get().ifPresent(s -> s.all(Kind.CRAFTING_TABLE).forEach(g -> candidates.add(g.pos())));
        Vec3 me = world.position();
        return candidates.stream()
                .filter(p -> p.center().horizontalDistance(me) <= TABLE_RADIUS && Math.abs(p.y() - me.y()) <= 8)
                .filter(p -> world.blockAt(p).equals(TABLE))
                .min(Comparator.comparingDouble(p -> p.center().horizontalDistance(me)))
                .orElse(null);
    }

    @Override
    public void finished(Step step, boolean done, String failure) {
        if (done) {
            retryAt.remove(step.key());
            return;
        }
        long now = clock.getAsLong();
        if (step.key().startsWith("log ")) {                   // that tree: leave it a while, try another
            String[] p = step.key().split(" ");
            skipTrees.put(new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])), now + SKIP_TREE_MILLIS);
        } else {
            retryAt.put(step.key(), now + RETRY_STEP_MILLIS);
        }
    }

    // ------------------------------------------------------------------ counting

    static int count(WorldView world, String id) {
        return world.inventory().stream().filter(i -> i.id().equals(id)).mapToInt(ItemView::count).sum();
    }

    static int count(WorldView world, Recipe.Ingredient ing) {
        return world.inventory().stream().filter(i -> ing.test(i.id())).mapToInt(ItemView::count).sum();
    }

    static int largestStack(WorldView world, Recipe.Ingredient ing) {
        return world.inventory().stream().filter(i -> ing.test(i.id())).mapToInt(ItemView::count).max().orElse(0);
    }
}
