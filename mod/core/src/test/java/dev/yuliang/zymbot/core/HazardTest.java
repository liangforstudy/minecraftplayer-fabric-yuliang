package dev.yuliang.zymbot.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Damage;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.body.Body;
import dev.yuliang.zymbot.core.body.HazardInterrupt;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** The owner lit a fire under Bot1, and pushed it into a cactus: it stood there burning (2026-10-01). */
class HazardTest {
    private final Body body = new Body(ArrayList::new, id -> false, () -> 0L, () -> {});

    private FakeWorld at(String damage) {
        FakeWorld w = new FakeWorld("Bot1");
        w.pos = new Vec3(10.5, 63, 10.5);
        w.blocks.put(new BlockPos(10, 62, 10), "minecraft:grass_block");
        w.damage = damage == null ? null : new Damage(damage, null, null, null);
        return w;
    }

    @Test
    void fireAtItsFeet_stepsOff() {
        FakeWorld w = at("minecraft:in_fire");
        w.blocks.put(new BlockPos(10, 63, 10), "minecraft:fire");
        w.dryLand = new BlockPos(13, 62, 10);
        HazardInterrupt h = new HazardInterrupt(body);
        assertTrue(h.triggered(w));
        assertTrue(h.respond(w, w).describe().contains("13 62 10"));
    }

    @Test
    void cactusBeside_counts() {
        FakeWorld w = at("minecraft:cactus");
        w.blocks.put(new BlockPos(11, 63, 10), "minecraft:cactus");
        assertTrue(new HazardInterrupt(body).triggered(w));
    }

    @Test
    void clearOfIt_orHitBySomeone_doesNothing() {
        assertFalse(new HazardInterrupt(body).triggered(at("minecraft:in_fire")));    // already stepped off
        FakeWorld w = at("minecraft:mob_attack");
        w.blocks.put(new BlockPos(10, 63, 10), "minecraft:fire");
        assertFalse(new HazardInterrupt(body).triggered(w));                          // the retreat's business
    }
}
