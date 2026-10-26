package io.github.liangforstudy.zymbot.core;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which server family a build plays (doc/9b9t/roadmap.md §0, Profiles). Stonecutter sets one per
 * Minecraft version in the adapter; {@link Bot} wires only that profile's modules. {@code core} is
 * shared, so ZymCiv code is in every jar, but under {@link #ANARCHY} it is never created.
 */
public enum Profile {
    /** ZymCiv, the modded 1.21.1 pack: the team, civfabric, the milestone ladder. */
    ZYMCIV,
    /** 9b9t and other anarchy servers: no team, no ZymCiv rules; standby until given a job. */
    ANARCHY;

    /** Module names {@link Bot#modules()} reports, for the ones {@link #ANARCHY} leaves out. */
    public static final String DOWNED = "downed", REGROUP = "regroup", LEASH = "leash", LADDER = "ladder",
            HANDSHAKE = "handshake", BUS = "bus", HUNGER = "hunger", GRAVES = "graves";

    /** Everything on the roadmap's "Off in anarchy" table that exists in code today. */
    public static final Set<String> ZYMCIV_ONLY = Set.of(DOWNED, REGROUP, LEASH, LADDER, HANDSHAKE, BUS, HUNGER, GRAVES);

    /** Tunables (/zbot set, the settings screen's Body tab) that mean something here. */
    private static final List<String> ANARCHY_TUNABLES = List.of("critical", "plantime", "lagtps");

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public boolean has(String module) { return this == ZYMCIV || !ZYMCIV_ONLY.contains(module); }

    /**
     * Takes control by itself on joining a listed server (ZymCiv's Bot accounts). Anarchy is active
     * on every server for the playing account but only stands by: one-shot jobs borrow the controls,
     * {@code /zbot start} hands them over (owner, 2026-10-26).
     */
    public boolean autostarts() { return this == ZYMCIV; }

    /** The danger modes offered: "modpack" is ZymCiv's mobs. */
    public List<String> dangerModes() {
        return this == ZYMCIV ? List.of("modpack", "easy", "normal", "hard") : List.of("easy", "normal", "hard");
    }

    public String defaultDanger() { return this == ZYMCIV ? "modpack" : "hard"; }

    public boolean hasTunable(String name) { return this == ZYMCIV || ANARCHY_TUNABLES.contains(name); }
}
