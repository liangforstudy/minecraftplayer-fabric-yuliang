package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.Hands;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import io.github.liangforstudy.zymbot.core.brain.Interrupt;
import io.github.liangforstudy.zymbot.core.task.EquipTask;
import io.github.liangforstudy.zymbot.core.task.Task;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Anarchy only (doc/9b9t/roadmap.md, "Before routes" 6): piglins leave a player wearing any gold armor
 * alone, and attack one who isn't. In the Nether (where they live; outside it they turn into zombified
 * piglins), with no gold piece on and one carried, put on the one that costs the fewest armor points:
 * a golden helmet is as good as an iron one (2 each), so it's usually that. The piece it replaces goes
 * to the hotbar.
 */
public final class GoldInterrupt implements Interrupt {
    /** A swap that didn't take is tried again only after this long, not every tick. */
    static final long RETRY_MILLIS = 30_000;
    /** Worn pieces, head first, as {@link WorldView#armor} lists them. */
    static final List<String> PIECES = List.of("helmet", "chestplate", "leggings", "boots");
    /** Armor points by material, head first (vanilla 1.21). */
    private static final Map<String, int[]> POINTS = Map.of(
            "leather", new int[] {1, 3, 2, 1}, "golden", new int[] {2, 5, 3, 1},
            "chainmail", new int[] {2, 5, 4, 1}, "iron", new int[] {2, 6, 5, 2},
            "diamond", new int[] {3, 8, 6, 3}, "netherite", new int[] {3, 8, 6, 3});

    private final Body body;
    private final LongSupplier clock;
    private long retryAt;

    public GoldInterrupt(Body body, LongSupplier clock) {
        this.body = body;
        this.clock = clock;
    }

    @Override public String name() { return "gold"; }

    @Override
    public boolean triggered(WorldView world) {
        if (world.isDead() || !world.waterEvaporates() || clock.getAsLong() < retryAt) return false;   // the Nether
        return world.armor().stream().noneMatch(GoldInterrupt::gold) && best(world).isPresent();
    }

    static boolean gold(String id) {
        return id != null && id.startsWith("minecraft:golden_") && piece(id) >= 0;
    }

    /** Which piece: 0 helmet … 3 boots; -1 for anything that isn't armor. */
    static int piece(String id) {
        if (id == null) return -1;
        for (int i = 0; i < PIECES.size(); i++) if (id.endsWith("_" + PIECES.get(i))) return i;
        return id.equals("minecraft:turtle_helmet") ? 0 : -1;
    }

    static int points(String id) {
        int p = piece(id);
        if (p < 0) return 0;
        if (id.equals("minecraft:turtle_helmet")) return 2;
        int[] by = POINTS.get(id.replaceFirst("^minecraft:", "").replaceFirst("_[a-z]+$", ""));
        return by == null ? 0 : by[p];
    }

    /** The gold piece carried that loses the fewest armor points over what's worn there; helmet first on a tie. */
    static Optional<ItemView> best(WorldView world) {
        List<String> worn = world.armor();
        return world.inventory().stream().filter(i -> gold(i.id()))
                .min(Comparator.comparingInt((ItemView i) -> loss(worn, i.id())).thenComparingInt(i -> piece(i.id())));
    }

    private static int loss(List<String> worn, String goldId) {
        int p = piece(goldId);
        return (p < worn.size() ? points(worn.get(p)) : 0) - points(goldId);
    }

    @Override
    public Task respond(WorldView world, Hands hands) {
        retryAt = clock.getAsLong() + RETRY_MILLIS;                 // done or not, not again straight away
        return best(world).<Task>map(i -> new EquipTask(hands, i.id(), body::note))
                .orElseGet(() -> Task.failed("putting gold on", "no gold armor carried"));
    }

    @Override
    public String why(WorldView world) {
        Optional<ItemView> b = best(world);
        if (b.isEmpty()) return "in the Nether, no gold on";
        int lost = loss(world.armor(), b.get().id());
        return "in the Nether, no gold on: piglins attack — " + b.get().id().replaceFirst("^minecraft:", "").replace('_', ' ')
                + (lost > 0 ? ", " + lost + " armor less" : ", no armor lost");
    }
}
