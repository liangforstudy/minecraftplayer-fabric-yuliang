package dev.yuliang.zymbot.fabric;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.yuliang.zymbot.core.Bot;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * /&lt;root&gt; — the root is config.command_root (default "zbot") plus any aliases, so it can't clash
 * with another mod or a server plugin. Client-side only: the server never sees these.
 */
final class ZymbotCommands {
    private ZymbotCommands() {}

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, Supplier<Bot> bot,
                         Function<Bot, String> summon, Supplier<String> openLan, Supplier<String> world,
                         String root, List<String> aliases) {
        dispatcher.register(tree(root, bot, summon, openLan, world, root));
        for (String alias : aliases) dispatcher.register(tree(alias, bot, summon, openLan, world, root));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> tree(String name, Supplier<Bot> bot,
            Function<Bot, String> summon, Supplier<String> openLan, Supplier<String> world, String root) {
        return literal(name)
                .executes(c -> reply(c, help(root)))
                .then(literal("help").executes(c -> reply(c, help(root))))
                .then(literal("status").executes(c -> withBot(c, bot, Bot::status)))
                .then(literal("start").executes(c -> withBot(c, bot, b -> {
                    if (b.isRunning()) return List.of("already running");
                    b.start("started by /" + root + " start");
                    return List.of("started — phase " + b.phase());
                })))
                .then(literal("stop").executes(c -> withBot(c, bot, b -> {
                    if (b.isBorrowing()) {                     // a Teammate's one-shot order: hand the controls back
                        b.stop("stopped by /" + root + " stop");
                        return List.of("gave the controls back");
                    }
                    if (!b.isRunning()) return List.of("already stopped");
                    b.stop("stopped by /" + root + " stop");
                    return List.of("stopped");
                })))
                .then(literal("role")
                        .executes(c -> withBot(c, bot, b -> List.of("this account: " + b.role().label())))
                        .then(literal("bot").executes(c -> withBot(c, bot, b -> List.of(b.setOwnRole(ZymbotConfig.Role.BOT)))))
                        .then(literal("teammate").executes(c -> withBot(c, bot, b -> List.of(b.setOwnRole(ZymbotConfig.Role.TEAMMATE)))))
                        .then(literal("none").executes(c -> withBot(c, bot, b -> List.of(b.setOwnRole(ZymbotConfig.Role.NONE))))))
                .then(literal("goto")
                        // goto <x> <z> or <x> <y> <z>; "~" and "~10" are relative to you, like vanilla; goto <player> = come
                        .then(argument("coords", StringArgumentType.greedyString())
                                .suggests((c, sb) -> {
                                    c.getSource().getOnlinePlayerNames().forEach(sb::suggest);
                                    return sb.buildFuture();
                                })
                                .executes(c -> withBot(c, bot, b -> List.of(goTo(c, b, StringArgumentType.getString(c, "coords")))))))
                .then(literal("follow").then(argument("player", StringArgumentType.word())
                        .suggests((c, sb) -> {
                            c.getSource().getOnlinePlayerNames().forEach(sb::suggest);
                            return sb.buildFuture();
                        })
                        .executes(c -> withBot(c, bot, b -> List.of(b.follow(StringArgumentType.getString(c, "player")))))))
                .then(literal("come").then(argument("player", StringArgumentType.word())
                        .suggests((c, sb) -> {
                            c.getSource().getOnlinePlayerNames().forEach(sb::suggest);
                            return sb.buildFuture();
                        })
                        .executes(c -> withBot(c, bot, b -> List.of(b.come(StringArgumentType.getString(c, "player")))))))
                .then(literal("watch").then(argument("bot", StringArgumentType.word())
                        .suggests((c, sb) -> {
                            c.getSource().getOnlinePlayerNames().forEach(sb::suggest);
                            return sb.buildFuture();
                        })
                        .executes(c -> withBot(c, bot, b -> List.of(b.watch(StringArgumentType.getString(c, "bot"), true))))
                        .then(literal("off").executes(c -> withBot(c, bot, b -> List.of(b.watch(StringArgumentType.getString(c, "bot"), false)))))))
                .then(literal("look").then(argument("player", StringArgumentType.word())
                        .suggests((c, sb) -> {
                            c.getSource().getOnlinePlayerNames().forEach(sb::suggest);
                            return sb.buildFuture();
                        })
                        .executes(c -> withBot(c, bot, b -> List.of(b.look(StringArgumentType.getString(c, "player")))))))
                .then(literal("eat").executes(c -> withBot(c, bot, b -> List.of(b.eat()))))
                // debug readouts and test orders, out of the everyday list (owner, 2026-09-27)
                .then(literal("debug")
                        .then(literal("terrain").then(argument("coords", StringArgumentType.greedyString())
                                .executes(c -> withBot(c, bot, b -> {
                                    String[] p = StringArgumentType.getString(c, "coords").trim().split("\\s+");
                                    if (p.length != 2) return List.of("debug terrain <x> <z>  (~ works)");
                                    var at = c.getSource().getPosition();
                                    try {
                                        return List.of(b.terrain(coord(p[0], at.x), coord(p[1], at.z)));
                                    } catch (NumberFormatException e) {
                                        return List.of("not a coordinate");
                                    }
                                }))))
                        .then(literal("block").then(argument("coords", StringArgumentType.greedyString())
                                .executes(c -> withBot(c, bot, b -> {
                                    String[] p = StringArgumentType.getString(c, "coords").trim().split("\\s+");
                                    if (p.length != 3) return List.of("debug block <x> <y> <z>  (~ works)");
                                    var at = c.getSource().getPosition();
                                    try {
                                        return List.of(b.block(coord(p[0], at.x), coord(p[1], at.y), coord(p[2], at.z)));
                                    } catch (NumberFormatException e) {
                                        return List.of("not a coordinate");
                                    }
                                }))))
                        .then(literal("punch").then(argument("coords", StringArgumentType.greedyString())
                                .executes(c -> withBot(c, bot, b -> {
                                    String[] p = StringArgumentType.getString(c, "coords").trim().split("\\s+");
                                    if (p.length != 3) return List.of("debug punch <x> <y> <z>  (~ works)");
                                    var at = c.getSource().getPosition();
                                    try {
                                        return List.of(b.punch(coord(p[0], at.x), coord(p[1], at.y), coord(p[2], at.z)));
                                    } catch (NumberFormatException e) {
                                        return List.of("not a coordinate");
                                    }
                                }))))
                        .then(literal("foods").executes(c -> withBot(c, bot, Bot::foods)))
                        .then(literal("survey").executes(c -> withBot(c, bot, Bot::surveyNow))))
                // see / view / spy: the same command, any name (owner, 2026-09-29)
                .then(see(literal("see"), bot))
                .then(see(literal("view"), bot))
                .then(see(literal("spy"), bot))
                .then(literal("grave").executes(c -> withBot(c, bot, b -> List.of(b.grave())))
                        // loot [except] <player> […]: other players' graves, on purpose only (PHASE3_FIXLIST #1)
                        .then(literal("loot")
                                .executes(c -> withBot(c, bot, b -> List.of(b.graveLoot(List.of(), false))))
                                .then(argument("players", StringArgumentType.greedyString())
                                        .suggests((c, sb) -> suggestPlayers(c, sb, bot))
                                        .executes(c -> withBot(c, bot, b -> List.of(graveLoot(b, StringArgumentType.getString(c, "players"))))))))
                .then(literal("cancel").executes(c -> withBot(c, bot, b -> List.of(b.cancel()))))
                .then(literal("set")
                        .executes(c -> withBot(c, bot, b -> ZymbotConfig.TUNABLES.keySet().stream().sorted()
                                .map(k -> k + " = " + b.config().tunable(k)).toList()))
                        .then(argument("setting", StringArgumentType.word())
                                .suggests((c, sb) -> {
                                    ZymbotConfig.TUNABLES.keySet().forEach(sb::suggest);
                                    return sb.buildFuture();
                                })
                                .then(argument("value", IntegerArgumentType.integer())
                                        .executes(c -> withBot(c, bot, b -> List.of(b.set(StringArgumentType.getString(c, "setting"), i(c, "value"))))))))
                .then(literal("roster").executes(c -> withBot(c, bot, Bot::roster)))
                .then(literal("regroup").executes(c -> withBot(c, bot, b -> List.of(b.regroupNow()))))
                .then(literal("danger")
                        .executes(c -> withBot(c, bot, b -> List.of(b.describeDanger())))
                        .then(argument("mode", StringArgumentType.word())
                                .suggests((c, sb) -> {
                                    ZymbotConfig.DANGER_MODES.forEach(sb::suggest);
                                    return sb.buildFuture();
                                })
                                .executes(c -> withBot(c, bot, b -> List.of(b.setDanger(StringArgumentType.getString(c, "mode")))))))
                .then(literal("summon")
                        .executes(c -> withBot(c, bot, b -> List.of(summon.apply(b))))
                        .then(literal("auto")
                                .executes(c -> withBot(c, bot, b -> List.of("auto-summon when you open to LAN: "
                                        + (b.config().autoSummonOnLan ? "on" : "off"))))
                                .then(literal("on").executes(c -> withBot(c, bot, b -> List.of(b.setAutoSummon(true)))))
                                .then(literal("off").executes(c -> withBot(c, bot, b -> List.of(b.setAutoSummon(false)))))))
                .then(literal("lan")
                        .executes(c -> reply(c, List.of(openLan.get())))
                        .then(literal("auto")
                                .executes(c -> withBot(c, bot, b -> List.of(world.get() == null ? "only in a singleplayer world"
                                        : "'" + world.get() + "' opens to LAN by itself: " + (b.autoOpensLan(world.get()) ? "yes" : "no"))))
                                .then(literal("on").executes(c -> withBot(c, bot, b -> List.of(world.get() == null
                                        ? "only in a singleplayer world" : b.setAutoOpenLan(world.get(), true)))))
                                .then(literal("off").executes(c -> withBot(c, bot, b -> List.of(world.get() == null
                                        ? "only in a singleplayer world" : b.setAutoOpenLan(world.get(), false)))))))
                .then(literal("autostart")
                        .executes(c -> withBot(c, bot, Bot::autostartList))
                        .then(literal("add").executes(c -> withBot(c, bot, b -> List.of(b.autostartAdd()))))
                        .then(literal("remove").executes(c -> withBot(c, bot, b -> List.of(b.autostartRemove()))))
                        .then(literal("list").executes(c -> withBot(c, bot, Bot::autostartList))));
    }

    /** /zbot see [<bot>] - this bot's status, or another bot's over the bus; also named view and spy. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> see(LiteralArgumentBuilder<FabricClientCommandSource> name, Supplier<Bot> bot) {
        return name
                .executes(c -> withBot(c, bot, Bot::status))
                .then(argument("bot", StringArgumentType.word())
                        .suggests((c, sb) -> {
                            Bot b = bot.get();                  // the team as heard on the bus
                            if (b != null) b.memory().roster.values().forEach(r -> sb.suggest(r.name));
                            return sb.buildFuture();
                        })
                        .executes(c -> withBot(c, bot, b -> b.see(StringArgumentType.getString(c, "bot")))));
    }

    private static List<String> help(String root) {
        String r = "/" + root;
        return List.of(
                r + " status (or see) — what the bot is doing, and why",
                r + " see <bot> (or view / spy) — that bot's status, asked over the team bus",
                r + " start / stop — hand control to the bot, or take it back",
                r + " role bot | teammate | none — what this account is (Bot: the bot plays it; Teammate: you play, it announces)",
                r + " autostart add | remove | list — servers where Zymbot is active",
                r + " goto <x> <z>  or  <x> <y> <z> — walk there (~ and ~10 work; needs Baritone); goto <player> = come",
                r + " follow <player> / come <player> / look <player> / eat — orders; " + r + " cancel drops the order",
                r + " watch <bot> [off] — that bot /msg's you its decisions (30 min)",
                r + " set leash | eat | critical | downed | regroup | plantime | lagtps <n> — thresholds (also in Mod Menu)",
                r + " roster — the team: Bot / Teammate, online, last heard, where, how we know",
                r + " regroup — walk back to the nearest teammate now (automatic on start and after a respawn)",
                r + " danger [modpack | easy | normal | hard] — when it runs: first hit (modpack) or critical health",
                r + " debug terrain <x> <z> | block <x> <y> <z> | punch <x> <y> <z> | foods | survey — readouts and test orders",
                r + " grave — walk to our own nearest grave (16 blocks; else where we died) and take our things back",
                r + " grave loot [except] [<player> …] — take from other players' graves: any but ours, only theirs, or any but theirs",
                "On a Teammate account, goto / come / punch / grave borrow your controls for that one order (a movement key takes them back).",
                r + " summon — bots waiting at their title screen (standby) join you; " + r + " summon auto on|off",
                r + " lan — open this world to LAN (port 25565, online mode off); " + r + " lan auto on|off — every time it loads",
                "Settings (team key, accounts, servers): Mod Menu → Zymbot.",
                "Pressing a movement key pauses the bot; it resumes after a few seconds.");
    }

    /** "x z" or "x y z", each a number, "~" or "~n" (relative to the player). */
    private static String goTo(CommandContext<FabricClientCommandSource> c, Bot b, String coords) {
        String[] p = coords.trim().split("\\s+");
        // goto <player> = come <player> (owner, 2026-09-30)
        if (p.length == 1 && p[0].matches("[A-Za-z0-9_]{3,16}") && !p[0].matches("-?[0-9]+")) return b.come(p[0]);
        if (p.length != 2 && p.length != 3) return "goto <x> <z>  or  goto <x> <y> <z>  (~ and ~10 work)  or  goto <player>";
        var at = c.getSource().getPosition();
        try {
            int x = coord(p[0], at.x);
            if (p.length == 2) return b.goTo(x, null, coord(p[1], at.z));
            return b.goTo(x, coord(p[1], at.y), coord(p[2], at.z));
        } catch (NumberFormatException e) {
            return "not a coordinate: " + coords + " — use numbers, ~ or ~10";
        }
    }

    /** "Alice Bob" → only their graves; "except Alice Bob" → any but theirs (and ours). */
    private static String graveLoot(Bot b, String players) {
        List<String> p = List.of(players.trim().split("\\s+"));
        boolean except = !p.isEmpty() && p.get(0).equalsIgnoreCase("except");
        return b.graveLoot(except ? p.subList(1, p.size()) : p, except);
    }

    /** Player names for the last word typed: the tab list and the roster ("except" first). */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(
            CommandContext<FabricClientCommandSource> c, com.mojang.brigadier.suggestion.SuggestionsBuilder sb, Supplier<Bot> bot) {
        String typed = sb.getRemaining();
        int cut = typed.lastIndexOf(' ') + 1;
        var last = sb.createOffset(sb.getStart() + cut);
        String prefix = typed.substring(cut).toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(c.getSource().getOnlinePlayerNames());
        Bot b = bot.get();
        if (b != null) b.memory().roster.values().forEach(r -> { if (r.name != null) names.add(r.name); });
        if (cut == 0) names.add("except");
        for (String n : names) if (n.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) last.suggest(n);
        return last.buildFuture();
    }

    static int coord(String s, double here) {
        if (s.startsWith("~")) return (int) Math.floor(here + (s.length() == 1 ? 0 : Double.parseDouble(s.substring(1))));
        return (int) Math.floor(Double.parseDouble(s));
    }

    private static int i(CommandContext<FabricClientCommandSource> c, String name) {
        return IntegerArgumentType.getInteger(c, name);
    }

    private static int withBot(CommandContext<FabricClientCommandSource> c, Supplier<Bot> bot,
                               Function<Bot, List<String>> action) {
        Bot b = bot.get();
        if (b == null) return reply(c, List.of("not in a world yet"));
        return reply(c, action.apply(b));
    }

    private static int reply(CommandContext<FabricClientCommandSource> c, List<String> lines) {
        for (String l : lines) c.getSource().sendFeedback(Component.literal("§7[zymbot]§r " + l));
        return 1;
    }
}
