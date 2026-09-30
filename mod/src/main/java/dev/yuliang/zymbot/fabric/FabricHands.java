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
    private boolean moving, sneaking;
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
        moving = forward || jump;
        holdingKeys = moving || sneaking;
    }

    @Override
    public void holdSneak(boolean down) {
        mc.options.keyShift.setDown(down);
        sneaking = down;
        holdingKeys = moving || sneaking;
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

    /** Shift-click each filled slot of the open container (not our own inventory's) — what fits moves over. */
    @Override
    public void takeAllFromContainer() {
        if (mc.player == null || mc.gameMode == null) return;
        var menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) return;
        for (var slot : menu.slots) {
            if (slot.container == mc.player.getInventory() || !slot.hasItem()) continue;
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
        }
    }

    /** Close the container as Esc does: tells the server, and clears the screen. */
    @Override
    public void closeContainer() {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) mc.player.closeContainer();
    }

    // ------------------------------------------------------------------ digging

    /** Most a wrist turns in one tick (degrees); a big turn takes a few ticks, easing in at the end. */
    static final float MAX_TURN_PER_TICK = 25f;
    /** Share of the remaining angle turned per tick, before the cap. */
    static final float TURN_SHARE = 0.4f;
    private boolean digging;                                   // a destroy is in progress on our behalf
    private boolean minedThisTick;
    private final java.util.Random jitter = new java.util.Random();

    /**
     * One tick of a held left mouse button on this block, as vanilla's Minecraft.startAttack /
     * continueAttack do it: turn toward the block, pick along the view (the crosshair), and only if
     * it's on this block call MultiPlayerGameMode.startDestroyBlock (first tick) then
     * continueDestroyBlock, with the crack particles and arm swing. No packets of our own.
     */
    @Override
    public boolean mine(dev.yuliang.zymbot.core.api.BlockPos b) {
        if (mc.player == null || mc.gameMode == null) return false;
        minedThisTick = true;
        var pos = new net.minecraft.core.BlockPos(b.x(), b.y(), b.z());
        turnToward(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        var hit = mc.player.pick(mc.player.blockInteractionRange(), 1.0f, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult bh) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || !bh.getBlockPos().equals(pos) || mc.player.isUsingItem()) {
            stopMining();                                      // crosshair not on it (yet): nothing held
            return false;
        }
        BotDigging.active = true;
        boolean first = !digging;
        digging = true;
        boolean hitting = first ? mc.gameMode.startDestroyBlock(pos, bh.getDirection())
                : mc.gameMode.continueDestroyBlock(pos, bh.getDirection());
        if (hitting && !first) mc.particleEngine.crack(pos, bh.getDirection());
        if (first || hitting) mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    @Override
    public void stopMining() {
        if (digging && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        digging = false;
        BotDigging.active = false;
    }

    /**
     * After the brain's tick: if nothing called {@link #mine} this tick (task done, cancelled, the
     * brain halted, paused, died...), let go — attack is never left held.
     */
    void endTick() {
        if (!minedThisTick && (digging || BotDigging.active)) stopMining();
        minedThisTick = false;
    }

    /** A wrist, not a snap: part of the way per tick, capped, with a hair of wobble. */
    private void turnToward(net.minecraft.world.phys.Vec3 p) {
        var eye = mc.player.getEyePosition();
        double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
        float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float dYaw = net.minecraft.util.Mth.wrapDegrees(wantYaw - mc.player.getYRot());
        float dPitch = wantPitch - mc.player.getXRot();
        mc.player.setYRot(mc.player.getYRot() + step(dYaw));
        mc.player.setXRot(net.minecraft.util.Mth.clamp(mc.player.getXRot() + step(dPitch), -90f, 90f));
    }

    private float step(float off) {
        float a = Math.abs(off);
        if (a < 0.5f) return off;                              // close enough: settle
        float s = Math.min(MAX_TURN_PER_TICK, Math.max(Math.min(a, 2f), a * TURN_SHARE));
        s += (jitter.nextFloat() - 0.5f) * 0.6f;               // ±0.3°: never the same curve twice
        return Math.copySign(Math.min(a, Math.max(0f, s)), off);
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
