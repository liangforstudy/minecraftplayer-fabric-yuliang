# Phase 2 — Discovery and regroup

Status: **scoped 2026-09-23 · partly built in 0.1.29 (2026-09-26) · deployed, not live-tested yet.**
0.1.29 is on both sides since 2026-09-26: the owner's client (`1.21.1 Modpack`) and Bot1 (synced,
90 mods). Next: the live-test checklist in §8.

| part | status | commit |
|---|---|---|
| P2-1 / P2-2 teammates-only anchor (leash, retreat direction) | built, unit-tested | f6f014b |
| §1 layer 1 — regroup over the local bus (`WHERE`) | built, unit-tested (in-sight path; the bus-only path is live-test only) | 9a51c99 |
| §1 layer 3 — spawn fallback (`probewait`) | built, unit-tested | 3faefde |
| §2 roster — `ROSTER`, tab-list left/back, `/zbot roster` | built, unit-tested | 76e629f |
| §1 layer 2 + §3 — `/msg` probe, team-only chat transport | **not built** (owner: autonomy first, 2026-09-26) | — |
| §4 leash | coded in Phase 1; now teammates-only — see §8 check 6 | — |
| §5 survey | **not built** | — |
| §6 follow via the route planner | **not built** | — |

Builds on [PHASE1.md](PHASE1.md) (the Body, 0.1.24) and
[FOUNDATION.md](FOUNDATION.md) (Phase 0: autostart whitelist, local bus, HELLO, remembered roster,
summon — already built).

**Goal:** after a cold start — everyone respawned hundreds of blocks apart (seen: (28,−82),
(164,59), (−338,244), (585,−282)) — a bot finds out where its team is, walks back to it, and knows
who's in the group. This is the **planner's first objective of its own**; until now an idle bot
only ever stood still.

---

## Decisions

| # | decision | why |
|---|---|---|
| P2-1 | **A bot regroups only to a Zymbot teammate** — a player whose game runs Zymbot as **Teammate** (the owner's client). Never to a human without the mod, never to another Bot | owner, 2026-09-23: the headless bot goes to the player with the client. A human without the mod can't see the bus, answer a probe or give orders |
| P2-2 | **The leash measures to the nearest Zymbot teammate** too, not to any human (`Body.nearestHuman` today counts every non-bot player) | same reason; a stranger walking past on a grudge server must not become the bot's anchor |
| P2-3 | **After regrouping, idle = stand still**, as in Phase 1 (P1-2). `follow` / `come` stay explicit orders | owner's choice; predictable |
| P2-4 | **Whispers only between team members.** The `/msg` probe and the chat transport only ever whisper to players on the roster (Bots and Teammates, all holding the team key), sealed and signed like the local bus. Nobody outside the team is ever whispered to | owner: "only teammates can whisper to each other"; coordinates never appear in plain text |
| P2-5 | **World spawn is the fallback meeting point** when no teammate can be found | owner: yes, use spawn — it isn't dangerous on the live server |
| P2-6 | **Nearest teammate wins** when several are online (among Zymbot teammates only, P2-1) | same rule as the leash, no setup needed |

**Later (roadmap, not Phase 2):** a bot running on a normal (non-headless) client may let a human
**without** the mod control it — e.g. hand its orders to a named player. Until then only Zymbot
teammates can direct a bot.

---

## 1. Regroup — the first objective

On start (and after a respawn), if **no Zymbot teammate is within `regroup_within` blocks**
(default 32), the planner sets the objective *regroup*. Layers, cheapest and most reliable first:

1. **Local bus.** Teammates on this machine / LAN already announce `HELLO` with their position (the
   envelope's x, z; every 60 s). Walk to the nearest one with the route planner (`RouteTask`, the
   same walk as `come`). While regrouping the bot asks for fresh positions (`WHERE`, answered by a
   `HELLO` at once) instead of chasing a 60-second-old one, and retargets on every newer position.
   Once the teammate is in render distance it switches to their live position.
2. **`/msg` probe** — for teammates the bus can't reach (another network, the live server). For
   each roster Teammate that the **tab list** shows online, whisper a sealed `HELLO`; their client
   answers with a sealed `HELLO` (their position). Then as layer 1. See §3 for the transport.
3. **World spawn.** No answer within `probe_wait_seconds` (default 30): walk to world spawn
   (`level.getSharedSpawnPos()`, sent by the server), wait there, keep listening. Any teammate
   heard later → regroup to them.

Every choice is logged: `regrouping — because nobody from the team within 32 blocks; Bot2 is at
412, −90 (bus, 5 s ago)`, `probe — because Bot2 is online but not on the local bus`, `going to
spawn — because no teammate answered in 30 s`.

Regroup ends within `COME_WITHIN` of the teammate (the `come` distance), then the bot is idle (P2-3).
It never starts while the bot has an order (`goto`, `follow`, …) — orders beat objectives, reflexes
beat both, as now.

## 2. Team list (roster)

Already there: `memory.roster` records every bus `HELLO` (name, phase, last heard, x, z).

- **`ROSTER <uuid,...> <n>`** on the bus: what this bot believes the group is. A bot that hears a
  `HELLO` from someone missing from its roster re-broadcasts `ROSTER`, so early and late joiners
  converge (BOT_DESIGN §2.23). No commit step yet (§ Not in Phase 2).
- **Online / left:** cross-checked with the tab list each tick — `Bot2 left — because gone from
  the tab list`; back again → `Bot2 is back`.
- **`/zbot roster`**: each member, Bot or Teammate, online or not, last heard, where, and how
  (bus / whisper / seen).

## 3. Chat transport — team-only whispers

`transports.chat` (config, off today) becomes real, **whispers only** (P2-4):

- Same envelope as the local bus — signed and sealed with the team key — sent as
  `/msg <teammate> <sealed text>`. Only to roster members; public chat is never used for bus
  messages.
- **Incoming Zymbot whispers are hidden** from the chat screen on the receiving client (they're
  for the mod, not the player) and handled as bus messages; anything that doesn't unseal with the
  team key is shown normally.
- **Rate-limited** well under the server's spam kick (vanilla kicks around 10 messages/s): one
  probe per teammate per `probe_wait_seconds`, a small send queue.
- **To verify first** (§6): the sealed text fits in a chat message (256 characters), and the live
  server's `/msg` format is recognised (the pack formats whispers as `<name> whispers to you: …`).

## 4. The leash goes live

`LeashInterrupt` is coded and unit-tested (Phase 1) but never ran, since a bot had no objective of
its own. With regroup as the first one it matters: while working on its own objective, a bot more
than `leash_blocks` from the **nearest Zymbot teammate** (P2-2) walks back. Regroup itself is
exempt (it's the walk back). No teammate online → the leash can't apply; regroup's spawn layer
covers that.

## 5. Survey — read only

On start the bot records what's around it, as BOT_BEHAVIOUR.md → "Startup: assess, don't assume"
describes, but **acts on none of it yet** — Phase 3's milestones (wood, tools, food, bed) will.

| scope | reads |
|---|---|
| self | inventory summary, health, hunger |
| local | beds, crafting tables, furnaces, farmland, chests, campfires within the loaded area (one bounded scan) |
| group | the roster (§2) |
| world | biome, time of day, day number, distance and direction to spawn |

`/zbot survey` prints it and it's logged once per start. The scan runs in the background like the
route planner (a copy taken on the game thread, searched off it).

## 6. Follow uses the route planner

Left over from Phase 1: `follow` still uses Step A's "swim when stalled". It becomes a `RouteTask`
toward the player, re-targeted as they move, so it gets the same dry-first, cheapest-crossing
choices as `goto` and `come`.

---

## 7. Test commands

| command | does |
|---|---|
| `/zbot roster` | the team list: Bot / Teammate, online, last heard, where, how |
| `/zbot survey` | what the bot found around it on start (read only) |
| `/zbot regroup` | run the regroup objective now (normally automatic on start / respawn) |
| `/zbot set regroup <n>` | `regroup_within`, blocks (8–256, default 32) |
| `/zbot set probewait <n>` | `probe_wait_seconds` before going to spawn (5–300, default 30) |

(When built, add each to SKILL.md's command table — `SkillDocTest` checks.) **In 0.1.29:** `roster`,
`regroup`, `set regroup`, `set probewait` — in SKILL.md and `/zbot help`. `survey` is not built.

## 8. Done means — in the LAN world

1. **Cold start, local bus:** you and Bot1 far apart (tp it 300+ blocks away), `/zbot start` → it
   walks to you by itself and stops within a few blocks; decisions say why at each step.
2. **Moving target:** you walk away while it regroups → it retargets on newer positions.
3. **Probe:** local bus off on Bot1 (`transports.local_bus = false`), chat on → it whispers, your
   client answers invisibly, it walks to you. Your chat shows no gibberish.
4. **Spawn fallback:** your client with Zymbot as None (not on the team) → Bot1 walks to spawn and
   waits; switch back to Teammate → it comes to you.
5. **Only teammates:** a second human *without* the mod stands closer than you → Bot1 ignores
   them, for regroup and for the leash.
6. **Leash:** during a regroup-then-idle cycle, walk past `leash_blocks` → it follows back.
7. **Roster:** Bot1 and Bot3 (when synced) agree on the roster; a leaver is noticed.
8. **Survey:** next to a bed and a crafting table, `/zbot survey` lists both.

### Live-test checklist (0.1.29)

Both sides need 0.1.29 — the owner's client answers `WHERE` and shares the roster. **Done
2026-09-26** (rebuilt, client jar swapped with the game closed, Bot1 synced). To start a test:
`play.bat "New World"` (your game first, Bot1 once the world loads), then the checks below. Record
each result here — pass/fail, the `[decision]` lines that show it, and anything that surprised.

| # | check | can test now? | how |
|---|---|---|---|
| 1 | cold start, local bus | yes | `/tp Bot1 ~300 ~ ~`, then `/zbot regroup` (as Bot1: `send.bat bot1 /zbot regroup`) → walks back, stops within ~4 blocks; `[decision] regrouping with …` names the reason |
| 1b | respawn arms it | yes | `/kill Bot1` far from you → after the respawn it regroups by itself |
| 1c | out of sight (bus only) | yes | `/tp` it 300+ blocks away, beyond render distance → "(bus, N s ago)" in the reason, `WHERE` asks every 10 s |
| 2 | moving target | yes | walk off while it regroups → it re-plans when you've moved 8+ blocks |
| 3 | `/msg` probe | **no** | not built (§3) |
| 4 | spawn fallback | yes | your client's role None (`/zbot role none`), `/zbot regroup` on Bot1 → after 30 s "going to spawn"; set yourself back to Teammate → it comes to you |
| 5 | only teammates | needs a 2nd human without the mod | a stranger closer than you is ignored for regroup, leash and retreat direction |
| 6 | leash | **open question** | after a regroup the bot is idle (P2-3), and the leash only applies to its own objectives — so walking off from an idle bot does nothing by design. Decide: should an idle bot follow at `leash_blocks`, or re-regroup? |
| 7 | roster | yes (Bot1 + you); Bot3 when synced | `/zbot roster` on both sides; disconnect Bot1 (`disconnect.bat bot1`) → "Bot1 left — gone from the tab list"; reconnect → "is back" |
| 8 | survey | **no** | not built (§5) |

## Not in Phase 2

- the second `/zbot start` that **freezes the roster** and assigns scouting sectors
  (BOT_DESIGN §2.23 COMMIT) — belongs with scouting;
- scouting / frontier exploration (§2.24), mission contracts (§2.25);
- milestones, farming, combat (Phase 3+);
- a human without the mod controlling a bot (roadmap);
- the relay transport (MQTT) for bots on different networks without the live server.

## Open questions

1. Does the **live server** limit `/msg` (range, rate, length) the way the LAN world doesn't
   (BOT_DESIGN Q5)? The probe must fail gracefully and fall through to spawn.
2. ~~Is world spawn dangerous on the live server?~~ **No** (owner, 2026-09-23) — spawn is a safe
   meeting point; no configurable alternative needed.

## After Phase 2

- **Re-test the knockout system** (civfabric dbno: `/down` → call for help → 45 s wait → /giveup,
  and a revive with stitches) — the owner's plan once Phase 2 is done. Regroup changes what happens
  after the respawn (it now walks back to the team), so check that too.
