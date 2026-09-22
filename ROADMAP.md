# Roadmap and architecture

Scoping only — **the main mod is not being written yet.** This is what it has to be shaped for,
so that nothing gets hardcoded that would make later work tedious.

---

## Test devices

The client needs ~**3.5 GB RSS** per bot (measured on the full 84-mod pack at `-Xmx3G`; 1.5 GB
OOMs). With LWJGL stubbed there's no GPU requirement at all — which is what makes the smaller
devices plausible. HeadlessMC ships native launchers for every platform below.

| device | CPU | RAM | OS | status | bots |
|---|---|---|---|---|---|
| **MacBook M2** | Apple M2, 8 cores | 16 GB | macOS | **verified** — full pack boots | ~2 alongside a server + GUI client, ~3 without |
| **Acer Nitro** | x86_64 | varies, 8–32 GB | Windows | expected to work — `windows-x64` launcher | ~1 per 4 GB spare |
| **Steam Deck OLED** | AMD Zen 2, 4c/8t | 16 GB | SteamOS (Arch) | expected to work — `linux-x64` launcher | ~2–3 |
| **Raspberry Pi** (model TBD) | ARM — Pi 4: Cortex-A72, Pi 5: Cortex-A76 | 4 / 8 / 16 GB | Linux, **64-bit OS** | **uncertain** | 8 GB: 1 · 16 GB: 2 · 4 GB: no |

Notes per device:

- **Acer Nitro** — which Nitro, and how much RAM? That's the only thing deciding the bot count.
  The discrete GPU is irrelevant headless.
- **Steam Deck** — needs Desktop Mode. SteamOS keeps the system partition read-only, so don't
  install Java system-wide; let HeadlessMC fetch its own (`hmc.auto.download.java=true`) into the
  home directory.
- **Raspberry Pi** — the exact model isn't known yet; it's an ARM board either way. Check it with
  `uname -m`: **`aarch64` is what we need** (64-bit OS, Pi 4 or 5 — HeadlessMC ships
  `linux-arm64`). `armv7l` means a 32-bit OS: reflash with 64-bit Raspberry Pi OS; a Pi 3 or older
  isn't worth trying. RAM is solvable (8 GB+ only; the 4 GB model can't fit one bot plus the OS).
  **CPU is the real unknown.** An A76 core (Pi 5) is well behind an M2 core, an A72 (Pi 4) further still, and 84 mods take a while to
  initialise. In the Pi's favour: with rendering stubbed, most of a client's per-frame work simply
  isn't happening. Worth one test boot; don't plan around it until then.

Everything above is the *client*. The server runs somewhere else.

---

## Architecture — built to be ported

The goal: a Forge port, a new Minecraft version, or a new modpack should each be a bounded job,
not a rewrite. That falls out of one decision — **keep Minecraft out of the core.**

```
┌──────────────────────────────────────────────────────────────────────┐
│  core/          pure Java — NO Minecraft or loader imports              │
│    planner       milestone ladder, objective selection, deference      │
│    interrupts    the priority stack                                     │
│    rotation      diet math                                              │
│    forecast      log-odds fusion                                        │
│    combat        state machine, threat assessment                       │
│    knowledge     loads packs + rules                                    │
└───────────────────────────────┬──────────────────────────────────────┘
                                │  talks only through interfaces
┌───────────────────────────────▼──────────────────────────────────────┐
│  adapters/      the ONLY code that imports Minecraft / a loader         │
│    fabric-1.21.1/   fabric-1.21.8/   (forge-*/ later)                   │
│    implements:  WorldView · PlayerView · Inventory · Chat · Pathing ·   │
│                 CombatControl                                           │
└──────────────────────────────────────────────────────────────────────┘

  packs/        DATA, not code — one folder per mod         rules/    user-authored
  tools/        the extractor that generates packs
```

What each kind of port then costs:

| port | touches | untouched |
|---|---|---|
| **new MC version** | one new adapter (mostly API renames) | core, packs, rules |
| **Forge / NeoForge** | one new adapter set | core, packs, rules |
| **new modpack** | run the extractor → new packs | all code |
| **vanilla** | load only the `vanilla` pack | all code |

If `core/` ever imports a `net.minecraft` class, that's the bug — it's the one rule that keeps all
of the above cheap.

Build tooling can wait until scaffolding: **Stonecutter** (several MC versions from one source
tree) and **Architectury** (several loaders from common code) both fit this shape. Architectury
is already in the ZymCiv pack.

### Knowledge packs — tables separated per mod

Every table the bot reads is data, split **by mod**, so packs compose:

```
packs/
  vanilla/          foods, mobs, recipes, loot, threat table, biome crops
  farmersdelight/   foods, wild crops, recipes, village loot
  cobblemon/        species drops, spawns, flee/defend/fly, berries
  letsdo/           farm_and_charm, bakery, vinery, …
  zymciv/           server layer: civfabric class gates, harvest curves, hunger model,
                    spice-of-fabric, pacifism, lunar events, bed rest
```

A **modpack profile** is just a list:

```yaml
zymciv:   [vanilla, farmersdelight, cobblemon, letsdo, ubesdelight, zymciv]
vanilla:  [vanilla]
```

Later packs override earlier ones, so `zymciv` can override vanilla's baked potato (4, not 5)
without vanilla knowing it exists.

**The server layer deserves its own pack.** Most of what makes this project hard — class gates,
no natural regen, the diet decay — isn't a content mod, it's server rules. Kept separate, the
same content packs serve a vanilla-rules server unchanged.

`survival-data/` is effectively the first draft of these packs; it just isn't split by mod yet.

### The extractor is the content-portability engine

`tools/pack-extractor/` is what produced `survival-data/` — including reading food values out of
Java **bytecode**, since Fabric mods don't keep them in data files. Supporting a new modpack
means running this, not hand-writing tables.

Right now it's hardwired to one instance's layout. Turning it into one command —
`extract <instance-dir> <packs-dir>` — is the highest-leverage piece of tooling on this list.

---

## User rules — the Tampermonkey / AutoHotkey idea

Rules as files dropped into `rules/`, hot-reloaded, no rebuild. Each is *when → then*:

```yaml
id: hunt-sleeping-birds
when:
  time: night
  mob_nearby: { tag: flying_food, state: sleeping }
  health_above: 0.6
then:
  objective: hunt
  target: nearest
priority: 40
```

What a rule can hook:

| hook | adds |
|---|---|
| interrupt table | a new interrupt at a chosen priority |
| milestone ladder | a new rung — the ladder is already data (`id, check, owner, contribution`) |
| objective scoring | a bias toward or away from something |
| commands | a new `/bot` subcommand |
| hotkeys | a key that triggers an action — the AutoHotkey half |

**Declarative first.** A YAML/JSON rule can't crash the bot, can be read at a glance, and can be
shared as a file. A scripting escape hatch (Lua via LuaJ is small and sandboxable) can come later
if rules genuinely can't express something. Most "I forgot to scope X" additions are one rule.

Rules sit *under* the survival interrupts, same as human deference — a rule can redirect work,
but not switch off eating, healing or blood-moon shelter.

---

## Future roadmap

Roughly in order; nothing here is committed.

1. **Threat tables and heavier targets.** As the ladder advances and gear improves, the bot takes
   on stronger mobs and Pokémon. `ASSESS` compares a mob row (HP, damage, defends, flees, flies)
   against a gear row (damage, armour) and decides. Pure table lookup — new gear or a new mob is a
   data change.
2. **Task mode — tedious jobs on request.** Instead of the full autonomous ladder, a human hands
   it a job: *mine this area*, *collect 64 of X*, *tend the farm while I'm away*. Baritone already
   mines well; the bot adds the server's rules on top (MINER rank gates, hunger budget, return
   trip).
3. **Beat vanilla Minecraft autonomously.** Load only the `vanilla` pack. The ladder already runs
   to `NETHER → END`; vanilla is the same ladder with the server-rule rungs removed.
4. **Other modpacks.** Extractor → packs → profile.
5. **User rule system** as above.
6. **Ports** — more MC versions, then maybe Forge/NeoForge, via new adapters.
7. ~~`/zbot summon`~~ — **built in 0.1.5**, see headless/README.md → "Summon from the game".

### On "it's vibecoded"

That's the strongest argument *for* this structure, not against it. A codebase where the game
knowledge is data, the loader is behind an interface, and new behaviour is a rule file is one
where each future change is small and local — which is exactly what keeps it workable, whoever or
whatever is doing the editing.
