package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the reflexes share: the recent-food list (Spice of Fabric), the last attacker, and who
 * counts as a human. Updated by the Bot once per tick.
 */
public final class Body {
    /** An attack is "recent" — worth running from — for this long. */
    public static final long ATTACK_MEMORY_MILLIS = 10_000;

    public record Attack(String who, Vec3 from, long at) {}

    private final Supplier<List<String>> recentFoods;
    private final Predicate<UUID> isBot;
    private final LongSupplier clock;
    private final Runnable changed;
    private Consumer<String> swimReport = why -> {};
    private Attack lastAttack;

    /**
     * @param recentFoods the list in the bot's current memory, oldest first — edited in place
     * @param isBot       true for players known to be bots (anyone else is a human)
     * @param changed     called when something worth saving changed
     */
    public Body(Supplier<List<String>> recentFoods, Predicate<UUID> isBot, LongSupplier clock, Runnable changed) {
        this.recentFoods = recentFoods;
        this.isBot = isBot;
        this.clock = clock;
        this.changed = changed;
    }

    public void sense(WorldView world) {
        world.recentDamage().ifPresent(d -> {
            if (d.attackerPos() != null) lastAttack = new Attack(d.attacker(), d.attackerPos(), clock.getAsLong());
        });
    }

    public Optional<Attack> recentAttack() {
        return Optional.ofNullable(lastAttack).filter(a -> clock.getAsLong() - a.at() <= ATTACK_MEMORY_MILLIS);
    }

    public List<String> recentFoods() { return recentFoods.get(); }

    /** Where walks report falling back to swimming (the decision log). */
    public void onSwim(Consumer<String> report) { this.swimReport = report; }

    public void reportSwim(String why) { swimReport.accept(why); }

    public void ate(String itemId) {
        List<String> foods = recentFoods.get();
        foods.add(itemId);
        while (foods.size() > FoodChooser.SPICE_HISTORY) foods.remove(0);
        changed.run();
    }

    public List<EntityView> humansWithin(WorldView world, double blocks) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.PLAYER && !e.uuid().equals(world.selfId()) && !isBot.test(e.uuid()))
                .filter(e -> e.pos().horizontalDistance(me) <= blocks)
                .toList();
    }

    public Optional<EntityView> nearestHuman(WorldView world) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.PLAYER && !e.uuid().equals(world.selfId()) && !isBot.test(e.uuid()))
                .min(Comparator.comparingDouble(e -> e.pos().horizontalDistance(me)));
    }
}
