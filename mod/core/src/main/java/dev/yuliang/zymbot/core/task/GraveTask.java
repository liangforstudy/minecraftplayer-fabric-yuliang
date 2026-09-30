package dev.yuliang.zymbot.core.task;

import dev.yuliang.zymbot.core.api.BlockHit;
import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.GraveOwner;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.ItemView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Get things out of a civfabric grave (BOT_DESIGN → civfabric grave). A right-click with an empty
 * hand gives the grave back to its owner — everything goes into their inventory and the grave
 * vanishes — and opens a 54-slot chest screen for anyone else (civfabric GraveBlock.useWithoutItem).
 * <p>
 * {@code /zbot grave} only ever wants our own: the owner is read from the grave's block entity data
 * ({@link WorldView#graveOwner}), and someone else's grave is skipped. Taking from someone else's is
 * {@code /zbot grave loot}, on purpose only (the owner, 2026-09-27: {@code /zbot grave} opened a
 * stranger's grave, then failed "not ours?").
 */
public final class GraveTask implements Task {
    public static final String GRAVE = "civfabric:grave";
    public static final int SEARCH_RADIUS = 16;
    static final int REACH = 2;
    /** After the click, the grave vanishing or its screen opening arrive within this; a backstop only. */
    static final int SETTLE_TICKS = 20;
    /** While looting, no slot has emptied for this long: the rest doesn't fit. A backstop only. */
    static final int LOOT_STALL_TICKS = 40;

    /** Which graves an order may open. Names match case-insensitively; nothing is saved. */
    public record Filter(Kind kind, Set<String> names) {
        public enum Kind { MINE, OTHERS, ONLY, EXCEPT }

        public static Filter mine() { return new Filter(Kind.MINE, Set.of()); }
        public static Filter others() { return new Filter(Kind.OTHERS, Set.of()); }
        public static Filter only(List<String> names) { return new Filter(Kind.ONLY, lower(names)); }
        public static Filter except(List<String> names) { return new Filter(Kind.EXCEPT, lower(names)); }

        private static Set<String> lower(List<String> names) {
            return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        }

        /** Whether a grave of this owner may be opened. */
        public boolean accepts(GraveOwner owner, UUID selfId, String selfName) {
            boolean ours = owner.is(selfId, selfName);
            String n = owner.name() == null ? "" : owner.name().toLowerCase(Locale.ROOT);
            return switch (kind) {
                case MINE -> ours;
                case OTHERS -> !ours;
                // "/zbot grave loot <me>" is "/zbot grave": our own name picks our own grave
                case ONLY -> names.contains(n) || (ours && names.contains(selfName.toLowerCase(Locale.ROOT)));
                case EXCEPT -> !ours && !names.contains(n);
            };
        }

        /** Only our own grave can come out of this filter — the right-click gives it back, no screen. */
        public boolean onlyMine(String selfName) {
            return kind == Kind.MINE || (kind == Kind.ONLY && names.equals(Set.of(selfName.toLowerCase(Locale.ROOT))));
        }

        public String describe() {
            return switch (kind) {
                case MINE -> "grave of mine";
                case OTHERS -> "grave but mine";
                case ONLY -> "grave of " + String.join(" or ", names);
                case EXCEPT -> "grave but mine or " + String.join("'s or ", names) + "'s";
            };
        }
    }

    /** A grave to open, and what we know of its owner (null: its data hasn't reached us). */
    public record Pick(BlockPos pos, GraveOwner owner) {
        public String whose() { return owner == null ? "an unknown player's" : owner.label() + "'s"; }
    }

    /**
     * The nearest grave within {@link #SEARCH_RADIUS} the filter accepts. For our own grave only, a
     * grave whose owner we can't read comes last: the click itself tells (it vanishes if it was ours,
     * a screen opens if not — which is then closed at once). {@code skipped} collects who else's lay
     * in range, for the reply.
     */
    public static Optional<Pick> pick(WorldView world, Filter filter, List<String> skipped) {
        UUID me = world.selfId();
        String myName = world.selfName();
        Vec3 at = world.position();
        List<Pick> ok = new ArrayList<>(), unknown = new ArrayList<>();
        for (BlockHit h : world.scanBlocks(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS, 64, GRAVE::equals)) {
            Optional<GraveOwner> o = world.graveOwner(h.pos());
            if (o.isEmpty()) unknown.add(new Pick(h.pos(), null));
            else if (filter.accepts(o.get(), me, myName)) ok.add(new Pick(h.pos(), o.get()));
            else skipped.add(o.get().label());
        }
        Comparator<Pick> nearest = Comparator.comparingDouble(p -> p.pos().center().horizontalDistance(at) + Math.abs(p.pos().y() - at.y()));
        Optional<Pick> best = ok.stream().min(nearest);
        if (best.isEmpty() && filter.onlyMine(myName)) best = unknown.stream().min(nearest);
        else if (best.isEmpty() && !unknown.isEmpty()) skipped.add(unknown.size() + " whose owner I can't read");
        return best;
    }

    private final Hands hands;
    private final BlockPos grave;
    private final GraveOwner owner;
    private final boolean loot;
    private final BiConsumer<String, String> log;
    private WalkTask walk;
    private DiveTask dive;
    private int waited = -1;
    private int filledAtStart = -1, lastFilled = -1, stalled;
    private String failure = "";

    /**
     * @param loot  take from someone else's grave if its screen opens; false (our own grave only):
     *              a screen means it isn't ours — close it and stop
     * @param log   (what, why) — the decision log
     */
    public GraveTask(Hands hands, Pick pick, boolean loot, BiConsumer<String, String> log) {
        this.hands = hands;
        this.grave = pick.pos();
        this.owner = pick.owner();
        this.loot = loot;
        this.log = log;
    }

    @Override
    public Status tick(WorldView world) {
        if (filledAtStart >= 0) return looting(world);
        if (waited >= 0) {                                    // clicked: which answer came back?
            if (!GRAVE.equals(world.blockAt(grave))) return Status.DONE;   // ours: all given back, grave gone
            int filled = world.openContainerFilled();
            if (filled >= 0) {                                // someone else's: civfabric opened it as a chest
                if (!loot) {
                    hands.closeContainer();
                    return fail("the grave at " + where() + " is " + (owner == null ? "not ours" : owner.label() + "'s")
                            + " — it opened as a chest; /zbot grave loot takes from someone else's on purpose");
                }
                if (filled == 0) {
                    hands.closeContainer();
                    log.accept("looted nothing", "the grave at " + where() + " was already empty");
                    return Status.DONE;
                }
                filledAtStart = lastFilled = filled;
                hands.takeAllFromContainer();
                return Status.RUNNING;
            }
            if (++waited > SETTLE_TICKS) return fail("the grave at " + where() + " neither gave our things back nor opened");
            return Status.RUNNING;
        }
        Vec3 me = world.position();
        double d = Math.sqrt(Math.pow(me.x() - (grave.x() + 0.5), 2) + Math.pow(me.y() - grave.y(), 2)
                + Math.pow(me.z() - (grave.z() + 0.5), 2));
        if (d > REACH + 1 && dive != null) {
            Status s = dive.tick(world);
            if (s == Status.FAILED) return fail("can't reach the grave under water: " + dive.failure());
            if (s == Status.RUNNING) return Status.RUNNING;
        } else if (d > REACH + 1) {
            if (walk == null) walk = new WalkTask(hands.paths(), grave, false, REACH);
            Status s = walk.tick(world);
            if (s == Status.FAILED && world.isWater(new BlockPos(grave.x(), grave.y() + 1, grave.z()))) {
                // under water: no pathfinder goes there, so swim over it and sink (owner, 2026-09-30)
                dive = new DiveTask(hands, grave);
                log.accept("diving to the grave at " + where(), "it's under water — " + walk.failure());
                return Status.RUNNING;
            }
            if (s == Status.FAILED) return fail("can't reach the grave: " + walk.failure());
            if (s == Status.RUNNING) return Status.RUNNING;
        }
        hands.paths().stop();
        int empty = emptyHotbarSlot(world);
        if (empty >= 0) hands.selectSlot(empty);
        hands.lookAt(new Vec3(grave.x() + 0.5, grave.y() + 0.5, grave.z() + 0.5));
        hands.useOn(grave);
        waited = 0;
        return Status.RUNNING;
    }

    /**
     * Shift-clicked everything once; the server empties slots as they fit. Done when it's empty (or
     * the grave went — civfabric removes a looted grave), or when nothing has moved for
     * {@link #LOOT_STALL_TICKS}: the rest doesn't fit.
     */
    private Status looting(WorldView world) {
        boolean gone = !GRAVE.equals(world.blockAt(grave));
        int open = world.openContainerFilled();
        // the grave gone: emptied. The screen shut under us (a human's Esc): what was left stays
        int filled = gone ? 0 : open < 0 ? lastFilled : open;
        if (filled < lastFilled) {
            lastFilled = filled;
            stalled = 0;
        }
        if (open >= 0 && filled > 0 && ++stalled <= LOOT_STALL_TICKS) return Status.RUNNING;
        hands.closeContainer();
        int took = filledAtStart - filled;
        if (took == 0) return fail("nothing from the grave at " + where() + " fit — inventory full");
        log.accept("looted " + took + " of " + filledAtStart + " stacks", "from " + whose() + " grave at " + where()
                + (filled > 0 ? " — the rest doesn't fit" : ""));
        return Status.DONE;
    }

    private static int emptyHotbarSlot(WorldView world) {
        for (int slot = 0; slot < 9; slot++) {
            final int s = slot;
            if (world.inventory().stream().noneMatch((ItemView i) -> i.slot() == s)) return s;
        }
        return -1;
    }

    private String where() { return grave.x() + " " + grave.y() + " " + grave.z(); }

    private String whose() { return owner == null ? "an unknown player's" : owner.label() + "'s"; }

    private Status fail(String why) {
        failure = why;
        return Status.FAILED;
    }

    @Override
    public void cancel() {
        if (walk != null) walk.cancel();
        if (dive != null) dive.cancel();
        if (waited >= 0) hands.closeContainer();              // never leave a grave's screen open
    }

    @Override public String failure() { return failure; }

    @Override
    public String describe() {
        return (loot ? "looting " + whose() + " grave at " : "picking up my grave at ") + where();
    }
}
