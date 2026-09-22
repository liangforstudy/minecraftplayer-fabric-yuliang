# Headless bot client

Runs the ZymCiv 1.21.1 modpack with no display, via
[HeadlessMC](https://github.com/headlesshq/headlessmc) 2.10.0 (MIT).

## Result: it works

**All 84 mods load and the client reaches the title screen headless on an M2 Mac**
(83 from the pack + one compat patch of our own).
No Xvfb, no virtual display — HeadlessMC stubs LWJGL, so it's a plain JVM process.

### Supplementaries — solved

Supplementaries and suppsquared crashed at boot: `SpriteUtils.parsePaletteStrip` reads pixel
data from a texture, and with STB stubbed every image decodes empty, so it throws
`has too few colors! Expected at least 13 and got 0`.

No config avoids it — real assets, `dynamic_assets_generation_mode = NO_OP`,
`building.globe.enabled = false` and `hmc-optimizations` 0.4.0 all still crash, because the
call is unconditional on resource reload.

**Fixed by [`../moonlight-headless-patch/`](../moonlight-headless-patch/)** — a standalone
~5 KB Fabric mod that returns a placeholder palette when headless. The **full 84-mod pack now
boots with Supplementaries enabled.** Drop its jar in `gamedir/mods/`.

Cobblemon and GeckoLib, the two I expected to break, were fine throughout.

## Memory

| `-Xmx` | result |
|---|---|
| 1.5G | **OOM** |
| 2G | loads without OOM, RSS 2270 MB — not confirmed to full boot |
| **3G** | **boots fully**, RSS 3492 MB |

So budget **~3 GB per bot**. On 16 GB that's roughly two headless bots alongside a local
server and a GUI client, or three without the GUI client.

Render distance is set to 6 in `gamedir/options.txt`. Remember this is the bot's
**perception radius**, not just a memory dial — lowering it slows scouting.

## Usage — two bots joining your world

Two isolated rigs, **`bot1/`** (Bot1) and **`bot3/`** (Bot3) — separate game directories and
separate offline usernames, so they don't overwrite each other's files or kick each other off.

```bash
./setup.sh                  # once per machine: finds Java 21 + Prism, writes rig configs, installs Fabric
./sync-bots.sh              # ALWAYS first — match the bots to your current Prism pack
./run-bot.sh bot1 3G 127.0.0.1:25565
./run-bot.sh bot3 3G 127.0.0.1:25565
./stop-bots.sh
```

### Standby, then connect — skip the 90-second boot on "go"

```bash
./standby.sh bot1                          # boot to the title screen and wait (~1 min)
./connect.sh bot1 127.0.0.1:25565          # join now — seconds, not minutes
./connect.sh bot1 127.0.0.1:25565 --wait   # or: join by itself the moment the world opens
./gui.sh bot1                              # what's on its screen right now (buttons, text)
./send.sh bot1 click 0                     # any hmc-specifics console command
./disconnect.sh bot1
```

This uses **[hmc-specifics](https://github.com/headlesshq/hmc-specifics)** (HeadlessMC's own
companion mod, MIT; `hmc-specifics-1.21.1-2.4.0-fabric-release.jar`, verified against GitHub's
SHA-256 digest), which turns HeadlessMC's console into a remote control: `connect`, `disconnect`,
`gui`, `click`, `msg`, `/`. The sync adds it to every rig; the host doesn't need it.

`bots.py` launches through a small **console relay**: a background process that owns the game's
console and accepts lines on **127.0.0.1 only**, with a random token in `<rig>/console.json`
(readable by you only). Nothing on another machine can reach it. The console is a pipe, not a
terminal, so JLine is switched off (`hmc.jline.enabled=false`) — with it on, hmc-specifics crashes
the game at startup ("Failed to start JLineCommandLineReader").

`sync-bots.sh` refuses to touch a running rig — stop it first.

### Team key

`setup.sh` creates `team-key.txt` (gitignored) and writes it into every rig's `zymbot.json`, so
your bots sign and encrypt their messages with the same key and can hear each other.
`./team-key.sh` shows it — paste it into your own Prism instance's `config/zymbot.json` to make
your client part of the team.

On **Windows** it's the same with `.bat` (`setup.bat`, `sync-bots.bat`, `run-bot.bat bot1 3G
127.0.0.1:25565`, `stop-bots.bat`). All four are one-line wrappers around **`bots.py`**, which is
the same code on every OS — see [Moving to another machine](#moving-to-another-machine-windows).

**Run `sync-bots.sh` before every test.** Each rig mirrors a Prism instance named in
`<rig>/.source` (default: Minimal) — **Bot1 mirrors `…Server Full`**, the instance your world is
hosted from. Mods whose id is in `sync-exclude.txt` (performance, visual-only and UI mods) are
never copied. A rig containing an `.unsynced` file is skipped and flagged — **Bot3 is held back
this way** until it joins. The host must run the same pack the rig mirrors, or it gets
connected-then-dropped.

 The ZymCiv pack updates often — three times on
2026-09-21 — and a version mismatch on the server's own mods (`civfabric`, `zymlabs-rules`) will
break or desync the join. It mirrors Prism's mods and config into each rig and re-adds the
headless patch; `./sync-bots.sh --check` reports drift without changing anything.

`run` auto-joins via vanilla quick-play (`--quickPlayMultiplayer`) and reports one of:
`IN THE WORLD`, `REJECTED by server`, `could not reach`, `MOD MISMATCH` (names the missing mods), `CONNECTED THEN DROPPED`, or `CRASHED`.

`stop` only matches processes stamped `minecraft.launcher.brand=HeadlessMc`, so it
**never touches your Prism client** — which also runs through Fabric's `KnotClient`, and would be
killed by a naive `pkill`.

### Hosting: the world must be offline-mode

A vanilla LAN world is **always online-mode**, whatever account the host is signed in with —
even a Prism Offline account. Offline headless bots are refused (*Invalid session*).

**Install OfflineLAN in the host instance** (Modrinth, host-only) and turn online mode **off** on
the Open to LAN screen. Chat confirms it: *"Online mode is OFF!"*. Details in
[Joining a LAN world](#joining-a-lan-world--what-it-took-2026-09-22-first-successful-join).

Then in-game: **Open to LAN → port `25565`.**

**Allow Cheats is optional for a connection test.** It's needed later, to assign the bots'
classes: setting another player's class uses civfabric's edit commands, which require op
(permission level 2, `civfabric.class.edit`), and the bots can't pick their own until the bot mod
exists. Ops do **not** bypass class gates — the only op check in civfabric's class system is that
edit permission — so cheats don't skew the host's side of a test. Vanilla only lets you choose it
when opening to LAN, so changing it means reopening the world.

### Memory on 16 GB

`-Xmx` is the heap *cap*; a process's real footprint (RSS) runs higher. Measured at the title
screen: `-Xmx3G` → ~3.5 GB RSS.

| plan | heaps | est. RSS | + macOS ~3–4 GB |
|---|---|---|---|
| 4 / 4 / 4 | 12 GB | ~14–15 GB | **over 16 GB — will swap** |
| **3 / 3 / 5** | 11 GB | ~13 GB | tight, workable |

**Prism needs the most, not the least** — hosting a LAN world means it runs the integrated
server for all three players *inside* its own process, on top of being a client. So give Prism
**5 GB**, bots **3 GB** each (verified to boot). Raise a bot to 3.5 GB only if it runs out of
memory once it's actually in the world — title-screen memory isn't in-world memory, and that's
still unmeasured.

Watch **Activity Monitor → Memory → Memory Pressure** during the test. Yellow is fine; red means
it's swapping hard and a bot should come down.

## Layout

| path | what |
|---|---|
| `headlessmc-launcher-2.10.0.jar` | the launcher (gitignored) |
| `bot1/`, `bot3/` | one rig per bot: `HeadlessMC/config.properties` + `gamedir/` |
| `bots.py` | **all the logic**: `setup`, `sync`, `run`, `stop` — cross-platform |
| `*.sh` / `*.bat` | one-line wrappers (macOS-Linux / Windows) |
| `sync_bots.py` | the sync rules: mirror each rig's `.source` instance, minus `sync-exclude.txt`; `--check` reports drift |
| `sync-exclude.txt` | mod ids never copied to a bot |
| `<rig>/.source`, `<rig>/.unsynced` | which instance a rig mirrors; opt a rig out of syncing |
| `moonlight-headless-patch-1.0.0.jar` | prebuilt copy of the patch, used when the build output isn't there |
| `hmc-specifics-*.jar` | console remote control for the running bot (downloaded, gitignored) |
| `team-key.txt`, `<rig>/console.json` | shared team key; the running relay's port + token (both gitignored) |
| `<rig>/identity.properties` | the bot's name, UUID and fixed HeadlessMC flags (tracked) |

Version files install to `~/Library/Application Support/minecraft/` (created fresh — no
pre-existing vanilla install was touched). Fabric loader here is 0.19.5; the Prism instance
uses 0.19.3.

## Config

```properties
hmc.gamedir=<this dir>/gamedir
hmc.assets.dummy=false     # true breaks mods that read texture pixels at boot
hmc.always.lwjgl.flag=true
hmc.offline=true
hmc.java.versions=<temurin-21>/bin/java
hmc.auto.download.java=false
```

## Moving to another machine (Windows)

Nothing machine-specific is stored. Every `setup`/`run` detects the paths and **rewrites each rig's
`HeadlessMC/config.properties`** (`hmc.gamedir`, `hmc.java.versions`), so a copied folder just works.

1. Copy the **whole repo folder** (the patch jar is included prebuilt in `headless/`, so no Gradle build).
2. Install **Python 3** (python.org — tick *Add to PATH*) and **Java 21** (Temurin, adoptium.net).
3. Install Prism and the ZymCiv pack there, with the **same instance name** as `bot1/.source`.
4. `setup.bat` → `sync-bots.bat` → host your world (OfflineLAN, online mode off) → `run-bot.bat bot1 3G 127.0.0.1:25565`.

What gets detected, and the override if it guesses wrong:

| thing | Windows | macOS | override |
|---|---|---|---|
| Prism data | `%APPDATA%\PrismLauncher` | `~/Library/Application Support/PrismLauncher` | `PRISM_DIR`, or `headless/prism-dir.txt` |
| Java 21 | `Program Files\{Eclipse Adoptium,Java,Microsoft,Zulu,…}`, Prism's own runtimes, PATH | `java_home -v 21` | `BOTS_JAVA` |
| game files | `%APPDATA%\.minecraft` | `~/Library/Application Support/minecraft` | — |

`setup` installs Fabric `0.19.5` for `1.21.1` via HeadlessMC if it's missing; the first launch then
downloads the game's libraries and assets. `.properties` files treat `\` as an escape, so paths are
written with `/` — Java accepts that on Windows. `stop` uses PowerShell on Windows, `pgrep` elsewhere.

**Untested on Windows so far** — written for it, verified on the Mac only. Expect the first run
there to find something.

## Joining a LAN world — what it took (2026-09-22, first successful join)

1. **Vanilla LAN is always online-mode.** `IntegratedServer` calls `setOnlineMode(true)` no matter
   which account the host uses, so an offline bot gets *Failed to log in: Invalid session* and hangs
   up (the host only logs `lost connection: Disconnected`). Fix: **OfflineLAN** in the *host* instance,
   Open to LAN with online mode off. It's host-only, so it's in `sync-exclude.txt`.
2. **"Client-only" mods can still be required.** An integrated host runs its own client mods, and
   anything they put in a synced registry is sent to joiners. Cosy Critters (particle types) and
   Xaero's Minimap / World Map had to stay on the bot. `sync_bots.py` auto-keeps what it can detect;
   Xaero slipped past the heuristic, so `run-bot.sh` now exits 6 and names the namespaces when Fabric
   reports *registry entries that are unknown to this client*.
3. A dedicated server (client mods not loaded, `online-mode=false`) avoids both — at ~3–4 GB more RAM.

## Known rough edges

- `mod list` crashes on a jar whose `fabric.mod.json` has object-form `authors` — a HeadlessMC
  parser bug. Manage mods by moving files instead.
- The launcher warns it isn't running from `headlessmc-launcher-wrapper`, so no plugin support
  or in-memory launching. Hasn't mattered so far.
- The launcher is an interactive REPL; drive it with piped stdin (`printf 'cmd\nexit\n' | java -jar ...`).
