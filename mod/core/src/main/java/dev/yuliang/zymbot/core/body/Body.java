package dev.yuliang.zymbot.core.body;

import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Vec3;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.task.RetreatTask;
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
    /** An attack is "recent" — worth running from — for this long after the last hit or chase. */
    public static final long ATTACK_MEMORY_MILLIS = 10_000;
    /** A mob hit us but the game didn't say which: the nearest hostile this close is taken to be it. */
    public static final double UNNAMED_ATTACKER_BLOCKS = 6;

    /** {@code from} is the attacker's live position while we can see it (tracked by {@code id}). */
    public record Attack(String who, UUID id, Vec3 from, long at) {}

    private final Supplier<List<String>> recentFoods;
    private final Predicate<UUID> isBot;
    private final Predicate<UUID> isTeammate;
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
        this(recentFoods, isBot, id -> !isBot.test(id), clock, changed);
    }

    /**
     * @param isTeammate players whose game runs Zymbot as Teammate — the only ones a bot anchors to
     *                   (PHASE2.md P2-1, P2-2): never a stranger, never another bot
     */
    public Body(Supplier<List<String>> recentFoods, Predicate<UUID> isBot, Predicate<UUID> isTeammate,
                LongSupplier clock, Runnable changed) {
        this.recentFoods = recentFoods;
        this.isBot = isBot;
        this.isTeammate = isTeammate;
        this.clock = clock;
        this.changed = changed;
    }

    public void sense(WorldView world) {
        long now = clock.getAsLong();
        world.recentDamage().ifPresent(d -> {                   // every hit refreshes the memory
            if (d.attackerPos() != null) lastAttack = new Attack(d.attacker(), d.attackerId(), d.attackerPos(), now);
            else if (fromAMob(d.type())) nearestHostile(world, UNNAMED_ATTACKER_BLOCKS)
                    .ifPresent(e -> lastAttack = new Attack(e.name(), e.uuid(), e.pos(), now));
        });
        if (lastAttack == null || lastAttack.id() == null || recentAttack().isEmpty()) return;
        UUID id = lastAttack.id();                              // follow it: run from where it is, not where it hit
        world.nearby().stream().filter(e -> id.equals(e.uuid())).findFirst().ifPresent(e -> {
            boolean chasing = e.pos().horizontalDistance(world.position()) < RetreatTask.DISTANCE;
            lastAttack = new Attack(lastAttack.who(), id, e.pos(), chasing ? now : lastAttack.at());
        });
    }

    private static boolean fromAMob(String type) {
        return type != null && (type.contains("mob") || type.contains("arrow") || type.contains("trident")
                || type.contains("fireball") || type.contains("sting") || type.contains("sonic_boom"));
    }

    public Optional<EntityView> nearestHostile(WorldView world, double blocks) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.HOSTILE && e.pos().horizontalDistance(me) <= blocks)
                .min(Comparator.comparingDouble(e -> e.pos().horizontalDistance(me)));
    }

    /** Drop the attack memory if the attacker is a teammate (after a revive). */
    public void forgetTeammateAttacker() {
        if (lastAttack != null && lastAttack.id() != null && isTeammate.test(lastAttack.id())) lastAttack = null;
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

    /** The nearest Zymbot teammate in sight — what the leash, retreats and regroup measure to. */
    public Optional<EntityView> nearestTeammate(WorldView world) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.PLAYER && !e.uuid().equals(world.selfId()) && isTeammate.test(e.uuid()))
                .min(Comparator.comparingDouble(e -> e.pos().horizontalDistance(me)));
    }

    public boolean isTeammate(UUID id) { return isTeammate.test(id); }

    /** Any human in sight, teammate or not — for calling for a revive, which anyone can give. */
    public Optional<EntityView> nearestHuman(WorldView world) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.PLAYER && !e.uuid().equals(world.selfId()) && !isBot.test(e.uuid()))
                .min(Comparator.comparingDouble(e -> e.pos().horizontalDistance(me)));
    }
}
