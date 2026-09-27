package dev.yuliang.zymbot.fabric;

import dev.yuliang.zymbot.core.Bot;
import dev.yuliang.zymbot.core.config.AutostartPolicy;
import dev.yuliang.zymbot.core.config.ConfigIO;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.protocol.LocalBusTransport;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ChatType;
import dev.yuliang.zymbot.core.protocol.SummonTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wires the brain into the client: join/leave, one brain tick per client tick, human input, and
 * the command. Everything interesting lives in core/ — this file only translates.
 */
public final class ZymbotClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");
    /** HeadlessMC stamps this on the game process; a headless client has no human at the keys. */
    static final boolean HEADLESS = "HeadlessMc".equals(System.getProperty("minecraft.launcher.brand"));

    private static ZymbotClient instance;
    /** The server's tick rate, from its time updates (mixin.ServerTimeMixin). */
    public static final dev.yuliang.zymbot.core.body.TpsMeter TPS = new dev.yuliang.zymbot.core.body.TpsMeter();
    private ZymbotConfig config;
    private Path configFile;
    private Bot bot;
    private FabricHands hands;
    private boolean wasPublished;                              // our world was open to LAN last tick
    private boolean lanAutoTried;                              // this world's auto-open, once per join
    private boolean wasAutoSummon;                             // auto-summon was on last tick
    /** Auto-summon waits for our own server to settle after the world loads (FIXLIST #3). */
    static final float SETTLED_MS_PER_TICK = 50;
    static final int SETTLED_FOR_TICKS = 5 * 20, SETTLE_AT_MOST_TICKS = 60 * 20;
    private boolean summonHeld;
    private int heldTicks, settledTicks;
    /** Respawns seen (WorldView.lives): the player object last ticked, its level, and whether it was dead. */
    private net.minecraft.client.player.LocalPlayer lastPlayer;
    private net.minecraft.client.multiplayer.ClientLevel lastLevel;
    private boolean lastPlayerDead;
    private int lives;

    static ZymbotClient get() { return instance; }
    ZymbotConfig config() { return config; }
    /** Null until the first world is joined. */
    Bot bot() { return bot; }

    /** Save settings edited in the screen. With a bot live, it re-checks roles/servers at once. */
    void settingsEdited() {
        ConfigIO.save(configFile, config);
        if (bot != null) bot.settingsChanged();
    }

    /** From the settings screen. Returns null when applied, or why it was refused. */
    String applyTeamKey(String key) {
        String problem = ZymbotConfig.validateTeamKey(key);
        if (problem != null) return problem;
        if (bot != null) return bot.setTeamKey(key);    // saves, and rekeys the live bus
        config.teamKey = key.strip();
        ConfigIO.save(configFile, config);
        return null;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        configFile = FabricLoader.getInstance().getConfigDir().resolve("zymbot.json");
        config = ConfigIO.load(configFile);
        LOG.info("[zymbot] loaded — command /{}{}, {}", config.commandRoot,
                config.commandAliases.isEmpty() ? "" : " (aliases " + config.commandAliases + ")",
                HEADLESS ? "headless" : "with a human at the keys");

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                ZymbotCommands.register(dispatcher, () -> bot, this::summon, () -> openLan(Minecraft.getInstance()),
                        () -> levelName(Minecraft.getInstance()), config.commandRoot, config.commandAliases));
        // the bot exists from the title screen on, so a summon can reach it before any world
        ClientLifecycleEvents.CLIENT_STARTED.register(this::createBot);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> { TPS.reset(); onJoin(mc); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { if (bot != null) bot.onLeave(); });
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, at) -> {
            if (bot != null) bot.onChat(sender == null ? null : sender.getName(), message.getString(),
                    params.chatType().is(ChatType.MSG_COMMAND_INCOMING));
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (bot != null && !overlay) bot.onChat(null, message.getString(), false);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { if (bot != null) bot.shutdown(); });
    }

    private void createBot(Minecraft mc) {
        if (bot != null) return;
        bot = new Bot(config, configFile, FabricLoader.getInstance().getGameDir().resolve("zymbot"),
                mc.getUser().getProfileId(), HEADLESS, System::currentTimeMillis);
        if (config.transports.localBus) {
            try {
                bot.attachTransport(new LocalBusTransport());
            } catch (IOException e) {
                LOG.warn("[zymbot] local bus unavailable: {}", e.getMessage());
            }
        }
    }

    /** /zbot summon: our LAN world if it's open, else the server we're on. */
    private String summon(Bot b) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer s = mc.getSingleplayerServer();
        if (s != null) {
            if (!s.isPublished()) return "open your world to LAN first (Esc → Open to LAN), then summon";
            return b.summon(SummonTarget.lan(s.getPort(), SummonTarget.localIps()));
        }
        ServerData server = mc.getCurrentServer();
        if (server != null) return b.summon(SummonTarget.server(server.ip));
        return "not in a world";
    }

    /**
     * Open our singleplayer world to LAN on config.lan_port, online mode off (bot accounts are
     * offline accounts). Does what OfflineLAN's toggle does, so it doesn't depend on that toggle.
     */
    String openLan(Minecraft mc) {
        IntegratedServer s = mc.getSingleplayerServer();
        if (s == null) return "only in a singleplayer world";
        if (s.isPublished()) {                                  // open already: at least call the bots in
            return "already open to LAN on port " + s.getPort() + " — "
                    + (bot == null ? "" : bot.summon(SummonTarget.lan(s.getPort(), SummonTarget.localIps())));
        }
        int port = config.lanPort;
        if (!net.minecraft.util.HttpUtil.isPortAvailable(port)) return "port " + port + " is in use — close whatever holds it";
        if (!s.publishServer(s.getDefaultGameType(), s.getWorldData().isAllowCommands(), port)) return "couldn't open to LAN on port " + port;
        s.setUsesAuthentication(false);
        return "opened to LAN on port " + port + " — online mode off, so the bots' offline accounts can join";
    }

    /** The world's name, as the auto-open list keys it; null outside singleplayer. */
    static String levelName(Minecraft mc) {
        IntegratedServer s = mc.getSingleplayerServer();
        return s == null ? null : s.getWorldData().getLevelName();
    }

    private void onJoin(Minecraft mc) {
        createBot(mc);
        lanAutoTried = false;
        if (hands == null) hands = new FabricHands(mc);
        String address, worldKey;
        ServerData server = mc.getCurrentServer();
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            address = AutostartPolicy.SINGLEPLAYER;
            worldKey = "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            address = server != null ? server.ip : "unknown";
            worldKey = address;
        }
        bot.onJoin(address, worldKey, mc.getUser().getName());
    }

    private void onTick(Minecraft mc) {
        if (config.skipExperimentalWorldWarning && mc.screen instanceof BackupConfirmScreen s) skipExperimentalWarning(s);
        if (bot == null) return;
        if (mc.player == null || mc.level == null) {
            BotDigging.active = false;                         // no world, nothing being dug
            if (mc.screen instanceof ConnectScreen) return;     // already joining somewhere
            bot.tickOutsideWorld();
            var join = bot.takeSummon();
            if (join.isEmpty() && mc.screen instanceof DisconnectedScreen) join = bot.retryFailedSummon();
            join.ifPresent(addr -> {
                LOG.info("[zymbot] summoned — joining {}", addr);
                ConnectScreen.startConnecting(new TitleScreen(), mc,
                        ServerAddress.parseString(addr), new ServerData("Summoned", addr, ServerData.Type.OTHER), false, null);
            });
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && !lanAutoTried && server.isReady() && bot.autoOpensLan(levelName(mc))) {
            lanAutoTried = true;
            hands.notifyLocal(openLan(mc));
        }
        boolean published = server != null && server.isPublished();
        boolean autoSummon = config.autoSummonOnLan;
        // just opened — or auto-summon was switched on while already open: call the bots once our
        // server keeps up. Right after a world loads it can run seconds behind, and every bot that
        // joins then times out (FIXLIST #3).
        if (published && autoSummon && (!wasPublished || !wasAutoSummon)) {
            summonHeld = true;
            heldTicks = settledTicks = 0;
        }
        if (summonHeld) {
            if (!published || !autoSummon) summonHeld = false;
            else {
                float ms = server.getCurrentSmoothedTickTime();
                settledTicks = ms < SETTLED_MS_PER_TICK ? settledTicks + 1 : 0;
                if (++heldTicks == 1) LOG.info("[zymbot] auto-summon: waiting for the world to settle ({} ms/tick)", Math.round(ms));
                if (settledTicks >= SETTLED_FOR_TICKS || heldTicks >= SETTLE_AT_MOST_TICKS) {
                    summonHeld = false;
                    bot.decisions().record("auto-summon", settledTicks >= SETTLED_FOR_TICKS
                            ? "the world settled after " + heldTicks / 20 + "s (" + Math.round(ms) + " ms/tick)"
                            : "waited " + SETTLE_AT_MOST_TICKS / 20 + "s and the world is still busy (" + Math.round(ms) + " ms/tick) — summoning anyway");
                    bot.lanOpened(server.getPort(), SummonTarget.localIps());
                }
            }
        }
        wasPublished = published;
        wasAutoSummon = autoSummon;
        if (!HEADLESS && !hands.holdingKeys() && humanIsMoving(mc.options)) bot.humanInput(hands);
        // a respawn replaces the player object; in the same level (or after it was dead) it was a death,
        // however quick - the robust signal when neither the death screen nor a new last-death spot shows
        if (mc.player != lastPlayer) {
            if (lastPlayer != null && (mc.level == lastLevel || lastPlayerDead)) lives++;
            lastPlayer = mc.player;
        }
        lastLevel = mc.level;
        lastPlayerDead = mc.player.isDeadOrDying();
        bot.tick(new FabricWorldView(mc.player, mc.level, mc.getConnection(), mc.gui.getBossOverlay(), lives), hands);
        hands.endTick();                                         // never leave attack held past a tick without a mine()
        // after a respawn the death screen can linger on a client nobody is looking at
        if (!mc.player.isDeadOrDying() && mc.screen instanceof DeathScreen) mc.setScreen(null);
    }

    /** Only the experimental-settings prompt: its title key, and its skip button, by key. */
    private static void skipExperimentalWarning(BackupConfirmScreen s) {
        if (!(s.getTitle().getContents() instanceof TranslatableContents t)
                || !"selectWorld.backupQuestion.experimental".equals(t.getKey())) return;
        for (var child : s.children()) {
            if (child instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents k
                    && "selectWorld.backupJoinSkipButton".equals(k.getKey())) {
                LOG.info("[zymbot] experimental-settings world: pressed \"I know what I'm doing!\" (skip_experimental_world_warning)");
                b.onPress();
                return;
            }
        }
    }

    private static boolean humanIsMoving(Options o) {
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown();
    }
}
