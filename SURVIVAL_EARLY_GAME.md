# Early-Game Food Survival — 1.21.1 ZymCivModded Server (Minimal)

Behaviour reference for an AI character starting with **an empty inventory** and no base.
Everything here was extracted from the actual instance at
`~/Library/Application Support/PrismLauncher/instances/1.21.1 ZymCivModded Server Minimal`
(84 mods) — jar data files, jar bytecode, and the **live server config files**.

Machine-readable companions live in `survival-data/`:

| file | contents |
|---|---|
| `food_values.json` | 306 edible items: hunger, saturation modifier, total saturation, effects, source mod |
| `wild_forage.json` | 44 wild-generating plant features: rarity, biome tag, exact drop table |
| `forage_yield_table.json` | forage flattened to one row per (plant → item), ranked by hunger |
| `cobblemon_food_drops.json` | 190 Pokémon that drop food, with base stats, spawn level, bucket, biomes |
| `structure_loot_food.json` | 112 chest loot tables containing food or seeds, with per-item weights |
| `server_rules.json` | modified hunger/farming rules, forced gamerules, tool facts, lunar events, biome growth tables, villager trades |
| `healing_and_hazards.json` | the 11 foods that restore health, and the 17 with negative effects |
| `spec_classes.json` | the 7-class system: harvest-success curves, recipe gates, crafting/movement hunger, medic healing |
| `civfabric/` | the raw civfabric config resources these were read from |

---

## 1. The two rules that change everything

Before any food logic, the agent must encode these. They are **not vanilla behaviour** and
they invalidate the usual "cook a stack of porkchops and forget about food" strategy.

### 1.1 Spice of Fabric — repeat food decays

Active on this server, from `config/spiceoffabric.hjson`:

```
hunger           = hungerValue * 0.7 ^ timesEaten
consume-duration = consumeDuration * 1.3 ^ timesEaten
saturation       = unchanged
history-length   = 11
```

`timesEaten` = how many of the **last 11 items you ate** were this same item.

| times eaten in last 11 | hunger multiplier |
|---|---|
| 0 | 1.00 |
| 1 | 0.70 |
| 2 | 0.49 |
| 3 | 0.34 |
| 4 | 0.24 |
| 5 | 0.17 |
| 6 | 0.12 |

**Consequences for the agent:**

- Maintain a rolling buffer of the last 11 eaten item IDs. Before eating, compute
  `effective_hunger = nutrition * 0.7 ** count_in_buffer(item)` and pick the highest.
- **Diet breadth is worth more than food quality.** Eleven different 3-hunger vegetables
  beat one stack of 8-hunger steak. A forager with 12 rotating crops never loses value.
- Eating the same food repeatedly also gets *slower* (`1.3^n`), which matters when eating
  under threat.
- Carrot mode is **off**, so max health is unaffected. Saturation modifier is **not** decayed —
  only the hunger number is, so high-saturation foods degrade more gracefully.
- On death: hunger resets to `max(8, hunger_at_death)` and the **food history is kept**
  (`reset-history: false`). Dying does not reset your diet penalties.

### 1.2 Realistic Plant Growth — farming is location-locked

From `config/realisticplantgrowth/`:

- **`min_natural_light: 15`** — player-planted crops need *full natural skylight*.
  Torch-lit underground or roofed farms **do not grow at all**. Farms must be open to the sky.
- **`only_player_planted: true`** — the modifiers apply to crops you plant. Naturally
  generated / village crops are not governed by them.
- **`bonemeal_limit: 3`** per plant, and `bonemeal_respects_biome: true` — bonemeal will not
  rescue a crop planted in a bad biome.
- **`soil_rotation_days: 5`** — the same soil planted with the same crop degrades; rotate crops.
- `destroy_farmland: false` for players (safe to walk on), but **`true` for villagers** — villagers
  will trample a farm you build next to them.
- Exempt from the light rule (grow in the dark): `RED_MUSHROOM`, `BROWN_MUSHROOM`, `COCOA`,
  `CAVE_VINES`, `KELP`, `NETHER_WART`, `GLOW_LICHEN`, and the nether fungi.

**Biome is a hard gate.** A crop with no matching biome group and an empty default list has
growth rate 0 — it will never mature. Pick the settle biome from the crop you intend to farm:

| crop | 100% growth in | reduced | will not grow elsewhere |
|---|---|---|---|
| Wheat | PLAINS, SUNFLOWER_PLAINS, MEADOW | Savanna 50%, Tropical 50% | yes |
| Carrots | BIRCH_FOREST, DARK_FOREST, FLOWER_FOREST, FOREST, OLD_GROWTH_BIRCH_FOREST | Chilly 50% | yes |
| Potatoes | STONY_PEAKS, JAGGED_PEAKS, STONY_SHORE, all Windy, all Arid | — | yes |
| Beetroots | Chilly group 100% | CHERRY_GROVE 75%, Frozen 75% | yes |
| Sweet berry bush | Chilly 100% | Frozen 80%, Temperate 60%, Windswept 25% | — |
| Pumpkin | Chilly 100% | Temperate 85%, Frozen 70% | yes |
| Melon | Tropical 100% | Temperate 25% | yes |
| Brown/red mushroom | Chilly, Tropical, Caves, DARK_FOREST, MUSHROOM_FIELDS — all 100% | — | — |
| Cocoa | JUNGLE, BAMBOO_JUNGLE, SPARSE_JUNGLE | — | yes |

`NaturalDeathChance` is the per-tick chance the plant dies; beetroots outside Chilly
(3.45%) and pumpkins in Frozen (5.97%) are notably lossy. Full group→biome mapping and
death chances are in `server_rules.json`.

**The server's own biome tags confirm the design.** The world datapack overrides the wild-crop
biome whitelists (`replace: true`), and they line up exactly with the growth table: wild
carrots only spawn in FOREST-family biomes (where carrots farm at 100%), wild potatoes only in
WINDSWEPT biomes (where potatoes farm at 100%), wild barley in PLAINS/SAVANNA, wild
tomatoes+corn in PLAINS/SAVANNA/BADLANDS, wild strawberries in MEADOW/forests/taigas.

**So: finding a wild patch tells you where to farm that crop.** That is the cheapest possible
biome survey and the agent should use it directly — no biome table lookup needed, just
remember where the wild version grew.

**Best all-round settle target: PLAINS / SUNFLOWER_PLAINS / MEADOW** — wheat at 100% with
**0% death chance**, and they are Temperate so pumpkin (85%) and sweet berries (60%) also work.
A FOREST/BIRCH_FOREST border gives carrots at 100% as well.


### 1.3 Natural regeneration is OFF

`zymlabs-rules` forces the gamerule **`naturalRegeneration = false`** (and `forgiveDeadPlayers = true`).

**You never heal from a full hunger bar.** Health comes back only from:

| item | hunger | effect | notes |
|---|---|---|---|
| `farm_and_charm:nettle_tea_cup` | 1 | **Instant Health** | wild nettle is forageable, 1/34 chunks |
| `farm_and_charm:ribwort_tea_cup` | 1 | Regeneration 3s | wild ribwort forageable, 1/41 chunks |
| `farmersdelight:fruit_salad` | 6 | Regeneration I, 5s | fruit + bowl — the cheapest real heal |
| `farmersdelight:mixed_salad` | 6 | Regeneration I, 5s | vegetables + bowl |
| `civfabric:hearty_soup` | 6 | Regeneration **II**, 5s | |
| `minecraft:golden_apple` | 4 | Regen II 5s + Absorption 2m | |
| `cobblemon:vivichoke_dip` | 10 | Absorption 45s | always edible |
| `farmersdelight:apple_cider` | — | Absorption 60s | always edible |

Two of these — **nettle and ribwort — grow wild and drop at 100%**. An agent that maps nettle
and ribwort patches has a renewable healing supply from phase 1, which matters enormously
when there is no passive regen.

Second-order effect: with natural regen disabled, the **6.0 exhaustion per healed point never
fires**, so hunger drains far more slowly than vanilla. Food pressure is lower than you'd
expect; *health* pressure is much higher.

### 1.4 Hazards to avoid eating

`minecraft:rotten_flesh` (80% Hunger), `minecraft:poisonous_potato` (60% Poison),
`farm_and_charm:rotten_tomato` and `vinery:rotten_cherry` (60% Poison),
`minecraft:pufferfish` (Poison II + Hunger III + Nausea), `minecraft:spider_eye` (Poison),
and **`minecraft:dried_kelp`** — the server adds a **10% chance of Poison II for 6s**
(`KELP_POISON_PERCENT = 10`). Kelp is not a safe staple here.

Full list in `healing_and_hazards.json`.

---

## 2. Phase 1 — first minutes, empty inventory

Forage on foot. All of these break **by hand, no tool, no cooking**.

### 2.1 Highest-value wild plants (instant edible food)

`letsdo` Farm & Charm wild crops are the single best early food source in this pack: they drop
**2–5 food items at 100%**, not seeds.

| plant | drops | hunger each | frequency |
|---|---|---|---|
| `farm_and_charm:wild_tomatoes` | `farm_and_charm:tomato` ×2–5 | 4 | 1 per 45 chunks |
| `farm_and_charm:wild_carrots` | `minecraft:carrot` ×2–5 | 3 | 1 per 44 chunks |
| `farm_and_charm:wild_lettuce` | `farm_and_charm:lettuce` ×2–5 | 3 | 1 per 47 chunks |
| `farm_and_charm:wild_onions` | `farm_and_charm:onion` ×2–5 | 2 | 1 per 48 chunks |
| `farm_and_charm:wild_corn` | `farm_and_charm:corn` ×2–5 | 2 | 1 per 39 chunks |
| `farm_and_charm:wild_emmer` | `minecraft:wheat` ×2–5 | — (craft to bread) | 1 per 36 chunks |
| `farm_and_charm:wild_beetroots` | `minecraft:beetroot` ×2–5 | 1 | 1 per 39 chunks |
| `farm_and_charm:wild_potatoes` | `minecraft:potato` ×2–5 | 1 (5 baked) | 1 per 33 chunks |
| `farm_and_charm:wild_strawberries` | `farm_and_charm:strawberry` ×2–5 | 1 | 1 per 47 chunks |
| `farm_and_charm:wild_barley` / `wild_oat` | `barley` / `oat` ×2–5 | cooking input | 1 per 40 / 44 |

Each also drops its **seed at ~12%** (with fortune scaling), which is your farm starter.

### 2.2 Farmer's Delight wild crops — seeds first, food sometimes

FD wild patches give a **guaranteed seed** and only a **20% chance of the vegetable**:

| plant | food (20%) | seed (100%) | frequency |
|---|---|---|---|
| `farmersdelight:wild_cabbages` | `cabbage` (2 hunger) | `cabbage_seeds` | 1/30 |
| `farmersdelight:wild_tomatoes` | `tomato` (1) | `tomato_seeds` | 1/100 |
| `farmersdelight:wild_onions` | `onion` (2) + always 1 `minecraft:allium` | — | 1/120 |
| `farmersdelight:wild_carrots` | — | `minecraft:carrot` (fortune) | 1/120 |
| `farmersdelight:wild_potatoes` | — | `minecraft:potato` (fortune) | 1/100 |
| `farmersdelight:wild_beetroots` | `minecraft:beetroot` (20%) | `beetroot_seeds` | 1/30 |
| `farmersdelight:wild_rice` | `farmersdelight:rice` ×2 at 100% | — | 1/20 |
| `farmersdelight:*_mushroom_colony` | 3× `brown`/`red_mushroom` at 100% | — | 1/15 |

**Mushroom colonies (1 per 15 chunks) are the densest guaranteed drop in the pack** — three
mushrooms per block, and two mushrooms + a bowl is `minecraft:mushroom_stew` at
**6 hunger / 0.6 saturation**. Mushrooms also ignore the light rule, so they are the only
crop farmable underground.

### 2.3 Other wild edibles

| plant | yield | hunger | frequency |
|---|---|---|---|
| `ubesdelight:wild_ube` | `ube` (shears/fortune) | 2 | 1/50 |
| `ubesdelight:wild_garlic` / `ginger` / `lemongrass` | matching item | 2 each | 1/80 |
| `vinery:*_grape_bush` | grape seeds 100% | grapes 2 (jungle grapes **5**) | 1/10 jungle, 1/22 else |
| `cobblemon:mints` (red/blue/etc.) | mint leaf + seeds | leaf is pokémon food, not player food | 1/12 |
| `cobblemon:revival_herb` | revival herb, pep-up flower, mental/mirror/power/white herb | not player food | rare |
| `brewery:wild_hops` | `hops` ×2–5 | brewing input | very common |
| `herbalbrews:hibiscus` / `lavender` | `tea_blossom` | 1, always edible | 1/33 |

**Note:** Cobblemon **berries (oran, pecha, sitrus, …) are not player food** — they have no
food component. They are for healing Pokémon and for berry-tree farming. Don't route hunger
through them.

### 2.4 Phase-1 decision rule

```
1. Sweep for farm_and_charm wild_* patches   → eat immediately, keep seeds
2. Sweep for mushroom colonies               → stockpile; craft bowl → mushroom_stew (6/0.6)
3. Pick up FD wild patches in passing        → seeds are the real prize
4. Punch grass / leaves for wheat & apple seeds as filler
5. Never eat the same item twice in a row while any alternative is in inventory
```

---

## 3. Phase 2 — protein from passive mobs and Cobblemon

Wild Pokémon in this pack drop vanilla meat and can be killed with bare hands or a wooden
tool; they do not aggro like hostile mobs. Prefer **low base HP, non-flying, common bucket,
level 1–5** targets. Full table with biomes in `cobblemon_food_drops.json`.

### 3.1 Best early kills (common spawn, level ≤ 10, sorted by base HP)

| Pokémon | min lvl | base HP | flying | food drop |
|---|---|---|---|---|
| `lechonk` | 1 | 54 | no | `porkchop` ×1–2 → **cooked 8 hunger** |
| `swinub` | 1 | 50 | no | `porkchop` ×1 |
| `wooloo` | 2 | 42 | no | `mutton` ×1–2 → cooked 6 |
| `mareep` | 3 | 55 | no | `mutton` ×1–2 |
| `skiddo` | 10 | 66 | no | `mutton` ×1–2 |
| `bunnelby` | 1 | 38 | no | `rabbit` ×1 + 2.5% `carrot` |
| `buneary` | 10 | 55 | no | `rabbit` ×1 + 2.5% `carrot` |
| `magikarp` / `feebas` | 1 | **20** | no | `salmon` ×1 → cooked 6 |
| `arrokuda` | 3 | 41 | no | `salmon` ×1 |
| `wishiwashi` | 1 | 45 | no | `cod` ×1 |
| `remoraid` | 5 | 35 | no | `cod` ×1 |
| `rookidee`, `pidgey`, `spearow`, `starly`, `taillow`, `pikipek` | 1–2 | 35–40 | **yes** | `chicken` ×1 → cooked 6 |
| `hoothoot` | 1 | 60 | yes | `chicken` ×1 |
| `doduo` | 6 | 35 | no | `chicken` ×1 |
| `exeggcute` | 8 | 60 | no | `egg` ×0–3 |
| `combee` | 1 | 30 | yes | `honey_bottle` + `honeycomb` ×0–1 |
| `cutiefly` | 5 | 40 | yes | `honey_bottle` ×0–1 |
| `vulpix`, `nickit` | 1–5 | 38–40 | no | `sweet_berries` ×1–3 |
| `teddiursa` | 8 | 60 | no | `sweet_berries` ×1–3 + 2.5% `honey_bottle` |
| `paras`, `morelull`, `shroomish`, `foongus`, `toedscool` | 4–9 | 35–69 | no | `red`/`brown_mushroom` |
| `milcery`, `swirlix` | 2–9 | 45–62 | some | `sugar` ×0–1 |
| `fidough` | 6 | 37 | no | `bread` ×0–1 (5 hunger, no cooking) |
| `capsakid` | 5 | 50 | no | `cobblemon:roasted_leek` ×0–1 |
| `slowpoke` | 7 | 90 | no | `cobblemon:tasty_tail` ×0–1 (3/0.3) |

**Targeting heuristics:**
- `magikarp` and `feebas` at **20 base HP** are the cheapest kills in the game and give salmon.
  Any river or ocean edge is a reliable protein line.
- `lechonk` is the best hunger-per-kill early target on land (porkchop ×1–2, cooks to 8).
- Skip flying species until you have a ranged option; they cost far more time per kill.
- `poochyena`, `rattata`, `purrloin`, `electrike`, `houndour`, `maschiff`, `joltik`, `spinarak`
  drop only `rotten_flesh` — **4 hunger but 80% chance of Hunger effect**. Emergency food only.
- Avoid anything labelled legendary/mythical/ultra_beast/paradox (filtered out of the table above).

### 3.2 Vanilla passive mobs

Unchanged: cow/pig/sheep/chicken/rabbit. Cooked beef and cooked porkchop remain the highest
raw numbers in the pack at **8 hunger / 0.8 saturation** — but under Spice of Fabric a steak
stack is a trap. Use them as the *anchor* of a rotation, not the whole diet.

---

## 4. Phase 3 — structures and chests

`lootr` is installed, so structure chests are **per-player instanced** — every player gets
their own roll, and chests do not deplete for you. Prioritise structures accordingly.
`zymlabs-rules` adds a `LootrLock` rule; expect some chests to be access-gated.

### 4.1 Best food chests

| structure | food worth the detour |
|---|---|
| `village/village_butcher` | `porkchop`/`beef`/`mutton` ×1–3 each (21% each) + FD `minced_beef`, `bacon`, `mutton_chops` ×2–6 (25% each) |
| `village/village_plains_house` (+ ctov variants) | `potato` ×1–7, `bread` ×1–4, `apple` ×1–5 (~23% each) |
| `village/village_taiga_house` | `potato` ×1–7, `sweet_berries` ×1–7, `bread`, `pumpkin_pie` |
| `village/village_snowy_house` | `potato` ×1–7, `bread`, `beetroot_seeds`, `beetroot_soup` |
| `ctov:village_farm` | `wheat`/`carrot`/`potato`/`beetroot` ×1–3 (22% each) |
| `ctov:village_mushroom_house` | `potato`, `bread`, brown + red mushrooms |
| `pillager_outpost` | `wheat` ×3–5, `potato` ×2–5, `carrot` ×3–5, plus FD `onion` ×4–12 |
| `shipwreck_supply` | `wheat` ×8–21, `carrot` ×4–8, `potato` ×2–6, `suspicious_stew`, FD seeds ×2–4 |
| `igloo_chest` | `apple` ×1–3 (23%), `wheat` ×2–3, `golden_apple` |
| `simple_dungeon` / `woodland_mansion` | `bread`, `wheat`, melon/pumpkin/beetroot seeds, `golden_apple` 5% |
| `stronghold_corridor` / `crossing` | `bread` ×1–3 and `apple` ×1–3 at 15–24% |
| `abandoned_mineshaft` | `bread` ×1–3, `glow_berries` ×3–6, seeds, FD `rice`/seeds ×2–4 |
| `trial_chambers/intersection_barrel` | `baked_potato` ×6–10 (30%) — best single food chest in the pack |
| `spawn_bonus_chest` | `apple` ×1–2, `bread` ×1–2, `salmon` ×1–2 |

`chefsdelight` adds chef/cook houses to villages (2 per plains village, **5 per desert
village**, 3 savanna/snowy) — these are food-dense buildings worth clearing.

`farmersdelight` has `generateFDCropsOnVillageFarms: true`, so village farm plots contain
cabbage / tomato / onion / rice alongside vanilla crops. **Harvesting a village farm is the
fastest way to get a full crop set with seeds.**


---

## 4a. Tools — what a hoe actually does

**Corrected.** An earlier version of this file said a hoe does not affect drops. That is true
of the *wild-crop loot tables* (vanilla loot, only shears and Fortune appear there) — but it
is **wrong for planted crops**, because `civfabric` adds a separate harvest-success gate that
the loot tables never see.

### The real rule

`civfabric` `HarvestChance.fails()` runs before any loot roll:

```
if crop.requiresHoe and you are not holding a hoe:   HARVEST FAILS, you get nothing
success = base + perRank × FARMER_rank + perHoeTier × hoeTier
```

`requiresHoe` is the **default**; only cocoa, melon and pumpkin opt out. So **without a hoe you
cannot harvest wheat, carrots, potatoes or beetroots at all.**

Hoe tiers: wood 1, stone/gold 2, iron 3, diamond 4, netherite 5.

| crop | base | per FARMER rank | per hoe tier | rank 0 + wood hoe | rank 5 + netherite |
|---|---|---|---|---|---|
| wheat, beetroot | 0.58 | 0.06 | 0.03 | **61%** | 100% |
| carrots | 0.448 | 0.06 | 0.05 | **50%** | ~100% |
| potatoes | 0.413 | 0.064 | 0.053 | **47%** | ~100% |
| `cobblemon:medicinal_leek` | 0.23 | 0.084 | 0.07 | **30%** | 100% |
| default (unlisted) | 0.4 | 0.1 | 0.06 | 46% | 100% |

Wheat and beetroot also have a lower `untended` curve (0.4) when the plot has been neglected.

So at rank 0 with a wooden hoe you lose **roughly half of every harvest**. FARMER rank and hoe
tier are first-order concerns, not polish.

Other effects: harvesting **wears the hoe**, and **exhausts the soil** (which ties into
Realistic Plant Growth's `soil_rotation_days: 5`). FARMER rank 2 unlocks **auto-replant**, at a
cost of 3 hoe durability per use.

### Why this forces cooperation

Recipe gating makes the tier ceiling a *social* problem:

| item | gate |
|---|---|
| `minecraft:wooden_hoe` | **ungated** — the only hoe you can make alone |
| `minecraft:stone_hoe` | BLACKSMITH rank 1 |
| `minecraft:iron_hoe` | BLACKSMITH rank 3 |
| `minecraft:shears` | BLACKSMITH rank 3 (+2.0 hunger) |

**A lone farmer is capped at hoe tier 1 forever.** Getting past ~61% wheat / 50% carrots
*requires a Blacksmith in the group*. Shears — needed to transplant wild crops — are likewise
Blacksmith 3. This is the mechanical reason the pack wants 3+ players.

Craftable with no class at all: torch, soul torch, stick, crafting table, furnace, chest,
barrel, planks, boats. That is the entire classless toolkit.

### Crafting and movement cost hunger

`civfabric` charges hunger to craft, and **refuses the craft if you are too hungry**:
bread 0.5 · mushroom stew 0.35 · crafting table 1.0 · chest 1.25 · furnace 1.25 ·
bucket 2.0 · shears 2.0 · boat 3.2.

And movement drains hunger on its own timer (every 200 ticks), *on top of* vanilla exhaustion:

| state | drain per 10s |
|---|---|
| idle | 0.0 |
| crouching | 0.1 |
| walking | 0.1 |
| **sprinting** | **1.0** |
| **swimming** | **4.0** |

Sprinting costs 3.75× walking per tick, swimming 15× — and idling now drains 0.05 too. For the
2000-block trek to a settle site, **walk**.

Vanilla exhaustion still applies underneath (break block 0.005 · attack 0.1 · sprint 0.1/m ·
jump 0.05 · swim 0.01/m; 4.0 exhaustion = 1 saturation). The vanilla `heal 6.0` drain never
fires, since natural regeneration is off.

---

## 4b. Blood moons (and storms, which only matter if you fly)

### Lunar events (world datapack, Enhanced Celestials)

| event | chance/night | min gap | moon phase | monster rate | effects |
|---|---|---|---|---|---|
| **Blood moon** | 10% | 4 nights | any | 1.0× | blocks sleeping, forces **surface** spawning |
| **Super blood moon** | 5% | 20 nights | full moon only | **2.25×** | blocks sleeping, forces surface spawning |

Both **block sleeping** — the night cannot be skipped. Both force mobs to spawn on the
surface, which means **on top of your open-sky farm**. And `pacifist-fabric` explicitly
suspends pacifism during `enhancedcelestials:blood_moon` and `super_blood_moon`, so **PvP
goes live**. Combined with no natural regeneration, a blood moon is the most dangerous
recurring event in the run.

Agent behaviour: detect the start notification, abort outdoor tasks, shelter with the farm
lit and walled, and do not fight unless the target is already engaged.

### Thunderstorms — only matter if you fly

**Corrected.** An earlier version of this section said open ground is lethal in a storm. That
was backwards.

`zymlabs` `StormLightning` measures `clearanceBelow(world, entity)` — the empty air
*underneath* the player — not the open sky above them. Risk starts at `MIN_CLEARANCE = 8`
blocks off the ground and hits full damage at `FULL_CLEARANCE = 48`:

- `LOW_EXPOSURE = 480s` → `LOW_DAMAGE = 5`
- `HIGH_EXPOSURE = 90s` → `FULL_DAMAGE = 100`
- `GRACE_TICKS = 200` (10s)

It's there to stop players flying through storms on an elytra or a flying Pokémon. **Anyone on
foot has clearance ~0 and is never struck.** A storm is a perfectly good time to wait indoors
for crops, but there's nothing to flee from.

---

## 5. Phase 4 — villagers, then settle

The user-stated priority is correct: during the wandering phase, **find a village before you
build anything**. A village gives you, in one place: mixed crops with seeds, food chests,
a composter, chef houses, and trades.

### 5.1 Food-relevant trades (all enabled in server config)

- **Vanilla farmer** — buys crops for emeralds, sells `bread`, `cookie`, `pumpkin_pie`,
  `golden_carrot`. Unchanged and still the fastest emerald→food conversion.
- **Farmer's Delight** (`enableFarmerFDTrades: true`) — farmers **buy** `cabbage`, `onion`,
  `rice`, `tomato` for emeralds.
- **FD wandering trader** (`enableWanderingTraderFDTrades: true`) — **sells `cabbage_seeds`
  and `tomato_seeds`**. This is the reliable way to get FD seeds without hunting 1/30 patches.
- **Vinery winemaker** — level 1 sells `red_grape_seeds` / `white_grape_seeds` for 2 emeralds
  and buys grapes for 15; level 2 sells `wine_bottle` and `apple_mash`.
- **UbesDelight** — `farmersBuyUDCrops: true`, `wanderingTraderSellsUDItems: true`.

### 5.2 Settle checklist

1. Village found, in **PLAINS / SUNFLOWER_PLAINS / MEADOW** (wheat 100%, 0% death) — ideally
   bordering a **FOREST/BIRCH_FOREST** for carrots at 100%.
2. Build the farm **open to the sky** (light 15). Do not roof it.
3. Fence the farm **away from villagers** — villager farming destroys farmland here.
4. Plant at least **four different crops** and rotate them every 5 in-game days.
5. Target **12 distinct foods** for full value (6 gets you 70%; 7–11 add nothing over 6) —
   see BOT_BEHAVIOUR.md for the staircase.
6. Build a **roofed shelter next to (not over) the farm** — the farm needs sky for light 15,
   you need cover for blood moons.
7. Keep nettle/ribwort tea or salads stocked: with no natural regen, health is the scarce
   resource, not food.

### 5.3 A worked rotation available from forage alone

`farm_and_charm:tomato` (4) · `minecraft:carrot` (3) · `farm_and_charm:lettuce` (3) ·
`minecraft:mushroom_stew` (6) · `farmersdelight:cabbage` (2) · `farm_and_charm:onion` (2) ·
`farm_and_charm:corn` (2) · `ubesdelight:ube` (2) · `minecraft:baked_potato` (4) ·
`minecraft:bread` (5) · `minecraft:cooked_salmon` (6) · `minecraft:cooked_porkchop` (8)

That's twelve. Every one is obtainable in phase 1–2 with no crafting station beyond a crafting
table, a bowl and a furnace. Cycled in order, each is eaten at **full value** — drop any one
and the whole rotation falls to 70%. (Note baked potato is **4** hunger on this server, not
vanilla's 5: `zymlabs` sets `BAKED_POTATO_HUNGER = 4`.)

---

## 6. Reference: hunger values for common early items

| item | hunger | sat. mod | total sat | notes |
|---|---|---|---|---|
| `minecraft:cooked_beef` / `cooked_porkchop` | 8 | 0.8 | 8.0 | best raw numbers |
| `minecraft:cooked_salmon` / `cooked_mutton` / `cooked_chicken` | 6 | 0.8 / 0.8 / 0.6 | 6.0 / 6.0 / 4.8 | |
| `minecraft:mushroom_stew` / `beetroot_soup` | 6 | 0.6 | 6.0 | 2 mushrooms + bowl |
| `minecraft:rabbit_stew` | 10 | 0.6 | 10.0 | |
| `minecraft:bread` / `baked_potato` / `cooked_cod` | 5 | 0.6 | 5.0 | |
| `vinery:jungle_grapes_red` / `_white` | 5 | 0.6 | 5.0 | forageable in jungle, 1/10 chunks |
| `minecraft:apple` / `farm_and_charm:tomato` | 4 | 0.3 | 2.4 | |
| `minecraft:carrot` / `farm_and_charm:lettuce` | 3 | 0.6 | 3.0 | |
| `minecraft:rotten_flesh` | 4 | 0.1 | 0.8 | 80% Hunger effect — emergency only |
| `farmersdelight:cabbage` / `onion`, `ubesdelight:ube` / `garlic` / `ginger` | 2 | 0.4 | 1.6 | |
| `minecraft:sweet_berries` / `glow_berries` | 2 | 0.1 | 0.4 | |
| `minecraft:potato` (raw) | 1 | 0.3 | 0.6 | always bake it |
| `minecraft:poisonous_potato` | 2 | 0.3 | 1.2 | 60% Poison |

Notable pack foods worth cooking toward later:
`farmersdelight:smoked_ham` **10 / 0.8**, `zymlabs-rules:katsudon` **14 / 0.75**,
`zymlabs-rules:oyakodon` **12 / 0.8**, `zymlabs-rules:cornucopia` **10 / 0.5**,
`farm_and_charm:farmers_breakfast` **12 / 1.2**, `cobblemon:vivichoke_dip` **10 / 0.6 +
Absorption, always edible**, `candlelight:lasagne` / `beef_wellington` **10 / 0.7**.

---

## 7. Extraction notes / confidence

- **Vanilla values were verified** against known values (apple 4/0.3, cooked beef 8/0.8,
  mushroom stew 6/0.6, rabbit stew 10/0.6) — the bytecode reader reproduces them exactly.
- **Farmer's Delight values were verified** the same way (smoked ham 10/0.8, beef patty 4/0.8).
- Bakery and Farm & Charm nutrition come from the **live server config**, not the jar defaults,
  so they reflect what this server actually serves.
- Items bound by Java field name (ubesdelight, vinery, part of farm_and_charm) rely on the
  convention `FIELD_NAME.lower() == registry id`. This holds throughout the pack but is a
  heuristic — spot-check those namespaces in-game if a value looks wrong.
- Some mod effect entries lack a duration where the mod passes effects through its own
  wrapper type rather than a vanilla `StatusEffectInstance`; the effect identity is still correct.
- Biome assignment for wild features is partly code-driven (Fabric biome modifications), so
  `biome_tag` is `null` for several mods. Rarity values are exact.
