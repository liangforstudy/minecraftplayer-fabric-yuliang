package dev.yuliang.zymbot.fabric;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.PathProvider;
import dev.yuliang.zymbot.core.api.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ClickType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The brain's hands, done with the real client. Each call is one small action on this tick. */
final class FabricHands implements Hands {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    private final Minecraft mc;
    private final PathProvider paths;
    private boolean holdingKeys;                               // so our own presses don't look human

    FabricHands(Minecraft mc) {
        this.mc = mc;
        // Baritone is optional (PHASE1.md P1-1): its classes are only touched when it's installed
        this.paths = FabricLoader.getInstance().isModLoaded("baritone") ? BaritonePaths.create() : PathProvider.NONE;
        LOG.info("[zymbot] pathfinder: {}", paths.name());
    }

    @Override
    public void respawn() {
        if (mc.player != null) mc.player.respawn();
    }

    @Override
    public void notifyLocal(String message) {
        LOG.info("[zymbot] {}", message);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§7[zymbot]§r " + message), false);
    }

    @Override public PathProvider paths() { return paths; }

    @Override
    public void lookAt(Vec3 p) {
        if (mc.player != null) mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z()));
    }

    @Override
    public void steerToward(Vec3 p, double toleranceDegrees) {
        if (mc.player == null) return;
        double dx = p.x() - mc.player.getX(), dz = p.z() - mc.player.getZ();
        float want = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);          // Minecraft's yaw convention
        float off = net.minecraft.util.Mth.wrapDegrees(want - mc.player.getYRot());
        if (Math.abs(off) > toleranceDegrees) mc.player.setYRot(mc.player.getYRot() + off);
    }

    @Override
    public void selectSlot(int hotbarSlot) {
        if (mc.player != null && hotbarSlot >= 0 && hotbarSlot < 9) mc.player.getInventory().selected = hotbarSlot;   // sent next tick
    }

    @Override
    public void swapToHotbar(int inventorySlot, int hotbarSlot) {
        if (mc.player == null || mc.gameMode == null) return;
        // the player's own inventory menu numbers slots 9–35 the same as the inventory does
        mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, inventorySlot, hotbarSlot, ClickType.SWAP, mc.player);
    }

    /**
     * Start using the held item directly (not via a click, so it never opens a chest or door it
     * happens to be facing), and hold the key so the client doesn't cancel the bite.
     */
    @Override
    public void holdUse(boolean down) {
        mc.options.keyUse.setDown(down);
        // no automatic second bite in the tick between "done" and our release
        if (down) ((dev.yuliang.zymbot.fabric.mixin.MinecraftAccessor) mc).zymbot$setRightClickDelay(4);
        if (down && mc.player != null && mc.gameMode != null && !mc.player.isUsingItem()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void holdKeys(boolean forward, boolean jump) {
        mc.options.keyUp.setDown(forward);
        mc.options.keyJump.setDown(jump);
        holdingKeys = forward || jump;
    }

    /** The bot itself is holding movement keys right now. */
    boolean holdingKeys() { return holdingKeys; }

    @Override
    public void attack(int entityId) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        Entity e = mc.level.getEntity(entityId);
        if (e == null) return;
        mc.gameMode.attack(mc.player, e);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    @Override
    public void useOn(dev.yuliang.zymbot.core.api.BlockPos b) {
        if (mc.player == null || mc.gameMode == null) return;
        var pos = new net.minecraft.core.BlockPos(b.x(), b.y(), b.z());
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),
                net.minecraft.core.Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    @Override
    public void chat(String message) {
        if (mc.getConnection() != null) mc.getConnection().sendChat(message);
    }

    @Override
    public void command(String command) {
        if (mc.getConnection() != null) mc.getConnection().sendCommand(command);
    }
}
