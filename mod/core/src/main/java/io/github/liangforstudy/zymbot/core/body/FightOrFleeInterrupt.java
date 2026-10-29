package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.Fallback;
import io.github.liangforstudy.zymbot.core.task.HideTask;
import io.github.liangforstudy.zymbot.core.task.CoverTask;
import io.github.liangforstudy.zymbot.core.task.FightTask;
import io.github.liangforstudy.zymbot.core.task.RetreatTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.Optional;

/**
 * Anarchy only (doc/9b9t/roadmap.md, "Attacked mid-route"): a mob that hits us, or a walker or a
 * shooter close enough to ({@link #closing}), is fought or backed away from, by {@link Combat#decide};
 * a creeper is backed away from before it hits. Below critical
 * health {@link CriticalHealthInterrupt}, ahead of this one, heals or runs instead. After it, the
 * job it interrupted (a walk) carries on.
 */
public final class FightOrFleeInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;

    /** A walker this close (3D) is dealt with before it hits: waiting for the first hit let zombies walk right up (owner, 2026-10-29). */
    public static final double CLOSING_BLOCKS = 6;
    /** A ground shooter this close with a clear shot at us, likewise: a skeleton's aim range. */
    public static final double SHOOTER_BLOCKS = 16;

    public FightOrFleeInterrupt(ZymbotConfig config, Body body) {
        this.config = config;
        this.body = body;
    }

    @Override public String name() { return "fight-or-flee"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead() || world.health() <= config.criticalFor()) return false;
        return threat(world).isPresent() || unseenAttacker(world).isPresent() || creeper(world).isPresent();
    }

    /** What hit us, still in sight and close enough to hit again (a ghast from 64 blocks). */
    private Optional<EntityView> attacker(WorldView world) {
        return body.recentAttack().filter(a -> a.id() != null).flatMap(a -> world.nearby().stream()
                .filter(e -> a.id().equals(e.uuid()))
                .filter(e -> Combat.distance(e.pos(), world.position()) < Combat.reach(e.type()))
                .findFirst());
    }

    /** What hit us; else the nearest mob closing on us. */
    private Optional<EntityView> threat(WorldView world) {
        Optional<EntityView> a = attacker(world);
        return a.isPresent() ? a : closing(world);
    }

    /**
     * A hostile about to be on us, not yet having hit: a walker within {@link #CLOSING_BLOCKS}, or a
     * ground shooter within {@link #SHOOTER_BLOCKS} with a clear shot. Flyers (a ghast across the
     * Nether) and the ones we leave alone wait for a hit.
     */
    private Optional<EntityView> closing(WorldView world) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.HOSTILE)
                .filter(e -> switch (Combat.kind(e.type())) {
                    case MELEE, STRONG -> Combat.distance(e.pos(), me) <= CLOSING_BLOCKS;
                    case SHOOTER -> Combat.distance(e.pos(), me) <= SHOOTER_BLOCKS
                            && world.clearShot(eyes(e.pos()), eyes(me));
                    default -> false;
                })
                .min(Comparator.comparingDouble(e -> Combat.distance(e.pos(), me)));
    }

    private static Vec3 eyes(Vec3 feet) {
        return new Vec3(feet.x(), feet.y() + 1.5, feet.z());
    }

    /**
     * Hit by something the game didn't name (an arrow from the dark): where it came from, if still
     * close. A named attacker that's gone from sight is dead or far, so nothing to do.
     */
    private Optional<Body.Attack> unseenAttacker(WorldView world) {
        return body.recentAttack()
                .filter(a -> a.id() == null)
                .filter(a -> a.from().horizontalDistance(world.position()) < RetreatTask.DISTANCE);
    }

    private Optional<EntityView> creeper(WorldView world) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> Combat.kind(e.type()) == Combat.Kind.EXPLODES)
                .filter(e -> Combat.distance(e.pos(), me) <= Combat.CREEPER_BLOCKS)   // not one in a cave below
                .min(Comparator.comparingDouble(e -> Combat.distance(e.pos(), me)));
    }

    private Combat.Decision decision(WorldView world, EntityView mob) {
        return Combat.decide(mob.type(), Combat.gear(world), Math.max(1, Combat.crowd(world)),
                Combat.shooters(world).size(), world.health(), config.criticalFor());
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        Optional<EntityView> creeper = creeper(world);
        if (creeper.isPresent()) return retreat(hands, creeper.get().pos());
        Optional<EntityView> mob = threat(world);
        if (mob.isPresent()) {
            return switch (decision(world, mob.get()).action()) {
                case FIGHT -> new FightTask(hands, mob.get(), body::reportSwim,
                        w -> creeper(w).isEmpty() && threat(w).map(m -> decision(w, m).fight()).orElse(true));
                case COVER -> cover(world, hands, body, retreat(hands, mob.get().pos()));
                case BACK_OFF -> new Fallback(body::note, retreat(hands, mob.get().pos()),
                        new HideTask(hands, FightOrFleeInterrupt::stillAfterUs, body::hiding).noting(body::note));
            };
        }
        return unseenAttacker(world).map(a -> retreat(hands, a.from()))
                .orElseGet(() -> Task.failed("fight or flee", "nothing in sight"));
    }

    /**
     * Against a ghast or a blaze, cover only this close; further off, dig in first. Cover further out
     * cost three fireballs on open ground, 20 → 10.6, before it got under (2026-10-29).
     */
    public static final int FLYER_COVER_BLOCKS = 3;

    /**
     * Out of the shooters' sight: cover, else dig in, else {@code last}. With a flyer among them, cover
     * a step or two away, else dig in at once, and only then cover further out (doc/9b9t/roadmap.md,
     * "Before routes" 5).
     */
    public static Task cover(WorldView world, Hands hands, Body body, Task last) {
        HideTask hide = new HideTask(hands, FightOrFleeInterrupt::stillAfterUs, body::hiding).noting(body::note);
        CoverTask cover = new CoverTask(hands, Combat::shooters, body::reportSwim);
        if (Combat.shooters(world).stream().noneMatch(e -> Combat.kind(e.type()) == Combat.Kind.FLYER)) {
            return new Fallback(body::note, cover, hide, last);
        }
        return new Fallback(body::note, new CoverTask(hands, Combat::shooters, body::reportSwim).within(FLYER_COVER_BLOCKS),
                hide, cover, last);
    }

    /** Something is still after us: a shooter in range, or a hostile close. */
    public static boolean stillAfterUs(WorldView world) {
        return !Combat.shooters(world).isEmpty() || Combat.crowd(world) > 0;
    }

    private Task retreat(Hands hands, Vec3 first) {
        return new RetreatTask(hands.paths(), () -> body.recentAttack().map(Body.Attack::from).orElse(first),
                Optional.empty(), body::reportSwim);
    }

    /** When a totem or golden apples raised the gear a tier, what it's made of. */
    private static String raised(WorldView world) {
        return Combat.gear(world) != Combat.Gear.of(world.armorPoints()) ? " (" + Combat.gearWhy(world) + ")" : "";
    }

    @Override
    public String why(WorldView world) {
        Optional<EntityView> creeper = creeper(world);
        if (creeper.isPresent()) return "a creeper " + Math.round(Combat.distance(creeper.get().pos(), world.position()))
                + " blocks away";
        Optional<EntityView> mob = attacker(world);
        if (mob.isPresent()) return "hit by " + decision(world, mob.get()).why() + raised(world);
        Optional<EntityView> near = closing(world);
        if (near.isPresent()) return Math.round(Combat.distance(near.get().pos(), world.position())) + " blocks off: "
                + decision(world, near.get()).why() + raised(world);
        return "hit by something out of sight";
    }
}
