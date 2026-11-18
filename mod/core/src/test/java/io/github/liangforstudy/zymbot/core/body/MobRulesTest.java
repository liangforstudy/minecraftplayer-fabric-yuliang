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
import io.github.liangforstudy.zymbot.core.config.ZymbotConfig.MobRule;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-server mob rules and auto-logout (owner, 2026-11-17). */
class MobRulesTest {
    @TempDir Path dir;
    final FakeClock clock = new FakeClock();

    FakeWorld armed() {
        FakeWorld w = new FakeWorld("Bluetails").give(2, "minecraft:diamond_sword", 1, null);
        w.armor = 20;
        return w;
    }

    Bot walking(FakeWorld w, ZymbotConfig cfg, String server) {
        Bot b = new Bot(cfg, dir.resolve("zymbot.json"), dir.resolve(w.name), w.id, false, clock, Profile.ANARCHY);
        b.onJoin(server, w.name);
        b.goTo(200, null, 0);
        ticks(b, w, 2);
        return b;
    }

    void ticks(Bot b, FakeWorld w, int n) {
        for (int i = 0; i < n; i++) {
            clock.now += 50;
            b.tick(w, w);
        }
    }

    String log(Bot b) {
        return String.join("\n", b.decisions().latest(30).stream().map(Object::toString).toList());
    }

    static EntityView mob(FakeWorld w, int id, String type, double x) {
        EntityView e = new EntityView(id, UUID.nameUUIDFromBytes(type.getBytes()), type, "minecraft:" + type,
                EntityView.Kind.HOSTILE, new Vec3(x, 64, 0.5));
        w.nearby.removeIf(o -> o.id() == id);
        w.nearby.add(e);
        return e;
    }

    @Test
    void rulesAreKeptPerServer_normalizedAndPruned() {
        ZymbotConfig c = new ZymbotConfig();
        c.setMobRule("9B9T.com", "minecraft:Zombie Villager", MobRule.ALERT);
        assertEquals(MobRule.ALERT, c.mobRule("9b9t.com:25565", "minecraft:zombie_villager"));
        assertEquals(MobRule.NORMAL, c.mobRule("other.net", "zombie_villager"), "another server keeps the default");
        assertEquals(MobRule.NORMAL, c.mobRule("9b9t.com", "zombie"));
        c.setMobRule("9b9t.com", "zombie_villager", MobRule.NORMAL);
        assertTrue(c.servers.isEmpty(), "nothing left to keep: " + c.servers);

        assertTrue(c.autoLogout("9b9t.com"), "servers: on by default");
        assertFalse(c.autoLogout("singleplayer"), "singleplayer: off by default");
        c.setAutoLogout("singleplayer", true);
        assertTrue(c.autoLogout("singleplayer"));
        c.setAutoLogout("9b9t.com", false);
        assertFalse(c.autoLogout("9b9t.com:25565"));

        c.servers.put("Play.Example.NET", new ZymbotConfig.ServerRules());
        c.servers.get("Play.Example.NET").mobs.put("minecraft:Witch", "IGNORE");
        c.servers.get("Play.Example.NET").mobs.put("zombie", "kill");
        assertEquals(1, c.normalize().stream().filter(s -> s.contains("ignoring mob rule zombie = kill")).count());
        assertEquals(MobRule.IGNORE, c.mobRule("play.example.net", "witch"));
    }

    @Test
    void the9b9tAddresses_shareOneSetOfRules_butNotTheReal2b2t() {
        ZymbotConfig c = new ZymbotConfig();
        c.setMobRule("2b2t.com", "zombie_villager", MobRule.ALERT);
        assertEquals(MobRule.ALERT, c.mobRule("9b9t.org", "zombie_villager"));
        assertEquals(MobRule.ALERT, c.mobRule("9b9t.com:25565", "zombie_villager"));
        assertEquals(MobRule.NORMAL, c.mobRule("2b2t.org", "zombie_villager"));
        assertEquals(java.util.List.of("2b2t.com:25565", "9b9t.com:25565", "eu.9b9t.com:25565"), ZymbotConfig.alsoKnownAs("9b9t.org:25565"));
        assertEquals(MobRule.ALERT, c.mobRule("eu.9b9t.com", "zombie_villager"), "issue #5: the EU address is 9b9t too");
        assertEquals("9b9t.org:25565", ZymbotConfig.rulesKey("EU.9b9t.com"));

        ZymbotConfig old = new ZymbotConfig();                      // set apart before: merged on load
        old.servers.put("2b2t.com:25565", new ZymbotConfig.ServerRules());
        old.servers.get("2b2t.com:25565").mobs.put("zombie_villager", "alert");
        old.servers.put("9b9t.org:25565", new ZymbotConfig.ServerRules());
        old.servers.get("9b9t.org:25565").mobs.put("witch", "ignore");
        old.normalize();
        assertEquals(java.util.Set.of("9b9t.org:25565"), old.servers.keySet());
        assertEquals(MobRule.ALERT, old.mobRule("9b9t.com", "zombie_villager"));
        assertEquals(MobRule.IGNORE, old.mobRule("2b2t.com", "witch"));
    }

    @Test
    void anAlertMob_isPingedOnce_keptAwayFrom_andFoughtOnlyOnceApproved() {
        FakeWorld w = armed();
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.setMobRule("9b9t.com", "zombie_villager", MobRule.ALERT);
        Bot b = walking(w, cfg, "9b9t.com");
        mob(w, 800, "zombie_villager", -2.5);
        ticks(b, w, 3);
        assertEquals(1, w.pings, log(b));
        assertTrue(w.notices.stream().anyMatch(n -> n.contains("zombie_villager") && n.contains("alert list") && n.contains("/zbot approve")),
                String.join("\n", w.notices));
        assertTrue(w.attacked.isEmpty(), "not hit before approval:\n" + log(b));
        ticks(b, w, 20);
        assertEquals(1, w.pings, "pinged once per mob");

        assertTrue(b.approve().get(0).startsWith("approved: zombie_villager"));
        w.attackReady = true;
        for (int i = 0; i < 40 && w.attacked.isEmpty(); i++) {     // after its 20-tick pause the reflex looks again
            mob(w, 800, "zombie_villager", w.pos.x() - 1.5);
            ticks(b, w, 1);
        }
        assertFalse(w.attacked.isEmpty(), "approved: fought as any zombie\n" + log(b));
        assertEquals(java.util.List.of("nothing waiting for approval"), b.approve());
    }

    @Test
    void anIgnoredMob_isNeitherFoughtNorDodged() {
        FakeWorld w = armed();
        ZymbotConfig cfg = new ZymbotConfig();
        cfg.setMobRule("9b9t.com", "zombie", MobRule.IGNORE);
        Bot b = walking(w, cfg, "9b9t.com");
        mob(w, 801, "zombie", -1.5);
        ticks(b, w, 10);
        assertTrue(w.attacked.isEmpty(), log(b));
        assertFalse(log(b).contains("fight-or-flee"), log(b));

        FakeWorld w2 = armed();                                     // elsewhere it is an ordinary zombie
        Bot b2 = walking(w2, new ZymbotConfig(), "other.net");
        mob(w2, 801, "zombie", -1.5);
        w2.attackReady = true;
        ticks(b2, w2, 10);
        assertFalse(w2.attacked.isEmpty(), log(b2));
    }

    @Test
    void backingOff_saysWhyInOurChatOnly_once() {
        FakeWorld w = new FakeWorld("Bluetails");                   // bare, two zombies: back off
        Bot b = walking(w, new ZymbotConfig(), "9b9t.com");
        mob(w, 802, "zombie", -3.5);
        mob(w, 803, "husk", -4.5);
        ticks(b, w, 10);
        java.util.List<String> lines = w.notices.stream().filter(n -> n.startsWith("backing off because of ")).toList();
        assertEquals(1, lines.size(), String.join("\n", w.notices));
        assertTrue(lines.get(0).matches("backing off because of a zombie and a husk \\d+ blocks away"), lines.get(0));
    }

    @Test
    void theChatCause_isPlain_andEachLeftAloneMobHasItsOwnReason() {
        assertEquals("3 shooters", Combat.decide("minecraft:skeleton", Combat.Gear.GOOD, 3, 3, 20, 12).cause());
        assertEquals("an evoker (hits too hard)", Combat.decide("minecraft:evoker", Combat.Gear.GOOD, 1, 0, 20, 12).cause());
        assertEquals("a creeper and 1 more mob", Combat.decide("minecraft:creeper", Combat.Gear.GOOD, 2, 0, 20, 12).cause());
        assertEquals("a piglin (hitting it brings its friends)",
                Combat.decide("minecraft:piglin", Combat.Gear.GOOD, 1, 0, 20, 12).cause());
        assertTrue(Combat.decide("minecraft:enderman", Combat.Gear.GOOD, 1, 0, 20, 12).fight(), "it hit us: hit it back");
        assertFalse(Combat.decide("minecraft:enderman", Combat.Gear.BARE, 2, 0, 20, 12).fight(), "with company, bare: back off");
        assertFalse(Combat.decide("minecraft:iron_golem", Combat.Gear.GOOD, 1, 0, 20, 12).why().contains("friends"));
    }

    @Test
    void anEnderman_isLeftAloneNearby_butFoughtOnceItHitsUs() {
        FakeWorld w = armed();
        Bot b = walking(w, new ZymbotConfig(), "9b9t.com");
        EntityView e = mob(w, 804, "enderman", -1.5);
        w.attackReady = true;
        ticks(b, w, 10);
        assertTrue(w.attacked.isEmpty(), "minding its own business: left alone\n" + log(b));
        assertFalse(log(b).contains("fight-or-flee"), log(b));

        w.damage = new Damage("minecraft:mob_attack", "Enderman", e.pos(), e.uuid());
        for (int i = 0; i < 30 && w.attacked.isEmpty(); i++) {
            mob(w, 804, "enderman", w.pos.x() - 1.5);
            ticks(b, w, 1);
        }
        assertFalse(w.attacked.isEmpty(), "it hit us: fought\n" + log(b));
    }

    @Test
    void aProvokedPigman_isFoughtByTheUsualRules_countingItsWholeGroup() {
        FakeWorld w = armed();                                      // good armor fights 4
        Bot b = walking(w, new ZymbotConfig(), "9b9t.com");
        EntityView p = new EntityView(810, UUID.nameUUIDFromBytes("pig0".getBytes()), "Zombified Piglin", "minecraft:zombified_piglin",
                EntityView.Kind.HOSTILE, new Vec3(-1.0, 64, 0.5));
        w.nearby.add(p);
        for (int i = 1; i <= 2; i++) w.nearby.add(new EntityView(810 + i, UUID.nameUUIDFromBytes(("pig" + i).getBytes()), "Zombified Piglin",
                "minecraft:zombified_piglin", EntityView.Kind.HOSTILE, new Vec3(-3.0 - i, 64, 0.5)));
        w.attackReady = true;
        ticks(b, w, 10);
        assertTrue(w.attacked.isEmpty(), "not provoked: left alone\n" + log(b));

        w.damage = new Damage("minecraft:mob_attack", "Zombified Piglin", p.pos(), p.uuid());
        ticks(b, w, 5);
        assertFalse(w.attacked.isEmpty(), "3 of them, good armor fights 4: hit back\n" + log(b));

        FakeWorld w2 = armed();                                     // five of them: too many
        Bot b2 = walking(w2, new ZymbotConfig(), "9b9t.com");
        EntityView q = new EntityView(820, UUID.nameUUIDFromBytes("pigq".getBytes()), "Zombified Piglin", "minecraft:zombified_piglin",
                EntityView.Kind.HOSTILE, new Vec3(-1.0, 64, 0.5));
        w2.nearby.add(q);
        for (int i = 1; i <= 4; i++) w2.nearby.add(new EntityView(820 + i, UUID.nameUUIDFromBytes(("pigq" + i).getBytes()), "Zombified Piglin",
                "minecraft:zombified_piglin", EntityView.Kind.HOSTILE, new Vec3(-2.0 - i, 64, 0.5)));
        w2.damage = new Damage("minecraft:mob_attack", "Zombified Piglin", q.pos(), q.uuid());
        w2.attackReady = true;
        ticks(b2, w2, 5);
        assertTrue(w2.attacked.isEmpty(), log(b2));
        assertTrue(w2.notices.stream().anyMatch(n -> n.startsWith("backing off because of 5 zombified piglins")), String.join("\n", w2.notices));
    }

    @Test
    void mobsAreNamed_notCounted() {
        java.util.List<EntityView> m = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) m.add(new EntityView(900 + i, UUID.randomUUID(), "Skeleton", "minecraft:skeleton", EntityView.Kind.HOSTILE, new Vec3(0, 64, 0)));
        assertEquals("3 skeletons", Combat.names(m));
        m.add(new EntityView(910, UUID.randomUUID(), "Stray", "minecraft:stray", EntityView.Kind.HOSTILE, new Vec3(0, 64, 0)));
        assertEquals("3 skeletons and a stray", Combat.names(m));
        m.add(new EntityView(911, UUID.randomUUID(), "Witch", "minecraft:witch", EntityView.Kind.HOSTILE, new Vec3(0, 64, 0)));
        m.add(new EntityView(912, UUID.randomUUID(), "Witch", "minecraft:witch", EntityView.Kind.HOSTILE, new Vec3(0, 64, 0)));
        assertEquals("3 skeletons, 2 witches and a stray", Combat.names(m));
    }

    @Test
    void autoLogout_offInSingleplayer_byDefault_andSwitchedPerServer() {
        FakeWorld w = new FakeWorld("Bluetails");
        Bot b = walking(w, new ZymbotConfig(), "singleplayer");
        w.health = 6;
        w.damage = new Damage("minecraft:mob_attack", "Blaze", new Vec3(-5, 64, 0));
        ticks(b, w, 1);
        assertNull(w.loggedOut, "singleplayer: off");
        assertTrue(b.autoLogout(true).startsWith("auto-logout on singleplayer: on"), b.autoLogout(null));
        ticks(b, w, 1);
        assertNotNull(w.loggedOut);

        FakeWorld w2 = new FakeWorld("Bluetails");
        ZymbotConfig cfg2 = new ZymbotConfig();
        cfg2.setAutoLogout("9b9t.com", false);
        Bot b2 = walking(w2, cfg2, "9b9t.com");
        w2.health = 6;
        w2.damage = new Damage("minecraft:mob_attack", "Blaze", new Vec3(-5, 64, 0));
        ticks(b2, w2, 1);
        assertNull(w2.loggedOut, "switched off on this server");
    }
}
