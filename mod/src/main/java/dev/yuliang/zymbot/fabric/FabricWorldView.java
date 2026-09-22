package dev.yuliang.zymbot.fabric;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.Damage;
import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Food;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * One tick's snapshot of the game, in the brain's terms. Built fresh every tick and never kept —
 * the level it holds is only read during that tick. Costly parts (entities) are built on demand.
 */
final class FabricWorldView implements WorldView {
    static final double NEARBY = 32;

    private final LocalPlayer player;
    private final ClientLevel level;
    private final UUID id;
    private final String name;
    private final Vec3 pos;
    private final float health;
    private final int hunger;
    private final float saturation;
    private final boolean dead, inWater, onGround, sprinting, using;
    private final int selected;
    private final int air, maxAir;
    private final boolean headInWater;
    private final String lastDeath;
    private final int downedSecs;
    private final boolean beingRevived;
    private final long dayTime;
    private final Set<UUID> online;
    private List<ItemView> inventory;
    private List<EntityView> nearby;

    /** civfabric dbno boss bars (literal names, not translation keys). */
    static final String BLEEDING_OUT = "Bleeding Out", BEING_REVIVED = "Being Revived";
    static final int BLEED_OUT_SECONDS = 60;

    FabricWorldView(LocalPlayer player, ClientLevel level, ClientPacketListener connection,
                    net.minecraft.client.gui.components.BossHealthOverlay bosses) {
        this.player = player;
        this.level = level;
        this.id = player.getUUID();
        this.name = player.getName().getString();
        this.pos = new Vec3(player.getX(), player.getY(), player.getZ());
        this.health = player.getHealth();
        this.hunger = player.getFoodData().getFoodLevel();
        this.saturation = player.getFoodData().getSaturationLevel();
        this.dead = player.isDeadOrDying();
        this.inWater = player.isInWater();
        this.onGround = player.onGround();
        this.sprinting = player.isSprinting();
        this.using = player.isUsingItem();
        this.selected = player.getInventory().selected;
        this.air = player.getAirSupply();
        this.maxAir = player.getMaxAirSupply();
        this.headInWater = player.isEyeInFluid(FluidTags.WATER);
        int downed = -1;
        boolean reviving = false;
        for (var bar : ((dev.yuliang.zymbot.fabric.mixin.BossHealthOverlayAccessor) bosses).zymbot$events().values()) {
            String name = bar.getName().getString();
            if (BLEEDING_OUT.equals(name)) downed = Math.round(bar.getProgress() * BLEED_OUT_SECONDS);
            else if (BEING_REVIVED.equals(name)) reviving = true;
        }
        this.downedSecs = downed;
        this.beingRevived = reviving;
        this.lastDeath = player.getLastDeathLocation()
                .map(g -> g.dimension().location() + " " + g.pos().toShortString()).orElse("");
        this.dayTime = level.getDayTime();
        this.online = connection == null ? Set.of() : Set.copyOf(connection.getOnlinePlayerIds());   // the tab list
    }

    @Override public UUID selfId() { return id; }
    @Override public String selfName() { return name; }
    @Override public Vec3 position() { return pos; }
    @Override public float health() { return health; }
    @Override public int hunger() { return hunger; }
    @Override public float saturation() { return saturation; }
    @Override public boolean isDead() { return dead; }
    @Override public boolean inWater() { return inWater; }
    @Override public boolean onGround() { return onGround; }
    @Override public boolean sprinting() { return sprinting; }
    @Override public boolean usingItem() { return using; }
    @Override public int selectedSlot() { return selected; }
    @Override public int air() { return air; }
    @Override public int maxAir() { return maxAir; }
    @Override public boolean headInWater() { return headInWater; }
    @Override public String lastDeath() { return lastDeath; }
    @Override public int downedSecondsLeft() { return downedSecs; }
    @Override public boolean beingRevived() { return beingRevived; }

    @Override
    public Optional<BlockPos> nearestDryLand(int radius) {
        BlockPos at = BlockPos.of(pos);
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            long flat = (long) dx * dx + (long) dz * dz;
            if (flat > (long) radius * radius || flat >= bestD) continue;
            for (int dy = 3; dy >= -4; dy--) {                 // shores near our height
                mp.set(at.x() + dx, at.y() + dy, at.z() + dz);
                if (!level.hasChunkAt(mp) || !standable(mp)) continue;
                long d = flat + (long) dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = new BlockPos(mp.getX(), mp.getY(), mp.getZ());
                }
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Solid, dry block with two free, dry blocks above it. */
    private boolean standable(net.minecraft.core.BlockPos p) {
        var floor = level.getBlockState(p);
        if (floor.getCollisionShape(level, p).isEmpty() || !floor.getFluidState().isEmpty()) return false;
        for (int up = 1; up <= 2; up++) {
            net.minecraft.core.BlockPos q = p.above(up);
            var s = level.getBlockState(q);
            if (!s.getCollisionShape(level, q).isEmpty() || !s.getFluidState().isEmpty()) return false;
        }
        return true;
    }
    @Override public long timeOfDay() { return Math.floorMod(dayTime, 24000L); }
    @Override public long day() { return Math.floorDiv(dayTime, 24000L); }
    @Override public Set<UUID> onlinePlayers() { return online; }

    @Override
    public List<ItemView> inventory() {
        if (inventory == null) {
            List<ItemView> out = new ArrayList<>();
            for (int slot = 0; slot < 36; slot++) {
                ItemStack st = player.getInventory().getItem(slot);
                if (!st.isEmpty()) out.add(new ItemView(slot, BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), food(st)));
            }
            inventory = List.copyOf(out);
        }
        return inventory;
    }

    private static Food food(ItemStack st) {
        FoodProperties fp = st.get(DataComponents.FOOD);
        if (fp == null) return null;
        boolean harmful = fp.effects().stream()
                .anyMatch(e -> e.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
        // an instant effect from food only fires if its duration is at least 1 tick — Farm & Charm's
        // nettle tea cup gives Instant Health for 0 ticks, which never heals (FIXLIST #5)
        boolean heals = fp.effects().stream().anyMatch(e -> e.effect().getDuration() > 0
                && (e.effect().is(MobEffects.HEAL) || e.effect().is(MobEffects.REGENERATION) || e.effect().is(MobEffects.ABSORPTION)));
        return new Food(fp.nutrition(), fp.saturation(), fp.canAlwaysEat(), harmful, heals);
    }

    @Override
    public Optional<Damage> recentDamage() {
        DamageSource src = player.getLastDamageSource();       // the client keeps it ~2s
        if (src == null) return Optional.empty();
        String type = src.typeHolder().unwrapKey().map(k -> k.location().toString()).orElse("unknown");
        Entity by = src.getEntity();
        return Optional.of(new Damage(type, by == null ? null : by.getName().getString(),
                by == null ? null : new Vec3(by.getX(), by.getY(), by.getZ())));
    }

    @Override
    public List<EntityView> nearby() {
        if (nearby == null) {
            List<EntityView> out = new ArrayList<>();
            for (Entity e : level.entitiesForRendering()) {
                if (e == player || !(e instanceof LivingEntity living) || e instanceof ArmorStand || !living.isAlive()) continue;
                if (e.distanceToSqr(player) > NEARBY * NEARBY) continue;
                EntityView.Kind kind = e instanceof Player ? EntityView.Kind.PLAYER
                        : e instanceof Enemy ? EntityView.Kind.HOSTILE : EntityView.Kind.PASSIVE;
                out.add(new EntityView(e.getId(), e.getUUID(), e.getName().getString(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), kind, new Vec3(e.getX(), e.getY(), e.getZ())));
            }
            nearby = List.copyOf(out);
        }
        return nearby;
    }

    /**
     * The surface from the client's heightmap (leaves ignored, so a forest reads as its floor):
     * water on top → WATER; lava and the usual hurting blocks → BLOCKED; otherwise LAND.
     */
    @Override
    public dev.yuliang.zymbot.core.api.Terrain terrain() {
        return new dev.yuliang.zymbot.core.api.Terrain() {
            private final net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();

            public Kind kind(int x, int z) {
                if (!level.hasChunk(x >> 4, z >> 4)) return Kind.UNLOADED;
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                var top = level.getBlockState(mp.set(x, y - 1, z));
                var fluid = top.getFluidState();
                if (fluid.is(FluidTags.WATER)) return Kind.WATER;
                if (fluid.is(FluidTags.LAVA) || HURTS.contains(top.getBlock())) return Kind.BLOCKED;
                // fences, walls, panes, bars: taller than a block — can't be jumped, whatever the heightmap says
                if (!top.getCollisionShape(level, mp).isEmpty()
                        && top.getCollisionShape(level, mp).max(net.minecraft.core.Direction.Axis.Y) > 1.0) return Kind.BLOCKED;
                return Kind.LAND;
            }

            public int height(int x, int z) {
                return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }
        };
    }

    private static final java.util.Set<net.minecraft.world.level.block.Block> HURTS = java.util.Set.of(
            net.minecraft.world.level.block.Blocks.CACTUS, net.minecraft.world.level.block.Blocks.MAGMA_BLOCK,
            net.minecraft.world.level.block.Blocks.FIRE, net.minecraft.world.level.block.Blocks.SOUL_FIRE,
            net.minecraft.world.level.block.Blocks.CAMPFIRE, net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE,
            net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH, net.minecraft.world.level.block.Blocks.POWDER_SNOW,
            net.minecraft.world.level.block.Blocks.WITHER_ROSE, net.minecraft.world.level.block.Blocks.POINTED_DRIPSTONE);

    @Override
    public String blockAt(BlockPos p) {
        net.minecraft.core.BlockPos mp = new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
        if (!level.hasChunkAt(mp)) return "unloaded";
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(mp).getBlock()).toString();
    }

    @Override
    public Optional<BlockPos> findBlock(String blockId, int radius) {
        int r = Math.min(radius, 32);
        BlockPos at = BlockPos.of(pos);
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            long d = (long) dx * dx + (long) dy * dy + (long) dz * dz;
            if (d >= bestD || d > (long) r * r) continue;
            mp.set(at.x() + dx, at.y() + dy, at.z() + dz);
            if (!level.hasChunkAt(mp)) continue;
            if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(mp).getBlock()).toString().equals(blockId)) {
                best = new BlockPos(mp.getX(), mp.getY(), mp.getZ());
                bestD = d;
            }
        }
        return Optional.ofNullable(best);
    }
}
