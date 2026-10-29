package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Damage;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.HideTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, hiding: dig in where there's no cover, then tower back out. */
class HideTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    /** Netherrack under (0, 0) from y 63 down to 59: room to dig 3 and stand on the 4th. */
    static FakeWorld onNetherrack() {
        FakeWorld w = new FakeWorld("Bluetails");
        for (int y = 59; y <= 63; y++) w.blocks.put(new BlockPos(0, y, 0), "minecraft:netherrack");
        w.pos = new Vec3(0.5, 64, 0.5);
        return w;
    }

    boolean airborne;

    /** Just enough of the game: fall into a dug hole, jump 1.25 up, land on the highest block below. */
    void physics(FakeWorld w) {
        int x = (int) Math.floor(w.pos.x()), z = (int) Math.floor(w.pos.z());
        if (w.jumpHeld && w.onGround) {
            w.pos = new Vec3(w.pos.x(), w.pos.y() + 1.25, w.pos.z());
            w.onGround = false;
            airborne = true;
            return;
        }
        int y = (int) Math.floor(w.pos.y() - 0.01);
        while (!w.sturdy(new BlockPos(x, y, z)) || w.blockAt(new BlockPos(x, y, z)).endsWith(":air")) y--;
        w.pos = new Vec3(w.pos.x(), y + 1, w.pos.z());
        w.onGround = true;
        airborne = false;
        w.pickUpNear();
    }

    @Test
    void digsThreeDown_waits_thenTowersBackOut_withTheDugBlocks() {
        FakeWorld w = onNetherrack();
        boolean[] danger = {true};
        HideTask t = new HideTask(w, x -> danger[0]);
        Task.Status s = Task.Status.RUNNING;
        for (int i = 0; i < 60 && w.pos.y() > 61; i++) { s = t.tick(w); physics(w); }
        assertEquals(61, w.pos.y(), "standing on the 4th block, 3 down");
        s = t.tick(w);
        assertEquals("hiding in a hole", t.describe());
        for (int i = 0; i < 200; i++) { s = t.tick(w); physics(w); }
        assertEquals(61, w.pos.y(), "stays down while something is after us");
        danger[0] = false;
        for (int i = 0; i < 400 && s == Task.Status.RUNNING; i++) { s = t.tick(w); physics(w); }
        assertEquals(Task.Status.DONE, s, t.failure());
        assertEquals(64, w.pos.y(), "back up where it stood");
        for (int y = 61; y <= 63; y++) assertEquals("minecraft:netherrack", w.blockAt(new BlockPos(0, y, 0)), "filled at " + y);
        assertFalse(w.jumpHeld, "jump let go");
    }

    @Test
    void coverThatNeverHolds_givesUpAfter8sInSight() {
        FakeWorld w = new FakeWorld("Bluetails");
        CombatTest.wall(w);
        EntityView g = new EntityView(700, UUID.randomUUID(), "ghast", "minecraft:ghast", EntityView.Kind.HOSTILE,
                new Vec3(-16, 64, 0));
        w.nearby.add(g);
        var t = new io.github.liangforstudy.zymbot.core.task.CoverTask(w,
                io.github.liangforstudy.zymbot.core.body.Combat::shooters, x -> {});
        Task.Status s = Task.Status.RUNNING;
        int n = 0;
        while (s == Task.Status.RUNNING && n++ < 400) s = t.tick(w);   // never gets there: the walk never arrives
        assertEquals(Task.Status.FAILED, s);
        assertTrue(n <= 8 * 20 + 2, "gave up after ~8 s, not 90: " + n);
        assertTrue(t.failure().contains("cover keeps failing"), t.failure());
    }

    @Test
    void dugIn_theCriticalReflexLeavesTheHoleAlone_unlessItCanEat() {
        Body body = new Body(java.util.ArrayList::new, id -> false, () -> 0L, () -> {});
        FakeWorld w = onNetherrack();
        w.health = 6;
        var crit = new CriticalHealthInterrupt(new ZymbotConfig(), body, true);
        assertTrue(crit.triggered(w));
        body.hiding(true);
        assertFalse(crit.triggered(w), "dug in, nothing to eat: stay put");
        w.give(0, "minecraft:golden_apple", 1, FakeWorld.healing(4));
        assertTrue(crit.triggered(w), "but eat when there's something that heals");
    }

    @Test
    void criticalWithAFarGhast_digsIn_staysDown_thenClimbsOut() {
        FakeWorld w = onNetherrack();
        for (int y = 59; y <= 63; y++) w.blocks.put(new BlockPos(0, y, 0), "minecraft:cobblestone");
        w.armor = 15;
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.start("test");
        w.health = 8;
        EntityView g = new EntityView(700, UUID.randomUUID(), "ghast", "minecraft:ghast", EntityView.Kind.HOSTILE,
                new Vec3(-55, 90, 0));
        w.nearby.add(g);
        w.damage = new Damage("minecraft:fireball", "ghast", g.pos(), g.uuid());
        String log = "";
        for (int i = 0; i < 20 * 20; i++) {
            clock.now += 50;
            b.tick(w, w);
            physics(w);
            if (i == 5) w.damage = null;
            if (i == 200) w.nearby.clear();                         // the ghast drifts off
            log = String.join("\n", b.decisions().latest(30).stream().map(Object::toString).toList());
            if (i == 150) assertEquals(61, w.pos.y(), "dug in and staying down while the ghast is about:\n" + log);
        }
        assertEquals(64, w.pos.y(), "climbed back out:\n" + log);
    }

    @Test
    void knockedUp_waitsToLand_knockedOff_digsANewHoleWhereItIs() {
        FakeWorld w = onNetherrack();
        for (int y = 59; y <= 63; y++) w.blocks.put(new BlockPos(4, y, 0), "minecraft:netherrack");
        HideTask t = new HideTask(w, x -> true);
        w.onGround = false;                                         // a fireball threw it up
        w.pos = new Vec3(0.5, 65.5, 0.5);
        assertEquals(Task.Status.RUNNING, t.tick(w));
        assertNull(w.mining, "no digging in mid-air");
        w.onGround = true;
        w.pos = new Vec3(0.5, 64, 0.5);
        t.tick(w);
        assertEquals(new BlockPos(0, 63, 0), w.mining);
        w.pos = new Vec3(4.5, 64, 0.5);                             // knocked 4 blocks east
        t.tick(w);
        assertEquals(new BlockPos(4, 63, 0), w.mining, "a new hole where it stands");
    }

    @Test
    void diggingIn_fireUnderfootDoesntInterrupt_lavaStillDoes() {
        Body body = new Body(java.util.ArrayList::new, id -> false, () -> 0L, () -> {});
        FakeWorld w = onNetherrack();
        w.blocks.put(new BlockPos(0, 64, 0), "minecraft:fire");
        w.damage = new Damage("minecraft:in_fire", null, null, null);
        HazardInterrupt h = new HazardInterrupt(body);
        assertTrue(h.triggered(w));
        body.hiding(true);
        assertFalse(h.triggered(w), "two more seconds of digging and it's under the fire");
        w.blocks.put(new BlockPos(0, 64, 0), "minecraft:lava");
        w.damage = new Damage("minecraft:lava", null, null, null);
        assertTrue(h.triggered(w));
    }

    @Test
    void cover_neverPicksAOneWideShaft() {
        FakeWorld w = new FakeWorld("Bluetails");
        for (int y = 61; y <= 63; y++) w.blocks.put(new BlockPos(3, y, 0), "minecraft:air");   // a half-dug hole 3 east
        EntityView g = new EntityView(700, UUID.randomUUID(), "ghast", "minecraft:ghast", EntityView.Kind.HOSTILE,
                new Vec3(-20, 75, 0));
        w.nearby.add(g);
        var t = new io.github.liangforstudy.zymbot.core.task.CoverTask(w,
                io.github.liangforstudy.zymbot.core.body.Combat::shooters, x -> {});
        assertEquals(Task.Status.FAILED, t.tick(w), "the shaft hides us but can't be walked out of");
    }

    @Test
    void neverDigsIntoLava_aDrop_orObsidian() {
        FakeWorld lava = onNetherrack();
        lava.blocks.put(new BlockPos(1, 62, 0), "minecraft:lava");
        assertTrue(HideTask.canDigIn(lava).orElse("").contains("lava beside"));

        FakeWorld drop = onNetherrack();
        drop.blocks.put(new BlockPos(0, 60, 0), "minecraft:air");
        drop.groundY = 40;
        assertTrue(HideTask.canDigIn(drop).orElse("").contains("nothing solid"));

        FakeWorld hard = onNetherrack();
        hard.blocks.put(new BlockPos(0, 62, 0), "minecraft:obsidian");
        assertTrue(HideTask.canDigIn(hard).orElse("").contains("obsidian"));

        assertTrue(HideTask.canDigIn(onNetherrack()).isEmpty());
    }

    @Test
    void aGhastOnOpenNetherrack_noCover_digsIn() {
        FakeWorld w = onNetherrack();
        w.armor = 15;
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.start("test");
        EntityView g = new EntityView(700, UUID.randomUUID(), "ghast", "minecraft:ghast", EntityView.Kind.HOSTILE,
                new Vec3(-30, 70, 0));
        w.nearby.add(g);
        w.damage = new Damage("minecraft:fireball", "ghast", g.pos(), g.uuid());
        for (int i = 0; i < 3; i++) {
            clock.now += 50;
            b.tick(w, w);
        }
        String log = String.join("\n", b.decisions().latest(20).stream().map(Object::toString).toList());
        assertTrue(new BlockPos(0, 63, 0).equals(w.mining) || w.blockAt(new BlockPos(0, 63, 0)).endsWith(":air"),
                "digging in under its feet: mining " + w.mining + "\n" + log);
    }
}
