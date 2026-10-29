package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ProjectileView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.task.DeflectTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.Optional;

/**
 * Anarchy only (doc/9b9t/roadmap.md, "Incoming projectiles"): a ghast's fireball on its way to us is
 * hit back at the ghast ({@link DeflectTask}). One return kills a ghast; three fireballs on open ground
 * cost 9.4 health in full iron before the bot got under (2026-10-29). Sidestepping doesn't help: the
 * blast reaches. Not while dug in: a hole is out of the ghast's sight.
 */
public final class IncomingInterrupt implements Interrupt {
    public static final String FIREBALL = "minecraft:fireball";
    /** Watched this far off: a fireball covers ~1 block a tick, so ~1 s to turn and wait. */
    static final double WATCH_BLOCKS = 32;
    /** Its straight path passes this close to our middle: it hits, or its blast does. */
    static final double MISS_BY = 2.5;

    private final Body body;

    public IncomingInterrupt(Body body) {
        this.body = body;
    }

    @Override public String name() { return "incoming"; }

    @Override
    public boolean triggered(WorldView world) {
        return !world.isDead() && !body.hiding() && incoming(world).isPresent();
    }

    /** The nearest ghast fireball whose path comes at us. */
    public static Optional<ProjectileView> incoming(WorldView world) {
        Vec3 me = middle(world.position());
        return world.projectiles().stream()
                .filter(p -> FIREBALL.equals(p.type()))
                .filter(p -> Combat.distance(p.pos(), me) <= WATCH_BLOCKS)
                .filter(p -> comingAt(p, me))
                .min(Comparator.comparingDouble(p -> Combat.distance(p.pos(), me)));
    }

    /** Flying toward {@code at}, and its straight path passes within {@link #MISS_BY}. */
    public static boolean comingAt(ProjectileView p, Vec3 at) {
        Vec3 v = p.velocity();
        double vv = v.x() * v.x() + v.y() * v.y() + v.z() * v.z();
        if (vv < 1e-4) return false;                                  // not moving yet: no direction to judge
        double rx = at.x() - p.pos().x(), ry = at.y() - p.pos().y(), rz = at.z() - p.pos().z();
        double t = (rx * v.x() + ry * v.y() + rz * v.z()) / vv;       // ticks to its closest point to us
        if (t <= 0) return false;                                     // going away
        double cx = rx - v.x() * t, cy = ry - v.y() * t, cz = rz - v.z() * t;
        return Math.sqrt(cx * cx + cy * cy + cz * cz) <= MISS_BY;
    }

    /** Our middle, from where we stand. */
    public static Vec3 middle(Vec3 feet) {
        return new Vec3(feet.x(), feet.y() + 0.9, feet.z());
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        return incoming(world).<Task>map(p -> new DeflectTask(hands, p, body::note))
                .orElseGet(() -> Task.failed("hitting a fireball back", "nothing coming"));
    }

    @Override
    public String why(WorldView world) {
        return incoming(world).map(p -> "a fireball " + Math.round(Combat.distance(p.pos(), middle(world.position())))
                + " blocks off, coming at us — hit it back").orElse("a fireball coming");
    }
}
