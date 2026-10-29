package io.github.liangforstudy.zymbot.core.task;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Dig in when there's nowhere to take cover and running doesn't work (owner's idea, 2026-10-29): dig
 * the {@link #DEPTH} blocks under our feet, stand in the hole until nothing is after us for
 * {@link #CLEAR_TICKS}, then tower back out — jump and put the dug blocks under ourselves, one a jump.
 * A 1-wide hole 3 deep is out of a ghast's or a skeleton's sight unless it's right overhead, and a
 * zombie at the rim can't reach down. On open netherrack a ghast burned Bluetails to death where it
 * stood (Nether, 2026-10-29).
 * <p>Never digs into lava, water or a drop, or through bedrock or obsidian: {@link #canDigIn} checks
 * on the first tick, and it fails there so the next fallback (backing off) runs instead.
 */
public final class HideTask implements Task {
    public static final int DEPTH = 3;
    /** Out only once nothing has been after us this long. */
    static final int CLEAR_TICKS = 5 * 20;
    /** Longest wait in the hole (then out anyway, to look again). */
    static final int MAX_WAIT_TICKS = 3 * 60 * 20;
    /** Longest the digging may take (bare-handed netherrack is under a second a block; dirt ~1 s). */
    static final int DIG_TICKS = 20 * 20;
    /** Longest the climb out may take. */
    static final int CLIMB_TICKS = 20 * 20;
    /** A fireball's knock-back: wait this long to land before digging in. */
    static final int LAND_TICKS = 3 * 20;
    /** Further than this off the hole's middle, we were knocked off it: dig a new one here. */
    static final double OFF_THE_HOLE = 0.7;
    /** The hotbar slot the blocks for the climb go to when they're in the main inventory. */
    static final int BLOCK_SLOT = 8;

    private enum Stage { DIG, WAIT, CLIMB }

    private final Hands hands;
    private final Predicate<WorldView> danger;
    private final java.util.function.Consumer<Boolean> hiding;
    private java.util.function.BiConsumer<String, String> note = (what, why) -> {};

    /** Each stage change goes here (the decision log). */
    public HideTask noting(java.util.function.BiConsumer<String, String> note) {
        this.note = note;
        return this;
    }
    private BlockPos top;                     // the block we stood on: the first one dug (set on the first tick)
    private final List<String> dugIds = new ArrayList<>();
    private Stage stage = Stage.DIG;
    private int ticks;
    private int clearFor;
    private boolean toolPicked;
    private int landing;
    /**
     * Below the surface: only from here is the hole "hiding" (fire no longer steps us off, critical
     * health leaves us be). Told at once, it stood in the fire through the whole dig and then burned
     * 8 s in the hole, 20 → 2.9 (2026-10-29); now the hazard reflex steps it off the fire first.
     */
    private boolean down;
    private BlockPos placingOn;               // the climb: the block under us when the jump started
    private String failure = "";

    /**
     * @param danger true while something is still after us (shooters in range, mobs close): the
     *               wait in the hole lasts until this has been false for {@link #CLEAR_TICKS}
     */
    public HideTask(Hands hands, Predicate<WorldView> danger) {
        this(hands, danger, h -> {});
    }

    /**
     * @param hiding told true once below the surface (digging and waiting) and false once out or given up, so
     *               the critical-health reflex leaves the hole alone: it took over from a dig-in, ran its
     *               own cover, ended when the ghast drifted off, and the climb out never came (2026-10-29)
     */
    public HideTask(Hands hands, Predicate<WorldView> danger, java.util.function.Consumer<Boolean> hiding) {
        this.hands = hands;
        this.danger = danger;
        this.hiding = hiding;
    }

    /** Why digging in here is unsafe, or empty when it's fine. */
    public static Optional<String> canDigIn(WorldView world) {
        BlockPos f = floor(world.position());
        for (int d = 0; d < DEPTH; d++) {
            BlockPos b = new BlockPos(f.x(), f.y() - d, f.z());
            String id = world.blockAt(b);
            // air: the hole half dug before a fireball cut in
            if (!id.endsWith(":air") && !diggable(id)) return Optional.of("can't dig " + id.replace("minecraft:", "") + " under us");
            for (int[] s : SIDES) {                                  // lava beside the hole pours in
                String side = world.blockAt(new BlockPos(b.x() + s[0], b.y(), b.z() + s[1]));
                if (side.contains("lava") || side.contains("water")) return Optional.of(side.replace("minecraft:", "") + " beside the hole");
            }
        }
        BlockPos bottom = new BlockPos(f.x(), f.y() - DEPTH, f.z());
        String below = world.blockAt(bottom);
        if (below.contains("lava") || !world.sturdy(bottom)) return Optional.of("nothing solid " + DEPTH + " down");
        return Optional.empty();
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    static boolean diggable(String id) {
        if (id.equals("unloaded") || id.endsWith(":air") || id.contains("lava") || id.contains("water") || id.contains("fire")) return false;
        return !(id.contains("bedrock") || id.contains("obsidian") || id.contains("reinforced_deepslate")
                || id.contains("ancient_debris") || id.contains("portal") || id.contains("spawner") || id.contains("chest"));
    }

    @Override
    public Status tick(WorldView world) {
        if (top != null && stage == Stage.DIG && offTheHole(world.position())) {   // knocked off it mid-dig
            note.accept("digging in again", "knocked off the hole at " + top.x() + " " + top.y() + " " + top.z());
            hands.stopMining();
            if (down) hiding.accept(false);
            down = false;
            top = null;
            dugIds.clear();
            ticks = 0;
        }
        if (top == null) {                                   // here, now — it may start after a run
            if (!world.onGround()) {                         // knocked up by a fireball: land first
                if (++landing > LAND_TICKS) return fail("never landed to dig in");
                return Status.RUNNING;
            }
            Optional<String> unsafe = canDigIn(world);
            if (unsafe.isPresent()) return fail(unsafe.get());
            top = floor(world.position());
            note.accept("digging in", "at " + top.x() + " " + top.y() + " " + top.z());
        }
        ticks++;
        return switch (stage) {
            case DIG -> dig(world);
            case WAIT -> waitIt(world);
            case CLIMB -> climb(world);
        };
    }

    private Status dig(WorldView world) {
        if (ticks > DIG_TICKS) return fail("still digging after " + DIG_TICKS / 20 + " s");
        if (!down && world.position().y() < top.y() + 0.5) {     // below the surface now
            down = true;
            hiding.accept(true);
        }
        for (int d = 0; d < DEPTH; d++) {
            BlockPos b = new BlockPos(top.x(), top.y() - d, top.z());
            String id = world.blockAt(b);
            if (id.endsWith(":air")) continue;
            if (!toolPicked) {
                toolPicked = true;
                int tool = world.bestToolSlot(b);
                if (tool >= 0) hands.selectSlot(tool);
            }
            if (dugIds.size() <= d) dugIds.add(id);
            hands.mine(b);
            return Status.RUNNING;
        }
        hands.stopMining();
        Vec3 me = world.position();
        if (me.y() > top.y() - DEPTH + 1.5) {                               // still falling in
            // On the rim: a body 0.6 wide, 0.22 off the middle, rested on the next block over the dug
            // hole and never dropped in, and the next fireball cut the dig short (2026-10-29). Step in.
            if (world.onGround() && me.y() > top.y() + 0.5) {
                hands.steerToward(new Vec3(top.x() + 0.5, me.y(), top.z() + 0.5), 30);
                hands.holdKeys(true, false);
            } else {
                hands.holdKeys(false, false);
            }
            return Status.RUNNING;
        }
        hands.holdKeys(false, false);
        note.accept("hiding in a hole", "dug " + DEPTH + " down at " + top.x() + " " + top.y() + " " + top.z());
        stage = Stage.WAIT;
        ticks = 0;
        return Status.RUNNING;
    }

    private Status waitIt(WorldView world) {
        clearFor = danger.test(world) ? 0 : clearFor + 1;
        if (clearFor >= CLEAR_TICKS || ticks > MAX_WAIT_TICKS) {
            note.accept("climbing out of the hole", clearFor >= CLEAR_TICKS
                    ? "nothing after us for " + CLEAR_TICKS / 20 + " s" : "waited " + MAX_WAIT_TICKS / 20 / 60 + " min");
            stage = Stage.CLIMB;
            ticks = 0;
        }
        return Status.RUNNING;
    }

    private Status climb(WorldView world) {
        Vec3 me = world.position();
        if (me.y() >= top.y() + 1 - 0.01 && world.onGround()) {                   // back up where we stood
            hands.holdKeys(false, false);
            hiding.accept(false);
            note.accept("out of the hole", "climbed " + DEPTH + " up");
            return Status.DONE;
        }
        if (ticks > CLIMB_TICKS) {
            hands.holdKeys(false, false);
            return fail("couldn't climb out of the hole");
        }
        if (!holdBlock(world)) {
            hands.holdKeys(false, false);
            return fail("no block to climb out with");
        }
        if (placingOn == null) {
            if (!world.onGround()) return Status.RUNNING;
            placingOn = floor(me);
            hands.holdKeys(false, true);                                          // jump...
            return Status.RUNNING;
        }
        if (me.y() > placingOn.y() + 2.05) {                                      // ...and at the top of it, put one under us
            if (hands.placeOn(placingOn)) {
                hands.holdKeys(false, false);
                placingOn = null;
            }
        }
        return Status.RUNNING;
    }

    /** A block we dug (or any we carry of those kinds) in hand, for the climb. */
    private boolean holdBlock(WorldView world) {
        List<ItemView> inv = world.inventory();
        Optional<ItemView> held = inv.stream().filter(i -> i.slot() == world.selectedSlot() && dugIds.contains(i.id())).findFirst();
        if (held.isPresent()) return true;
        Optional<ItemView> any = inv.stream().filter(i -> dugIds.contains(i.id())).findFirst();
        if (any.isEmpty()) return false;
        if (any.get().inHotbar()) hands.selectSlot(any.get().slot());
        else {
            hands.swapToHotbar(any.get().slot(), BLOCK_SLOT);
            hands.selectSlot(BLOCK_SLOT);
        }
        return true;
    }

    private Status fail(String why) {
        cancel();
        failure = why;
        return Status.FAILED;
    }

    private boolean offTheHole(Vec3 me) {
        return Math.abs(me.x() - (top.x() + 0.5)) > OFF_THE_HOLE || Math.abs(me.z() - (top.z() + 0.5)) > OFF_THE_HOLE;
    }

    private static BlockPos floor(Vec3 p) {
        return new BlockPos((int) Math.floor(p.x()), (int) Math.floor(p.y() - 0.01), (int) Math.floor(p.z()));
    }

    @Override public void cancel() {
        if (top != null && failure.isEmpty()) note.accept("stopped hiding", "cancelled while " + describe());
        hands.stopMining();
        hands.holdKeys(false, false);
        hiding.accept(false);
    }
    @Override public String failure() { return failure; }
    @Override public String describe() {
        return switch (stage) {
            case DIG -> "digging in";
            case WAIT -> "hiding in a hole";
            case CLIMB -> "climbing out of the hole";
        };
    }
}
