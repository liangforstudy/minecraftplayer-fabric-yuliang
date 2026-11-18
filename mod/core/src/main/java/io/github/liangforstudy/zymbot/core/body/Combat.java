package io.github.liangforstudy.zymbot.core.body;

import io.github.liangforstudy.zymbot.core.api.EntityView;
import io.github.liangforstudy.zymbot.core.api.ItemView;
import io.github.liangforstudy.zymbot.core.api.Vec3;
import io.github.liangforstudy.zymbot.core.api.WorldView;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Fight or back off (doc/9b9t/roadmap.md, "Attacked mid-route"): which mobs are worth hitting back,
 * scaled to the armor worn. Overworld mobs first; the Nether's come with the Nether routes.
 */
public final class Combat {
    private Combat() {}

    public enum Kind {
        /** Walks up and hits: hit it back. */
        MELEE,
        /**
         * Shoots from the ground: rush it — running only gives it more shots (Nether test, 2026-10-29).
         * Take cover when there are more of them than the armor handles.
         */
        SHOOTER,
        /** Shoots from the air (ghasts, blazes): out of reach, so take cover. */
        FLYER,
        /** Creepers: never stand next to one. */
        EXPLODES,
        /** Hits too hard for a sword and some armor: back off. */
        STRONG,
        /** Neutral (piglins, iron golems): never hit, just leave. */
        LEAVE_ALONE,
        /**
         * Neutral until provoked (endermen, zombified piglins): left alone while it minds its own
         * business, fought as a walker once it hits us (owner, 2026-11-17: it provoked it; ender pearls
         * later). Not counted in a crowd, and not met head-on before it hits. A provoked zombified
         * piglin brings its group: {@link #provokedCrowd} counts them all.
         */
        PROVOKED
    }

    /** How much the armor worn soaks up, by armor points (full iron = 15, full diamond = 20). */
    public enum Gear {
        BARE, BASIC, GOOD;

        public static Gear of(int armorPoints) {
            return armorPoints >= 15 ? GOOD : armorPoints >= 7 ? BASIC : BARE;
        }

        /**
         * The armor's tier, one up with a totem in the offhand or {@link #HEALING_BITES} golden apples
         * (or other healing food) carried: they buy another life, or another go (doc/9b9t/roadmap.md,
         * "Before routes" 3). Both together still count one: the armor does the soaking.
         */
        public static Gear of(int armorPoints, boolean totem, int healingBites) {
            Gear g = of(armorPoints);
            return (totem || healingBites >= HEALING_BITES) && g != GOOD ? values()[g.ordinal() + 1] : g;
        }

        /** How many melee mobs at once are still worth fighting. */
        int crowd() { return this == GOOD ? 3 : this == BASIC ? 2 : 1; }   // 4 zombies took good armor to 11 (live, 2026-11-18)

        /**
         * How many shooters at once are still worth rushing: fewer than walkers — four skeletons took
         * full iron from 17 to 11 in 3 s while it closed on the first (Nether, 2026-10-29).
         */
        int shooters() { return this == GOOD ? 2 : 1; }
    }

    /** Healing food carried that raises the gear a tier (a golden apple is one). */
    public static final int HEALING_BITES = 2;

    /** Our gear, armor plus what still saves us. */
    public static Gear gear(WorldView world) {
        return Gear.of(world.armorPoints(), totem(world), healingBites(world));
    }

    /** What the gear tier is made of, for the log: "armor 7 + a totem + 3 golden apples". */
    public static String gearWhy(WorldView world) {
        String s = "armor " + world.armorPoints();
        if (totem(world)) s += " + a totem";
        int bites = healingBites(world);
        if (bites > 0) s += " + " + bites + " healing food";
        return s;
    }

    public static boolean totem(WorldView world) {
        return LogOutInterrupt.TOTEM.equals(world.offhandItem());
    }

    /** Healing food carried (golden apples), counted by bites. */
    public static int healingBites(WorldView world) {
        return world.inventory().stream().filter(i -> i.edible() && i.food().heals() && !i.food().harmful())
                .mapToInt(ItemView::count).sum();
    }

    /** Hostiles counted as a crowd when this close. */
    public static final double CROWD_BLOCKS = 8;
    /** Creepers this close are backed away from before they hiss. */
    public static final double CREEPER_BLOCKS = 5;
    /** Below this much health over critical, only a well-armored body keeps fighting. */
    static final int FIGHT_MARGIN = 4;

    private static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("zombie", Kind.MELEE), Map.entry("husk", Kind.MELEE), Map.entry("drowned", Kind.MELEE),
            Map.entry("zombie_villager", Kind.MELEE), Map.entry("spider", Kind.MELEE),
            Map.entry("cave_spider", Kind.MELEE), Map.entry("silverfish", Kind.MELEE),
            Map.entry("endermite", Kind.MELEE), Map.entry("slime", Kind.MELEE),
            Map.entry("magma_cube", Kind.MELEE), Map.entry("hoglin", Kind.MELEE),
            Map.entry("skeleton", Kind.SHOOTER), Map.entry("stray", Kind.SHOOTER), Map.entry("bogged", Kind.SHOOTER),
            Map.entry("pillager", Kind.SHOOTER), Map.entry("witch", Kind.SHOOTER),
            Map.entry("blaze", Kind.FLYER), Map.entry("ghast", Kind.FLYER),
            Map.entry("creeper", Kind.EXPLODES),
            Map.entry("vindicator", Kind.STRONG), Map.entry("evoker", Kind.STRONG), Map.entry("ravager", Kind.STRONG),
            Map.entry("warden", Kind.STRONG), Map.entry("wither_skeleton", Kind.STRONG),
            Map.entry("piglin_brute", Kind.STRONG), Map.entry("zoglin", Kind.STRONG),
            Map.entry("breeze", Kind.STRONG), Map.entry("shulker", Kind.STRONG), Map.entry("phantom", Kind.STRONG),
            Map.entry("zombified_piglin", Kind.PROVOKED), Map.entry("enderman", Kind.PROVOKED),
            Map.entry("piglin", Kind.LEAVE_ALONE), Map.entry("iron_golem", Kind.LEAVE_ALONE));

    /** The mob types Combat knows, sorted: suggestions for /zbot mob. */
    public static List<String> knownTypes() {
        return KINDS.keySet().stream().sorted().toList();
    }

    /** Anything not listed (a modded mob, a new one) is backed away from. */
    /**
     * Its kind, as this one is: a mounted melee mob, or one with a spear, hits too hard (owner,
     * 2026-11-18: a spear zombie on a zombie horse took us from 15 to 2 in one exchange).
     */
    public static Kind kind(EntityView e) {
        return kind(e.type());
    }

    /**
     * A melee mob riding something (a spear zombie on a zombie horse): it can't be outrun, and its
     * charge took us from 15 to 2. Its mount is hit first, and a 1-wide hole is where it can't follow
     * (owner, 2026-11-18).
     */
    public static boolean rider(EntityView e) {
        return e.has(EntityView.MOUNTED) && kind(e.type()) == Kind.MELEE;
    }

    /** What {@code rider} sits on: the mob of its mount's type right under it. */
    public static java.util.Optional<EntityView> mountOf(WorldView world, EntityView rider) {
        String type = rider.mount().orElse(null);
        if (type == null) return java.util.Optional.empty();
        return world.nearby().stream()
                .filter(e -> type.equals(e.type()) && !java.util.Objects.equals(e.uuid(), rider.uuid()))
                .filter(e -> e.pos().horizontalDistance(rider.pos()) < 1 && e.pos().y() <= rider.pos().y())
                .findFirst();
    }

    /** "baby zombie", "zombie with a spear on a zombie horse", "zombie". */
    public static String label(EntityView e) {
        String name = e.type() == null ? "mob" : e.type().replaceFirst("^[a-z0-9_.-]+:", "");
        if (e.has(EntityView.BABY)) name = "baby " + name;
        if (e.has(EntityView.SPEAR)) name += " with a spear";
        String on = e.mount().map(m -> m.replaceFirst("^[a-z0-9_.-]+:", "").replace('_', ' ')).orElse(null);
        if (on != null) name += " on " + a(on);
        return name.replace('_', ' ');
    }

    public static Kind kind(String type) {
        if (type == null) return Kind.STRONG;
        return KINDS.getOrDefault(type.replaceFirst("^minecraft:", ""), Kind.STRONG);
    }

    public enum Action { FIGHT, BACK_OFF, COVER }

    /**
     * The answer, with the reason for the decision log, and the plain cause for the owner's chat:
     * "taking cover because of 3 shooters" (owner, 2026-11-17).
     */
    public record Decision(Action action, String why, String cause) {
        public Decision(Action action, String why) { this(action, why, why); }
        public boolean fight() { return action == Action.FIGHT; }
    }

    /** "zombie_villager" → "a zombie villager"; "evoker" → "an evoker". */
    public static String a(String name) {
        String n = name.replace('_', ' ');
        return ("aeiou".indexOf(n.isEmpty() ? 'x' : n.charAt(0)) >= 0 ? "an " : "a ") + n;
    }

    /** "skeleton" → "skeletons", "witch" → "witches", "silverfish" stays. */
    static String plural(String name) {
        String n = name.replace('_', ' ');
        if (n.endsWith("fish")) return n;
        if (n.endsWith("ch") || n.endsWith("sh") || n.endsWith("s") || n.endsWith("x")) return n + "es";
        return n + "s";
    }

    /**
     * Mobs by name, for the owner's chat: "3 skeletons", "2 skeletons and a stray", "a zombie, a husk
     * and 2 spiders" (owner, 2026-11-17: "3 shooters" says less than what they are). Most first.
     */
    public static String names(List<EntityView> mobs) {
        Map<String, Long> byType = new java.util.LinkedHashMap<>();
        mobs.stream().map(Combat::label)
                .forEach(t -> byType.merge(t, 1L, Long::sum));
        List<String> parts = byType.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .map(e -> e.getValue() == 1 ? a(e.getKey()) : e.getValue() + " " + plural(e.getKey()))
                .toList();
        if (parts.isEmpty()) return "nothing";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** How far a mob's shots reach us: skeletons ~24, a ghast's fireball 64; walkers only up close. */
    public static double reach(String type) {
        return switch (kind(type)) {
            case FLYER -> 64;
            case SHOOTER -> 24;
            default -> io.github.liangforstudy.zymbot.core.task.RetreatTask.DISTANCE;
        };
    }

    /**
     * @param health   ours now
     * @param critical the critical-health line for the danger mode
     * @param crowd    hostiles within {@link #CROWD_BLOCKS}, the attacker included
     * @param shooters shooters in range of us ({@link #shooters})
     */
    public static Decision decide(String type, Gear gear, int crowd, int shooters, float health, int critical) {
        return decide(type == null ? "something" : type.replaceFirst("^minecraft:", ""), kind(type), gear, crowd, shooters, health, critical);
    }

    /** {@link #decide(String, Gear, int, int, float, int)} for this mob as it is (a spear, a mount). */
    public static Decision decide(EntityView mob, Gear gear, int crowd, int shooters, float health, int critical) {
        return decide(label(mob), kind(mob), gear, crowd, shooters, health, critical);
    }

    private static Decision decide(String name, Kind mobKind, Gear gear, int crowd, int shooters, float health, int critical) {
        String armor = gear.name().toLowerCase(java.util.Locale.ROOT) + " armor";
        boolean low = health <= critical + FIGHT_MARGIN && gear != Gear.GOOD;
        return switch (mobKind) {
            case LEAVE_ALONE -> {                                    // each for its own reason: an enderman has no friends (owner, 2026-11-17)
                String reason = switch (name) {
                    case "iron_golem" -> "hits too hard";      // a village's: the whole village turns on us
                    default -> "hitting it brings its friends";      // piglins
                };
                yield new Decision(Action.BACK_OFF, name + " — never hit back (" + reason + ")", a(name) + " (" + reason + ")");
            }
            case EXPLODES -> {                                       // hit and step back, one at a time (owner, 2026-11-03)
                if (gear == Gear.BARE) yield new Decision(Action.BACK_OFF, name + ", " + armor + " — keep away",
                        a(name) + " and too little armor to fight it");
                if (crowd > 1) yield new Decision(Action.BACK_OFF, name + " and " + (crowd - 1) + " more — keep away",
                        a(name) + " and " + plural(crowd - 1, "more mob", "more mobs"));
                if (shooters > 0) yield new Decision(Action.BACK_OFF, name + " with " + shooters + " shooting — keep away",
                        a(name) + " and " + plural(shooters, "shooter", "shooters"));
                if (low) yield new Decision(Action.BACK_OFF, name + ", health " + Math.round(health) + " in " + armor + " — keep away",
                        a(name) + " at low health (" + Math.round(health) + ")");
                yield new Decision(Action.FIGHT, name + ", " + armor + " — hit it and step back");
            }
            case STRONG -> new Decision(Action.BACK_OFF, name + " hits too hard", a(name) + (name.contains(" on a") ? " (its charge hits too hard)" : " (hits too hard)"));
            case FLYER -> new Decision(Action.COVER, name + " shoots from the air — cover", a(name) + " shooting from the air");
            case SHOOTER -> {
                int n = Math.max(1, shooters);
                if (n > gear.shooters()) yield new Decision(Action.COVER, n + " shooters, " + armor + " rushes " + gear.shooters() + " — cover",
                        n + " shooters");
                if (crowd > gear.crowd()) yield new Decision(Action.COVER, crowd + " mobs, " + armor + " fights " + gear.crowd() + " — cover",
                        crowd + " mobs");
                if (low) yield new Decision(Action.COVER, name + ", health " + Math.round(health) + " in " + armor + " — cover",
                        a(name) + " at low health (" + Math.round(health) + ")");
                yield new Decision(Action.FIGHT, name + (n > 1 ? " and " + (n - 1) + " more shooting" : "") + ", " + armor + " — rush it");
            }
            case MELEE, PROVOKED -> {
                if (crowd > gear.crowd()) yield new Decision(Action.BACK_OFF, crowd + " mobs, " + armor + " fights " + gear.crowd(),
                        crowd + " mobs");
                if (low) yield new Decision(Action.BACK_OFF, name + ", health " + Math.round(health) + " in " + armor,
                        a(name) + " at low health (" + Math.round(health) + ")");
                yield new Decision(Action.FIGHT, name + (crowd > 1 ? " and " + (crowd - 1) + " more" : "") + ", " + armor);
            }
        };
    }

    /** Things that can shoot us from where they are ({@link #reach}), in 3D. */
    public static List<EntityView> shooters(WorldView world) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.HOSTILE)
                .filter(e -> kind(e) == Kind.SHOOTER || kind(e) == Kind.FLYER)
                .filter(e -> distance(e.pos(), me) <= reach(e.type()))
                .toList();
    }

    /**
     * Hostiles near enough to join in, not counting the ones we leave alone. Measured in 3D: a cave
     * full of skeletons 30 blocks under our feet isn't a crowd (2026-10-29, a lone husk read as "2 mobs").
     */
    public static int crowd(WorldView world) {
        return crowd(world, e -> true);
    }

    /** {@link #crowd(WorldView)}, counting only the mobs {@code counted} lets through (not the ones set to ignore). */
    public static int crowd(WorldView world, java.util.function.Predicate<EntityView> counted) {
        Vec3 me = world.position();
        return (int) world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.HOSTILE && kind(e) != Kind.LEAVE_ALONE && kind(e) != Kind.PROVOKED)
                .filter(counted)
                .filter(e -> distance(e.pos(), me) <= CROWD_BLOCKS)
                .count();
    }

    /**
     * The crowd when a {@link Kind#PROVOKED} mob hit us: the usual crowd plus every one of its kind
     * within {@link #CROWD_BLOCKS}, it included. One zombified piglin hit turns all of them near.
     */
    public static int provokedCrowd(WorldView world, EntityView attacker, java.util.function.Predicate<EntityView> counted) {
        Vec3 me = world.position();
        String kind = attacker.type() == null ? "" : attacker.type().replaceFirst("^minecraft:", "");
        long same = world.nearby().stream()
                .filter(e -> e.type() != null && e.type().replaceFirst("^minecraft:", "").equals(kind))
                .filter(counted)
                .filter(e -> distance(e.pos(), me) <= CROWD_BLOCKS || e.uuid() != null && e.uuid().equals(attacker.uuid()))
                .count();
        return crowd(world, counted) + (int) Math.max(1, same);
    }

    public static double distance(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The hotbar slot holding a bow, if any. */
    public static OptionalInt bow(List<ItemView> inventory) {
        return inventory.stream().filter(i -> i.inHotbar() && "minecraft:bow".equals(i.id())).mapToInt(ItemView::slot).findFirst();
    }

    /** Something to shoot: an Infinity bow still needs one arrow in the inventory. */
    public static boolean arrows(List<ItemView> inventory) {
        return inventory.stream().anyMatch(i -> i.id().endsWith("arrow") && i.count() > 0);
    }

    private static final List<String> MATERIALS = List.of("netherite", "diamond", "iron", "stone", "golden", "wooden");

    /** The hotbar slot with the best melee weapon: by material, then a sword over an axe. Empty if none. */
    public static OptionalInt bestWeapon(List<ItemView> inventory) {
        ItemView best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ItemView it : inventory) {
            if (!it.inHotbar()) continue;
            String id = it.id().replaceFirst("^minecraft:", "");
            int kind = id.endsWith("_sword") ? 0 : id.endsWith("_axe") ? 1 : -1;
            if (kind < 0) continue;
            int material = MATERIALS.indexOf(id.substring(0, id.lastIndexOf('_')));
            int score = (material < 0 ? MATERIALS.size() : material) * 2 + kind;
            if (score < bestScore) { bestScore = score; best = it; }
        }
        return best == null ? OptionalInt.empty() : OptionalInt.of(best.slot());
    }
}
