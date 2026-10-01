package dev.yuliang.zymbot.core;

import dev.yuliang.zymbot.core.api.BlockPos;
import dev.yuliang.zymbot.core.api.EntityView;
import dev.yuliang.zymbot.core.api.Hands;
import dev.yuliang.zymbot.core.api.WorldView;
import dev.yuliang.zymbot.core.body.Body;
import dev.yuliang.zymbot.core.body.ChatIn;
import dev.yuliang.zymbot.core.body.ChatOut;
import dev.yuliang.zymbot.core.body.CriticalHealthInterrupt;
import dev.yuliang.zymbot.core.body.DownedInterrupt;
import dev.yuliang.zymbot.core.body.StrandedInterrupt;
import dev.yuliang.zymbot.core.body.WadingInterrupt;
import dev.yuliang.zymbot.core.task.DownedTask;
import dev.yuliang.zymbot.core.body.DrowningInterrupt;
import dev.yuliang.zymbot.core.body.FoodChooser;
import dev.yuliang.zymbot.core.body.HazardInterrupt;
import dev.yuliang.zymbot.core.body.HungerInterrupt;
import dev.yuliang.zymbot.core.body.HungerMeter;
import dev.yuliang.zymbot.core.body.LeashInterrupt;
import dev.yuliang.zymbot.core.brain.Brain;
import dev.yuliang.zymbot.core.brain.DecisionLog;
import dev.yuliang.zymbot.core.brain.Objective;
import dev.yuliang.zymbot.core.brain.Phase;
import dev.yuliang.zymbot.core.brain.Planner;
import dev.yuliang.zymbot.core.config.AutostartPolicy;
import dev.yuliang.zymbot.core.config.ConfigIO;
import dev.yuliang.zymbot.core.config.ZymbotConfig;
import dev.yuliang.zymbot.core.protocol.Bus;
import dev.yuliang.zymbot.core.protocol.Envelope;
import dev.yuliang.zymbot.core.protocol.MessageTypes;
import dev.yuliang.zymbot.core.protocol.SummonTarget;
import dev.yuliang.zymbot.core.protocol.Transport;
import dev.yuliang.zymbot.core.store.BotMemory;
import dev.yuliang.zymbot.core.store.VersionedStore;
import dev.yuliang.zymbot.core.task.EatTask;
import dev.yuliang.zymbot.core.task.FollowTask;
import dev.yuliang.zymbot.core.task.GraveTask;
import dev.yuliang.zymbot.core.task.Task;
import dev.yuliang.zymbot.core.task.WalkTask;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bot: lifecycle, the brain, the bus, and memory. Pure Java — the Fabric adapter calls
 * {@link #onJoin}, {@link #tick}, {@link #humanInput} and the command methods; tests drive the same
 * calls against a fake world. Everything runs on the game thread.
 */
public final class Bot {
    public static final long HELLO_EVERY_MILLIS = 60_000;
    public static final long RESPAWN_RETRY_MILLIS = 2_000;
    /**
     * After a respawn nobody saw coming (new body / new death spot, never seen dead), wait this many
     * ticks before calling it one: civfabric's knockout also swaps the body, and its "bleeding out"
     * bar may arrive a tick or two either side. Downed within the window → not a respawn.
     */
    public static final int RESPAWN_GRACE_TICKS = 20;
    public static final long RESUME_WARNING_MILLIS = 3_000;
    /** A message from someone not (yet) in the tab list waits this long before it's dropped. */
    public static final long TAB_LIST_GRACE_MILLIS = 10_000;
    /** Silent this long, then heard again: logged as "back" even within one session. */
    public static final long AWAY_MILLIS = 5 * 60_000;
    /** Unsaved memory is written at least this often — a killed process loses at most this much. */
    public static final long SAVE_EVERY_MILLIS = 60_000;
    public static final long HUNGER_LOG_EVERY_MILLIS = 5 * 60_000;
    /** An auto-summon is repeated this often, for this long — bots still booting catch a later one. */
    public static final long SUMMON_REPEAT_EVERY_MILLIS = 15_000;
    /**
     * After acting on a summon, ignore more for this long: joining this pack takes 20-35 s, and a
     * repeat arriving mid-join started a fresh connection that cancelled it (2026-09-26, 7 tries).
     */
    public static final long SUMMON_JOIN_GRACE_MILLIS = 60_000;
    public static final long SUMMON_REPEAT_FOR_MILLIS = 5 * 60_000;
    /**
     * A failed summoned join is retried this many times, waiting {@link #SUMMON_RETRY_FIRST_MILLIS}
     * then twice as long each time: one immediate retry lost both tries to a host still busy
     * after loading, three starts in a row (2026-09-26).
     */
    public static final int SUMMON_RETRIES = 4;
    public static final long SUMMON_RETRY_FIRST_MILLIS = 10_000;
    /** /zbot come: this close to the person is there (not their exact block). */
    public static final int COME_WITHIN = 4;
    /** A watcher gets our decisions for this long unless they stop sooner. */
    public static final long WATCH_FOR_MILLIS = 30 * 60_000;
    private static final Logger LOG = LoggerFactory.getLogger("zymbot");

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
            new VersionedStore<>(ConfigIO.GSON, BotMemory.class, BotMemory.SCHEMA_VERSION)
                    .migration(1, doc -> doc)                    // 1 → 2: recent_foods, empty by default
                    .migration(2, doc -> doc);                   // 2 → 3: roster role, null (read from the phase) until heard again
    private final Body body;
    private final HungerMeter hungerMeter = new HungerMeter();
    private final dev.yuliang.zymbot.core.body.FidgetWatchdog fidget = new dev.yuliang.zymbot.core.body.FidgetWatchdog();
    private final ChatIn chatIn = new ChatIn();
    private final ChatOut chatOut;

    private Phase phase = Phase.STOPPED;
    private String address;            // as joined, or "singleplayer"; null when not in a world
    private String serverId = "none";
    private String selfName = "?";
    private BotMemory memory = new BotMemory();
    private long pausedUntil;
    private boolean resumeWarned;
    private long nextHello;
    private final dev.yuliang.zymbot.core.team.Regroup regroup;
    private final java.util.Set<String> inTabList = new java.util.HashSet<>();   // roster members online now
    private final java.util.Set<String> seenOnline = new java.util.HashSet<>();  // ... at some point this session
    private long nextRosterCheck;
    private long nextRosterAsk;
    private long lastRespawn = -RESPAWN_RETRY_MILLIS;
    private WorldView current;                                   // this tick's snapshot, for the bus handler
    private final List<Pending> pending = new ArrayList<>();
    private int notHere;
    private final java.util.Set<String> heardThisSession = new java.util.HashSet<>();
    private boolean memoryDirty;
    private long nextSave;
    private long nextHungerLog;
    private Hands hands;                                         // last tick's, for status
    private String pendingSummon;                                // an address to join, from a SUMMON
    private long joiningSince = Long.MIN_VALUE / 2;              // when we last acted on a summon
    private String lastSummon;                                   // where it sent us, for the retries
    private int summonRetries;                                   // retries used for lastSummon
    private long nextSummonRetry;                                // not before this (0 = the join hasn't failed yet)
    private boolean saidIgnoringSummons;                         // "already in a world": once per world
    private String knownLastDeath;                               // null until first seen in this world
    private boolean sawDeath;                                    // saw ourselves dead (the slow way)
    private int knownLives = -1;                                 // WorldView.lives(); -1 until first seen in this world
    private String pendingRespawn;                               // an unseen respawn's why, until the grace runs out
    private int pendingRespawnTicks;
    private boolean wasDowned, sawRevive;                        // knocked out last tick; saw "Being Revived"
    private int reviveCheckTicks, livesWhenUp;                   // >0: up again, a revive unless it turns out a death
    private final java.util.Map<String, Long> watchers = new java.util.LinkedHashMap<>();   // name → until
    private SummonTarget repeatSummon;                           // auto-summon: say it again for a while
    private long repeatSummonUntil, nextSummonRepeat;
    private String surveyWanted;                                 // why a survey is due (start, respawn, command), until taken
    private java.util.concurrent.CompletableFuture<dev.yuliang.zymbot.core.survey.Survey> surveying;
    private dev.yuliang.zymbot.core.survey.Survey survey;        // the latest, for the planner and /zbot survey
    private String surveyToChat;                                 // the why of an asked-for survey still running: print it when done
    /** /zbot see <bot> asks still waiting for their lines, by request id. */
    private final java.util.Map<String, SeeAsk> seeAsks = new java.util.LinkedHashMap<>();
    private record SeeAsk(String bot, long deadline, String[] lines) {}
    /** Backstop only: the answer normally arrives within a tick or two over the local bus. */
    static final long SEE_TIMEOUT_MILLIS = 5_000;
    /**
     * A Teammate lent us its controls for one order (goto, come, punch, grave): its name, until the
     * order ends or the human touches a movement key; null otherwise. The phase stays TEAMMATE — it
     * keeps announcing as one (PHASE3_FIXLIST #6, 2026-09-27).
     */
    private String borrowedFor;

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
        this.chatOut = new ChatOut(clock);
        this.body = new Body(() -> memory.recentFoods, this::isKnownBot, this::isTeammate, clock, () -> memoryDirty = true);
        body.onSwim(this::reportSwim);
        // BOT_BEHAVIOUR.md interrupt table, phase 1 rows, in priority order
        DownedTask.Help help = new DownedTask.Help() {
            public void callForHelp(WorldView w, List<EntityView> humans) { announceDowned(w, humans); }
            public boolean medicNear(WorldView w) { return false; }   // bots announce their class in a later phase
            // a teammate who hurt us (a test /damage, friendly fire) and then revived us isn't a threat:
            // Bot1 ran from its reviver straight after getting up (2026-09-26)
            public void revived(WorldView w) { body.forgetTeammateAttacker(); }
        };
        this.regroup = new dev.yuliang.zymbot.core.team.Regroup(config, body, () -> memory.roster, clock,
                target -> new dev.yuliang.zymbot.core.task.RouteTask(hands.paths(), target, true, COME_WITHIN,
                        config.routeRadius, config.swimCostBlocks, log::record, "walking back to the team")
                        .limits(config.planTimeoutMs, config.lagTps),
                this::askWhere, COME_WITHIN, log::record);
        this.brain = new Brain(List.of(
                new DownedInterrupt(config, body, help, log::record),
                new DrowningInterrupt(),
                new HazardInterrupt(body),
                new CriticalHealthInterrupt(config, body),
                new StrandedInterrupt(body, this::nothingToDo),
                new LeashInterrupt(config, body, this::workingOnItsOwn, this::idleForTheLeash, regroup::nearestTeammate),
                new HungerInterrupt(config, body),
                new WadingInterrupt(body, this::idleForWading)), regroup, log);
        bus.subscribe(this::onEnvelope);
        log.onRecord(this::toWatchers);
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
        pendingSummon = null;
        inTabList.clear();
        seenOnline.clear();
        lastSummon = null;                                       // we're in: nothing to retry
        saidIgnoringSummons = false;
        knownLastDeath = null;
        sawDeath = false;
        knownLives = -1;
        pendingRespawn = null;
        wasDowned = sawRevive = false;
        reviveCheckTicks = 0;
        heardThisSession.clear();
        hungerMeter.settle();
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
        brain.cancelOrder("left the world");
        if (borrowedFor != null) giveControlsBack("left the world");
        if (phase.controlling()) brain.halt("left the world");
        if (phase != Phase.STOPPED) log.record("stopped", "left the world");
        phase = Phase.STOPPED;
        if (address != null) saveMemory();
        address = null;
        repeatSummon = null;
        survey = null;                                           // another world's surroundings
        surveying = null;
        surveyWanted = null;
        surveyToChat = null;
        seeAsks.clear();
    }

    /** The bot takes control. Works for any role when asked by hand. */
    public void start(String why) {
        if (phase.controlling()) return;
        if (borrowedFor != null) {                               // a full start takes over the borrowed order too
            log.record("keeping the controls", "started — no longer just borrowed for " + borrowedFor);
            borrowedFor = null;
        }
        phase = Phase.DISCOVERY;
        nextHello = 0;                  // say hello on the next tick
        pausedUntil = 0;
        regroup.arm();                  // first objective: find the team (PHASE2.md §1)
        surveyWanted = "started";       // and look around (PHASE3.md §2)
        log.record("started: " + phase, why);
    }

    /**
     * Hands control back. A Teammate on a listed server keeps announcing; stopping again (or any
     * other role) goes fully silent.
     */
    public void stop(String why) {
        if (borrowedFor != null) {                               // a Teammate's errand: hand back, keep announcing
            brain.cancelOrder(why);
            giveControlsBack(why);
            return;
        }
        if (phase == Phase.STOPPED) return;
        if (phase.controlling()) {
            brain.cancelOrder("stopped");
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
        if (headless) return;
        if (borrowedFor != null) {                               // borrowed: the human wants them back — no pause, no resume
            brain.cancelOrder("you touched a movement key");
            giveControlsBack("you touched a movement key");
            return;
        }
        if (!phase.controlling()) return;
        long now = clock.getAsLong();
        if (now >= pausedUntil) {
            brain.halt("a human took the controls");
            log.record("paused", "a human took the controls");
            hands.notifyLocal("zymbot paused — you can move yourself around for now. It goes back to automation mode "
                    + config.humanPauseSeconds + "s after you stop, or /" + config.commandRoot + " stop to stop the bot.");
        }
        pausedUntil = now + config.humanPauseSeconds * 1000L;
        resumeWarned = false;
    }

    // ------------------------------------------------------------------ tick

    public void tick(WorldView world, Hands hands) {
        long now = clock.getAsLong();
        current = world;
        this.hands = hands;
        retryPending(now);
        bus.drain();
        expireSeeAsks(now);
        trackTabList(world, now);
        if (memoryDirty && now >= nextSave) saveMemory();
        if (repeatSummon != null && now >= nextSummonRepeat) {
            if (now >= repeatSummonUntil || !config.autoSummonOnLan) repeatSummon = null;   // done, or switched off
            else {
                sendSummon(repeatSummon);
                nextSummonRepeat = now + SUMMON_REPEAT_EVERY_MILLIS;
            }
        }

        if (world.isDead()) {                                  // whatever it was doing died with it
            if (!sawDeath) died(null);
            sawDeath = true;
        }
        // died and respawned between two ticks, the death screen never shown: either signal will do -
        // a new last-death spot, or a new player object (a /kill with auto-respawn logged nothing and
        // Bot1 idled 146 blocks from its teammate, 2026-09-26)
        String lastDeath = world.lastDeath();
        int lives = world.lives();
        boolean newDeathSpot = knownLastDeath != null && !lastDeath.equals(knownLastDeath);
        boolean newLife = knownLives >= 0 && lives != knownLives;
        knownLastDeath = lastDeath;                              // the first tick in a world sets the baseline
        knownLives = lives;
        if (newDeathSpot || newLife) {
            if (sawDeath) log.record("respawned", "died" + (newDeathSpot ? " at " + lastDeath : ""));
            else {                                               // or knocked out (civfabric): decide after the grace
                pendingRespawn = newDeathSpot ? "died and respawned at once — last death at " + lastDeath
                        : "died and respawned at once — a new player body, never seen dead";
                pendingRespawnTicks = RESPAWN_GRACE_TICKS;
            }
            sawDeath = false;
        } else if (!world.isDead()) {
            sawDeath = false;
        }
        if (pendingRespawn != null) {
            if (world.downedSecondsLeft() >= 0) {                // the knockout's body swap: the downed reflex has it
                log.record("not a respawn", "knocked out — " + world.downedSecondsLeft() + "s to bleed out");
                pendingRespawn = null;
            } else if (world.isDead() || --pendingRespawnTicks <= 0) {
                String why = pendingRespawn;
                pendingRespawn = null;
                if (!world.isDead()) died(why);                  // dead: the death path already handled it
            }
        }
        watchForRevive(world, lives);

        if (world.isDead() && config.autoRespawn && (phase.controlling() || headless)
                && now - lastRespawn >= RESPAWN_RETRY_MILLIS) {
            lastRespawn = now;
            log.record("respawning", "died" + (!phase.controlling() ? " (headless: nobody to press the button)" : ""));
            hands.respawn();
            hungerMeter.settle();
            return;
        }
        pollSurvey(world, now);
        if ((phase == Phase.STOPPED && borrowedFor == null) || world.isDead()) return;
        if (!phase.controlling()) {                          // announce only; a human is playing
            if (phase == Phase.TEAMMATE) maybeHello(world, now);
            if (borrowedFor != null) tickBorrowed(world, hands);   // ...who lent us the controls for one order
            return;
        }

        if (pausedUntil > now) {
            fidget.reset();                                  // a human's turning isn't ours
            if (!resumeWarned && pausedUntil - now <= RESUME_WARNING_MILLIS) {
                resumeWarned = true;
                hands.notifyLocal("zymbot resumes in 3s — touch a movement key to keep control, or /"
                        + config.commandRoot + " stop to stop the bot");
            }
            return;
        }
        if (pausedUntil != 0) {
            pausedUntil = 0;
            log.record("resumed", "no human input for " + config.humanPauseSeconds + "s");
        }

        maybeHello(world, now);
        body.sense(world);
        hungerMeter.sample(world);
        if (now >= nextHungerLog) {
            if (nextHungerLog != 0) LOG.info("[zymbot] hunger meter: {}", String.join("; ", hungerMeter.lines()));
            nextHungerLog = now + HUNGER_LOG_EVERY_MILLIS;
        }
        watchForFidgeting(world, hands);
        brain.tick(world, hands);
        chatOut.flush(hands);
    }

    /**
     * One tick of a borrowed order: the brain runs it as on a Bot (reflexes included — the body is
     * the same), and the controls go back the moment the order is over, however it ended.
     */
    private void tickBorrowed(WorldView world, Hands hands) {
        if (brain.hasOrder()) {
            body.sense(world);
            brain.tick(world, hands);
            chatOut.flush(hands);
        }
        if (!brain.hasOrder()) giveControlsBack("the order is over");
    }

    /** End a borrow: stop whatever runs, and tell the human the controls are theirs. */
    private void giveControlsBack(String why) {
        String what = borrowedFor;
        borrowedFor = null;
        brain.halt(why);
        if (hands != null) {
            hands.paths().stop();
            hands.holdKeys(false, false);
            hands.notifyLocal("the controls are yours again — " + why);
        }
        log.record("gave the controls back", why + " (borrowed for " + what + ")");
    }

    /**
     * Bobbing and spinning in place, getting nowhere (FidgetWatchdog): stop the task and the order,
     * let go of the pathfinder and the keys, and hold still for a minute. A survival reflex at work
     * (surfacing, retreating) is never judged, and still runs while holding still.
     */
    private void watchForFidgeting(WorldView world, Hands hands) {
        String reflex = brain.runningReflex();
        if (brain.holdingStill() || (reflex != null && Brain.SURVIVAL.contains(reflex))) {
            fidget.reset();
            return;
        }
        fidget.sample(world.position(), world.yaw(), !brain.idle()).ifPresent(why -> {
            brain.holdStill(dev.yuliang.zymbot.core.body.FidgetWatchdog.HOLD_TICKS, why);
            hands.paths().stop();
            hands.holdKeys(false, false);
        });
    }

    /**
     * Knocked out, then up again: a revive — unless it turns out to be the respawn after bleeding out or
     * /giveup (dead, or a new body, within the grace). The downed reflex can't say so itself: once the
     * bleeding-out bar is gone its task never ticks again. A teammate who hurt us is forgotten at once —
     * the critical-health retreat fires the same tick, and Bot1 fled from its reviver (2026-09-26).
     */
    private void watchForRevive(WorldView world, int lives) {
        boolean downed = world.downedSecondsLeft() >= 0;
        if (downed) {
            wasDowned = true;
            if (world.beingRevived()) sawRevive = true;
            reviveCheckTicks = 0;
            return;
        }
        if (wasDowned) {                                         // just got up (or died)
            wasDowned = false;
            body.forgetTeammateAttacker();
            reviveCheckTicks = RESPAWN_GRACE_TICKS;
            livesWhenUp = lives;
        }
        if (reviveCheckTicks <= 0) return;
        if (world.isDead() || lives != livesWhenUp || pendingRespawn != null) {
            reviveCheckTicks = 0;                                // a death after all: the respawn path has it
            sawRevive = false;
        } else if (--reviveCheckTicks == 0) {
            log.record("revived", sawRevive ? "someone treated my injuries" : "back up without dying");
            sawRevive = false;
        }
    }

    /**
     * Orders die with the bot; so does the hunger meter's baseline (respawn resets food). Every death
     * arms the regroup, however it was noticed. {@code respawnedWhy}: set when only the respawn itself
     * gave the death away.
     */
    private void died(String respawnedWhy) {
        if (phase.controlling() || borrowedFor != null) {        // a borrow ends with it (given back once up)
            brain.cancelOrder("died");
            brain.halt("died");
        }
        hungerMeter.settle();
        regroup.arm();                                           // respawned somewhere else: find the team again
        if (phase.controlling()) surveyWanted = "respawned";      // somewhere new: look around again
        if (respawnedWhy != null) log.record("respawned", respawnedWhy);
    }

    /** Knocked out: tell the team on the bus, and each human near us by /msg — once. */
    private void announceDowned(WorldView world, List<EntityView> humans) {
        var p = world.position();
        int x = (int) Math.floor(p.x()), z = (int) Math.floor(p.z()), secs = world.downedSecondsLeft();
        bus.publish(serverId, world.day(), x, z, MessageTypes.DOWNED, List.of(selfName, Integer.toString(secs)));
        String wait = config.downedWaitForHumansSeconds > 0 ? "giving up in " + config.downedWaitForHumansSeconds + "s unless someone"
                : "giving up now — next time, ";
        for (EntityView h : humans) {
            chatOut.whisper(h.name(), "I'm knocked out at " + x + " " + z + " (" + secs + "s left) — " + wait
                    + " revives me with stitches.");
        }
        log.record("called for help", "knocked out; told the team" + (humans.isEmpty() ? "" : " and "
                + humans.stream().map(EntityView::name).toList()));
    }

    private void maybeHello(WorldView world, long now) {
        if (now < nextHello) return;
        nextHello = now + HELLO_EVERY_MILLIS;
        var p = world.position();
        bus.publish(serverId, world.day(), (int) Math.floor(p.x()), (int) Math.floor(p.z()),
                MessageTypes.HELLO, List.of(selfName, phase.name(), role().name()));
    }

    // ------------------------------------------------------------------ bus

    /**
     * A bot counts as "here" only if it's in our tab list — the one thing the host and every guest
     * see identically. (Addresses don't work: the host is "singleplayer" to itself, and guests may
     * join by different addresses.) Unknown senders get a short grace period, then are dropped.
     */
    private void onEnvelope(Envelope e) {
        if (MessageTypes.SUMMON.equals(e.type())) {          // for bots *not* on a server yet: no tab list
            onSummon(e);
            return;
        }
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
        if (MessageTypes.WATCH.equals(e.type())) {
            onWatch(e.field(0), e.field(1), "on".equals(e.field(2)));
            return;
        }
        if (MessageTypes.WHERE.equals(e.type())) {              // a bot is regrouping: tell it where we are now
            nextHello = 0;
            return;
        }
        if (MessageTypes.STATUS.equals(e.type())) {             // someone ran /zbot see <us>
            answerStatus(e.field(0), e.field(1), e.field(2));
            return;
        }
        if (MessageTypes.STATUS_LINE.equals(e.type())) {
            onStatusLine(e);
            return;
        }
        if (MessageTypes.ROSTER.equals(e.type())) {             // someone we don't know yet? ask them all
            String self = selfId.toString();
            boolean unknown = java.util.Arrays.stream(e.field(0).split(","))
                    .anyMatch(id -> !id.isBlank() && !id.equals(self) && !memory.roster.containsKey(id));
            long now = clock.getAsLong();
            if (unknown && now >= nextRosterAsk) {
                nextRosterAsk = now + 30_000;
                askWhere();
            }
            return;
        }
        if (MessageTypes.DOWNED.equals(e.type())) {
            log.record(e.field(0) + " is knocked out", "at " + e.x() + ", " + e.z() + ", " + e.field(1) + "s to bleed out");
            return;
        }
        if (MessageTypes.HELLO.equals(e.type())) {
            String key = e.sender().toString();
            long now = clock.getAsLong();
            BotMemory.RosterEntry before = memory.roster.get(key);
            // field 2, the role, is new (2026-09-27): an older client leaves it out, "" here
            memory.roster.put(key, new BotMemory.RosterEntry(e.field(0), now, e.x(), e.z(), e.field(1),
                    e.field(2).isEmpty() ? null : e.field(2)));
            boolean firstThisSession = heardThisSession.add(key);
            String what = e.field(0) + " (" + e.field(1) + ")";
            memoryDirty = true;
            if (before == null) {
                log.record("met " + what, "HELLO on the bus at " + e.x() + ", " + e.z());
                saveMemory();
                shareRoster();                                  // a newcomer: tell everyone who we know
            } else if (firstThisSession || now - before.lastSeenMillis > AWAY_MILLIS) {
                log.record(e.field(0) + " is back" + (before.name.equals(e.field(0)) ? "" : " (was " + before.name + ")")
                        + " — " + e.field(1), "last heard " + ago(now - before.lastSeenMillis) + ", now at " + e.x() + ", " + e.z());
                saveMemory();
            }
        }
    }

    // ------------------------------------------------------------------ commands

    public List<String> status() {
        long now = clock.getAsLong();
        List<String> out = new ArrayList<>();
        out.add("phase: " + phase + (pausedUntil > now ? " (paused " + ((pausedUntil - now + 999) / 1000) + "s — human)" : "")
                + (borrowedFor != null ? " (controls borrowed for: " + borrowedFor + ")" : ""));
        out.add("doing: " + (borrowedFor != null ? brain.describe() : switch (phase) {
            case STOPPED -> "nothing — silent";
            case TEAMMATE -> "announcing to the team — never takes control";
            default -> brain.describe();
        }));
        out.add("role: " + role().label() + (config.roleOf(selfId) == ZymbotConfig.Role.NONE && role() == ZymbotConfig.Role.BOT
                ? " (headless, no accounts listed)" : ""));
        out.add("server: " + (address == null ? "not in a world" : AutostartPolicy.normalize(address)
                + (onWhitelist() ? " (on the server list)" : " (not on the server list)")));
        WorldView w = current;
        if (w != null && address != null) {
            out.add(String.format(Locale.ROOT, "body: health %d, hunger %d/20 (+%.1f saturation) — eats at ≤%d, critical ≤%d, leash %d, danger %s",
                    Math.round(w.health()), w.hunger(), w.saturation(), config.eatBelowHunger, config.criticalFor(), config.leashBlocks, config.danger));
        }
        out.add("pathfinder: " + (hands == null ? "?" : hands.paths().name()));
        if (w != null && address != null) {
            java.util.Map<String, Integer> food = new java.util.TreeMap<>();
            w.inventory().stream().filter(dev.yuliang.zymbot.core.api.ItemView::edible)
                    .forEach(i -> food.merge(i.id().replace("minecraft:", ""), i.count(), Integer::sum));
            out.add("food carried: " + (food.isEmpty() ? "none" : food.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(", "))));
        }
        if (!memory.recentFoods.isEmpty()) out.add("recent foods: " + String.join(", ", memory.recentFoods));
        List<String> meter = hungerMeter.lines();
        if (!meter.isEmpty()) out.add("hunger meter: " + String.join("; ", meter));
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
            memory.roster.values().forEach(r -> out.add("  " + r.name + " — " + r.label() + ", " + r.phase + ", last "
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

    // ------------------------------------------------------------------ summon

    /** Not in a world (title screen): still listen to the bus, so a summon can reach us. */
    public void tickOutsideWorld() {
        current = null;
        bus.drain();
    }

    /** An address a summon asked us to join, once; empty if none. */
    public java.util.Optional<String> takeSummon() {
        String s = pendingSummon;
        pendingSummon = null;
        if (s != null) {
            joiningSince = clock.getAsLong();
            lastSummon = s;
            summonRetries = 0;
            nextSummonRetry = 0;
        }
        return java.util.Optional.ofNullable(s);
    }

    /**
     * The summoned join failed (the client is on the "disconnected" screen, asked every tick): the
     * address to try again once its wait is up, else empty. The first join after a boot often times
     * out — the host allows 15 s and this pack's config data takes the bot ~16 s — and a host still
     * busy after loading drops the next one too, so it backs off: 10 s, 20 s, 40 s, 80 s.
     */
    public java.util.Optional<String> retryFailedSummon() {
        if (lastSummon == null || summonRetries >= SUMMON_RETRIES) return java.util.Optional.empty();
        long now = clock.getAsLong();
        if (nextSummonRetry == 0) {                          // just failed: start the wait
            long wait = SUMMON_RETRY_FIRST_MILLIS << summonRetries;
            nextSummonRetry = now + wait;
            log.record("waiting to retry the summon", "the join to " + lastSummon + " failed — trying again in "
                    + wait / 1000 + "s (" + (summonRetries + 1) + " of " + SUMMON_RETRIES + ")");
            return java.util.Optional.empty();
        }
        if (now < nextSummonRetry) return java.util.Optional.empty();
        summonRetries++;
        nextSummonRetry = 0;
        joiningSince = now;
        log.record("retrying the summon", "try " + summonRetries + " of " + SUMMON_RETRIES + " to " + lastSummon);
        return java.util.Optional.of(lastSummon);
    }

    /** Ask the team's bots waiting at their title screens to join us there. */
    public String summon(SummonTarget target) {
        sendSummon(target);
        log.record("summoned the team's bots", "to " + target.describe());
        return "summoned — bots with your team key that are waiting at the title screen join you at "
                + target.describe() + " (start them with standby)";
    }

    private void sendSummon(SummonTarget target) {
        WorldView w = current;
        int x = w == null ? 0 : (int) Math.floor(w.position().x()), z = w == null ? 0 : (int) Math.floor(w.position().z());
        bus.publish(serverId, w == null ? 0 : w.day(), x, z, MessageTypes.SUMMON, List.of(selfName, target.encode()));
    }

    /**
     * This client just opened its world to LAN. With auto-summon on, the summon is repeated for
     * {@link #SUMMON_REPEAT_FOR_MILLIS}, so bots that are still booting when the world opens join too.
     */
    public void lanOpened(int port, List<String> ips) {
        if (!config.autoSummonOnLan) return;
        SummonTarget t = SummonTarget.lan(port, ips);
        summon(t);
        long now = clock.getAsLong();
        repeatSummon = t;
        repeatSummonUntil = now + SUMMON_REPEAT_FOR_MILLIS;
        nextSummonRepeat = now + SUMMON_REPEAT_EVERY_MILLIS;
    }

    /** Whether this singleplayer world opens to LAN by itself. */
    public boolean autoOpensLan(String levelName) {
        return config.autoOpenLanWorlds.contains(levelName);
    }

    public String setAutoOpenLan(String levelName, boolean on) {
        config.autoOpenLanWorlds.remove(levelName);
        if (on) config.autoOpenLanWorlds.add(levelName);
        ConfigIO.save(configFile, config);
        return "'" + levelName + "' " + (on ? "opens to LAN by itself on port " + config.lanPort + " (online mode off)"
                : "no longer opens to LAN by itself");
    }

    public String setAutoSummon(boolean on) {
        config.autoSummonOnLan = on;
        ConfigIO.save(configFile, config);
        return "auto-summon when you open to LAN: " + (on ? "on" : "off");
    }

    private void onSummon(Envelope e) {
        String who = e.field(0);
        if (address != null) {                               // auto-summon repeats: say so once, not every 15 s
            if (!saidIgnoringSummons) log.record("ignored a summon from " + who, "already in a world");
            saidIgnoringSummons = true;
            return;
        }
        if (role() != ZymbotConfig.Role.BOT) return;          // a human's client is never pulled anywhere
        if (clock.getAsLong() - joiningSince < SUMMON_JOIN_GRACE_MILLIS) return;   // still joining the last one
        var target = SummonTarget.decode(e.field(1));
        if (target.isEmpty()) {
            log.record("ignored a summon from " + who, "unreadable target");
            return;
        }
        pendingSummon = target.get().addressFrom(java.util.Set.copyOf(SummonTarget.localIps()));
        log.record("summoned by " + who, "joining " + pendingSummon + " (" + target.get().describe() + ")");
    }

    // ------------------------------------------------------------------ orders (PHASE1.md test commands)

    /** Walk to a block, or with {@code y == null} to any height in that column. */
    public String goTo(int x, Integer y, int z) {
        String no = oneShotRefusal();
        if (no != null) return no;
        BlockPos target = new BlockPos(x, y == null ? 0 : y, z);
        String where = x + (y == null ? "" : " " + y) + " " + z;
        String lent = oneShot(Objective.of("walk to " + where, "ordered by /" + config.commandRoot + " goto",
                (world, h) -> new dev.yuliang.zymbot.core.task.RouteTask(h.paths(), target, y == null,
                        config.routeRadius, config.swimCostBlocks, log::record).limits(config.planTimeoutMs, config.lagTps)));
        return "walking to " + where + pathfinderWarning() + lent;
    }

    public String follow(String name) {
        String no = needsControl();
        if (no != null) return no;
        brain.order(Objective.of("follow " + name, "ordered by /" + config.commandRoot + " follow",
                (world, h) -> new FollowTask(h.paths(), name, this::reportSwim,
                        why -> log.record("walking dry again", why))));
        return "following " + name + " until /" + config.commandRoot + " cancel" + pathfinderWarning();
    }

    public String eat() {
        String no = needsControl();
        if (no != null) return no;
        if (current != null && FoodChooser.best(current, memory.recentFoods, config.neverEat, false).isEmpty()) {
            return current.hunger() >= 20 ? "not hungry — nothing here can be eaten at full hunger" : "no safe food in the inventory";
        }
        brain.order(Objective.of("eat", "ordered by /" + config.commandRoot + " eat",
                (world, h) -> FoodChooser.best(world, memory.recentFoods, config.neverEat, false)
                        .<Task>map(item -> new EatTask(h, item, body::ate))
                        .orElseGet(() -> Task.failed("eat", "no safe food in the inventory"))));
        return "eating the best food";
    }

    /**
     * Take our things back from our own grave: the nearest one of ours if it's close, otherwise walk
     * back to where we last died (the server tells us) and look there. Someone else's grave is never
     * opened — that is {@link #graveLoot}, on purpose (2026-09-27: it opened a stranger's grave).
     */
    public String grave() {
        return grave(GraveTask.Filter.mine(), "grave", true);
    }

    /**
     * Take from other players' graves, on purpose only: any nearby grave but ours ({@code names}
     * empty), only these players' ({@code except} false), or anyone's but theirs ({@code except}
     * true). Our own name in the list is {@code /zbot grave} without the walk to the death spot.
     */
    public String graveLoot(List<String> names, boolean except) {
        List<String> clean = names.stream().map(String::strip).filter(n -> !n.isEmpty()).toList();
        if (except && clean.isEmpty()) return "grave loot except <player> [<player> …] — whose graves to leave alone";
        GraveTask.Filter f = clean.isEmpty() ? GraveTask.Filter.others()
                : except ? GraveTask.Filter.except(clean) : GraveTask.Filter.only(clean);
        return grave(f, "grave loot" + (except ? " except" : "") + (clean.isEmpty() ? "" : " " + String.join(" ", clean)), false);
    }

    private String grave(GraveTask.Filter filter, String command, boolean walkToDeath) {
        String no = oneShotRefusal();
        if (no != null) return no;
        if (current == null) return "not in a world";
        String why = "ordered by /" + config.commandRoot + " " + command;
        List<String> skipped = new ArrayList<>();
        var pick = GraveTask.pick(current, filter, skipped);
        String others = skipped.isEmpty() ? "" : " (skipped: " + String.join(", ", skipped) + ")";
        if (pick.isPresent()) {
            GraveTask.Pick g = pick.get();
            boolean mine = filter.onlyMine(selfName) || (g.owner() != null && g.owner().is(selfId, selfName));
            String name = mine ? "pick up my grave" : "loot " + g.whose() + " grave";
            log.record("chose " + g.whose() + " grave at " + g.pos().x() + " " + g.pos().y() + " " + g.pos().z(),
                    "wanted a " + filter.describe() + (g.owner() == null ? "; its owner hasn't reached this client, the click will tell" : "") + others);
            String lent = oneShot(Objective.of(name, why, (world, h) -> new GraveTask(h, g, !mine, log::record)));
            return "going to " + (mine ? "my" : g.whose()) + " grave at " + g.pos().x() + " " + g.pos().y() + " " + g.pos().z() + others + lent;
        }
        var death = walkToDeath ? deathSpot(current.lastDeath()) : null;
        if (death == null) return "no " + filter.describe() + " within " + GraveTask.SEARCH_RADIUS + " blocks"
                + (walkToDeath ? ", and no death on record" : "") + others;
        String lent = oneShot(Objective.of("walk back to where I died", why + " — then look for the grave",
                (world, h) -> new dev.yuliang.zymbot.core.task.RouteTask(h.paths(), death, false, 3,
                        config.routeRadius, config.swimCostBlocks, log::record, "walking back to where I died").limits(config.planTimeoutMs, config.lagTps)));
        return "no grave of mine in sight" + others + " — walking back to where I died (" + death.x() + " " + death.y() + " " + death.z()
                + "); run it again there" + lent;
    }

    /** "minecraft:overworld -65, 66, -268" → the block; null if none or another dimension. */
    static BlockPos deathSpot(String lastDeath) {
        if (lastDeath == null || lastDeath.isEmpty()) return null;
        String[] p = lastDeath.split(" ", 2);
        if (p.length < 2) return null;
        String[] xyz = p[1].split(",\\s*");
        try {
            return new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Debug: what the route planner sees at a column. */
    public String terrain(int x, int z) {
        if (current == null) return "not in a world";
        var t = current.terrain();
        var k = t.kind(x, z);
        return x + " " + z + ": " + k + (k == dev.yuliang.zymbot.core.api.Terrain.Kind.LAND || k == dev.yuliang.zymbot.core.api.Terrain.Kind.WATER
                ? " at y " + t.height(x, z) : "");
    }

    /** Debug: what this client sees at a block. */
    public String block(int x, int y, int z) {
        if (current == null) return "not in a world";
        return x + " " + y + " " + z + ": " + current.blockAt(new BlockPos(x, y, z));
    }

    /** Walk to a player — to within {@link #COME_WITHIN} blocks: where we see them, else where they last announced. */
    public String come(String name) {
        String no = oneShotRefusal();
        if (no != null) return no;
        int x, z;
        boolean seen = current != null && current.player(name).isPresent();
        if (seen) {
            var p = current.player(name).get().pos();
            x = (int) Math.floor(p.x());
            z = (int) Math.floor(p.z());
        } else {
            var heard = memory.roster.values().stream().filter(r -> r.name.equalsIgnoreCase(name)).findFirst();
            if (heard.isEmpty()) return "don't know where " + name + " is — not in sight, and they haven't announced themselves";
            x = heard.get().x;
            z = heard.get().z;
        }
        BlockPos target = new BlockPos(x, 0, z);
        String lent = oneShot(Objective.of("come to " + name, "ordered by /" + config.commandRoot + " come",
                (world, h) -> new dev.yuliang.zymbot.core.task.RouteTask(h.paths(), target, true, COME_WITHIN,
                        config.routeRadius, config.swimCostBlocks, log::record, "coming to " + name).limits(config.planTimeoutMs, config.lagTps)));
        return "coming to " + name + " at " + x + " " + z + (seen ? "" : " (where they last announced)") + pathfinderWarning() + lent;
    }

    /**
     * /zbot see &lt;bot&gt;: ask that bot for its status over the bus — its state lives on its own
     * client, so a Teammate can't read it locally. The lines are printed as they complete.
     */
    public List<String> see(String botName) {
        if (address == null) return List.of("not in a world");
        if (botName.equalsIgnoreCase(selfName)) return status();
        WorldView w = current;
        int x = w == null ? 0 : (int) Math.floor(w.position().x()), z = w == null ? 0 : (int) Math.floor(w.position().z());
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        seeAsks.put(reqId, new SeeAsk(botName, clock.getAsLong() + SEE_TIMEOUT_MILLIS, null));
        bus.publish(serverId, w == null ? 0 : w.day(), x, z, MessageTypes.STATUS, List.of(selfName, botName, reqId));
        return List.of("asking " + botName + " for its status…");
    }

    private void answerStatus(String asker, String botName, String reqId) {
        if (!botName.equalsIgnoreCase(selfName) || asker.equalsIgnoreCase(selfName)) return;
        WorldView w = current;
        int x = w == null ? 0 : (int) Math.floor(w.position().x()), z = w == null ? 0 : (int) Math.floor(w.position().z());
        List<String> lines = status();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() > 300) line = line.substring(0, 300) + "…";   // one line per packet, well under the 1400-byte cap
            bus.publish(serverId, w == null ? 0 : w.day(), x, z, MessageTypes.STATUS_LINE,
                    List.of(selfName, asker, reqId, Integer.toString(i), Integer.toString(lines.size()), line));
        }
        log.record("told " + asker + " my status", "asked by /" + config.commandRoot + " see " + selfName);
    }

    /** Lines may arrive out of order (UDP): hold them until the set is complete, then print in order. */
    private void onStatusLine(Envelope e) {
        if (!e.field(1).equalsIgnoreCase(selfName)) return;
        SeeAsk ask = seeAsks.get(e.field(2));
        if (ask == null) return;                                // timed out already, or not ours
        int i, n;
        try {
            i = Integer.parseInt(e.field(3));
            n = Integer.parseInt(e.field(4));
        } catch (NumberFormatException ex) {
            return;
        }
        if (n <= 0 || n > 100 || i < 0 || i >= n) return;
        String[] lines = ask.lines();
        if (lines == null || lines.length != n) {
            lines = new String[n];
            seeAsks.put(e.field(2), ask = new SeeAsk(ask.bot(), ask.deadline(), lines));
        }
        lines[i] = e.field(5);
        if (java.util.Arrays.stream(lines).anyMatch(java.util.Objects::isNull)) return;
        seeAsks.remove(e.field(2));
        if (hands == null) return;
        // when it was taken: an answer read later in chat or scrollback said nothing about its age (owner, 2026-09-29)
        String at = java.time.LocalTime.now().withNano(0).toString();
        hands.notifyLocal(e.field(0) + " status (at " + at + "):");
        for (String l : lines) hands.notifyLocal("  " + l);
    }

    /** The backstop: no (complete) answer in time. */
    private void expireSeeAsks(long now) {
        if (seeAsks.isEmpty()) return;
        var it = seeAsks.values().iterator();
        while (it.hasNext()) {
            SeeAsk a = it.next();
            if (now < a.deadline()) continue;
            it.remove();
            if (hands == null) continue;
            if (a.lines() == null) {
                hands.notifyLocal("no answer from " + a.bot() + " in " + SEE_TIMEOUT_MILLIS / 1000
                        + "s — not online, not on the local bus, or on another team key");
            } else {
                hands.notifyLocal(a.bot() + " status (some lines lost on the way):");
                for (String l : a.lines()) if (l != null) hands.notifyLocal("  " + l);
            }
        }
    }

    /** Ask a bot (by name) to /msg us its decisions, or to stop. */
    public String watch(String botName, boolean on) {
        if (address == null) return "not in a world";
        WorldView w = current;
        int x = w == null ? 0 : (int) Math.floor(w.position().x()), z = w == null ? 0 : (int) Math.floor(w.position().z());
        bus.publish(serverId, w == null ? 0 : w.day(), x, z, MessageTypes.WATCH, List.of(selfName, botName, on ? "on" : "off"));
        return on ? "asked " + botName + " to /msg you its decisions (for 30 min; /" + config.commandRoot + " watch " + botName + " off to stop)"
                  : "asked " + botName + " to stop";
    }

    private void onWatch(String watcher, String botName, boolean on) {
        if (!botName.equalsIgnoreCase(selfName) || watcher.equalsIgnoreCase(selfName)) return;
        if (on) {
            boolean isNew = watchers.put(watcher, clock.getAsLong() + WATCH_FOR_MILLIS) == null;
            if (isNew) chatOut.whisper(watcher, "watching — you'll get my decisions here for 30 min. /"
                    + config.commandRoot + " watch " + selfName + " off to stop.");
        } else if (watchers.remove(watcher) != null) {
            chatOut.whisper(watcher, "stopped sending you my decisions.");
        }
    }

    private void toWatchers(dev.yuliang.zymbot.core.brain.DecisionLog.Entry entry) {
        if (watchers.isEmpty()) return;
        long now = clock.getAsLong();
        watchers.values().removeIf(until -> until < now);
        for (String w : watchers.keySet()) chatOut.whisper(w, entry.toString());
    }

    /** Break one block like a player and pick up what drops (PHASE3.md build step 2, a debug order). */
    public String punch(int x, int y, int z) {
        String no = oneShotRefusal();
        if (no != null) return no;
        BlockPos target = new BlockPos(x, y, z);
        String where = x + " " + y + " " + z;
        String why = "ordered by /" + config.commandRoot + " debug punch";
        String lent = oneShot(Objective.of("break the block at " + where, why,
                (world, h) -> new dev.yuliang.zymbot.core.task.BreakTask(h, target, why, log::record)));
        String id = current == null ? "the block" : current.blockAt(target);
        return "breaking " + id + " at " + where + pathfinderWarning() + lent;
    }

    public String look(String name) {
        String no = needsControl();
        if (no != null) return no;
        if (current == null || current.player(name).isEmpty()) return "can't see " + name;
        brain.order(Objective.of("look at " + name, "ordered by /" + config.commandRoot + " look",
                (world, h) -> world.player(name).<Task>map(p -> Task.once("looking at " + name, () -> h.lookAt(eyes(p))))
                        .orElseGet(() -> Task.failed("look at " + name, "can't see " + name))));
        return "looking at " + name;
    }

    /**
     * Every food carried, with the values the game reports right now (the server's pack may change
     * them) and what Spice of Fabric would leave of it after recent meals.
     */
    public List<String> foods() {
        WorldView w = current;
        if (w == null) return List.of("not in a world");
        java.util.Map<String, dev.yuliang.zymbot.core.api.ItemView> kinds = new java.util.TreeMap<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (var i : w.inventory()) {
            if (!i.edible()) continue;
            kinds.putIfAbsent(i.id(), i);
            counts.merge(i.id(), i.count(), Integer::sum);
        }
        if (kinds.isEmpty()) return List.of("no food carried");
        List<String> out = new ArrayList<>();
        out.add("food: hunger / saturation — the game's live values for this player (Spice of Fabric already applied)");
        for (var i : kinds.values()) {
            var f = i.food();
            boolean skipped = !FoodChooser.safe(i, config.neverEat);
            out.add(String.format(Locale.ROOT, "  %d %s: %d / %.1f%s%s%s%s", counts.get(i.id()), i.id().replace("minecraft:", ""),
                    f.nutrition(), f.saturation(),
                    f.heals() ? ", heals (kept for emergencies)" : "", f.canAlwaysEat() ? ", always edible" : "",
                    f.harmful() ? ", HARMFUL" : "", skipped ? " — never eaten" : ""));
        }
        return out;
    }

    /** Drop the current order; the bot stays running. */
    public String cancel() {
        if (!brain.hasOrder()) return "no order to cancel";
        brain.cancelOrder("cancelled by /" + config.commandRoot + " cancel");
        return "cancelled";
    }

    /** Change a body threshold (leash / eat / critical) and save it. */
    public String set(String name, int value) {
        String problem = config.setTunable(name.toLowerCase(Locale.ROOT), value);
        if (problem != null) return problem;
        ConfigIO.save(configFile, config);
        log.record("set " + name + " = " + value, "changed by a player");
        return name + " = " + value;
    }

    /** "modpack" (run at the first hit) or vanilla "easy" / "normal" / "hard" (run at critical health). */
    public String setDanger(String mode) {
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if (!ZymbotConfig.DANGER_MODES.contains(m)) return "danger must be one of " + String.join(", ", ZymbotConfig.DANGER_MODES);
        config.danger = m;
        ConfigIO.save(configFile, config);
        log.record("danger = " + m, "changed by a player");
        return describeDanger();
    }

    public String describeDanger() {
        return "danger: " + config.danger + " — " + (config.runsAtFirstHit()
                ? "runs at the first hit from a mob; heals at health ≤ " + config.criticalFor()
                : "heals or runs at health ≤ " + config.criticalFor());
    }

    /** A line of chat reached this client. */
    public void onChat(String sender, String text, boolean whisper) {
        chatIn.heard(new ChatIn.Line(sender, text, whisper, clock.getAsLong()));
    }

    /** Swimming costs ~85× walking per block: always say when and why. */
    private void reportSwim(String why) {
        log.record("swimming", why);
    }

    private String needsControl() {
        if (phase.controlling()) return null;
        // a human's account: never suggest start - the owner did, and Zymbot took over their own player (2026-09-26)
        if (role() == ZymbotConfig.Role.TEAMMATE) return "this account is a Teammate — here only one-shot orders work"
                + " (goto, come, punch, grave), each borrowing your controls for that one task; send standing orders to a bot";
        if (role() != ZymbotConfig.Role.BOT) return "this account isn't a Bot account — orders only work on a Bot account"
                + " (send them to the bot, e.g. from its console)";
        return "the bot isn't running — /" + config.commandRoot + " start first";
    }

    /**
     * Null if a one-shot order (goto, come, punch, grave) may run now: the bot is running, or this is
     * a Teammate account in a world — it lends the controls for that one order (PHASE3_FIXLIST #6:
     * "walk me to my grave" is a handy errand for a human, and /zbot start would take their player
     * over for good). Standing jobs (follow, the planner) still need a start.
     */
    private String oneShotRefusal() {
        if (phase.controlling()) return null;
        if (role() == ZymbotConfig.Role.TEAMMATE && address != null) return null;
        return needsControl();
    }

    /** Give the brain a one-shot order, borrowing a Teammate's controls for it; the reply's tail. */
    private String oneShot(Objective order) {
        boolean borrow = !phase.controlling();
        if (borrow) {
            if (borrowedFor == null) log.record("borrowed the controls", "this Teammate account ordered \"" + order.name()
                    + "\" — only until it's done, or a movement key is touched");
            borrowedFor = order.name();
        }
        brain.order(order);
        return borrow ? " — borrowing your controls for this; touch a movement key to take them back" : "";
    }

    /** The controls are lent for one order right now (a Teammate account). */
    public boolean isBorrowing() { return borrowedFor != null; }

    private String pathfinderWarning() {
        return hands != null && !hands.paths().available() ? " — but " + hands.paths().name() : "";
    }

    private static dev.yuliang.zymbot.core.api.Vec3 eyes(EntityView p) {
        return new dev.yuliang.zymbot.core.api.Vec3(p.pos().x(), p.pos().y() + 1.62, p.pos().z());
    }

    private boolean workingOnItsOwn() { return brain.autonomous() && !regroup.active(); }   // regroup is the walk back

    /**
     * Who of the team is on this server: the tab list, checked once a second. Says when someone
     * leaves, and when someone we saw earlier this session comes back (PHASE2.md §2).
     */
    private void trackTabList(WorldView world, long now) {
        if (address == null || now < nextRosterCheck) return;
        nextRosterCheck = now + 1000;
        var online = world.onlinePlayers();
        for (var e : memory.roster.entrySet()) {
            boolean on;
            try {
                on = online.contains(UUID.fromString(e.getKey()));
            } catch (IllegalArgumentException bad) {
                continue;
            }
            String key = e.getKey(), name = e.getValue().name;
            if (on && inTabList.add(key)) {
                if (!seenOnline.add(key)) log.record(name + " is back", "in the tab list again");
            } else if (!on && inTabList.remove(key)) {
                log.record(name + " left", "gone from the tab list");
            }
        }
    }

    /** ROSTER on the bus: everyone we know, so early and late joiners converge (BOT_DESIGN §2.23). */
    private void shareRoster() {
        WorldView w = current;
        if (w == null) return;
        bus.publish(serverId, w.day(), (int) Math.floor(w.position().x()), (int) Math.floor(w.position().z()),
                MessageTypes.ROSTER, List.of(String.join(",", memory.roster.keySet()), Integer.toString(memory.roster.size())));
    }

    /** /zbot roster: each member, Bot or Teammate, online or not, last heard, where, and how we know. */
    public List<String> roster() {
        if (memory.roster.isEmpty()) return List.of("roster: nobody yet — no HELLO heard on this server");
        long now = clock.getAsLong();
        List<String> out = new ArrayList<>();
        out.add("roster (" + memory.roster.size() + "):");
        for (var e : memory.roster.entrySet()) {
            var r = e.getValue();
            boolean on = inTabList.contains(e.getKey());
            var seen = current == null ? java.util.Optional.<EntityView>empty()
                    : current.nearby().stream().filter(p -> p.uuid().toString().equals(e.getKey())).findFirst();
            String role = r.label();                              // by role: "Teammate (bot driving)", not "Bot"
            String where = seen.map(p -> Math.round(p.pos().x()) + ", " + Math.round(p.pos().z()) + " (seen)")
                    .orElse(r.x + ", " + r.z + " (bus)");
            out.add("  " + r.name + " — " + role + ", " + (on ? "online" : "offline") + ", heard "
                    + ago(now - r.lastSeenMillis) + ", at " + where);
        }
        return out;
    }

    /** Ask the team for fresh positions: teammates answer with a HELLO at once. */
    private void askWhere() {
        WorldView w = current;
        if (w == null) return;
        bus.publish(serverId, w.day(), (int) Math.floor(w.position().x()), (int) Math.floor(w.position().z()),
                MessageTypes.WHERE, List.of(selfName));
    }

    /** /zbot regroup: look for the team now (normally automatic on start and after a respawn). */
    // ------------------------------------------------------------------ survey (PHASE3.md §2)

    /** The latest survey, if one has finished. What the planner asks "nearest tree?" of. */
    public java.util.Optional<dev.yuliang.zymbot.core.survey.Survey> survey() { return java.util.Optional.ofNullable(survey); }

    /** A finished survey is kept and logged; a wanted one starts once we're alive and up. */
    private void pollSurvey(WorldView world, long now) {
        if (surveying != null && surveying.isDone()) {
            try {
                survey = surveying.join();
                log.record("surveyed", survey.why() + " : " + survey.summary());
                // the asker saw only "surveying in the background"; the new one used to reach the log
                // alone, leaving the old one in chat (2026-09-27): print it to them now
                if (survey.why().equals(surveyToChat) && hands != null) {
                    surveyToChat = null;
                    survey.lines(now, BlockPos.of(world.position())).forEach(hands::notifyLocal);
                }
            } catch (RuntimeException e) {
                LOG.warn("[zymbot] survey failed: {}", e.toString());
            }
            surveying = null;
        }
        if (surveyWanted != null && surveying == null && address != null && !world.isDead() && world.downedSecondsLeft() < 0) {
            String why = surveyWanted;
            surveyWanted = null;
            surveying = dev.yuliang.zymbot.core.survey.Surveyor.take(world, why, now, config.planTimeoutMs);
            if (surveying.isDone()) pollSurvey(world, now);      // tests: grouped at once
        }
    }

    /** /zbot debug survey: look around now, and print what was found. Read only. */
    public List<String> surveyNow() {
        WorldView w = current;
        if (w == null || address == null) return List.of("not in a world");
        String why = "asked by /" + config.commandRoot + " debug survey";
        surveyWanted = why;
        dev.yuliang.zymbot.core.survey.Survey before = survey;
        long now = clock.getAsLong();
        BlockPos here = BlockPos.of(w.position());
        pollSurvey(w, now);
        if (survey != null && survey != before) return survey.lines(now, here);
        surveyToChat = why;                                      // pollSurvey prints it when the scan finishes
        List<String> out = new ArrayList<>();
        out.add("surveying in the background — it'll show here when done");
        if (before != null) {
            out.add("meanwhile, the last one:");
            out.addAll(before.lines(now, here));
        }
        return out;
    }

    public String regroupNow() {
        String no = needsControl();
        if (no != null) return no;
        regroup.arm();
        return "looking for the team — regrouping unless a teammate is within " + config.regroupWithin + " blocks";
    }

    private boolean noOrder() { return !brain.hasOrder(); }

    /** No order, and no objective crossing on purpose (a regroup may swim) — the stranded reflex may act. */
    private boolean nothingToDo() { return noOrder() && !regroup.active() && !"leash".equals(brain.runningReflex()); }

    /**
     * Idle, for the leash's idle follow (PHASE2.md check 6): no order, no regroup armed or under way
     * (out of sight or far off at a start is the regroup's job), and nothing running but its own follow.
     */
    private boolean idleForTheLeash() {
        return noOrder() && !regroup.armed() && !regroup.active()
                && (brain.idle() || "leash".equals(brain.runningReflex()));
    }

    /** Idle, for stepping out of shallow water: nothing to do, and nothing running but its own walk out. */
    private boolean idleForWading() {
        return nothingToDo() && (brain.idle() || "wading".equals(brain.runningReflex()));
    }

    /**
     * A player we've heard announce itself as a controlling bot. Everyone else is a human — a
     * Teammate account with the bot driving too: it's still the owner's player.
     */
    private boolean isKnownBot(UUID id) {
        BotMemory.RosterEntry r = memory.roster.get(id.toString());
        return r != null && !r.teammate() && r.botDriving();
    }

    /**
     * A player whose game runs Zymbot as Teammate: heard announcing it on the bus (signed with the
     * team key), or listed as one in our accounts. Only these anchor a bot (PHASE2.md P2-1).
     */
    private boolean isTeammate(UUID id) {
        if (id.equals(selfId)) return false;
        if (config.roleOf(id) == ZymbotConfig.Role.TEAMMATE) return true;
        BotMemory.RosterEntry r = memory.roster.get(id.toString());
        return r != null && r.teammate();
    }

    private void saveMemory() {
        if (address == null) return;
        memoryStore.save(memoryFile(), memory);
        memoryDirty = false;
        nextSave = clock.getAsLong() + SAVE_EVERY_MILLIS;
    }

    // ------------------------------------------------------------------ accessors

    public ChatIn chatIn() { return chatIn; }
    public ChatOut chatOut() { return chatOut; }
    public HungerMeter hungerMeter() { return hungerMeter; }
    public boolean hasOrder() { return brain.hasOrder(); }

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

    /** "40s ago", "16 min ago", "3 h ago", "2 days ago". */
    static String ago(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 90) return s + "s ago";
        if (s < 90 * 60) return (s / 60) + " min ago";
        if (s < 36 * 3600) return (s / 3600) + " h ago";
        return (s / 86400) + " days ago";
    }

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
