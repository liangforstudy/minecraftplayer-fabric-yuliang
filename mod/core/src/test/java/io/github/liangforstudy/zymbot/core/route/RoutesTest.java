package io.github.liangforstudy.zymbot.core.route;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md §2: waypoints, routes walked A → E and back, a far start asked about once. */
class RoutesTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    /** In a world, standing by (anarchy), at 0 64 0, with waypoints A (100 64 0), B (100 64 100), C (0 64 100). */
    Bot withPoints(FakeWorld w) {
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        w.pos = new Vec3(0.5, 64, 0.5);
        ticks(b, w, 1);
        b.addWaypoint("A", 100, 64, 0);
        b.addWaypoint("B", 100, 64, 100);
        b.addWaypoint("C", 0, 64, 100);
        return b;
    }

    void ticks(Bot b, FakeWorld w, int n) {
        for (int i = 0; i < n; i++) {
            clock.now += 50;
            b.tick(w, w);
        }
    }

    /** Arrive at each goal the walk sets, recording them, until it stops setting new ones. */
    List<BlockPos> walk(Bot b, FakeWorld w, int legs) {
        List<BlockPos> goals = new java.util.ArrayList<>();
        for (int i = 0; i < legs; i++) {
            ticks(b, w, 3);
            if (w.paths.goal == null || (!goals.isEmpty() && goals.get(goals.size() - 1).equals(w.paths.goal))) break;
            goals.add(w.paths.goal);
            w.paths.arrive();
        }
        ticks(b, w, 3);
        return goals;
    }

    String log(Bot b) {
        return String.join("\n", b.decisions().latest(30).stream().map(Object::toString).toList());
    }

    @Test
    void goWalksAtoC_inOrder_thenBackWalksCtoA() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = withPoints(w);
        assertTrue(b.addRoute("trip", List.of("A", "B", "C")).contains("A → B → C (200 blocks)"));
        assertTrue(b.walkRoute("trip", false, false).startsWith("route trip: A → B → C"));
        assertEquals(List.of(new BlockPos(100, 64, 0), new BlockPos(100, 64, 100), new BlockPos(0, 64, 100)), walk(b, w, 5), log(b));
        assertTrue(log(b).contains("route trip done — because at C"), log(b));
        assertNull(b.memory().routeProgress, "nothing left to carry on from");

        w.paths.goal = null;
        w.pos = new Vec3(0.5, 64, 0.5);
        assertTrue(b.walkRoute("trip", true, false).startsWith("route trip back: C → B → A"));
        assertEquals(List.of(new BlockPos(0, 64, 100), new BlockPos(100, 64, 100), new BlockPos(100, 64, 0)), walk(b, w, 5), log(b));
    }

    @Test
    void cutShort_theSameOrderCarriesOnFromThePointItWasHeadingFor_restartStartsAtA() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = withPoints(w);
        b.addRoute("trip", List.of("A", "B", "C"));
        b.walkRoute("trip", false, false);
        ticks(b, w, 3);
        w.paths.arrive();                                           // at A
        ticks(b, w, 3);
        assertEquals(new BlockPos(100, 64, 100), w.paths.goal, "on to B");
        assertTrue(b.stopRoute().startsWith("stopped route trip"));
        assertEquals(1, b.memory().routeProgress.leg);
        assertTrue(b.routes().get(0).contains("cut short before B"), b.routes().toString());

        String reply = b.walkRoute("trip", false, false);
        assertTrue(reply.contains("carrying on from B (point 2 of 3)"), reply);
        ticks(b, w, 3);
        assertEquals(new BlockPos(100, 64, 100), w.paths.goal);

        b.stopRoute();
        w.paths.goal = null;
        w.pos = new Vec3(0.5, 64, 0.5);                             // back home: A is a walk again
        b.walkRoute("trip", false, true);
        ticks(b, w, 3);
        assertEquals(new BlockPos(100, 64, 0), w.paths.goal, "restart: A first");
    }

    @Test
    void aFarFirstPoint_isRefusedOnce_theSameOrderAgainGoes() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = withPoints(w);
        b.addWaypoint("far", 900, 64, 0);
        b.addRoute("long", List.of("far", "A"));
        String first = b.walkRoute("long", false, false);
        assertTrue(first.contains("is 900 blocks away, more than 500 — type route go long again within 60 s"), first);
        ticks(b, w, 3);
        assertNull(w.paths.goal, "not walking");
        assertTrue(b.walkRoute("long", true, false).contains("route long back"), "back starts at A, 100 off: no question");
        b.stopRoute();
        assertTrue(b.walkRoute("long", false, false).contains("more than 500"), "a different order in between: asked again");
        assertTrue(b.walkRoute("long", false, false).startsWith("route long: far → A"), "typed again: goes");

        b.stopRoute();
        clock.now += 61_000;
        assertTrue(b.walkRoute("long", false, true).contains("more than 500"), "a minute on: asked again");
        b.set("routefar", 0);
        assertTrue(b.walkRoute("long", false, true).startsWith("route long"), "routefar 0: never asks");
    }

    @Test
    void waypointsAndRoutes_areChecked() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = withPoints(w);
        assertTrue(b.addRoute("bad", List.of("A", "Z")).contains("no waypoint 'Z'"));
        w.nether = true;
        ticks(b, w, 1);
        assertTrue(b.addWaypoint("N", 10, 70, 10).contains("(the Nether)"));
        assertTrue(b.addRoute("mixed", List.of("A", "N")).contains("changes dimension at N"));
        b.addRoute("trip", List.of("A", "B"));
        assertTrue(b.walkRoute("trip", false, false).contains("A is in the overworld, we're in the Nether"));
        assertTrue(b.removeWaypoint("A").contains("is on route trip"));
        assertTrue(b.removeRoute("trip").contains("removed"));
        assertTrue(b.removeWaypoint("A").contains("removed"));
        assertTrue(b.addWaypoint("no spaces!", 0, null, 0).contains("letters, digits"));
        assertEquals(3, b.waypoints().size(), b.waypoints().toString());
    }

    @Test
    void routesAreKept_acrossALogOut() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = withPoints(w);
        b.addRoute("trip", List.of("A", "B", "C"));
        b.walkRoute("trip", false, false);
        ticks(b, w, 3);
        w.paths.arrive();
        ticks(b, w, 3);
        b.onLeave();                                                // saves this server's memory
        Bot again = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        again.onJoin("9b9t.com", w.name);
        ticks(again, w, 1);
        assertEquals(List.of("A", "B", "C"), again.memory().routes.get("trip"));
        String reply = again.walkRoute("trip", false, false);
        assertTrue(reply.contains("carrying on from B"), reply);
    }
}
