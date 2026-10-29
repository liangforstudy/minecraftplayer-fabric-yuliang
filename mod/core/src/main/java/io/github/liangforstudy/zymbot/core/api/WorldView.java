package io.github.liangforstudy.zymbot.core.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The bot's senses: a read-only snapshot of the game for one tick. The Fabric adapter builds it
 * from Minecraft; tests build it from a scripted fake world. The brain can't tell which.
 */
public interface WorldView {
    UUID selfId();
    String selfName();
    Vec3 position();
    float health();          // 0..20
    int hunger();            // 0..20
    boolean isDead();
    long timeOfDay();        // 0..24000
    long day();              // world day number
    /**
     * Everyone in the tab list right now, by UUID. The same list for the host and every guest, so
     * "is that bot on my server?" is simply "is it in here?" — no address or world id to agree on.
     */
    Set<UUID> onlinePlayers();

    // ------------------------------------------------------------------ body (phase 1)

    default float saturation() { return 0; }
    /** Where the body faces, in degrees (Minecraft yaw; unbounded, wraps every 360). */
    default float yaw() { return 0; }
    default boolean inWater() { return false; }
    default boolean onGround() { return true; }
    default boolean sprinting() { return false; }
    /** Mid-bite (or mid-draw, mid-block): the use key is doing something. */
    default boolean usingItem() { return false; }
    /** Air left, in ticks (300 = 15 s when full). Only drops while the head is under water. */
    default int air() { return 300; }
    default int maxAir() { return 300; }
    /**
     * Water in this block, by its fluid, not its name: seagrass, kelp and a waterlogged grave are water too.
     * Seagrass on a dirt shelf passed as "dry land" and Bot1 spun there at 96 -34 (2026-09-30).
     */
    default boolean isWater(BlockPos p) { return blockAt(p).contains("water"); }
    /** Eyes under water: breathing is off. */
    default boolean headInWater() { return false; }
    /**
     * Nearest spot to stand on out of the water — a solid block with two free, dry blocks above —
     * within {@code radius} blocks. The block itself; you stand on top of it.
     */
    default Optional<BlockPos> nearestDryLand(int radius) { return Optional.empty(); }
    /** The world spawn, as the server sends it — the fallback meeting point (PHASE2.md P2-5). */
    default Optional<BlockPos> worldSpawn() { return Optional.empty(); }
    /** The nearest place to stand that {@code ok} accepts. */
    default Optional<BlockPos> nearestDryLand(int radius, java.util.function.Predicate<BlockPos> ok) {
        return nearestDryLand(radius).filter(ok);
    }
    /**
     * Where we last died ("" if never), as the server reports it with each respawn. A change means
     * we died — even when death and respawn were too quick to ever see ourselves dead.
     */
    default String lastDeath() { return ""; }
    /**
     * Respawns this client has seen in this world: the player object replaced in the same level, or
     * replaced after it was dead. A change means we respawned, even when neither the death screen nor
     * a new {@link #lastDeath()} ever showed.
     */
    default int lives() { return 0; }
    /**
     * Knocked out (civfabric "down but not out"): seconds until bleeding out, or -1 when up. Read
     * from the red "Bleeding Out" boss bar — see BOT_DESIGN → Knocked out.
     */
    default int downedSecondsLeft() { return -1; }
    /** Someone is reviving us (the green "Being Revived" boss bar). */
    default boolean beingRevived() { return false; }
    /** The hotbar slot in hand, 0–8. */
    default int selectedSlot() { return 0; }
    /** Every non-empty stack in slots 0–35. */
    default List<ItemView> inventory() { return List.of(); }
    /** Hurt within the last couple of seconds. */
    default Optional<Damage> recentDamage() { return Optional.empty(); }
    /** Players and mobs within ~32 blocks (hostiles and players ~64: a ghast fires from there, and on anarchy a player can), not including ourselves. */
    default List<EntityView> nearby() { return List.of(); }
    /**
     * Every other player the server sends us, however far: the server's own tracking limit, not
     * ours (owner, 2026-10-16). Who the chat check (roadmap3.md §4) can "meet in sight".
     */
    default List<EntityView> playersInView() {
        return nearby().stream().filter(e -> e.kind() == EntityView.Kind.PLAYER).toList();
    }
    /** Registry id of the block there ("minecraft:stone"), or "unloaded". */
    default String blockAt(BlockPos p) { return "unloaded"; }
    /** Nearest block with this registry id within {@code radius} blocks. */
    default Optional<BlockPos> findBlock(String id, int radius) { return Optional.empty(); }

    // ------------------------------------------------------------------ hands (phase 3)

    /** Armor points worn, 0–20 (the bar above the hearts): full iron is 15, full diamond 20. */
    default int armorPoints() { return 0; }
    /** The weapon has recharged: a hit now does full damage (the attack cooldown). */
    default boolean attackReady() { return true; }
    /** Nothing solid between these two points: a shooter at {@code from} could hit {@code to}. */
    default boolean clearShot(Vec3 from, Vec3 to) { return true; }

    /** Within reach (~4.5 blocks from the eyes) with a clear line of sight to the block. */
    default boolean canReach(BlockPos b) { return false; }
    /**
     * In reach of the block, but something else is the first thing the eyes' line to it hits (a vine,
     * leaves): that block. Empty when the block is in sight, or too far to tell.
     */
    default Optional<BlockPos> inTheWay(BlockPos b) { return Optional.empty(); }
    /** The hotbar slot whose item digs this block faster than a bare hand, the fastest; -1 if none. */
    default int bestToolSlot(BlockPos b) { return -1; }
    /** Items lying on the ground within ~32 blocks. */
    default List<DroppedItem> droppedItems() { return List.of(); }
    /** Whose civfabric grave is at {@code p}; empty if it isn't one, or its data hasn't reached us. */
    default Optional<GraveOwner> graveOwner(BlockPos p) { return Optional.empty(); }
    /**
     * A container screen (a chest, someone else's grave) is open: how many of its own slots — not
     * our inventory's — hold something. -1 when none is open.
     */
    default int openContainerFilled() { return -1; }
    /**
     * The open crafting screen: the inventory screen (2×2) or a crafting table (3×3), once its contents
     * have arrived; any other open container as {@link MenuView.Kind#OTHER}. Empty when no screen is open.
     */
    default Optional<MenuView> menu() { return Optional.empty(); }
    /**
     * A block can go on top of this one: its top face is solid, and clicking it doesn't open anything
     * (a crafting table, a chest) — what placing a block against it needs.
     */
    default boolean sturdy(BlockPos p) { return false; }
    /** Free for a block to go in: air, or something placing replaces (tall grass). Never water or lava. */
    default boolean replaceable(BlockPos p) { return false; }

    default Optional<EntityView> player(String name) {
        return nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.PLAYER && e.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /** The loaded ground around us, for route planning. {@link Terrain#NONE} if unknown. */
    default Terrain terrain() { return Terrain.NONE; }
    /** How fast the server is ticking, 0–20 (TpsMeter); 20 when unknown. */
    default double serverTps() { return 20; }

    /** Nearest known place with this tag ("bed", "village", ...). Empty until the map exists. */
    default Optional<BlockPos> nearest(String placeTag) { return Optional.empty(); }

    // ------------------------------------------------------------------ survey (phase 3)

    /** Registry id of the biome at our feet ("minecraft:plains"), or "unknown". */
    default String biome() { return "unknown"; }

    /**
     * Every loaded block within {@code radius} blocks across and {@code below}..{@code above} of our
     * feet whose registry id {@code wanted} accepts, at most {@code max} of them. Read now, on the
     * calling (game) thread: only the matching positions are copied, so the result is safe to search
     * from any other thread (the survey's grouping runs off the game thread, like the route planner).
     */
    default List<BlockHit> scanBlocks(int radius, int below, int above, int max,
                                      java.util.function.Predicate<String> wanted) {
        return List.of();
    }
}
