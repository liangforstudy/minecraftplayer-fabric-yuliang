package io.github.liangforstudy.zymbot.fabric;

import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Where Minecraft's API differs between the versions we build (1.21.1, 1.21.11, 26.2), so the rest of the
 * adapter reads the same in both. Stonecutter picks the branch per version (doc/9b9t/roadmap.md §0).
 */
public final class McCompat {
    private McCompat() {}

    /** The server family this build plays (doc/9b9t/roadmap.md §0, Profiles): one per Minecraft version. */
    //? if >=1.21.11 {
    /*public static final io.github.liangforstudy.zymbot.core.Profile PROFILE = io.github.liangforstudy.zymbot.core.Profile.ANARCHY;
    *///?} else {
    public static final io.github.liangforstudy.zymbot.core.Profile PROFILE = io.github.liangforstudy.zymbot.core.Profile.ZYMCIV;
    //?}

    public static int selectedSlot(Inventory inv) {
        //? if >=1.21.11 {
        /*return inv.getSelectedSlot();
        *///?} else {
        return inv.selected;
        //?}
    }

    public static void selectSlot(Inventory inv, int slot) {
        //? if >=1.21.11 {
        /*inv.setSelectedSlot(slot);
        *///?} else {
        inv.selected = slot;
        //?}
    }

    /** A registry key's id, e.g. {@code minecraft:plains}. */
    public static String id(ResourceKey<?> key) {
        //? if >=1.21.11 {
        /*return key.identifier().toString();
        *///?} else {
        return key.location().toString();
        //?}
    }

    public static int minY(Level level) {
        //? if >=1.21.11 {
        /*return level.getMinY();
        *///?} else {
        return level.getMinBuildHeight();
        //?}
    }

    /** The highest block y, inclusive. */
    public static int maxY(Level level) {
        //? if >=1.21.11 {
        /*return level.getMaxY();
        *///?} else {
        return level.getMaxBuildHeight() - 1;
        //?}
    }

    public static BlockPos worldSpawn(Level level) {
        //? if >=1.21.11 {
        /*return level.getRespawnData().pos();
        *///?} else {
        return level.getSharedSpawnPos();
        //?}
    }

    /** Every effect a food can give, whatever its chance. */
    public static List<MobEffectInstance> foodEffects(ItemStack st) {
        //? if >=1.21.11 {
        /*var c = st.get(net.minecraft.core.component.DataComponents.CONSUMABLE);
        if (c == null) return List.of();
        return c.onConsumeEffects().stream()
                .filter(e -> e instanceof net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect)
                .flatMap(e -> ((net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect) e).effects().stream())
                .toList();
        *///?} else {
        var fp = st.get(net.minecraft.core.component.DataComponents.FOOD);
        if (fp == null) return List.of();
        return fp.effects().stream().map(e -> e.effect()).toList();
        //?}
    }

    public static Holder<MobEffect> instantHealth() {
        //? if >=1.21.11 {
        /*return MobEffects.INSTANT_HEALTH;
        *///?} else {
        return MobEffects.HEAL;
        //?}
    }

    /** Should the client swing its arm for this result? */
    public static boolean swings(InteractionResult result) {
        //? if >=1.21.11 {
        /*return result instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT;
        *///?} else {
        return result.shouldSwing();
        //?}
    }

    /** The cracking particles of a block being mined. */
    public static void crack(Minecraft mc, BlockPos pos, Direction side) {
        //? if >=1.21.11 {
        /*if (mc.level != null) mc.level.addBreakingBlockEffect(pos, side);
        *///?} else {
        mc.particleEngine.crack(pos, side);
        //?}
    }

    public static String string(CompoundTag tag, String key) {
        //? if >=1.21.11 {
        /*return tag.getStringOr(key, "");
        *///?} else {
        return tag.getString(key);
        //?}
    }

    public static UUID uuid(CompoundTag tag, String key) {
        //? if >=1.21.11 {
        /*return tag.read(key, net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        *///?} else {
        return tag.hasUUID(key) ? tag.getUUID(key) : null;
        //?}
    }

    public static String name(com.mojang.authlib.GameProfile profile) {
        //? if >=1.21.11 {
        /*return profile.name();
        *///?} else {
        return profile.getName();
        //?}
    }

    public static boolean shiftDown() {
        //? if >=1.21.11 {
        /*return Minecraft.getInstance().hasShiftDown();
        *///?} else {
        return net.minecraft.client.gui.screens.Screen.hasShiftDown();
        //?}
    }

    public static boolean controlDown() {
        //? if >=1.21.11 {
        /*return Minecraft.getInstance().hasControlDown();
        *///?} else {
        return net.minecraft.client.gui.screens.Screen.hasControlDown();
        //?}
    }

    /** The GLFW window handle. */
    public static long window(Minecraft mc) {
        //? if >=1.21.11 {
        /*return mc.getWindow().handle();
        *///?} else {
        return mc.getWindow().getWindow();
        //?}
    }

    /** A line in our own chat, not sent to the server. */
    public static void say(net.minecraft.client.player.LocalPlayer player, net.minecraft.network.chat.Component line) {
        //? if >=26.2 {
        /*player.sendSystemMessage(line);
        *///?} else {
        player.displayClientMessage(line, false);
        //?}
    }

    /** The overworld's time of day, counting up across days (26.x: the overworld clock). */
    public static long dayTime(Level level) {
        //? if >=26.2 {
        /*return level.getOverworldClockTime();
        *///?} else {
        return level.getDayTime();
        //?}
    }

    /** Open our singleplayer world to LAN on this port, with its own game mode and cheats setting. */
    public static boolean publishLan(net.minecraft.client.server.IntegratedServer s, int port) {
        //? if >=26.2 {
        /*return s.publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, s.getDefaultGameType(),
                s.getWorldData().isAllowCommands(), port);
        *///?} else {
        return s.publishServer(s.getDefaultGameType(), s.getWorldData().isAllowCommands(), port);
        //?}
    }

    /** Press a button as if clicked. */
    public static void press(net.minecraft.client.gui.components.Button b) {
        //? if >=1.21.11 {
        /*b.onPress(new net.minecraft.client.input.KeyEvent(0, 0, 0));
        *///?} else {
        b.onPress();
        //?}
    }
}
