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
        /** Neutral, or a group that all turns on us (zombified piglins): never hit, just leave. */
        LEAVE_ALONE
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
        int crowd() { return this == GOOD ? 4 : this == BASIC ? 2 : 1; }

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
            Map.entry("zombified_piglin", Kind.LEAVE_ALONE), Map.entry("enderman", Kind.LEAVE_ALONE),
            Map.entry("piglin", Kind.LEAVE_ALONE), Map.entry("iron_golem", Kind.LEAVE_ALONE));

    /** Anything not listed (a modded mob, a new one) is backed away from. */
    public static Kind kind(String type) {
        if (type == null) return Kind.STRONG;
        return KINDS.getOrDefault(type.replaceFirst("^minecraft:", ""), Kind.STRONG);
    }

    public enum Action { FIGHT, BACK_OFF, COVER }

    /** The answer, with the reason for the decision log. */
    public record Decision(Action action, String why) {
        public boolean fight() { return action == Action.FIGHT; }
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
        String name = type == null ? "something" : type.replaceFirst("^minecraft:", "");
        String armor = gear.name().toLowerCase(java.util.Locale.ROOT) + " armor";
        boolean low = health <= critical + FIGHT_MARGIN && gear != Gear.GOOD;
        return switch (kind(type)) {
            case LEAVE_ALONE -> new Decision(Action.BACK_OFF, name + " — never hit back (its friends join in)");
            case EXPLODES -> new Decision(Action.BACK_OFF, name + " — keep away");
            case STRONG -> new Decision(Action.BACK_OFF, name + " hits too hard");
            case FLYER -> new Decision(Action.COVER, name + " shoots from the air — cover");
            case SHOOTER -> {
                int n = Math.max(1, shooters);
                if (n > gear.shooters()) yield new Decision(Action.COVER, n + " shooters, " + armor + " rushes " + gear.shooters() + " — cover");
                if (crowd > gear.crowd()) yield new Decision(Action.COVER, crowd + " mobs, " + armor + " fights " + gear.crowd() + " — cover");
                if (low) yield new Decision(Action.COVER, name + ", health " + Math.round(health) + " in " + armor + " — cover");
                yield new Decision(Action.FIGHT, name + (n > 1 ? " and " + (n - 1) + " more shooting" : "") + ", " + armor + " — rush it");
            }
            case MELEE -> {
                if (crowd > gear.crowd()) yield new Decision(Action.BACK_OFF, crowd + " mobs, " + armor + " fights " + gear.crowd());
                if (low) yield new Decision(Action.BACK_OFF, name + ", health " + Math.round(health) + " in " + armor);
                yield new Decision(Action.FIGHT, name + (crowd > 1 ? " and " + (crowd - 1) + " more" : "") + ", " + armor);
            }
        };
    }

    /** Things that can shoot us from where they are ({@link #reach}), in 3D. */
    public static List<EntityView> shooters(WorldView world) {
        Vec3 me = world.position();
        return world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.HOSTILE)
                .filter(e -> kind(e.type()) == Kind.SHOOTER || kind(e.type()) == Kind.FLYER)
                .filter(e -> distance(e.pos(), me) <= reach(e.type()))
                .toList();
    }

    /**
     * Hostiles near enough to join in, not counting the ones we leave alone. Measured in 3D: a cave
     * full of skeletons 30 blocks under our feet isn't a crowd (2026-10-29, a lone husk read as "2 mobs").
     */
    public static int crowd(WorldView world) {
        Vec3 me = world.position();
        return (int) world.nearby().stream()
                .filter(e -> e.kind() == EntityView.Kind.HOSTILE && kind(e.type()) != Kind.LEAVE_ALONE)
                .filter(e -> distance(e.pos(), me) <= CROWD_BLOCKS)
                .count();
    }

    public static double distance(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
