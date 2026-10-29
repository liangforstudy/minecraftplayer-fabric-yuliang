package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.task.Task;
import io.github.liangforstudy.zymbot.core.task.WalkTask;
import java.util.Set;

/**
 * Hurt by the block it's standing in or next to — fire, cactus, magma, a berry bush: step off it. These
 * hits have no attacker, so the retreat (which runs *from* someone) never fired: the owner lit a fire
 * under Bot1 and it burned where it stood, same for a cactus (2026-10-01).
 * <p>
 * Fires only while it is both being hurt by a block and still touching a hazard, so it stops as soon
 * as it's clear (the client keeps the last damage source ~2 s).
 */
public final class HazardInterrupt implements Interrupt {
    /** Damage types that come from a block. */
    static final Set<String> BLOCK_DAMAGE = Set.of("minecraft:in_fire", "minecraft:cactus", "minecraft:hot_floor",
            "minecraft:sweet_berry_bush", "minecraft:lava", "minecraft:freeze", "minecraft:stalagmite",
            "minecraft:wither", "minecraft:on_fire");
    /** How far it looks for a safe spot. */
    static final int RADIUS = 5;
    /**
     * Then this far: a ghast's fireballs set the netherrack around Bluetails alight, nothing within 5
     * was clear, and it burned where it stood (Nether, 2026-10-29). Netherrack fire never goes out.
     */
    static final int FAR_RADIUS = 12;

    private final Body body;

    public HazardInterrupt(Body body) {
        this.body = body;
    }

    @Override public String name() { return "hazard"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead()) return false;
        var d = world.recentDamage();
        if (d.isEmpty() || d.get().attackerPos() != null || !BLOCK_DAMAGE.contains(d.get().type())) return false;
        // digging in from a ghast: its fireballs light the ground all round, and stepping off the fire
        // kept restarting the dig (2026-10-29); two more seconds of digging and we're under it. Lava still counts.
        if (body.hiding() && (d.get().type().endsWith("in_fire") || d.get().type().endsWith("on_fire"))) return false;
        boolean fire = d.get().type().endsWith("in_fire") || d.get().type().endsWith("on_fire");
        // in water a fire hit is old news unless a fire block is still in the body: the burning reflex had
        // just poured its bucket and this stepped it off, so the water was never scooped back (2026-10-29)
        if (world.inWater() && fire) return bodyInHazard(world, world.position());
        // burned with fire close round or overhead: a burning canopy keeps relighting us, so move on — the
        // pour-and-scoop looped ~50 times in 18 s under one, 20 → 10, never stepping away (2026-10-29)
        return touchesHazard(world, standingOn(world)) || bodyInHazard(world, world.position())
                || (fire && nearFire(world, standingOn(world)));
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        BlockPos here = standingOn(world);
        var safe = StrandedInterrupt.safeLand(body, world)
                .and(p -> !(p.x() == here.x() && p.z() == here.z()) && !touchesHazard(world, p) && !nearFire(world, p));
        var spot = world.nearestDryLand(RADIUS, safe);
        if (spot.isEmpty()) spot = world.nearestDryLand(FAR_RADIUS, safe);
        if (spot.isEmpty()) return Task.failed("stepping off the hazard", "no safe spot within " + FAR_RADIUS + " blocks");
        BlockPos s = spot.get();
        Task walk = new WalkTask(hands.paths(), new BlockPos(s.x(), s.y() + 1, s.z()), false, 0);
        return new Task() {
            public Status tick(WorldView w) { return walk.tick(w); }
            public void cancel() { walk.cancel(); }
            public String failure() { return walk.failure(); }
            public String describe() { return "stepping off the hazard to " + s.x() + " " + s.y() + " " + s.z(); }
        };
    }

    @Override
    public String why(WorldView world) {
        return world.recentDamage().map(d -> "hurt by " + d.type().replace("minecraft:", "")).orElse("hurt by a block");
    }

    /** The block under the feet. */
    static BlockPos standingOn(WorldView world) {
        Vec3 p = world.position();
        return new BlockPos((int) Math.floor(p.x()), (int) Math.floor(p.y() - 0.01) , (int) Math.floor(p.z()));
    }

    /** Standing on {@code floor}: a hazard under it, at the feet or head, or a cactus beside the feet or head. */
    static boolean touchesHazard(WorldView world, BlockPos floor) {
        if (hazard(world.blockAt(floor))) return true;
        for (int dy = 1; dy <= 2; dy++) {
            if (hazard(world.blockAt(new BlockPos(floor.x(), floor.y() + dy, floor.z())))) return true;
            for (int[] f : AROUND) {
                String b = world.blockAt(new BlockPos(floor.x() + f[0], floor.y() + dy, floor.z() + f[1]));
                if (b.contains("cactus") || b.endsWith(":fire") || b.endsWith("soul_fire")) return true;
            }
        }
        return false;
    }

    /**
     * All 8 round it: the body is 0.6 wide, so standing near a corner it overlaps the block diagonally
     * across — it stepped off a fire, stopped at 24.8 −52.2, and burned there with the fire at 25 −52
     * (2026-10-29).
     */
    private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /**
     * Fire this close to the side, or this far overhead, is too close to stand by: it spreads. Overhead
     * as high as a tree's leaves: at 5 it stopped under a canopy burning 5–7 up and was lit again
     * (2026-10-29).
     */
    static final int FIRE_SIDE = 3, FIRE_ABOVE = 8;

    /** Fire within {@link #FIRE_SIDE} of standing on {@code floor}, from the feet to {@link #FIRE_ABOVE} up. */
    static boolean nearFire(WorldView world, BlockPos floor) {
        for (int dx = -FIRE_SIDE; dx <= FIRE_SIDE; dx++)
            for (int dz = -FIRE_SIDE; dz <= FIRE_SIDE; dz++)
                for (int dy = 1; dy <= FIRE_ABOVE; dy++) {
                    String b = world.blockAt(new BlockPos(floor.x() + dx, floor.y() + dy, floor.z() + dz));
                    if (b.endsWith(":fire") || b.endsWith("soul_fire")) return true;
                }
        return false;
    }

    /** Half the body's width, and its height. */
    static final double HALF_WIDTH = 0.3, HEIGHT = 1.8;

    /** A hazard in any block the body itself is in (from where it really stands, not the block's middle). */
    static boolean bodyInHazard(WorldView world, Vec3 p) {
        for (int x = (int) Math.floor(p.x() - HALF_WIDTH); x <= (int) Math.floor(p.x() + HALF_WIDTH); x++)
            for (int z = (int) Math.floor(p.z() - HALF_WIDTH); z <= (int) Math.floor(p.z() + HALF_WIDTH); z++)
                for (int y = (int) Math.floor(p.y()); y <= (int) Math.floor(p.y() + HEIGHT); y++)
                    if (hazard(world.blockAt(new BlockPos(x, y, z)))) return true;
        return false;
    }

    static boolean hazard(String id) {
        return id.endsWith(":fire") || id.endsWith("soul_fire") || id.contains("cactus") || id.contains("magma_block")
                || id.endsWith("campfire") || id.contains("sweet_berry_bush") || id.contains("lava")
                || id.contains("powder_snow") || id.contains("wither_rose") || id.contains("pointed_dripstone");
    }
}
