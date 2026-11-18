package io.github.liangforstudy.zymbot.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.liangforstudy.zymbot.core.Bot;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Zymbot's own freecam (issue #9). The camera leaves the player and flies on the movement keys (jump up,
 * sneak down, sprint faster), turned by the mouse; the player stays put, so the bot and Baritone keep
 * control of it. Clicks go to the freecam, never to the player:
 * <ul>
 *   <li>left-click a block: Baritone walks the player there (breaking and placing as {@code goto} allows);</li>
 *   <li>left-click a container (chest, barrel, shulker...) or an entity with one (chest minecart, donkey):
 *       walk there and open it (Chest Tracker records it as usual);</li>
 *   <li>shift + left-click a container: what Chest Tracker has recorded there, if its API is installed
 *       (read-only: Zymbot never writes to Chest Tracker);</li>
 *   <li>right-click: cancel the walk.</li>
 * </ul>
 * Toggled by the Freecam key (Options → Controls, unbound by default) or {@code /zbot freecam}.
 * The camera is an armor stand that is never added to the world, so nothing is sent for it.
 */
final class Freecam {
    private static final double SPEED = 1.0, SPRINT = 3.0, REACH = 256;

    private static ArmorStand camera;
    private static Object levelFor;

    private Freecam() {}

    static boolean active() { return camera != null; }

    static String toggle(Minecraft mc) {
        if (active()) {
            stop(mc);
            return "freecam off";
        }
        if (mc.player == null || mc.level == null) return "not in a world";
        camera = new ArmorStand(mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ());
        camera.setInvisible(true);
        camera.noPhysics = true;
        Vec3 eye = mc.player.getEyePosition();
        camera.setPos(eye.x, eye.y - camera.getEyeHeight(), eye.z);
        camera.setYRot(mc.player.getYRot());
        camera.setXRot(mc.player.getXRot());
        syncOld();
        levelFor = mc.level;
        mc.setCameraEntity(camera);
        return "freecam on — left-click: walk there (or open a container), shift + left-click: Chest Tracker's record, right-click: cancel";
    }

    static void stop(Minecraft mc) {
        camera = null;
        levelFor = null;
        if (mc.player != null) mc.setCameraEntity(mc.player);
    }

    /** The mouse turned: the camera turns instead of the player (mixin.FreecamTurnMixin). */
    static boolean turn(double dx, double dy) {
        if (camera == null) return false;
        camera.turn(dx, dy);
        return true;
    }

    private static void syncOld() {
        camera.xo = camera.getX();
        camera.yo = camera.getY();
        camera.zo = camera.getZ();
        camera.xOld = camera.getX();
        camera.yOld = camera.getY();
        camera.zOld = camera.getZ();
        camera.yRotO = camera.getYRot();
        camera.xRotO = camera.getXRot();
    }

    /**
     * Start of the client tick, before the game reads the keys: take the human's movement keys and clicks
     * for the camera, so the player doesn't move, attack or use. Keys the bot is holding (not physically
     * pressed) are left alone.
     */
    static void beforeTick(Minecraft mc) {
        if (camera == null) return;
        if (mc.player == null || mc.level == null || mc.level != levelFor) {
            stop(mc);
            return;
        }
        if (mc.screen != null) return;                      // chat, a chest: the camera holds still
        var o = mc.options;
        boolean fwd = take(mc, o.keyUp), back = take(mc, o.keyDown), left = take(mc, o.keyLeft), right = take(mc, o.keyRight);
        boolean up = take(mc, o.keyJump), down = take(mc, o.keyShift), fast = take(mc, o.keySprint);
        syncOld();
        double speed = fast ? SPRINT : SPEED;
        double f = (fwd ? 1 : 0) - (back ? 1 : 0), s = (left ? 1 : 0) - (right ? 1 : 0), v = (up ? 1 : 0) - (down ? 1 : 0);
        if (f != 0 || s != 0 || v != 0) {
            double yaw = Math.toRadians(camera.getYRot());
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            double len = Math.max(1, Math.sqrt(f * f + s * s));
            double dx = (-sin * f + cos * s) / len * speed, dz = (cos * f + sin * s) / len * speed;
            camera.setPos(camera.getX() + dx, camera.getY() + v * speed, camera.getZ() + dz);
        }
        while (o.keyAttack.consumeClick()) leftClick(mc);
        while (o.keyUse.consumeClick()) rightClick(mc);
        if (physicallyDown(mc, o.keyAttack)) o.keyAttack.setDown(false);
        if (physicallyDown(mc, o.keyUse)) o.keyUse.setDown(false);
    }

    /** Whether the human holds this key; if so, it's the camera's, and the game doesn't see it. */
    private static boolean take(Minecraft mc, KeyMapping key) {
        if (!physicallyDown(mc, key)) return false;
        key.setDown(false);
        return true;
    }

    private static boolean physicallyDown(Minecraft mc, KeyMapping key) {
        //? if >=1.21.11 {
        /*InputConstants.Key bound = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(key);
        if (bound.getType() != InputConstants.Type.KEYSYM) return key.isDown();
        return InputConstants.isKeyDown(mc.getWindow(), bound.getValue());
        *///?} else {
        InputConstants.Key bound = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(key);
        if (bound.getType() != InputConstants.Type.KEYSYM) return key.isDown();
        return InputConstants.isKeyDown(McCompat.window(mc), bound.getValue());
        //?}
    }

    // ------------------------------------------------------------------ clicks

    private record Hit(BlockPos block, Entity entity) {}

    /** What the camera looks at, up to {@link #REACH} blocks: the nearer of a block and an entity. */
    private static Optional<Hit> look(Minecraft mc) {
        Vec3 from = camera.getEyePosition();
        Vec3 to = from.add(camera.getViewVector(1f).scale(REACH));
        BlockHitResult b = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        double best = b.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : b.getLocation().distanceToSqr(from);
        Entity nearest = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e == camera) continue;
            var at = e.getBoundingBox().inflate(0.3).clip(from, to);
            if (at.isPresent() && at.get().distanceToSqr(from) < best) {
                best = at.get().distanceToSqr(from);
                nearest = e;
            }
        }
        if (nearest != null) return Optional.of(new Hit(null, nearest));
        if (b.getType() == HitResult.Type.MISS) return Optional.empty();
        return Optional.of(new Hit(b.getBlockPos(), null));
    }

    private static void leftClick(Minecraft mc) {
        Bot bot = ZymbotClient.get().bot();
        FabricHands hands = ZymbotClient.get().hands();
        if (bot == null || hands == null) return;
        Optional<Hit> hit = look(mc);
        if (hit.isEmpty()) {
            hands.notifyLocal("freecam: nothing there within " + (int) REACH + " blocks");
            return;
        }
        if (hit.get().entity() != null) {
            Entity e = hit.get().entity();
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            String name = e.getName().getString();
            if (opensByEntity(type)) {
                boolean sneak = rideable(type);
                int id = e.getId();
                hands.notifyLocal(bot.openEntity(name, id, sneak, () -> {
                    Entity now = mc.level == null ? null : mc.level.getEntity(id);
                    return now == null || now.isRemoved() ? null
                            : new io.github.liangforstudy.zymbot.core.api.Vec3(now.getX(), now.getY() + now.getBbHeight() / 2, now.getZ());
                }));
            } else {
                BlockPos p = e.blockPosition();
                hands.notifyLocal(bot.goTo(p.getX(), p.getY(), p.getZ()));
            }
            return;
        }
        BlockPos p = hit.get().block();
        String block = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath().replace('_', ' ');
        if (isContainer(mc, p)) {
            if (McCompat.shiftDown()) {
                hands.notifyLocal(ChestTrackerLookup.describe(mc, p, block));
                return;
            }
            hands.notifyLocal(bot.openBlock(block, p.getX(), p.getY(), p.getZ()));
            return;
        }
        hands.notifyLocal(bot.goTo(p.getX(), p.getY() + 1, p.getZ()));      // stand on it
    }

    private static void rightClick(Minecraft mc) {
        Bot bot = ZymbotClient.get().bot();
        FabricHands hands = ZymbotClient.get().hands();
        if (bot == null || hands == null) return;
        hands.notifyLocal(bot.cancel());
    }

    private static boolean isContainer(Minecraft mc, BlockPos p) {
        var be = mc.level.getBlockEntity(p);
        return be instanceof net.minecraft.world.Container
                || BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).getPath().equals("ender_chest");
    }

    /** Entities with an inventory to open: chest and hopper minecarts, chest boats and rafts, and the chested animals. */
    private static boolean opensByEntity(String type) {
        return type.endsWith("chest_minecart") || type.endsWith("hopper_minecart") || type.endsWith("chest_boat")
                || type.endsWith("chest_raft") || rideable(type);
    }

    /** Donkeys, mules and llamas: plain use rides them; sneak + use opens their inventory. */
    private static boolean rideable(String type) {
        return List.of("donkey", "mule", "llama", "trader_llama").contains(type);
    }

    /**
     * Chest Tracker's read-only API (to be added by its agent): what it has recorded at a position.
     * Looked up by reflection so Zymbot runs with or without Chest Tracker, or with a version that predates it.
     * Expected: {@code io.github.liangforstudy.entitytracker.api.ChestTrackerApi.describe(String dimension, int x, int y, int z)}
     * returning {@code List<String>} (one line per stack, first line a heading), or null when nothing is recorded there.
     */
    static final class ChestTrackerLookup {
        private static Method describe;
        private static boolean looked;

        static String describe(Minecraft mc, BlockPos p, String block) {
            if (!looked) {
                looked = true;
                try {
                    Class<?> api = Class.forName("io.github.liangforstudy.entitytracker.api.ChestTrackerApi");
                    describe = api.getMethod("describe", String.class, int.class, int.class, int.class);
                } catch (ReflectiveOperationException | LinkageError e) {
                    describe = null;
                }
            }
            if (describe == null) return "Chest Tracker's API isn't installed (needs the Chest Tracker update with its read-only API)";
            try {
                String dim = McCompat.id(mc.level.dimension());
                Object out = describe.invoke(null, dim, p.getX(), p.getY(), p.getZ());
                if (!(out instanceof List<?> lines) || lines.isEmpty()) return "Chest Tracker has no record of the " + block + " at " + p.toShortString();
                StringBuilder sb = new StringBuilder("Chest Tracker, " + block + " at " + p.toShortString() + ":");
                for (Object line : lines) sb.append("\n  ").append(line);
                return sb.toString();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "Chest Tracker's API failed: " + e;
            }
        }
    }
}
