# Phase 3 — First milestones: gather, craft, feed itself

Status: **scoping started 2026-09-26** — draft for the owner to decide the open questions (§7).
Builds on [PHASE2.md](PHASE2.md) (regroup, roster, follow — live-tested 0.1.29–0.1.34) and the
spec in [BOT_BEHAVIOUR.md](BOT_BEHAVIOUR.md) (interrupt table, milestone ladder, bootstrap, food
rotation) with the facts in [SURVIVAL_EARLY_GAME.md](SURVIVAL_EARLY_GAME.md).

**Goal:** a bot that respawns with empty pockets (seen all through the Phase 2 tests: `failed: eat
— no safe food` every 30 s) gets itself **fed and tooled** without a human giving it bread: it
surveys what's around, punches wood, crafts the classless toolkit, forages wild food, and keeps a
rotation going. This is the **planner's first real ladder** — M0 `HAS_TOOL` and M1 `HAS_FOOD` —
with regroup/follow (Phase 2) still sitting above it.

Out of scope here (Phase 4+): bed + campfire (M2), shelter (M3), farming (M4–M5), classes and
class XP, mining, combat beyond today's retreat, the lunar forecast.

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

## 7. Open questions for the owner

1. **Scope cut:** M0 + M1 only (this draft), or also M2 (bed + campfire — needs coal/charcoal →
   furnace → more crafting)?
2. **Classes:** stay classless through Phase 3 (crafting stays free), or have the bot pick
   FARMER + MINER (BOT_BEHAVIOUR's test profile) at M0 so ranks start accruing? Taking a class makes
   every craft cost hunger.
3. **Crafting method:** click slots in the real container screens (works everywhere, slower, more
   code), or use the recipe book "place recipe" (fast, but needs the recipe unlocked/known)?
4. **Tree felling:** punch only the logs it can reach from the ground (no pillaring, no block
   placing), accepting tall trees are partly wasted — or allow a 1-block pillar?
5. **Work radius** 48 around the teammate — OK? And should foraging pause while the teammate is
   moving (i.e. only work while they're settled somewhere)?
6. **Leftovers from Phase 2 to fold in first:** reviver-as-attacker nit, `revived` log line,
   Baritone chunk cache (long regroups), `/msg` probe (#3). Which before Phase 3 starts?

## 8. Proposed build steps (each compiled, tested, committed)

1. Survey (§2) + `/zbot survey` — read-only, safe first step.
2. Hands: **break + collect** (a `BreakTask`), tested on "punch 3 logs" via a debug order
   (`/zbot punch <x> <y> <z>`).
3. Hands: **craft (2×2)** planks/sticks/table, then **place** the table, then **craft (3×3)** tools.
4. Planner: the ladder as data (`id, check, owner, min_players`), M0 wired to steps 2–3.
5. Forage: wild-crop targets from the survey, M1 floor, the "new food type" priority.
6. CLAIMs on the bus.
7. Live test (§6), update SKILL.md's command table for every new `/zbot` command.
