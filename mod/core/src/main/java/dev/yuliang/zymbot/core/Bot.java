package dev.yuliang.zymbot.core;

import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.brain.Brain;
import dev.yuliang.zymbot.core.brain.DecisionLog;
import dev.yuliang.zymbot.core.brain.Phase;
import dev.yuliang.zymbot.core.brain.Planner;
import dev.yuliang.zymbot.core.config.AutostartPolicy;
import dev.yuliang.zymbot.core.config.ConfigIO;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.protocol.Bus;
import dev.yuliang.zymbot.core.protocol.Envelope;
import dev.yuliang.zymbot.core.protocol.MessageTypes;
import dev.yuliang.zymbot.core.protocol.Transport;
import dev.yuliang.zymbot.core.store.BotMemory;
import dev.yuliang.zymbot.core.store.VersionedStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * One bot: lifecycle, the brain, the bus, and memory. Pure Java — the Fabric adapter calls
 * {@link #onJoin}, {@link #tick}, {@link #humanInput} and the command methods; tests drive the same
 * calls against a fake world. Everything runs on the game thread.
 */
public final class Bot {
    public static final long HELLO_EVERY_MILLIS = 60_000;
    public static final long RESPAWN_RETRY_MILLIS = 2_000;
    public static final long RESUME_WARNING_MILLIS = 3_000;

    private final ZymbotConfig config;
    private final Path configFile;
    private final Path dataDir;
    private final UUID selfId;
    private final boolean headless;
    private final LongSupplier clock;
    private final DecisionLog log;
    private final Bus bus;
    private final Brain brain;
    private final VersionedStore<BotMemory> memoryStore =
            new VersionedStore<>(ConfigIO.GSON, BotMemory.class, BotMemory.SCHEMA_VERSION);

    private Phase phase = Phase.STOPPED;
    private String address;            // as joined, or "singleplayer"; null when not in a world
    private String serverId = "none";
    private String selfName = "?";
    private BotMemory memory = new BotMemory();
    private long pausedUntil;
    private boolean resumeWarned;
    private long nextHello;
    private long lastRespawn = -RESPAWN_RETRY_MILLIS;

    public Bot(ZymbotConfig config, Path configFile, Path dataDir, UUID selfId, boolean headless, LongSupplier clock) {
        this.config = config;
        this.configFile = configFile;
        this.dataDir = dataDir;
        this.selfId = selfId;
        this.headless = headless;
        this.clock = clock;
        this.log = new DecisionLog(50, clock);
        this.bus = new Bus(selfId, config.teamKey, clock);
        this.brain = new Brain(List.of(), Planner.EMPTY, log);
        bus.subscribe(this::onEnvelope);
    }

    public void attachTransport(Transport t) {
        bus.addTransport(t);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Joined a server. Autostarts only if it's on the whitelist. */
    public void onJoin(String address, String selfName) {
        onJoin(address, address, selfName);
    }

    /**
     * @param address  as joined, or "singleplayer" — what the whitelist matches
     * @param worldKey what memory is keyed on: the address, or "singleplayer:&lt;world folder&gt;"
     */
    public void onJoin(String address, String worldKey, String selfName) {
        this.address = address;
        this.selfName = selfName;
        this.serverId = serverIdOf(worldKey);
        this.memory = memoryStore.load(memoryFile(), new BotMemory());
        if (AutostartPolicy.shouldAutostart(config.autostartServers, address)) {
            start("autostart: " + AutostartPolicy.normalize(address) + " is on the whitelist");
        } else {
            log.record("waiting", "not on the autostart whitelist — /" + config.commandRoot + " start to begin");
        }
    }

    public void onLeave() {
        if (phase != Phase.STOPPED) stop("left the world");
        if (address != null) memoryStore.save(memoryFile(), memory);
        address = null;
    }

    public void start(String why) {
        if (phase != Phase.STOPPED) return;
        phase = Phase.DISCOVERY;
        nextHello = 0;                  // say hello on the next tick
        pausedUntil = 0;
        log.record("started: " + phase, why);
    }

    public void stop(String why) {
        if (phase == Phase.STOPPED) return;
        brain.halt("stopped");
        phase = Phase.STOPPED;
        log.record("stopped", why);
    }

    /** A human pressed a movement key. Headless clients have no humans. */
    public void humanInput(Hands hands) {
        if (phase == Phase.STOPPED || headless) return;
        long now = clock.getAsLong();
        if (now >= pausedUntil) {
            brain.halt("a human took the controls");
            log.record("paused", "a human took the controls");
            hands.notifyLocal("zymbot paused — you have the controls. It resumes " + config.humanPauseSeconds
                    + "s after you stop.");
        }
        pausedUntil = now + config.humanPauseSeconds * 1000L;
        resumeWarned = false;
    }

    // ------------------------------------------------------------------ tick

    public void tick(WorldView world, Hands hands) {
        long now = clock.getAsLong();
        bus.drain();

        if (world.isDead() && config.autoRespawn && (phase != Phase.STOPPED || headless)
                && now - lastRespawn >= RESPAWN_RETRY_MILLIS) {
            lastRespawn = now;
            log.record("respawning", "died" + (phase == Phase.STOPPED ? " (headless: nobody to press the button)" : ""));
            hands.respawn();
            return;
        }
        if (phase == Phase.STOPPED || world.isDead()) return;

        if (pausedUntil > now) {
            if (!resumeWarned && pausedUntil - now <= RESUME_WARNING_MILLIS) {
                resumeWarned = true;
                hands.notifyLocal("zymbot resumes in 3s — touch a movement key to keep control.");
            }
            return;
        }
        if (pausedUntil != 0) {
            pausedUntil = 0;
            log.record("resumed", "no human input for " + config.humanPauseSeconds + "s");
        }

        if (now >= nextHello) {
            nextHello = now + HELLO_EVERY_MILLIS;
            var p = world.position();
            bus.publish(serverId, world.day(), (int) Math.floor(p.x()), (int) Math.floor(p.z()),
                    MessageTypes.HELLO, List.of(selfName, phase.name()));
        }
        brain.tick(world, hands);
    }

    // ------------------------------------------------------------------ bus

    private void onEnvelope(Envelope e) {
        if (!e.serverId().equals(serverId)) return;             // another server's bots
        if (MessageTypes.HELLO.equals(e.type())) {
            String key = e.sender().toString();
            boolean known = memory.roster.containsKey(key);
            memory.roster.put(key, new BotMemory.RosterEntry(e.field(0), clock.getAsLong(), e.x(), e.z(), e.field(1)));
            if (!known) log.record("met " + e.field(0), "HELLO on the bus at " + e.x() + ", " + e.z());
        }
    }

    // ------------------------------------------------------------------ commands

    public List<String> status() {
        long now = clock.getAsLong();
        List<String> out = new ArrayList<>();
        out.add("phase: " + phase + (pausedUntil > now ? " (paused " + ((pausedUntil - now + 999) / 1000) + "s — human)" : ""));
        out.add("doing: " + (phase == Phase.STOPPED ? "nothing — stopped" : brain.describe()));
        out.add("server: " + (address == null ? "not in a world" : AutostartPolicy.normalize(address)
                + (AutostartPolicy.shouldAutostart(config.autostartServers, address) ? " (autostart)" : "")));
        out.add("bus: " + String.join(", ", bus.transportNames())
                + (config.teamKey.isEmpty() ? " — NO TEAM KEY (anyone on the network can read and fake messages)"
                                              : " — signed + encrypted, key " + ZymbotConfig.fingerprint(config.teamKey))
                + (bus.rejectedCount() > 0 ? ", " + bus.rejectedCount() + " rejected (wrong team key?)" : "")
                + (bus.staleCount() > 0 ? ", " + bus.staleCount() + " stale (replayed, or a clock is off)" : ""));
        if (memory.roster.isEmpty()) {
            out.add("bots heard: none yet");
        } else {
            out.add("bots heard:");
            memory.roster.values().forEach(r -> out.add("  " + r.name + " — " + r.phase + ", last "
                    + ((now - r.lastSeenMillis) / 1000) + "s ago near " + r.x + ", " + r.z));
        }
        log.latest(3).forEach(d -> out.add("  · " + d));
        return out;
    }

    /** Apply a new team key now: saved to config, and used for the very next message. */
    public String setTeamKey(String key) {
        String problem = ZymbotConfig.validateTeamKey(key);
        if (problem != null) return problem;
        config.teamKey = key.strip();
        ConfigIO.save(configFile, config);
        bus.rekey(config.teamKey);
        nextHello = 0;                                   // announce ourselves under the new key
        log.record("team key changed", "fingerprint " + ZymbotConfig.fingerprint(config.teamKey));
        return null;
    }

    public String autostartAdd() {
        if (address == null) return "not in a world";
        String key = AutostartPolicy.normalize(address);
        if (AutostartPolicy.shouldAutostart(config.autostartServers, address)) return key + " is already on the list";
        config.autostartServers.add(key);
        ConfigIO.save(configFile, config);
        return "added " + key + " — the bot will start by itself here";
    }

    public String autostartRemove() {
        if (address == null) return "not in a world";
        String key = AutostartPolicy.normalize(address);
        boolean removed = config.autostartServers.removeIf(s -> AutostartPolicy.normalize(s).equals(key));
        if (removed) ConfigIO.save(configFile, config);
        return removed ? "removed " + key : key + " wasn't on the list";
    }

    public List<String> autostartList() {
        return config.autostartServers.isEmpty() ? List.of("autostart list is empty")
                : config.autostartServers.stream().map(AutostartPolicy::normalize).toList();
    }

    // ------------------------------------------------------------------ accessors

    public Phase phase() { return phase; }
    public boolean isRunning() { return phase != Phase.STOPPED; }
    public ZymbotConfig config() { return config; }
    public BotMemory memory() { return memory; }
    public DecisionLog decisions() { return log; }
    public void shutdown() { onLeave(); bus.close(); }

    // ------------------------------------------------------------------ helpers

    private Path memoryFile() {
        return dataDir.resolve("memory").resolve(serverId).resolve(selfId + ".json");
    }

    /** A short, non-reversible id for the server, so addresses never travel on the bus. */
    static String serverIdOf(String address) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(AutostartPolicy.normalize(address).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
