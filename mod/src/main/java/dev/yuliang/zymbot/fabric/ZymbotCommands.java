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
                        // goto <x> <z>, or goto <x> <y> <z> — the middle number is z or y depending on the count
                        .then(argument("x", IntegerArgumentType.integer()).then(argument("y_or_z", IntegerArgumentType.integer())
                                .executes(c -> withBot(c, bot, b -> List.of(b.goTo(i(c, "x"), null, i(c, "y_or_z")))))
                                .then(argument("z", IntegerArgumentType.integer())
                                        .executes(c -> withBot(c, bot, b -> List.of(b.goTo(i(c, "x"), i(c, "y_or_z"), i(c, "z")))))))))
                .then(literal("follow").then(argument("player", StringArgumentType.word())
                        .suggests((c, sb) -> {
                            c.getSource().getOnlinePlayerNames().forEach(sb::suggest);
                            return sb.buildFuture();
                        })
                        .executes(c -> withBot(c, bot, b -> List.of(b.follow(StringArgumentType.getString(c, "player")))))))
                .then(literal("look").then(argument("player", StringArgumentType.word())
                        .suggests((c, sb) -> {
                            c.getSource().getOnlinePlayerNames().forEach(sb::suggest);
                            return sb.buildFuture();
                        })
                        .executes(c -> withBot(c, bot, b -> List.of(b.look(StringArgumentType.getString(c, "player")))))))
                .then(literal("eat").executes(c -> withBot(c, bot, b -> List.of(b.eat()))))
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

    private static List<String> help(String root) {
        String r = "/" + root;
        return List.of(
                r + " status — what the bot is doing, and why",
                r + " start / stop — hand control to the bot, or take it back",
                r + " role bot | teammate | none — what this account is (Bot: the bot plays it; Teammate: you play, it announces)",
                r + " autostart add | remove | list — servers where Zymbot is active",
                r + " goto <x> <z>  or  <x> <y> <z> — walk there (needs Baritone)",
                r + " follow <player> / look <player> / eat — orders; " + r + " cancel drops the order",
                r + " set leash | eat | critical <n> — body thresholds (also in Mod Menu)",
                r + " summon — bots waiting at their title screen (standby) join you; " + r + " summon auto on|off",
                r + " lan — open this world to LAN (port 25565, online mode off); " + r + " lan auto on|off — every time it loads",
                "Settings (team key, accounts, servers): Mod Menu → Zymbot.",
                "Pressing a movement key pauses the bot; it resumes after a few seconds.");
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
