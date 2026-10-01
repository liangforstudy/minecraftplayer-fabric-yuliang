# civbot-yuliang

An interrupt stack over a milestone planner (**not an LLM!!**). Like Altoclef, but written from the ground up by Claude
Opus 5.5 (low effort) for newer versions of Minecraft. Bug tested by me.

This is not an MCP and doesn't contain any built-in LLM.

The goal is to beat newer versions of Minecraft, and:

- earning all Java advancements
- pretending to be a different mob, via your favourite morph mod (villager, etc.)
- Litematica, map art, builder
- not an in-game girlfriend, that's gross

---

A client-side Fabric bot that plays the **1.21.1 ZymCiv modded server** — foraging, farming and
surviving alongside human players, with the end goal of **beating the game**.

It doesn't assume a fresh spawn. On start it surveys the world, works out how far the
civilisation has actually progressed, and picks up the binding constraint from there.

The mod is called **Zymbot** (`mod/`). It's rule-based: a stack of interrupts (drowning, hunger,
danger, knocked out, …) over a milestone planner, with every choice logged as
`[decision] … — because …`. Walking is done by **Baritone**; Zymbot decides where.

> **Status (2026-10-01): Zymbot 0.1.42 runs headless and is live-tested** — survival reflexes, regroup and
> follow, survey, break and collect, graves (diving too). Next: crafting (PHASE3.md).

The server changes enough of Minecraft's survival rules that a normal bot would starve, fail
every harvest, and die on the first blood moon. Much of this repo is the work of finding out
exactly *what* changed, by reading the pack's data files, configs and bytecode.

## Quick start (Windows)

In `headless/` (details in [headless/README.md](headless/README.md) and [SKILL.md](SKILL.md)):

- `setup.bat` once, then `sync-bots.bat` whenever the Prism pack changes.
- **`play.bat`** — your game straight into "New World", then Bot1 joins it, then the console opens.
- `standby-bot1-singleplayer.bat` — Bot1 only: waits for your world to open to LAN, then joins.
- The **console** (Windows Terminal, a tab per bot): the log on top, decisions and a command prompt
  below (Tab completes, Up/Down recalls). Close the tab, or `stop-bot1.bat`, to stop the bot.
- In game: `/zbot help`. `/zbot start` hands the account to the bot.

macOS / Linux: the same commands as `.sh` (`bots.py`).

## Start here

> **These docs are living.** Every in-game test can prove or disprove what they say; when it does, the doc changes.

| file | what it is |
|---|---|
| **[BOT_BEHAVIOUR.md](BOT_BEHAVIOUR.md)** | **What the bot does** — interrupt table, phases, food rotation, commands |
| [BOT_DESIGN.md](BOT_DESIGN.md) | Why it does that — 40 requirements and the verified mechanics behind each |
| [ROADMAP.md](ROADMAP.md) | Test devices, the port-friendly architecture, per-mod packs, user rules, future work |
| **[FOUNDATION.md](FOUNDATION.md)** | **Phase 0 spec** — the hard-to-change base: modules, tasks, protocol, command root, autostart whitelist, comms stack |
| [PHASE1.md](PHASE1.md) | Phase 1 — the Body: walking, water, eating, reflexes (built, live-tested) |
| [PHASE2.md](PHASE2.md) | Phase 2 — Discovery and regroup: find the team after a cold start (built, live-tested) |
| **[PHASE3.md](PHASE3.md)** | **Phase 3 — gather, craft, feed itself (building now; open decisions at the top)** |
| [PHASE3_FIXLIST.md](PHASE3_FIXLIST.md) | Bugs from live tests and what still needs testing |
| [villagers_entity_roadmap.md](villagers_entity_roadmap.md) | Later: look like a villager (morph mods), build from schematics, safe parkour |
| [mod/](mod/) | The Zymbot mod: `core/` pure Java (all logic, unit-tested), `src/` the Fabric adapter |
| [SKILL.md](SKILL.md) | Operating manual for agents: the rig, `/zbot` commands, Baritone, safety rules |
| [SURVIVAL_EARLY_GAME.md](SURVIVAL_EARLY_GAME.md) | The survival guide: forage, easy kills, loot, where to farm |
| [survival-data/](survival-data/) | Machine-readable extracts the above rest on |
| [headless/](headless/) | Headless client rig (HeadlessMC) — the bots run here, Windows and macOS |
| [moonlight-headless-patch/](moonlight-headless-patch/) | Standalone Fabric mod that makes Moonlight-based mods survive headless |
| [tools/pack-extractor/](tools/pack-extractor/) | The scripts that read a modpack's jars (incl. bytecode) into knowledge tables |

## The five rules that shape everything

1. **Repeat food decays.** `hunger × 0.7^timesEaten` over the last 11 items eaten. Diet breadth
   beats food quality — a dozen vegetables beat a stack of steak. Full value needs **12**
   distinct foods, and only 2 / 3 / 4 / 6 / 12 are worth chasing — see BOT_BEHAVIOUR.md.
2. **No natural regeneration.** The gamerule is forced off. Health returns only from healing
   foods, a Medic, or bed rest — and bed rest costs **1 hunger per HP**. The farm funds the
   health bar.
3. **No hoe, no harvest.** Planted crops fail outright without one, and success is
   `base + 0.06×FARMER_rank + 0.03×hoe_tier`. At rank 0 with a wooden hoe you lose ~40% of
   wheat and ~50% of carrots.
4. **Crops are biome-locked, and no biome grows all three staples.** Wheat wants plains,
   carrots want forest, potatoes want windswept. Settling means finding a *junction*.
5. **Movement costs hunger directly.** Walking 0.2 per 10s, sprinting 0.75, swimming 3.0 — and
   even standing idle drains 0.05. The bot walks. Per distance, sprinting costs ~3× walking and
   swimming far more.

## Beating the game is a class-XP problem

The Nether is class-gated: obsidian needs a diamond pickaxe (**BLACKSMITH 3**) and diamond ore
needs **MINER 4**. There's a bypass — casting the portal frame with lava and water skips
mining obsidian, so it only really needs **BLACKSMITH 3** for a bucket and flint & steel.
Past that, `blaze_powder` and `ender_eye` are ungated, so the End isn't class-locked.

Which makes food the foundation rather than the point: **class XP is the currency of
progression, and dying costs half of it.** A bot that starves or dies while grinding moves
backwards.

## Why three players

Not a suggestion — it's enforced three separate ways:

- A town requires **3 beds** within 64 blocks.
- **6 classes enabled × 2 slots = exactly 3 players** for full coverage.
- The tool chain crosses players: **MINER 2 → iron → BLACKSMITH 2 → iron hoe → FARMER yield.**
  A lone farmer is capped at a wooden hoe forever.

## Where the data came from

Everything in `survival-data/` was extracted from a live Prism instance — 84 mods — rather
than from wikis:

- Recipes, loot tables, worldgen and tags from the jars' data files.
- **Food values from Java bytecode**, via a `javap` reader plus an obfuscated→intermediary
  remapper built from Prism's own mappings. Verified against known values: vanilla apple 4/0.3,
  cooked beef 8/0.8, mushroom stew 6/0.6, Farmer's Delight smoked ham 10/0.8.
- Server rules from the **live config files and the world datapack**, so they reflect what this
  server actually runs, not mod defaults.

Confidence notes and known gaps are in [SURVIVAL_EARLY_GAME.md](SURVIVAL_EARLY_GAME.md) §7.

## Headless status

The bot client runs headless via HeadlessMC — no Xvfb, no display — on Windows (the owner's
machine, `bots.ps1` + `.bat`) and macOS (`bots.py` + `.sh`). **The full
84-mod pack loads and reaches the title screen.** Cobblemon and GeckoLib, the expected
problems, were fine. Supplementaries did break — it parses texture pixels at boot through
stubbed STB — so [moonlight-headless-patch/](moonlight-headless-patch/) fixes it at the
Moonlight level, which covers every mod that calls the same palette parser. Budget ~3 GB per
bot. Details in [headless/](headless/).

## Target

Fabric, Minecraft **1.21.1** (the modded pack); built with Stonecutter so other versions can be
added (only 1.21.1 exists today). Testing runs in the owner's singleplayer world opened to LAN with
online mode off (`/zbot lan`), so the headless bots can join it.
