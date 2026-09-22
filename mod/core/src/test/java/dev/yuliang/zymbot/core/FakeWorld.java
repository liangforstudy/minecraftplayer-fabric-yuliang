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
    }

    public final UUID id;
    public final String name;
    public Vec3 pos = new Vec3(0, 64, 0);
    public float health = 20;
    public int hunger = 20;
    public float saturation = 5;
    public boolean dead;
    public boolean inWater;
    public boolean sprinting;
    public boolean usingItem;
    public boolean onGround = true;
    public boolean headInWater;
    public int air = 300;
    public BlockPos dryLand;
    public boolean forwardHeld, jumpHeld;
    public String lastDeath = "";
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
    @Override public boolean isDead() { return dead; }
    @Override public boolean inWater() { return inWater; }
    @Override public boolean sprinting() { return sprinting; }
    @Override public boolean usingItem() { return usingItem; }
    @Override public boolean onGround() { return onGround; }
    @Override public boolean headInWater() { return headInWater; }
    @Override public int air() { return air; }
    @Override public String lastDeath() { return lastDeath; }
    @Override public String blockAt(BlockPos p) { return blocks.getOrDefault(p, "minecraft:air"); }
    @Override public Optional<BlockPos> findBlock(String id, int radius) {
        return blocks.entrySet().stream().filter(e -> e.getValue().equals(id)).map(java.util.Map.Entry::getKey).findFirst();
    }
    @Override public void useOn(BlockPos b) { used.add(b); }
    @Override public dev.yuliang.zymbot.core.api.Terrain terrain() { return terrain; }
    @Override public int downedSecondsLeft() { return downed; }
    @Override public boolean beingRevived() { return beingRevived; }
    @Override public Optional<BlockPos> nearestDryLand(int radius) { return Optional.ofNullable(dryLand); }
    @Override public void holdKeys(boolean forward, boolean jump) { forwardHeld = forward; jumpHeld = jump; }
    @Override public int selectedSlot() { return selected; }
    @Override public List<ItemView> inventory() { return List.copyOf(inventory); }
    @Override public Optional<Damage> recentDamage() { return Optional.ofNullable(damage); }
    @Override public List<EntityView> nearby() { return List.copyOf(nearby); }
    @Override public long timeOfDay() { return time; }
    @Override public long day() { return day; }
    @Override public Set<UUID> onlinePlayers() { return online; }

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
