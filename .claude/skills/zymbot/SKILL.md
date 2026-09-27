---
name: zymbot
description: How to run and operate Zymbot, the headless rule-based Minecraft bot in this repo — starting bots with HeadlessMC (bots.py / *.sh), sending /zbot commands, reading decision logs, the Baritone settings it uses, and the safety rules for testing in the owner's world. Use whenever starting, stopping, connecting, commanding, updating or live-testing a bot.
---

# Operating Zymbot

Zymbot is a **client-side, rule-based (no LLM) Fabric 1.21.1 mod** that plays a Minecraft account
on the ZymCiv modpack. Bots run **headless** through HeadlessMC; the owner plays in Prism and hosts
the world (Open to LAN). Read `PHASE1.md` (what's built, the test commands) and `headless/README.md`
(the rig) for depth — this file is the operating manual.

> **Keep this file true.** The command table below must match
> `mod/src/main/java/dev/yuliang/zymbot/fabric/ZymbotCommands.java`. When you **add** a `/zbot`
> command, add a row here; when you **remove** one, delete its row; when you change its arguments,
> fix the row. `SkillDocTest` (core tests) fails if a subcommand is missing from — or lingering in —
> the table. Update `help()` in ZymbotCommands and PHASE1.md's command table at the same time.
> A copy sits at the repo root (`SKILL.md`, next to BOT_DESIGN.md); keep the two identical —
> `SkillDocTest` checks that too.

## Rules you must follow

- **Don't launch, connect or stop bots unless the owner asks.** Swapping a jar means copying it,
  not starting anything.
- **Commit only when asked.** Local git only — never push. Never commit `headless/team-key.txt`
  or `<rig>/console.json`, and never paste the team key into chat.
- **The world is the owner's.** To inspect it use read-only commands only (`/data get`,
  `/execute if …`). Never `/fill`, `/setblock`, `/clear`, `/kill` to "check" something.
- **`stop-bots.sh` only kills HeadlessMC processes** (`minecraft.launcher.brand=HeadlessMc`).
  Never `pkill java` / `KnotClient` — that kills the owner's Prism game.
- **Never `jstack`/`kill -QUIT` the relay PID** — the console relay is Python on macOS/Linux
  (`bots.py _relay`) and a hidden PowerShell on Windows (`bots.ps1 _relay`); SIGQUIT kills it. Only
  jstack the Java game process.
- **Watch logs from background commands**, not long foreground ones (the owner's UI shows
  "a shell command is already running" otherwise).
- Bugs in other mods (civfabric, Farm & Charm, …) go to the owner as a plain description in their
  own words — never an AI-written patch. Downloads need the owner's permission.

## The rig (`headless/`)

Two rigs, `bot1/` (Bot1, mirrors the Prism instance named in `bot1/.source` — `1.21.1 Modpack` on
the owner's Windows machine since 2026-09-25) and `bot3/` (held back by `bot3/.unsynced`). Each has
`HeadlessMC/config.properties`, `gamedir/` (mods, `config/zymbot.json`, logs) and
`identity.properties` (offline name + UUID). The logic is in `bots.py` (macOS/Linux, `.sh`
wrappers) and `bots.ps1` (Windows, `.bat` wrappers, no Python) — keep the two in step. Run them from
`headless/`. The table uses `.sh`; on Windows use the `.bat` of the same name.

| command | does |
|---|---|
| `./setup.sh` | once per machine: finds Java 21 + Prism, writes rig configs, installs Fabric 0.19.5 |
| `./sync-bots.sh` [`--check`] | mirror the Prism pack's mods + config into the rigs (refuses a running rig) |
| `./standby.sh bot1` | boot to the title screen and wait (~1 min) — the usual way to start |
| `./connect.sh bot1 127.0.0.1:25565` [`--wait`] | join now, or as soon as the world opens |
| `./run-bot.sh bot1 3G 127.0.0.1:25565` | boot and join in one go; prints IN THE WORLD / REJECTED / MOD MISMATCH / CONNECTED THEN DROPPED / CRASHED |
| `./gui.sh bot1` | what's on the bot's screen (buttons, text) |
| `./send.sh bot1 <console command>` | any hmc-specifics console command — see below |
| `./disconnect.sh bot1` | leave the server, keep running |
| `./stop-bots.sh` | stop all headless bots (never the Prism client) |
| `./play.sh "New World"` | bots to standby in the background + Prism straight into that world; skips if the game is already running. `play.bat` (Windows) opens the game first and starts the bots once the world loads |
| `./team-key.sh` | show the team key (for the owner's own config — don't print it to chat) |

**HeadlessMC settings** (`<rig>/HeadlessMC/config.properties`, rewritten by setup/run):
`hmc.gamedir`, `hmc.java.versions`, `hmc.offline=true`, `hmc.assets.dummy=false` (true breaks mods
that read textures), `hmc.always.lwjgl.flag=true`, `hmc.auto.download.java=false`,
`hmc.jline.enabled=false` (JLine on crashes hmc-specifics). Heap **3G** per bot (1.5G OOMs).
`gamedir/options.txt` render distance 6 = the bot's perception radius.
`moonlight-headless-patch` must be in `mods/` (Supplementaries crashes headless without it).

**Joining:** the host must be offline-mode — `/zbot lan` (or OfflineLAN) opens LAN on 25565 with
online mode off. Synced-registry mods (Cosy Critters, Xaero's) must stay on the bot, or it's
dropped with "registry entries that are unknown"; the sync keeps what it can detect. Not detected
but required on LAN: `diagonalfences`, `visualworkbench`, `xaeroworldmap` (sync-exclude.txt says
so). Joins can stall ~20 s while `Blocks.rebuildCache` runs next to the host (FIXLIST #3) — wait.
The first join after a boot often times out (host allows 15 s, config data takes ~16 s):
`connect` retries once, and so does Zymbot for a summoned join (0.1.28).

**Updating a bot's Zymbot:** build (`cd mod && ./gradlew build`), then with the bot **stopped**
replace `headless/bot1/gamedir/mods/zymbot-*.jar` with
`mod/versions/1.21.1/build/libs/zymbot-<ver>+1.21.1.jar` (remove the old one). The owner's own
client is `D:\PrismLauncher\instances\1.21.1 Modpack\minecraft\mods\` on Windows
(`~/Library/Application Support/PrismLauncher/instances/<instance>/minecraft/mods/` on the Mac) —
only touch it when asked, and only with the game closed. Keep `mod.version` (stonecutter.properties.toml) and
`core/build.gradle.kts` version in step. **On Windows** set `JAVA_HOME=D:\Downloads\jdk-21.0.2`,
bump versions with `sed` or the Edit tool — PowerShell 5.1 `Set-Content -Encoding utf8` writes a BOM
that breaks `stonecutter.properties.toml` — and there's **no Python**: tell every subagent so.
A Prism launch straight into the world: `play.bat "New World"` (a bare `--launch` stops at the title
screen); with Bot1 running too the owner may get Prism's "Low free memory" prompt.

## Talking to a running bot

`./send.sh bot1 / zbot status` — the hmc-specifics `/` command, **a space after the slash**, runs a
client command as the bot (on the Mac). **On Windows it's `send.bat bot1 /zbot status`, no space** —
the spaced form is rejected there (seen 2026-09-25). `./send.sh bot1 msg <text>` says something in chat. `send` prints the
console lines from the next ~3 s.

The owner can also type the same `/zbot …` commands in their own game — they act on **their**
client's Zymbot (e.g. `/zbot summon`, `/zbot watch Bot1`). Gotchas seen 2026-09-26:
- A Teammate client is **silent** unless the world is on its autostart list
  (`/zbot autostart add singleplayer`) — the bots then can't find the owner and fall back to spawn.
- `/zbot role none` removes the account from `accounts`; put it back with `role teammate`.
- `/zbot start` on the owner's own client makes Zymbot **play their account** (`/zbot stop` to
  hand it back) — orders on a Teammate account now say so instead of suggesting `start` (0.1.31).
  It still announces its **role** (Teammate) separately from the phase, so the roster shows
  "Teammate (bot driving)" and bots still regroup with it.
- One-shot orders on a Teammate account (`goto`, `come`, `punch`, `grave`, `grave loot …`)
  **borrow the controls** for that one task, no `start` needed: they're given back when it ends or
  the moment a movement key is touched (`[decision] borrowed the controls` / `gave the controls back`).
  Standing jobs (`follow`, `eat`, `look`, `regroup`, the planner) still need `start`.
- `/kill`, `/tp`, `/give`, `/data get` work from the bot's console (`send.bat bot1 /kill Bot1`) in the
  owner's LAN world with cheats on. Offline-account bots rejoin/respawn at a new spot, often near
  spawn, and respawn with no food (foraging is Phase 3) — `/give Bot1 minecraft:bread 16`.

**Logs:** `headless/bot1/run-HHMMSS.log` (newest = current run). Useful greps:
`[decision]` (every choice with its reason: `[decision] walking to … — because …`),
`[zymbot]` (route timings, pathfinder, LAN, summon), `[CHAT]` (chat, /msg). Watch in the background,
e.g. `tail -f headless/bot1/run-*.log | grep -a --line-buffered "\[decision\]"`.

## `/zbot` commands

The root is `command_root` in `zymbot.json` (default `zbot`, plus `command_aliases`).
Coordinates accept `~` and `~10` (relative to whoever typed it). Orders need the bot to be in
control (`start`, role Bot); pressing a movement key pauses it for a few seconds.

| command | does |
|---|---|
| `help` | list the commands (also bare `/zbot`) |
| `status` / `see` | phase, current task and why, interrupts |
| `see <bot>` | that bot's status, asked over the team bus (STATUS → STATUS_LINE); for a Teammate reading a bot; "no answer" after 5 s |
| `start` / `stop` | hand control to the bot / take it back |
| `role bot\|teammate\|none` | what this account is (Bot: bot plays it; Teammate: human plays, it announces) |
| `goto <x> <z>` / `goto <x> <y> <z>` | walk there via the route planner + Baritone |
| `follow <player>` | keep following a player |
| `come <player>` | walk to within a few blocks of a player, then stop |
| `watch <bot> [off]` | that bot /msg's you its decisions for 30 min |
| `look <player>` | face a player |
| `eat` | eat the best food now |
| `debug terrain <x> <z>` | what the route planner sees in that column (LAND/WATER/BLOCKED + height) |
| `debug block <x> <y> <z>` | the block id there |
| `debug foods` | the food carried, with the game's live (Spice of Fabric–decayed) values |
| `debug punch <x> <y> <z>` | order: break that block like a player (walk into reach, best hotbar tool, else bare hands) and pick up the drops (5 s); `~` works |
| `grave` | walk to **our own** nearest civfabric grave (16 blocks; owner read from the grave's block entity; else the death spot) and take the items back — never opens anyone else's |
| `grave loot` / `grave loot <player> […]` / `grave loot except <player> […]` | take from other players' graves, on purpose only: any nearby grave but ours / only those players' / any but theirs (names autocomplete; nothing saved); shift-clicks everything that fits, then closes the screen. `grave loot <me>` = `grave` without the death-spot walk |
| `cancel` | drop the current order |
| `set [<setting> <n>]` | list, or change a tunable: `leash` 8–1000, `heel` 1–256 (idle follow stops this close; below the leash), `eat` 1–19, `critical` 1–19, `downed` 0–55 s, `regroup` 8–256, `probewait` 5–300 s, `plantime` 50–5000 ms, `lagtps` 5–19 (also Mod Menu → Zymbot → Body) |
| `danger [modpack\|easy\|normal\|hard]` | when it runs from a mob: `modpack` (default) at the first hit; vanilla `easy`/`normal`/`hard` only at critical health (`critical` −2 / ±0 / +4) |
| `regroup` | walk back to the nearest Zymbot teammate now (automatic on start and after a respawn, when none is within `regroup` blocks); once started it goes all the way (~4 blocks); a bus position older than 120 s doesn't count, so a silent team → spawn after `probewait`. An idle bot also follows past `leash` (default 21) and stops at `heel` (default 2), like a wolf |
| `debug survey` | read only: look around now; the new one prints in chat when the background scan finishes (an old one meanwhile, labelled with its age and where it was taken) — trees (nearest, huge = 2×2 trunk), wild food patches, crafting tables, furnaces, chests/barrels, beds, campfires within 56 blocks (16 below to 24 above), plus inventory, health/hunger, food carried with live values, biome, day/time, spawn distance. Automatic on start and after a respawn; logged as `[decision] surveyed — because …` |
| `roster` | the team list: each Bot / Teammate / "Teammate (bot driving)" (by the account's announced role, not its phase), online or not (tab list), last heard, where, and how (bus / seen) |
| `summon` / `summon auto on\|off` | bots waiting at their title screen join this world (auto: whenever it opens to LAN) |
| `lan` / `lan auto on\|off` | open this world to LAN, port 25565, online mode off (auto: every time it loads) |
| `autostart add\|remove\|list` | servers where Zymbot activates by itself |

Config (`gamedir/config/zymbot.json`, snake_case) also holds `never_eat`, `swim_cost_blocks` (3),
`route_radius` (96), `plan_timeout_ms`, `lag_tps`, `auto_respawn`, `skip_experimental_world_warning`,
`team_key` (secret), `accounts` (uuid → role).

## Baritone

Optional (Baritone 1.11.3 API, `headless/baritone-api-fabric-1.11.3.jar`, compile-only) behind
`PathProvider`; `BaritonePaths` is only loaded when Baritone is installed. `[zymbot] pathfinder:
Baritone 1.11.3` in the log confirms it. Settings Zymbot applies (`BaritonePaths.java`):
`allowSprint` only while retreating, `allowBreak/allowPlace/allowParkour/allowInventory = false`,
`maxFallHeightNoWater = 3`, `sprintInWater = false`, no water-bucket falls, and **water is banned by
adding it to `blocksToAvoid`** (removed again on stop). Baritone walks whole trips; Zymbot's own
A* (`core/route/RoutePlanner`, cost = hunger, water ×3) looks ahead every 64 blocks on a background
thread and takes over only for a water crossing (it only sees loaded chunks — idea: read Baritone's
chunk cache, `gamedir/baritone/<server>/`, to see past render distance). Reflexes around water:
stranded (out of its depth, idle) swims to shore; wading (idle, standing in water 2.5 s) steps out,
giving up after 3 tries; a fidget watchdog holds it still for 60 s if it bobs/spins in place for
10 s (survival reflexes still run). Each search has a `plantime` budget; below
`lagtps` it looks ahead less often and plans gently. Baritone's own chat commands (`#…`) exist but
Zymbot doesn't use them — drive it through `/zbot`.

## Testing checklist

1. Ask before starting anything. `./sync-bots.sh --check` if the pack may have changed.
2. Owner hosts; `./standby.sh bot1` in the background; owner does `/zbot summon` (or you
   `./connect.sh bot1 127.0.0.1:25565 --wait`).
3. Give orders with `./send.sh bot1 / zbot …`; confirm via `[decision]` lines, not assumptions.
4. Record results in PHASE1.md / PHASE2.md / FIXLIST.md (living docs); stop the bot when the owner says.
5. The owner's working style: while testing, code/doc edits go to a background subagent ("write,
   don't compile") so testing continues; once the game is closed, build, test, swap jars and commit
   yourself, inline — no agent.
