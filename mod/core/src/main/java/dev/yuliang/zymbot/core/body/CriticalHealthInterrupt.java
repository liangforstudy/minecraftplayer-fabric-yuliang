package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Interrupt;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.task.EatTask;
import dev.yuliang.zymbot.core.task.RetreatTask;
import dev.yuliang.zymbot.core.task.Task;
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

    public CriticalHealthInterrupt(ZymbotConfig config, Body body) {
        this.config = config;
        this.body = body;
    }

    @Override public String name() { return "critical-health"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead()) return false;
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
        var attack = attackerClose(world);
        if (attack.isPresent()) {
            Optional<EntityView> human = body.nearestTeammate(world);   // help is a teammate, not a stranger (P2-2)
            Vec3 first = attack.get().from();
            return new RetreatTask(hands.paths(), () -> body.recentAttack().map(Body.Attack::from).orElse(first),
                    human.map(EntityView::pos), body::reportSwim);
        }
        return Task.failed("recover", "no healing food, and nothing to run from");
    }

    @Override
    public String why(WorldView world) {
        String hurt = body.recentAttack().map(a -> ", hurt by " + (a.who() == null ? "something" : a.who())).orElse("");
        if (!critical(world)) return "hit" + hurt.replaceFirst("^, hurt", "") + " (danger: " + config.danger + ")";
        return "health " + Math.round(world.health()) + " ≤ " + config.criticalFor() + hurt;
    }
}
