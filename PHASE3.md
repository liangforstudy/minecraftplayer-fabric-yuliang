# Phase 3 — First milestones: gather, craft, feed itself

Status: **scoping 2026-09-26** — owner answered Q1–Q5 (§7); open: Q6 (Phase 2 leftovers first?), taking a bed from an inhabited village, and village trips beyond the 48-block work radius.
Builds on [PHASE2.md](PHASE2.md) (regroup, roster, follow — live-tested 0.1.29–0.1.34) and the
spec in [BOT_BEHAVIOUR.md](BOT_BEHAVIOUR.md) (interrupt table, milestone ladder, bootstrap, food
rotation) with the facts in [SURVIVAL_EARLY_GAME.md](SURVIVAL_EARLY_GAME.md).

**Goal:** a bot that respawns with empty pockets (seen all through the Phase 2 tests: `failed: eat
— no safe food` every 30 s) gets itself **fed and tooled** without a human giving it bread: it
surveys what's around, punches wood, crafts the classless toolkit, forages wild food, and keeps a
rotation going. This is the **planner's first real ladder** — M0 `HAS_TOOL` and M1 `HAS_FOOD` —
with regroup/follow (Phase 2) still sitting above it.

In scope (owner, 2026-09-26): M0 tool, M1 food, M2 bed + campfire (bed found in a village/structure). Out of scope (Phase 4+): shelter (M3), farming (M4–M5), class XP grinding and
ore mining, hunting and combat beyond today's retreat, the lunar forecast.

---

## 1. What's new underneath — the bot gets hands

Everything so far only *walks* and *eats*. Baritone runs with `allowBreak/allowPlace/allowInventory
= false`. Phase 3 needs four new abilities, each a small adapter behind the core interfaces
(ROADMAP: Minecraft stays out of `core/`):

| ability | what | notes |
|---|---|---|
| **break** | mine one chosen block by hand/tool (hold attack, look at it) | Zymbot picks *which* block (a log, a wild crop); not Baritone's free-for-all `allowBreak` |
| **collect** | walk over dropped items to pick them up | drops scatter; a short "sweep the drops" walk after each break |
| **craft** | 2×2 inventory grid and 3×3 crafting table, by recipe id | via the container screen (click slots), or the recipe book's "place recipe" packet — see Q3 |
| **place** | put one block (the crafting table) on a free spot next to it | only for the table in this phase |

civfabric rules that apply to all four (SURVIVAL_EARLY_GAME §4a): crafting **costs hunger and is
refused when too hungry**; while classless the classless set is free (planks, sticks, table,
torch, furnace, chest, barrel). The bot must read "craft refused" as a signal (eat first), not a bug.

## 2. Survey — carried over from Phase 2 §5, now acted on

On start and after a respawn, one bounded scan (background, like the route planner):

| scope | reads |
|---|---|
| self | inventory summary, health, hunger, food carried + live (decayed) values |
| local | logs/trees, wild crops (Farm & Charm `wild_*`, FD wild patches, mushroom colonies), crafting tables, chests, beds, campfires |
| world | biome, time, day, distance to spawn |

`/zbot survey` prints it. Unlike Phase 2 it **feeds the planner**: nearest tree, nearest wild food.

## 3. The ladder, first two rungs

The planner (BOT_BEHAVIOUR → "Choosing what to do") takes the lowest unmet rung. Regroup and the
leash/follow stay **above** it: a bot far from its team regroups first; a bot with its team works
the ladder within the leash.

### M0 `HAS_TOOL` — the classless bootstrap

Straight from BOT_BEHAVIOUR → Bootstrap, minus farming (the hoe is the goal, planting is Phase 4):

| step | action | met when |
|---|---|---|
| 1 | walk to the nearest tree, punch **≥ 3 logs** (with Treecapitator an axe later makes it per-tree) | logs in inventory |
| 2 | logs → planks (2×2) | planks |
| 3 | planks → **crafting table** (2×2), place it | table within reach |
| 4 | planks → sticks | sticks |
| 5 | **wooden axe**, then **wooden hoe** (3×3, at the table) | both in inventory |

A table the survey already found counts (don't place a second one next to it — BOT_BEHAVIOUR's
"three states": IN_PROGRESS beats UNMET).

### M1 `HAS_FOOD` — forage and rotate

- **Floor:** food buffer ≥ `food_floor` hunger-points *after decay* (tunable; proposal 20 = one full
  bar) and ≥ 2 distinct foods.
- **Forage order** (SURVIVAL_EARLY_GAME §2.4): Farm & Charm `wild_*` patches (2–5 food each, no
  tool) → mushroom colonies (3 per block; stew needs a bowl = planks, so after M0) → FD wild patches
  in passing (seeds kept for Phase 4) → apples from leaves.
- **Rotation:** already built (`EatInterrupt` picks the best live, Spice-decayed value). New: while
  `k < 4` distinct foods, "acquire a new food type" outranks "more of one I have" (the staircase:
  2 → 17%, 3 → 34%, 4 → 49%, 6 → 70%, 12 → 100%).
- **Never eat** the hazard list (rotten flesh, poisonous potato, pufferfish, spider eye, raw
  chicken, dried kelp here) — the current `never_eat` config, extended from `healing_and_hazards.json`.

## 4. Where it works — radius and the team

- Forage/wood search radius: `work_radius` (proposal 48) around the **nearest teammate**, not the
  bot — so it stays useful to the group and the leash (21) rarely has to pull it back. With no
  teammate known, around itself.
- The leash already pulls a *working* bot back at leash/2 (Phase 1 behaviour, now live).

## 5. Bus and team

- `CLAIM tree x z` / `CLAIM patch x z` for 60 s so two bots don't punch the same tree or strip the
  same patch (BOT_BEHAVIOUR "live CLAIM"). With one bot it's a no-op, but cheap to add now.
- `/zbot status` shows the rung: `ladder: M0 HAS_TOOL — step 3/5 crafting table (because no tool,
  2 logs)`.

## 6. Test plan (live, like Phase 2 §8)

| # | check |
|---|---|
| 1 | cold start, empty inventory, trees in sight → logs → planks → table placed → axe → hoe, every step a `[decision]` line |
| 2 | a table already nearby → uses it, doesn't place another |
| 3 | hungry with wild tomatoes in range → forages, eats, keeps the rest |
| 4 | only one food type → goes for a second before stockpiling the first |
| 5 | craft refused for hunger → eats, retries |
| 6 | owner walks 30 blocks off mid-task → leash pulls it back, task resumes |
| 7 | knocked out / respawn mid-task → the task is dropped, ladder re-assessed after the respawn |
| 8 | two bots (Bot3 synced) → CLAIMs keep them off each other's tree |

## 7. Decisions (owner, 2026-09-26) and what's still open

| # | question | decision |
|---|---|---|
| 1 | scope: M2 too? | **Owner: yes — the bed comes from villages / structures.** Campfire half as below; the bed isn't crafted (no wool without hunting or shears) but **found**: a village bed or one in a structure, broken (it drops as an item) and placed next to its campfire, then claimed (`BedClaims.OWNED_BED`, one per player). Villages also carry food chests and crop plots (SURVIVAL_EARLY_GAME §4–5), so finding one pays M1 too. This pulls in a **village/structure search** — the frontier search from BOT_BEHAVIOUR → Site search, scoped down to "find the nearest village", leashed to the team. **Steal beds from villages first** (owner) — inhabited or not; structures after. *Original analysis:* Campfire half of M2 is *not* blocked: campfire = 3 sticks + 3 logs + 1 charcoal, all classless; charcoal = a log smelted in a furnace; furnace = 8 cobblestone (classless craft, 1.25 hunger); cobblestone needs a wooden pickaxe, and plain stone isn't MINER-gated (only ores are, BOT_DESIGN §2.17). The furnace also cooks food → more food types for M1. **Bed is blocked upstream:** 3 wool = killing sheep (hunting/attack — the combat state machine, not built) or shears (**BLACKSMITH 3**); beds are claimed one per player (`BedClaims.OWNED_BED`). **Recommendation:** Phase 3 = M0 + M1 + campfire/furnace/charcoal/cooking; the bed waits for hunting, or for wool handed over by a human. |
| 2 | classes | **Pick FARMER + MINER** (BOT_BEHAVIOUR test profile) so ranks start accruing. Consequence: every craft costs hunger (civfabric), so crafting is planned around the eat threshold and "craft refused — too hungry" means eat first. How a class is chosen (a `/classes` GUI click? a command?) — to find in the civfabric jar before step 3. |
| 3 | crafting method | **Depends on the anticheat:** the real container screens, the way a human does it (recipe-book click in the screen where allowed, paced like a human), with **manual slot clicks as the fallback**. Never raw packets the vanilla client wouldn't send. The live server's anticheat already rules out crit tricks (BOT_BEHAVIOUR → Combat) — check what it says about inventory click speed. |
| 4 | trees | **Fell the entire tree.** Skip huge trees (2×2 dark oak / jungle / mega spruce) unless no other tree is nearby — weighed by hunger and time cost. With a wooden axe, zymlabs Treecapitator fells the whole tree from one log (radius 16) — so craft the axe first and most trees become a single break. **Big oaks** (branching, logs out of reach) need **towering**: climb on **what the world already has first** — leaves and the tree's own wood as footing, no placing — and only where that runs out, pillar up on scaffold blocks it carries (jump-place under itself). Scaffold preference (owner): **dirt / sand / gravel first** — quick to break bare-handed, so tidy-up is cheap — **then cobblestone** (slower to break, needs the pickaxe to pick back up), clear the high logs, then **tidy up: break its own scaffold on the way down** and pick the blocks back up. Remember every block it placed (a per-task list) so it removes exactly those and never a block that was already there. Needs `place` beyond the crafting table, and the fall rule (`maxFallHeightNoWater = 3`) respected on the way down. |
| 5 | work radius | **48 around the nearest teammate — OK.** |
| 6 | Phase 2 leftovers first? | *still open* — reviver-as-attacker nit, `revived` log line, Baritone chunk cache (long regroups), `/msg` probe (#3). |

## 8. Proposed build steps (each compiled, tested, committed)

1. Survey (§2) + `/zbot survey` — read-only, safe first step. **Built** (core/survey: `Survey`, `Surveyor`, `SurveyCatalog`).
2. Hands: **break + collect** (a `BreakTask`), tested on "punch 3 logs" via a debug order
   (`/zbot punch <x> <y> <z>`).
3. Hands: **craft (2×2)** planks/sticks/table, then **place** the table, then **craft (3×3)** tools.
4. Planner: the ladder as data (`id, check, owner, min_players`), M0 wired to steps 2–3.
5. Forage: wild-crop targets from the survey, M1 floor, the "new food type" priority.
6. M2: wooden pickaxe → cobblestone → furnace → charcoal → campfire; cooking. Village/structure search (leash question: a village farther than 48 blocks from the teammate — go only with the team, or ask?), take a bed, place it by the campfire, claim it.
7. Towering + scaffold tidy-up for big oaks.
8. CLAIMs on the bus.
9. Live test (§6), update SKILL.md's command table for every new `/zbot` command.

## 9. Research findings (2026-09-27, read from civfabric-0.4.2 bytecode and the pack's jars)

**Classes aren't chosen — they're earned.** civfabric stores only XP per class; rank comes from XP,
and a player's "specialisations" are simply their **top N classes by XP** (N = `specialisationSlots`,
default 2 — the live server may override). There's no join action, no cost, no cooldown; the
`/classes` GUI is read-only (clicks are reverted). The catch: once N classes each reach ~85 % of rank 1
the **drain arms** and every award drains the off-classes — so locking in is effectively one-way.
→ "Pick FARMER + MINER" (owner, Q2) means **earn FARMER and MINER XP first**, before anything else
arms the drain: harvesting crops (FARMER) and mining (MINER). Chopping wood / crafting may award
other classes (unverified which) — watch `/classes` in the live test.

**Classless crafting is free only for tagged items**, not everything: `CraftingHunger` waives the cost
while classless (all ranks 0) *and* the item carries one of a set of tags (list not yet extracted).
Otherwise cost = `craft_hunger.json` (crafting table 1.0, chest 1.25, …) or a shape × material formula
(wooden pickaxe ×0.35 …), scaled by rank; "You're too hungry to craft" refuses it.

**No anticheat in the client pack.** Nothing in the mods (civfabric / zymlabs-rules police gameplay,
not behaviour). The "Criticals is flagged" note and hidden F3 coords come from the **live server
only** — its limits (click speed, break speed, rotations, recipe-book packets) can't be read from here.
Defaults until the owner confirms: only packets the vanilla client would send, slot clicks paced at
human speed (≥ 50–150 ms apart), smooth rotations, never faster than vanilla break progress.
