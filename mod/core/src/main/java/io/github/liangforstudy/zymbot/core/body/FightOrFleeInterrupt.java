package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.FightTask;
import io.github.liangforstudy.zymbot.core.task.RetreatTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.Optional;

/**
 * Anarchy only (doc/9b9t/roadmap.md, "Attacked mid-route"): a mob that hits us is fought or backed
 * away from, by {@link Combat#decide}; a creeper is backed away from before it hits. Below critical
 * health {@link CriticalHealthInterrupt}, ahead of this one, heals or runs instead. After it, the
 * job it interrupted (a walk) carries on.
 */
public final class FightOrFleeInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;

    public FightOrFleeInterrupt(ZymbotConfig config, Body body) {
        this.config = config;
        this.body = body;
    }

    @Override public String name() { return "fight-or-flee"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead() || world.health() <= config.criticalFor()) return false;
        return attacker(world).isPresent() || unseenAttacker(world).isPresent() || creeper(world).isPresent();
    }

    /** What hit us, still in sight and close. */
    private Optional<EntityView> attacker(WorldView world) {
        return body.recentAttack().filter(a -> a.id() != null).flatMap(a -> world.nearby().stream()
                .filter(e -> a.id().equals(e.uuid()))
                .filter(e -> e.pos().horizontalDistance(world.position()) < RetreatTask.DISTANCE)
                .findFirst());
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
        return Combat.decide(mob.type(), Combat.Gear.of(world.armorPoints()), Math.max(1, Combat.crowd(world)),
                world.health(), config.criticalFor());
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        Optional<EntityView> creeper = creeper(world);
        if (creeper.isPresent()) return retreat(hands, creeper.get().pos());
        Optional<EntityView> mob = attacker(world);
        if (mob.isPresent()) {
            if (decision(world, mob.get()).fight()) return new FightTask(hands, mob.get(), body::reportSwim);
            return retreat(hands, mob.get().pos());
        }
        return unseenAttacker(world).map(a -> retreat(hands, a.from()))
                .orElseGet(() -> Task.failed("fight or flee", "nothing in sight"));
    }

    private Task retreat(Hands hands, Vec3 first) {
        return new RetreatTask(hands.paths(), () -> body.recentAttack().map(Body.Attack::from).orElse(first),
                Optional.empty(), body::reportSwim);
    }

    @Override
    public String why(WorldView world) {
        Optional<EntityView> creeper = creeper(world);
        if (creeper.isPresent()) return "a creeper " + Math.round(Combat.distance(creeper.get().pos(), world.position()))
                + " blocks away";
        Optional<EntityView> mob = attacker(world);
        if (mob.isPresent()) return "hit by " + decision(world, mob.get()).why();
        return "hit by something out of sight";
    }
}
