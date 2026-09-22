package dev.yuliang.zymbot.fabric;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.PathProvider;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walking via Baritone. Only loaded when Baritone is installed. Settings are set for this server
 * each time we start a path (so a human's own Baritone use in between is left alone until then):
 * walk, never sprint (R19 — sprinting costs 10× the hunger); no breaking or placing yet (MINER
 * gates would make it loop, BOT_DESIGN §2.20); short falls only (no natural regeneration).
 * <p>
 * <b>Water.</b> Baritone has no cost setting for it, only a ban: water on {@code blocksToAvoid}
 * makes it impassable (checked live, nothing cached). So paths are dry unless the task asks to
 * swim — which it only does when no dry path exists (PHASE1.md → water).
 */
final class BaritonePaths implements PathProvider {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    private final IBaritone baritone;
    private final String name;

    private BaritonePaths(IBaritone baritone, String version) {
        this.baritone = baritone;
        this.name = "Baritone " + version;
    }

    static PathProvider create() {
        try {
            String version = FabricLoader.getInstance().getModContainer("baritone")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
            return new BaritonePaths(BaritoneAPI.getProvider().getPrimaryBaritone(), version);
        } catch (Throwable t) {                              // wrong Baritone build for this game version
            LOG.warn("[zymbot] Baritone is installed but unusable: {}", t.toString());
            return new PathProvider() {
                public String name() { return "none — Baritone failed to load (" + t.getClass().getSimpleName() + ")"; }
                public boolean available() { return false; }
                public void goTo(BlockPos target, boolean ignoreY, int within, boolean swim, boolean sprint) {}
                public void follow(String playerName, boolean swim) {}
                public void stop() {}
                public boolean busy() { return false; }
            };
        }
    }

    /** The water blocks a dry path must not enter (kelp and seagrass stand in water too). */
    private static final List<Block> WATER = List.of(Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.KELP,
            Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS);

    /** We put water on the avoid list — take it off again when we stop, for a human's own Baritone. */
    private boolean bannedWater;

    private void applyServerRules(boolean swim, boolean sprint) {
        Settings s = BaritoneAPI.getSettings();
        s.allowSprint.value = sprint;
        List<Block> avoid = new ArrayList<>(s.blocksToAvoid.value);   // keep anyone else's entries
        if (swim) {
            if (bannedWater) avoid.removeAll(WATER);
            bannedWater = false;
        } else if (!avoid.containsAll(WATER)) {
            avoid.removeAll(WATER);
            avoid.addAll(WATER);
            bannedWater = true;
        }
        s.blocksToAvoid.value = avoid;
        s.sprintInWater.value = false;
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowParkour.value = false;
        s.allowInventory.value = false;
        s.allowWaterBucketFall.value = false;
        s.assumeWalkOnWater.value = false;
        s.maxFallHeightNoWater.value = 3;
    }

    @Override public String name() { return name; }
    @Override public boolean available() { return true; }

    @Override
    public void goTo(BlockPos t, boolean ignoreY, int within, boolean swim, boolean sprint) {
        applyServerRules(swim, sprint);
        Goal goal = ignoreY ? new GoalXZ(t.x(), t.z())
                : within > 0 ? new GoalNear(new net.minecraft.core.BlockPos(t.x(), t.y(), t.z()), within)
                : new GoalBlock(t.x(), t.y(), t.z());
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
    }

    @Override
    public void follow(String playerName, boolean swim) {
        applyServerRules(swim, false);
        baritone.getFollowProcess().follow(e -> e instanceof Player p && p.getGameProfile().getName().equalsIgnoreCase(playerName));
    }

    @Override
    public void stop() {
        baritone.getPathingBehavior().cancelEverything();
        if (bannedWater) {
            List<Block> avoid = new ArrayList<>(BaritoneAPI.getSettings().blocksToAvoid.value);
            avoid.removeAll(WATER);
            BaritoneAPI.getSettings().blocksToAvoid.value = avoid;
            bannedWater = false;
        }
    }

    @Override
    public boolean busy() {
        return baritone.getCustomGoalProcess().isActive() || baritone.getFollowProcess().isActive()
                || baritone.getPathingBehavior().isPathing();
    }
}
