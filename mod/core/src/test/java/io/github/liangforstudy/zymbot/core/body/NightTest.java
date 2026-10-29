package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import io.github.liangforstudy.zymbot.core.store.BotMemory.Waypoint;
import io.github.liangforstudy.zymbot.core.task.HideTask;
import io.github.liangforstudy.zymbot.core.task.NightWaitTask;
import io.github.liangforstudy.zymbot.core.task.RouteWalkTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** doc/9b9t/roadmap.md: overworld routes at night wait for day somewhere safe, then go on. */
class NightTest {
    static final int STOP = 12500, END = 22822;
    final HideTest game = new HideTest();                         // its physics: fall in, jump, land

    static FakeWorld at(long dayTime) {
        FakeWorld w = HideTest.onNetherrack();
        w.time = dayTime;
        return w;
    }

    @Test
    void unsafeFromTheStopTimeUntilFirstLight() {
        assertFalse(Night.unsafe(at(12499), STOP, END), "late afternoon: walk on");
        assertTrue(Night.unsafe(at(12500), STOP, END), "dusk: dig in before mobs come");
        assertTrue(Night.unsafe(at(18000), STOP, END));
        assertTrue(Night.unsafe(at(22821), STOP, END), "mobs can still spawn at 22821");
        assertFalse(Night.unsafe(at(END), STOP, END), "first light: mobs stop spawning");
        assertTrue(Night.unsafe(at(22900), STOP, 23000), "a later resume time (sunrise) waits longer");
        assertFalse(Night.unsafe(at(23000), STOP, 23000));
        assertFalse(Night.unsafe(at(24000 + 6000), STOP, END), "day times wrap round");
    }

    @Test
    void stormsAndRainCountTheNetherAndCreativeDont() {
        FakeWorld storm = at(6000);
        storm.thundering = true;
        storm.rain = storm.thunder = 1;
        assertTrue(Night.unsafe(storm, STOP, END), "a thunderstorm: mobs spawn at noon");
        assertTrue(Night.why(storm, STOP, END).startsWith("a thunderstorm"), Night.why(storm, STOP, END));

        FakeWorld rainyDawn = at(23000);
        rainyDawn.rain = 1;
        assertTrue(Night.unsafe(rainyDawn, STOP, END), "rain keeps the dawn dark enough for mobs");
        assertTrue(Night.why(rainyDawn, STOP, END).startsWith("rain"), Night.why(rainyDawn, STOP, END));
        FakeWorld rainyNoon = at(6000);
        rainyNoon.rain = 1;
        assertFalse(Night.unsafe(rainyNoon, STOP, END), "rain alone doesn't darken a day enough");

        FakeWorld nether = at(18000);
        nether.nether = true;
        assertFalse(Night.unsafe(nether, STOP, END), "no day to wait for in the Nether");
        FakeWorld creative = at(18000);
        creative.untouchable = true;
        assertFalse(Night.unsafe(creative, STOP, END));

        assertTrue(Night.why(at(12600), STOP, END).startsWith("dusk"), Night.why(at(12600), STOP, END));
        assertTrue(Night.why(at(18000), STOP, END).startsWith("night"), Night.why(at(18000), STOP, END));
    }

    @Test
    void theNightSettingsHaveSensibleRanges() {
        ZymbotConfig cfg = new ZymbotConfig();
        assertEquals(12500, cfg.tunable("nightstop"));
        assertEquals(22822, cfg.tunable("nightend"));
        assertNotNull(cfg.setTunable("nightend", 20000), "before first light means nothing: the wait lasts while mobs spawn anyway");
        assertNull(cfg.setTunable("nightend", 23000));
        assertEquals(23000, cfg.nightResumeAt);
        assertNotNull(cfg.setTunable("nightstop", 14000), "after dark: too late to dig in first");
        cfg.nightResumeAt = 5;
        cfg.normalize();
        assertEquals(22822, cfg.nightResumeAt, "clamped into range");
    }

    // ------------------------------------------------------------------ the wait

    NightWaitTask waitTask(FakeWorld w) {
        return new NightWaitTask(w, x -> Night.unsafe(x, STOP, END), x -> false, x -> Night.why(x, STOP, END), h -> {}, (a, b) -> {});
    }

    @Test
    void digsIn_staysDownAllNight_climbsOutAtFirstLight() {
        FakeWorld w = at(13000);
        NightWaitTask t = waitTask(w);
        Task.Status s = Task.Status.RUNNING;
        for (int i = 0; i < 200; i++) { s = t.tick(w); game.physics(w); w.time++; }
        assertEquals(61, w.pos.y(), "dug in, 3 down");
        assertEquals("waiting for day: hiding in a hole", t.describe());
        w.time = END;
        for (int i = 0; i < 400 && s == Task.Status.RUNNING; i++) { s = t.tick(w); game.physics(w); }
        assertEquals(Task.Status.DONE, s, t.failure());
        assertEquals(64, w.pos.y(), "out again");
        assertNull(w.loggedOut);
        assertFalse(t.loggedOut());
    }

    @Test
    void cantDigIn_logsOutWithTheReason() {
        FakeWorld w = at(18000);
        w.blocks.put(new BlockPos(0, 63, 0), "minecraft:bedrock");
        NightWaitTask t = waitTask(w);
        assertEquals(Task.Status.DONE, t.tick(w));
        assertTrue(t.loggedOut());
        assertTrue(w.loggedOut.contains("night") && w.loggedOut.contains("can't dig in here"), w.loggedOut);
    }

    @Test
    void stillDarkAfterTheLongestWait_logsOut() {
        FakeWorld w = at(18000);
        w.thundering = true;                                        // a storm that never ends
        NightWaitTask t = waitTask(w);
        Task.Status s = Task.Status.RUNNING;
        for (int i = 0; i < NightWaitTask.MAX_WAIT_TICKS + 2000 && s == Task.Status.RUNNING; i++) { s = t.tick(w); game.physics(w); }
        assertEquals(Task.Status.DONE, s);
        assertTrue(t.loggedOut());
        assertTrue(w.loggedOut.contains("still a thunderstorm"), w.loggedOut);
    }

    @Test
    void builtAgainAtTheBottomOfItsHole_waitsThere_insteadOfDiggingDeeper() {
        FakeWorld w = at(18000);
        for (int y = 61; y <= 63; y++) {
            w.blocks.put(new BlockPos(0, y, 0), "minecraft:air");
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) w.blocks.put(new BlockPos(d[0], y, d[1]), "minecraft:netherrack");
        }
        w.pos = new Vec3(0.5, 61, 0.5);                             // standing on (0, 60, 0), 3 down
        w.give(0, "minecraft:netherrack", 3, null);
        HideTask t = new HideTask(w, x -> x.timeOfDay() < END);
        t.tick(w);
        assertEquals("hiding in a hole", t.describe());
        assertNull(w.mining, "no digging: it's already down");
        assertEquals(3, HideTask.shaftDepth(w));
        w.time = END;
        Task.Status s = Task.Status.RUNNING;
        for (int i = 0; i < 400 && s == Task.Status.RUNNING; i++) { s = t.tick(w); game.physics(w); }
        assertEquals(Task.Status.DONE, s, t.failure());
        assertEquals(64, w.pos.y(), "climbed out with the netherrack it carried");
        assertEquals("minecraft:netherrack", w.blockAt(new BlockPos(0, 61, 0)));
    }

    @Test
    void notAHole_onOpenGround_orInATunnel() {
        FakeWorld open = HideTest.onNetherrack();
        assertEquals(0, HideTask.shaftDepth(open));
        FakeWorld tunnel = at(18000);                               // walled on three sides only
        for (int y = 61; y <= 63; y++) {
            tunnel.blocks.put(new BlockPos(0, y, 0), "minecraft:air");
            tunnel.blocks.put(new BlockPos(1, y, 0), "minecraft:air");
        }
        tunnel.pos = new Vec3(0.5, 61, 0.5);
        assertEquals(0, HideTask.shaftDepth(tunnel));
    }

    // ------------------------------------------------------------------ the route

    /** A leg that never arrives on its own: counts its ticks and whether it was cancelled. */
    static final class Leg implements Task {
        int ticks;
        boolean cancelled;
        @Override public Status tick(WorldView world) { ticks++; return Status.RUNNING; }
        @Override public void cancel() { cancelled = true; }
        @Override public String failure() { return ""; }
        @Override public String describe() { return "walking a leg"; }
    }

    /** A wait the test ends by hand. */
    static final class Wait implements Task {
        Status next = Status.RUNNING;
        boolean cancelled;
        @Override public Status tick(WorldView world) { return next; }
        @Override public void cancel() { cancelled = true; }
        @Override public String failure() { return ""; }
        @Override public String describe() { return "waiting for day"; }
    }

    final List<Leg> legs = new ArrayList<>();
    final List<Wait> waits = new ArrayList<>();
    final List<String> notes = new ArrayList<>();

    RouteWalkTask route(Predicate<WorldView> dark) {
        return new RouteWalkTask("home", List.of("A", "B"),
                List.of(new Waypoint(10, 64, 0, "minecraft:overworld"), new Waypoint(20, 64, 0, "minecraft:overworld")),
                () -> 0, p -> { Leg l = new Leg(); legs.add(l); return l; }, (leg, of) -> {}, (what, why) -> notes.add(what))
                .stopFor(dark, () -> { Wait x = new Wait(); waits.add(x); return x; });
    }

    @Test
    void routeStopsTheLegForTheNight_thenStartsItAgainAtDay() {
        FakeWorld w = at(6000);
        RouteWalkTask r = route(x -> Night.unsafe(x, STOP, END));
        r.tick(w);
        assertEquals(1, legs.size());
        w.time = 13000;
        assertEquals(Task.Status.RUNNING, r.tick(w));
        assertTrue(legs.get(0).cancelled, "the leg stops for the night");
        assertEquals(1, waits.size());
        assertTrue(r.describe().startsWith("waiting for day"), r.describe());
        r.tick(w);
        assertEquals(1, legs.size(), "no walking while it waits");
        w.time = END;
        waits.get(0).next = Task.Status.DONE;
        r.tick(w);
        assertEquals(2, legs.size(), "the same leg again, from where it is");
        assertTrue(notes.contains("carrying on with route home"), notes.toString());
    }

    @Test
    void waitDoneButStillDark_theRouteStopsForTheNight() {
        FakeWorld w = at(18000);
        RouteWalkTask r = route(x -> Night.unsafe(x, STOP, END));
        r.tick(w);
        waits.get(0).next = Task.Status.DONE;                       // logged out, say
        assertEquals(Task.Status.FAILED, r.tick(w));
        assertTrue(r.failure().startsWith("stopped for the night"), r.failure());
    }

    @Test
    void neverStopsInWaterOrMidAir_andCancelStopsTheWait() {
        FakeWorld w = at(18000);
        w.inWater = true;
        RouteWalkTask r = route(x -> Night.unsafe(x, STOP, END));
        r.tick(w);
        assertTrue(waits.isEmpty(), "swims on to dry land first");
        w.inWater = false;
        w.onGround = false;
        r.tick(w);
        assertTrue(waits.isEmpty(), "lands first");
        w.onGround = true;
        r.tick(w);
        assertEquals(1, waits.size());
        r.cancel();
        assertTrue(waits.get(0).cancelled);
    }

    @Test
    void aRouteAlreadyWalkedDoesntDigInFirst() {
        RouteWalkTask done = new RouteWalkTask("home", List.of("A"), List.of(new Waypoint(10, 64, 0, "minecraft:overworld")),
                () -> 1, p -> new Leg(), (leg, of) -> {}, (what, why) -> {})
                .stopFor(x -> true, () -> { Wait x = new Wait(); waits.add(x); return x; });
        assertEquals(Task.Status.DONE, done.tick(at(18000)));
        assertTrue(waits.isEmpty());
    }

    @Test
    void noStopWhereThePredicateSaysNo() {
        RouteWalkTask r = route(x -> false);                        // ZymCiv routes, the Nether: Bot passes "never"
        FakeWorld night = at(18000);
        r.tick(night);
        r.tick(night);
        assertTrue(waits.isEmpty());
        assertEquals(1, legs.size(), "walks on through the night");
    }
}
