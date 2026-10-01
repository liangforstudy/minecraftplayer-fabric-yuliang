package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.task.Task;
import dev.yuliang.zymbot.core.task.WalkTask;
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
        return touchesHazard(world, standingOn(world));
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        BlockPos here = standingOn(world);
        var spot = world.nearestDryLand(RADIUS, StrandedInterrupt.safeLand(body, world)
                .and(p -> !(p.x() == here.x() && p.z() == here.z()) && !touchesHazard(world, p)));
        if (spot.isEmpty()) return Task.failed("stepping off the hazard", "no safe spot within " + RADIUS + " blocks");
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
            for (int[] f : new int[][] { {1, 0}, {-1, 0}, {0, 1}, {0, -1} }) {
                String b = world.blockAt(new BlockPos(floor.x() + f[0], floor.y() + dy, floor.z() + f[1]));
                if (b.contains("cactus") || b.endsWith(":fire") || b.endsWith("soul_fire")) return true;
            }
        }
        return false;
    }

    static boolean hazard(String id) {
        return id.endsWith(":fire") || id.endsWith("soul_fire") || id.contains("cactus") || id.contains("magma_block")
                || id.endsWith("campfire") || id.contains("sweet_berry_bush") || id.contains("lava")
                || id.contains("powder_snow") || id.contains("wither_rose") || id.contains("pointed_dripstone");
    }
}
