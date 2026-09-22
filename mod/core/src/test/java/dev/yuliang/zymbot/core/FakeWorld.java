package dev.yuliang.zymbot.core;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A scripted stand-in for Minecraft (FOUNDATION.md decision 10). Implements the same senses and
 * hands the Fabric adapter does, so the brain runs here in milliseconds with no game at all.
 */
public final class FakeWorld implements WorldView, Hands {
    public final UUID id;
    public final String name;
    public Vec3 pos = new Vec3(0, 64, 0);
    public float health = 20;
    public int hunger = 20;
    public boolean dead;
    public long time = 1000;
    public long day = 1;
    public int respawns;
    public final List<String> notices = new ArrayList<>();
    /** This player's tab list. Starts with just ourselves, like a real client. */
    public final Set<UUID> online = new HashSet<>();

    public FakeWorld(String name) {
        this.name = name;
        this.id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes());
        online.add(id);
    }

    /** Put these players on one server: each sees all of them in its tab list. */
    public static void sameServer(FakeWorld... worlds) {
        for (FakeWorld a : worlds) for (FakeWorld b : worlds) a.online.add(b.id);
    }

    @Override public UUID selfId() { return id; }
    @Override public String selfName() { return name; }
    @Override public Vec3 position() { return pos; }
    @Override public float health() { return health; }
    @Override public int hunger() { return hunger; }
    @Override public boolean isDead() { return dead; }
    @Override public long timeOfDay() { return time; }
    @Override public long day() { return day; }
    @Override public Set<UUID> onlinePlayers() { return online; }

    @Override public void respawn() { respawns++; dead = false; health = 20; }
    @Override public void notifyLocal(String message) { notices.add(message); }
}
