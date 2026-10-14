package dev.yuliang.zymbot.core;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Damage;
import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Food;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.PathProvider;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A scripted stand-in for Minecraft (FOUNDATION.md decision 10). Implements the same senses and
 * hands the Fabric adapter does, so the brain runs here in milliseconds with no game at all.
 */
public final class FakeWorld implements WorldView, Hands {
    static {
        dev.yuliang.zymbot.core.task.RouteTask.PLANNER = Runnable::run;   // plans finish at once in tests
        dev.yuliang.zymbot.core.survey.Surveyor.EXECUTOR = Runnable::run; // so do surveys
    }

    public String biome = "minecraft:plains";

    @Override public String biome() { return biome; }

    /** The scripted {@link #blocks}, filtered like the real scan (a cube around us, capped). */
    @Override
    public List<dev.yuliang.zymbot.core.api.BlockHit> scanBlocks(int radius, int below, int above, int max,
                                                                 java.util.function.Predicate<String> wanted) {
        BlockPos at = BlockPos.of(pos);
        return blocks.entrySet().stream()
                .filter(e -> Math.abs(e.getKey().x() - at.x()) <= radius && Math.abs(e.getKey().z() - at.z()) <= radius
                        && e.getKey().y() >= at.y() - below && e.getKey().y() <= at.y() + above && wanted.test(e.getValue()))
                .limit(max)
                .map(e -> new dev.yuliang.zymbot.core.api.BlockHit(e.getKey(), e.getValue()))
                .toList();
    }

    /** A straight trunk of {@code height} logs standing on (x, y, z); {@code huge}: a 2×2 trunk. */
    public FakeWorld tree(int x, int y, int z, int height, String log, boolean huge) {
        for (int h = 0; h < height; h++) {
            blocks.put(new BlockPos(x, y + h, z), log);
            if (huge) {
                blocks.put(new BlockPos(x + 1, y + h, z), log);
                blocks.put(new BlockPos(x, y + h, z + 1), log);
                blocks.put(new BlockPos(x + 1, y + h, z + 1), log);
            }
        }
        return this;
    }

    public final UUID id;
    public final String name;
    public Vec3 pos = new Vec3(0, 64, 0);
    public float health = 20;
    public int hunger = 20;
    public float saturation = 5;
    public float yaw;
    public boolean dead;
    public boolean inWater;
    public boolean sprinting;
    public boolean usingItem;
    public boolean onGround = true;
    public boolean headInWater;
    public int air = 300;
    public double tps = 20;
    public BlockPos dryLand;
    public BlockPos spawn;
    public boolean forwardHeld, jumpHeld, sneakHeld;
    public String lastDeath = "";
    public int lives;
    public final java.util.Map<BlockPos, String> blocks = new java.util.HashMap<>();
    public final List<BlockPos> used = new ArrayList<>();
    public dev.yuliang.zymbot.core.api.Terrain terrain = dev.yuliang.zymbot.core.api.Terrain.NONE;
    public int downed = -1;
    public boolean beingRevived;
    public int selected;
    public long time = 1000;
    public long day = 1;
    public int respawns;
    public final List<String> notices = new ArrayList<>();
    /** This player's tab list. Starts with just ourselves, like a real client. */
    public final Set<UUID> online = new HashSet<>();
    public final List<ItemView> inventory = new ArrayList<>();
    public final List<EntityView> nearby = new ArrayList<>();
    public Damage damage;
    public final FakePaths paths = new FakePaths();
    public final List<String> said = new ArrayList<>();
    public boolean useHeld;
    public Vec3 lookedAt;

    public FakeWorld(String name) {
        this.name = name;
        this.id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
        online.add(id);
    }

    /** Put these players on one server: each sees all of them in its tab list. */
    public static void sameServer(FakeWorld... worlds) {
        for (FakeWorld a : worlds) for (FakeWorld b : worlds) a.online.add(b.id);
    }

    public static Food food(int nutrition) { return new Food(nutrition, nutrition * 0.6f, false, false, false); }
    public static Food healing(int nutrition) { return new Food(nutrition, nutrition * 0.6f, true, false, true); }
    public static Food poison(int nutrition) { return new Food(nutrition, 0.1f, false, true, false); }

    public FakeWorld give(int slot, String id, int count, Food food) {
        inventory.removeIf(i -> i.slot() == slot);
        inventory.add(new ItemView(slot, id, count, food));
        return this;
    }

    public EntityView player(String name, double x, double z) {
        EntityView e = new EntityView(nearby.size() + 100, UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()),
                name, "minecraft:player", EntityView.Kind.PLAYER, new Vec3(x, 64, z));
        nearby.add(e);
        return e;
    }

    /** What the server does when a bite finishes: one item gone, hunger up. */
    public void finishBite() {
        ItemView held = inventory.stream().filter(i -> i.slot() == selected).findFirst().orElseThrow();
        inventory.remove(held);
        if (held.count() > 1) inventory.add(new ItemView(held.slot(), held.id(), held.count() - 1, held.food()));
        hunger = Math.min(20, hunger + held.food().nutrition());
        usingItem = false;
    }

    @Override public UUID selfId() { return id; }
    @Override public String selfName() { return name; }
    @Override public Vec3 position() { return pos; }
    @Override public float health() { return health; }
    @Override public int hunger() { return hunger; }
    @Override public float saturation() { return saturation; }
    @Override public float yaw() { return yaw; }
    @Override public boolean isDead() { return dead; }
    @Override public boolean inWater() { return inWater; }
    @Override public boolean sprinting() { return sprinting; }
    @Override public boolean usingItem() { return usingItem; }
    @Override public boolean onGround() { return onGround; }
    @Override public boolean headInWater() { return headInWater; }
    @Override public int air() { return air; }
    @Override public String lastDeath() { return lastDeath; }
    @Override public int lives() { return lives; }
    @Override public String blockAt(BlockPos p) {
        String b = blocks.get(p);
        if (b != null) return b;
        // dryLand is a shore: water straight beside it at its level, so a swimmer can climb out face-on
        // (SurfaceTask.faceOnShore) - unless a test sets those blocks itself
        if (dryLand != null && p.y() == dryLand.y()
                && Math.abs(p.x() - dryLand.x()) + Math.abs(p.z() - dryLand.z()) == 1) return "minecraft:water";
        return "minecraft:air";
    }
    @Override public Optional<BlockPos> findBlock(String id, int radius) {
        return blocks.entrySet().stream().filter(e -> e.getValue().equals(id)).map(java.util.Map.Entry::getKey).findFirst();
    }
    @Override public void useOn(BlockPos b) { used.add(b); }

    // ------------------------------------------------------------------ graves (PHASE3_FIXLIST #1)

    public final java.util.Map<BlockPos, dev.yuliang.zymbot.core.api.GraveOwner> graveOwners = new java.util.HashMap<>();
    /** The open container's filled slots; -1: no screen. Tests script what the server does. */
    public int containerFilled = -1;
    public int takeAlls, closes;

    @Override public Optional<dev.yuliang.zymbot.core.api.GraveOwner> graveOwner(BlockPos p) {
        return Optional.ofNullable(graveOwners.get(p));
    }
    @Override public int openContainerFilled() { return containerFilled; }
    @Override public void takeAllFromContainer() { takeAlls++; }
    @Override public void closeContainer() {
        if (containerFilled >= 0 || menuKind != null) closes++;
        containerFilled = -1;
        if (menuKind != null) {                                 // the grid and the cursor go back, as in the game
            for (int i = 0; i < grid.length; i++) if (grid[i] != null) { addToInventory(grid[i], gridCount[i]); grid[i] = null; gridCount[i] = 0; }
            if (cursor != null) addToInventory(cursor, cursorCount);
            cursor = null;
            cursorCount = 0;
            menuKind = null;
        }
    }

    // ------------------------------------------------------------------ crafting screens (phase 3)

    /** The open crafting screen, or null. Slots as the game numbers them: 0 result, then the grid, then the inventory. */
    public dev.yuliang.zymbot.core.api.MenuView.Kind menuKind;
    public final String[] grid = new String[9];
    public final int[] gridCount = new int[9];
    public String cursor;
    public int cursorCount;
    public int clicks;
    /** civfabric's "too hungry": the shift-click on the result does nothing, and this is said. */
    public boolean refuseCrafts;
    /** Where the server's lines go (the bot's ChatIn), if a test wants them. */
    public dev.yuliang.zymbot.core.body.ChatIn serverSays;
    /** Below this height (inclusive) unset blocks are solid ground — for placing. */
    public int groundY = 63;

    private int gridWidth() { return menuKind == null ? 0 : menuKind.gridWidth; }
    private int gridSize() { return gridWidth() * gridWidth(); }
    /** Screen slot → player inventory slot (0–35), or -1. */
    private int playerSlot(int index) {
        int first = 1 + gridSize();
        if (index < first || index >= first + 36) return -1;
        int i = index - first;                                   // 27 main first, then the hotbar
        return i < 27 ? i + 9 : i - 27;
    }

    @Override
    public Optional<dev.yuliang.zymbot.core.api.MenuView> menu() {
        if (menuKind == null) return Optional.empty();
        List<dev.yuliang.zymbot.core.api.MenuView.Slot> s = new ArrayList<>();
        var r = craftResult();
        s.add(new dev.yuliang.zymbot.core.api.MenuView.Slot(0, dev.yuliang.zymbot.core.api.MenuView.Role.RESULT, -1,
                r == null ? null : r.id(), r == null ? 0 : r.count()));
        for (int i = 0; i < gridSize(); i++) {
            s.add(new dev.yuliang.zymbot.core.api.MenuView.Slot(1 + i, dev.yuliang.zymbot.core.api.MenuView.Role.GRID, i, grid[i], gridCount[i]));
        }
        for (int idx = 1 + gridSize(); idx < 1 + gridSize() + 36; idx++) {
            int p = playerSlot(idx);
            ItemView it = stack(p);
            s.add(new dev.yuliang.zymbot.core.api.MenuView.Slot(idx, dev.yuliang.zymbot.core.api.MenuView.Role.PLAYER, p,
                    it == null ? null : it.id(), it == null ? 0 : it.count()));
        }
        return Optional.of(new dev.yuliang.zymbot.core.api.MenuView(menuKind, s, cursor, cursorCount));
    }

    private record Crafted(String id, int count, dev.yuliang.zymbot.core.craft.Recipe recipe) {}

    /** The recipe the grid holds, laid out from the top-left, as the server would see it. */
    private Crafted craftResult() {
        int gw = gridWidth();
        for (var r : dev.yuliang.zymbot.core.craft.Recipe.ALL) {
            if (r.width() > gw || r.height() > gw) continue;
            boolean ok = true;
            for (int cell = 0; cell < gridSize() && ok; cell++) {
                int col = cell % gw, row = cell / gw;
                var ing = col < r.width() && row < r.height() ? r.cells().get(row * r.width() + col) : null;
                ok = ing == null ? grid[cell] == null : ing.test(grid[cell]);
            }
            if (!ok) continue;
            String id = r.result().id() != null ? r.result().id()
                    : grid[0].replace("_log", "_planks").replace("_wood", "_planks");
            return new Crafted(id, r.makes(), r);
        }
        return null;
    }

    @Override public void openInventory() { if (menuKind == null) menuKind = dev.yuliang.zymbot.core.api.MenuView.Kind.INVENTORY; }

    @Override
    public boolean interact(BlockPos b) {
        if (!canReach(b)) return false;
        if (blockAt(b).equals("minecraft:crafting_table")) menuKind = dev.yuliang.zymbot.core.api.MenuView.Kind.CRAFTING_TABLE;
        return true;
    }

    @Override
    public void click(int slot, int button, boolean shift) {
        clicks++;
        if (slot == 0) {                                         // the result
            Crafted c = craftResult();
            if (c == null || !shift) return;
            if (refuseCrafts) {                                  // civfabric says so on the action bar
                if (serverSays != null) serverSays.heard(new dev.yuliang.zymbot.core.body.ChatIn.Line(null, "You're too hungry to craft", false, 0));
                return;
            }
            int times = Integer.MAX_VALUE;
            for (int i = 0; i < gridSize(); i++) if (grid[i] != null) times = Math.min(times, gridCount[i]);
            for (int i = 0; i < gridSize(); i++) {
                if (grid[i] == null) continue;
                gridCount[i] -= times;
                if (gridCount[i] == 0) grid[i] = null;
            }
            addToInventory(c.id(), c.count() * times);
            return;
        }
        boolean isGrid = slot >= 1 && slot <= gridSize();
        int p = playerSlot(slot);
        String id = isGrid ? grid[slot - 1] : stack(p) == null ? null : stack(p).id();
        int count = isGrid ? gridCount[slot - 1] : stack(p) == null ? 0 : stack(p).count();
        if (shift) {                                             // back into the inventory
            if (isGrid && id != null) {
                addToInventory(id, count);
                grid[slot - 1] = null;
                gridCount[slot - 1] = 0;
            }
            return;
        }
        String newId;
        int newCount;
        if (button == 0) {
            if (cursor == null) { cursor = id; cursorCount = count; newId = null; newCount = 0; }
            else if (id == null) { newId = cursor; newCount = cursorCount; cursor = null; cursorCount = 0; }
            else if (id.equals(cursor)) { newId = id; newCount = count + cursorCount; cursor = null; cursorCount = 0; }
            else { newId = cursor; newCount = cursorCount; cursor = id; cursorCount = count; }
        } else {
            if (cursor == null) {                                // take half
                if (id == null) return;
                int half = (count + 1) / 2;
                cursor = id; cursorCount = half;
                newId = count - half == 0 ? null : id; newCount = count - half;
            } else if (id == null || id.equals(cursor)) {        // put one down
                newId = cursor; newCount = count + 1;
                if (--cursorCount == 0) cursor = null;
            } else return;
        }
        if (isGrid) { grid[slot - 1] = newId; gridCount[slot - 1] = newCount; }
        else put(p, newId, newCount);
    }

    public ItemView stack(int slot) {
        return inventory.stream().filter(i -> i.slot() == slot).findFirst().orElse(null);
    }

    public void put(int slot, String id, int count) {
        inventory.removeIf(i -> i.slot() == slot);
        if (id != null && count > 0) inventory.add(new ItemView(slot, id, count, null));
    }

    /** As the game does: onto a stack of the same, then the first empty slot (hotbar first). */
    public void addToInventory(String id, int count) {
        for (ItemView i : List.copyOf(inventory)) {
            if (count == 0) return;
            if (!i.id().equals(id) || i.count() >= 64) continue;
            int add = Math.min(count, 64 - i.count());
            put(i.slot(), id, i.count() + add);
            count -= add;
        }
        for (int s = 0; s < 36 && count > 0; s++) {
            if (stack(s) != null) continue;
            int add = Math.min(count, 64);
            put(s, id, add);
            count -= add;
        }
    }

    @Override
    public boolean sturdy(BlockPos p) {
        String b = blocks.get(p);
        if (b == null) return p.y() <= groundY;
        return !b.equals("minecraft:air") && !b.equals("minecraft:water") && !b.equals("minecraft:crafting_table");
    }

    @Override
    public boolean replaceable(BlockPos p) {
        String b = blocks.get(p);
        return b == null ? p.y() > groundY : b.equals("minecraft:air") || b.equals("minecraft:short_grass");
    }

    public int placeClicks;

    @Override
    public boolean placeOn(BlockPos support) {
        placeClicks++;
        if (!canReach(support)) return false;
        ItemView held = stack(selected);
        BlockPos at = new BlockPos(support.x(), support.y() + 1, support.z());
        if (held != null && replaceable(at)) {
            blocks.put(at, held.id());
            put(selected, held.id(), held.count() - 1);
        }
        return true;
    }
    @Override public dev.yuliang.zymbot.core.api.Terrain terrain() { return terrain; }
    @Override public double serverTps() { return tps; }
    @Override public int downedSecondsLeft() { return downed; }
    @Override public boolean beingRevived() { return beingRevived; }
    @Override public Optional<BlockPos> nearestDryLand(int radius) { return Optional.ofNullable(dryLand); }
    @Override public Optional<BlockPos> worldSpawn() { return Optional.ofNullable(spawn); }
    @Override public void holdKeys(boolean forward, boolean jump) { forwardHeld = forward; jumpHeld = jump; }
    @Override public void holdSneak(boolean down) { sneakHeld = down; }
    @Override public int selectedSlot() { return selected; }
    @Override public List<ItemView> inventory() { return List.copyOf(inventory); }
    @Override public Optional<Damage> recentDamage() { return Optional.ofNullable(damage); }
    @Override public List<EntityView> nearby() { return List.copyOf(nearby); }
    @Override public long timeOfDay() { return time; }
    @Override public long day() { return day; }
    @Override public Set<UUID> onlinePlayers() { return online; }

    // ------------------------------------------------------------------ digging (phase 3)

    public final List<dev.yuliang.zymbot.core.api.DroppedItem> drops = new ArrayList<>();
    /** Blocks that can't be seen from anywhere (behind a wall). */
    public final Set<BlockPos> hidden = new HashSet<>();
    /** Ticks of {@link #mine} a block takes; the fake ignores tools. */
    public int digTicks = 3;
    /** The block being dug right now, null when attack isn't held. */
    public BlockPos mining;
    public int dug;
    public int stopMiningCalls;

    /** Block → what stands in the line of sight to it (a vine), while that is still there. */
    public final java.util.Map<BlockPos, BlockPos> blockers = new java.util.HashMap<>();

    @Override public Optional<BlockPos> inTheWay(BlockPos b) {
        BlockPos in = blockers.get(b);
        return in == null || blockAt(in).equals("minecraft:air") ? Optional.empty() : Optional.of(in);
    }

    @Override public boolean canReach(BlockPos b) {
        if (hidden.contains(b) || inTheWay(b).isPresent()) return false;
        double dx = b.x() + 0.5 - pos.x(), dy = b.y() + 0.5 - (pos.y() + 1.62), dz = b.z() + 0.5 - pos.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= 4.5;
    }
    @Override public int bestToolSlot(BlockPos b) {
        if (!blockAt(b).endsWith("_log")) return -1;
        return inventory.stream().filter(i -> i.inHotbar() && i.id().endsWith("_axe")).mapToInt(ItemView::slot).findFirst().orElse(-1);
    }
    @Override public List<dev.yuliang.zymbot.core.api.DroppedItem> droppedItems() { return List.copyOf(drops); }

    @Override
    public boolean mine(BlockPos b) {
        if (!b.equals(mining)) dug = 0;
        mining = b;
        if (++dug >= digTicks) {
            String id = blockAt(b);
            blocks.put(b, "minecraft:air");
            drops.add(new dev.yuliang.zymbot.core.api.DroppedItem(500 + drops.size(), id, 1,
                    new Vec3(b.x() + 0.5, b.y(), b.z() + 0.5)));
            mining = null;
        }
        return true;
    }

    @Override public void stopMining() { mining = null; dug = 0; stopMiningCalls++; }

    /** What the client does walking over drops: everything within a block goes into the inventory. */
    public void pickUpNear() {
        for (var d : List.copyOf(drops)) {
            if (d.pos().horizontalDistance(pos) > 1.0) continue;
            drops.remove(d);
            addToInventory(d.item(), d.count());                // onto the same stack, as the game does
        }
    }

    @Override public void respawn() { respawns++; dead = false; health = 20; }
    @Override public void notifyLocal(String message) { notices.add(message); }
    @Override public PathProvider paths() { return paths; }
    @Override public void selectSlot(int hotbarSlot) { selected = hotbarSlot; }
    @Override public void lookAt(Vec3 point) { lookedAt = point; }
    @Override public void chat(String message) { said.add(message); }
    @Override public void command(String command) { said.add("/" + command); }

    @Override
    public void swapToHotbar(int inventorySlot, int hotbarSlot) {
        ItemView from = inventory.stream().filter(i -> i.slot() == inventorySlot).findFirst().orElseThrow();
        Optional<ItemView> to = inventory.stream().filter(i -> i.slot() == hotbarSlot).findFirst();
        inventory.remove(from);
        to.ifPresent(inventory::remove);
        inventory.add(new ItemView(hotbarSlot, from.id(), from.count(), from.food()));
        to.ifPresent(t -> inventory.add(new ItemView(inventorySlot, t.id(), t.count(), t.food())));
    }

    @Override
    public void holdUse(boolean down) {
        useHeld = down;
        if (down && !usingItem && inventory.stream().anyMatch(i -> i.slot() == selected && i.edible())) usingItem = true;
        if (!down) usingItem = false;
    }

    /** A pathfinder that walks instantly when told to {@link #arrive()}. */
    public final class FakePaths implements PathProvider {
        public boolean available = true;
        public boolean busy;
        public BlockPos goal;
        public String following;
        public int stops;
        public boolean swim;
        /** Pretend no dry path exists: a dry request gives up at once. */
        public boolean onlyWet;

        public String name() { return available ? "fake" : "none — install Baritone to walk"; }
        public boolean available() { return available; }
        public boolean sprint;

        public void goTo(BlockPos target, boolean ignoreY, int within, boolean swim, boolean sprint) {
            goal = target;
            this.swim = swim;
            this.sprint = sprint;
            busy = swim || !onlyWet;
        }
        public void follow(String playerName, boolean swim) { following = playerName; this.swim = swim; busy = true; }
        public void stop() { busy = false; following = null; stops++; }
        public boolean busy() { return busy; }

        public void arrive() {
            pos = new Vec3(goal.x() + 0.5, goal.y(), goal.z() + 0.5);
            busy = false;
        }
    }
}
