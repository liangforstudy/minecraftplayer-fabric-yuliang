package dev.yuliang.zymbot.core.craft;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.MenuView;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.body.ChatIn;
import dev.yuliang.zymbot.core.task.Task;
import dev.yuliang.zymbot.core.task.WalkTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * Craft one recipe the way a player does (PHASE3.md step 3, decision 3): open the inventory screen
 * (2×2) or walk to a crafting table and open it (3×3), clear the grid, lay the items out with slot
 * clicks paced at human speed, wait for the server's result, shift-click it, and check that it stayed
 * in the inventory — civfabric takes a craft back when it would leave hunger below 4 ("You're too
 * hungry to craft"), and only the server knows. The screen is closed on every way out; closing gives
 * back anything left in the grid or on the cursor.
 * <p>
 * {@code times} crafts at once only for one-cell recipes (logs → planks): the stack goes in one cell
 * and the shift-click crafts them all. Everything else is crafted once.
 */
public final class CraftTask implements Task {
    /** Hunger below this: don't start. civfabric refuses a craft that would leave hunger below 4. */
    public static final int TOO_HUNGRY = 6;
    /** Waiting for a screen to open (the server answers a table click). */
    static final int OPEN_TICKS = 40;
    /** Tries at opening the screen before giving up. */
    static final int MAX_OPENS = 3;
    /** Waiting for the server's result after the grid is laid out. */
    static final int RESULT_TICKS = 40;
    /** After the shift-click: let the server confirm (or take it back) before counting. */
    static final int SETTLE_TICKS = 10;
    /** Clicks this many ticks apart, plus 0–1 more: 100–150 ms, a quick human. */
    static final int CLICK_TICKS = 2;
    static final int WALK_WITHIN = 2;

    private enum Stage { WALK, OPEN, CLEAR, FILL, RESULT, TAKE, SETTLE }

    private record Click(int slot, int button, boolean shift) {}

    private final Hands hands;
    private final Recipe recipe;
    private final BlockPos table;
    private final String why;
    private final BiConsumer<String, String> log;
    private final ChatIn chat;
    private final Random jitter = new Random();
    private int times;
    private Stage stage;
    private WalkTask walk;
    private boolean openAsked;
    private int opens;
    private int waited;
    private int wait;
    private final List<Click> clicks = new ArrayList<>();
    private int before;
    private ChatIn.Line lastHeard;
    private boolean screenOpen;
    private String failure = "";

    /**
     * @param table the crafting table to use, or null to craft in the inventory's 2×2 grid
     * @param chat  what the server says (a refusal), or null
     */
    public CraftTask(Hands hands, Recipe recipe, int times, BlockPos table, String why, ChatIn chat,
                     BiConsumer<String, String> log) {
        this.hands = hands;
        this.recipe = recipe;
        this.times = Math.max(1, recipe.cells().size() == 1 ? times : 1);
        this.table = table;
        this.why = why;
        this.chat = chat;
        this.log = log;
        this.stage = table == null ? Stage.OPEN : Stage.WALK;
        if (table == null && !recipe.fitsHand()) throw new IllegalArgumentException(recipe.name() + " needs a crafting table");
    }

    @Override
    public Status tick(WorldView world) {
        if (wait > 0) {
            wait--;
            return Status.RUNNING;
        }
        return switch (stage) {
            case WALK -> walk(world);
            case OPEN -> open(world);
            case CLEAR -> clear(world);
            case FILL -> fill(world);
            case RESULT -> result(world);
            case TAKE -> take(world);
            case SETTLE -> settle(world);
        };
    }

    // ------------------------------------------------------------------ getting to the grid

    private Status walk(WorldView world) {
        if (!world.blockAt(table).equals("minecraft:crafting_table")) {
            return fail("the crafting table at " + where(table) + " is gone (" + world.blockAt(table) + ")");
        }
        if (world.canReach(table)) {
            if (walk != null) walk.cancel();
            walk = null;
            stage = Stage.OPEN;
            return open(world);
        }
        if (walk == null) walk = new WalkTask(hands.paths(), table, false, WALK_WITHIN).dryOnly();
        Status s = walk.tick(world);
        if (s == Status.FAILED) return fail("can't get to the crafting table at " + where(table) + " — " + walk.failure());
        if (s == Status.DONE && !world.canReach(table)) {
            return fail("standing by the crafting table at " + where(table) + " but can't reach it");
        }
        return Status.RUNNING;
    }

    private Status open(WorldView world) {
        var menu = world.menu();
        MenuView.Kind want = table == null ? MenuView.Kind.INVENTORY : MenuView.Kind.CRAFTING_TABLE;
        if (menu.isPresent() && menu.get().kind() == want) {
            screenOpen = true;
            if (opens == 0 && !openAsked) {                    // already open (a human left it so): fine
                log.accept("using the open " + (table == null ? "inventory" : "crafting table"), why);
            }
            if (world.hunger() < TOO_HUNGRY) return fail("too hungry to craft (hunger " + world.hunger() + ") — eat first");
            stage = Stage.CLEAR;
            return clear(world);
        }
        if (menu.isPresent() && !openAsked) {                  // some other screen: close it first
            hands.closeContainer();
            pace();
            return Status.RUNNING;
        }
        if (!openAsked) {
            if (++opens > MAX_OPENS) return fail("the " + (table == null ? "inventory" : "crafting table") + " screen never opened");
            if (table == null) {
                hands.openInventory();
                openAsked = true;
            } else if (hands.interact(table)) {
                openAsked = true;
            } else if (++waited > OPEN_TICKS) {
                return fail("couldn't get the crosshair on the crafting table at " + where(table));
            }
            if (openAsked) waited = 0;
            return Status.RUNNING;
        }
        if (++waited > OPEN_TICKS) {                           // asked, nothing came: ask again
            openAsked = false;
            waited = 0;
        }
        return Status.RUNNING;
    }

    // ------------------------------------------------------------------ laying it out

    /** Anything already in the grid goes back to the inventory first, one shift-click at a time. */
    private Status clear(WorldView world) {
        MenuView m = menuOrNull(world);
        if (m == null) return reopen();
        if (!m.cursorEmpty()) {                                // left on the cursor: put it anywhere free
            var free = m.slots().stream().filter(s -> s.role() == MenuView.Role.PLAYER && s.empty()).findFirst();
            if (free.isEmpty()) return fail("the inventory is full — nowhere to put down " + Recipe.shortName(m.carried()));
            hands.click(free.get().index(), 0, false);
            pace();
            return Status.RUNNING;
        }
        var filled = m.grid().stream().filter(s -> !s.empty()).findFirst();
        if (filled.isPresent()) {
            hands.click(filled.get().index(), 0, true);
            pace();
            return Status.RUNNING;
        }
        String problem = plan(m);
        if (problem != null) return fail(problem);
        before = count(world, recipe.result());
        lastHeard = newest();
        stage = Stage.FILL;
        return fill(world);
    }

    /**
     * The clicks that lay the recipe out in the top-left of the grid: for each ingredient, pick up a
     * stack, right-click one into each cell that wants it (or drop the whole stack in, for a one-cell
     * recipe crafted that many times), and put the rest back where it came from. Null when planned.
     */
    private String plan(MenuView m) {
        clicks.clear();
        int gw = m.gridWidth();
        Map<Recipe.Ingredient, List<Integer>> cells = new LinkedHashMap<>();
        for (int i = 0; i < recipe.cells().size(); i++) {
            Recipe.Ingredient ing = recipe.cells().get(i);
            if (ing != null) cells.computeIfAbsent(ing, k -> new ArrayList<>()).add(recipe.gridCell(i, gw));
        }
        for (var e : cells.entrySet()) {
            Recipe.Ingredient ing = e.getKey();
            List<MenuView.Slot> sources = m.slots().stream()
                    .filter(s -> s.role() == MenuView.Role.PLAYER && ing.test(s.id()))
                    .sorted(Comparator.comparingInt(MenuView.Slot::count).reversed())
                    .toList();
            int have = sources.stream().mapToInt(MenuView.Slot::count).sum();
            if (recipe.cells().size() == 1) {                   // one cell, `times` of one kind: one stack
                if (sources.isEmpty()) return "no " + ing.name() + " to craft " + recipe.name() + " from";
                MenuView.Slot src = sources.get(0);
                times = Math.min(times, src.count());
                int cell = cellIndex(m, e.getValue().get(0));
                clicks.add(new Click(src.index(), 0, false));     // pick it all up
                if (times == src.count()) clicks.add(new Click(cell, 0, false));
                else {
                    for (int n = 0; n < times; n++) clicks.add(new Click(cell, 1, false));
                    clicks.add(new Click(src.index(), 0, false)); // the rest back
                }
                continue;
            }
            int need = e.getValue().size();
            if (have < need) return "not enough " + ing.name() + " for " + recipe.name() + " — have " + have + ", need " + need;
            int placed = 0;
            for (MenuView.Slot src : sources) {
                if (placed == need) break;
                clicks.add(new Click(src.index(), 0, false));
                int fromThis = Math.min(src.count(), need - placed);
                for (int n = 0; n < fromThis; n++) clicks.add(new Click(cellIndex(m, e.getValue().get(placed + n)), 1, false));
                placed += fromThis;
                if (fromThis < src.count()) clicks.add(new Click(src.index(), 0, false));
            }
        }
        return null;
    }

    private static int cellIndex(MenuView m, int cell) {
        return m.cell(cell).orElseThrow(() -> new IllegalStateException("no grid cell " + cell)).index();
    }

    private Status fill(WorldView world) {
        if (menuOrNull(world) == null) return reopen();
        if (clicks.isEmpty()) {
            stage = Stage.RESULT;
            waited = 0;
            return Status.RUNNING;
        }
        Click c = clicks.remove(0);
        hands.click(c.slot(), c.button(), c.shift());
        pace();
        return Status.RUNNING;
    }

    /** The server fills the result slot (the client doesn't): wait for it, and check it's ours. */
    private Status result(WorldView world) {
        MenuView m = menuOrNull(world);
        if (m == null) return reopen();
        var r = m.result().filter(s -> !s.empty());
        if (r.isEmpty()) {
            if (++waited > RESULT_TICKS) {
                return fail("laid out " + recipe.name() + ", but nothing showed in the result slot — the recipe may differ on this server");
            }
            return Status.RUNNING;
        }
        if (!recipe.result().test(r.get().id())) {
            return fail("laid out " + recipe.name() + ", but the result is " + Recipe.shortName(r.get().id()));
        }
        stage = Stage.TAKE;
        return take(world);
    }

    private Status take(WorldView world) {
        MenuView m = menuOrNull(world);
        if (m == null) return reopen();
        hands.click(m.result().orElseThrow().index(), 0, true);   // shift-click: all of them, into the inventory
        stage = Stage.SETTLE;
        waited = 0;
        return Status.RUNNING;
    }

    private Status settle(WorldView world) {
        if (++waited < SETTLE_TICKS) return Status.RUNNING;
        int got = count(world, recipe.result()) - before;
        if (got <= 0) {
            if (waited < RESULT_TICKS) return Status.RUNNING;
            String said = refusal();
            return fail("the craft didn't stick — " + (said != null ? "the server said \"" + said + "\""
                    : "nothing came out (hunger " + world.hunger() + "; civfabric takes back a craft that would leave hunger below 4)"));
        }
        close();
        log.accept("crafted " + got + " " + resultName(world), why);
        return Status.DONE;
    }

    // ------------------------------------------------------------------ helpers

    /** The screen closed under us (a human pressed Esc): start over from opening it. */
    private Status reopen() {
        screenOpen = false;
        clicks.clear();
        openAsked = false;
        waited = 0;
        stage = Stage.OPEN;
        return Status.RUNNING;
    }

    private MenuView menuOrNull(WorldView world) {
        MenuView.Kind want = table == null ? MenuView.Kind.INVENTORY : MenuView.Kind.CRAFTING_TABLE;
        return world.menu().filter(m -> m.kind() == want).orElse(null);
    }

    private void pace() { wait = CLICK_TICKS - 1 + jitter.nextInt(2); }

    private ChatIn.Line newest() {
        if (chat == null) return null;
        var lines = chat.recent();
        return lines.isEmpty() ? null : lines.get(lines.size() - 1);
    }

    /** A server line since we started that sounds like a refusal, or null. */
    private String refusal() {
        if (chat == null) return null;
        var lines = chat.recent();
        int from = lastHeard == null ? 0 : lines.indexOf(lastHeard) + 1;
        for (int i = Math.max(0, from); i < lines.size(); i++) {
            String t = lines.get(i).text();
            String low = t.toLowerCase(Locale.ROOT);
            if (lines.get(i).sender() == null && (low.contains("craft") || low.contains("hungry"))) return t;
        }
        return null;
    }

    private static int count(WorldView world, Recipe.Ingredient ing) {
        return world.inventory().stream().filter(i -> ing.test(i.id())).mapToInt(ItemView::count).sum();
    }

    private String resultName(WorldView world) {
        if (recipe.result().id() != null) return Recipe.shortName(recipe.result().id());
        return world.inventory().stream().filter(i -> recipe.result().test(i.id())).map(i -> Recipe.shortName(i.id()))
                .findFirst().orElse(recipe.name());
    }

    private void close() {
        if (screenOpen) hands.closeContainer();
        screenOpen = false;
    }

    private Status fail(String why) {
        if (walk != null) walk.cancel();
        close();
        failure = why;
        return Status.FAILED;
    }

    @Override
    public void cancel() {
        if (walk != null) walk.cancel();
        if (stage != Stage.WALK) {                             // a half-laid grid goes back with the screen
            hands.closeContainer();
            screenOpen = false;
        }
    }

    @Override public String failure() { return failure; }

    private static String where(BlockPos p) { return p.x() + " " + p.y() + " " + p.z(); }

    @Override
    public String describe() {
        String what = (times > 1 ? times + "× " : "") + recipe.name();
        return switch (stage) {
            case WALK -> "walking to the crafting table at " + where(table) + " to craft " + what;
            default -> "crafting " + what + (table == null ? " in the inventory grid" : " at the table at " + where(table));
        };
    }
}
