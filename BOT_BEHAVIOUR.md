# What the bot actually does

The control spec. [BOT_DESIGN.md](BOT_DESIGN.md) explains *why* each number below is what it
is; this file is just the behaviour.

This is the spec to build against. **Partly built (2026-09-30):** the interrupt stack, survival
interrupts, regroup/follow, survey, break-and-collect, graves. What's built is in PHASE1–3.md and
SKILL.md; where this file and the code disagree, the code and those files win.

---

## The shape

A **prioritised interrupt stack** over a **milestone planner**. Every tick:

```
1. SENSE     world state, bus messages, player positions, time of day
2. INTERRUPT walk the table top-down; the first trigger that fires takes control
3. OBJECTIVE if nothing interrupted, work the current objective
```

There is **no fixed phase order**. The bot surveys the world, works out which milestone is the
binding constraint, and acts on that — so restarting mid-game, or joining an established base,
behaves correctly without special cases.

Interrupts pre-empt; they don't queue. A higher one firing suspends whatever was running, and
the objective resumes when it clears.

---

## Interrupt table

Evaluated in order, every tick. First match wins.

| # | trigger | action | why |
|---|---|---|---|
| −1 | **knocked out** (civfabric dbno: "Bleeding Out" boss bar) | let go of everything; tell the team (bus `DOWNED`) and each human within 64 blocks (`/msg`); wait only while being revived, a medic bot is near, or a human is near (45 s, `/zbot set downed <s>`) — otherwise `/giveup` at once | bleeding out and giving up are the same death, so waiting only pays if someone can come — see BOT_DESIGN → Knocked out *(built 0.1.9, wait 45 s since 0.1.10; medic bots don't exist yet)* |
| 0 | player pressed WASD / jump / inventory | → `SUSPENDED`, start resume countdown | R12 — the human always wins |
| 1a | air ≤ ⅔ while in water | surface, swim for the nearest dry land (or tread water) | a player who presses nothing sinks; drowning has no attacker to run from |
| 1 | health ≤ critical | eat a healing food; if none, break contact and retreat | no natural regen — damage is not self-correcting |
| 2 | engaged in combat | hand control to the **combat state machine** (below); Baritone paused | Baritone is poor at combat |
| 3 | blood moon imminent/active | move to shelter, stay until clear | forced surface spawns, PvP live, sleeping blocked |
| 4 | `hunger − return_budget < reserve` | abort task, follow breadcrumbs home | the point of no return; past it the bot strands |
| 5 | distance to nearest human > leash | pause task, close distance | R2 |
| 6 | hunger ≤ eat threshold | eat best-value food (see rotation) | — |
| 7 | — | work the current objective | — |

**As built (2026-09-30), differences from the table above:**
- **Human input (#0)** pauses the bot; it takes control back 10 s after the last key (`/zbot stop` to keep it).
- **Water:** besides drowning (#1a) there are *stranded* (out of its depth with nothing to do: swim to
  land with water straight beside it and dry on top), *wading* (idle in shallows: step onto dry land)
  and diving (a task, for graves under water).
- **Being hit:** the `danger` setting decides — `modpack` (default) runs at the first hit, dry ground
  first; `easy`/`normal`/`hard` only at critical health. No combat machine yet (PHASE3 decision C).
- **Hazards (0.1.43):** hurt by a block it's touching (fire, cactus, magma, berry bush, lava, …) → step
  to the nearest safe spot within 5 blocks. Those hits have no attacker, so the retreat never fired.
- **Fidget watchdog:** spinning or bobbing in place for 10 s → hold still, log why.
- **Knocked out (#−1)** and **regroup** are built; blood moon (#3) and return budget (#4) are not.

**Good moons are objectives, not interrupts.** Harvest and blue moons (both on the live server)
don't block sleeping — so sleeping *skips* them. On a likely harvest moon the bot holds ripe crops
and harvests during the night (drops ×2, super ×4); on a blue moon it enchants and repairs (costs
×0.5, super ×0.25). The forecast feeds these the same way it feeds blood-moon shelter.

**Thunderstorms are not on this list.** zymlabs `StormLightning` measures `clearanceBelow` —
empty air *underneath* the player — and only bites from 8 blocks off the ground, reaching full
damage at 48. It exists to stop people flying in storms with an elytra or a flying Pokémon. A
bot on foot has clearance ~0 and is never at risk. Waiting out a storm indoors while crops grow
is a perfectly good *choice*, not a safety interrupt. (It becomes a hard rule the day the bot
ever flies.)

`return_budget = distance_home / 43.17 × 0.2 × safety_factor` (safety ≈ 2) — walking drains 0.2
per 10 s and covers 43.17 blocks in that time.

**Under ambiguity, prefer the safe branch.** An unnecessary shelter night costs minutes of
farming; being caught out costs half the class XP and there is no passive healing.

---

## Startup: assess, don't assume

The bot does **not** assume a fresh spawn. On start it surveys, works out where the
civilisation actually is, and picks up from there. Restarting mid-game, or joining an
established base, must both work.

```
ASSESS  →  find the binding constraint  →  act  →  re-ASSESS
```

### What it surveys

| scope | reads |
|---|---|
| **self** | inventory, classes + ranks, health, hunger, owned bed, food-history buffer |
| **persisted** | saved roster, player→class assignments, known base/farm/bed coords, explored-chunk grid, breadcrumbs |
| **local** | farm plots, tilled soil, beds, heat sources, shelter, crafting table / furnace, storage contents |
| **group** | who answered `HELLO`, who is online, which classes are covered |
| **world** | biome, time of day, lunar state, distance to spawn |

Persisted state is what makes "grab the saved bot list and class assignments" work — and it
must be treated as a *hypothesis*, re-verified against the world. A farm in the save file that
no longer exists means the milestone is unmet again.

### What the survey costs, and what it caches

Most of the survey is free. The split that matters:

| layer | examples | cost to gather | volatility | strategy |
|---|---|---|---|---|
| **self** | inventory, hunger, health, class ranks | ~free | high | never cache — read live |
| **group** | roster, player→class map | free (arrives on the bus) | low | update on message / join / leave |
| **structures** | beds, farm plots, chests, campfires | moderate block scan | medium | **event-driven + verify-on-use** |
| **terrain** | biome grid, walkability, explored chunks | **expensive — requires walking** | **none** | cache permanently |

The useful accident here: **the expensive layer is the one that never changes.** Terrain and
biomes are fixed, so the explored-chunk grid is *monotone* — it only ever grows, and never
needs invalidating. The volatile layers are the cheap ones. So the costly part of surveying is
paid once per area, for good.

**How it knows a structure changed.** For chunks currently loaded, it doesn't have to guess —
**the server sends block updates**, and the client receives them. Subscribe, and when a changed
block is one the bot tracks (bed, farmland, crop, chest, warmth source), invalidate that cell.
That's exact, not a heuristic.

For chunks that are *not* loaded, you genuinely cannot tell, so:

- **Mark-unverified-on-reload.** While a chunk was unloaded no updates arrived, so on reload its
  structure facts drop to "remembered, unconfirmed".
- **Verify-on-use.** Don't re-scan eagerly. When a plan depends on "bed at X", confirm it on
  arrival — the bot is walking there anyway, so the check is free.
- **Confidence decay** for facts it can't reach: a farm seen three in-game days ago is weaker
  evidence than one seen this morning, and a milestone resting on stale evidence should be
  re-checked before it's relied on.

So a cold-start survey is a bounded block scan plus some file reads, and steady state is
event-driven — not a per-tick cost.

### The milestone ladder

The end goal is to **beat the game**. On this server that is mostly a class-XP problem, and
food is what funds the grind. Each milestone has a concrete check:

| # | milestone | met when | owner | min players |
|---|---|---|---|---|
| M0 | `HAS_TOOL` | a hoe is in inventory or reachable storage | bot | 1 |
| M1 | `HAS_FOOD` | food buffer ≥ floor | bot | 1 |
| M2 | `HAS_BED` | owned bed claimed, heat source within radius 4 | bot | 1 |
| M3 | `HAS_SHELTER` | enclosed, roofed space adjacent to the bed | bot / BUILDER | 1 |
| M4 | `HAS_FARM` | tilled plot under open sky, ≥1 crop planted | bot (FARMER) | 1 |
| M5 | `FARM_SUSTAINS` | ≥6 distinct foods (70%) sustained, production > consumption; 12 for full value | bot (FARMER) | 1 |
| M6 | `TOWN` | ≥3 claimed beds within 64 blocks | group | **3** |
| M7 | `CLASSES_COVERED` | FARMER, BLACKSMITH, MINER, MEDIC all held by someone | group | roster |
| M8 | `IRON_AGE` | MINER 2 reached, iron flowing | bot (MINER) | 1 |
| M9 | `TOOLED` | BLACKSMITH 2 → iron hoe; BLACKSMITH 3 → bucket, flint & steel | BLACKSMITH | 1* |
| M10 | `NETHER_READY` | portal route secured, gear and food stocked | group | 1* |
| M11 | `NETHER` | blaze rods obtained | group | 1* |
| M12 | `END` | pearls → eyes → stronghold | group | 1* |

`1*` = achievable by one player only if that player holds the needed class. `roster` = depends
on who's present and what they picked, not a fixed count.

**Rungs the roster can't meet are skipped, not failed.** A milestone carries a minimum player
count, and the planner skips any rung the current roster can't satisfy, moving on to the next.
It is *deferred*, not unmet — so it never becomes the objective, and the bot never gets stuck
chasing a town with two people in it. When a third player joins, the roster changes, the planner
re-assesses, and the rung comes back into play.

That one rule covers every group size — this two-player test, the full three-player group, and a
solo vanilla run later — without special cases.

The ladder is **open** — new rungs get inserted as scope is discovered. A milestone is just
`(id, check, owner, contribution, min_players)`, so adding one is data, not a refactor. Order matters only
in that the planner takes the lowest unmet rung; nothing else depends on the numbering.

### Current test profile — you + Bot1

Two players, 2 class slots each, so 4 of the 6 enabled classes are covered:

| player | classes |
|---|---|
| Bot1 (headless) | FARMER + MINER |
| you (Prism) | BLACKSMITH + BUILDER |
| *Bot3 — joins later* | *MEDIC + LIBRARIAN* |

**Deferred until Bot3 joins:**

| what | why | effect meanwhile |
|---|---|---|
| **M6 `TOWN`** | needs 3 claimed beds; beds are one per player | skipped |
| **M7 `CLASSES_COVERED`** | nobody holds MEDIC | skipped |
| **MEDIC healing** | nobody holds MEDIC | the only heals are healing foods and bed rest (1 hunger per HP) |
| **librarian nudges** | nobody holds LIBRARIAN | no nudge is sent; there's no one to send it to |
| **high-rank forecasts** | forecast rank = LIBRARIAN rank | both readings are rank 0; two positives fuse to only ~0.47, so the bot will default to sheltering on any reported blood moon |

The last one is worth expecting during testing: with no librarian, blood-moon warnings will be
**low-confidence and cautious by design**. That's correct behaviour for the evidence available,
not a bug.

Everything else is in scope: bootstrap, food rotation, site search, bed + campfire, farming,
mining to MINER 2 for iron, combat and hunting, the interrupt table, and the you↔Bot1 tool
hand-off.

### Choosing what to do

```
for m in ladder:            # lowest first
    if met(m): continue
    if owner(m) is me:      -> objective = m
    else:                   -> objective = best contribution I can make to m
    break
```

**The second branch is the important one.** M9 belongs to the Blacksmith, but the bot is not
idle while it waits — it mines the iron that M9 consumes. A milestone owned by someone else
becomes a *supply* task, not a stall. If there is no useful contribution, fall back to the
next milestone the bot *can* advance, and post a request on the bus.

Re-assess on a timer, on any milestone flipping, and whenever a player joins or leaves.

### Milestones have three states, not two

`met / unmet` is not enough. The dangerous case is a **false negative**: the survey finds no farm
and starts a second one, when a site was already chosen and is half-built — by this bot before a
restart, by another bot, or by a human. Same for beds.

| state | meaning |
|---|---|
| `UNMET` | nothing exists and nobody has claimed it |
| **`IN_PROGRESS`** | a site is chosen or construction has started — **not** a reason to start again |
| `MET` | the check passes |

Evidence that something is in progress, any one of which blocks a restart:

- **persisted intent** — "farm site chosen at (x, z)", saved the moment the decision is made,
  *before* any block is placed
- **a live `CLAIM` on the bus** from another bot for that milestone
- **partial structures** — tilled soil with no crops yet, fence posts, a placed but unclaimed bed,
  a campfire with no bed beside it
- **a human said so**

The rule: **when unsure between `UNMET` and `IN_PROGRESS`, go and look before building.** A
duplicate farm costs seeds, hoe durability and days; walking over to check costs a few minutes.
This is the same verify-on-use principle as the cache.

Persist intent *early*: record "I'm building a farm at X" when choosing the site, not when
finishing it. Otherwise a restart mid-build loses the only evidence the site was ever chosen.

### When the bot's role has changed

On restart the bot may hold different classes than its saved state says — reassigned by a
human, a server reset, or a different account. Its old plan now rests on milestone ownership that
no longer holds.

1. **Detect** — current class ranks ≠ persisted role.
2. **Drop the old plan.** Don't keep executing FARMER work as a BLACKSMITH.
3. **Recompute** what it can own and contribute with the classes it actually has.
4. **Ask** — humans first, then other bots: *"I'm BLACKSMITH + BUILDER now, not FARMER + MINER.
   What should I focus on?"*
5. **Fall back** to its own best-contribution choice if nobody answers within a window.

**How much to listen — the deference weight.** Human direction gets a tunable weight against the
bot's own plan, for when a human is bossy:

```
score(objective) = own_plan_value × (1 − deference) + human_request × deference
```

- `deference = 1.0` — do what humans say, full stop.
- `deference = 0.5` — weigh a request equally with its own judgement.
- low — treat requests as suggestions.

Order of authority: **human > other bots > own plan**. Bots are peers; humans outrank them.

**Deference applies to *what to work on*, never to the interrupt table.** A chat instruction can
redirect the bot's objective, but it can't talk the bot out of eating, healing, or sheltering from
a blood moon. A human who wants to override survival behaviour does it the direct way — WASD
takes control (interrupt 0).

### Why the ladder looks like this

Scanned from the pack's own gate tables:

- **Nether access is class-gated.** Obsidian needs a diamond pickaxe → **BLACKSMITH 3**, and
  diamond ore needs **MINER 4**. Lighting the portal needs flint & steel → **BLACKSMITH 3**.
- **There is a bypass.** Casting the frame with lava and water avoids mining obsidian entirely,
  so the Nether only really needs **BLACKSMITH 3** (bucket + flint & steel) rather than
  BLACKSMITH 3 + MINER 4. It saves the whole MINER 4 grind, so the planner should prefer it.
- `blaze_powder` and `ender_eye` are **ungated**, so the End is not class-locked once you can
  get to the Nether.
- Side gates worth knowing: brewing stand → **MEDIC 4**, enchanting table → **LIBRARIAN 3**,
  anvil → BLACKSMITH 3, blast furnace → BLACKSMITH 1.

And the reason food dominates the early ladder: **class XP is the currency of progression, and
dying costs half of it.** A bot that starves or dies grinding is moving backwards. M0–M5 exist
to make M8 onward survivable.

### Bootstrap — when the survey finds nothing

Only runs when M0/M1 are unmet, i.e. a genuine cold start.

**Why the hoe first:** planted crops cannot be harvested *at all* without one
(`requiresHoe` is the default), so it gates the entire food economy. A **wooden hoe is the
only hoe craftable without a BLACKSMITH**, and it is ungated — `RecipeGates.canCraft` returns
true for any recipe with no rule, and `wooden_hoe` has none.

| step | craft | grid | cost |
|---|---|---|---|
| 1 | punch a tree → **≥ 3 logs** (PHASE3 M0) | — | bare hands work |
| 2 | logs → **8 planks** | 2×2 inventory | ~0.25–0.5, free while classless |
| 3 | 4 planks → **crafting table** | 2×2 inventory | free while classless |
| 4 | 2 planks → **4 sticks** | 2×2 inventory | free while classless |
| 5 | place the table | — | — |
| 6 | 2 planks + 2 sticks → **wooden hoe** | **3×3 — needs the table** | ~0.35 hunger |

Step 6 is why the table is mandatory: the hoe is a three-row recipe and will not fit the 2×2
inventory grid.

Build a **wooden axe** straight after (3 planks + 2 sticks). With zymlabs Treecapitator that
turns wood from per-log into per-tree — 8 logs on the first break, radius 16.

While *classless*, the classless set is free to craft (planks, sticks, table, torch, furnace,
chest, barrel). Once a class is taken the bot pays for those too, so the cheapest order is
bootstrap-then-specialise — but the saving is fractions of a hunger point and should never
delay specialising, since FARMER rank drives harvest success.

### Site search — only when M4 needs a location

Not a fixed phase; it runs when the bot needs somewhere to farm and has none.

```
loop:
    update known/unknown chunk grid from loaded chunks
    frontier  = boundary(known_walkable, unknown)
    clusters  = spatial_cluster(frontier)
    drop clusters near another bot's live CLAIM
    target    = argmax  site_promise × info_gain / travel_cost
    broadcast CLAIM
    walk to target          # never sprint; water is near-impassable
    score the site; if score ≥ threshold → settle here
```

`travel_cost` uses the walk-only, water-hostile model, so water frontiers price themselves out
with no special case. Bots fan out because claimed clusters are discounted, and they split
exactly when terrain opens — that is when the frontier breaks into separate clusters.

`site_promise` favours biomes completing the staple set. **No single biome grows wheat +
carrots + potatoes**, so the bot is hunting a *junction*, and it must also weigh ore access,
since M8 depends on it. Breadcrumb every step; the return path is then known-walkable.

### Settling — the M2–M4 block

1. Place and claim a bed — needs a **warmth source within radius 4, height 2**, or rest does
   nothing. `BedRest.isWarm` accepts exactly: **lit campfire** (or soul campfire), `lantern`,
   `soul_lantern`, `magma_block`, `lava`, `lava_cauldron`, `fire`, `soul_fire`.

   **Campfire is the only one reachable early** — 3 sticks + 1 coal/charcoal + 3 logs,
   **ungated**, no class needed. Lanterns need iron (MINER 2); lava and magma need a bucket
   (BLACKSMITH 3). So M2 is really "bed + campfire", and both come out of the same wood the
   bootstrap already gathers.
2. Till farmland **under open sky**; light 15 is required, so the farm cannot be roofed.
3. Build shelter *beside* the farm, not over it — the farm needs open sky, the bot needs cover
   on blood moons.
4. Plant the staple set the site supports.
5. Fence villagers out — they trample farmland here.

## The food rotation

Eating the same item repeatedly decays it: `hunger × 0.7^timesEaten`, where `timesEaten` counts
that item in the **last 11 items eaten**.

```
buffer = ring(11)                      # item ids, most recent last

on eat decision:
    best = argmax over edible items in inventory of
               nutrition(item) × 0.7 ** buffer.count(item)
    if health is low, prefer items that also heal
    eat best; buffer.push(best)
```

This degrades gracefully by construction — with two foods it still picks the less-decayed one.
What changes early on is the *planner's* job.

### Diversity is a staircase, not a slope

Rotating `k` distinct foods evenly, each meal is worth `0.7^floor(11/k)` of face value:

| distinct foods `k` | meal value | |
|---|---|---|
| 1 | 2% | effectively starving |
| 2 | 17% | |
| 3 | 34% | |
| **4** | **49%** | first big target |
| 5 | 49% | **adds nothing** |
| **6** | **70%** | |
| 7 – 11 | 70% | **add nothing** |
| **12** | **100%** | full value |

Two consequences the planner must act on:

1. **Full value needs 12 distinct foods, not 11.** With 11 in rotation, the last time a food was
   eaten is exactly 11 meals back — still inside the window, so it's decayed once. (That's my
   reading of `history-length: 11`; the diet journal in game will confirm it.)
2. **Only the breakpoints are worth chasing: 2, 3, 4, 6, 12.** A 5th food type buys nothing over
   four; the 7th through 11th buy nothing over six. When deciding whether to go hunt down a new
   food type, aim for the *next breakpoint* or don't bother.

### The first few days

The bot will realistically have two or three foods — 17–34% value, so it needs three to six
times the raw food. Survivable, because natural regeneration is off and hunger drains slowly.
But it has real costs: bed rest heals **1 hunger per HP**, so poor diet is poor healing too.

So while `k < 4`, **"acquire a new food type" is a high-priority objective of its own**, ranked
above making more of an existing one. Going from 2 to 4 food types nearly triples the value of
every meal. Cheapest early sources for new types: wild Farm & Charm crops (tomato, lettuce,
carrot, corn, onion — each a separate type), mushroom stew, cooked vs raw of the same meat
(they count as different items), and passive Pokémon drops.

Other rules:

- Saturation is *not* decayed — only the hunger number — so high-saturation foods degrade more
  gracefully.
- Death does not reset the history.

## Combat

Baritone is a pathfinder, not a fighter. So combat is its **own state machine**, and entering it
**pauses Baritone** rather than running alongside it. Baritone resumes the interrupted task once
combat ends.

```
          ┌───────────────── target dead / lost ─────────────────┐
          ▼                                                       │
IDLE ──► ASSESS ──► ENGAGE ──────────────────────────────────────┤
           │          │                                           │
           │          └── health < retreat line, or target ──► RETREAT ──► RECOVER ──► IDLE
           │              stronger than estimated                              (eat, heal,
           └── not worth it ─────────────────────────────────► IDLE              resume task)
```

| state | does |
|---|---|
| `ASSESS` | look the target up in the threat table; compare against own health, gear, food |
| `ENGAGE` | direct control — face, close, attack on full cooldown, strafe. Baritone paused |
| `RETREAT` | break line of sight and walk away. **Sprint is allowed here** — the one case the hunger is worth it |
| `RECOVER` | eat / heal, then hand control back to the objective and resume Baritone |

**When combat starts — and when it must not.** Pacifism means hostile mobs are neutral until
provoked, so the bot only ever enters combat in two cases:

1. **it chose to** — a hunting objective against a passive food mob, or
2. **self-defence** — it took damage from an entity.

It must **never** open on a hostile mob just because one is nearby at night. Worse, the grudge
groups mean hitting one zombie turns *zombie, husk, drowned and zombie villager* hostile, and one
skeleton turns *skeleton, stray and bogged*. Provoking one starts a war with the whole kin group.

### Hunting

From the species data, for the 66 common, level ≤ 10, food-dropping Pokémon:

| behaviour | count | treatment |
|---|---|---|
| passive — neither flees nor defends | **56** | fair game |
| `willDefendSelf` — fights back | **10** | skip early; they almost all drop only rotten flesh or chicken, which passive mobs also give |
| `willFlee` — runs when hit | **0** | — |

**Pidgey and pidove don't flee — they fly.** None of the early food Pokémon have `willFlee`.
What makes the birds hard is that they're airborne as part of normal idle movement, so they're
out of melee reach. The problem is *reach*, not escape.

**So hunt fliers while they sleep.** Most sleep at `light 0–4` — i.e. at night — with a rouse
chance of only 0.0042. A sleeping bird is on the ground and stationary. And since pacifism makes
the night safe to travel, night is exactly when the bot is free to go get them.
**Exception: Hoothoot sleeps by day** (`times: ["day"]`) — it's an owl.

Fast walkers (doduo, rattata ~0.40 walk speed) may drift off but won't flee once engaged.

Hits-to-kill against a given weapon isn't in the data files and needs measuring in game.

**Critical hits.** A hit while falling (jump, then swing on the way down, not sprinting, not in
water) does 1.5× damage. The jump costs 0.05 exhaustion, and the attack 0.1 either way, so a crit is
worth it whenever the target needs two or more normal hits: it saves at least one attack, and one
more round of taking damage. `ENGAGE` should time jump-crits by default, weighed against hunger
when food is short. A *faked* crit (sending movement packets that pretend to fall — the
"Criticals" cheat) is **not built**: on the live server it's cheating, and anticheat flags it.
Only if the server owner explicitly allows it.

### Threat table (roadmap)

As gear improves, the bot should take on stronger targets. That's a lookup, not code: each mob
gets a row (HP, damage, defends, flees, flies, drops), each gear tier a row (damage, armour), and
`ASSESS` compares them. Tables live per mod — see ROADMAP.md.

## The lunar forecast — peer to peer

**No aggregator bot.** Every bot keeps its own copy of every reading *and* every verdict it
hears, and forms its own verdict. Readings (`READ`) and verdicts (`GUESS`) are both shared —
see FOUNDATION.md → Lunar forecast.

```
each bot:
    readings[(uuid, day)] = (moon, confidence, rank)    # own + every READ/AMEND heard
    P(event) = fuse(prior, readings for today)          # computed locally
    act on P                                            # shelter or carry on
```

Why this is better than a server bot:

- **No leader election**, no single point of failure, nothing to hand over when a bot leaves.
- Each bot acts on what *it* actually heard. If the transport has a range limit, two bots may
  hear different readings and reach slightly different conclusions — which is correct; each is
  acting on its own evidence.
- The fusion math is the same everywhere (log-odds, see BOT_DESIGN.md §2.4), so bots that
  heard the same readings reach the same answer without coordinating.

A bot publishes its verdict as `GUESS` and re-publishes each revision as a new step. Anyone can
fetch a bot's readings and verdict history — `/<root> forecast <bot>`, or `!lf readings` /
`!lf guesses` from a player without the mod. Verdicts may feed other verdicts; that echo is
intended game behaviour.

### The dusk routine

| time | action |
|---|---|
| t ≈ 11000 | run `/lunarforecast`, publish own `READ`, nudge the librarian if no reading from them yet |
| t ≈ 12500 | second nudge, only if still nothing — **hard cap of two per day** |
| dusk (13000) | fuse locally, decide: shelter or carry on |

Stop nudging the moment their reading arrives. Never nudge twice for the same day.

If no librarian reading has arrived, the local confidence collapses to ~0.47 — *neither* a call
to prep nor to relax — so **default to preparing**.

---

## Bus messages

One transport, swappable. **Built (2026-09-30): a local bus** between bots on the same PC, signed and
encrypted with the team key; messages so far `HELLO` (with role), `STATUS`, `STATUS_LINE`. The list
below is the plan; only raw facts go on it.

```
HELLO  <uuid> <name> <pos>                  # discovery
ROSTER <uuid,...> <n>                       # agreed group
READ   <uuid> <moon> <conf> <rank> <day>    # a forecast reading
AMEND  <uuid> <moon> <conf> <rank> <day>    # supersedes that uuid's last READ
CLAIM  <uuid> <x> <z> <radius> <what>       # scouting claim, or a farm/bed site in progress
DONE   <uuid> <x> <z> <result>              # claim released
GUESS  <uuid> <day> <event> <p> <step>      # a bot's verdict; each revision a new step
ANSWERED <msg-id>                           # I answered that public request — others stay quiet
```

Readings and verdicts are both on the bus; there is no single group verdict. Reading state is keyed `(uuid, day)`; `AMEND` replaces, never appends,
so a player re-running the command can't double-count themselves.

`CLAIM` doubles as the in-progress signal for milestones — a bot building a farm claims the
site, which is exactly the evidence another bot's survey needs to avoid starting a second one.

### Silent mode

**`/bot silent on`** stops the bot sending anything on the bus. It still *listens* — readings
from others still feed its local fusion — but it posts nothing: no `READ`, no `CLAIM`, and no
librarian nudges, since those are chat messages too.

Costs of running silent, worth knowing:

- other bots lose this bot's reading, so their fusion gets weaker
- other bots can't see its `CLAIM`s, so they may start a duplicate farm or scout the same ground
- a silent bot that relies on a librarian gets no nudge sent — so its own forecast degrades on
  nights the librarian forgets

---

## Commands

**Superseded:** the real commands are `/zbot …` (root configurable), listed in SKILL.md and
PHASE1.md. `/zbot role` is bot / teammate / none, not classes — classes get their own command
(PHASE3 decision A). The table below is the original plan, kept for the ideas not built yet
(librarian, forecast, silent, defer).

| command | effect |
|---|---|
| `/bot start` | two-phase discovery then run |
| `/bot stop` | hard stop; never auto-resumes |
| `/bot leash <blocks>` | set follow distance |
| `/bot role <class>,<class>` | override class assignment |
| `/bot librarian <name>` | seed the forecaster identity |
| `/bot status` | current objective, milestone states, hunger, return budget, roster |
| `/<root> forecast [bot]` | readings table + verdict history (steps) for this bot or another |
| `/bot silent on\|off` | stop / resume sending on the bus; still listens |
| `/bot defer <0..1>` | how much weight human instructions get against the bot's own plan |

---

## Tunables

Nothing below is settled — these are the dials playtesting is meant to set.

| knob | starting value | notes |
|---|---|---|
| leash range | ? | must be **tighter** than transport range, or bots lose contact |
| resume countdown | ? | after WASD release |
| eat threshold | ? | hunger level that triggers interrupt 5 |
| return safety factor | 2.0 | multiplier on the walk-home budget |
| `LOW` / `HIGH` forecast thresholds | 0.25 / 0.70 | below LOW = relax, above HIGH = prep, between = prep anyway |
| deference | ? | human instructions vs own plan; 1.0 = obey |
| retreat health line | ? | combat → RETREAT below this |
| role-change answer window | ? | how long to wait for a human/bot reply before choosing alone |
| min viable site score | **undefined** | the one to find by playing |
| `FARM_SUSTAINS` surplus margin | **undefined** | production vs consumption — same playtest question |
| re-assess interval | ? | plus forced re-assess on milestone flip / roster change |
| render distance | ? | this is *perception radius*, not just a memory dial |

**Persist across restarts:** roster, player→class map, base/farm/bed coords, explored-chunk
grid, breadcrumbs, food-history buffer. All of it is a *hypothesis* — re-verify against the
world on load, because a farm in the save file that no longer exists means M4 is unmet again.

**Log every settle/explore decision** — site, biome mix, coverage, distance, hunger spent, and
whether the farm actually sustained the rotation. That's what defines "minimum sustainable"
empirically instead of by argument.
