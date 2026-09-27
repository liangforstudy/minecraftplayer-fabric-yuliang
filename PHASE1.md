# Phase 1 — the Body

Status: **built (0.1.9), 82 unit tests.** Tested in game 2026-09-22: walking, water Step A,
follow-swim, drowning reflex, eating (one bite per eat), critical health, summon / play.sh.
Knockout reflex tested live (`/down` → /msg, 15 s, /giveup). Still to re-test: FIXLIST #7. Builds on
[FOUNDATION.md](FOUNDATION.md) (Phase 0, verified in game).

**Goal:** give the brain senses, hands and survival reflexes, so a bot can walk, follow a player,
eat sensibly and stay alive. No goals of its own yet — the planner stays empty until Phase 2 ([PHASE2.md](PHASE2.md)).

Everything below lives behind the existing `WorldView` / `Hands` split: the rules are in `core`
(pure Java, unit-tested with `FakeWorld`), the Minecraft calls are in the Fabric adapter.

---

## Decisions

| # | decision | why |
|---|---|---|
| P1-1 | **Baritone is optional.** Zymbot loads without it; walking then fails with *"Baritone not installed"* and says so in `/zbot status`. Baritone stays a separate, user-installed jar | keeps Baritone's LGPL-3.0 apart from our code (the Altoclef approach, BOT_DESIGN §2.20) |
| P1-2 | **An idle Bot stands still.** With nothing to do it waits; it moves only on a command, a reflex, or (from Phase 2) an objective | predictable while testing; a bot that wanders by itself on a grudge server is a liability |
| P1-3 | **Leash, eat and critical-health thresholds are tunable** by command and in Mod Menu | R18 — instrument, don't guess |
| P1-4 | All movement goes through our own **`PathProvider`** interface; Baritone is just the first implementation | the 1.21.8 port needs a different Baritone build, and a custom scout walker may replace it (§2.20) |

Starting values (config, tunable):

| setting | default | meaning |
|---|---|---|
| `leash_blocks` | 100 | max distance from the nearest human while working (§2.6: bots pool readings within 100) |
| `eat_below_hunger` | 14 / 20 | eat when hunger falls to this |
| `critical_health` | 8 / 20 | the "health ≤ critical" interrupt |

**Leash vs idle (P1-2).** The leash stops a bot straying while it works on **its own** objective.
It doesn't apply to an idle bot (it stays put if you walk off) or to a player's direct order
(`goto` 200 blocks away is what you asked for). `/zbot follow` is the explicit way to bring it
along. Phase 1 has no objectives of its own yet, so the leash is unit-tested only until Phase 2.

---

## 1. Senses — additions to `WorldView`

- **Inventory:** hotbar, main inventory, held item; per stack: item id, count, food value
  (nutrition, saturation, effects).
- **Body:** health, hunger, saturation, in water, on ground, sprinting, mid-bite. (Active effects
  and light level: when a later phase needs them.)
- **Damage:** the last thing that hurt it, and when.
- **Nearby entities** within a radius: players, hostile mobs, passive mobs, Pokémon — with
  positions.
- **Blocks:** the block at a position; the nearest block with a given id within 32 blocks.

## 2. Hands — additions to `Hands`

- **Walk** via `PathProvider` → Baritone, with this server's settings applied each time it starts
  a path (a human's own Baritone use in between is left alone):
  - `allowSprint = false`, `sprintInWater = false` — sprinting costs 10× walking's hunger here
    (SURVIVAL_EARLY_GAME §4a);
  - **no breaking or placing** yet — avoids the refused-break loops on MINER-gated blocks
    (§2.20 friction #2); no parkour, no water-bucket drops, falls of 3 blocks at most;
  - **water: dry first, swim as a last resort, and report it** — see [Water](#water) below.
- **Stop, look** at a point or entity, **select** a hotbar slot, **eat** (use until consumed),
  **attack** once.
- **Chat out:** public chat and `/msg`, rate-limited.
- **Chat in:** incoming chat handed to the brain as data. Plumbing only — `!lf` and `!where`
  come in later phases.

## 3. Survival reflexes — rows of the interrupt table

The Phase 1 subset of [BOT_BEHAVIOUR.md → Interrupt table](BOT_BEHAVIOUR.md#interrupt-table).

| # | trigger | Phase 1 action |
|---|---|---|
| 0 | player pressed WASD | already built; now also **stops Baritone** immediately |
| 1a | air ≤ ⅔ while in water (added after a live drowning — FIXLIST #2) | stop the pathfinder, hold jump, swim straight for the nearest dry land within 24 blocks; tread water if there's none |
| 1 | health ≤ `critical_health` | eat a healing food (SURVIVAL_EARLY_GAME §1.3); if none, **run** away from the attacker, toward the nearest human — the only sprinting the bot does (0.1.10: a walker can't outpace a zombie) |
| 5 | nearest human > `leash_blocks` **while working** | pause the task, close the distance |
| 6 | hunger ≤ `eat_below_hunger` | eat the food worth the most **right now** — the game's live value, which Spice of Fabric has already decayed for this player (0.1.18; before, the bot decayed it a second time); recent meals only break ties; never a hazard food (§1.4) |

**Deferred:** #2 combat (the state machine is Phase 5; no hunting or self-defence yet),
#3 blood-moon shelter (needs the forecast), #4 point of no return (needs a home).

## 4. Test commands

| command | does |
|---|---|
| `/zbot goto <x> <z>` or `<x> <y> <z>` | walk there (any height in that column, or that block); `~` / `~10` relative, like vanilla (0.1.11) |
| `/zbot follow <name>` | follow a player until `/zbot cancel` |
| `/zbot come <name>` | walk to a player — to within 4 blocks: where it sees them, else where they last announced (0.1.16) |
| `/zbot watch <bot> [off]` | that bot `/msg`s you each decision for 30 min — over the team bus, so only teammates can ask (0.1.16) |
| `/zbot eat` | eat the best food now |
| `/zbot grave` | take our things back from civfabric's grave: the nearest within 16 blocks (walk up, empty hand, right-click, check it's gone), else walk back to where we last died first — **passed live 2026-09-22** (0.1.19–20) |
| `/zbot block <x> <y> <z>` | debug: what this client sees at a block |
| `/zbot punch <x> <y> <z>` | order (PHASE3 step 2): walk into reach, look at the block (turning a little per tick), hold the best hotbar tool, dig it like a held mouse, then walk over the drops until picked up or 5 s; `~` works |
| `/zbot foods` | the food carried, with the game's live hunger/saturation values and what Spice of Fabric leaves of them |
| `/zbot look <name>` | face a player |
| `/zbot set leash\|heel\|eat\|critical\|downed\|plantime\|lagtps <n>` | change a threshold (also Mod Menu → Zymbot → Body); `/zbot set` lists them. `heel` = how close the idle follow comes to the teammate (1–256, below the leash; default 2), `plantime` = ms per route search (50–5000), `lagtps` = plan less below this TPS (5–19) |
| `/zbot danger [modpack\|easy\|normal\|hard]` | when it runs from a mob (0.1.25): `modpack` (default) at the first hit, following the attacker by UUID; vanilla `easy`/`normal`/`hard` only at critical health (`critical` −2 / ±0 / +4). Live test deferred until all phases are built |
| `/zbot cancel` | drop the current order; the bot keeps running |

Orders need the bot running. An interrupt (eating, retreating) pauses an order, which resumes
afterwards; so does a human touching the keys. Dying, `/zbot stop` or leaving cancels it.

## 5. Measuring (R18)

Hunger spent per activity (idle, walking, sprinting, swimming, eating) is shown in `/zbot status`
and written to the game log every 5 minutes (`[zymbot] hunger meter: …`), so the
SURVIVAL_EARLY_GAME §4a drain table can be confirmed or corrected against the live game. It counts
hunger + saturation, which the server sends the client; exhaustion itself isn't visible.

## 6. Done means — in the LAN world

| # | test | passes when |
|---|---|---|
| 1 | `goto` ~200 blocks away | arrives on foot, never sprints; the hunger meter shows how much it swam |
| 2 | `follow` | keeps up with you; says "lost sight of you" if you get out of view for 30 s |
| 3 | `/effect give Bot1 minecraft:hunger` | eats at the threshold, and rotates foods instead of repeating one |
| 4 | damage to low health | eats a heal, or walks away from the attacker when it has none |
| 5 | WASD mid-walk — on **your** client with the Bot role (a headless bot has no keys) | Baritone stops at once; walking resumes after the countdown — **passed live 2026-09-22** (`/zbot start` on your own client) |
| 6 | no Baritone installed | loads fine; `goto` fails with a clear reason (unit-tested; both our clients have Baritone) |
| 7 | disconnect / death mid-walk | no crash; the walk is cancelled and cleaned up |

## Also fixed in 0.1.0

- FIXLIST #1 — memory is saved when a bot is met or comes back, and once a minute while it has
  changes, so a hard stop (`stop-bots`) loses at most a minute.
- The brain now has **orders** between interrupts and the planner, and an interrupt that can't
  act (no food) steps aside for 30 s instead of blocking everything below it.

## Water

~~Swimming costs about 85× walking per block~~ — **wrong, corrected 2026-09-22:** civfabric's 4.0-per-10-s
"swimming" drain only applies to the **sprint-swim pose** (`isSwimming`), which the bot never uses.
Crossing water at a normal pace counts as walking for civfabric, just slower, plus vanilla's small
in-water exhaustion: **about 3× walking per block** (`swim_cost_blocks` = 3). Measured live: a ~100-block
crossing from an island cost about 1 food (1.16 food/min in water). Water is still slower, and a
bot in it can drown — the drowning reflex covers that.

Baritone has no *cost* setting for water, only a ban: water on its `blocksToAvoid` list makes it
impassable (it's checked live, nothing cached). Its internals are obfuscated per build, so
patching its cost model from outside would break on every update.

**Step A — built in 0.1.1.**

| situation | what happens |
|---|---|
| any walk, follow, retreat or leash walk | tries **dry** first: water, bubble columns, kelp and seagrass banned |
| Baritone finds no dry path | the same walk is retried **allowing water**, and the log says so: `swimming — because no dry path to X` |
| the walk starts in water | allows water from the start: `swimming — because already in the water` |
| following, and the gap stops closing for 10 s while they're in view | swims after them (`no dry way to X`); back to dry once within 4 blocks and out of the water (`walking dry again`) |
| no path even swimming | fails, with the reason |
| the bot stops walking | water comes off Baritone's avoid list again, so a human's own Baritone is untouched |

What Step A can't do: pick *where* to cross. Once water is allowed, Baritone's own path may swim
further than it needs to.

**Step B — built in 0.1.12, first live walk 2026-09-22 (0.1.13–14), reworked in 0.1.16.** In `core`:

1. A terrain sense: surface height and top block per column, from the loaded chunks.
2. A search over the surface, one column per node, 96 blocks around (`route_radius`). Land costs 1 per block, water the swim ratio
   (`swim_cost_blocks`, 3 — was 85 until the live measurement); climbs over 1 or drops over 3, lava, fire,
   cactus, magma, berry bushes, powder snow, and anything taller than a block (fences, walls, panes —
   the heightmap makes them look like a 1-block step) are blocked. Each block climbed costs 5 blocks
   of flat walking (the hunger meter measured 3.8 food/min walking over hills vs ~0.6 on the flat).
   The ground is copied on the game thread and the search runs on its own thread (a 1.9 s plan froze
   the game in the first live walk); both times are logged (`[zymbot] route: … copied … planned …`).
3. **Baritone walks the whole trip** (water banned) — it's good at long walks. The planner only
   keeps a lookout: every 64 blocks it checks the ground ahead in the background, and only if the
   cheapest way crosses water does it take over for that crossing — near shore, across, then back to
   Baritone for the rest (0.1.17; before, every trip was cut into 48-block legs).
4. Beyond the loaded area there's nothing to check; the lookout just runs again 64 blocks on.
5. *Not yet:* follow still uses Step A's "swim when stalled", and the leash walk is a plain walk.
6. Each crossing is justified: `swimming 6 blocks — because the dry way round is 410 blocks`.
7. When no dry route exists it always swims — the shortest gap — and reports it (your call:
   last resort, never refuse).
8. **Time budget and lag (0.1.24).** Each search stops after `plantime` ms (default 500, like
   Baritone's own ~0.5 s limit) and goes with the best route found so far. If the *dry* check runs
   out of time it doesn't swim on a guess — Baritone keeps walking dry (`not planning a swim — ran
   out of time …`). The bot reads the server's TPS from its world-time updates (about one a second);
   below `lagtps` (default 15) it logs `planning less`, looks ahead less often (64 blocks × 20/TPS,
   at most 4×: every 160 blocks at 8 TPS) and plans gently — a low-priority thread that pauses every
   512 columns — so it doesn't take CPU from a laggy host on the same Mac. (Java thread priority is
   mostly ignored on macOS; the pauses are what actually hand the CPU back.)

Later, not in either step: boats (no swim drain, but crafting one costs 3.2 hunger — Phase 3) and
bridging (needs block placing).
