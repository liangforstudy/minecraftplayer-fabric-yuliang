package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.DroppedItem;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, "Pick up ghast tears": only with nothing shooting and health to spare. */
class GhastTearTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();
    final Body body = new Body(ArrayList::new, id -> false, clock, () -> {});
    final ZymbotConfig config = new ZymbotConfig();             // critical 8: tears only above 12
    final GhastTearInterrupt tears = new GhastTearInterrupt(config, body, clock);

    /** In the Nether on netherrack (the fake's ground, y 63), a tear lying `away` blocks east. */
    static FakeWorld tearAt(double away) {
        FakeWorld w = new FakeWorld("Bluetails");
        w.nether = true;
        w.drops.add(new DroppedItem(900, GhastTearInterrupt.TEAR, 1, new Vec3(away, 64, 0.5)));
        return w;
    }

    static EntityView mob(String type, Vec3 at) {
        return new EntityView(700, UUID.randomUUID(), type, "minecraft:" + type, EntityView.Kind.HOSTILE, at);
    }

    @Test
    void aTearOnSafeGroundInRange_isFetched_asTreasure() {
        FakeWorld w = tearAt(20.5);
        assertTrue(tears.triggered(w));
        assertEquals("treasure", tears.why(w));
        Task t = tears.respond(w, w);
        assertEquals("walking to ghast tear", t.describe());
        t.tick(w);
        assertEquals(new BlockPos(20, 64, 0), w.paths.goal, "walks to the tear");
    }

    @Test
    void notWithAShooterInRange_lowHealth_whileHiding_orTooFar() {
        FakeWorld ghast = tearAt(10.5);
        ghast.nearby.add(mob("ghast", new Vec3(-30, 80, 0)));
        assertFalse(tears.triggered(ghast), "a ghast about: the tear is let go");

        FakeWorld hurt = tearAt(10.5);
        hurt.health = 12;
        assertFalse(tears.triggered(hurt), "health must be above critical + 4 (12)");
        hurt.health = 13;
        assertTrue(tears.triggered(hurt));

        FakeWorld far = tearAt(40.5);
        assertFalse(tears.triggered(far), "over 32 blocks off");

        FakeWorld spectator = tearAt(10.5);
        spectator.untouchable = true;
        assertFalse(tears.triggered(spectator));

        body.hiding(true);
        assertFalse(tears.triggered(tearAt(10.5)), "dug in: stay down");
        body.hiding(false);

        FakeWorld zombieOnly = tearAt(10.5);
        zombieOnly.nearby.add(mob("zombified_piglin", new Vec3(5, 64, 5)));
        assertTrue(tears.triggered(zombieOnly), "only things that shoot hold it back");
    }

    @Test
    void notOverLavaWaterOrFire_norWithNothingSolidUnder() {
        FakeWorld lava = tearAt(10.5);
        lava.blocks.put(new BlockPos(10, 63, 0), "minecraft:lava");
        assertFalse(tears.triggered(lava), "lost in a lava lake");

        FakeWorld fire = tearAt(10.5);
        fire.blocks.put(new BlockPos(10, 64, 0), "minecraft:fire");
        assertFalse(tears.triggered(fire));

        FakeWorld drop = tearAt(10.5);
        drop.blocks.put(new BlockPos(10, 63, 0), "minecraft:air");
        assertFalse(tears.triggered(drop), "nothing solid under it");
    }

    @Test
    void aTearWeWentFor_isTriedAgainAfterAMinute_andForgottenOnceDespawned() {
        FakeWorld w = tearAt(10.5);
        tears.respond(w, w);
        assertFalse(tears.triggered(w), "not again straight away");
        clock.advance(GhastTearInterrupt.RETRY_MILLIS + 1);
        assertTrue(tears.triggered(w), "still there a minute later: once more");
        tears.respond(w, w);
        clock.advance(GhastTearInterrupt.FORGET_MILLIS + 1);
        w.drops.clear();
        w.drops.add(new DroppedItem(900, GhastTearInterrupt.TEAR, 1, new Vec3(10.5, 64, 0.5)));   // the same id, reused
        assertTrue(tears.triggered(w), "forgotten after the despawn time");
    }

    @Test
    void anarchyFetchesTheTearAndItsGunpowder_thenTheReflexIsDone() {
        FakeWorld w = tearAt(3.5);
        w.drops.add(new DroppedItem(901, "minecraft:gunpowder", 2, new Vec3(4.5, 64, 1.5)));
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.start("test");
        for (int i = 0; i < 400; i++) {                             // on past the pick-up: the sweep settles, then reports
            clock.now += 50;
            b.tick(w, w);
            if (w.paths.busy) {                                     // the fake walk: arrive at once
                w.paths.arrive();
                w.pickUpNear();
            }
        }
        assertTrue(w.drops.isEmpty(), "both picked up");
        String log = String.join("\n", b.decisions().latest(20).stream().map(Object::toString).toList());
        assertTrue(log.contains("walking to ghast tear") && log.contains("treasure"), log);
        assertTrue(log.contains("picked up") && log.contains("ghast_tear") && log.contains("gunpowder"), log);
    }
}
