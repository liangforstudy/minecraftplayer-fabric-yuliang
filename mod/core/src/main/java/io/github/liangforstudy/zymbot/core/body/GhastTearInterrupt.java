package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.DroppedItem;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.SweepTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Anarchy only (doc/9b9t/roadmap.md, "Pick up ghast tears"; owner, 2026-10-29: "tears are rare on
 * 9b9t"). A ghast tear on the ground within {@link #RANGE} blocks: go and get it before 9b9t's
 * 2-minute despawn, sweeping up what lies around it too (a ghast also drops gunpowder). Decided by the
 * owner: only when nothing that shoots is in range and health is above critical + 4; otherwise the tear
 * is let go. Only where it can be stood next to: not in or over lava, water or fire, and on something
 * solid. Baritone walks there, and Zymbot's Baritone never paths through lava.
 */
public final class GhastTearInterrupt implements Interrupt {
    public static final String TEAR = "minecraft:ghast_tear";
    static final double RANGE = 32;
    /** Swept around the tear (its gunpowder lands close by). */
    static final double SWEEP_RADIUS = 6;
    /** A tear we went for and didn't get is tried again only after this long... */
    static final long RETRY_MILLIS = 60_000;
    /** ...and forgotten after this: it has despawned (2 minutes on 9b9t). */
    static final long FORGET_MILLIS = 150_000;

    private final ZymbotConfig config;
    private final Body body;
    private final LongSupplier clock;
    private final Map<Integer, Long> tried = new HashMap<>();   // item entity id → when we went for it

    public GhastTearInterrupt(ZymbotConfig config, Body body, LongSupplier clock) {
        this.config = config;
        this.body = body;
        this.clock = clock;
    }

    @Override public String name() { return "ghast tear"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead() || world.untouchable() || body.hiding()) return false;
        if (!Combat.shooters(world).isEmpty() || world.health() <= config.criticalFor() + 4) return false;
        return tear(world).isPresent();
    }

    /** The nearest tear worth fetching. */
    Optional<DroppedItem> tear(WorldView world) {
        long now = clock.getAsLong();
        tried.values().removeIf(at -> now - at > FORGET_MILLIS);
        return world.droppedItems().stream()
                .filter(d -> TEAR.equals(d.item()))
                .filter(d -> Combat.distance(d.pos(), world.position()) <= RANGE)
                .filter(d -> !tried.containsKey(d.id()) || now - tried.get(d.id()) > RETRY_MILLIS)
                .filter(d -> safeGround(world, d))
                .min(Comparator.comparingDouble(d -> Combat.distance(d.pos(), world.position())));
    }

    /** It lies where we can stand: not in lava, water or fire, on a solid block that isn't lava. */
    static boolean safeGround(WorldView world, DroppedItem d) {
        BlockPos at = BlockPos.of(d.pos());
        BlockPos under = new BlockPos(at.x(), at.y() - 1, at.z());
        String here = world.blockAt(at), below = world.blockAt(under);
        return !bad(here) && !bad(below) && world.sturdy(under);
    }

    private static boolean bad(String id) {
        return id.equals("unloaded") || id.contains("lava") || id.contains("water") || id.contains("fire");
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        Optional<DroppedItem> t = tear(world);
        if (t.isEmpty()) return Task.failed("walking to ghast tear", "it's gone");
        tried.put(t.get().id(), clock.getAsLong());                  // got or not, not again straight away
        return new SweepTask(hands, BlockPos.of(t.get().pos()), SWEEP_RADIUS, SweepTask.counts(world), "the ghast tear", body::note)
                .chaseFor(45).sweepFor(75).describedAs("walking to ghast tear");
    }

    @Override public String why(WorldView world) { return "treasure"; }
}
