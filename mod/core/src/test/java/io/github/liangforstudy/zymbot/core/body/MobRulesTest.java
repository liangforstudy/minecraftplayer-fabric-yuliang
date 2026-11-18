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
        assertTrue(w.notices.stream().anyMatch(n -> n.contains("zombie villager") && n.contains("alert list") && n.contains("/zbot approve")),
                String.join("\n", w.notices));
        assertTrue(w.attacked.isEmpty(), "not hit before approval:\n" + log(b));
        ticks(b, w, 20);
        assertEquals(1, w.pings, "pinged once per mob");

        assertTrue(b.approve().get(0).startsWith("approved: zombie villager"));
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
    void aZombieWithACreeper_theZombieIsFoughtFirst_butNotWhileTheCreeperHisses() {
        FakeWorld w = armed();
        Bot b = walking(w, new ZymbotConfig(), "9b9t.com");
        mob(w, 901, "zombie", w.pos.x() - 2);
        mob(w, 902, "creeper", w.pos.x() - 5);
        w.attackReady = true;
        for (int i = 0; i < 30 && !w.attacked.contains(901); i++) {
            mob(w, 901, "zombie", w.pos.x() - 2);                   // it keeps close; the creeper hangs back, out of hiss range
            mob(w, 902, "creeper", w.pos.x() - 4.8);
            ticks(b, w, 1);
        }
        assertTrue(w.attacked.contains(901), "the zombie, first\n" + log(b) + "\n" + String.join("\n", w.notices));
        assertTrue(w.notices.stream().anyMatch(n -> n.startsWith("fighting a zombie first, then the creeper")), String.join("\n", w.notices));

        FakeWorld h = armed();                                      // hissing: keep away from both
        Bot b2 = walking(h, new ZymbotConfig(), "9b9t.com");
        mob(h, 901, "zombie", h.pos.x() - 2);
        mob(h, 902, "creeper", h.pos.x() - 5);
        h.fuses.put(902, new io.github.liangforstudy.zymbot.core.api.Fuse(0.3f, true, false));
        h.attackReady = true;
        for (int i = 0; i < 10; i++) {
            mob(h, 901, "zombie", h.pos.x() - 2);
            mob(h, 902, "creeper", h.pos.x() - 4.8);
            ticks(b2, h, 1);
        }
        assertFalse(h.attacked.contains(901), log(b2));

        FakeWorld n = armed();                                      // calm but about to hiss: keep away too
        Bot b3 = walking(n, new ZymbotConfig(), "9b9t.com");
        mob(n, 901, "zombie", n.pos.x() - 2);
        mob(n, 902, "creeper", n.pos.x() - 3.5);
        n.attackReady = true;
        for (int i = 0; i < 10; i++) {
            mob(n, 901, "zombie", n.pos.x() - 2);
            mob(n, 902, "creeper", n.pos.x() - 3.5);
            ticks(b3, n, 1);
        }
        assertFalse(n.attacked.contains(901), log(b3));
    }

    @Test
    void afterBackingOff_itTurnsBackForTheEndermanThatFollowed() {
        FakeWorld w = armed();
        w.armor = 10;                                               // basic armor fights 2: three zombies and it is too many
        Bot b = walking(w, new ZymbotConfig(), "9b9t.com");
        for (int i = 0; i < 3; i++) mob(w, 910 + i, "zombie" + i, w.pos.x() - 3 - i);
        EntityView e = mob(w, 920, "enderman", w.pos.x() - 1.5);
        w.damage = new Damage("minecraft:mob_attack", "Enderman", e.pos(), e.uuid());
        w.attackReady = true;
        ticks(b, w, 5);
        assertTrue(w.notices.stream().anyMatch(n -> n.startsWith("backing off")), String.join("\n", w.notices) + "\n" + log(b));
        assertFalse(w.attacked.contains(920), log(b));

        w.nearby.removeIf(o -> o.id() >= 910 && o.id() < 913);       // the zombies are left behind; the enderman keeps up
        for (int i = 0; i < 60 && !w.attacked.contains(920); i++) {
            mob(w, 920, "enderman", w.pos.x() - 1.5);
            ticks(b, w, 1);
        }
        assertTrue(w.attacked.contains(920), "turned back for it\n" + log(b));
    }

    @Test
    void skeletonsWeHideFrom_outrankAZombieNearby() {
        FakeWorld w = armed();                                      // good armor rushes 2 shooters; 3 is cover
        Bot b = walking(w, new ZymbotConfig(), "9b9t.com");
        w.attackReady = true;
        for (int i = 0; i < 10; i++) {
            mob(w, 930, "zombie", w.pos.x() - 5);
            for (int k = 0; k < 3; k++) mob(w, 931 + k, "skeleton", w.pos.x() + 12 + k);
            ticks(b, w, 1);
        }
        assertFalse(w.attacked.contains(930), "skeletons shooting: cover, not the zombie\n" + log(b));
        assertFalse(log(b).contains("digging in"), "a zombie 5 blocks off would drop into the hole with us\n" + log(b));
        assertTrue(w.notices.stream().anyMatch(n -> n.startsWith("taking cover because of 3 skeletons")), String.join("\n", w.notices));
    }

    static void rider(FakeWorld w, double x) {
        Vec3 at = new Vec3(x, 64, 0.5);
        w.nearby.removeIf(o -> o.id() == 940 || o.id() == 941);
        w.nearby.add(new EntityView(940, UUID.nameUUIDFromBytes("horse".getBytes()), "Zombie Horse", "minecraft:zombie_horse",
                EntityView.Kind.PASSIVE, at));
        w.nearby.add(new EntityView(941, UUID.nameUUIDFromBytes("rider".getBytes()), "Zombie", "minecraft:zombie",
                EntityView.Kind.HOSTILE, new Vec3(x, 64.6, 0.5), java.util.Set.of(EntityView.SPEAR, EntityView.MOUNTED, "on:minecraft:zombie_horse")));
    }

    @Test
    void aSpearRider_itsHorseIsHitFirst_andItDigsInWhenLow() {
        FakeWorld w = armed();
        Bot b = walking(w, new ZymbotConfig(), "9b9t.com");
        w.attackReady = true;
        for (int i = 0; i < 40 && !w.attacked.contains(940); i++) {
            rider(w, w.pos.x() - 2);
            ticks(b, w, 1);
        }
        assertTrue(w.attacked.contains(940), "the horse, first\n" + log(b));
        assertFalse(w.attacked.contains(941), log(b));
        assertTrue(w.notices.stream().anyMatch(n -> n.startsWith("hitting the zombie horse first")), String.join("\n", w.notices));

        FakeWorld low = armed();
        Bot b2 = walking(low, new ZymbotConfig(), "9b9t.com");
        low.health = 14;                                            // at the fight margin: into a hole a horse can't follow
        for (int i = 0; i < 10; i++) {
            rider(low, low.pos.x() - 8);
            ticks(b2, low, 1);
        }
        assertTrue(low.notices.stream().anyMatch(n -> n.startsWith("digging in because of a zombie with a spear on a zombie horse")),
                String.join("\n", low.notices) + "\n" + log(b2));
    }

    @Test
    void lowHealth_holdsUntilWellOverTheLine() {
        Body body = new Body(java.util.List::of, id -> false, clock, () -> {});
        assertFalse(body.lowHealth(14, 12));
        assertTrue(body.lowHealth(12, 12));
        assertTrue(body.lowHealth(12.5f, 12), "half a heart back: still low");
        assertTrue(body.lowHealth(16, 12));
        assertFalse(body.lowHealth(16.5f, 12), "4 over: recovered");
        assertFalse(body.lowHealth(13, 12), "and stays recovered until it falls to the line again");
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
