package io.github.liangforstudy.zymbot.core.craft;

import io.github.liangforstudy.zymbot.core.api.BlockPos;
import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Put one block down next to us (PHASE3.md step 3 — the crafting table): bring it to the hotbar,
 * hold it, pick a free spot on solid ground within a couple of blocks — never where we stand — turn
 * to its floor and right-click, then check the block is really there. A spot that doesn't take is
 * left for the next one. {@link #placed()} says where it went.
 */
public final class PlaceTask implements Task {
    /** Turning onto the face takes a few ticks; this long and the spot is given up. */
    static final int AIM_TICKS = 40;
    /** After the click: the block should be there by now. */
    static final int CONFIRM_TICKS = 20;
    static final int MAX_SPOTS = 3;
    /** Spots this far out (each way) are looked at; 2 is the sweet spot — in reach, out of the way. */
    static final int SEARCH = 2;

    private enum Stage { HOLD, SELECT, AIM, CONFIRM }

    private final Hands hands;
    private final String item;
    private final String block;
    private final String why;
    private final BiConsumer<String, String> log;
    private Stage stage = Stage.HOLD;
    private int hotbar = -1;
    private int ticks;
    private BlockPos spot;
    private final Set<BlockPos> tried = new HashSet<>();
    private BlockPos placed;
    private int heldAtClick;
    private String failure = "";

    /** @param item the item to place; {@code block} what it becomes (the same id for a crafting table) */
    public PlaceTask(Hands hands, String item, String block, String why, BiConsumer<String, String> log) {
        this.hands = hands;
        this.item = item;
        this.block = block;
        this.why = why;
        this.log = log;
    }

    @Override
    public Status tick(WorldView world) {
        return switch (stage) {
            case HOLD -> hold(world);
            case SELECT -> select(world);
            case AIM -> aim(world);
            case CONFIRM -> confirm(world);
        };
    }

    private Status hold(WorldView world) {
        Optional<ItemView> have = world.inventory().stream().filter(i -> i.id().equals(item))
                .min(Comparator.comparingInt(ItemView::slot));
        if (have.isEmpty()) return fail("no " + Recipe.shortName(item) + " to place");
        if (have.get().inHotbar()) {
            hotbar = have.get().slot();
        } else {                                               // into an empty hotbar slot, else the last one
            hotbar = 8;
            for (int s = 0; s < 9; s++) {
                final int slot = s;
                if (world.inventory().stream().noneMatch(i -> i.slot() == slot)) { hotbar = s; break; }
            }
            hands.swapToHotbar(have.get().slot(), hotbar);
        }
        stage = Stage.SELECT;
        return Status.RUNNING;                                  // the swap reaches the server next tick
    }

    private Status select(WorldView world) {
        if (world.inventory().stream().noneMatch(i -> i.slot() == hotbar && i.id().equals(item))) {
            if (held(world) == 0) return fail("no " + Recipe.shortName(item) + " left in the inventory");
            if (++ticks > 10) return fail("couldn't get the " + Recipe.shortName(item) + " into the hotbar");
            return Status.RUNNING;
        }
        if (world.selectedSlot() != hotbar) {
            hands.selectSlot(hotbar);
            return Status.RUNNING;
        }
        return nextSpot(world);
    }

    private Status nextSpot(WorldView world) {
        if (tried.size() >= MAX_SPOTS) return fail("tried " + MAX_SPOTS + " spots and the " + Recipe.shortName(item) + " didn't go down");
        Optional<BlockPos> s = spot(world);
        if (s.isEmpty()) return fail("no free spot on solid ground within " + SEARCH + " blocks to put the " + Recipe.shortName(item));
        spot = s.get();
        tried.add(spot);
        ticks = 0;
        stage = Stage.AIM;
        return Status.RUNNING;
    }

    /**
     * A free block with solid ground under it, at our feet's level (or one up or down), not in our own
     * column or touching our body, its floor in reach — nearest to 2 blocks away first.
     */
    Optional<BlockPos> spot(WorldView world) {
        Vec3 me = world.position();
        BlockPos feet = BlockPos.of(me);
        List<BlockPos> out = new ArrayList<>();
        for (int dy : new int[]{0, -1, 1}) {
            for (int dx = -SEARCH; dx <= SEARCH; dx++) for (int dz = -SEARCH; dz <= SEARCH; dz++) {
                BlockPos p = new BlockPos(feet.x() + dx, feet.y() + dy, feet.z() + dz);
                if (tried.contains(p) || touchesBody(p, me)) continue;
                BlockPos below = new BlockPos(p.x(), p.y() - 1, p.z());
                if (!world.replaceable(p) || !world.sturdy(below) || !world.canReach(below)) continue;
                out.add(p);
            }
        }
        return out.stream().min(Comparator.comparingDouble(p -> Math.abs(flat(p, me) - 2) + (p.y() == feet.y() ? 0 : 0.5)));
    }

    /** The player is 0.6 wide and 1.8 tall: a block anywhere in that box would be inside us. */
    public static boolean touchesBody(BlockPos p, Vec3 me) {
        boolean x = p.x() + 1 > me.x() - 0.3 && p.x() < me.x() + 0.3;
        boolean z = p.z() + 1 > me.z() - 0.3 && p.z() < me.z() + 0.3;
        boolean y = p.y() + 1 > me.y() && p.y() < me.y() + 1.8;
        return x && z && y;
    }

    private static double flat(BlockPos p, Vec3 me) { return Math.hypot(p.x() + 0.5 - me.x(), p.z() + 0.5 - me.z()); }

    private Status aim(WorldView world) {
        if (world.selectedSlot() != hotbar || world.inventory().stream().noneMatch(i -> i.slot() == hotbar && i.id().equals(item))) {
            stage = Stage.HOLD;                                // the hand changed under us: hold it again
            return Status.RUNNING;
        }
        BlockPos below = new BlockPos(spot.x(), spot.y() - 1, spot.z());
        int before = held(world);
        if (hands.placeOn(below)) {
            heldAtClick = before;
            ticks = 0;
            stage = Stage.CONFIRM;
            return Status.RUNNING;
        }
        if (++ticks > AIM_TICKS) {                             // the crosshair never got there: another spot
            stage = Stage.SELECT;
            ticks = 0;
            return Status.RUNNING;
        }
        return Status.RUNNING;
    }

    private Status confirm(WorldView world) {
        if (world.blockAt(spot).equals(block)) {
            placed = spot;
            log.accept("placed " + Recipe.shortName(block) + " at " + spot.x() + " " + spot.y() + " " + spot.z(), why);
            return Status.DONE;
        }
        if (++ticks > CONFIRM_TICKS) {
            // gone from the inventory but not on the spot: it went down somewhere next to it — find it, don't
            // craft another (2026-10-14: a table a try, three tries, the bot never saw one)
            if (held(world) < heldAtClick) {
                Optional<BlockPos> near = nearby(world, spot);
                if (near.isPresent()) {
                    placed = near.get();
                    log.accept("placed " + Recipe.shortName(block) + " at " + placed.x() + " " + placed.y() + " " + placed.z(),
                            why + " (not on the spot picked, " + spot.x() + " " + spot.y() + " " + spot.z() + ")");
                    return Status.DONE;
                }
                return fail("the " + Recipe.shortName(item) + " left the inventory, but isn't within " + LOOK_AROUND + " blocks of "
                        + spot.x() + " " + spot.y() + " " + spot.z());
            }
            stage = Stage.SELECT;                              // didn't take there: try another spot
            ticks = 0;
        }
        return Status.RUNNING;
    }

    /** After a click that took the item but didn't put it on the spot: look this far around. */
    static final int LOOK_AROUND = 3;

    private Optional<BlockPos> nearby(WorldView world, BlockPos around) {
        BlockPos best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -LOOK_AROUND; dx <= LOOK_AROUND; dx++) for (int dy = -LOOK_AROUND; dy <= LOOK_AROUND; dy++) for (int dz = -LOOK_AROUND; dz <= LOOK_AROUND; dz++) {
            BlockPos p = new BlockPos(around.x() + dx, around.y() + dy, around.z() + dz);
            int d = dx * dx + dy * dy + dz * dz;
            if (d < bestD && world.blockAt(p).equals(block)) {
                best = p;
                bestD = d;
            }
        }
        return Optional.ofNullable(best);
    }

    private int held(WorldView world) {
        return world.inventory().stream().filter(i -> i.id().equals(item)).mapToInt(ItemView::count).sum();
    }

    /** Where it went, once done. */
    public BlockPos placed() { return placed; }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override public void cancel() {}

    @Override public String failure() { return failure; }

    @Override
    public String describe() {
        return "placing " + Recipe.shortName(block) + (spot == null ? "" : " at " + spot.x() + " " + spot.y() + " " + spot.z());
    }
}
