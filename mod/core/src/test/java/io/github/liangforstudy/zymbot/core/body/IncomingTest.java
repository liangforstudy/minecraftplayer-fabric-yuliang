package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.ProjectileView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, "Incoming projectiles": a ghast's fireball is hit back at the ghast. */
class IncomingTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    static final int FIREBALL = 900;

    Bot walking(FakeWorld w) {
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.goTo(200, null, 0);
        tick(b, w);
        tick(b, w);
        return b;
    }

    void tick(Bot b, FakeWorld w) {
        clock.now += 50;
        b.tick(w, w);
    }

    String log(Bot b) {
        return String.join("\n", b.decisions().latest(30).stream().map(Object::toString).toList());
    }

    static EntityView ghast(FakeWorld w, double x, double y, double z) {
        EntityView g = new EntityView(700, UUID.randomUUID(), "Ghast", "minecraft:ghast", EntityView.Kind.HOSTILE, new Vec3(x, y, z));
        w.nearby.add(g);
        return g;
    }

    /** A fireball at {@code from}, flying at {@code speed} blocks a tick toward {@code to}. */
    static ProjectileView fireball(Vec3 from, Vec3 to, double speed, int owner) {
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new ProjectileView(FIREBALL, "minecraft:fireball", from, new Vec3(dx / d * speed, dy / d * speed, dz / d * speed), owner);
    }

    /** Moves the fireball on one tick. */
    static void fly(FakeWorld w) {
        ProjectileView p = w.projectiles.remove(0);
        Vec3 v = p.velocity();
        w.projectiles.add(new ProjectileView(p.id(), p.type(), new Vec3(p.pos().x() + v.x(), p.pos().y() + v.y(), p.pos().z() + v.z()),
                v, p.ownerId()));
    }

    static double distance(Vec3 a, Vec3 b) {
        return Combat.distance(a, b);
    }

    @Test
    void aFireballComingAtUs_facesTheGhast_hitsItInReach_thenTheWalkGoesOn() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView g = ghast(w, -20, 70, 0);
        w.projectiles.add(fireball(new Vec3(-18, 71, 0), new Vec3(0, 64.9, 0), 1.0, g.id()));
        Vec3 eyes = new Vec3(0, 64 + 1.62, 0);
        boolean hit = false;
        for (int i = 0; i < 30 && !hit; i++) {
            tick(b, w);
            hit = w.attacked.contains(FIREBALL);
            if (!hit) fly(w);
            else assertTrue(distance(w.projectiles.get(0).pos(), eyes) <= io.github.liangforstudy.zymbot.core.task.DeflectTask.REACH + 1.0,
                    "hit in reach, not from afar: " + distance(w.projectiles.get(0).pos(), eyes));
        }
        assertTrue(hit, "swung at the fireball:\n" + log(b));
        assertEquals(List.of(FIREBALL), w.attacked, "one swing, at the fireball, not the ghast");
        assertEquals(new Vec3(-20, 72, 0), w.lookedAt, "facing the ghast's middle as it swung");
        assertTrue(log(b).contains("hit it back") && log(b).contains("hit a fireball back"), log(b));

        ProjectileView p = w.projectiles.remove(0);                // deflected: now flies back at the ghast
        w.projectiles.add(new ProjectileView(p.id(), p.type(), p.pos(),
                new Vec3(-p.velocity().x(), -p.velocity().y(), -p.velocity().z()), p.ownerId()));
        for (int i = 0; i < 25; i++) tick(b, w);
        assertEquals(200, w.paths.goal.x(), "back on the walk:\n" + log(b));
    }

    @Test
    void aDriftingGhast_isLedByWhereItWillBeWhenTheFireballGetsBack() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView g = ghast(w, -20, 70, 0);
        w.projectiles.add(fireball(new Vec3(-18, 71, 0), new Vec3(0, 64.9, 0), 1.0, g.id()));
        double gz = 0;
        for (int i = 0; i < 30 && w.attacked.isEmpty(); i++) {
            tick(b, w);
            if (!w.attacked.isEmpty()) break;
            fly(w);
            gz += 0.3;                                              // drifting south, 0.3 a tick
            w.nearby.clear();
            w.nearby.add(new EntityView(g.id(), g.uuid(), g.name(), g.type(), g.kind(), new Vec3(-20, 70, gz)));
        }
        assertEquals(List.of(FIREBALL), w.attacked, log(b));
        assertTrue(w.lookedAt.z() > gz + 2, "aimed ahead of it (at z " + w.lookedAt.z() + ", it's at " + gz + ")");
    }

    @Test
    void aFireballPassingWide_isLeftAlone() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        EntityView g = ghast(w, -20, 70, 0);
        w.projectiles.add(fireball(new Vec3(-18, 71, 0), new Vec3(0, 65, 6), 1.0, g.id()));
        for (int i = 0; i < 25; i++) {
            tick(b, w);
            fly(w);
        }
        assertTrue(w.attacked.isEmpty());
        assertFalse(log(b).contains("hit it back"), log(b));
    }

    @Test
    void anUnseenShooter_isHitBackTheWayItCame() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        w.projectiles.add(fireball(new Vec3(-18, 71, 0), new Vec3(0, 64.9, 0), 1.0, -1));
        for (int i = 0; i < 30 && w.attacked.isEmpty(); i++) {
            tick(b, w);
            if (w.attacked.isEmpty()) fly(w);
        }
        assertEquals(List.of(FIREBALL), w.attacked, log(b));
        assertTrue(w.lookedAt.x() < -10 && w.lookedAt.y() > 68, "faced back up its path: " + w.lookedAt);
    }

    @Test
    void dugIn_orAnArrow_isNoTrigger() {
        Body body = new Body(java.util.ArrayList::new, id -> false, () -> 0L, () -> {});
        IncomingInterrupt in = new IncomingInterrupt(body);
        FakeWorld w = new FakeWorld("Bluetails");
        w.projectiles.add(fireball(new Vec3(-10, 70, 0), new Vec3(0, 64.9, 0), 1.0, -1));
        assertTrue(in.triggered(w));
        body.hiding(true);
        assertFalse(in.triggered(w), "in the hole it's out of sight");
        body.hiding(false);
        w.projectiles.clear();
        ProjectileView f = fireball(new Vec3(-10, 70, 0), new Vec3(0, 64.9, 0), 1.0, -1);
        w.projectiles.add(new ProjectileView(f.id(), "minecraft:arrow", f.pos(), f.velocity(), -1));
        assertFalse(in.triggered(w), "arrows aren't hit back");
    }
}
