package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.Damage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Mobs the client doesn't show that still hurt the player (issue #10, "ghost hoglins"): a mob's hit
 * whose attacker isn't on the client at all. One can be a desync blip, so it takes {@link #HITS} of them
 * within {@link #WINDOW_MS} to log out. Runs with or without {@code /zbot start}, like the hang guard.
 */
public final class GhostHitWatch {
    /** Damage types dealt by a mob that is there (not falling, fire, a potion cloud...). */
    static final Set<String> MOB_HITS = Set.of("minecraft:mob_attack", "minecraft:mob_attack_no_aggro", "minecraft:mob_projectile");
    public static final int HITS = 2;
    public static final long WINDOW_MS = 10_000;

    private final Deque<Long> hits = new ArrayDeque<>();

    /** A hit from something the client can't see: a mob's damage type, and no attacker on the client. */
    public static boolean isGhost(Damage d) {
        return d != null && MOB_HITS.contains(d.type()) && d.attacker() == null;
    }

    /** Each new hit (once per hit, not per tick). True when it's time to log out. */
    public boolean onHit(Damage d, long nowMs) {
        while (!hits.isEmpty() && nowMs - hits.peekFirst() > WINDOW_MS) hits.pollFirst();
        if (!isGhost(d)) return false;
        hits.addLast(nowMs);
        if (hits.size() < HITS) return false;
        hits.clear();
        return true;
    }

    public void reset() {
        hits.clear();
    }
}
