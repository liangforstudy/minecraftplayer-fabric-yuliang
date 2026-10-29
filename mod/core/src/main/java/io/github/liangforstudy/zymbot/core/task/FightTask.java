package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.body.Combat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Hit back one mob: the best sword or axe in the hotbar, close in when it's out of reach, and
 * swing only when the weapon has recharged (a spammed swing does a fraction of the damage). Done
 * when it's gone from sight — dead, or {@link RetreatTask#DISTANCE} away.
 */
public final class FightTask implements Task {
    /** A player's reach to a mob, a little short of the game's 3 so the swing lands. */
    static final double REACH = 2.8;
    /** Mobs stand on different blocks: this far up or down still counts as in reach. */
    static final double REACH_UP_DOWN = 2;
    /** Close in again once it has moved this far from where the walk was aimed. */
    static final double REAIM_BLOCKS = 2;
    static final int GIVE_UP_TICKS = 30 * 20;
    /** How often the fight asks again whether it's still worth it (more mobs, less health). */
    public static final int RECHECK_TICKS = 20;

    private final Hands hands;
    private final UUID target;
    private final Consumer<String> report;
    private final Predicate<WorldView> stillWorthIt;
    private String who;
    private WalkTask walk;
    private Vec3 aimedAt;
    private boolean armed;
    private int ticks;
    private int swings;
    private String failure = "";

    /**
     * @param stillWorthIt asked every {@link #RECHECK_TICKS}: false ends the fight (DONE), so the
     *                     reflex can decide again — a siege grows while we swing at the first zombie
     */
    public FightTask(Hands hands, EntityView target, Consumer<String> report, Predicate<WorldView> stillWorthIt) {
        this.hands = hands;
        this.target = target.uuid();
        this.who = target.name();
        this.report = report;
        this.stillWorthIt = stillWorthIt;
    }

    @Override
    public Status tick(WorldView world) {
        Optional<EntityView> t = world.nearby().stream().filter(e -> target.equals(e.uuid())).findFirst();
        Vec3 me = world.position();
        if (t.isEmpty() || t.get().pos().horizontalDistance(me) >= RetreatTask.DISTANCE) {
            cancel();
            return Status.DONE;
        }
        if (ticks % RECHECK_TICKS == RECHECK_TICKS - 1 && !stillWorthIt.test(world)) {
            cancel();
            return Status.DONE;
        }
        if (++ticks > GIVE_UP_TICKS) {
            cancel();
            failure = "still fighting " + who + " after " + GIVE_UP_TICKS / 20 + " s (" + swings + " swings)";
            return Status.FAILED;
        }
        EntityView mob = t.get();
        who = mob.name();
        if (!armed) {
            armed = true;
            Combat.bestWeapon(world.inventory()).ifPresent(hands::selectSlot);
        }
        boolean inReach = mob.pos().horizontalDistance(me) <= REACH && Math.abs(mob.pos().y() - me.y()) <= REACH_UP_DOWN;
        if (!inReach) {
            if (walk == null || mob.pos().horizontalDistance(aimedAt) >= REAIM_BLOCKS) {
                if (walk != null) walk.cancel();
                walk = new WalkTask(hands.paths(), new BlockPos((int) Math.floor(mob.pos().x()),
                        (int) Math.floor(mob.pos().y()), (int) Math.floor(mob.pos().z())), false, 1, report).sprinting();
                aimedAt = mob.pos();
            }
            if (walk.tick(world) == Status.FAILED) {
                failure = "can't get to " + who + ": " + walk.failure();
                walk = null;
                return Status.FAILED;
            }
            return Status.RUNNING;
        }
        if (walk != null) {
            walk.cancel();
            walk = null;
        }
        hands.lookAt(new Vec3(mob.pos().x(), mob.pos().y() + 0.9, mob.pos().z()));   // its body, not its feet
        if (world.attackReady()) {
            hands.attack(mob.id());
            swings++;
        }
        return Status.RUNNING;
    }

    @Override public void cancel() { if (walk != null) walk.cancel(); walk = null; }
    @Override public String failure() { return failure; }
    @Override public String describe() { return "fighting " + who + (swings > 0 ? " (" + swings + " swings)" : ""); }
}
