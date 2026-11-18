package io.github.liangforstudy.zymbot.core.api;

import java.util.UUID;

/** Something alive near the bot. {@code type} is the registry id, e.g. "minecraft:zombie". */
public record EntityView(int id, UUID uuid, String name, String type, Kind kind, Vec3 pos, java.util.Set<String> traits) {
    public enum Kind { PLAYER, HOSTILE, PASSIVE }

    /** A baby (faster, smaller). */
    public static final String BABY = "baby";
    /** Holding a spear: a mounted charge with one hurts far more than a punch. */
    public static final String SPEAR = "spear";
    /** Riding something; the trait "on:&lt;type&gt;" names what. */
    public static final String MOUNTED = "mounted";

    public EntityView {
        traits = traits == null ? java.util.Set.of() : java.util.Set.copyOf(traits);
    }

    public EntityView(int id, UUID uuid, String name, String type, Kind kind, Vec3 pos) {
        this(id, uuid, name, type, kind, pos, java.util.Set.of());
    }

    public boolean has(String trait) { return traits.contains(trait); }

    /** What it rides ("zombie_horse"), if anything. */
    public java.util.Optional<String> mount() {
        return traits.stream().filter(t -> t.startsWith("on:")).map(t -> t.substring(3)).findFirst();
    }
}
