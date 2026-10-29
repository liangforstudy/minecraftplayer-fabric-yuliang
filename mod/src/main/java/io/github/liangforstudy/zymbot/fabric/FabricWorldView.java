package io.github.liangforstudy.zymbot.fabric;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Damage;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Food;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
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
    /**
     * Hostiles and players further out: a ghast fires from up to 64, and at 32 it dropped out of sight
     * mid-fight — cover ended, the critical reflex found "nothing to run from", and it burned (Nether,
     * 2026-10-29). On anarchy servers players are as dangerous (bows, crystals), so the same (owner).
     */
    static final double NEARBY_HOSTILE = 64;

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
    private final int lives;
    private final int downedSecs;
    private final boolean beingRevived;
    private final long dayTime;
    private final Set<UUID> online;
    private final double tps;
    private final boolean inventoryScreen;
    private List<ItemView> inventory;
    private List<EntityView> nearby;

    /** civfabric dbno boss bars (literal names, not translation keys). */
    static final String BLEEDING_OUT = "Bleeding Out", BEING_REVIVED = "Being Revived";
    static final int BLEED_OUT_SECONDS = 60;

    FabricWorldView(LocalPlayer player, ClientLevel level, ClientPacketListener connection,
                    net.minecraft.client.gui.components.BossHealthOverlay bosses, int lives, boolean inventoryScreen) {
        this.player = player;
        this.inventoryScreen = inventoryScreen;
        this.lives = lives;
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
        this.selected = McCompat.selectedSlot(player.getInventory());
        this.air = player.getAirSupply();
        this.maxAir = player.getMaxAirSupply();
        this.headInWater = player.isEyeInFluid(FluidTags.WATER);
        int downed = -1;
        boolean reviving = false;
        for (var bar : ((io.github.liangforstudy.zymbot.fabric.mixin.BossHealthOverlayAccessor) bosses).zymbot$events().values()) {
            String name = bar.getName().getString();
            if (BLEEDING_OUT.equals(name)) downed = Math.round(bar.getProgress() * BLEED_OUT_SECONDS);
            else if (BEING_REVIVED.equals(name)) reviving = true;
        }
        this.downedSecs = downed;
        this.beingRevived = reviving;
        this.lastDeath = player.getLastDeathLocation()
                .map(g -> McCompat.id(g.dimension()) + " " + g.pos().toShortString()).orElse("");
        this.dayTime = McCompat.dayTime(level);
        this.tps = ZymbotClient.TPS.tps(System.nanoTime());
        this.online = connection == null ? Set.of() : Set.copyOf(connection.getOnlinePlayerIds());   // the tab list
    }

    @Override public UUID selfId() { return id; }
    @Override public String selfName() { return name; }
    @Override public Vec3 position() { return pos; }
    @Override public float health() { return health; }
    @Override public int hunger() { return hunger; }
    @Override public float saturation() { return saturation; }
    @Override public float yaw() { return player.getYRot(); }
    @Override public boolean isDead() { return dead; }
    @Override public boolean inWater() { return inWater; }
    @Override public boolean onGround() { return onGround; }
    @Override public boolean sprinting() { return sprinting; }
    @Override public boolean usingItem() { return using; }
    @Override public int selectedSlot() { return selected; }
    @Override public boolean untouchable() { return player.getAbilities().invulnerable || player.isSpectator(); }
    @Override public boolean onFire() { return player.isOnFire(); }
    @Override public boolean fireResistant() { return player.hasEffect(MobEffects.FIRE_RESISTANCE); }
    @Override public boolean waterEvaporates() { return "minecraft:the_nether".equals(McCompat.id(level.dimension())); }

    @Override
    public Optional<ItemView> fireResistancePotion() {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack st = player.getInventory().getItem(slot);
            if (!st.is(net.minecraft.world.item.Items.POTION)) continue;          // drinkable, not splash or lingering
            var contents = st.get(DataComponents.POTION_CONTENTS);
            if (contents == null) continue;
            for (var e : contents.getAllEffects()) {
                if (e.is(MobEffects.FIRE_RESISTANCE)) {
                    return Optional.of(new ItemView(slot, BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), null));
                }
            }
        }
        return Optional.empty();
    }

    @Override public String offhandItem() {
        ItemStack st = player.getOffhandItem();
        return st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }
    @Override public int air() { return air; }
    @Override public double serverTps() { return tps; }
    @Override public int maxAir() { return maxAir; }
    @Override public boolean headInWater() { return headInWater; }
    @Override public String lastDeath() { return lastDeath; }
    @Override public int lives() { return lives; }
    @Override public int downedSecondsLeft() { return downedSecs; }
    @Override public boolean beingRevived() { return beingRevived; }
    @Override public int armorPoints() { return player.getArmorValue(); }
    @Override public List<String> armor() {
        List<String> out = new ArrayList<>();
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[] {net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET}) {
            ItemStack st = player.getItemBySlot(slot);
            out.add(st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        }
        return out;
    }
    @Override public boolean attackReady() { return player.getAttackStrengthScale(0.5f) >= 0.95f; }

    @Override
    public Optional<BlockPos> worldSpawn() {
        var s = McCompat.worldSpawn(level);
        return Optional.of(new BlockPos(s.getX(), s.getY(), s.getZ()));
    }

    @Override
    public Optional<BlockPos> nearestDryLand(int radius) { return nearestDryLand(radius, p -> true); }

    @Override
    public Optional<BlockPos> nearestDryLand(int radius, java.util.function.Predicate<BlockPos> ok) {
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
                BlockPos here = new BlockPos(mp.getX(), mp.getY(), mp.getZ());
                if (!ok.test(here)) break;                          // the top of this column is unsafe
                long d = flat + (long) dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = here;
                }
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Somewhere to stand, head above water: any block with a top to stand on — full blocks, gravel,
     * slabs, panes, trapdoors, fences — that doesn't hurt, with room for a player above it. The
     * block at the feet may hold one layer of water (the owner, 2026-09-26: shallows count as
     * shore); the one at the head must be free and dry.
     */
    private boolean standable(net.minecraft.core.BlockPos p) {
        var floor = level.getBlockState(p);
        if (floor.getCollisionShape(level, p).isEmpty() || !floor.getFluidState().isEmpty()) return false;
        if (HURTS.contains(floor.getBlock())) return false;
        net.minecraft.core.BlockPos feet = p.above(), head = p.above(2);
        var f = level.getBlockState(feet);
        if (!f.getCollisionShape(level, feet).isEmpty() || f.getFluidState().is(FluidTags.LAVA)) return false;
        var h = level.getBlockState(head);
        return h.getCollisionShape(level, head).isEmpty() && h.getFluidState().isEmpty();
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
        var effects = McCompat.foodEffects(st);
        boolean harmful = effects.stream()
                .anyMatch(e -> e.getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
        // an instant effect from food only fires if its duration is at least 1 tick — Farm & Charm's
        // nettle tea cup gives Instant Health for 0 ticks, which never heals (FIXLIST #5)
        boolean heals = effects.stream().anyMatch(e -> e.getDuration() > 0
                && (e.is(McCompat.instantHealth()) || e.is(MobEffects.REGENERATION) || e.is(MobEffects.ABSORPTION)));
        return new Food(fp.nutrition(), fp.saturation(), fp.canAlwaysEat(), harmful, heals);
    }

    @Override
    public Optional<Damage> recentDamage() {
        DamageSource src = player.getLastDamageSource();       // the client keeps it ~2s
        if (src == null) return Optional.empty();
        String type = src.typeHolder().unwrapKey().map(McCompat::id).orElse("unknown");
        Entity by = src.getEntity();
        return Optional.of(new Damage(type, by == null ? null : by.getName().getString(),
                by == null ? null : new Vec3(by.getX(), by.getY(), by.getZ()), by == null ? null : by.getUUID()));
    }

    @Override
    public List<EntityView> nearby() {
        if (nearby == null) {
            List<EntityView> out = new ArrayList<>();
            for (Entity e : level.entitiesForRendering()) {
                if (e == player || !(e instanceof LivingEntity living) || e instanceof ArmorStand || !living.isAlive()) continue;
                double range = e instanceof Enemy || e instanceof Player ? NEARBY_HOSTILE : NEARBY;
                if (e.distanceToSqr(player) > range * range) continue;
                EntityView.Kind kind = e instanceof Player ? EntityView.Kind.PLAYER
                        : e instanceof Enemy ? EntityView.Kind.HOSTILE : EntityView.Kind.PASSIVE;
                out.add(new EntityView(e.getId(), e.getUUID(), e.getName().getString(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), kind, new Vec3(e.getX(), e.getY(), e.getZ())));
            }
            nearby = List.copyOf(out);
        }
        return nearby;
    }

    @Override
    public List<io.github.liangforstudy.zymbot.core.api.ProjectileView> projectiles() {
        List<io.github.liangforstudy.zymbot.core.api.ProjectileView> out = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.projectile.Projectile p) || e.isRemoved()) continue;
            if (e.distanceToSqr(player) > NEARBY_HOSTILE * NEARBY_HOSTILE) continue;
            var v = e.getDeltaMovement();
            if (v.lengthSqr() < 1e-6) v = e.position().subtract(e.xo, e.yo, e.zo);   // not synced yet: what it moved
            Entity owner = p.getOwner();
            out.add(new io.github.liangforstudy.zymbot.core.api.ProjectileView(e.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                    new Vec3(e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ()), new Vec3(v.x, v.y, v.z),
                    owner == null ? -1 : owner.getId()));
        }
        return out;
    }

    @Override
    public List<EntityView> playersInView() {
        List<EntityView> out = new ArrayList<>();
        for (Player p : level.players()) {                      // all the server tracks for us: no distance cap
            if (p == player || !p.isAlive()) continue;
            out.add(new EntityView(p.getId(), p.getUUID(), p.getName().getString(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(p.getType()).toString(), EntityView.Kind.PLAYER,
                    new Vec3(p.getX(), p.getY(), p.getZ())));
        }
        return out;
    }

    /**
     * The surface, from the client's MOTION_BLOCKING heightmap — the only surface map the server
     * sends clients (NO_LEAVES is server-only: on a client it's empty, and every column read as flat
     * land at the bottom of the world until 0.1.22). Leaves are stepped down through, so a forest
     * reads as its floor; a trunk stays a tall column. Water on top → WATER; lava, the usual hurting
     * blocks and anything taller than a block → BLOCKED; otherwise LAND.
     */
    @Override
    public io.github.liangforstudy.zymbot.core.api.Terrain terrain() {
        return new io.github.liangforstudy.zymbot.core.api.Terrain() {
            private final net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();

            /** Feet height on this column: the heightmap, then down through any leaves. */
            private int surface(int x, int z) {
                int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                int floor = McCompat.minY(level);
                while (y > floor && level.getBlockState(mp.set(x, y - 1, z)).is(net.minecraft.tags.BlockTags.LEAVES)) y--;
                return y;
            }

            public Kind kind(int x, int z) {
                if (!level.hasChunk(x >> 4, z >> 4)) return Kind.UNLOADED;
                int y = surface(x, z);
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
                return surface(x, z);
            }
        };
    }

    private static final java.util.Set<net.minecraft.world.level.block.Block> HURTS = java.util.Set.of(
            net.minecraft.world.level.block.Blocks.CACTUS, net.minecraft.world.level.block.Blocks.MAGMA_BLOCK,
            net.minecraft.world.level.block.Blocks.FIRE, net.minecraft.world.level.block.Blocks.SOUL_FIRE,
            net.minecraft.world.level.block.Blocks.CAMPFIRE, net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE,
            net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH, net.minecraft.world.level.block.Blocks.POWDER_SNOW,
            net.minecraft.world.level.block.Blocks.WITHER_ROSE, net.minecraft.world.level.block.Blocks.POINTED_DRIPSTONE);

    /** Reach from the eyes to the block's centre, and nothing else's outline in the way. */
    @Override
    public boolean canReach(BlockPos b) {
        net.minecraft.core.BlockPos mp = new net.minecraft.core.BlockPos(b.x(), b.y(), b.z());
        if (!level.hasChunkAt(mp)) return false;
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 centre = net.minecraft.world.phys.Vec3.atCenterOf(mp);
        if (eye.distanceTo(centre) > player.blockInteractionRange()) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, centre,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(mp);
    }

    /** Arrows and fireballs stop on a block's collision shape, so that's what's checked (not its outline). */
    @Override
    public boolean clearShot(Vec3 from, Vec3 to) {
        var hit = level.clip(new net.minecraft.world.level.ClipContext(
                new net.minecraft.world.phys.Vec3(from.x(), from.y(), from.z()), new net.minecraft.world.phys.Vec3(to.x(), to.y(), to.z()),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    @Override
    public Optional<BlockPos> inTheWay(BlockPos b) {
        net.minecraft.core.BlockPos mp = new net.minecraft.core.BlockPos(b.x(), b.y(), b.z());
        if (!level.hasChunkAt(mp)) return Optional.empty();
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 centre = net.minecraft.world.phys.Vec3.atCenterOf(mp);
        if (eye.distanceTo(centre) > player.blockInteractionRange()) return Optional.empty();
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, centre,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || hit.getBlockPos().equals(mp)) return Optional.empty();
        var h = hit.getBlockPos();
        return Optional.of(new BlockPos(h.getX(), h.getY(), h.getZ()));
    }

    /** The hotbar item that digs this block fastest, if faster than a hand; a correct tool (drops) wins ties. */
    @Override
    public int bestToolSlot(BlockPos b) {
        var state = level.getBlockState(new net.minecraft.core.BlockPos(b.x(), b.y(), b.z()));
        int best = -1;
        float bestSpeed = 1.0f;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack s = player.getInventory().getItem(slot);
            if (s.isEmpty()) continue;
            float speed = s.getDestroySpeed(state) + (s.isCorrectToolForDrops(state) ? 0.01f : 0);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = slot;
            }
        }
        return best;
    }

    @Override
    public List<io.github.liangforstudy.zymbot.core.api.DroppedItem> droppedItems() {
        List<io.github.liangforstudy.zymbot.core.api.DroppedItem> out = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity item) || !item.isAlive()) continue;
            if (e.distanceToSqr(player) > NEARBY * NEARBY) continue;
            ItemStack s = item.getItem();
            out.add(new io.github.liangforstudy.zymbot.core.api.DroppedItem(e.getId(), BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),
                    s.getCount(), new Vec3(e.getX(), e.getY(), e.getZ())));
        }
        return out;
    }

    /**
     * civfabric's GraveBlockEntity isn't on our classpath, so its data is read by key: the server's
     * update tag for a grave carries {@code owner} (name) and {@code owner_id} (UUID) — it renders its
     * owner's head (civfabric 0.4.2, GraveBlockEntity.getUpdateTag) — and the client's copy loads
     * both (loadAdditional), so saving the client copy gives them back. Empty until that data arrives.
     */
    @Override
    public Optional<io.github.liangforstudy.zymbot.core.api.GraveOwner> graveOwner(BlockPos p) {
        var mp = new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
        if (!level.hasChunkAt(mp)) return Optional.empty();
        var be = level.getBlockEntity(mp);
        if (be == null) return Optional.empty();
        try {
            var tag = be.saveWithoutMetadata(level.registryAccess());
            String owner = McCompat.string(tag, "owner");
            UUID ownerId = McCompat.uuid(tag, "owner_id");
            if (owner.isEmpty() && ownerId == null) return Optional.empty();
            return Optional.of(new io.github.liangforstudy.zymbot.core.api.GraveOwner(owner, ownerId));
        } catch (RuntimeException e) {                          // someone else's block entity: no owner to read
            return Optional.empty();
        }
    }

    /**
     * The open container's own filled slots (not our inventory's). Not open (yet) until its contents
     * have arrived: the menu's state id is 0 until the server's first full contents packet, so an
     * empty-looking grave the tick it opens isn't read as "already empty".
     */
    @Override
    public int openContainerFilled() {
        var menu = player.containerMenu;
        if (menu == null || menu == player.inventoryMenu || menu.getStateId() == 0) return -1;
        int filled = 0;
        for (var slot : menu.slots) {
            if (slot.container != player.getInventory() && slot.hasItem()) filled++;
        }
        return filled;
    }

    /**
     * The inventory screen (its menu is always there; the screen says whether it's open) or a crafting
     * table's, once its contents have arrived (state id 0 until the server's first full packet).
     */
    @Override
    public Optional<io.github.liangforstudy.zymbot.core.api.MenuView> menu() {
        var menu = player.containerMenu;
        io.github.liangforstudy.zymbot.core.api.MenuView.Kind kind;
        if (menu == player.inventoryMenu) {
            if (!inventoryScreen) return Optional.empty();
            kind = io.github.liangforstudy.zymbot.core.api.MenuView.Kind.INVENTORY;
        } else if (menu.getStateId() == 0) {
            return Optional.empty();
        } else {
            kind = menu instanceof net.minecraft.world.inventory.CraftingMenu
                    ? io.github.liangforstudy.zymbot.core.api.MenuView.Kind.CRAFTING_TABLE : io.github.liangforstudy.zymbot.core.api.MenuView.Kind.OTHER;
        }
        List<io.github.liangforstudy.zymbot.core.api.MenuView.Slot> slots = new ArrayList<>();
        for (var slot : menu.slots) {
            ItemStack st = slot.getItem();
            String id = st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            io.github.liangforstudy.zymbot.core.api.MenuView.Role role;
            int ref = -1;
            if (slot instanceof net.minecraft.world.inventory.ResultSlot) {
                role = io.github.liangforstudy.zymbot.core.api.MenuView.Role.RESULT;
            } else if (slot.container instanceof net.minecraft.world.inventory.CraftingContainer) {
                role = io.github.liangforstudy.zymbot.core.api.MenuView.Role.GRID;
                ref = slot.getContainerSlot();
            } else if (slot.container == player.getInventory() && slot.getContainerSlot() < 36) {
                role = io.github.liangforstudy.zymbot.core.api.MenuView.Role.PLAYER;
                ref = slot.getContainerSlot();
            } else {
                role = io.github.liangforstudy.zymbot.core.api.MenuView.Role.OTHER;
            }
            slots.add(new io.github.liangforstudy.zymbot.core.api.MenuView.Slot(slot.index, role, ref, id, st.getCount()));
        }
        ItemStack carried = menu.getCarried();
        return Optional.of(new io.github.liangforstudy.zymbot.core.api.MenuView(kind, List.copyOf(slots),
                carried.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(carried.getItem()).toString(), carried.getCount()));
    }

    /** A solid top, and nothing that opens when clicked (a table, a chest, a door). */
    @Override
    public boolean sturdy(BlockPos p) {
        var mp = new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
        if (!level.hasChunkAt(mp)) return false;
        var st = level.getBlockState(mp);
        return st.isFaceSturdy(level, mp, net.minecraft.core.Direction.UP) && level.getBlockEntity(mp) == null
                && st.getMenuProvider(level, mp) == null && !HURTS.contains(st.getBlock());
    }

    @Override
    public boolean replaceable(BlockPos p) {
        var mp = new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
        if (!level.hasChunkAt(mp)) return false;
        var st = level.getBlockState(mp);
        return st.canBeReplaced() && st.getFluidState().isEmpty();
    }

    @Override
    public String blockAt(BlockPos p) {
        net.minecraft.core.BlockPos mp = new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
        if (!level.hasChunkAt(mp)) return "unloaded";
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(mp).getBlock()).toString();
    }

    @Override
    public boolean isWater(BlockPos p) {
        net.minecraft.core.BlockPos mp = new net.minecraft.core.BlockPos(p.x(), p.y(), p.z());
        return level.hasChunkAt(mp) && level.getFluidState(mp).is(net.minecraft.tags.FluidTags.WATER);
    }

    @Override
    public String biome() {
        return level.getBiome(player.blockPosition()).unwrapKey().map(McCompat::id).orElse("unknown");
    }

    /** Per block type: does the survey want it? Asked once per type, not once per block. */
    private static final java.util.Map<net.minecraft.world.level.block.Block, Boolean> WANTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Game thread, kept cheap: walks the loaded chunk sections in range, skips any section whose
     * palette holds nothing the survey wants (most of them — stone, dirt, air), and only reads the
     * blocks of the rest. Copies positions + ids, nothing else.
     */
    @Override
    public List<io.github.liangforstudy.zymbot.core.api.BlockHit> scanBlocks(int radius, int below, int above, int max,
                                                                 java.util.function.Predicate<String> wanted) {
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> want = st -> WANTED.computeIfAbsent(
                st.getBlock(), b -> wanted.test(BuiltInRegistries.BLOCK.getKey(b).toString()));
        BlockPos at = BlockPos.of(pos);
        int minY = Math.max(McCompat.minY(level), at.y() - below), maxY = Math.min(McCompat.maxY(level), at.y() + above);
        List<io.github.liangforstudy.zymbot.core.api.BlockHit> out = new ArrayList<>();
        for (int cx = (at.x() - radius) >> 4; cx <= (at.x() + radius) >> 4; cx++) {
            for (int cz = (at.z() - radius) >> 4; cz <= (at.z() + radius) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                var chunk = level.getChunk(cx, cz);
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    int idx = level.getSectionIndexFromSectionY(sy);
                    if (idx < 0 || idx >= chunk.getSections().length) continue;
                    var section = chunk.getSections()[idx];
                    if (section.hasOnlyAir() || !section.maybeHas(want)) continue;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = (sy << 4) + ly;
                        if (y < minY || y > maxY) continue;
                        for (int lx = 0; lx < 16; lx++) {
                            int x = (cx << 4) + lx;
                            if (Math.abs(x - at.x()) > radius) continue;
                            for (int lz = 0; lz < 16; lz++) {
                                int z = (cz << 4) + lz;
                                if (Math.abs(z - at.z()) > radius) continue;
                                var st = section.getBlockState(lx, ly, lz);
                                if (!want.test(st)) continue;
                                out.add(new io.github.liangforstudy.zymbot.core.api.BlockHit(new BlockPos(x, y, z),
                                        BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString()));
                                if (out.size() >= max) return out;
                            }
                        }
                    }
                }
            }
        }
        return out;
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
