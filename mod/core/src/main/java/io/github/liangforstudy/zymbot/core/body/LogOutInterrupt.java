package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.Damage;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Anarchy only, the last line (doc/9b9t/roadmap.md, "Before routes" 1; decided, owner, 2026-10-29):
 * still being hurt at the log-out line, leave the server. Fighting, cover, running and digging in
 * come first (critical health is 12); this is for when none of them stopped it. Lower lines with
 * something that still saves us: a totem in the offhand pops once more, and logging out keeps it.
 * Meteor's Auto Reconnect is turned off first, or it would put us straight back.
 */
public final class LogOutInterrupt implements Interrupt {
    public static final String TOTEM = "minecraft:totem_of_undying";
    /** A totem in the offhand. */
    public static final int WITH_TOTEM = 4;
    /** Golden apples (or other healing food) carried, no totem. */
    public static final int WITH_HEALING = 6;
    /** Neither. */
    public static final int WITH_NOTHING = 8;

    private final ZymbotConfig config;
    private final Body body;

    public LogOutInterrupt(ZymbotConfig config, Body body) {
        this.config = config;
        this.body = body;
    }

    @Override public String name() { return "log-out"; }

    /** The health at or below which a hit logs us out, by what we carry. */
    public int line(WorldView world) {
        if (TOTEM.equals(world.offhandItem())) return WITH_TOTEM;
        if (FoodChooser.best(world, body.recentFoods(), config.neverEat, true).isPresent()) return WITH_HEALING;
        return WITH_NOTHING;
    }

    @Override
    public boolean triggered(WorldView world) {
        return !world.isDead() && world.recentDamage().isPresent() && world.health() <= line(world);
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        String why = why(world);
        return new Task() {
            private boolean done;

            @Override public Status tick(WorldView w) {
                if (!done) {
                    done = true;
                    hands.logOut("Zymbot logged out: " + why);
                }
                return Status.DONE;
            }

            @Override public void cancel() {}
            @Override public String failure() { return ""; }
            @Override public String describe() { return "logging out"; }
        };
    }

    /** Other hostiles this close are listed too, the nearest {@link #LISTED} of them. */
    static final double AROUND = 24;
    static final int LISTED = 3;

    /**
     * Why, and where everything is from where we face: on logging back in we face the same way, and
     * "6 ahead, 3 left" says which way to turn where "at 120 70 −3400" doesn't (owner, 2026-10-29).
     * The coordinates come last, for the map.
     */
    @Override
    public String why(WorldView world) {
        Vec3 me = world.position();
        float yaw = world.yaw();
        Optional<Damage> hit = world.recentDamage();
        Optional<EntityView> attacker = hit.filter(d -> d.attackerId() != null)
                .flatMap(d -> world.nearby().stream().filter(e -> d.attackerId().equals(e.uuid())).findFirst());
        String by = hit.map(d -> d.attacker() != null ? d.attacker() : d.type().replaceFirst("^minecraft:", "")).orElse("something");
        Vec3 from = attacker.map(EntityView::pos).orElse(hit.map(Damage::attackerPos).orElse(null));
        if (from != null) by += " (" + Relative.of(me, yaw, from) + ")";
        String carry = TOTEM.equals(world.offhandItem()) ? "a totem in the offhand"
                : line(world) == WITH_HEALING ? "healing food but no totem" : "no totem, no healing food";
        String others = world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.HOSTILE && attacker.map(a -> !a.uuid().equals(e.uuid())).orElse(true))
                .filter(e -> Combat.distance(e.pos(), me) <= AROUND)
                .sorted(Comparator.comparingDouble(e -> Combat.distance(e.pos(), me)))
                .limit(LISTED)
                .map(e -> e.name() + " (" + Relative.of(me, yaw, e.pos()) + ")")
                .collect(Collectors.joining(", "));
        return "health " + Math.round(world.health() * 10) / 10.0 + " ≤ " + line(world) + ", hurt by " + by
                + (others.isEmpty() ? "" : "; also " + others) + "; " + carry
                + "; facing " + Relative.facing(yaw) + " at " + Math.round(me.x()) + " " + Math.round(me.y()) + " " + Math.round(me.z());
    }
}
