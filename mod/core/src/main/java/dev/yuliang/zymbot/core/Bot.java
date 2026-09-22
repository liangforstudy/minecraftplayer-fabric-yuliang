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
    /** A message from someone not (yet) in the tab list waits this long before it's dropped. */
    public static final long TAB_LIST_GRACE_MILLIS = 10_000;

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
    private WorldView current;                                   // this tick's snapshot, for the bus handler
    private final List<Pending> pending = new ArrayList<>();
    private int notHere;

    /** A message whose sender isn't in the tab list yet — they may have joined a moment ago. */
    private record Pending(Envelope envelope, long since) {}

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
        String where = AutostartPolicy.normalize(address);
        if (!onWhitelist()) {
            log.record("silent", where + " isn't on the server list — /" + config.commandRoot + " start to begin");
            return;
        }
        switch (role()) {
            case BOT -> start("this account is a Bot, and " + where + " is on the server list");
            case TEAMMATE -> announce("this account is a Teammate, and " + where + " is on the server list");
            case NONE -> log.record("silent", "this account isn't listed as a Bot or Teammate");
        }
    }

    /** This account's role. A headless client with no accounts listed is a bot — nobody else plays it. */
    public ZymbotConfig.Role role() {
        ZymbotConfig.Role r = config.roleOf(selfId);
        return r == ZymbotConfig.Role.NONE && headless && config.accounts.isEmpty() ? ZymbotConfig.Role.BOT : r;
    }

    private boolean onWhitelist() {
        return address != null && AutostartPolicy.shouldAutostart(config.autostartServers, address);
    }

    /** Teammate mode: say HELLO so bots know we're here; never touch the controls. */
    private void announce(String why) {
        phase = Phase.TEAMMATE;
        nextHello = 0;
        log.record("announcing as a teammate", why);
    }

    public void onLeave() {
        if (phase.controlling()) brain.halt("left the world");
        if (phase != Phase.STOPPED) log.record("stopped", "left the world");
        phase = Phase.STOPPED;
        if (address != null) memoryStore.save(memoryFile(), memory);
        address = null;
    }

    /** The bot takes control. Works for any role when asked by hand. */
    public void start(String why) {
        if (phase.controlling()) return;
        phase = Phase.DISCOVERY;
        nextHello = 0;                  // say hello on the next tick
        pausedUntil = 0;
        log.record("started: " + phase, why);
    }

    /**
     * Hands control back. A Teammate on a listed server keeps announcing; stopping again (or any
     * other role) goes fully silent.
     */
    public void stop(String why) {
        if (phase == Phase.STOPPED) return;
        if (phase.controlling()) {
            brain.halt("stopped");
            if (role() == ZymbotConfig.Role.TEAMMATE && onWhitelist()) {
                announce("stopped controlling — " + why);
                return;
            }
        }
        phase = Phase.STOPPED;
        log.record("stopped", why);
    }

    /** A human pressed a movement key. Headless clients have no humans. */
    public void humanInput(Hands hands) {
        if (!phase.controlling() || headless) return;
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
        current = world;
        retryPending(now);
        bus.drain();

        if (world.isDead() && config.autoRespawn && (phase.controlling() || headless)
                && now - lastRespawn >= RESPAWN_RETRY_MILLIS) {
            lastRespawn = now;
            log.record("respawning", "died" + (!phase.controlling() ? " (headless: nobody to press the button)" : ""));
            hands.respawn();
            return;
        }
        if (phase == Phase.STOPPED || world.isDead()) return;
        if (phase == Phase.TEAMMATE) {                       // announce only; a human is playing
            maybeHello(world, now);
            return;
        }

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

        maybeHello(world, now);
        brain.tick(world, hands);
    }

    private void maybeHello(WorldView world, long now) {
        if (now < nextHello) return;
        nextHello = now + HELLO_EVERY_MILLIS;
        var p = world.position();
        bus.publish(serverId, world.day(), (int) Math.floor(p.x()), (int) Math.floor(p.z()),
                MessageTypes.HELLO, List.of(selfName, phase.name()));
    }

    // ------------------------------------------------------------------ bus

    /**
     * A bot counts as "here" only if it's in our tab list — the one thing the host and every guest
     * see identically. (Addresses don't work: the host is "singleplayer" to itself, and guests may
     * join by different addresses.) Unknown senders get a short grace period, then are dropped.
     */
    private void onEnvelope(Envelope e) {
        if (current == null || !current.onlinePlayers().contains(e.sender())) {
            pending.add(new Pending(e, clock.getAsLong()));
            return;
        }
        handle(e);
    }

    private void retryPending(long now) {
        if (pending.isEmpty() || current == null) return;
        var it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (current.onlinePlayers().contains(p.envelope().sender())) {
                it.remove();
                handle(p.envelope());
            } else if (now - p.since() > TAB_LIST_GRACE_MILLIS) {
                it.remove();
                notHere++;                                      // on another server, or long gone
            }
        }
    }

    private void handle(Envelope e) {
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
        out.add("doing: " + switch (phase) {
            case STOPPED -> "nothing — silent";
            case TEAMMATE -> "announcing to the team — never takes control";
            default -> brain.describe();
        });
        out.add("role: " + role().label() + (config.roleOf(selfId) == ZymbotConfig.Role.NONE && role() == ZymbotConfig.Role.BOT
                ? " (headless, no accounts listed)" : ""));
        out.add("server: " + (address == null ? "not in a world" : AutostartPolicy.normalize(address)
                + (onWhitelist() ? " (on the server list)" : " (not on the server list)")));
        out.add("bus: " + String.join(", ", bus.transportNames())
                + (config.teamKey.isEmpty() ? " — NO TEAM KEY (anyone on the network can read and fake messages)"
                                              : " — signed + encrypted, key " + ZymbotConfig.fingerprint(config.teamKey))
                + (bus.rejectedCount() > 0 ? ", " + bus.rejectedCount() + " rejected (wrong team key?)" : "")
                + (bus.staleCount() > 0 ? ", " + bus.staleCount() + " stale (replayed, or a clock is off)" : "")
                + (notHere > 0 ? ", " + notHere + " from bots not in this server's tab list" : ""));
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
        return address == null ? "not in a world" : serverAdd(address);
    }

    public String autostartRemove() {
        return address == null ? "not in a world" : serverRemove(address);
    }

    /** Add any server to the list (the settings screen can add one before you've joined it). */
    public String serverAdd(String server) {
        String key = AutostartPolicy.normalize(server);
        if (key.equals(":25565")) return "type an address first";
        if (AutostartPolicy.shouldAutostart(config.autostartServers, server)) return key + " is already on the list";
        config.autostartServers.add(key);
        ConfigIO.save(configFile, config);
        settingsChanged();
        return "added " + key;
    }

    public String serverRemove(String server) {
        String key = AutostartPolicy.normalize(server);
        boolean removed = config.autostartServers.removeIf(s -> AutostartPolicy.normalize(s).equals(key));
        if (!removed) return key + " wasn't on the list";
        ConfigIO.save(configFile, config);
        settingsChanged();
        return "removed " + key;
    }

    /** Set this account's role (Bot / Teammate / NONE = remove). */
    public String setOwnRole(ZymbotConfig.Role role) {
        config.setRole(selfId, selfName, role);
        ConfigIO.save(configFile, config);
        settingsChanged();
        return role == ZymbotConfig.Role.NONE ? "this account is no longer listed"
                : "this account is now a " + role.label()
                  + (role == ZymbotConfig.Role.BOT && !phase.controlling() ? " — it takes control next time you join a listed server (or /" + config.commandRoot + " start)" : "");
    }

    /**
     * Roles or the server list changed while in a world: start or stop *announcing* to match. Never
     * takes control on its own — a Bot role applies on the next join, so a menu click can't
     * suddenly drive your character.
     */
    public void settingsChanged() {
        if (address == null || phase.controlling()) return;
        boolean shouldAnnounce = role() == ZymbotConfig.Role.TEAMMATE && onWhitelist();
        if (shouldAnnounce && phase == Phase.STOPPED) announce("settings changed");
        else if (!shouldAnnounce && phase == Phase.TEAMMATE) {
            phase = Phase.STOPPED;
            log.record("silent", "settings changed");
        }
    }

    public List<String> autostartList() {
        return config.autostartServers.isEmpty() ? List.of("autostart list is empty")
                : config.autostartServers.stream().map(AutostartPolicy::normalize).toList();
    }

    // ------------------------------------------------------------------ accessors

    public Phase phase() { return phase; }
    /** The bot is driving this player. */
    public boolean isRunning() { return phase.controlling(); }
    public UUID selfId() { return selfId; }
    public String selfName() { return selfName; }
    /** As joined, or null when not in a world. */
    public String currentServer() { return address; }
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
