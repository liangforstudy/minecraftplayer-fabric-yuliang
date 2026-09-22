package dev.yuliang.zymbot.fabric;

import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/** One tick's snapshot of the game, in the brain's terms. Built fresh every tick; never stored. */
final class FabricWorldView implements WorldView {
    private final UUID id;
    private final String name;
    private final Vec3 pos;
    private final float health;
    private final int hunger;
    private final boolean dead;
    private final long dayTime;
    private final Set<UUID> online;

    FabricWorldView(LocalPlayer player, ClientLevel level, ClientPacketListener connection) {
        this.id = player.getUUID();
        this.name = player.getName().getString();
        this.pos = new Vec3(player.getX(), player.getY(), player.getZ());
        this.health = player.getHealth();
        this.hunger = player.getFoodData().getFoodLevel();
        this.dead = player.isDeadOrDying();
        this.dayTime = level.getDayTime();
        this.online = connection == null ? Set.of() : Set.copyOf(connection.getOnlinePlayerIds());   // the tab list
    }

    @Override public UUID selfId() { return id; }
    @Override public String selfName() { return name; }
    @Override public Vec3 position() { return pos; }
    @Override public float health() { return health; }
    @Override public int hunger() { return hunger; }
    @Override public boolean isDead() { return dead; }
    @Override public long timeOfDay() { return Math.floorMod(dayTime, 24000L); }
    @Override public long day() { return Math.floorDiv(dayTime, 24000L); }
    @Override public Set<UUID> onlinePlayers() { return online; }
}
