# Bot design — requirements and the mechanics they rest on

> **Looking for what the bot actually does?** That's [BOT_BEHAVIOUR.md](BOT_BEHAVIOUR.md) —
> interrupt table, phases, food rotation, commands. This file is the *reasoning*: every
> requirement, and the verified mechanic behind it.

Client-side helper bot for the ZymCiv 1.21.1 pack. Non-LLM (rule/behaviour-tree driven);
an LLM can later occupy a *player* role rather than replacing the bot's control loop.
Current scope: **singleplayer offline**, for capability testing.

Status: requirements captured, mechanics verified against the pack. **No code written yet.**

## Changelog — pack updates

The ZymCiv pack is actively iterated; it updated three times on 2026-09-21 alone. Values here are
re-checked against the installed jars whenever that happens. `headless/sync-bots.sh --check`
reports drift before a test.

### 2026-09-21 — civfabric 0.4.0 → 0.4.2, zymlabs-rules 0.0.6 → 0.0.8, realisticplantgrowth 0.1.0 → 0.1.1

Re-checked 36 recorded values: **29 unchanged, 7 changed**, plus a batch of gate changes.

**Unchanged — the core design holds:** natural regen off, bed rest (1 hunger/HP, floor 3, warmth
radius 4), `TOWN_MIN_BEDS = 3`, `DEFAULT_SLOTS = 2`, the forecast sensitivity/noise arrays and
priors (so every fusion number in §2.4 and §2.21 still stands), stitches, specialisation drain,
harvest curves, crafting hunger, campfire burn 30%, kelp poison, baked potato 4, lightning.

**Changed:**

| value | 0.4.0 | 0.4.2 |
|---|---|---|
| hunger drain — idle | 0.0 | **0.05** |
| hunger drain — walking | 0.1 | **0.2** |
| hunger drain — sprinting | 1.0 | **0.75** |
| hunger drain — swimming | 4.0 | **3.0** |
| iron ore | MINER 3 | **MINER 2** |
| gold ore | MINER 2 | **MINER 3** |
| iron hoe / pickaxe / sword | BLACKSMITH 3 | **BLACKSMITH 2** |
| diamond hoe / pickaxe | BLACKSMITH 4 | **BLACKSMITH 3** |
| blast furnace | BLACKSMITH 4 | **BLACKSMITH 1** |
| netherite tools | BLACKSMITH 5 | no longer gated at the crafting table |
| pacifism-off XP multiplier | 2.0 | **2.5** |
| Pokémon capture / victory XP | 10 / 5 | **0 / 0** |

**Unchanged and load-bearing:** bucket, flint & steel and shears stay at **BLACKSMITH 3**, so the
lava-cast Nether bypass still needs BLACKSMITH 3 — it now *matches* the obsidian route's pickaxe
gate, but still saves the MINER 4 diamond grind.

**New:**

- **Killing Pokémon gives class XP** — `POKEMON_KILL_XP = 2.0` + 0.25/level — while catching and
  battling now give none. Hunting is progression, not just food.
- **`UNDER_ATTACK_XP_MULTIPLIER = 2.5`** for 900 ticks after being attacked.
- **`/guessforecast`** (new `ForecastGuess`): name the moon you expect — or a quiet night — and a
  class to receive the reward; if it comes true you earn class XP (`FORECAST_GUESS_XP_BASE =
  160`). You can change the guess until nightfall. A bot running the peer-to-peer fusion has the
  best estimate in the group, so it can bank this XP every night.
  *Verified in bytecode:* **no penalty for a wrong guess**; payout = `160 × (1 − c)` when guessing
  your own reading's moon, `160 × c` when guessing against it (c = your reading's confidence);
  paid unboosted. Super blood counts as blood. See `server_rules.json → forecast_guess`.
- ~40 new recipe gates, mostly Cobblemon poké balls behind BLACKSMITH 1–3; `stitches` → MEDIC 1;
  `cobblemon:healing_machine` block → MEDIC 1.
- `MEAL_REPEAT_FACTOR = 0.7`, `NOURISHMENT_SWIMMING_MULTIPLIER = 0.5` — meaning not yet traced.

**What it changes for the bot:**

- **The iron-tier food boost arrives a rank earlier on both sides** — MINER 2 and BLACKSMITH 2
  instead of 3 and 3. Bot1 (FARMER + MINER) reaches iron sooner.
- **Walking costs twice as much**: a 2000-block trip is ~9 units (about half a bar) instead of ~5.
  Sprinting is now only ~3× walking per distance rather than ~8×, but walking is still cheapest.
- **Idle is no longer free.** Waiting out a blood moon in shelter now costs hunger too.
- Gold got harder (MINER 3), which pushes golden apples — a healing item — later.

---

## 1. Requirements as stated

| # | Requirement |
|---|---|
| R1 | Bot starts on a **client-side command**; then autonomously wanders to find a suitable biome |
| R2 | **Leash to a human player.** If the bot strays beyond a range, it *pauses* the biome search and prioritises closing distance. Range configurable in-game by command |
| R3 | **Bed priority scales with distance from spawn point**, because death returns you to spawn with nothing (items go to a grave) |
| R4 | Bed priority must be **weighed against the hunger/farming economy** |
| R5 | Bot reads `/lunarforecast`, which is **not 100% accurate** and reports a certainty percentage |
| R6 | Bot should **fuse readings from multiple players** for a better estimate |
| R7 | Bot cannot see other players' command output — needs a **chat broadcast channel** it can detect |
| R8 | Design assumes **3+ players cooperating** on a sustainable farm |
| R9 | Players exchange messages only within **~100 blocks** of each other |
| R10 | **Bot 1 is the aggregator** ("server" role). Bots 2..n publish their readings to chat; bot 1 fuses everything it hears from bots *and* humans |
| R11 | Bot 1 **replies only when conditions are met** — humans are slow, so it speaks up **only to correct a false alarm** |
| R12 | Bot mode **pauses on WASD input**, resumes after a timer, with a warning before returning to autonomous control |
| R13 | A good site may be **~2000 blocks from spawn**. The bot must decide **settle vs keep exploring** by judging whether a candidate farm can meet the minimum sustainability goal |
| R14 | Aggregator **publishes the fused certainty to chat**, reacting when players publish theirs |
| R15 | A player may **amend their last reading** if they misfired |
| R16 | Scope for now: **1 bot cooperating with 2 human players** (not 3 bots) |
| R17 | The bot **assigns classes** at game start (deferred: applies to bot-to-bot; for R16 it *recommends* to humans) |
| R18 | "Minimum sustainable" is **undefined on purpose** — to be calibrated during playtesting |
| R19 | Scouting bots **WALK, never sprint** |
| R20 | Players hold **multiple classes**; classes lock once an XP tier is reached; the number of slots is server-defined, **default 2** (owner intends 3 when HUNTER is re-enabled — so it must be config-driven) |
| R21 | Pathfinding: Baritone vs Altoclef vs custom — **decided below** |
| R22 | **LIBRARIAN and HUNTER** are the forecast-accuracy classes (known; there may be others) |
| R23 | **Bot takes FARMER + MINER**, configurable |
| R24 | Bot **reminds the LIBRARIAN to run the forecast** rather than holding the class itself |
| R25 | Start command is **two-phase**: first run discovers nearby bots and waits; second run commits and starts the core loop |
| R26 | Bots **scan undiscovered chunks in parallel**, outward, N-way — an outward spiral, but shared between however many bots are present |
| R27 | Bots **split when terrain opens up** — a C-shaped ocean should make them follow land, not swim the arms of a spiral |
| R28 | A wander command; running it again recalls the bot — **but it cannot hear the recall when out of range** |
| R29 | Bots **follow breadcrumbs back to spawn** to share scouted data |
| R30 | Decide whether **dying on purpose** is an acceptable way home |
| R31 | Possible pivot: run 2 bots **server-side**, human joins as bot 1, **summon more bots on the fly**. Baritone stays on the list in case the scope returns to client-only |
| R32 | Bots must run **headless**, on a 16 GB M2 alongside a local server and a GUI client |
| R33 | ~~Thunderstorm shelter~~ — **corrected**: storms only strike players high off the ground (flying). Not a ground threat |
| R34 | Food rotation must cope with fewer foods than the target — likely for the first few days |
| R35 | No false negatives: a farm or bed that's chosen / half-built must not be started again |
| R36 | On a role change, adapt; ask humans first, then bots; weight for bossy humans |
| R37 | Forecast is **peer-to-peer** — every bot fuses its own copy; ~~only readings shared~~ verdicts shared too (R43) |
| R43 | **Verdicts are shared too** (`GUESS`, stepped revisions, fetchable by anyone); each bot makes its own `/guessforecast`; verdicts feeding verdicts is intended. Public one-reply requests (`!lf help`) answered by exactly one bot via a hash-ordered queue. Autostart whitelist includes singleplayer. Command root configurable. See FOUNDATION.md |
| R44 | **The bot knows exact coordinates; humans don't.** The live server hides F3 coordinates from players (a server-side rule, not in the pack), but the client still holds its exact position, so the bot reads it. Allowed, not cheating — but it's an asymmetry: anything a bot tells a *human* must be navigable without xyz |
| R45 | **Docs are living.** BOT_BEHAVIOUR.md and the other design docs are expected to change as in-game testing proves or disproves them |
| R46 | **`!where`** — nearby bots tell humans where they are, or where a bot / spawn / **village, town, base, farm, bed, portal** is (from places bots have actually seen; team places answered to teammates only). Modes `off / relative / exact`, default `relative`; `exact` only with the server owner's OK (**pending — to ask**). Only about players the bot can see; always by `/msg`. See FOUNDATION.md |
| R38 | Toggle to stop the bot talking on the bus |
| R39 | Combat is a state machine that pauses Baritone |
| R40 | Handle mobs that fly or run — can it kill them before they get away? |
| R41 | First live test: **2 headless bots + 1 human in Prism**, same Mac, same world |
| R42 | Test with **you + Bot1 first**, Bot3 later; any rung needing 3 players is **deferred**, not failed — via a `min_players` field on each rung |

---

## 2. What the pack actually does (verified)

### 2.1 Hostiles are neutral until provoked — confirmed

`pacifist-fabric` patches `MobMixin` and `TargetingConditionsMixin`. Night travel is safe by
default. But there are hard exceptions the bot must encode:

- **Grudge groups.** Hitting one member turns the *whole kin group* hostile:
  - `zombie_kin` = zombie, husk, drowned, zombie_villager
  - `skeleton_kin` = skeleton, stray, bogged
- **Villages suspend pacifism at night** (`villagesSuspendPacifismAtNight: true`, 5s notice).
  Being inside a village after dark is one of the few genuinely dangerous states.
- **Blood moons suspend pacifism entirely** (both variants).
- **Creepers** are on `villageAlwaysIgnores` — village pacifism never protects against them.
- Timers: 15s engagement grace, 20s hit cooldown, 30min kill cooldown.

> The bot's threat model is **not** "mobs at night". It is: *don't provoke, don't be in a
> village after dark, don't be outside on a blood moon.*

### 2.2 R3/R4 — the bed *is* the hunger↔health bridge

`civfabric` `BedConfig` makes this exact and resolves R4 cleanly:

| constant | value |
|---|---|
| `REST_HEAL_PER_DAY` | 5.0 HP |
| `REST_HEAL_PER_CYCLE` | 1.0 HP |
| `REST_INTERVAL_TICKS` | 900 (45s) |
| `REST_HUNGER_PER_HEALTH` | **1.0 hunger per HP** |
| `REST_HUNGER_FLOOR` | 3 (rest stops here) |
| `WARMTH_RADIUS` / `HEIGHT` | 4 / 2 — rest needs a nearby heat source |
| `TOWN_RADIUS` | 64 |
| `TOWN_MIN_BEDS` | **3** |

Since `naturalRegeneration` is forced **off**, bed rest is the main renewable heal — and it is
**literally paid for in hunger**. Healing 5 HP costs 5 hunger ≈ one good meal. That makes R4
a straight arithmetic trade rather than a heuristic:

```
rest_value   = min(missing_hp, 5, max(0, hunger - 3))      # HP recoverable now
rest_cost    = rest_value                                   # hunger, 1:1
```

And `TOWN_MIN_BEDS = 3` means **R8's "3 players minimum" is a mechanical threshold**, not a
suggestion — three claimed beds within 64 blocks is what constitutes a town.

Beds are **claimed per player** (`BedClaims.OWNED_BED`), so the bot needs its *own* bed; it
cannot share a human's.

### 2.3 R3 — death cost is distance, confirmed

`civfabric` `grave/` drops your items into a grave block at the death site
(`GRAVE_KEEPS_ALL_EXPERIENCE = false`) and respawns you at your spawn point with nothing.
There's also a `dbno` (down-but-not-out) module — a downed state before true death. **Read in
full 2026-09-22 (civfabric 0.4.2), see [§ Knocked out](#knocked-out--civfabric-dbno) below.**

So death costs **the round trip**, not the items. Bed priority rising with distance from
spawn is the correct model. Suggested shape:

```
bed_priority ∝ distance_to_spawn × P(death per unit time) × trip_cost_per_block
```

### 2.4 R5/R6 — the forecast noise model, extracted

`/lunarforecast` (alias `/forecast`) is `civfabric`'s, **not** Enhanced Celestials'. EC's own
`/lunarForecast` reads shared world data and gives everyone the same answer. civfabric's is
the interesting one:

- `read(player, level, time)` → `Reading(moon, confidence)`
- **seeded by `seed(playerUUID, dayBucket)`** — each player gets an *independently* noisy
  reading of the same underlying truth. This is why R6 works; it's a designed mechanic.
- Accuracy depends on the player's **class rank** (`SpecConfig.FORECAST_CLASSES`,
  `ClassManager.rank`):

| rank | 0 | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|
| sensitivity `P(reports E \| E true)` | 0.40 | 0.50 | 0.60 | 0.70 | 0.80 | 0.90 |
| noise `P(reports E \| E false)` | 0.10 | 0.08 | 0.06 | 0.05 | 0.04 | 0.02 |

Priors: ordinary moons **0.0491** on a full moon / **0.0522** otherwise; super moons **0.0267**.
Only `blood_moon` and `super_blood_moon` exist in this world's datapack, and
`super_blood_moon` is full-moon-only.

**Fusion is exact, not heuristic.** Because readings are independently seeded and share one
prior, log-odds addition is the correct combiner:

```
# if each reporter's rank is known
odds = prior_odds × Π LR_i
LR_i = sens_i / noise_i              if player i reports E
     = (1 - sens_i) / (1 - noise_i)  otherwise

# if only the reported confidence is known (no rank)
odds = prior_odds × Π ( odds_i / prior_odds ),   odds_i = c_i / (1 - c_i)
```

Worked example: three rank-0 players all report a blood moon on a non-full night.
Prior 0.0522 → odds 0.0551. Each LR = 4.0. Fused odds = 0.0551 × 64 = 3.53 → **P ≈ 0.78**,
versus **P ≈ 0.18** from a single rank-0 reading. Three low-rank observers beat one mid-rank one.

**Note the asymmetry:** a *negative* report is weak evidence at low rank
(LR = 0.6/0.9 = 0.67) but strong at high rank (0.1/0.98 = 0.10). The bot should weight
"nobody sensed anything" by the reporters' ranks before relaxing.

### 2.5 R7 — the chat channel

Confirmed: the forecast reply is sent only to the player who ran the command, so the bot
genuinely cannot read it. Since every human in the group runs this client-side mod anyway,
the clean solution is to let the mod do it:

1. At dusk each client auto-runs `/lunarforecast` (or on demand via a keybind/command).
2. The client mod intercepts the reply **client-side** before it renders.
3. It re-emits a compact tagged line into public chat, e.g.
   `[LF] blood_moon 72.3 r3 <day>`
4. Every instance parses `[LF]` lines, feeds them into the fusion above, and **suppresses the
   raw line from display** so chat stays clean.

Include the day bucket so stale readings are discarded, and the rank so the exact
sensitivity/noise LR can be used instead of the confidence-only form.

Fallback if a human lacks the mod: parse the human-readable sentence
`"You sense a <Moon> <tonight|tomorrow night> … <conf>%"` if they paste it manually.


### 2.6 R9 — the 100-block range is NOT in this instance

I looked for proximity text chat and could not find it. In the 1.21.1 Minimal instance:

- **No mod implements a text-chat radius.** `zymlabs` `ChatFormatMixin` only styles chat;
  `civfabric` `ServerChat` only formats a prefix. Vanilla chat is global.
- The only distance limit is **Simple Voice Chat**: `max_voice_distance = 48.0`,
  `whisper_distance = 24.0` — that's *voice*, not text, and it's 48 not 100.
- And the current scope is **singleplayer offline**, where chat range is meaningless.

So the 100-block rule is presumably enforced on the private online server by something not
shipped in this instance. **Treat transport range as a configurable parameter**, not a
constant: unlimited in singleplayer testing, ~100 on the live server. Worth confirming where
that number comes from before it's baked into the fusion logic.

This matters because it couples two otherwise independent things: **R2's leash range and
R9's chat range are the same problem.** If readings only travel 100 blocks, the bot cluster
must hold formation within 100 blocks to pool them at all — so the leash range should default
to *less* than the chat range, not more.

### 2.7 R10/R11 — chat as a message bus

> **Superseded.** The single-aggregator design below was replaced by a **peer-to-peer** model:
> every bot keeps every reading and fuses locally; only raw readings are shared, never a verdict.
> See [BOT_BEHAVIOUR.md → The lunar forecast](BOT_BEHAVIOUR.md). Kept for the reasoning.

The architecture is a **single aggregator over a broadcast bus**:

```
bot2 ──[LF] reading──┐
bot3 ──[LF] reading──┼──► bot1 (aggregator) ──► fuse ──► reply ONLY on exception
human ─ pasted/auto ─┘
```

Design notes:

- **Leader selection.** "Bot 1" needs to be unambiguous. Cheapest: explicit config/command
  (`/botrole server|client`). Self-electing (lowest UUID present, re-elect on timeout) is more
  robust for a group that joins in any order, but needs a heartbeat on the bus.
- **Exception-only reply (R11)** is the right call for chat hygiene, and the fusion math makes
  "false alarm" precise rather than a vibe:

  ```
  fused = fuse(all readings heard)
  someone_claims_event = any human/bot reported E
  false_alarm  = someone_claims_event AND fused_P < LOW   # speak: "probably not"
  missed_event = no one claims E     AND fused_P > HIGH   # arguably also worth speaking
  ```

  Suggest `LOW ≈ 0.25`, `HIGH ≈ 0.7`, both tunable. Note the earlier asymmetry: at low rank a
  *negative* report is weak evidence (LR 0.67), so "nobody sensed anything" should not by
  itself trigger a confident all-clear.
- **Staleness.** Every message carries the day bucket; drop readings from a previous bucket.
- **Dedupe.** One reading per (player UUID, day bucket) — a re-run must not double-count, or
  a player could spam their own reading and dominate the fused odds.
- **Humans are slow (R11).** Give the aggregator a collection window (e.g. until dusk + N
  seconds) and let it fuse on whatever arrived, rather than blocking on a full quorum.

### 2.8 R12 — autopilot disengage

Standard pattern, three states:

| state | entered by | behaviour |
|---|---|---|
| `AUTONOMOUS` | command, or resume timer expiring | bot has full control |
| `SUSPENDED` | any WASD / jump / look / inventory input | bot releases control immediately, countdown starts |
| `ARMED` | countdown nearly expired | warning shown; any further input re-enters `SUSPENDED` |

Open choices: which inputs count as intervention (WASD only, or also mouse-look, hotbar,
attack?), the resume delay, and whether the warning is actionbar, toast or chat. Actionbar
with a countdown is the least intrusive and matches how the rest-bar already behaves
(`BedRest.hideBar`). A hard `/botstop` should exit to a fourth state that never auto-resumes.


### 2.9 What "fake server player" meant — and why it matters

Fair question, and your answer settles it. In MC modding "a bot" usually means a **server-side
fake player** — a real `ServerPlayer` entity spawned by the server (Carpet's `/player` command
does this). It has an inventory, can own a bed, holds a UUID, and counts as a player to every
server system.

You've said client-side Fabric mod, so that's **not** what this is. The bot **is** the local
player: it reads the world and writes the same inputs a human would. One game client = one
player. That resolves the earlier open question — the bot inherits the human's bed ownership,
class rank and town membership automatically, because it *is* that account.

**But it has a consequence for the test scope.** If one client = one player, then:

> **3 bots = 3 separate Minecraft clients, each logged into a different account.**

So **singleplayer cannot test the coop half of this design at all** — there is exactly one
player, no chat peers, no second bed, no forecast to fuse. Singleplayer is fine for the
single-bot survival loop (forage, hunger rotation, farm, bed). Everything in R6–R11 and R14–R15
needs a multi-client setup.

The cheap fix: run a **local dedicated server with `online-mode=false`**, and launch 2–3
clients on the same machine. That also unlocks a much better transport than chat (below).

### 2.10 Transport — chat now, but not forever

You asked whether I could write a channel. Yes, and there are four real options:

| option | server mod needed | hidden from humans | works offline/local | notes |
|---|---|---|---|---|
| **A. Public chat** | no | no | yes | what you've chosen; simple, visible, rate-limited |
| **B. Chat steganography** | no | yes | yes | encode payload in trailing `§`-colour codes on a normal line; client strips it. Fragile — servers may sanitise |
| **C. Custom payload channel** | **yes** (tiny relay) | yes | yes | the correct long-term answer. You own the server, so a ~100-line relay mod forwarding one custom channel between clients removes all chat spam |
| **D. Local IPC bus** | no | yes | **yes — ideal for testing** | clients on one machine talk over a localhost socket. Completely bypasses Minecraft. Perfect for the 3-client local test setup, useless for remote players |

Recommendation: **ship A now** as you decided, but put it behind a `ForecastTransport`
interface with `publish(Reading)` / `onReceive(cb)`. Then D drops in for local multi-client
testing and C for the live server, with zero change to the fusion logic. Writing C or D is a
small job — say the word and I'll do it once the core loop exists.

Note A's real constraints: chat is rate-limited (vanilla drops spam), messages are capped in
length, and if the 100-block rule (§2.6) is real, **the transport has a range** — which is
exactly why it should be swappable.

### 2.11 R14/R15 — revised forecast protocol

> **Superseded.** The single-aggregator design below was replaced by a **peer-to-peer** model:
> every bot keeps every reading and fuses locally; only raw readings are shared, never a verdict.
> See [BOT_BEHAVIOUR.md → The lunar forecast](BOT_BEHAVIOUR.md). Kept for the reasoning.

Three message kinds on the bus, all carrying a **day bucket** so stale data is dropped:

```
READ  <uuid> <moon> <confidence> <rank?> <day>   # a reading
AMEND <uuid> <moon> <confidence> <rank?> <day>   # supersedes this uuid's last READ
FUSE  <moon> <confidence> <n_sources> <day>      # aggregator's fused result
```

- State is a map `(uuid, day) -> reading`. `AMEND` **replaces**, never appends — which is
  exactly the dedupe rule from §2.7, so R15 costs nothing extra to support.
- The aggregator recomputes on every `READ`/`AMEND` and publishes `FUSE` (R14). **Debounce it**
  — otherwise three players reporting in quick succession produce three `FUSE` lines. A short
  quiet window (~2–3s) after the last input, plus "don't republish if the fused result is
  unchanged", keeps chat readable.
- Humans are slow (R11), so keep a collection window open past dusk and re-publish `FUSE` when
  a late reading materially moves the number.
- R11's exception-only behaviour and R14's always-publish behaviour conflict. I've written R14
  as the current intent: **publish the fused certainty whenever it changes materially**, and
  treat "false alarm" as *emphasis* on that message rather than a separate trigger.

### 2.12 R13 — settle vs keep exploring

I ran the biome/crop table for this, and it produces a hard structural result:

> **No single biome grows wheat, carrots and potatoes.** The three staple sets are disjoint.

```
WHEAT    : PLAINS, SUNFLOWER_PLAINS, MEADOW (100%, 0% death) + savanna/tropical at 50%
CARROTS  : FOREST, BIRCH_FOREST, DARK_FOREST, FLOWER_FOREST, OLD_GROWTH_BIRCH_FOREST (100%)
           + Chilly (taigas) at 50%
POTATOES : WINDSWEPT_*, STONY_SHORE, JAGGED_PEAKS, and all Arid (100%)
```

So **settlement is a biome-junction search, not a biome search.** The bot should score a
*position* by the biomes reachable around it, not the biome it stands in.

Strongest single biomes by crops at 100%:

| biome | crops @100% |
|---|---|
| TAIGA / OLD_GROWTH_PINE / OLD_GROWTH_SPRUCE | beetroot, sweet berry, pumpkin, mushroom (**4**) + carrots at 50% |
| DARK_FOREST | carrots, mushroom (2) |
| JUNGLE family | melon, mushroom (2) |
| MEADOW | wheat (1) — but meadows generate next to forests and peaks |

A **MEADOW/FOREST/WINDSWEPT junction** covers all three staples; a **TAIGA/PLAINS** border
covers five crops at 100% plus wheat. Those are the shapes to search for.

Proposed scoring — settle when value clears a threshold that *decays with cost*:

```
coverage(site) = Σ_crop  w_crop × best_rate(crop, biomes within R) × (1 - death_chance)
site_value     = coverage
               + water_access + sky_access(light 15) + village_bonus
               - β × distance_to_spawn        # death = full trip back to the grave
               - γ × travel_time_so_far       # hunger + risk already spent

settle if site_value ≥ threshold(t),  threshold decaying as search continues
```

`β` is grounded in real numbers: death costs the round trip, and bed rest costs **1 hunger per
HP** (§2.2), so distance is payable in food. This is the same currency as `coverage`, which is
why they can be traded off directly.

**The minimum goal still needs defining.** Spice of Fabric wants 12 distinct foods for full
value (6 for 70% — the curve is a staircase, see BOT_BEHAVIOUR.md), but those
are *items*, not crops — wheat→bread, potato→baked potato, mushrooms→stew all count
separately. Working out the smallest crop set that yields 12 distinct items needs the
**recipe graph**, which I haven't extracted yet. Until then the threshold is a guess.


### 2.13 The class system — this changes the whole plan (R17)

I mapped `civfabric`'s spec system. It is much larger than a perk tree, and it makes R17
central rather than cosmetic. Full data in `survival-data/spec_classes.json`.

**Seven classes, ranks 0–5:** FARMER, BUILDER, MINER, MEDIC, LIBRARIAN, BLACKSMITH, HUNTER.

Specialising **drains XP from off-classes** (`DRAIN_TOTAL_FROM_OFF_CLASSES = 1.0`,
`DRAIN_PER_SPECIALISATION = 0.525`), so nobody can max everything. Class choice is a real,
mostly irreversible allocation — which is exactly why assigning them up front (R17) matters.

**Classes gate crafting.** Gated recipe counts: builder 250, blacksmith 99, librarian 52,
medic 6, farmer 5, miner 1. The entire classless toolkit is: torch, soul torch, stick,
crafting table, furnace, chest, barrel, planks, boats.

**Classes gate harvesting.** This is the big one, and it corrects an earlier claim of mine:

```
if crop.requiresHoe and not holding a hoe:   HARVEST FAILS OUTRIGHT
success = base + perRank × FARMER_rank + perHoeTier × hoeTier
```

At rank 0 with a wooden hoe: wheat **61%**, carrots **50%**, potatoes **47%**. Roughly half
of every harvest is lost. See `SURVIVAL_EARLY_GAME.md` §4a for the full curve table.

**And the tier ceiling is social:**

| item | gate |
|---|---|
| wooden hoe | ungated |
| stone hoe | BLACKSMITH 1 |
| iron hoe | BLACKSMITH 2 |
| shears | BLACKSMITH 3 |

**A lone FARMER is capped at hoe tier 1 permanently.** Improving harvest rates *requires* a
BLACKSMITH. Likewise MEDIC is the only renewable heal for others (stitches: 4 HP + 1.5/rank,
costs 3 food) in a world with `naturalRegeneration = false`.

**So the 3-player minimum is real in a second, independent way**: `TOWN_MIN_BEDS = 3` for
towns, *and* the class gates mean a self-sufficient group needs at least FARMER + BLACKSMITH +
MEDIC. With R16's 1 bot + 2 humans, that is exactly three slots to fill.

**R17 assignment logic.** For R16 the bot can't assign anything to humans — it can only
*recommend*. A sensible default split for 1 bot + 2 humans:

| role | who | why |
|---|---|---|
| FARMER | **the bot** | repetitive, tolerant of grind, directly drives the food rotation |
| BLACKSMITH | human | unlocks the bot's hoe upgrades — the bot's own yield ceiling depends on it |
| MEDIC | human | reactive, needs judgement, and humans are the ones who take damage |

The bot taking FARMER is also self-serving in the right way: it's the class whose rank
improves the bot's own core loop. Worth exposing as config rather than hard-coding.

### 2.14 Hunger is not vanilla (affects R13 travel cost)

`civfabric` drains hunger on its own 200-tick timer *on top of* vanilla exhaustion:

| state | per 10s |
|---|---|
| idle | 0.0 |
| crouching / walking | 0.1 |
| **sprinting** | **1.0** |
| **swimming** | **4.0** |

Sprinting is 3.75× walking per tick, swimming 15×, and idling drains 0.05 (civfabric 0.4.2 — it
was 10× / 40× / free in 0.4.0). Crafting also costs hunger and is **refused outright
when too hungry** (bread 0.5, chest 1.25, bucket 2.0, boat 3.2).

This puts a real number on R13's travel term: a 2000-block walk is cheap, the same distance
sprinting is not, and swimming it is ruinous. The bot's pathing should prefer walking and
route around water rather than through it.

### 2.15 R18 — instrument, don't guess

Since "minimum sustainable" is to be found by playtesting, the code should not hard-code a
threshold. Instead:

- Expose it as tunable config (`minViableCoverage`, `settleThresholdDecay`, leash range,
  resume timer) editable in-game.
- **Log the inputs to every settle/explore decision** — candidate site, biome mix, crop
  coverage, distance, hunger spent — plus the outcome (did the farm actually sustain the
  rotation?). A few sessions of that data defines the threshold empirically instead of by
  argument.


### 2.16 R20 — the slot arithmetic pins the group size

Verified in `ClassSetup`: `DEFAULT_SLOTS = 2`, read from `civfabric/classes.json` under
`specialisationSlots`. `DEFAULT_ENABLED` is **all classes except HUNTER** — so six are live by
default: FARMER, BUILDER, MINER, MEDIC, LIBRARIAN, BLACKSMITH.

> **3 players × 2 slots = 6 = exactly the number of enabled classes.**

The pack is dimensioned for a three-player group with no slack: every class covered exactly
once, none spare. That's a third independent confirmation of the 3-player minimum, alongside
`TOWN_MIN_BEDS = 3` and the crafting gates.

Locking is one-way in practice — specialisations arm at 85% of a tier
(`DRAIN_ARMING_FRACTION`) and then bleed XP out of off-classes (`DRAIN_PER_SPECIALISATION =
0.525`). So R17's up-front assignment is close to irreversible, and worth getting right.

### 2.17 Mining is gated too — and it feeds the food loop

`civfabric/mining.json` sets a minimum MINER rank per ore:

| MINER rank | ores |
|---|---|
| 1 | copper |
| 2 | gold, lapis, nether gold |
| **3** | **iron**, redstone, emerald |
| 4 | diamond, ancient debris |

**Iron needs MINER 2** (was 3 before civfabric 0.4.2). Which produces the dependency chain that
decides the whole build order:

```
MINER 2  →  iron ore  →  iron ingots
                            ↓
BLACKSMITH 2  →  iron hoe  (hoe tier 3)          BLACKSMITH 3 → shears
                            ↓
FARMER  →  wood → iron hoe: +0.06 wheat, +0.10 carrots, +0.11 potatoes
```

(An earlier version of this diagram said +0.15 / +0.25 — that was five tiers' worth. Wood is
tier 1 and iron tier 3, so the upgrade is two tiers.)

**Keep the hoe in proportion.** It's a real gain but a modest one — at rank 0, wheat goes 61% →
67%, carrots 50% → 60%. **FARMER rank is the bigger lever**: each rank adds +0.06 on wheat, the
same as the entire wood→iron upgrade. Rank 5 with an iron hoe reaches wheat 97%, carrots 90%,
potatoes 89%. The iron chain still matters — it's the only way past tier 1 — but a FARMER that
levels up gains as much as one that waits for tools.

**The food engine is not a FARMER problem.** A farmer alone is stuck on a wooden hoe at ~61%
wheat / 50% carrots forever. Raising it needs a MINER *and* a BLACKSMITH first. Any settle
plan that doesn't budget for ore access is capped from day one.

### 2.18 Revised role allocation (supersedes §2.13)

With 2 slots each and six classes, 1 bot + 2 humans covers everything exactly. Pair the
classes that feed each other, and give the bot the repetitive ones:

| who | classes | rationale |
|---|---|---|
| **bot** | FARMER + MINER | both are grind-shaped: harvest loops and ore loops. The bot mines the iron that upgrades its own hoe, so its yield ceiling is self-served |
| human A | BLACKSMITH + BUILDER | the crafting hub — consumes the bot's ore, produces its tools; 349 of the gated recipes sit here |
| human B | MEDIC + LIBRARIAN | reactive and judgement-heavy; MEDIC is the only renewable heal for others with natural regen off |

Expose as config, not hard-code — the server owner intends to **raise slots to 3 when HUNTER
is re-enabled**, which makes it 7 classes over 9 slots and changes the allocation. The role
planner must read `specialisationSlots` and the enabled-class list at runtime, never assume 2.

### 2.19 R19 — walking is already the right answer

Confirmed by the hunger numbers in §2.14: sprinting drains 1.0 per 10s against walking's 0.1.
A 2000-block scout at walking pace costs about a tenth of the same trip sprinting. Swimming at
3.0 is 15× walking, so the pathfinder should treat water as near-impassable for scouting
routes rather than merely slow.

Worth encoding as a hard rule in the movement layer, with sprint reserved for **fleeing** —
the one case where the hunger is worth it.


### 2.20 R21 — pathfinding

Checked current state rather than relying on memory.

**Altoclef is out.** The original repo was **archived in June 2024** and targets MC 1.18.
Community forks (TenorClef, MiranCZ) cover 1.16.5–1.21.1 but **nothing for 1.21.8**. It also
doesn't bundle Baritone — you'd install both anyway. And structurally it duplicates the very
thing this project is: a hierarchical task system over Baritone. Its task-tree design is
worth reading as prior art; the code isn't worth depending on.

**Baritone covers both target versions — but with different builds:**

| MC | Baritone | Fabric |
|---|---|---|
| 1.21.1 | **v1.11.3** | yes |
| 1.21.8 | **v1.15.0** | yes |

So the two-version build needs Baritone behind an interface anyway; the API is stable-ish
across these but not guaranteed identical. Wrap it in a `PathProvider` of your own from day one.

**Recommendation: Baritone for movement, custom for everything above it.** Writing a
production MC pathfinder (chunk-aware A*, movement types, break/place costs, fall damage,
parkour) is months of work for no differentiation. But go in aware of four real frictions,
all of which come from this server being non-vanilla:

1. **Baritone's cost model is time-based; this server's is hunger-based.** Baritone sprints and
   swims because they're *fast*. Here sprinting is 3.75× the hunger of walking per tick and
   swimming 15× (§2.14). Left alone, Baritone will do exactly the wrong thing and violate R19.
   `allowSprint=false` handles half of it; water avoidance needs cost work or heavy use of
   avoidance settings.
2. **Mining gates will cause loops.** Baritone doesn't know iron needs MINER 2 (§2.17) — it will
   path to ore, try to break it, be refused by the server, and retry. Feed `mining.json` into
   Baritone's block-avoidance settings, keyed to the bot's current MINER rank.
3. **Crafting can be refused** for hunger (§2.14). Task-layer concern, not pathfinder, but it
   means "walk to crafting table → craft" can fail at the last step and must be re-plannable.
4. **LGPL-3.0** ("with anime exception"). Depending on it is fine, and shipping it as a
   separate user-installed jar (as Altoclef did) keeps things simplest. **But if friction #1
   pushes you to fork Baritone to change the cost function, those changes must be published
   under LGPL.** Worth deciding early, because forking is the clean fix for #1.

**A middle path worth considering.** The bot has two very different movement needs:

| need | character | best tool |
|---|---|---|
| **Scouting** (R13, up to ~2000 blocks) | long overland walking, no block breaking, avoid water | a simple custom walker over the heightmap — naturally respects walk-only and water-avoidance, no cost-model fight |
| **Local work** (farm, mine, structures) | break/place, vertical, tight spaces | Baritone, where it's strong |

Scouting is the bulk of the early game and the part where Baritone's cost model fights hardest
against R19. A few hundred lines of terrain-following A* there may be less work than bending
Baritone, and it keeps the long-range behaviour fully under your control.

**Decided (Phase 1, [PHASE1.md](PHASE1.md)):** Baritone is an *optional*, separately installed
jar behind `PathProvider`; Zymbot loads without it.

**Suggested order:** wrap Baritone behind `PathProvider`, use it for everything at first, and
only write the custom scout walker if `allowSprint=false` plus avoidance settings can't stop
it swimming and sprinting across the map.


### 2.21 R22 — only LIBRARIAN is live, and it changes the allocation

`rank(player)` for forecasting is the **max rank across the active forecast classes**. Those
are LIBRARIAN and HUNTER — but **HUNTER is disabled by default** (§2.16). So right now:

> **A player's forecast rank *is* their LIBRARIAN rank. Everyone else is rank 0.**

Fused outcomes for a 3-player group, prior 0.0522 (blood moon, non-full night):

| situation | P(blood moon) |
|---|---|
| all 3 report — LIB5 + two rank-0 | **0.975** |
| all 3 report — LIB3 + two rank-0 | 0.925 |
| all 3 report — **no librarian** | 0.779 |
| only LIB5 reports, two silent | 0.524 |
| **LIB5 silent, two rank-0 report** | **0.082** |
| single LIB5 alone | 0.713 |
| single rank-0 alone | 0.181 |
| **nobody reports — LIB5 present** | **0.002** |
| nobody reports — all rank-0 | 0.016 |

Three operational conclusions:

1. **A high-rank librarian's silence is nearly a veto.** LIB5 silent against two rank-0
   positives lands at 0.082 — the group should *not* prep for a blood moon. Encode this: the
   bot must treat a known-high-rank silence as strong evidence, which it only can if the
   `[LF]` broadcast carries **rank** (§2.11). Confidence alone is not enough here.
2. **Detection can be crowdsourced; all-clears cannot.** Three novices (0.779) just beat one
   LIB5 (0.713) at spotting an event. But for the negative case a librarian is irreplaceable:
   0.002 vs 0.016, eight times more certain. Given no natural regeneration and PvP live on
   blood moons, a wrong all-clear is the expensive error — so **LIBRARIAN rank is bought for
   the nights nothing happens**, not the nights something does.
3. When HUNTER returns and slots go to 3, forecast rank becomes `max(LIBRARIAN, HUNTER)` and
   good readings get much cheaper to source. The planner should not hard-code "librarian".

**Allocation — current test group: two bots + you (R41).** 6 enabled classes × 2 slots =
exactly 3 players, so every class is covered once:

| player | classes | why |
|---|---|---|
| **Bot1** (headless) | **FARMER + MINER** | the grind roles; it mines the iron that raises its own hoe tier |
| **you** (Prism) | **BLACKSMITH + BUILDER** | the crafting hub and the decision seat — suits someone monitoring |
| **Bot3** (headless) | **MEDIC + LIBRARIAN** | healing for everyone, and a forecaster that never forgets |

Why this way round:

- **LIBRARIAN on a bot removes the weakest link in the forecast design.** The nudge system exists
  because a human librarian might forget `/lunarforecast`, and a missed reading drops the group's
  confidence to ~0.47 — the can't-decide zone. A bot runs it every dusk. (It starts at rank 0 like
  anyone, so its readings improve as it levels.)
- **You stay on the critical path.** Bot1's iron comes to you; you make the iron hoe that lifts its
  harvest rate, and the bucket and flint & steel that open the Nether via a lava-cast portal.
- **BUILDER is the most judgement-heavy class** — 250 gated recipes, and it decides what the base
  becomes.
- **MEDIC reduces to a rule for a bot** — heal whoever's lowest in range, within the stitches
  cooldowns and its 3-food cost.

Config-driven throughout: the planner reads `specialisationSlots` and the enabled-class list at
runtime, since the owner intends slots → 3 with HUNTER re-enabled.

### 2.22 R23/R24 — the bot nags instead of specialising

Bot is **FARMER + MINER** (config-driven, per R23), so its own forecast rank is **0**. It still
runs `/lunarforecast` and contributes — a rank-0 reading is worth LR 4, which is real evidence.
But the group's certainty now depends on a human remembering, which is exactly what R24 fixes.

**What a missing librarian reading actually costs** (bot r0 + human r0 + LIB5):

| | librarian responds | librarian silent/absent |
|---|---|---|
| both novices report | **0.975** | **0.468** |
| nobody reports (all-clear) | **0.0025** | **0.0239** |

Two things to notice. The all-clear degrades **9.6×**. And the detection case collapses to
**0.468** — which sits *between* the suggested LOW (0.25) and HIGH (0.7) thresholds, i.e.
squarely in "cannot make a call". A missing librarian reading doesn't just reduce confidence,
it lands the group in the one zone where no action is justified. That is the whole case for R24.

**Identifying the librarian.** A client-side mod cannot read another player's class rank, so:

1. Seed from config (`/bot librarian <name>`).
2. Otherwise **learn it from the bus** — `[LF]` messages carry rank (§2.11), so the first time
   anyone reports with rank > 0 the bot records them as the forecaster.
3. Fall back to a one-off chat question on day 1 if neither is available.

Keep a per-player best-known rank and prefer the highest; re-learn each session, since ranks
climb over time.

**Reminder policy.** The reading is bucketed per day, so it can be run any time during the day —
no need to wait for dusk. Given `DUSK = 13000`:

- First nudge around **t ≈ 11000**, leaving real time to act before dark.
- Second nudge at **t ≈ 12500** only if nothing received.
- **Hard cap two per day bucket.** Stop the moment their `READ` arrives; never nudge if a
  reading for this bucket already exists. Nagging a human twice a night is the limit before
  it becomes the thing they mute.

**Escalation when the nudge fails.** At dusk with no librarian reading, the bot should say so
explicitly rather than publishing a bare number — something like *"no librarian reading;
confidence 0.47, treating as unknown"* — and then **default to preparing**.

That default is not arbitrary. The costs are asymmetric: an unnecessary shelter night is a few
minutes of farming lost, while being caught out on a blood moon means forced surface spawns,
PvP live, and **no natural regeneration to recover from it** (§1.3, §2.4). Under ambiguity,
prep. The bot should only skip preparation when it has a *confident* all-clear — which, per the
table above, essentially requires the librarian.


### 2.23 R25 — two-phase start

```
/bot start   (1st)  -> DISCOVERY: broadcast HELLO, listen, show roster + countdown
/bot start   (2nd)  -> COMMIT:    freeze roster of N, assign sectors, begin core loop
/bot start   (again while running) -> ignored (or re-roster behind a confirm)
```

Discovery needs two message kinds on the same bus as the forecast (§2.11):

```
HELLO <uuid> <name> <pos>      # I am here and unstarted
ROSTER <uuid,...> <n>          # what I currently believe the group is
```

Notes:
- Converge before committing: a bot that hears a `HELLO` after publishing its own should
  re-broadcast `ROSTER` so late joiners and early joiners agree on N.
- **The 100-block range (§2.6) bites here.** If the transport really is proximity-limited,
  bots must be physically near each other to discover — which is fine, since a two-phase
  manual start implies they're grouped anyway. Worth making the discovery window generous
  (~30s) and showing the roster so a human can see if someone was missed.
- Degenerate case N=1 must work: a lone bot just scans alone.

### 2.24 R26/R27 — why the spiral is the wrong primitive

An N-armed geometric spiral (bot *k* takes angular offset 2πk/N) is the obvious reading of
R26, and it fails exactly on your horseshoe.

If the group spawns inside the bay of a C-shaped ocean, two of three spiral arms point into
water. And water is not merely slow here — swimming drains **3.0 hunger per 10s, 15× walking**
(§2.14). A spiral arm crossing a bay can cost more hunger than the whole rest of the search.
The bot would either swim it (ruinous) or stall against an obstacle its plan never modelled.

**The right primitive is frontier-based exploration**, the standard approach in multi-robot
coverage, adapted to the hunger cost model:

1. Maintain a coarse grid of **known / unknown** chunks (the client only knows chunks it has
   loaded, so "known" literally means "somebody walked near it").
2. The **frontier** is the boundary between known-walkable and unknown.
3. Each bot scores frontier cells by `information_gain / travel_cost`, where travel cost uses
   the **walk-only, water-hostile** cost model from R19 — so water frontiers price themselves
   out automatically, with no special-case code.
4. **Cluster the frontier** into spatially separated groups. Assign clusters to bots (greedy
   by cost, or Hungarian for a proper assignment).
5. Re-plan on a timer and whenever the frontier set changes shape.

**R27 falls out of step 4 rather than needing its own rule.** While the group is in a narrow
corridor there's one frontier cluster and the bots stay together. The moment the land opens
up, the frontier splits into multiple clusters and the assignment step pushes bots onto
different ones. "Split when the terrain opens up" *is* frontier clustering — you don't have to
detect openness separately.

The horseshoe then resolves correctly with no special handling: every water frontier is
priced out, so all bots follow the land around the bay, and they fan out as soon as the coast
widens into open ground.

**Coordination is cheap.** A full shared chunk map is too much for a chat bus. The minimum
that works is **claim broadcasting**:

```
CLAIM <uuid> <targetX> <targetZ> <radius>
DONE  <uuid> <targetX> <targetZ> <result>
```

Each bot discounts frontier cells near another bot's live claim. That alone produces the
"parting ways" behaviour; a shared map is an optimisation, not a requirement.

**This is a targeted search, not blind coverage.** The goal is §2.12's biome junction with ore
access, not maximal map knowledge. So the utility should be

```
utility = P(site is good | what we know nearby) × info_gain / travel_cost
```

Bias toward frontiers adjacent to biomes that complete the staple set: if the group is sitting
in TAIGA (beetroot/berry/pumpkin/mushroom at 100%), frontiers hinting at PLAINS (wheat),
FOREST (carrots) or WINDSWEPT (potatoes) are worth far more than more taiga.

**Two practical constraints:**

- **R2's leash fights R26.** If the bot must stay within leash range of a human, its search
  radius is capped by that, not by the frontier. During the scouting phase either the humans
  travel with the bot, or the leash must be relaxed explicitly. Worth surfacing as a warning
  rather than silently stalling.
- **Singleplayer can predict chunks; the server cannot.** Offline you know the seed and could
  look up biomes without walking. That would make testing wildly unrepresentative — the whole
  cost model exists because exploration is expensive. Keep any seed-based lookup behind a flag
  that is **off by default**, or skip it entirely.


### 2.25 R28/R29/R30 — the probe problem

You're right that this is a probe-mission shape: once the bot is past transport range you
cannot command it and you cannot tell whether it sank. The fix is the same one NASA uses —
**stop relying on recall and send it out with a contract.**

**Mission contract.** The bot departs with pre-agreed return conditions and honours whichever
trips first:

```
return when ANY of:
  elapsed         > T_max
  hunger          < return_budget(distance_home) + margin
  chunks_scanned  > budget
  site_found      with score > threshold
  no bus contact  > T_silence        # dead-man timer
```

Nothing has to reach it mid-flight. A recall command still works *when in range* — treat it as
an optimisation, not the mechanism.

**Breadcrumbs (R29) are the right return path, and cheap.** Record the outbound route as
waypoints. Returning along it needs no new pathfinding and is guaranteed walkable, because the
bot already walked it. It also gives the mission a natural rendezvous: come home, sync the
scouted map over the bus, go out again. That's your data-sharing story for free.

**R30 — don't. The numbers are lopsided.** Walking is astonishingly cheap here, because vanilla
walking adds no exhaustion and civfabric charges 0.2 per 10s (civfabric 0.4.2):

| distance | walking | time | sprinting | swimming |
|---|---|---|---|---|
| 500 | 2.32 | 1.9 min | 6.7 | ~150 |
| 1000 | 4.63 | 3.9 min | 13.4 | ~300 |
| **2000** | **9.27** | 7.7 min | 26.7 | ~600 |
| 4000 (2000 round trip) | 18.53 | 15.4 min | 53.5 | ~1200 |

If the drain unit is hunger points, a 2000-block walk costs **9.3 of 20**; if it's exhaustion,
about 2.3. So a 2000-block walk is **roughly half a hunger bar** — twice what it was under
civfabric 0.4.0 — and a full bar carries a bot ~4300 blocks with no food at all.

Against that, dying costs **`DEATH_XP_LOSS = 0.5` — half of all class XP.** For a FARMER+MINER
bot that is a direct hit to harvest success (§2.17) and ore access. Trading ~9 hunger for 50%
of the farm's yield curve is a terrible deal.

*Confirmed in play (2026-09-22): a `/kill` printed "[CivLabs] » Death cost you 50% of your class XP —
Farmer −1.5 → 1.5 left", then "[=] <name> respawned nearby". That respawn line is a reliable
server-side signal that a player is alive again. The headless client can't click Respawn yet, so
the bot mod needs auto-respawn (or `doImmediateRespawn true` on the world) before any death test.*

So **suicide is an emergency fallback, never a plan.** It is defensible in exactly one case:
stranded with no food, no reachable food, and no route home — where the alternative is dying
anyway *and* losing the trip. Note the chunk map is client-side and **survives death**, so the
scouting data does come home even then; what is lost is the XP and the inventory, which ends
up in a grave 2000 blocks away.

**Point of no return.** The contract's hunger clause is the important one — real fuel budgeting:

```
return_budget = distance_home / 43.17 blocks-per-interval × 0.2 × safety_factor
turn around when  hunger - return_budget < reserve
```

With `safety_factor ≈ 2` (detours, terrain, mobs) and a small reserve, a bot leaving on a full
bar with a few meals can comfortably work a 2000-block radius and still walk home.

### 2.26 Chunk caching — what exists

**Bobby** (Johni0702) is the closest existing thing: client-only Fabric, **LGPL-3.0**, caches
every chunk the server sends into `.minecraft/.bobby` and re-displays it beyond server view
distance. Actively maintained across the 1.21.x line.

**But it does not solve scouting.** Bobby only caches chunks *the server already sent you* —
it extends memory, not perception. It cannot reveal terrain the bot has not walked near, so it
does not reduce the travel cost that the whole frontier model is built around. Useful so the
bot doesn't re-walk known ground; not a scanner.

**Recommendation: write your own cache, it's tiny.** The bot doesn't need chunk data — it needs
a coarse grid of *biome id + walkable/water + explored flag* per chunk, maybe per 4×4 chunks.
That's a few bytes per cell, trivially serialisable, trivially shareable over the bus at
rendezvous (R29), and carries no LGPL obligation. Full chunk storage would be orders of
magnitude larger for information the bot never uses.

Same for breadcrumbs: a waypoint list is a handful of lines. Mods like JourneyMap and Xaero's
cache and draw this, but they're built for humans to look at, and pulling a dependency for a
list of coordinates isn't worth it.


### 2.27 R31 — server-side bots, and what it costs

**No, Baritone has no server-side version.** It hooks the client — `Minecraft`, the local
player controller, the client tick loop. There is no port, and there won't be a
straightforward one; the design assumes it is driving *a client's* inputs.

**Also worth flagging: "Paper Fabric server" isn't a thing.** Paper is a Bukkit/Spigot fork and
Fabric is a separate platform; they don't run each other's code. Your server is definitively
**Fabric** — `civfabric` and `zymlabs-rules` are Fabric mods with mixins. Bukkit-on-Fabric
bridges (Cardboard, Banner) exist but are fragile, and a mod as invasive as `civfabric` is
exactly the kind they break on. So the Paper fake-player plugins (FPP and friends, some of
which do ship a real A* engine) are **not available to you**.

That leaves three architectures:

| | A. Headless clients | B. Server-side fake players | C. Protocol bots |
|---|---|---|---|
| what a bot is | a real MC client running Fabric + your mod | a `ServerPlayer` spawned server-side (Carpet-style) | a process speaking the protocol, no MC client |
| **Baritone** | **yes** | no | no |
| cost per bot | ~1 GB RAM, a full client | very light | very light |
| "summon on the fly" | launch a process | trivial | trivial |
| real player to the server? | yes — own bed, class rank, town count | yes | yes |
| modded content | works, it's a real client | works | you reimplement it — likely fatal here |
| pathfinding | Baritone | **you write it** | you write it |

**Vanilla mob pathfinding is not a fallback.** `GroundPathNavigation` is built for short-range
mob chases: the node budget is tied to the follow-range attribute (~32 blocks for most mobs),
it cannot break or place blocks, and it handles vertical terrain badly. A zombie paths perhaps
16–32 blocks. For a 2000-block scout it is unusable — not "worse pathfinding", but the wrong
tool by two orders of magnitude. If you go server-side, **writing an A\* is mandatory, not
optional.**

**Option C is probably out.** This server is heavily modded — Cobblemon, `civfabric`'s class
and packet systems, custom payloads. A protocol bot would have to reimplement enough of that
to be a losing battle.

**Recommendation: A for fidelity, B only if RAM forces it.**

Option A is also the *least* new work, because it's what you already have: the client-side mod
you're building, run on more clients. Bots are genuine players — they own beds, hold class
ranks, count toward `TOWN_MIN_BEDS`, and appear in `/lunarforecast` with real ranks. On an
`online-mode=false` local server, extra accounts are free. "Summon on the fly" becomes
"launch another client process", which is scriptable.

**One thing to watch if you do go server-side (B):** a server-side bot can load and read *any*
chunk. That quietly deletes the entire premise of §2.24 — frontier exploration only matters
because perception is expensive. A server-side bot could biome-scan a 2000-block radius
instantly. You'd have to impose an artificial perception limit to keep the design meaningful,
which is the same trap as the singleplayer seed lookup (§2.24).

**Architecture hedge.** Whichever way this lands, write the pathfinder core against a narrow
`BlockAccess` + `CostModel` interface rather than against Baritone or against `ServerLevel`
directly. Then the walk-only/water-hostile cost model (R19) lives in one place, and the same
A\* serves a server-side bot or a client-side fallback if Baritone's cost model proves
unbendable (§2.20). That keeps R31 and the client-only scope both open at low cost.


### 2.28 R32 — headless clients on this machine

**The 8 GB isn't real.** The instance has `OverrideMemory=false` and Prism's global
`MaxMemAlloc=8192` — that's the launcher's default *ceiling*, inherited, not a measured
requirement. Nothing has ever proven the pack needs it.

**The tool: [HeadlessMC](https://github.com/headlesshq/headlessmc)** (MIT). It patches LWJGL so
every function is a stub — no GL context, no window, **no display server at all**. Launch is
`launch fabric:1.21.1 -lwjgl`. Two details that matter here:

- **It works on macOS.** The usual headless recipe (Xvfb + Mesa llvmpipe) is Linux-only, and
  you're on an M2. Because HeadlessMC stubs LWJGL rather than faking a display, it sidesteps
  that entirely — it's a plain JVM process.
- **Dummy assets.** It can skip loading textures, models and sounds, which are the bulk of a
  client's memory. Companion `hmc-optimizations` skips render code paths too.

**Hardware budget: 16 GB, Apple M2, 8 cores.** Roughly 12 GB usable after macOS. A realistic
local test rig is *one dedicated server + one GUI client (you) + one or two headless bots*.
Three headless bots plus a server plus a GUI client will be tight.

Don't trust a guess for per-bot RAM — **measure it**. Launch one headless instance, watch RSS,
then set `-Xmx` from evidence. Budget ~2–3 GB as a starting point and tune down; Cobblemon
alone ships 1025 species files, so data-side memory is non-trivial even without rendering.

**The known risk is Cobblemon and GeckoLib.** Both are heavy rendering mods, and stubbed LWJGL
is exactly where such mods crash at init. Test connectivity with the full pack *first*, before
building anything on top of it.

**Trimming the pack won't be guided by metadata.** All 82 mods declare `environment: "*"` —
none mark themselves client- or server-only, so there's no cheap way to tell what's droppable.
You'd find out empirically: remove a candidate, see if the server still accepts the connection.
Likely-safe candidates are the cosmetic ones (`immersive_paintings`, `mcw-paintings`,
`simplehats`, `appleskin`, `VisualWorkbench`, `voicechat`), but that's a hypothesis, not a fact.

**Render distance is not a free saving.** It's the bot's *perception radius* — the bot only
knows chunks it has loaded (§2.24). Dropping render distance to 4 saves memory and directly
slows scouting, because frontier discovery scales with how much terrain each step reveals.
There's a floor set by how fast you need R26 to run, not by comfort.

**Suggested order:**
1. Launch one headless instance of the existing pack via HeadlessMC. Confirm it connects.
2. Measure RSS at idle and while walking; set `-Xmx` from that.
3. Trim cosmetic mods only if step 2 demands it, testing connection after each removal.
4. Only then decide how many bots this machine supports.

---

## 3. Open questions

1. ~~Bot identity~~ — **resolved** (§2.9): client-side, the bot *is* the local player.
   Follow-on: **how do you want to test the coop half?** Singleplayer structurally can't.
   A local `online-mode=false` server with 3 clients is the obvious route.
2. ~~Class rank / slots / forecast classes~~ — **resolved** (§2.13, §2.16, §2.21).
   Residual: LIBRARIAN and HUNTER are the *known* forecast classes — worth confirming against
   the server's `classes.json` that no third one is configured.
3. *Partly resolved 2026-09-23 (PHASE2.md P2-1/P2-2/P2-6): bots regroup to and leash to the
   **nearest Zymbot teammate** only — never a human without the mod.* **R2 leash range default**, and behaviour when multiple humans are present — nearest
   player, a designated owner, or the centroid? Note this now conflicts with R26 (§2.24):
   a leashed bot cannot range far enough to scout. Does the leash suspend during scouting,
   or do the humans travel with the bot?
4. **1.21.8.** You said skip the instances — but the two versions still differ mechanically.
   Should the 1.21.8 build target the same pack assumptions, or vanilla behaviour?
5. **Where does the 100-block chat range come from?** It isn't in this instance (§2.6). If
   it's a live-server rule, I need its exact value and whether it applies to system messages
   and command feedback as well as player chat.
   *Tested 2026-09-22 on the Full pack, LAN world:* at ~1000 blocks, headless Bot1 received
   public chat (`Bot2 >> hello far`, the pack's chat format) **and** `/msg`
   (`Bot2 whispers to you: …`). So the pack itself has **no range limit**; if the live server
   has one, it's a server-only plugin. The bot must **probe on join** (whisper HELLO to a known
   teammate, wait for the echo) rather than assume either way.
   *Cold start:* players respawn scattered hundreds of blocks apart (seen: (28,−82), (164,59),
   (−338,244), (585,−282)) and only the tab list shows who's online. Regroup, in layers:
   (1) local out-of-game channel between the owner's own bots, (2) `/msg` coordinates if the probe
   succeeds, (3) world spawn as the fallback meeting point.
6. **Bot 1's reply channel.** Does the aggregator's correction go to public chat (visible to
   humans, which is the point) or to the bot bus? If public, it needs to be human-readable,
   not the compact `[LF]` form.
7. **Does the aggregator also *publish* its own reading**, or only consume and adjudicate? If
   the bot has no class rank (Q2), it has no reading of its own to contribute.

---

## 4. Data this rests on

All constants above are extracted, not assumed — see `survival-data/server_rules.json`
sections `pacifism`, `bed_and_rest`, `death_and_graves`, `lunar_forecast`, plus
`forced_gamerules` and `lunar_events`. Provenance and confidence notes are in
`SURVIVAL_EARLY_GAME.md` §7.


---

## Knocked out — civfabric `dbno`

Read from civfabric 0.4.2 bytecode on 2026-09-22 (all constants compiled in — no config file);
first seen live when a provoked zombie downed Bot1. [R] = read in the code, [I] = inferred.

**Going down.** Any hit that would kill you downs you instead, unless it overkills by 10+ HP
(`/kill`, void). No damage type is exempt [R]. Downed health = `max(0.5, 10 + health after the
hit)` [R]. You're *not* dead (no death screen) — you sit on an invisible, tiny armour stand and
can't move, dismount, use or attack anything, break blocks or pick up items; you can look, chat and
run commands [R/I]. Hunger doesn't drain while down [R].

**Bleeding out.** 60 s (1200 ticks), counting on even while logged off [R]. Darkness pulses after
10 s. **Any further lethal hit kills for real** [R] — that's what finished Bot1 three seconds after
it went down. Bleeding out, `/giveup` (aliases `/suicide /die /rip /gg /d`) and the killing blow
are real deaths: vanilla drops, and the class-XP death penalty (0.5, moon-adjusted) [R].
`/giveup` is refused if a *player* downed you [R].

**Revive.** A **MEDIC** (rank > 0 when the class system is active) right-clicks you within 7 blocks
holding **Stitches** (`civfabric:stitches`, shapeless: 8 paper + 1 sugar cane), which opens a
6-row "Reviving <name>" screen: click the 7 red **injury** items (custom data `civfabric:injury`),
never the green **healthy organs** (a wrong click adds 3 injuries). You come back at
`2 × medic rank` HP (max 5 ranks → 10 HP) [R]. `/revive` (self-revive) is **op-only** — Bot1 saw
the `[Revive]` button only because it's an op in the LAN world; a real server bot won't have it.

**Carry / leash / pat-down.** A Medic can carry a downed player on their shoulders (half speed,
sneak to drop). Hunter rank ≥ 2 can leash them. Anyone can sneak-right-click to search their
inventory and drop items out of it [R].

**What a client can see** (no custom packets; all vanilla) [R/I]:

| signal | meaning |
|---|---|
| boss bar **"Bleeding Out"** (red, 20 notches, progress = time left) | *I* am down — reliable |
| boss bar **"Being Revived"** (green, progress = injuries treated) | someone is reviving me |
| chat `[CivLabs] » You're knocked out.` / `You're back on your feet.` | down / up (literal text) |
| my vehicle is an armour stand; health 0.5–10 | down |
| another player riding a 0.01-scale invisible armour stand, blood-red dust | *a teammate* is down |

**What it means for the bot** (design; not built):

1. **Downed is the top interrupt**, above everything: stop pathing and all actions (they're
   refused anyway), and shout — a bus `DOWNED x z secs` plus a `/msg` to nearby humans. **Give up
   at once unless someone can come** (owner's call, 2026-09-22): being revived, a medic bot near,
   or a human near for 45 s (owner: 15 was too short to walk over with stitches; `/zbot set downed`).
   Bleeding out is the same death, only a minute later. *Built in 0.1.9; 45 s in 0.1.10.*
2. **A fleeing bot at critical health must get away *before* it's downed** — once down it can't
   move, and the next lethal hit is a real death. That's why the reflex timing fix (FIXLIST #7)
   matters so much.
3. **Reviving is a bot skill worth having:** the minigame is trivially machine-readable (injury
   items carry `civfabric:injury` custom data). A MEDIC bot carrying Stitches can answer a
   `DOWNED` call within 60 s. Stitches are cheap (paper + sugar cane) — worth a milestone rung.
4. `/revive` is op-only: the bot must never rely on it.
