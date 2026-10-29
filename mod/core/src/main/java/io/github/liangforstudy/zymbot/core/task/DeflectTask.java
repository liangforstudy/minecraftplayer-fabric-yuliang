package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ProjectileView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.body.Combat;
import io.github.liangforstudy.zymbot.core.body.IncomingInterrupt;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Hit a ghast's fireball back: face whoever shot it and, once it's in reach, swing at it. A hit
 * fireball flies off the way we face, so it goes back at the ghast. The server reads our facing from
 * the movement packet sent the tick after we turn, so the swing waits until we've faced it for
 * {@link #AIM_TICKS}. Any hit deflects: no need to wait for the weapon to recharge.
 */
public final class DeflectTask implements Task {
    /** Eyes to its middle: a player's reach of 3 to its 1-block box, a little short so the swing lands. */
    public static final double REACH = 3.3;
    static final int AIM_TICKS = 2;
    static final int GIVE_UP_TICKS = 4 * 20;
    static final double EYES = 1.62;
    /** A ghast's middle, above where it "stands". */
    static final double GHAST_MIDDLE = 2;
    /**
     * A hit-back fireball's average speed, blocks a tick: it leaves at 1 and gains 0.1 a tick with 5%
     * drag, toward ~1.9.
     */
    static final double RETURN_SPEED = 1.5;
    /** After the swing, watch it this long for where it went. */
    static final int FOLLOW_TICKS = 10;

    private final Hands hands;
    private final int fireball;
    private final int owner;
    private final BiConsumer<String, String> note;
    private int ticks;
    private boolean swung;
    private double lastDistance = Double.NaN;
    private Vec3 aimedAt;
    private String swing = "";
    private int followed;
    private Vec3 firstSeen;
    private int firstTick;

    public DeflectTask(Hands hands, ProjectileView fireball, BiConsumer<String, String> note) {
        this.hands = hands;
        this.fireball = fireball.id();
        this.owner = fireball.ownerId();
        this.note = note;
    }

    @Override
    public Status tick(WorldView world) {
        Optional<ProjectileView> f = world.projectiles().stream().filter(p -> p.id() == fireball).findFirst();
        Vec3 me = world.position();
        Vec3 eyes = new Vec3(me.x(), me.y() + EYES, me.z());
        if (swung) return follow(world, f);
        if (f.isEmpty()) return missed("it was gone " + Math.round(lastDistance * 10) / 10.0 + " blocks off");   // hit us, or burst
        ProjectileView p = f.get();
        lastDistance = Combat.distance(p.pos(), eyes);
        if (++ticks > GIVE_UP_TICKS) return missed("still coming after " + GIVE_UP_TICKS / 20 + " s");
        if (!IncomingInterrupt.comingAt(p, IncomingInterrupt.middle(me))) {
            return missed("it turned away " + Math.round(lastDistance * 10) / 10.0 + " blocks off");
        }
        Optional<EntityView> ghast = world.nearby().stream().filter(e -> e.id() == owner).findFirst();
        Vec3 aim = ghast.map(g -> lead(g, eyes)).orElseGet(() -> back(p));
        hands.lookAt(aim);
        if (ticks > AIM_TICKS && lastDistance <= REACH + p.speed()) {         // it closes in a tick
            hands.attack(fireball);
            swung = true;
            aimedAt = aim;
            swing = ghast.map(g -> "at the " + g.name() + ", " + Math.round(Combat.distance(g.pos(), me))
                    + " blocks off").orElse("the way it came") + ", from " + Math.round(lastDistance * 10) / 10.0;
            return Status.RUNNING;
        }
        return Status.RUNNING;
    }

    /**
     * After the swing, a few ticks to see where it went: how far its new flight is off where we aimed
     * (the server's reading of our facing) and off the ghast (the lead). Three returns in a row missed
     * the ghast by 13–17° (2026-10-29); this says which of the two it was.
     */
    private Status follow(WorldView world, Optional<ProjectileView> f) {
        if (f.isEmpty() || ++followed > FOLLOW_TICKS) {
            note.accept("hit a fireball back", swing + (f.isEmpty() ? ", and it was gone" : ", still coming at us"));
            return Status.DONE;
        }
        ProjectileView p = f.get();
        if (IncomingInterrupt.comingAt(p, IncomingInterrupt.middle(world.position()))) return Status.RUNNING;   // not turned yet
        String off = ", flying " + Math.round(angle(p.velocity(), minus(aimedAt, p.pos()))) + "° off our aim";
        Optional<EntityView> ghast = world.nearby().stream().filter(e -> e.id() == owner).findFirst();
        if (ghast.isPresent()) {
            Vec3 g = new Vec3(ghast.get().pos().x(), ghast.get().pos().y() + GHAST_MIDDLE, ghast.get().pos().z());
            off += " and " + Math.round(angle(p.velocity(), minus(g, p.pos()))) + "° off the ghast";
        }
        note.accept("hit a fireball back", swing + off);
        return Status.DONE;
    }

    private static Vec3 minus(Vec3 a, Vec3 b) {
        return new Vec3(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    /** Degrees between two directions. */
    static double angle(Vec3 a, Vec3 b) {
        double la = Math.sqrt(a.x() * a.x() + a.y() * a.y() + a.z() * a.z());
        double lb = Math.sqrt(b.x() * b.x() + b.y() * b.y() + b.z() * b.z());
        if (la < 1e-9 || lb < 1e-9) return 0;
        double c = (a.x() * b.x() + a.y() * b.y() + a.z() * b.z()) / la / lb;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
    }

    /**
     * Where the ghast will be when its fireball gets back: it drifts on while the fireball flies (a
     * second or more), and a return aimed at where it was went past it (2026-10-29, three returns, three
     * misses). Its drift is measured over this task's ticks.
     */
    private Vec3 lead(EntityView g, Vec3 eyes) {
        Vec3 now = new Vec3(g.pos().x(), g.pos().y() + GHAST_MIDDLE, g.pos().z());
        if (firstSeen == null) {
            firstSeen = now;
            firstTick = ticks;
        }
        int seen = ticks - firstTick;
        if (seen < 2) return now;
        double vx = (now.x() - firstSeen.x()) / seen, vy = (now.y() - firstSeen.y()) / seen, vz = (now.z() - firstSeen.z()) / seen;
        double flight = Combat.distance(now, eyes) / RETURN_SPEED;
        return new Vec3(now.x() + vx * flight, now.y() + vy * flight, now.z() + vz * flight);
    }

    private Status missed(String why) {
        if (!swung) note.accept("didn't hit a fireball back", why);
        return Status.DONE;
    }

    /** Back along its path: where it came from, when its shooter is out of sight. */
    private static Vec3 back(ProjectileView p) {
        double s = Math.max(p.speed(), 1e-3);
        return new Vec3(p.pos().x() - p.velocity().x() / s * 20, p.pos().y() - p.velocity().y() / s * 20,
                p.pos().z() - p.velocity().z() / s * 20);
    }

    @Override public void cancel() {}
    @Override public String failure() { return ""; }
    @Override public String describe() { return swung ? "hit a fireball back" : "waiting to hit a fireball back"; }
}
