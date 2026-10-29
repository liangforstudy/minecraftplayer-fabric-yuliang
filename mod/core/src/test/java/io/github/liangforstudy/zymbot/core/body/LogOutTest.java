package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.Damage;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, "Before routes" 1: still being hurt at the log-out line, leave the server. */
class LogOutTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    Bot walking(FakeWorld w) {
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.goTo(200, null, 0);
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

    static void hurtByBlaze(FakeWorld w) {
        w.damage = new Damage("minecraft:mob_attack", "Blaze", new Vec3(-5, 64, 0));
    }

    @Test
    void bare_hurtAt8_logsOut_sayingWhyAndWhere() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        w.health = 8;
        hurtByBlaze(w);
        tick(b, w);
        assertNotNull(w.loggedOut, log(b));
        assertTrue(w.loggedOut.contains("health 8.0 ≤ 8, hurt by Blaze (5 right); no totem, no healing food; facing south at 0 64 0"),
                w.loggedOut);
        assertTrue(log(b).contains("logging out — because health 8.0 ≤ 8"), log(b));
    }

    @Test
    void withGoldenApples_theLineIs6() {
        FakeWorld w = new FakeWorld("Bluetails").give(5, "minecraft:golden_apple", 2, FakeWorld.healing(4));
        Bot b = walking(w);
        w.health = 8;
        hurtByBlaze(w);
        tick(b, w);
        assertNull(w.loggedOut, "8 with golden apples: eat, don't leave");
        w.health = 6;
        tick(b, w);
        assertNotNull(w.loggedOut, log(b));
        assertTrue(w.loggedOut.contains("≤ 6") && w.loggedOut.contains("healing food but no totem"), w.loggedOut);
    }

    @Test
    void withATotemInTheOffhand_theLineIs4() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.offhand = LogOutInterrupt.TOTEM;
        Bot b = walking(w);
        w.health = 5;
        hurtByBlaze(w);
        tick(b, w);
        assertNull(w.loggedOut);
        w.health = 4;
        tick(b, w);
        assertNotNull(w.loggedOut, log(b));
        assertTrue(w.loggedOut.contains("≤ 4") && w.loggedOut.contains("a totem in the offhand"), w.loggedOut);
    }

    @Test
    void lowButNotBeingHurt_staysOn() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w);
        w.health = 3;
        for (int i = 0; i < 20; i++) tick(b, w);
        assertNull(w.loggedOut, "nothing hurting us: no reason to leave");
    }
}
