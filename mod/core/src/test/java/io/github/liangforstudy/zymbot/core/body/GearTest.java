package io.github.liangforstudy.zymbot.core.body;

import static org.junit.jupiter.api.Assertions.*;

import io.github.liangforstudy.zymbot.core.Bot;
import io.github.liangforstudy.zymbot.core.FakeClock;
import io.github.liangforstudy.zymbot.core.FakeWorld;
import io.github.liangforstudy.zymbot.core.Profile;
import io.github.liangforstudy.zymbot.core.api.Damage;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** doc/9b9t/roadmap.md, "Before routes" 3: a totem and golden apples in the gear; the plain apples eaten first. */
class GearTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    @Test
    void aTotemOrTwoGoldenApples_raiseTheTierOne() {
        assertEquals(Combat.Gear.BARE, Combat.Gear.of(0, false, 1));
        assertEquals(Combat.Gear.BASIC, Combat.Gear.of(0, false, 2));
        assertEquals(Combat.Gear.BASIC, Combat.Gear.of(0, true, 0));
        assertEquals(Combat.Gear.BASIC, Combat.Gear.of(0, true, 9), "both together still one tier");
        assertEquals(Combat.Gear.GOOD, Combat.Gear.of(7, true, 0));
        assertEquals(Combat.Gear.GOOD, Combat.Gear.of(20, true, 9));
    }

    @Test
    void twoZombies_bareWithATotem_areFought_andTheLogSaysWhy() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.offhand = LogOutInterrupt.TOTEM;
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.goTo(200, null, 0);
        for (int i = 0; i < 2; i++) { clock.now += 50; b.tick(w, w); }
        EntityView z = new EntityView(500, UUID.randomUUID(), "zombie", "minecraft:zombie", EntityView.Kind.HOSTILE, new Vec3(-2, 64, 0));
        w.nearby.add(z);
        w.nearby.add(new EntityView(501, UUID.randomUUID(), "zombie", "minecraft:zombie", EntityView.Kind.HOSTILE, new Vec3(-3, 64, 1)));
        w.damage = new Damage("minecraft:mob_attack", z.name(), z.pos(), z.uuid());
        for (int i = 0; i < 2; i++) { clock.now += 50; b.tick(w, w); }
        String log = String.join("\n", b.decisions().latest(20).stream().map(Object::toString).toList());
        assertEquals(List.of(z.id()), w.attacked.subList(0, 1), log);
        assertTrue(log.contains("basic armor") && log.contains("(armor 0 + a totem)"), log);
    }

    @Test
    void healing_eatsAPlainGoldenAppleBeforeAnEnchantedOne() {
        FakeWorld w = new FakeWorld("Bluetails")
                .give(2, "minecraft:enchanted_golden_apple", 1, FakeWorld.healing(4))
                .give(6, "minecraft:golden_apple", 3, FakeWorld.healing(4));
        assertEquals("minecraft:golden_apple", FoodChooser.best(w, List.of(), List.of(), true).orElseThrow().id());
        FakeWorld onlyEnchanted = new FakeWorld("Bluetails").give(2, "minecraft:enchanted_golden_apple", 1, FakeWorld.healing(4));
        assertEquals("minecraft:enchanted_golden_apple", FoodChooser.best(onlyEnchanted, List.of(), List.of(), true).orElseThrow().id(),
                "still eaten when it's all there is");
    }

    @Test
    void inSpectatorOrCreative_aZombieWalkingUp_isLeftAlone_andTheWalkGoesOn() {
        FakeWorld w = new FakeWorld("Bluetails");
        w.untouchable = true;
        Bot b = new Bot(new ZymbotConfig(), dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin("9b9t.com", w.name);
        b.goTo(200, null, 0);
        w.nearby.add(new EntityView(500, UUID.randomUUID(), "zombie", "minecraft:zombie", EntityView.Kind.HOSTILE, new Vec3(-2, 64, 0)));
        for (int i = 0; i < 5; i++) { clock.now += 50; b.tick(w, w); }
        String log = String.join("\n", b.decisions().latest(20).stream().map(Object::toString).toList());
        assertTrue(w.attacked.isEmpty(), log);
        assertEquals(200, w.paths.goal.x(), "still walking: " + log);
        w.untouchable = false;                                   // back to survival: the reflexes are back
        for (int i = 0; i < 3; i++) { clock.now += 50; b.tick(w, w); }
        assertFalse(w.attacked.isEmpty(), "in survival it fights again");
    }
}
