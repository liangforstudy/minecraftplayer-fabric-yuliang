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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ServerData;
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
    private ZymbotConfig config;
    private Path configFile;
    private Bot bot;
    private FabricHands hands;

    static ZymbotClient get() { return instance; }
    ZymbotConfig config() { return config; }

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
                ZymbotCommands.register(dispatcher, () -> bot, config.commandRoot, config.commandAliases));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> onJoin(mc));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> { if (bot != null) bot.onLeave(); });
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { if (bot != null) bot.shutdown(); });
    }

    private void onJoin(Minecraft mc) {
        if (bot == null) {
            hands = new FabricHands(mc);
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
        if (bot == null || mc.player == null || mc.level == null) return;
        if (!HEADLESS && humanIsMoving(mc.options)) bot.humanInput(hands);
        bot.tick(new FabricWorldView(mc.player, mc.level), hands);
        // after a respawn the death screen can linger on a client nobody is looking at
        if (!mc.player.isDeadOrDying() && mc.screen instanceof DeathScreen) mc.setScreen(null);
    }

    private static boolean humanIsMoving(Options o) {
        return o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown() || o.keyJump.isDown();
    }
}
