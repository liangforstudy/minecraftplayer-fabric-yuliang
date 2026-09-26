package dev.yuliang.zymbot.core.api;

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
    default boolean inWater() { return false; }
    default boolean onGround() { return true; }
    default boolean sprinting() { return false; }
    /** Mid-bite (or mid-draw, mid-block): the use key is doing something. */
    default boolean usingItem() { return false; }
    /** Air left, in ticks (300 = 15 s when full). Only drops while the head is under water. */
    default int air() { return 300; }
    default int maxAir() { return 300; }
    /** Eyes under water: breathing is off. */
    default boolean headInWater() { return false; }
    /**
     * Nearest spot to stand on out of the water — a solid block with two free, dry blocks above —
     * within {@code radius} blocks. The block itself; you stand on top of it.
     */
    default Optional<BlockPos> nearestDryLand(int radius) { return Optional.empty(); }
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
    /** Players and mobs within ~32 blocks, not including ourselves. */
    default List<EntityView> nearby() { return List.of(); }
    /** Registry id of the block there ("minecraft:stone"), or "unloaded". */
    default String blockAt(BlockPos p) { return "unloaded"; }
    /** Nearest block with this registry id within {@code radius} blocks. */
    default Optional<BlockPos> findBlock(String id, int radius) { return Optional.empty(); }

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
}
