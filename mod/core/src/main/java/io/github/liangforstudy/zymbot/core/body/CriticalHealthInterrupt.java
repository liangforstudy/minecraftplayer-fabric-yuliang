package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.Fallback;
import io.github.liangforstudy.zymbot.core.task.HideTask;
import io.github.liangforstudy.zymbot.core.task.EatTask;
import io.github.liangforstudy.zymbot.core.task.RetreatTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Optional;

/**
 * Interrupt #1 — health at or below critical (for the {@code danger} mode), or — in "modpack" mode —
 * any hit from a mob still close by. Natural regeneration is off on this server, so damage never
 * fixes itself: at critical health eat something that heals; otherwise get away from whatever is
 * hurting us, toward a human, following where the attacker is now.
 */
public final class CriticalHealthInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;

    private final boolean coverFromShooters;

    public CriticalHealthInterrupt(ZymbotConfig config, Body body) {
        this(config, body, false);
    }

    /**
     * @param coverFromShooters anarchy: with shooters in range, take cover rather than run — running
     *                          at critical health from four skeletons just took more arrows (2026-10-29)
     */
    public CriticalHealthInterrupt(ZymbotConfig config, Body body, boolean coverFromShooters) {
        this.config = config;
        this.body = body;
        this.coverFromShooters = coverFromShooters;
    }

    @Override public String name() { return "critical-health"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead()) return false;
        if (body.hiding() && FoodChooser.best(world, body.recentFoods(), config.neverEat, true).isEmpty()) return false;
        return critical(world) || (config.runsAtFirstHit() && attackerClose(world).isPresent());
    }

    private boolean critical(WorldView world) { return world.health() <= config.criticalFor(); }

    private Optional<Body.Attack> attackerClose(WorldView world) {
        return body.recentAttack()
                .filter(a -> a.from().horizontalDistance(world.position()) < RetreatTask.DISTANCE);   // not already clear
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        if (critical(world)) {
            var heal = FoodChooser.best(world, body.recentFoods(), config.neverEat, true);
            if (heal.isPresent()) return new EatTask(hands, heal.get(), body::ate);
        }
        Task run = Task.failed("recover", "no healing food, and nothing to run from");
        var attack = attackerClose(world);
        if (attack.isPresent()) {
            Optional<EntityView> human = body.nearestTeammate(world);   // help is a teammate, not a stranger (P2-2)
            Vec3 first = attack.get().from();
            run = new RetreatTask(hands.paths(), () -> body.recentAttack().map(Body.Attack::from).orElse(first),
                    human.map(EntityView::pos), body::reportSwim);
        }
        if (!coverFromShooters) return run;
        if (!Combat.shooters(world).isEmpty()) return FightOrFleeInterrupt.cover(world, hands, body, run);
        HideTask hide = new HideTask(hands, FightOrFleeInterrupt::stillAfterUs, body::hiding).noting(body::note);
        return attack.isPresent() ? new Fallback(body::note, run, hide) : run;   // no healing food: run, and dig in if that fails
    }

    @Override
    public String why(WorldView world) {
        String hurt = body.recentAttack().map(a -> ", hurt by " + (a.who() == null ? "something" : a.who())).orElse("");
        if (!critical(world)) return "hit" + hurt.replaceFirst("^, hurt", "") + " (danger: " + config.danger + ")";
        return "health " + Math.round(world.health()) + " ≤ " + config.criticalFor() + hurt;
    }
}
