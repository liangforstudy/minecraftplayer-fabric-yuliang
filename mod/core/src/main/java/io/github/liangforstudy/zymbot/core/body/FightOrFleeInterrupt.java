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
import io.github.liangforstudy.zymbot.core.task.CreeperFightTask;
import io.github.liangforstudy.zymbot.core.task.FightTask;
import io.github.liangforstudy.zymbot.core.task.RetreatTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.Optional;

/**
 * Anarchy only (doc/9b9t/roadmap.md, "Attacked mid-route"): a mob that hits us, or a walker or a
 * shooter close enough to ({@link #closing}), is fought or backed away from, by {@link Combat#decide};
 * a creeper on its own is fought, hit and step back (any other is backed away from). Below critical
 * health {@link CriticalHealthInterrupt}, ahead of this one, heals or runs instead. After it, the
 * job it interrupted (a walk) carries on.
 */
public final class FightOrFleeInterrupt implements Interrupt {
    private final ZymbotConfig config;
    private final Body body;
    /** The server we're on, or "singleplayer": the mob rules are kept per server (owner, 2026-11-17). */
    private final java.util.function.Supplier<String> server;
    private final java.util.function.LongSupplier clock;
    private CreeperFightTask.Tally creeperTally;
    /** Alert mobs pinged about, waiting for the owner's say-so: uuid → what and where, as pinged. */
    private final java.util.Map<java.util.UUID, String> pending = new java.util.LinkedHashMap<>();
    /** Alert mobs the owner said to fight ({@code /zbot approve}): treated as any other mob. */
    private final java.util.Set<java.util.UUID> approved = new java.util.HashSet<>();
    private String lastSaid = "";
    private long lastSaidAt = Long.MIN_VALUE / 2;
    /** The same reason isn't said again this soon; a new one always is. */
    static final long SAY_AGAIN_MILLIS = 10_000;

    /** A walker this close (3D) is dealt with before it hits: waiting for the first hit let zombies walk right up (owner, 2026-10-29). */
    public static final double CLOSING_BLOCKS = 6;
    /** A ground shooter this close with a clear shot at us, likewise: a skeleton's aim range. */
    public static final double SHOOTER_BLOCKS = 16;

    public FightOrFleeInterrupt(ZymbotConfig config, Body body) {
        this(config, body, () -> null, System::currentTimeMillis);
    }

    public FightOrFleeInterrupt(ZymbotConfig config, Body body, java.util.function.Supplier<String> server,
                                java.util.function.LongSupplier clock) {
        this.config = config;
        this.body = body;
        this.server = server;
        this.clock = clock;
    }

    private ZymbotConfig.MobRule rule(EntityView e) {
        return config.mobRule(server.get(), e.type());
    }

    /** Neither set to ignore here, nor an alert mob still waiting for the owner. */
    private boolean handled(EntityView e) {
        ZymbotConfig.MobRule r = rule(e);
        return r == ZymbotConfig.MobRule.NORMAL || r == ZymbotConfig.MobRule.ALERT && approved.contains(e.uuid());
    }

    /** Counted at all: anything but the ones set to ignore. */
    private boolean seen(EntityView e) {
        return rule(e) != ZymbotConfig.MobRule.IGNORE;
    }

    /**
     * An alert mob not yet approved that hit us, or is near: a walker or creeper within
     * {@link #CLOSING_BLOCKS}, a shooter within its reach.
     */
    private Optional<EntityView> alert(WorldView world) {
        Vec3 me = world.position();
        Optional<java.util.UUID> hitBy = body.recentAttack().map(Body.Attack::id);
        return world.nearby().stream()
                .filter(e -> e.uuid() != null && rule(e) == ZymbotConfig.MobRule.ALERT && !approved.contains(e.uuid()))
                .filter(e -> hitBy.map(id -> id.equals(e.uuid())).orElse(false)
                        || Combat.distance(e.pos(), me) <= Math.max(CLOSING_BLOCKS, switch (Combat.kind(e.type())) {
                            case SHOOTER -> SHOOTER_BLOCKS;
                            case FLYER -> Combat.reach(e.type());
                            default -> CLOSING_BLOCKS;
                        }))
                .min(Comparator.comparingDouble(e -> Combat.distance(e.pos(), me)));
    }

    /**
     * {@code /zbot approve}: fight the alert mobs pinged about. Returns what was approved.
     */
    public java.util.List<String> approve() {
        if (pending.isEmpty()) return java.util.List.of("nothing waiting for approval");
        java.util.List<String> out = new java.util.ArrayList<>();
        pending.forEach((id, what) -> { approved.add(id); out.add("approved: " + what + " — fighting it as any other mob"); });
        pending.clear();
        return out;
    }

    /** The alert mobs waiting for approval, as pinged. */
    public java.util.Collection<String> waiting() { return pending.values(); }

    @Override public String name() { return "fight-or-flee"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead() || world.health() <= config.criticalFor()) return false;
        return threat(world).isPresent() || unseenAttacker(world).isPresent() || creeper(world).isPresent()
                || alert(world).isPresent();
    }

    /** What hit us, still in sight and close enough to hit again (a ghast from 64 blocks). */
    private Optional<EntityView> attacker(WorldView world) {
        return body.recentAttack().filter(a -> a.id() != null).flatMap(a -> world.nearby().stream()
                .filter(e -> a.id().equals(e.uuid()))
                .filter(this::handled)
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
                .filter(this::handled)
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
                .filter(a -> a.id() == null)                .filter(a -> a.from().horizontalDistance(world.position()) < RetreatTask.DISTANCE);
    }

    /**
     * The nearest creeper within {@link Combat#CREEPER_BLOCKS} (3D: not one in a cave below), or, with
     * a bow and arrows, one within {@link CreeperFightTask#BOW_RANGE} we can see to shoot.
     */
    private Optional<EntityView> creeper(WorldView world) {
        Vec3 me = world.position();
        boolean bow = Combat.bow(world.inventory()).isPresent() && Combat.arrows(world.inventory());
        return world.nearby().stream()
                .filter(e -> Combat.kind(e.type()) == Combat.Kind.EXPLODES)
                .filter(this::handled)
                .filter(e -> Combat.distance(e.pos(), me) <= Combat.CREEPER_BLOCKS
                        || bow && Combat.distance(e.pos(), me) <= CreeperFightTask.BOW_RANGE && world.clearShot(eyes(me), eyes(e.pos())))
                .min(Comparator.comparingDouble(e -> Combat.distance(e.pos(), me)));
    }

    /**
     * A creeper on its own is fought, hit and step back ({@link CreeperFightTask}), with armor, a
     * weapon in the hotbar and dry ground; a charged one (twice the blast), one with company, or one
     * met in water is kept away from.
     */
    Combat.Decision creeperDecision(WorldView world, EntityView creeper) {
        String name = creeper.type() == null ? "creeper" : creeper.type().replaceFirst("^minecraft:", "");
        if (world.fuse(creeper.id()).map(io.github.liangforstudy.zymbot.core.api.Fuse::charged).orElse(false)) {
            return new Combat.Decision(Combat.Action.BACK_OFF, "charged " + name + " — keep away", "a charged " + name);
        }
        if (Combat.bestWeapon(world.inventory()).isEmpty()) return new Combat.Decision(Combat.Action.BACK_OFF, name + ", no weapon — keep away",
                Combat.a(name) + " and no weapon in the hotbar");
        if (world.inWater()) return new Combat.Decision(Combat.Action.BACK_OFF, name + ", in the water — keep away",
                Combat.a(name) + " while we're in water");
        Combat.Decision d = decision(world, creeper);
        if (d.fight() && Combat.bow(world.inventory()).isPresent() && Combat.arrows(world.inventory())) {
            return new Combat.Decision(d.action(), d.why().replace("hit it and step back", "shoot it, then hit it and step back"), d.cause());
        }
        return d;
    }

    private Combat.Decision decision(WorldView world, EntityView mob) {
        int crowd = Combat.kind(mob.type()) == Combat.Kind.PROVOKED ? Combat.provokedCrowd(world, mob, this::seen)
                : Combat.crowd(world, this::seen);
        return Combat.decide(mob.type(), Combat.gear(world), Math.max(1, crowd),
                (int) Combat.shooters(world).stream().filter(this::seen).count(), world.health(), config.criticalFor());
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        Optional<EntityView> creeper = creeper(world);
        if (creeper.isPresent()) {
            if (!creeperDecision(world, creeper.get()).fight()) {
                tell(hands, "backing off because of " + creeperDecision(world, creeper.get()).cause() + " " + away(world, creeper.get()));
                return retreat(hands, creeper.get().pos());
            }
            if (creeperTally == null || !creeperTally.of(creeper.get().uuid())) creeperTally = new CreeperFightTask.Tally(creeper.get().uuid());
            java.util.function.BiConsumer<String, String> note = (what, because) -> {
                body.note(what, because);
                if (what.startsWith("running clear")) tell(hands, "running from the creeper because it's hissing" + because.replaceFirst("^it's hissing,", "").replace(" blocks off", " blocks away"));
                else if (what.startsWith("cornered")) tell(hands, "fighting the creeper where it stands because there's no open ground to run to");
            };
            return new CreeperFightTask(hands, creeper.get(), creeperTally, body::reportSwim, note,
                    w -> creeper(w).map(c -> creeperDecision(w, c).fight()).orElse(true));
        }
        Optional<EntityView> alerted = alert(world);
        if (alerted.isPresent()) {
            EntityView e = alerted.get();
            String what = name(e) + " (" + Relative.of(world.position(), world.yaw(), e.pos()) + ")";
            if (!pending.containsKey(e.uuid())) {
                pending.put(e.uuid(), what);
                hands.ping();
                hands.notifyLocal("§e" + what + " is on the alert list here — keeping away. §f/"
                        + config.commandRoot + " approve§e to fight it, §f/" + config.commandRoot + " mob "
                        + ZymbotConfig.mobKey(e.type()) + " normal§e to always");
                body.note("alert: " + what, "on this server's alert list; waiting for /" + config.commandRoot + " approve");
            } else {
                tell(hands, "backing off because of " + Combat.a(name(e)) + " " + away(world, e) + " (on the alert list; /"
                        + config.commandRoot + " approve to fight it)");
            }
            return untilApproved(e.uuid(), new Fallback(body::note, retreat(hands, e.pos()),
                    new HideTask(hands, FightOrFleeInterrupt::stillAfterUs, body::hiding).noting(body::note)));
        }
        Optional<EntityView> mob = threat(world);
        if (mob.isPresent()) {
            Combat.Decision d = decision(world, mob.get());
            return switch (d.action()) {
                case FIGHT -> new FightTask(hands, mob.get(), body::reportSwim,
                        w -> creeper(w).isEmpty() && alert(w).isEmpty() && threat(w).map(m -> decision(w, m).fight()).orElse(true));
                case COVER -> {
                    tell(hands, "taking cover because of " + named(world, d.cause()) + " " + away(world, mob.get()));
                    yield cover(world, hands, body, retreat(hands, mob.get().pos()));
                }
                case BACK_OFF -> {
                    tell(hands, "backing off because of " + named(world, d.cause()) + " " + away(world, mob.get()));
                    yield new Fallback(body::note, retreat(hands, mob.get().pos()),
                            new HideTask(hands, FightOrFleeInterrupt::stillAfterUs, body::hiding).noting(body::note));
                }
            };
        }
        Optional<Body.Attack> unseen = unseenAttacker(world);
        if (unseen.isPresent()) tell(hands, "backing off because something out of sight hit us ("
                + Relative.of(world.position(), world.yaw(), unseen.get().from()) + ")");
        return unseen.map(a -> retreat(hands, a.from()))
                .orElseGet(() -> Task.failed("fight or flee", "nothing in sight"));
    }

    /**
     * Why it dodges, in the owner's chat only, never the server's (owner, 2026-11-17): the same line
     * not again within {@link #SAY_AGAIN_MILLIS}.
     */
    private void tell(Hands hands, String line) {
        long now = clock.getAsLong();
        if (line.equals(lastSaid) && now - lastSaidAt < SAY_AGAIN_MILLIS) return;
        lastSaid = line;
        lastSaidAt = now;
        hands.notifyLocal(line);
    }

    /** Keeping away from an alert mob ends once it's approved: the reflex then fights it as any other. */
    private Task untilApproved(java.util.UUID id, Task inner) {
        return new Task() {
            @Override public Status tick(WorldView w) {
                if (approved.contains(id)) {
                    inner.cancel();
                    return Status.DONE;
                }
                return inner.tick(w);
            }
            @Override public void cancel() { inner.cancel(); }
            @Override public String failure() { return inner.failure(); }
            @Override public String describe() { return inner.describe(); }
        };
    }

    /**
     * "3 shooters" or "4 mobs" as what they are ("3 skeletons", "2 zombies and a husk"), when the ones in
     * sight add up to the count; else as it was.
     */
    private String named(WorldView world, String cause) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+) (shooters|mobs)").matcher(cause);
        if (!m.matches()) return cause;
        int n = Integer.parseInt(m.group(1));
        Vec3 me = world.position();
        java.util.List<EntityView> them = m.group(2).equals("shooters")
                ? Combat.shooters(world).stream().filter(this::seen).toList()
                : world.nearby().stream()
                        .filter(e -> e.kind() == EntityView.Kind.HOSTILE && Combat.kind(e.type()) != Combat.Kind.LEAVE_ALONE)
                        .filter(this::seen)
                        .filter(e -> Combat.distance(e.pos(), me) <= Combat.CROWD_BLOCKS)
                        .toList();
        return them.size() == n ? Combat.names(them) : cause;
    }

    /** "15 blocks away", "1 block away". */
    private static String away(WorldView world, EntityView e) {
        long n = Math.round(Combat.distance(e.pos(), world.position()));
        return n + (n == 1 ? " block away" : " blocks away");
    }

    private static String name(EntityView e) {
        return e.type() == null ? e.name() : e.type().replaceFirst("^minecraft:", "");
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
        if (creeper.isPresent()) return Math.round(Combat.distance(creeper.get().pos(), world.position()))
                + " blocks off: " + creeperDecision(world, creeper.get()).why() + raised(world);
        Optional<EntityView> alerted = alert(world);
        if (alerted.isPresent()) return Math.round(Combat.distance(alerted.get().pos(), world.position())) + " blocks off: "
                + name(alerted.get()) + " is on this server's alert list — keep away until approved";
        Optional<EntityView> mob = attacker(world);
        if (mob.isPresent()) return "hit by " + decision(world, mob.get()).why() + raised(world);
        Optional<EntityView> near = closing(world);
        if (near.isPresent()) return Math.round(Combat.distance(near.get().pos(), world.position())) + " blocks off: "
                + decision(world, near.get()).why() + raised(world);
        return "hit by something out of sight";
    }
}
