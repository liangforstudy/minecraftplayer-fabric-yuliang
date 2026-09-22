# Foundation — Phase 0

The parts of the bot that everything else is built on, and so are the most expensive to change
later. **Phase 0 adds no gameplay.** It's plumbing, done once, done right.

Decided: **Java**, **Stonecutter from day one** (only 1.21.1 active; 1.21.8 — our earlier test
server — is a later switch), Baritone for movement (BOT_DESIGN.md R21). Architectury waits until
a Forge port is real; the core doesn't care about the loader.

---

## The ten foundation decisions

| # | decision | shape |
|---|---|---|
| 1 | **Module split** | `core/` pure Java, no Minecraft imports — enforced by the build, not by discipline. `adapter-fabric/` is the only code that touches Minecraft. |
| 2 | **Multi-version build** | Stonecutter over the adapter only. Core is version-free. |
| 3 | **Everything is a task** | Every action ("walk to", "eat", "mine") is a cancellable task reporting `RUNNING / DONE / FAILED(reason)`. Interrupts pause tasks cleanly. |
| 4 | **Tick model** | Core runs once per client tick, single-threaded, on a world snapshot. Only Baritone runs its own thread. |
| 5 | **Decision model** | Priority stack of interrupts above scored objectives (the milestone ladder); combat is its own state machine. See BOT_BEHAVIOUR.md. |
| 6 | **Message protocol** | Versioned, compact, transport-independent — see [Communication stack](#communication-stack). |
| 7 | **Data pack schema** | Namespaced ids, `schema_version` in every file, fixed override order. |
| 8 | **Bot memory** | One store per (server, bot, world), versioned, with migrations. |
| 9 | **Visibility** | Every decision logs *what* and *why*; `/<root> status` shows the current task and its reason. |
| 10 | **Fake world** | A simulated world implementing the same interfaces, so behaviour is tested in seconds, not 90-second boots. |

**Phase 0 is done when** Bot1 joins, auto-respawns, answers `status` with "idle — no
objectives", obeys `stop`, pauses on human WASD, sends HELLO over every enabled transport — and
auto-starts only on a whitelisted server.

**✅ Done — verified in the real game, 2026-09-22 (Zymbot 0.0.5, headless Bot1 + the host's client):**

| check | evidence |
|---|---|
| autostart only on listed servers | `started: DISCOVERY — because this account is a Bot, and 127.0.0.1:25565 is on the server list` |
| HELLO both ways | Bot1's status: `Bot2 — TEAMMATE, last 35s ago`; host client heard Bot1 over the signed + encrypted local bus |
| `status` | `doing: idle — no objectives` (read from the headless bot via its console: `send.sh bot1 "/zbot status"`) |
| `stop` / `start` | `stopped — because stopped by /zbot stop`, then back to DISCOVERY |
| auto-respawn | `doImmediateRespawn false`, `/kill Bot1` → `respawning — because died` in the same second |
| WASD pause | paused on W, warning 7 s after release, resumed at 10 s; `/zbot stop` returned the host to Teammate |

Found and fixed along the way: vanilla LAN is always online-mode (OfflineLAN), host-vs-guest server
identity (now the tab list), hmc-specifics needing JLine off, sync touching a running rig.

---

## The brain's shape — a sketch

**Illustrative, not final code.** This is the shape Phase 0 builds: the brain (`core/`) sees the
game only through two interfaces, runs everything as tasks, and asks interrupts before
objectives. Every later behaviour — farming, forecast, combat — is one more interrupt or objective
in this same loop.

**Senses and hands** — the adapter implements these for the real game, the fake world for tests:

```java
// core/api/WorldView.java  — no Minecraft imports anywhere in core/
public interface WorldView {
    Vec3 position();
    float health();                 // 0..20
    float hunger();                 // 0..20
    long timeOfDay();               // 0..24000
    Optional<BlockPos> nearest(String placeTag);   // "bed", "village", ...
}

public interface Hands {
    Task walkTo(BlockPos target);   // Baritone behind this, in the adapter
    Task eat(String itemId);
    void send(Message msg);         // bus: local, relay, or chat
}
```

**Everything is a task** — cancellable, and it reports how it's going:

```java
public interface Task {
    enum Status { RUNNING, DONE, FAILED }
    Status tick();                  // called once per game tick
    void cancel();                  // an interrupt can stop it cleanly
    String describe();              // shown by /<root> status
}
```

**The loop** — interrupts first, then the best objective:

```java
public final class Brain {
    private final List<Interrupt> interrupts;   // ordered: survive, flee, eat...
    private final Planner planner;              // the milestone ladder
    private Task current;
    private String reason = "idle — no objectives";

    public void tick(WorldView world, Hands hands) {
        for (Interrupt i : interrupts) {
            if (i.triggered(world)) {
                switchTo(i.respond(world, hands), i.why(world));
                return;
            }
        }
        if (current == null || current.tick() != Task.Status.RUNNING) {
            Objective next = planner.best(world);
            switchTo(next.start(world, hands), next.why());
        }
    }

    private void switchTo(Task task, String why) {
        if (current != null && current != task) current.cancel();
        current = task;
        reason = why;               // visibility: every decision says why
        log.info("{} — because {}", task.describe(), why);
    }
}
```

**One interrupt** — blood-moon shelter:

```java
public final class BloodMoonShelter implements Interrupt {
    public boolean triggered(WorldView w) {
        return w.timeOfDay() > 12000
            && forecast.myVerdict("blood_moon") > threshold(w);   // hurt bots play safer
    }
    public Task respond(WorldView w, Hands h) {
        return h.walkTo(w.nearest("shelter").orElseThrow());
    }
    public String why(WorldView w) {
        return "blood moon likely (%.0f%%)".formatted(forecast.myVerdict("blood_moon") * 100);
    }
}
```

`/<root> status` then prints e.g. *"walking to shelter — because blood moon likely (81%)"*.

**Fake world** — the same brain, driven by a scripted scenario instead of Minecraft; runs in
milliseconds, same result every time:

```java
world = FakeWorld.builder()
    .time(11500)                      // just before dusk
    .health(14).hunger(8)
    .bed(at(100, 64, 20))
    .reading("Bot3", "blood", 0.85)   // a teammate reports a blood moon
    .build();

bot.runUntilIdle(world);
assert bot.currentTask() == "shelter";
```

---

## Command root — configurable

The command name is **not hardcoded**. `/bot` is exactly the kind of name another mod or a
server plugin will also want, and a client-side command shadows a server command of the same
name — so a clash silently breaks one of them.

```json5
// config/<modid>.json5
command_root: "zbot",          // registered at startup; change needs a restart
command_aliases: [],           // optional extra names
```

Every doc writes `/<root>`; examples use the default. The root is read once at client init
(Fabric registers client commands then), so a change needs a restart — that's a one-time cost
to avoid a permanent conflict.

---

## Server whitelist — autostart

```json5
autostart_servers: [
  "play.example.net",           // hostname, default port 25565
  "127.0.0.1:25565",            // LAN test world
],
```

Each **account** also has a role (Mod Menu → Zymbot → Accounts, or `/<root> role`), matched by
UUID because names change on real servers:

| on join | **Bot** account | **Teammate** account (you) | unlisted account |
|---|---|---|---|
| server **in** the list | takes control → **DISCOVERY** | **announces** (HELLO, shares readings), never takes control | silent |
| server **not** in the list | silent | silent | silent |

- `/<root> start` still hands control over by hand, for any role; `stop` returns a Teammate to
  announcing, and a second `stop` goes silent.
- Menu changes apply live, with one exception: giving an account the Bot role never grabs the
  controls from a menu click — it takes effect on the next join.
- A headless client with no accounts listed counts as a Bot (nobody else can be playing it);
  `setup` lists each rig explicitly anyway.
- Singleplayer follows the same rules: add `"singleplayer"` to the server list.

Matching is on the address the player joined with, lower-cased, `:25565` implied. Commands:
`/<root> autostart add` (adds the current server), `remove`, `list`.

**Why a whitelist, not a toggle:** a bot mod that silently starts playing on a random public
server is the fastest way to get a player banned. Opt-in per server is the safe default.

---

## Communication stack

Minecraft chat is one transport, not *the* transport. Four transports carry the same messages:

| transport | reach | who can use it | phase |
|---|---|---|---|
| **local bus** — UDP multicast on the LAN + loopback | same machine / same network | the owner's own bots | 2 |
| **relay** — MQTT over TLS to a broker | anywhere on the internet | bots (and modded humans) of one team | later |
| **server chat** — `/msg` or public | whatever the server allows | **everyone, including humans without the mod** | 2 |
| *(fake bus)* | in-process | tests | 0 |

No existing mod does client-to-client messaging outside the server (searched 2026-09-22 —
only chat cosmetics, Discord bridges, and generic P2P libraries), so this is ours.

### Rules every message follows

- **Envelope:** `zb1 <msg-id> <sender-uuid> <server-id> <sent-at> <day> <x,z> <TYPE> <fields…> #<sig>`.
  The version lets old and new bots coexist; unknown types are ignored, not errors.
- **Dedupe by `msg-id`.** The same message can arrive by local bus *and* chat; it counts once.
- **"Same server" means "in my tab list".** A message only counts if its sender's UUID is in the
  receiver's tab list right now — the one thing the host and every guest see identically. Not
  the address: a LAN host is "singleplayer" to itself while guests joined `127.0.0.1:25565`, so
  address-based ids never matched (found live, 2026-09-22). A sender not yet listed gets a 10 s
  grace period (a HELLO can beat the tab-list update). `server-id` in the envelope is kept only as
  the key for the bot's saved memory.
- **Position travels with every message**, so a receiver can apply a *virtual range* if the
  game design calls for "within N blocks" — the transport's reach and the game rule are separate.
- **Signed with a team key** (HMAC), and **encrypted on every transport that leaves the
  process** (AES-256-GCM → `zbx <blob>`) — the LAN included, not just the relay: on café Wi-Fi a
  signed-but-plain message still shows bot positions to anyone listening. Without the key, a
  stranger can neither read nor fake a message (a fake blood-moon reading would make every bot
  hide all night). The relay topic is derived from `hash(server-id + team key)`, so strangers
  can't even find it.
- **Never an empty key.** The mod generates a random team key on first run; headless `setup`
  gives every local bot the same one (`headless/team-key.txt`, gitignored); `bots.py team-key`
  shows it for your own client's config.
- **Replays are dropped.** `sent-at` is signed; anything older than 60 s (or dated in the
  future) is rejected even with a valid signature. Machines need roughly correct clocks — status
  reports "stale" messages if one is off.
- **Humans can't use coordinates.** The live server hides them from F3; only bots know exact
  positions. So bot↔bot messages carry xyz, but anything **for a human** is phrased the way a
  human can act on it: *"~300 blocks north-east of spawn"* (a compass points to spawn, the sun
  gives east), a landmark (*"the village by the lake"*), or simply *"follow me"* — a bot can walk
  a human there. A bot is the group's natural navigator.
- **Nothing sensitive in public chat.** Coordinates go over the local bus, relay, or `/msg` —
  never public chat, on a server with grudges.

Why MQTT for the relay: mature small Java clients (Eclipse Paho), free public brokers to test
on, and a self-hosted broker (Mosquitto) runs on a Raspberry Pi. Nostr and WebRTC were the
alternatives; Nostr's Java tooling is thinner, WebRTC needs NAT traversal we don't need.

### Security model — who can do what

| threat | defence |
|---|---|
| a stranger **controls** a bot | control only listens on `127.0.0.1` (the headless console relay, and any future control channel) plus a random per-run token — other machines can't even reach it |
| a stranger **fakes** bot messages | HMAC signature with the team key |
| a stranger **reads** bot messages | AES-GCM on every off-process transport |
| a stranger **replays** an old message | signed timestamp, 60 s window, plus message-id dedupe |
| **no key** configured | a random key is generated; the status line shouts if it's ever empty |
| players type **nasty chat** | chat is untrusted input: strict parsing, value bounds, one reading per player per night, rate limits — chat can never trigger a command |
| **bad downloads** | official sources only, checked against the published checksum/digest |
| the **key leaks** | it lives in each game's `config/zymbot.json`, outside git |

This is the bots' version of humans sharing coordinates on Discord — no more power than a
coordinated human team already has.

---

## Lunar forecast — the human syntax

Humans without the mod only have chat. They type a short tagged message, **public or as a
`/msg` to any bot**. The prefix `!lf` is configurable (`human_forecast_prefix`), for servers
where `!` is taken.

```
!lf blood 70        tonight: blood moon, 70% certain (copy what /lunarforecast showed)
!lf super 40        super blood moon, 40%
!lf harvest 30      harvest moon (also: blue)
!lf clear 85        no event, 85%
!lf blood 70 r2     optional: my LIBRARIAN rank is 2
!lf guess blood 80  my verdict (what I'd bet on), not a reading
!lf ?               a bot's current verdict
!lf readings        every reading that bot received tonight
!lf guesses         that bot's verdict history tonight, step by step
!lf help            this list
```

| word | event (Enhanced Celestials, server datapack) |
|---|---|
| `blood` | `blood_moon` |
| `super` | `super_blood_moon` |
| `clear` | none tonight |
| `blue` | `blue_moon` (+ super) — Luck, enchanting/anvil costs halved |
| `harvest` | `harvest_moon` (+ super) — crop drops ×2 (×4) |

- Bots **acknowledge by `/msg`** — *"got it: blood 70% (rank 0). my guess: blood 81%, step 3"* —
  so a human knows it landed.
- A human reading enters the same `readings[(uuid, day)]` table as a bot's `READ`, marked
  `source=human`. Sending again the same night replaces it (like `AMEND`).

### Readings and verdicts are both shared

Two different things travel on the bus, and both are public to the team:

| | what it is | message |
|---|---|---|
| **reading** | what `/lunarforecast` told someone — raw evidence | `READ` / `AMEND` |
| **verdict** | what someone *concludes* from everything they've heard, and what they'll `/guessforecast` | `GUESS <uuid> <day> <event> <p> <step>` |

- **Every bot forms its own verdict** from the readings and verdicts it has heard, makes **its
  own `/guessforecast`**, and banks its own class XP (`FORECAST_GUESS_XP_BASE = 160`) if right.
  **A wrong guess costs nothing** (the owner's choice, to discourage gambling), so every bot always guesses. The payout is
  `160 × (1 − own confidence)` for agreeing with your own `/lunarforecast`, `160 × own confidence`
  for going against it — so each bot picks the guess with the best *P(event) × payout*. That makes
  shared readings and verdicts worth real XP: when the team's evidence contradicts a bot's own
  reading, the contrarian guess pays up to 160.
- **Verdicts are published and re-published.** Each revision is a new `step`; the whole history
  is kept and anyone — bot or human — can fetch it (`!lf guesses`, `/<root> forecast <bot>`).
- **Verdicts can feed verdicts.** A bot may weigh others' verdicts into its own, so opinions can
  echo and cascade around the group. **That's intended** — it's part of the game, the same way
  human players sway each other. Readings are always kept separately from verdicts, so anyone can
  inspect what the raw evidence actually was. How much weight a bot gives other verdicts is a
  per-bot setting (`verdict_weight`).
- Each bot still decides **shelter or carry on** by its own threshold — a hurt bot can play safer
  on the same number.

### Public requests get one answer, not one per bot

A `/msg` to a bot is answered by that bot, by `/msg`. In public, per-bot questions (`!lf ?`,
`readings`, `guesses`) are answered by each bot **privately by `/msg`** to the asker — public chat
stays clean. A public request that needs only one reply (`!lf help`) must not get five identical
answers from five bots —
and it must still get answered if the "obvious" bot missed it (out of range, busy, offline).

```
every bot that heard the request:
    rank = position of my uuid in sort_by(hash(msg-id + uuid))      # same order on every bot
    wait rank × 1.5 s
    if someone already answered (seen in chat, or ANSWERED <msg-id> on the bus): stay quiet
    else: answer, and send ANSWERED <msg-id> on the bus
```

No leader, no election. Every bot computes the same queue from the message id; the first in
line answers, and if it didn't hear the request, the next one does 1.5 s later. The order changes
per message, so the job spreads around. Bots that answered the same request within ~60 s don't
repeat themselves.

Humans *with* the mod never type this — their client reads their own `/lunarforecast` and
publishes a `READ` automatically.

---

## `!where` — bots as navigators *(exact mode pending server-owner OK)*

The live server hides coordinates from players; bots know them exactly (BOT_DESIGN.md R44).
This lets a bot answer "where am I?" — which also works against a rule the owner chose, so it's
**off by default until the owner decides.**

```
!where              where am I?
!where Bot1         where is that bot, relative to me?
!where spawn        which way, and how far, to spawn?
!where village      nearest known village
!where town         nearest known town (civfabric, ≥3 beds)
!where base         our base — also: farm, bed, portal, chest
!where list         every kind of place this bot knows
!where help         this list
```

**Every human command family has a `help`** (`!lf help`, `!where help`, and any added later).
Same rules as `!lf help`: asked by `/msg` → that bot replies by `/msg`; asked in public → exactly
one bot replies ([one-answer rule](#public-requests-get-one-answer-not-one-per-bot)). The
keyword `help` is configurable (`help_keyword`), like the prefixes.

**Places come from what bots have actually seen.** Every structure a bot passes — villages,
towns, portals, and the team's own base, farms and beds — goes into its persistent map (decision
8), and teammates share theirs over the bus. So `!where village` means *"the nearest village any
of us has found"*, never a guess. Nothing known → *"none found yet"*.

**Who may ask about what:**

| place | who gets an answer |
|---|---|
| world features — village, spawn, portal we didn't build | anyone who asks |
| **our places** — base, farm, bed, chest, town we built | **teammates only** (the roster) |

On a server with grudges, a bot that tells any stranger where the team's beds are is a
liability — so our own places are answered only to players on the roster.

The kinds of place are **data, not code** — each has a tag in a pack (`village`, `town`,
`portal`, …) with its visibility. A new modpack's structures become `!where` targets by adding
rows, not by writing code.

| mode | answer | default |
|---|---|---|
| `off` | ignored | — |
| `relative` | *"~300 blocks NE of spawn"*, *"Bot1 is ~120 blocks west of you"* — direction by compass point, distance rounded | **default** |
| `exact` | *"you're at 412, 68, −1290"* | only where the owner allows it |

```json5
share_coords_with_humans: "relative",   // off | relative | exact
```

- **A bot only answers about a player it can currently see** (within its render distance) — so
  only *nearby* bots can answer, by construction.
- **Always by `/msg`**, never public — coordinates stay out of public chat on a grudge server.
- In public, the [one-answer rule](#public-requests-get-one-answer-not-one-per-bot) applies:
  exactly one nearby bot replies.
- `relative` is the safe middle ground: direction and rough distance, the same help a friend
  pointing the way would give — useful even where exact coordinates aren't allowed.

## Later phases

1. **Body** — walk (Baritone), look, eat, inventory, chat in/out, survival interrupts.
2. **Discovery and regroup** — whitelist autostart, civilization assessment, `/msg` probe,
   roster, local bus, world spawn as the fallback meeting point.
3. **Early game** — M0–M5 (wood, tools, food, wooden hoe, bed).
4. **Economy** — FARMER/MINER rules, bed rest, food rotation, forecast.
5. **Combat → Nether → End.**
6. **Ports and rules** — 1.21.8, vanilla pack, relay transport, user rule files.
