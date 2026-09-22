# Pack extractor (prototype)

The scripts that produced `survival-data/`. They read a modpack's jars and configs and emit
the knowledge tables the bot runs on.

**This is the portability engine for content.** Supporting a new modpack — or vanilla — means
running these against it, not hand-writing tables. That's why they're kept, even rough.

## Status: working, but hardwired

They were written interactively against one Prism instance and assume a working layout:

| expects | what |
|---|---|
| `x/<modjar>/` | each jar's `data/` + `assets/*/lang/en_us.json` unzipped |
| `allcls/<modjar>/` | each jar's `.class` files unzipped |
| `map/` | intermediary `mappings.tiny` + the vanilla client jar's classes |
| `vanilla/` | the vanilla jar's `data/minecraft/` |
| `survival-data/` | output (some scripts write here directly) |

Turning this into a single `extract <instance-dir> <out-dir>` command is roadmap work.

## What each does

| script | produces |
|---|---|
| `build_map.py` | intermediary field → registry id maps for effects and items, from the vanilla jar + mappings |
| `foodex.py` | **the bytecode food reader** — parses `javap` output for `FoodComponent.Builder` chains, lambda/BootstrapMethod linkage, `(IF…)` factory calls, builder-returning helpers, and field-name binding |
| `runall.py` | runs `foodex` per mod, seeded with vanilla constants |
| `vanilla_food2.py` / `vanilla_consts.py` | remaps the obfuscated vanilla jar to intermediary so `foodex` can read vanilla foods |
| `forage2.py` | wild-plant worldgen features → rarity, placed blocks, harvest drops |
| `cobble.py` | Cobblemon species × spawn pools → food-dropping Pokémon with stats and biomes |
| `loot.py` | chest loot tables → food and seed weights |
| `rank.py` | forage yields joined with food values, ranked |
| `consolidate.py` | merges everything into `survival-data/*.json` |
| `statics.py` | dumps compile-time constants from a config class (used on `zymlabs-rules`) |

## Why bytecode

Food values in Fabric mods live in Java code, not data files. Reading them from bytecode is
what made the data trustworthy — verified against known values (vanilla apple 4/0.3, cooked
beef 8/0.8, mushroom stew 6/0.6, Farmer's Delight smoked ham 10/0.8).

Where mods read values from **config** at runtime (Let's Do Bakery, Farm & Charm), the live
config file wins over bytecode.

## Known limits

- Bound-by-field-name items rely on `FIELD_NAME.lower() == registry id`. Held across this pack,
  but it is a heuristic.
- Effects passed through a mod's own wrapper type lose their duration.
- Kotlin mods (Cobblemon) needed the builder-flush fix; other Kotlin idioms may need more.
- Biome placement set in code (Fabric biome modifications) isn't visible in data files.
