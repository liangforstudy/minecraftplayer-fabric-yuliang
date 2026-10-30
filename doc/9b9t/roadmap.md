# 9b9t roadmap (draft)

Status: **plan, 2026-10-25**, from talks with the owner. Built so far: §0 Phase 0 steps 1–3 (both
versions, the profiles); everything from §2 on is still a plan.

Why: ZymBot (`lol.zym.zymbot`, a separate project) is further up the ZymCiv milestone ladder than
this repo ([BOT_BEHAVIOUR.md](../../BOT_BEHAVIOUR.md#the-milestone-ladder)). So Zymbot also learns
jobs for **9b9t**, an anarchy server: ice for boat highways, staircase mapart, routes, and later
fights with withers and wardens.

**9b9t allows bots** (owner, 2026-10-25). Mapart is built at a safe spot far from spawn.

## 0. Versions and builds

| Server | Server version | Client we build | Profile | Modules |
|---|---|---|---|---|
| ZymCiv | 1.21.1 (modded) | 1.21.1 | `zymciv` | pack loader, class XP, milestone ladder, ... |
| 9b9t | 26.2 | **1.21.11** (joins through the server's version support) | `anarchy` | routes, ice miner, mapart, fights |

- Stonecutter gets a second version, `1.21.11`, beside `1.21.1` (`mod/settings.gradle.kts`,
  `mod/stonecutter.properties.toml`).
- `core` stays plain Java, **built once and shared** by both versions. General reflexes (interrupt
  stack, drowning, stranded, fidget watchdog, `BreakTask`, `PlaceTask`, decision log) serve both.
- **26.2 client build: built 2026-10-28, live on 9b9t (goto, daybreak), offline checks passed
  2026-10-29.** Third Stonecutter version
  beside 1.21.1 and 1.21.11 (profile `anarchy`, like 1.21.11). Done:
  - Build: loom-back-compat 0.4.3 with both Loom plugins (1.19.3) declared in
    `stonecutter.gradle.kts`, Gradle 9.8.1 run on JDK 25 (`gradle/gradle-daemon-jvm.properties`),
    Java 25 for 26.x and 21 for the rest. 26.2 deps: Fabric API 0.161.0+26.2, Mod Menu 20.0.3,
    Baritone API 1.19.0 (`headless/baritone-api-fabric-1.19.0.jar`, gitignored, copied from the
    owner's 26.2 instance). Set up as in the owner's Chest Tracker port.
  - Renames as Stonecutter replacements in `build.gradle.kts` (26.2 only): `ClientCommands`,
    `KeyMappingHelper`, `ContainerInput` / `handleContainerInput`, `mc.gui.screen()` /
    `gui.setScreen`, `mc.gui.hud.getBossOverlay()`, `GuiGraphicsExtractor` / `extractRenderState` /
    `text` / `centeredText`. Real changes in `McCompat` (`say`, `dayTime` = overworld clock,
    `publishLan` with `MultiplayerScope.LAN`) and a `//?` block for the tooltip. The settings
    boxes drop non-digits in `insertText` on every version (26.x's EditBox has no `setFilter`).
  - `fabric.mod.json` depends on `${key_module}`: `fabric-key-mapping-api-v1` on 26.x (the first
    launch failed on the old name, which compiling doesn't catch).
  - All three jars build, 250 core tests pass. In the owner's `26.2` instance (Meteor 26.2-35,
    Baritone 1.19.0) it loads: "profile anarchy", no mixin errors at startup.
  - **Offline checks, 2026-10-29** (9b9t down; over MCP Fabric on port 25602, singleplayer "New
    World"), all pass:
    - Mod Menu → Zymbot: the three anarchy settings (critical, plan time, lag TPS). Typing "x2" keeps
      only the "2"; dragging the range scrubs and stops at the cap (19); ↺ resets; tooltips show.
    - `probe tps`: Zymbot 19.6 from the game time, +20 ticks per update with no jumps (so 9b9t's jumps
      are the server's, not 26.2's); Meteor's TickRate read 15.8 right after the world loaded, 19.9
      a minute later. `probe ack`: a break confirmed in 56 ms. `probe meteor` lists the modules.
    - `daybreak`: "day: hostile mobs start spawning in the open at 10:01:30 (in 13 min 9 s)" from
      time 2052, with the pace from the TPS meter until the clock has 30 s.
    - `/zbot lan`: opened on 25565; `tools/mcping.py 127.0.0.1:25565 776` answers "26.2, players 1/8".
    - Death (`/kill`) and respawn: status shows "respawned — because died at … -48, 64, -197".
      Status read right at respawn says health 0 for one line; the next one is right.
    - Status prints "critical ≤12" with the setting at 8: danger "hard" adds 4 (`criticalFor`). The
      settings screen shows only the base value; worth a hint there.
    - MCP Fabric's `get_recent_chat` returns nothing on 26.2 (our lines go through
      `sendSystemMessage`); read chat by screenshot. That's MCP Fabric's, for its owner.
  - Emergency stop key (bound to `]` in the owner's instance), 2026-10-29: pressed in the middle of a
    `goto`, the walk stopped at once ("the controls are yours again — emergency stop key", then
    "Zymbot stopped, Baritone cancelled"). Also with the inventory open mid-walk (swimming):
    stopped at once, same lines, position held after.
  - Start key (owner, 2026-10-29): Options → Controls → Zymbot → "Start", unbound until set. It does
    `/zbot start`. Bound to the same key as the emergency stop, that key toggles: stop while the bot
    runs (or holds a borrowed order), start while it doesn't. Passed 2026-10-29: both on `]`, presses
    went stop, start, stop, start. Fixes after: the long label ran into the key button (now "Start
    (/zbot start)"), and on a shared key the stop line says "press ] again" instead of `/zbot start`.
- **9b9t's version, checked 2026-10-26:** **26.2, with 1.21.4 and newer accepted.**
  - `tools/mcping.py` (server-list ping, no login; follows the `_minecraft._tcp` SRV record, since
    2b2t.com's plain address is a web IP): the version name is "9b9t 26.2" whatever we send; protocols
    774 (1.21.11), 776 (26.2) and 777 (26.3) are echoed back, i.e. accepted; 340 (1.12.2) gets −1,
    refused. `preventsChatReports` is set. Pings sent back to back time out (rate limit); 15 s apart
    they answer.
  - Inside, with the owner's 26.2 client: the tab footer says "Current version: 26.2 (1.21.4+
    accepted)"; F3 shows the brand only as `"9b9t" server` (the software is hidden).
  - So our 1.21.11 client goes through version translation (ViaVersion/ViaBackwards or the like).
    The game time that stays put and then jumps (§Probes) is likely that translation; a 26.2 client
    build would skip it.
- **What the other mods support, checked 2026-10-26** (for choosing what a 26.x Zymbot leans on):
  - **Baritone:** branches 26.1, 26.2, 26.3; 1.19.0 is 26.2, release 1.20.0 is 26.3. Keeps up.
  - **Meteor Client:** master is on 26.3. Keeps up, but supports only its newest build: the site
    lists 26.3. The owner's `26.2` instance already runs Meteor 26.2-35 with Baritone API 1.19.0
    (Meteor's own `baritone-meteor-26.2` there is disabled), so a 26.2 Zymbot would sit next to an
    unsupported but working Meteor. Fine for us (owner, 2026-10-26).
  - **Lambda** (lambda-client/lambda, GPL-3.0, active: commits 2026-10-22): **1.21.11 only**, no 26.x
    branch or issue. It has skipped versions before (branches 1.20.4 → 1.21.5 → 1.21.11). Its
    `HighwayTools` and its `Printer` (places blocks from a loaded Litematica schematic; reads
    Litematica's `DataManager` / `SchematicWorldHandler`, so Litematica + MaLiLib must be installed)
    would fit the highways and mapart, but **nothing in Zymbot should depend on Lambda**: a 26.x
    Zymbot would lose it. Highways: Meteor's HighwayBuilder (§2). Mapart: Zymbot's own placer
    reading the Litematica placement itself, as Lambda's Printer does (§4).
  - 9b9t also accepts 26.3 clients (protocol 777 echoed), so the 26.x build could follow Meteor to
    26.3 instead of 26.2, at the cost of going back through translation.

### Profiles (decided, owner, 2026-10-25)

Stonecutter only builds the adapter (`mod/src`) per version; `core` is shared, so ZymCiv code can't be
left out of it at compile time (not without making `core` per-version too, which isn't worth it).
Instead, **Stonecutter sets a profile per version** (`zymciv` for 1.21.1, `anarchy` for 1.21.11), the
adapter passes it to `Bot`, and `Bot` wires only that profile's modules. The ZymCiv code is in the
jar but never runs on 9b9t.

**Team features parked in ZymCiv too (owner, 2026-10-29):** the team bus, the chat check and regroup
are off in the 1.21.1 build as well, for now. `McCompat.TEAM_PARKED` (true) calls `Profile.parkTeam`
at startup; the log line says "(team features parked)". `summon`, `see <bot>`, `watch`, `roster` and
`regroup` answer that they're switched off. Core's tests still cover the features (parked only in a
test of its own). Back on: set `TEAM_PARKED` false. In place on headless Bot1 (2026-10-29); the
owner's own 1.21.1 client is still 0.1.48.

| Off in `anarchy` | Why |
|---|---|
| Knocked out (`DownedInterrupt`, `DownedTask`) | civfabric's knocked-out state; vanilla has none |
| Pack loader (`pack/`) | ZymCiv's data |
| Milestone ladder (`ladder/`) | ZymCiv's progression |
| Danger mode `modpack` | ZymCiv's mobs; anarchy defaults to `hard` |
| Regroup, leash, follow-the-teammate | team play; regroup can walk to **world spawn**, the worst place on 9b9t |
| Chat handshake, `!lf`, `!where`, sharing coordinates | public chat on an anarchy server; never answer coordinate questions |
| Local bus, team key | bot-to-bot team messages |
| Zymbot's own eating | Meteor's AutoEat instead (below) |

Kept: drowning, hazard, critical health, stranded and wading interrupts, fidget watchdog, `RouteTask`,
`BreakTask`, `PlaceTask`, decision log, commands, emergency stop.

### Settings in the `anarchy` profile (draft, for the owner to review)

**Works out of the box (owner, 2026-10-26).** In the `anarchy` profile nobody has to list their account
or a server first: the settings for that are hidden and confuse players. By default Zymbot is active
**on every server, for the account that's playing**. A blacklist / whitelist of servers comes later,
as an option.

**"Active" means standby and ready** (owner, 2026-10-26): switched on, not disabled, waiting for
orders. It **never takes control on join**; it takes control only when given a job (`/zbot start`, a
route, the ice miner, ...).

Settings screen:

| Tab / setting | What it does | ZymCiv-only? | Anarchy profile |
|---|---|---|---|
| **Team key** tab | encrypts bot-to-bot messages (local bus, chat handshake) | yes: both are off on 9b9t | **hide**: off for now (owner, 2026-10-25) |
| **Accounts** → Bot | which account the bot plays | no | **hide**: the playing account, by default (above) |
| **Accounts** → Teammate | "tells your bots you're on the team" | yes: no team features | **hide** |
| **Servers** tab | where Zymbot is active, On / Off | no | **hide** for now: every server, by default (above); a blacklist / whitelist later |
| Body → **Leash** | max blocks from the nearest teammate | yes | **hide** |
| Body → **Follow: stop this close** | follow distance to a teammate | team-based | **hide** for now |
| Body → **Eat when hunger ≤** | Zymbot's eating | no, but Meteor's AutoEat replaces it | **hide** while AutoEat is used |
| Body → **Critical health** | heal or retreat threshold | no | keep |
| Body → **Knocked out: wait for a human** | civfabric's knocked-out state | yes | **hide** |
| Body → **Regroup** | walk back to teammates | yes (and it can walk to world spawn) | **hide** |
| Body → **Wait for a teammate's answer, then spawn** | part of regroup | yes | **hide** |
| Body → **Route search time limit** | path planning budget | no | keep |
| Body → **Server lagging below (TPS)** | plan less when the server lags | no | keep |
| Help text: "No natural regeneration here…" | ZymCiv rule | yes, and **wrong on 9b9t** | **change** to profile-specific text |

Only in commands or `zymbot.json`:

| Setting | ZymCiv-only? | Anarchy profile |
|---|---|---|
| `danger` = `modpack` (run at the first hit) | `modpack` is ZymCiv | default **`hard`**, no `modpack` option |
| LAN: auto-summon, auto-open LAN worlds, LAN port | for testing in the owner's worlds | off; open: keep for local tests? |
| `!lf`, `!where`, sharing coordinates with humans | chat features; chat is off | **hide** |
| `neverEat` (includes rotten flesh and chorus fruit) | ZymCiv list | an anarchy default list, for when we write our own eating |

### Phase 0 plan

Checked in a cloud container on 2026-10-25:
- Today's 1.21.1 build compiles and all tests pass.
- Everything 1.21.11 needs is published: **Baritone 1.17.0** (built from the official `1.21.11`
  branch), **Fabric API 0.141.6+1.21.11**, **Mod Menu 17.0.1**. Java 21 is still enough.
- A trial compile against 1.21.11 (not committed): **about 44 errors, all in 6 adapter files;
  `core` has none.** All are known renames, fixed with Stonecutter `//? if` blocks:

| File | Errors | What changed in Minecraft |
|---|---|---|
| `ZymbotSettingsScreen` | ~16 | mouse and keyboard handling rewritten in 1.21.9 (`MouseButtonEvent`), `hasShiftDown` moved, tooltip API |
| `FabricWorldView` | ~17 | `getMinBuildHeight` → `getMinY`; NBT getters return `Optional`; `ResourceKey.location()` renamed (1.21.11); `Inventory.selected` private; spawn position API; food effects moved |
| `ZymbotClient` | ~6 | key binding categories are objects, not strings; `Button.onPress` signature |
| `FabricHands` | ~4 | `InteractionResult` reworked; block-cracking call; selected slot |
| `BaritonePaths` | 1 | `GameProfile.getName()` → `name()` |
| `ServerTimeMixin` | 1 | the time packet changed |

The 4 mixins (`ContinueAttackMixin`, `ServerTimeMixin`, `MinecraftAccessor`,
`BossHealthOverlayAccessor`) also need checking in game: a renamed target method isn't a compile
error. (Their targets, `continueAttack`, `handleSetTime`, `rightClickDelay` and `events`, are all still
in the 1.21.11 jar under the same names, checked with `javap`.)

Steps:
1. **Build** (done, 2026-10-25): `1.21.11` added to `mod/settings.gradle.kts` and
   `mod/stonecutter.properties.toml`. Compiling it needs `headless/baritone-api-fabric-1.17.0.jar`
   (from Baritone's GitHub releases, gitignored like the 1.11.3 one).
2. **Port the adapter** (done, 2026-10-25): both versions compile and all 235 `core` tests pass. The
   version differences live in one class, `McCompat`; the settings screen's mouse handling and the
   emergency stop key have their own `//? if` blocks. Not yet tried in game. The settings screen
   isn't trimmed to the anarchy settings yet; that comes with step 3.
3. **Profiles** (done, 2026-10-26, not yet tried in game): `core/Profile` (`ZYMCIV`, `ANARCHY`);
   `McCompat.PROFILE` picks it per version; `Bot` creates the off-list modules (knocked out, regroup,
   leash, ladder, chat check, bus, Zymbot's own hunger reflex, graves) only under `zymciv`, and
   `ProfileTest` checks that `anarchy` creates none of them and keeps the `zymciv` reflexes as they
   were. In `anarchy`:
   - **Standby:** active on every server for the playing account, never takes control on join.
     One-shot jobs (`goto`, `come`, `debug punch`, `debug craft`, `debug place table`) borrow the
     controls like a Teammate's errand and hand them back when done or when a movement key is
     touched; standing jobs (`follow`, `eat`, `look`) still need `/zbot start`.
   - **Eating:** the hunger reflex is off (Meteor's AutoEat). `/zbot eat` stays as an order, on purpose.
   - **Danger:** `easy` / `normal` / `hard` only; a config still on `modpack` runs as `hard`.
   - **Commands** stay registered (one help text, one Tab list) but the ZymCiv ones answer "a ZymCiv
     feature — not in this anarchy build": `role`, `autostart`, `summon` (and `lan auto`, `summon
     auto`), `regroup`, `roster`, `see`/`view`/`spy`/`watch` of another bot, `grave`, and the
     tunables `leash`, `heel`, `eat`, `downed`, `regroup`, `probewait`. `/zbot lan` still opens a world
     by hand, for local tests.
   - **Settings screen:** the Body tab only (critical health, route search time limit, lag TPS),
     with anarchy help text instead of "No natural regeneration here".
   - Players coming into and going out of view are no longer logged (that was the chat check's reach;
     at 9b9t's spawn it would flood the log).
4. **Smoke test** on the owner's own Prism client (1.21.11 instance with Meteor, Baritone 1.17.0 and
   Zymbot): first a singleplayer or LAN world (join, `/zbot status`, a Baritone walk, break one
   block, emergency stop), then join 9b9t and stand idle. Plan (2026-10-26):
   - **4a. Stage 0 instance, singleplayer, Claude drives it through MCP Fabric.** With the game
     closed, swap `zymbot-0.1.49…` for `zymbot-0.2.0+1.21.11.jar`. Its `zymbot.json` still lists the
     account as a Bot with `singleplayer` on the server list, which `anarchy` must ignore. Check: the
     log says `profile anarchy`; joining logs "standing by" and doesn't start; `/zbot status` shows the
     trimmed lines and `danger hard`; `goto` and `debug punch` borrow the controls and give them back;
     `/zbot eat` and `follow` ask for `/zbot start`; with Hunger and food in the inventory it doesn't
     eat by itself; `role`, `regroup`, `roster`, `grave` and `set leash` answer "a ZymCiv feature"; the
     world doesn't open to LAN by itself; Mod Menu → Zymbot shows the Body tab only. Emergency stop:
     the owner binds the key, presses it during a `goto`, then unbinds it (MCP Fabric can't press keys).
     **Result (2026-10-26): passed**, 0.2.0 in Stage 0. Standby on join despite the Bot listing; no
     LAN; status trimmed, `danger hard`; `goto` and `debug punch` borrowed and gave back; the ZymCiv
     commands refused; running at hunger 0 with food it didn't eat by itself, and `/zbot eat` did;
     settings screen Body-only (owner's screenshot); the emergency stop key stopped a borrowed `goto`
     mid-walk (Baritone cancelled, controls given back, same tick); the key is to be unbound again after the test. Found
     and fixed: `/zbot summon` gave the LAN hint before "a ZymCiv feature" (fixed in source, after
     this jar). Cosmetic, fixed in source the same day, not yet seen in game: on 1.21.11 a setting's
     tooltip showed its line break as an `LF` box (now split into lines before
     `setTooltipForNextFrame`), and "50–5000" ran under the ↺ button (the range column is now as wide
     as its widest text, laid out from the right). **Both seen fixed in game** (0.2.1 on 9b9t, owner's
     screenshots, 2026-10-26).
   - **4b. The 9b9t instance, offline.** Add `zymbot-0.2.0+1.21.11.jar` beside Meteor 85 and Baritone
     1.17.0 (Meteor 85 doesn't bundle Baritone, checked 2026-10-26, so there's one Baritone). The owner
     starts it and opens a singleplayer world: no crash, Zymbot and Meteor both load, `/zbot status`
     says `pathfinder: Baritone 1.17.0`, the settings screen opens. Meteor's AutoEat is the eater from
     here on.
     **Result (2026-10-26): passed**, except the settings screen, not yet looked at. The jar is the build
     with the tooltip, range-column and summon fixes. In world `lambda testing`: no crash; the log says
     `profile anarchy` and `pathfinder: Baritone 1.17.0`; `/zbot status` shows standing by, `danger
     hard`; `/zbot summon` now answers "a ZymCiv feature" alone (fix seen in game). Harmless log noise:
     Meteor's `ComeCommandMixin` finds no target (the Baritone *API* jar has no default commands), and
     Zymbot still generates a `team_key` on first start though `anarchy` has no bus (cosmetic).
   - **4c. Join 9b9t and stand idle.** The owner joins with their own account; Claude doesn't drive the
     game there (no MCP Fabric in that instance) and reads `latest.log` afterwards. Expected: "standing
     by", no `[decision]` lines that move or act, nothing sent to chat by Zymbot (no chat check, no
     bus), `[zymbot]` errors none, and the TPS reading (the time-packet mixin) sane on a real server
     joined through its version support. A Baritone walk on 9b9t is a later, separate test.
     **Result (2026-10-26): passed**, except the TPS reading. The owner joined via the 2b2t.com entry;
     over ~3.5 min idle the only Zymbot line was `[decision] standing by — … on 2b2t.com:25565`; no
     chat sent, no `[zymbot]` errors, no mixin warnings. The TPS value can't be checked: nothing
     prints it (not `/zbot status`, not the log), so only "the time-packet mixin applies" is known.

   **Done when** 4a-4c pass. Not covered here: mob avoidance for Baritone (proposed after the skeleton
   chase in the 2026-10-26 Stage 0 test, not decided).

**ZymCiv regression (2026-10-26, headless Bot1 on 0.2.1+1.21.1, owner's client on 0.1.48): passed as
far as it went.** Loaded `profile zymciv`, started DISCOVERY by itself, surveyed, full ZymCiv status
(danger `modpack`, leash, ladder, bus, chat check), no Zymbot errors. It stood still, as the
unchanged logic says: the owner's Zymbot was silent ("singleplayer is switched off on the server
list"), so no current teammate; "work first" (2026-10-14) held the walk to spawn because the ladder
wanted a tree, and the only tree was 52 blocks off, past its 48. **Not exercised:** the team bus, the
chat check and an actual regroup (they need the owner's Zymbot talking). The first two joins timed out
(login 15 s vs ~16 s of config sync, known); the third got in.

**Done when:** both jars build from one tree, all tests pass, the 1.21.1 bot behaves exactly as
before on ZymCiv, and the 1.21.11 bot joins 9b9t and runs nothing ZymCiv-specific.

**Later, not Phase 0** (owner, 2026-10-25): a headless 9b9t rig with a Microsoft login through
HeadlessMC. Tedious, so it waits until Routes and the ice miner need it. Then `headless/bots.py`'s
built-in `MC_VERSION = "1.21.1"` becomes per rig, and it's checked that HeadlessMC can install
1.21.11.

**Version (decided, owner, 2026-10-26):** the first build for both versions is **0.2.0** (`0.2.0+1.21.1` and `0.2.0+1.21.11`).

### Shared: eating (all 9b9t modules: ice miner, mapart, fights)

- **Meteor Client's AutoEat first.** If the owner finds it unsatisfactory, we write our own.
- Zymbot already has its own eating (`HungerInterrupt`, `EatTask`, `FoodChooser`). Only one of the two
  may run at a time, or they fight over the hotbar. In the 9b9t build, Zymbot's eating is off while
  Meteor's AutoEat is in use.
- Food and thresholds come from the config (e.g. rotten flesh for the ice miner, §3).

**Why eating pauses everything else** (Meteor's AutoEat and AutoGap both pause Baritone; agreed with
the owner, 2026-10-26). It's vanilla, not lag:
- While an item is in use, the client drops attack and use clicks: no breaking a block, no hit, mid-bite,
  even when eating from the offhand (the check is "using any item", not per hand).
- A bite is 32 ticks (1.6 s) of holding use with the food selected. A hotbar change (Baritone picks its
  own tools) or a click cancels it, and the bite starts again.
- Eating slows movement to about 20% and stops sprinting, so a path planned at walking speed misses
  its jumps and replans.
- Lag makes it worse but isn't the cause: the server counts the 32 ticks, so at 10 TPS a bite takes ~3.2 s,
  and with high ping a slot change can reach the server before the client thinks the bite is done.

**So our own eating picks the gaps** (when we write it):
- mining: between blocks (one break done, the next not started), never standing over water or at an edge;
- fighting: while no hit is due (attack cooldown recovering) or after backing off out of reach; walking
  while eating is allowed but too slow to be an escape;
- our walks: Zymbot holds its own path, eats, and carries on; a pause from Meteor's AutoEat must read
  as a pause, not a failed path;
- lagging server (the TPS meter): eat earlier, at a higher threshold, since each bite takes longer.

## 1. Baritone findings (`1.21.11` branch, checked 2026-10-25)

- **No slipperiness handling.** Movement and path execution don't account for ice friction, so the
  bot overshoots and slides on ice. Zymbot does short moves on ice itself: no sprinting, stopping short
  of the target.
- **It never breaks regular ice.** `MovementHelper.avoidBreaking` has
  `b == Blocks.ICE // ice becomes water` built in, with no setting to turn it off. That holds even
  with Silk Touch. So `#mine ice` can't mine regular ice (packed and blue ice are allowed).
  → **Zymbot picks and breaks every ice block itself** (`BotDigging`). Baritone only walks, on
  solid ground.
- **Liquids** (`MovementHelper`, checked 2026-10-26; the owner suspected this):
  - **Still water: yes.** It walks through a full (level 8) water block when the block above it isn't
    liquid or a lily pad, i.e. at the surface, and it stands on water only with water above it
    (going through a submerged column) or with `assumeWalkOnWater` (a Jesus-style hack).
  - **Flowing water: no.** A block below level 8, or a full one next to a block that may be flowing,
    counts as flowing and is refused. So a source block at the edge of a current is refused too.
  - **Lava: never walked through**, still or flowing. It stands on still lava only with
    `assumeWalkOnLava`, again for a hack that makes lava solid.
  - **No Fire Resistance logic** anywhere in `MovementHelper`: with the effect it still treats lava as a
    wall. Swimming through lava under Fire Resistance would be Zymbot's own move, like the water
    crossings `RouteTask` already does itself.
  - Zymbot's walks put water on `blocksToAvoid` anyway (dry paths first); this matters for the ice
    miner's frozen-ocean pickups (§3) and the Nether.

## 2. Routes (named waypoints, A → B → C → D → E)

Baritone goes straight to one goal. Routes walk a list of points in order.

**First Baritone walk on 9b9t (2026-10-26, 0.2.1, owner watching): passed.** `/zbot goto ~-200 ~`
from x 249405 z 3404 (a beach; east, north and south water): 200 blocks west in 56 s, arriving on the
target block, controls given back. It planned past the loaded chunks (105 land + 8 water to the edge,
then 112 and 98 land as chunks loaded), swam the 8 blocks itself, and the lag back-off ran. Found:
- **A false "you touched a movement key"** on the first try: borrowed and given back in the same
  second, no key pressed. The second try ran clean. The check is "a movement key is down while
  Zymbot holds none" (`ZymbotClient.humanIsMoving`); a key still down from the window taking focus is
  the likely cause. Not fixed: the owner thinks it was a real press (2026-10-29); left as is unless
  it's seen again.
- **The lag back-off flaps**: TPS read 14–15 and 19–20 by turns (each a few seconds), so "planning
  less" / "planning normally" logged every 3–10 s, right at the `lagtps` 15 line. Wants some
  hysteresis (e.g. back to normal only above 17) so the log isn't flooded on 9b9t. It also read 1 TPS
  in the first second of the first try (just after a stall), which was fair. **Fixed 2026-10-29:**
  `RouteTask` starts lagging below `lagtps` and stops only at `lagtps` + 2 (at most 19.5); the
  look-ahead stretch follows that state. Swapped into the 1.21.11 instance and Bot1; the 26.2 jar waits for that game to close. Not yet seen on 9b9t.

**Before routes (decided, owner, 2026-10-29), in this order:**
1. **Log out** when health keeps falling (thresholds lower with a totem and golden apples carried).
   **Built and passed (2026-10-29): `log-out`, anarchy only, the first reflex**
   (`core/body/LogOutInterrupt`). Hurt in the last ~2 s at or below the line: **4** with a totem in the
   offhand (it pops once more; leaving keeps it), **6** with healing food but no totem, **8** with
   neither (critical health is 12: fight, cover, run and dig in come first). It turns off Meteor's
   Auto Reconnect (owner, 2026-10-29), then leaves as the pause menu's Disconnect does, and the
   disconnected screen and the decision log say why and where: "logging out — because health 8.0 ≤ 8,
   hurt by magic, no totem, no healing food, at 26 151 −51". Live in the 26.2 world: the first try
   crashed the game (it left mid-tick, and the rest of the tick used the player that was gone; the
   world saved); now the leaving waits for the tick's end, and the second try left cleanly. Auto
   Reconnect stays off afterwards: the owner turns it back on.
   **Where things are, from where we face** (owner, 2026-10-29: absolute coordinates don't say which
   way to turn on logging back in; the facing is kept over a log-out). The attacker and up to 3 other
   hostiles within 24 blocks, as "ahead/behind, left/right, above/below", then the facing and the
   coordinates (`core/body/Relative`). Live: "hurt by Husk (3 ahead, 6 left, 3 above); also Skeleton
   (5 right, 3 above); no totem, no healing food; facing east at 25 148 −51", matching where they
   were put; on rejoining the player faced east (yaw −90).
2. **Eating on a route**: check that Meteor's AutoEat and a Zymbot walk get along (a pause, not a
   failed path); our own eating if not.
   **Passed (2026-10-29, 26.2 world, 20 TPS):** Auto Eat on (turned on with Meteor's `.toggle
   auto-eat`, sent as chat), steak in hotbar slot 5, a 150-block `/zbot goto` with a hunger effect
   started just after. Sampled each second (MCP `player.getState`): at food 14 it switched to the
   steak, ate in about a second while still walking slowly, and switched back to slot 0; food 20.
   The walk went on and finished (150 blocks in 48 s), with no failed path, no extra replan and no
   "touched a movement key". So no eating of our own for now. Not yet seen under 9b9t's lag (a bite
   ~3 s at 10 TPS). Auto Eat's blacklist has rotten flesh on it (the ice miner, §3, will need it off).
3. **Healing and gear in the decisions**: golden apples and a totem count in the gear score; the
   critical reflex eats a golden apple.
   **Built (2026-10-29):** the gear tier (bare / basic / good by armor points) goes up one with a
   totem in the offhand or 2+ golden apples (or other healing food) carried; both together still one
   (`Combat.gear`). When that raised it, fight-or-flee's reason says so: "zombie and 1 more, basic
   armor (armor 0 + a totem)". The critical reflex already ate healing food at any hunger; now it eats
   plain golden apples before an enchanted one (the rarer, better one), which is still eaten when it's
   all there is. Live in the 26.2 world: at health 11 it ate a golden apple (3 → 2; the enchanted one
   kept), back to 20, so 26.2's food effects read as healing.
4. **Burning**: put out the ~8 s burn after touching fire (fire resistance; a water bucket in the
   overworld).
   **Built and passed (2026-10-29): `burning`, anarchy only, ahead of `hazard`**
   (`core/body/BurningInterrupt`, `core/task/PourWaterTask`). On fire, not fire resistant, not in
   water or lava: a water bucket (hotbar, or swapped in from the main inventory) poured at our feet
   looking straight down, then scooped back once out, and the old slot taken back; not where water
   boils away (the Nether). Else a fire resistance potion at or below `fireres` health (14; `/zbot set
   fireres 0` never drinks one, owner, 2026-10-29). Ahead of the hazard reflex because the water puts
   the fire block out too: in a burning forest the hazard reflex kept the turn and the burn took it
   to 11. Found and fixed on the way: the hazard reflex stepped off fire to a block whose corner still
   overlapped the fire (the body is 0.6 wide) and burned there 10 s; it now checks the body's own
   blocks and all 8 around a safe spot; and in water a fire hit no longer counts as a hazard (it had
   cut off the pour before the scoop, leaving the water out). Live, 26.2 world: a blaze fireball set
   it alight, and standing in a fire on netherrack: poured and scooped back each time, no step off.
   An enchanted golden apple gives fire resistance: then nothing is done, rightly.
   Test tips: don't light fires in a forest (a forest fire started; put out with `fill … air replace
   fire`); fire on cobblestone dies at once, on netherrack it stays.
   **Never clear fire with MCP's `world.fill` / `fill_blocks`**: they have no replace filter and filled
   four boxes round a test fire with air, a 76×72 ring of terrain from y 85 to 110 (26.2 test world,
   x 85–160, z −70 to 1, 2026-10-29; left as is). Only the game's `/fill … air replace fire`.
   **Forest fire (owner's test idea, 2026-10-29):** fire hung under the leaves at head height: the pour
   landed in that fire block and put it out, health 20. A canopy fire overhead (7×7, 4–5 up): the
   burning reflex **looped**, pour, scoop, alight again, ~50 times in 18 s, standing still, 20 → 10,
   never stepping away. **Fixed:** the hazard reflex goes first again (out of the fire, then the
   water); burned with fire within 3 blocks to the side or 8 overhead counts as a hazard, and no safe
   spot has fire that near; in water a fire hit only counts with a fire block in the body. Retest in a
   forest at 560 63 168 (9×9 canopy fire 5 up, `fill … fire replace air`): no pour loop, it stepped
   away each time it was burned, health 18–20; with overhead at 5 it stopped under the burning leaves
   (5–7 up), so overhead is now 8. The owner tried it too: "looks great enough" (2026-10-29).
5. **Dig in at the first fireball** when there's no cover, rather than looking for cover first.
   **Built (2026-10-29), not yet run live.** With a ghast or a blaze among the shooters, cover is
   looked for only within 3 blocks (`FightOrFleeInterrupt.FLYER_COVER_BLOCKS`); with none that close it
   digs in at once, then wider cover (10) if it can't dig, then backs off. Ground shooters keep cover
   first. The critical-health reflex uses the same order (`FightOrFleeInterrupt.cover`). The log reads
   "digging in — because looking for cover gave up: no cover within 3 blocks". The trigger is still
   the first fireball that **hurts**: a fireball hit back (`incoming`) doesn't count, or the walk would
   stop and dig in each time a ghast is about, in the Nether nearly always (tried, then dropped; an
   existing test of the walk going on after a return caught it). A missed return costs one hit (~3),
   then it digs in. Tests: no cover near → digs in; a wall a step away → cover there, no digging; a
   wall 6 off → digs in, doesn't walk to it. 324 core tests pass. Live test to do: open platform, full
   iron, ghast ~20 off, `incoming` letting one through (or no sword swing in reach): the first hit
   should start the dig, with no walk first.
   **Live, 2026-10-29 (new 26.2 world on this PC, Hard: a fireball is 9 + an ~7 blast in full iron,
   vs ~3.1 in the old world; 25×25 cobblestone platform at y 147–150, x −246..−222, z −83..−59).**
   - A natural ghast 20 off: two fireballs, both hit back, **the second return killed it** (died "by
     fireball" 46 blocks out, "1° off our aim and 3° off the ghast"): the first kill by a return. No damage.
   - A forced hit (a fireball summoned 2 blocks off, owned by the ghast, too close to swing at): 20 →
     ~5 in the one hit, then **"digging in at −231 150 −71" the same second, no walk to cover**: item 5
     works. But it dug all three blocks from 0.22 off the hole's middle, and a body 0.6 wide rested on
     the next block: it **never dropped in**. 4 s later the next fireball's `incoming` cut the dig short
     (not yet "hiding": still at the surface), and every retry failed "can't dig air under us" (its own
     hole), falling to wide cover (none) and backing off (done at once, the ghast past 32): 7 loops in 7 s,
     until the ghast drifted to 63. It took no more hits (the returns held).
   - **Fixed:** with the hole dug and still on the rim, it steps to the middle (steer + forward) and
     lets go once in; a half-dug hole under us is fine to dig in again. Tests for both; a cover test whose
     wall was 3.5 off now has it 2.5 off (the old one only passed because the dig failed on the fake
     world's air). 326 core tests pass.
   - **Passed live (2026-10-29, game restarted on the fixed jar):** forced hit from 0.28 off the
     middle (where the last run stuck): 20 → 14.8, "digging in" the same second, "hiding in a hole —
     dug 3 down" 3 s later, down at y 148. That ghast despawned (a summoned overworld ghast past 32
     blocks may), so it climbed out after 9 s. Rerun with `PersistenceRequired:1b`, blown ~2.5 blocks
     by the blast, 0.2 off the middle in z: waited to land, dug in where it landed, down in 3 s,
     **stayed down 60 s** with the ghast about, no more damage; ghast removed → "nothing after us for
     5 s" → "climbed 3 up", the hole filled back with its own cobblestone.
   - Owner's 3-ghast run on the old jar (2026-10-29): 11 returns, mostly 1–8° off the ghast; one got
     through, it dug in at once, the next fireball cut the dig short; the critical reflex dug in again,
     got down, and with health still falling in the hole (4.3) the log-out reflex left, as designed.
   - Test notes: after a log-out and rejoin Zymbot is only standing by: `/zbot start` again before a
     test (the first run here had nothing reacting, 4 fireballs, dead). Fire from fireballs stays a
     while on cobblestone here (18 fire blocks after one run): clear with `/fill … air replace fire`.
     Summon test ghasts with `PersistenceRequired:1b`, or they may despawn mid-test. A jar copied
     over while the game runs isn't loaded by leaving and rejoining the world: quit Minecraft fully.
6. **One gold armor piece** on against piglins, when carried.
   **Built and passed (2026-10-29): `gold`, anarchy only** (`core/body/GoldInterrupt`,
   `core/task/EquipTask`; `WorldView.armor()` lists the worn pieces, head first). In the Nether (where
   piglins live; outside it they turn into zombified piglins), with no gold piece on and one carried,
   it puts on the one that costs the fewest armor points (a tie goes head first): a golden helmet
   matches an iron one (2 each), so it's usually that. Brought to the hotbar (slot 8 from the main
   inventory), used (the game swaps it with the worn piece, which lands in that slot), the use let go
   the next tick (held, the game swaps them back after 4 ticks), then the old hotbar slot taken back.
   A swap that doesn't take is retried only after 30 s. After fight-or-flee, before stranded.
   Live, 26.2 world, Nether platform at 0 100 0, full iron with a golden helmet in the main
   inventory: "putting golden helmet on — because in the Nether, no gold on: piglins attack — golden
   helmet, no armor lost", "put golden helmet on — because 1 try", the iron helmet in slot 8, slot 2
   still selected; then three piglins 6–8 blocks off for 30 s: no damage, nothing logged. 330 core tests.
   Not done: taking the gold off again outside the Nether (the iron helmet stays in the hotbar), and
   a control run without gold (piglins attacking is vanilla). Test tip: fill the floor before
   teleporting in; in creative the player dropped 20 blocks into a cave below first.

**Overworld routes at night: wait for day (decided, owner, 2026-10-29).** Somewhere safe, then go on.
**Built 0.2.2 (2026-10-29), from the plan in `doc/9b9t/deepseek suggestions.txt`, with changes:**
- *When:* from day time **12500** (`nightstop`, 12000–13179: a little before mobs can spawn at 13179,
  so the hole is dug before they come) until **22822**, first light (`nightend`, 22822–24000; 23000 =
  sunrise; earlier would mean nothing, the wait lasts while mobs spawn anyway). The suggestions file
  said 22821, but that is the last tick mobs can spawn (`Daylight`, "13179 to 22821"), so first light
  is one tick later, and whenever mobs can
  spawn in the open by day: a thunderstorm, or rain keeping a dawn dark (`core/body/Night`, from
  `Daylight`). Overworld only; never in creative or spectator. Both in `/zbot set` and Mod Menu.
- *Where:* the route stops at once if it's on its feet on dry ground; in water or mid-air the leg goes
  on until it is. The wait is in `RouteWalkTask`, so only routes stop, not `goto` or `come`; at day the
  same leg starts again from where the bot is.
- *How:* `NightWaitTask` digs in 3 down (`HideTask`) and stays until it's day and nothing is after us,
  at most 30 min. Can't dig in here, or still dark after 30 min (a long storm): it **logs out** with the
  reason, and the route carries on from the same point next time.
- *An interrupt mid-wait* (a fight at the rim) builds the route again: `HideTask` now sees it's already
  at the bottom of a 1-wide hole and waits there, instead of digging 3 more.
- Not yet tried in game.

**Night ambushes (owner, 2026-10-26):** on 9b9t a walk can get ambushed by hard mobs at night. Today
the anarchy build only reacts at low health (danger `hard`: critical health + 4) and Baritone doesn't
steer around mobs (the mob-avoidance idea from the Stage 0 skeleton chase, §0, still undecided). To
decide before long routes: avoid walking at night (wait it out somewhere safe, or a route step "wait
for day"), keep away from mobs while walking (Baritone's `avoidance` settings, or Zymbot's own
detour), and/or back off earlier than critical health on a route.

**Routes are mostly in the Nether (owner, 2026-10-26)**, with its own hostile mobs. So:
- No day or night there: "wait for day" doesn't help; mobs spawn all the time.
- The threats: ghasts (fireballs from range, knockback off edges and bridges), hoglins (hostile, hit
  hard), piglins (hostile unless the player wears gold: one gold piece is worth having on), wither
  skeletons and blazes near fortresses, piglin brutes in bastions, magma cubes.
- Lava, not water, is the hazard: Zymbot's planner is built around water crossings; in the Nether
  the questions are lava next to the path, falls into lava, and ghast knockback near edges.
- Many Nether routes run on the highways (and the boat highway, §2 above), where withers and wardens
  also turn up (§5).

**Attacked mid-route (decided, owner, 2026-10-29): fight weak mobs, back off from the rest.**
Today the anarchy build only reacts at health ≤ 12 (danger `hard`: critical 8 + 4), then eats or runs
32 blocks from the attacker (`RetreatTask`); it never hits back. Too late and wrong for the Nether. On
a route instead:
- **Scaled to the gear (owner, 2026-10-29: "I'm geared up, I can take more damage").** At the start of
  a route read the armor (points, toughness, Protection levels), totems and golden apples carried, and
  judge each mob by what its hits cost *through that gear* (the game's armor and enchantment
  reduction): a well-geared player keeps going (or fights) through hits that would send a bare one
  running. Netherite's knockback resistance (10% a piece) counts too, near lava and edges.
- Armor doesn't help with lava, falls (Feather Falling aside), the wither effect or a swarm, so those
  rules stay whatever the gear.
- React early only when the hits are big for the gear, not at the **first hit** of anything.
- **Hit back** simple melee mobs (zombies, a lone hoglin or magma cube) instead of running.
- **Back off** from the rest; from ranged ones (ghasts, blazes, skeletons) take **cover** (break line of
  sight) rather than run, since running 32 blocks doesn't escape a ghast's range.
- **Never hit a zombified piglin** (the whole group turns on us); wear a gold piece for piglins.
- Then **resume the leg** where it was.
- **Log out** if health keeps falling below a threshold, as with withers and wardens; lower with a
  totem in the offhand and golden apples on hand, higher without.
Still to settle: the "weak" list per gear, the log-out thresholds, and how this sits with Meteor's modules
(KillAura stays off: it turns the camera).

**Before the first hit (owner, 2026-10-29: "bot don't flee or fight when a zombie is within range").**
Until then `fight-or-flee` waited to be hit, so a zombie walked right up to an idle or walking bot.
Now it also starts on a hostile closing in: a walker (melee or strong) within 6 blocks in 3D, or a
ground shooter within 16 with a clear shot at us (`FightOrFleeInterrupt.closing`). The same rules
then fight or back off from it ("2 blocks off: zombie, bare armor" in the log). Flyers (a ghast
across the Nether) and leave-alone mobs (an enderman, zombified piglins) still wait for a hit. A
creeper within 5 is backed away from, as before. Passed in the 26.2 test world (2026-10-29, noon,
cobblestone platform, bare + iron sword, started): a husk 5 blocks off was fought at once and killed,
health stayed 20; two husks were backed away from before either hit (on a 25-block platform it kept
re-backing off as they followed, no damage); a skeleton behind a 3-high wall 11 blocks off was left
alone, and rushed and killed as soon as the wall was gone (health 20). Test tip: the platform is solid
to y 150, so stand players and mobs at y 151 (a tp to y 149 suffocated the player once more).

**Built, first cut for overworld mobs (2026-10-29): `fight-or-flee`, anarchy only.** A reflex after
critical health (`core/body/FightOrFleeInterrupt`, rules in `core/body/Combat`, `core/task/FightTask`):
- Kinds: **melee** (zombie, husk, drowned, zombie villager, spiders, silverfish, endermite, slimes,
  magma cubes, hoglin), **ranged** (skeleton, stray, bogged, pillager, witch, blaze), **explodes**
  (creeper), **strong** (vindicator, evoker, ravager, warden, wither skeleton, brute, zoglin, ghast,
  breeze, shulker, phantom, and anything unlisted), **leave alone** (zombified piglin, enderman,
  piglin, iron golem).
- Gear by armor points: bare < 7, basic < 15, good ≥ 15 (full iron). Melee is fought up to 1 / 2 / 4
  at once (hostiles within 8 blocks, in 3D); below critical + 4 health only good armor keeps
  fighting. Ranged only in good armor, alone, above critical + 4. Everything else, and anything that
  hit us unseen, is backed away from (`RetreatTask`); a creeper within 5 blocks is backed away from
  before it hits. Leave-alone mobs are never hit back.
- The fight: the best sword or axe in the hotbar, close in when out of reach (2.8), swing only when
  the weapon has recharged. Done when the mob is gone or 16 away; the interrupted job (a walk) carries
  on after. `fight-or-flee` is a survival reflex: it runs while holding still, and the fidget
  watchdog doesn't judge it.
- In the 26.2 singleplayer world (normal difficulty, noon; MCP Fabric): bare + iron sword vs a husk:
  sword out, killed in ~4 s, −3 health. Bare vs 2 husks: backed off ("2 mobs, bare armor fights 1"),
  then the critical retreat at 11. Full iron vs 2 husks: fought both, ~6 s, −4. A creeper summoned 3
  blocks away: backed off 30 blocks, no blast. A lone skeleton in full iron at health 14: backed off
  (needs 16). At full health, at night, on level ground: closed in and killed it in ~3 s, −4 ("a lone
  skeleton, good armor"). (Earlier skeletons "ran away": summoned onto a ledge, they fell into a cave
  20 below; and the game sat paused whenever its window lost focus — singleplayer pauses.)
- Night zombie sieges, full iron: 3 at once fought one after another (~10 s, −7); 6 at once backed off
  ("5 mobs, good armor fights 4") and outran them. **A fight now asks again every second** whether
  it's still worth it (more mobs, less health, a creeper close): a siege grows while we swing. Live:
  fought 3, then 4, and the moment a 5th joined it backed off.
- **After `/zbot start` the owner's keys only pause it while held** (owner, 2026-10-29): it takes
  over again 0.5 s after they let go, fights included; ZymCiv keeps its 10 s pause and the 3 s
  warning. Said once per start in chat. Live: held S mid-fight, "paused", then "resumed — no human
  input for 0.5s" and back to the zombie within the second.
- Found and fixed on the way: hostiles in caves 20–40 blocks below counted as a crowd (now 3D
  distance), and the fidget watchdog froze the bot one second into a fight (turning and stepping in
  looked like spinning in place).
- Not yet: cover from ranged mobs, the Nether's mobs, totems and golden apples in the gear score,
  logging out, and the route waypoints themselves. The first try was a `goto` whose route swam: a
  skeleton shot from the shore and the retreat went on through the water, which costs health and
  air. Retreats should prefer land when the walk is wet.
- **Nether test, 2026-10-29** (26.2 singleplayer, full iron + iron sword, keepInventory on, MCP Fabric):
  - The bot walked into the owner's portal (`/zbot goto` to the portal block); a 40-block `goto`
    across the Nether: route planner 39 land, Baritone walked it in 15 s, y 63 → 47, no damage.
  - Magma cube (size 2): fought, it split; each small one fought as it hit (only after a hit, never
    before). Lone hoglin: fought, killed in ~4 s, −7. Piglin, no gold: backed off, never hit back,
    lost it after ~70 blocks (−5). Zombified piglin made angry (we hit it once): never hit back, backed
    off — but the angry group kept pace and the retreat led into skeletons: down to 8.5 when stopped.
  - Skeletons (a soul sand valley next to the portal): fought a lone one, backed off when a second
    joined — and then took arrows all the way, down to 8 when stopped. **Running doesn't shake
    shooters** (as expected): cover is needed, or fight back when cornered.
  - The retreat picks its way by the attacker only: it ran into other mobs twice. It should steer
    away from every hostile in sight, not just the one that hit.
  - Ghast: inconclusive (summoned into the terrain, never shot).
  - The fidget watchdog called 10 s of pure turning while idle "spinning in place" (739°, 0 blocks):
    likely the owner looking around with the mouse, which isn't counted as human input. To check.
  - Ghast, retried in the open (owner's clear spot): two natural ghasts joined it; four fireball hits
    took it from 20 to 0 in ~10 s ("walked into fire while fighting Ghast"). Then a ghast that had
    drifted through the portal fireballed it again at the overworld bed, and the bed was blown up.
    The reflex barely reacted: it only counted an attacker within 16 blocks, and ghasts shot from 20–40.
- **Retreat fixes after the Nether test (2026-10-29, built and tried live, below):**
  - **Ground shooters are rushed** (skeleton, stray, bogged, pillager, witch) when there are no more
    of them in range than the armor fights (1 / 2 / 4) — running only gives them more shots.
    Otherwise, and for **flyers** (ghast, blaze) always: **take cover** (`CoverTask`): the nearest spot
    within 10 blocks (±3 up or down) where a block cuts every shooter's line of sight (`clearShot`,
    the collision shape), wait there while they're about, look again every second; with no cover in
    reach, back off as before.
  - **Shooters count from their own range**: ground shooters 24 blocks, flyers 64 (was 16 for all).
  - **Backing off picks its way out of 16 directions**: heading toward any hostile within 24 blocks
    costs (nearer and more head-on, more; the attacker double), away from the attacker gains a
    little, and the ground counts: nowhere to stand within 3 up or down at 3, 6, 9 or 12 blocks out (a
    cliff face, a Nether wall, a drop) costs, the sooner the more, and lava along it costs most. Before,
    adding "away" pushes ran straight at a mob ahead; and a straight line in the Nether runs into walls
    and over lava lakes, which Baritone could only route round toward a goal we'd already picked.
  - Tests: walls give cover from two skeletons and a ghast; open ground backs off; a mob ahead is gone
    round; a cliff face and a lava lake are steered clear of. 283 core tests pass.
  - **Tried live in the Nether (full iron):** a lone skeleton: "rush it", killed in ~3 s for barely a
    heart. Four at once (then the rule was "good armor rushes 4"): rushed, 17 → 11 in 3 s, then the
    critical-health reflex ran the old way and took more arrows. So: **at most 2 shooters rushed in
    good armor, 1 otherwise**, and **critical health takes cover too** when shooters are about
    (anarchy only; ZymCiv keeps its retreat). Then three skeletons: "3 shooters, good armor rushes 2
    — cover"; skeletons walk round a wall (up to ~5 blocks), so cover now also **hits a shooter that
    comes into reach**, and the walk to cover is dry only (no lava, no lake). It lost 6 (was 10 for
    the same setup) and came out 30 blocks off, past their range. A ghast after that never fired in
    30 s (no line of sight where it stood), so cover from fireballs is still untested live.
    285 core tests pass.
  - Ghast summoned 8 blocks off, in the open: hit once (−2), "3 shooters … — cover" (the ghast and two
    skeletons); the ghast was dead within 15 s (skeleton arrows or its own fireball), so still no clean
    fireball test. Natural skeletons kept coming (five, likely a soul sand valley): at 12 it looked
    for cover again, found none within 10 blocks on that open ground, backed off, and gave up after
    20 s no further away, at 5.9 when stopped. **Open ground with no cover is the case left**: it's
    where hiding (tower up, wall in, dig in, below) is needed.
  - **Ghast alone, at the owner's open spot** (skeletons cleared first; ghast 10 blocks off): "ghast
    shoots from the air — cover", but no cover within 10 on open netherrack, so it fell back to backing
    off — which ended at once, since the ghast was already 32 away (backing off is "clear" at 32). Its
    fireballs set the netherrack alight; the hazard reflex found nowhere clear within 5 and gave up
    quietly, and it stood in the fire: 20 → 0 in ~8 s, "went up in flames". Fixed: the hazard reflex
    looks 12 blocks out when nothing within 5 is clear (netherrack fire never goes out). Still open:
    **a flyer with no cover in reach** — backing off can't escape a ghast; hiding (dig in, wall in) is
    the answer, next.
- **Hiding, first cut (2026-10-29): dig in, tower back out** (`core/task/HideTask`). Dig the 3 blocks
  under our feet (best tool), stand in the 1-wide hole until nothing is after us for 5 s (shooters in
  range, hostiles within 8; at most 3 minutes), then tower out: jump and put the dug blocks under
  ourselves, one a jump, back to where we stood. Never into lava or water beside the hole, a drop
  below, or through bedrock, obsidian, a chest or a portal (`canDigIn`, checked where it starts).
  Reflexes now chain fallbacks (`Fallback`): **cover, else dig in, else back off** for shooters and
  flyers (critical health too, anarchy only); **back off, else dig in** for crowds that can't be
  outrun. Wall-in is left for later: 8 blocks placed on the sides, and the hands place on top faces
  only today. Tested in the fake world (dig, wait, climb out with the dug blocks; the safety checks;
  a ghast on open netherrack digs in). Not yet seen live: every live ghast run found cover first.
- **Hostiles and players are seen out to 64 blocks** (was 32 for everything): the ghast at 33–40
  had dropped out of sight mid-fight, so cover ended and the critical reflex found "nothing to run
  from"; it then died again, probably knocked into lava by a fireball. On 9b9t players are as
  dangerous (owner). Live after: the ghast at the owner's spot, cover twice, −4, survived.
- **Digging in, live on a flat test platform** (overworld, 25×25 at y 147–150 over 13..37, −63..−39,
  made for this; netherrack first, then cobblestone since a ghast's fireballs crater netherrack and so
  made cover; an iron pickaxe given): **it digs in** — three down (151 → 148), the 3 cobblestone picked
  up, and the fireballs stopped landing. But: cover wandered 22–30 s first (20 → 7), so cover now
  gives up after 8 s in sight in all; and the hide ended within a couple of seconds of digging, leaving
  it idle in the hole with no climb. Fixed what could be found: the critical reflex leaves a dug-in
  bot alone unless it can eat (`Body.hiding`). The cause of the early end isn't in the log (it ended
  "done", not failed): next, log each fallback step and hide stage, and run it again. The climb out is
  tested in the fake world only. A flaky core test seen once: `HandshakeTest.aBotWithAnotherKey…`
  (passes alone; likely the shared parked-team static under parallel tests).
- **Logged and run again (2026-10-29): the whole hide works live.** Each fallback hand-over and hide
  stage now goes to the decision log (`Body.note`). What the runs showed, each fixed in turn:
  - Cover ended "done" whenever the ghast hovered at the 64-block edge and dropped out of range for a
    tick; cover now waits for 3 s with no shooter before it's done.
  - The first dig started in mid-air (a fireball's knock-back): "can't dig air". It now waits up to
    3 s to land. Knocked 4 blocks off the hole mid-dig, it kept digging the old one from the side and
    gave up after 20 s; now it digs a new hole where it stands ("digging in again").
  - Fire from the fireballs set off the hazard reflex, and every step off the fire restarted the dig
    from cover. While digging in, fire no longer interrupts (lava still does).
  - Cover picked the half-dug hole as cover and the bot sat in it when the ghast left, with no way out
    on foot. Cover no longer picks a 1-wide shaft.
  - Then, live: hit → no cover within 10 → digging in at 24 150 −50 → knocked off → digging in again at
    24 150 −49 → "hiding in a hole" → 1 min 46 s → "climbing out — nothing after us for 5 s" → "out
    of the hole — climbed 3 up", the hole filled back with its own cobblestone. **But it came out at
    1.9 health**: where it lost the rest isn't measured (the health trace was lost to a backgrounded
    watcher). Likely suspects: fireballs and burning during the dig, now that fire doesn't make it step
    off. Next: a health trace through a hide, and maybe don't hide at all below some health but run.
  - A second flaky handshake test seen (`aLateFirstAnswer_stillCounts`); core tests aren't run in
    parallel, so not the parked-team static after all. To look at.
- **Health trace of that hide** (MCP Fabric's `player_damage` events, with causes): 20 → 2.9 was two
  fireballs (~6), standing in fire while digging (~3), and **burning on in the hole, 1 a second for
  8 s (~8)** — the burn lasts ~8 s after the last touch of fire, and there's no water up there. (32
  `inWall` hits before the run were the test setup: refilling the platform round the player.) Fix,
  built and tested, **not yet run live**: the hide counts as "hiding" (fire ignored, critical health
  holds off) only once below the surface, so the hazard reflex steps it off the fire before it digs.
  The ~8 s burn after any touch of fire stays without water or fire resistance: worth a reflex later
  (drink fire resistance when burning, or a water bucket).
- **Traced hide rerun, passed (2026-10-29, platform refilled flat, clear weather, noon, full iron,
  ghast summoned ~21 north and 12 up):** three fireballs 3.5 s apart on open ground, 20 → 16.9 → 13.8
  → 10.6, each ~3.1 with ~1 of fire after; **no `onFire` burn ticks at all** (yesterday ~8), so the
  "hiding only below the surface" fix holds. The first dig was knocked off its spot by the second
  fireball; at health 11 the critical reflex took over the second dig (it wasn't below the surface
  yet) and took cover at the platform's corner, where it was hit no more for 60 s; then it had to move,
  found no cover, dug in at 30 150 −39, waited ("nothing after us for 5 s": the ghast had drifted 80
  blocks off), climbed 3 up and refilled the hole. Out at 10.6, all lost before it got under. A run
  before it (craters from an earlier fireball left in the platform) took cover in a 2-deep crater
  instead and never needed to hide. Left over: the first seconds on open ground are the costly ones
  (three fireballs before it's under); digging faster or digging in at the first fireball rather than
  looking for cover first would save most of it.
- **Incoming projectiles (idea, owner, 2026-10-29; not decided in detail).** Arrows, ghast and blaze
  fireballs, wither skulls and dragon fireballs are entities the client sees, with position and
  velocity, so a hit can be predicted (fireballs fly straight, arrows arc under gravity). A reflex
  `incoming`, ahead of fight-or-flee, in this order of worth:
  1. **Hit ghast fireballs back**: face the ghast and swing at the fireball when it's in reach; one
     return kills a ghast (10 health). Fireballs are slow, so there's time. Sidestepping doesn't
     help with them (the blast reaches). Three fireballs cost 9.4 health in the traced hide above.
  2. **Shield**, when one is carried (offhand): raise it toward an incoming arrow or fireball; blocks
     explosions from the front too. Robust under lag; the answer to skeletons.
  3. **Sidestep** with no shield: strafe across the predicted path; works for blaze fireballs and
     far arrows, not close arrows (~5 ticks at 200+ ms ping).
  4. Wither skulls and the dragon belong to §5 fights and the End.
  On 9b9t projectile updates come in jumps (low TPS, ping 200+): judge by the last known velocity,
  act a few ticks early, favour 1 and 2. Test in the 26.2 world with summoned projectiles first.

  **1 built (2026-10-29): `incoming`, anarchy only** (`core/body/IncomingInterrupt`,
  `core/task/DeflectTask`; `WorldView.projectiles()` gives each projectile's position, velocity and
  shooter). Ahead of hazard and critical health, a survival reflex; not while dug in. A ghast fireball
  (`minecraft:fireball`) within 32 blocks whose straight path passes within 2.5 of our middle: face its
  ghast, leading it (its drift over the wait × the return's flight at ~1.5 blocks a tick), and once
  we've faced it 2 ticks (the server reads our facing from the movement packet a tick later) swing at
  the fireball when it's within 3.3 + its speed of our eyes. Then watch the return a few ticks and log
  how far its flight is off our aim and off the ghast. Arrows and blaze fireballs aren't hit.
  - Live, 26.2 world, full iron, platform at noon, three runs (2026-10-29): **every fireball that
    reached the bot was hit back; no damage taken** (the traced hide before it took 9.4 from three).
    First run, aimed at where the ghast was: the returns went past it. With the lead: "flying 2° off
    our aim and 4° off the ghast" at 27 blocks, "1° off our aim and 11° off the ghast" at 56. So the
    swing goes where we aim; what misses is the ghast changing course on the way back, more the
    further it is. No kill seen yet (a ghast's box is 4 wide: within ~2.5 blocks of its middle hits).
    Two fireballs vanished 16–18 blocks out, likely the platform's edge (that ghast flew below its top).
  - Still to do: a kill (closer ghasts, or a better lead); shields (2), sidestepping (3).
  - Test tip: `execute as @e[type=ghast] run tp @s ~ ~ ~` does **not** report where a ghast is: `~`
    is the world spawn there, so it moved them to 16 114 −48. Use MCP `get_entity` by uuid.
- **The owner's keys and the game mode (owner, 2026-10-29).** The anarchy take-over after the owner
  lets go is now 0.25 s (was 0.5), a setting: `resume` in ms, 0–2000 (`/zbot set resume`, Mod Menu →
  Zymbot). Bug: fight-or-flee went on in spectator; now no reflex runs in creative or spectator
  (nothing hurts us there); orders like `goto` still do.
- **Phantoms (ideas, owner, 2026-10-29).** Avoid them: they come to players who haven't slept for 3
  nights, dive from above and hit hard; today they read as "strong", so the bot backs off, which a
  flyer simply follows (seen in the burning test: retreats round the platform). Better: sleep when a
  bed is to hand (it resets the count), keep under cover at night, and with shields and arrows (the
  projectile item above) block the dive and shoot them down.
- **Pick up ghast tears (owner, 2026-10-29: "tears are rare on 9b9t").** A ghast killed by a returned
  fireball is our kill: it drops 0–1 tear and 0–2 gunpowder. Watch where it falls; if the tear lands
  on solid ground with a safe path (no lava, no drop) within ~32 blocks, fetch it before 9b9t's
  2-minute despawn, then resume the route. Tears aren't fireproof: over a lava lake, it's lost.
  In the decision log (owner's wording): `walking to ghast tear — because treasure`.
  **Decided (owner, 2026-10-29): fetch a tear only when nothing that shoots is in range and health is
  above critical + 4**; otherwise keep hiding (or fighting) and let it go. So in a hole with other
  ghasts about (the owner's 3-ghast run), the tear is lost to the 2-minute despawn rather than risked.
  Going after ghasts on purpose is a fight mode the owner switches on (§5, "Ghasts"), not this.
  **Built 0.2.2 (2026-10-29)** as the `ghast tear` reflex (after `gold`): a tear within 32 blocks on
  ground it can stand next to (nothing of lava, water or fire in or under it, something solid under
  it), with no shooter in range and health above critical + 4. It sweeps 6 blocks round the tear, so
  the gunpowder comes too; up to 45 s to reach it, 75 s in all. Zymbot's Baritone never paths through
  lava, so the walk there is as safe as the spot. A tear it went for and didn't get is tried again
  after 60 s and forgotten after 150 s (despawned). Not yet tried in game.
- **Next for fight-or-flee: hide when it can't win or outrun them (idea, owner, 2026-10-29).** Backing
  off at night often ends in water (seen in every siege test), which zombies follow slowly but where
  drowned spawn and air runs out. A last resort instead, when outnumbered, low on health and with no
  dry way to run:
  - **Tower up** 3–4 blocks (jump and place under itself); zombies can't reach, skeletons still can,
    so a block or two at the sides.
  - **Wall in**: four sides and one overhead, 5–6 blocks, anywhere flat.
  - **Dig in** with a shovel: two down, cap above; fewest blocks, good against arrows, but a hole is
    easy to find on 9b9t.
  - Needs blocks (5 or more) or a shovel in the inventory; the pieces exist already (placing and
    breaking blocks, the best tool). Wait it out until daybreak (`/zbot daybreak`'s clock) or the
    mobs leave, then open up and carry on.

- **Waypoint**: a name plus absolute `x y z`, saved per server.
- **Route**: a name plus an ordered list of waypoints. Many routes can share waypoints, and each takes
  its own path.
- Commands (draft):
  - `/zbot wp add <name> <x> <y> <z>` (or `here`), `/zbot wp remove <name>`, `/zbot wp list`
  - `/zbot route add <name> <wp> <wp> ...`, `/zbot route remove <name>`, `/zbot route list`
  - `/zbot route go <name>`, `/zbot route stop`
  - `/zbot route back <name>`: the same route in reverse, E → A (proposed 2026-10-29: the owner walks
    each route both ways, so one route instead of two).
- **Too far from the start (decided, owner, 2026-10-29):** `route go` is refused when the bot is more
  than a set distance from the route's first point (`route back`: from its last), with the distance
  and the reason in chat; **the same command again confirms** and it goes anyway. The legs themselves
  can be any length, no limit between points (owner, 2026-10-29; highway legs run to thousands of
  blocks). The distance: **500 blocks** by default, a setting (owner, 2026-10-29).
- **Each waypoint keeps its dimension** (proposed 2026-10-29): most routes are in the Nether, where the
  same x y z is a different place from the overworld. A leg that changes dimension needs a portal, so
  it's refused until portals are handled.
- Each leg is a `RouteTask`, so it keeps the existing water handling (Baritone walks, Zymbot takes over
  for water crossings), stranded checks and lag back-off.
- **Resumes at the current leg** after a death, a disconnect or an interrupt (eating, retreating),
  instead of starting again from the first waypoint.
- Later: actions at a waypoint (pause, open a chest, wait for chunks to load).
- **Built and passed (2026-10-29)** (`core/route/Routes`, `core/task/RouteWalkTask`; waypoints, routes
  and the progress in the per-server memory, schema 4). Commands as drafted plus `route back <name>`
  and `[restart]` on go/back; `wp add` takes `x y z`, `x z` (any height) or `here`, `~` works, and keeps
  the dimension; a waypoint on a route can't be removed; a route is all one dimension. One `RouteTask`
  a leg; the point it's heading for is saved after each one reached (the brain builds a new task after
  every interrupt), so the same order carries on from there after a reflex, a death, a log-out or
  `route stop`; `restart` starts over; `route list` says where one was cut short. Far start: over
  `routefar` (500; `/zbot set routefar`, Mod Menu) refused once, the same order within 60 s goes. Not
  automatic on rejoining: after a log-out for danger, walking straight back in is the wrong default.
  335 core tests. Live, overworld sky platform: A → B → C (36 blocks) in 11 s, controls given back;
  `route back`, stopped between B and A, `route list` "back cut short before A", `route back` again
  "carrying on from A (point 3 of 3)", done at A; a waypoint 700 off: refused with the distance, the
  same order again went (stopped at once). Not built yet: portals between dimensions, actions at a
  waypoint. Waiting for day at night: built 0.2.2, below.

The ice miner and mapart both use routes (stash → work site → storage).

### Nether boat highway: Meteor's HighwayBuilder (owner, 2026-10-26)

For the Nether boat-highway route, look at **Meteor's `HighwayBuilder`** (World category) before
writing our own. What it has (read from the Meteor 85 jar, 2026-10-26, not yet tried):
- Digs and paves along the highway it faces, straight or diagonal (diagonals need width ≥ 3):
  `width`, `height`, `floor` mode, `railings` (+ `corner-support-block`, `mine-above-railings`),
  `blocks-to-place` (so the floor block could be the ice from §3).
- Its own pickaxe care (`dont-break-tools`, `durability-percentage`, `save-pickaxes`) and restocking
  from shulkers and the ender chest; can mine ender chests for obsidian.
- `pause-on-lag`; `disconnect-on-toggle` (logs out when it runs out of blocks); `destroy-crystal-traps`
  with a bow. Its tips: Velocity on, Speed off.
- It moves the player itself, so while it runs Zymbot stays on standby (like Meteor's AutoEat pausing
  Baritone). What's left to Zymbot: getting there and back (a route), supplies, eating gaps, and fights
  (withers and wardens on the highways, §5).

### Riding the boat highway (owner, 2026-10-26; ideas, not decided in detail)

The owner rides ice boat highways in the Nether. Zymbot helps while the owner (or later the bot) is in
the boat:
- **Hold W and keep the boat aligned** on the highway: steer back to the lane's centre line and axis
  heading, so the boat doesn't drift into the railings.
- **Spot cracks and griefs** ahead: missing or broken ice, holes, blocks placed on the lane, lava. Slow
  down or stop before them and say where they are. ("grifts" in the owner's note is read as griefs;
  to confirm.)
- **Back off if a boss suddenly appears** on the highway: brake or back the boat away, then the
  highway backoff from §5 (off the axis into a side tunnel, log out, coordinates to the owner). In a
  boat on ice this is much faster than on foot, so it must see further ahead and react earlier.
- **Clearing end crystals safely**, the owner's way: place a block between the bot and the crystal,
  then detonate the crystal from behind it. Meteor's HighwayBuilder uses arrows
  (`destroy-crystal-traps`); it would be good to have both.

## Chest opener (idea, owner, 2026-10-29)

A module that goes around opening the nearest chests: start it, and it walks to the chests nearby and
opens each one, then the next nearest. It needn't be a base; any chests it can see will do. Opening is
what matters (the owner's Chest Tracker records what's inside).

First done by hand on 9b9t on 2026-10-29 (a stash in a mushroom fields biome near x 249816 z 3419,
with a script over MCP Fabric, 26.2 build): all 149 surface chest blocks, 82 chests, opened in about
13 minutes with nothing broken or placed. What it taught:
- **Which chests:** a scan of the loaded chunks found 197 chest blocks within 150 blocks; 48 were buried
  or indoors (solid blocks above). "Surface" = nothing solid overhead; another chest on top doesn't
  count (a chest opens under a chest, not under a solid block).
- **Walking:** Zymbot's `goto` (Baritone, breaking and placing off) walks on chests; MCP Fabric's own
  A* didn't (stuck on chest tops at 7/8 of a block).
- **Always give a height.** A bare `goto x z` means any height: once it led through a cave to y 15
  under the stash. A guard stopped the run if the player dropped below the stash's level.
- **Where to stand:** on the ground beside a stack, not on top. Free-standing stacks are up to 3 chests
  high and can't be climbed without placing blocks; from the ground every chest in the stack is in
  reach (≤ 4.3 blocks from the eyes).
- **Double chests** open as one (54 slots): the other half needn't be opened.
- On 9b9t a chest sometimes gave no menu within 15 s (lag); those were retried or covered by their
  other half.
- The pause menu opens when the game window loses focus; in multiplayer it doesn't stop the game, so
  the run closes it and carries on. Any other screen (chat, inventory) stops it, as does damage.

As a Zymbot module it would add: its own reach and stack logic in `core`, a radius and a "done" list
per server (opened chests aren't opened again), staying out of buried chests unless asked, and the
usual stops (damage, mobs, night, a player in view).

## 3. Ice miner

Mines **regular, packed and blue ice** for boat highways.

### Tools and repair
- The owner provides **Silk Touch pickaxes**. With Silk Touch, regular ice never turns to water.
- The bot counts its pickaxes and checks each one's durability. It stops using a pickaxe at a
  configurable threshold (default in the **2–5%** range) and switches to the next.
- **XP bottle toggle** (bottles are expensive):
  - on: throw bottles to repair the Mending pickaxe;
  - off: keep the bottles and just switch pickaxes.
- No usable pickaxe left → store what it carries, then stop and tell the owner.

### Where it mines
- In the **ice biome the chest is in, or one next to it**.
- **Blacklisted areas** (draft: `/zbot ice deny <pos1> <pos2>`), saved in the config. It never mines
  inside them.

### Storing
- Into an **empty double chest nearby** that the bot has confirmed is the right one. It never breaks
  the chest.
- **Chest full → stop and tell the owner.**

### Food
- **Rotten flesh** from inventory (the owner has plenty). The 9b9t build gets its own food profile in
  the config; `FoodChooser` is tuned for ZymCiv today.
- Rotten flesh gives Hunger 80% of the time. That only drains the hunger bar faster, so the bot eats a
  little more often.

### Not getting stuck
- **Mine top-down, in layers**, never standing on a block it's about to break, and not digging itself
  into holes it then has to path out of.
- **Moves on ice are its own** (§1): walk, no sprint, stop short.
- **Frozen oceans and icebergs**: the drops land in the water. The bot **swims out on purpose, picks
  them up**, and climbs back onto ice or land. This is a new "in water on purpose" mode alongside the
  existing drowning and stranded interrupts.
- When the fidget or stranded watchdog fires on ice, it handles that case specifically (climb out,
  fill or step around the water) rather than just asking Baritone for a new path.

## 4. Mapart

**Staircase** mapart (more colours than flat), **including slabs**.

- Materials come from the owner's **shulker stash**. The loop: go to the stash, take the right
  shulkers, empty them at the build site, go back (a route, §2).
- Built at a safe spot far from spawn.
- Build order: row by row along the map's north-south axis, so staircase heights stay right and
  the build resumes cleanly after a disconnect or death.
- Baritone's `#build` reads `.litematic`, but it handles refills and large flat builds poorly. Plan:
  a dedicated placer for the build, with Baritone only for getting around.
- **Wrongly placed blocks** are broken and their drops **picked up before they despawn (2 minutes on
  9b9t), no matter what**. No block left lying around.
- **Ghost blocks and lag:** a place the server rejects can still show on the client, and 9b9t takes
  0.3–9.5 s to confirm a place depending on its lag spikes (§Probes). So a layer is checked by
  rereading it once the recent ack has passed, not trusted per place (§Probes, "Not needed now").
- **Where to build (owner, 2026-10-30): on an ocean.** It's flat, and the water catches the bot when
  it falls while building a staircase map. On flat land (plains) instead, lay a water base under the
  map first, for the same reason. Seed finding (Later ideas) looks for oceans, and flat land nearby.
- **Roadmap, later:** gather materials itself **only if the stash has been griefed**.

Open: the owner has much more to add here (schematic source, slab rules, map size and multi-map
art, stash layout, how progress is checked and saved).

## 5. Fights: ghasts, then withers and wardens

Last on the list, or before mapart if the owner wants it sooner. **The owner is researching how to
win these fights without dying.**

Zymbot has **no attack code** today. `RetreatTask` (running away while tracking the attacker, dry
ground first) and the critical-health interrupt are the starting points.

### Gear the owner will provide
- A sword with **Smite** (extra damage to undead: withers, wither skeletons).
- **Diamond armour with Mending**, plus **XP bottles** to repair it.
- **Chorus fruit** to get unstuck (random teleport).
- **Golden apples** for healing in a fight (owner, 2026-10-26).

### Food in a fight (owner, 2026-10-26; details to decide with the owner)

Eating in a fight is its own system, not the hunger bar's. Mapart and the ice miner just eat
**rotten flesh** when hungry (§3); a fight also has **golden apples**, eaten for what they do rather
than for hunger:
- golden apple (vanilla 1.21): Regeneration II for 5 s and Absorption (4 extra health) for 2 min;
  edible at full hunger.
- enchanted golden apple, if the owner provides any: Regeneration II 20 s, Absorption IV, Resistance
  and Fire Resistance; far too rare to spend lightly.
- Eating takes 1.6 s with the hand busy and the player slowed, so **when** to eat matters: between
  hits or after backing off, not in the middle of a wither's barrage. Rotten flesh still covers
  hunger, so the golden apples are kept for health.
- To decide: the health or Absorption level that triggers an apple, how many to keep in reserve
  before retreating (like the spare totems), and how this works alongside Meteor's AutoEat (§0) or
  replaces it.
- **What Meteor 85 has** (read from the jar, 2026-10-26; owner to try on 9b9t):
  - **AutoEat** is hunger food: `hunger-threshold`, `health-threshold`, `threshold-mode`
    (Health / Hunger / Any / Both), `food-priority`, `search-inventory`, `pause-baritone`. Its default
    `blacklist` holds golden and enchanted golden apples, **rotten flesh**, chorus fruit, spider eye,
    pufferfish, poisonous potato, raw chicken and suspicious stew, so rotten flesh (§3) must be taken
    off the blacklist for the ice miner.
  - **AutoGap** is the golden apple module: `health-enabled` + `health-threshold` (counts
    Absorption), eat when Regeneration (or, with E-Gaps, Fire Resistance / Absorption) runs out,
    `before-expiry` + `expiry-threshold`, `allow-egap`, `always`, `pause-baritone`. No reserve count:
    it eats the last apple too, so keeping some back stays Zymbot's job (or the owner's).
  - Both pause Baritone while eating, which Zymbot's walks need to tolerate (a paused path isn't a
    failed one).

### Behaviour
- **One at a time** (preferred): when mobs are bunched up, pull one away and fight it alone, rather
  than fighting the crowd.
- **Gear durability low** (same threshold logic as the ice pickaxes) → break off and retreat.
- **Retreat to heal**: go to a known safe spot, **or burrow in** (dig down and seal the roof), eat, get
  health back, return.
- **Ghasts**, **wither clearing** and **warden** are separate fights.

### Ghasts (owner, 2026-10-29): first, before withers and wardens

Going after ghasts is a fight like the wither and warden ones: a mode the owner switches on, never
the bot's own choice. On a route it still only hits fireballs back and hides (`incoming`, "Before
routes" 5). In the mode it seeks ghasts out and kills them with returned fireballs (the first kill:
26.2 world, 2026-10-29, the second return, 46 blocks out), and fetches the tears (rule under §2, "Pick
up ghast tears"). Built before the wither and warden fights: smaller, and it reuses what exists.
Details to decide with the owner: how it finds ghasts, how close it lets them come (a closer ghast is
an easier return), when it hides or leaves, and how many at once.

### Wither clearing (owner, 2026-10-25)

The setup: **a pile of withers on stone or bedrock**, at ground level. The bot fights from the ground:
**no tower or platform** (too slow).

1. **Checklist before it starts.** If anything is missing, it doesn't start, and it says what's
   missing:
   - a totem of undying in the offhand, plus **spare totems in the inventory: 5 by default,
     adjustable** (the owner sets the numbers in game)
   - armour and sword durability above the threshold
   - food (rotten flesh), golden apples, XP bottles, chorus fruit
   - a known safe spot to retreat to
   - no milk buckets: too expensive
   - Meteor Client: **KillAura and aim modules off** (they rotate the camera and fight Zymbot's
     aiming); Velocity on, Criticals on only if they work (below)
2. **Go to the nearest wither and stand next to it.** That's enough to engage it; there's no luring it
   away.
3. **Jump crits all the way to zero.** Zymbot times them itself: jump, swing while falling, off the
   ground, not sprinting, attack cooldown full. That's vanilla's crit, with no extra client needed.
   Hits only the chosen wither until it's dead, then the next nearest.
4. **Every wither in range fires at the bot**, not just the one it's hitting:
   - the Wither effect stacks: **effect up and health dropping → back off** until it wears off
   - on stone, black skulls blow holes in the floor (not on bedrock), so check where it's standing
5. **Totems:** when one pops, put the next one in the offhand at once. **Out of spare totems →
   retreat** (the offhand totem is the last one).
6. Durability low or health low → retreat to the safe spot or burrow in, heal, repair, come back.

**Never dying comes first, then speed.** Inventory is expensive, so the bot never trades safety for a
faster kill. But clearing a pile takes a long time, so it should be fast within that rule. It times
itself: seconds per kill, kills per hour, totems used per run, written to the decision log, so
tactics can be compared.

**Dying with a totem left** (owner, on 9b9t's 1.12.2 days, with 200-1000 withers around): a totem
only saves you if it's **in your hand at the moment** of lethal damage. After one pops, the offhand is
empty until it's refilled. Refilling is an inventory click that has to reach the server, so with ping
and lag, a second lethal hit in that gap kills even with totems in the inventory. That's still true
in current versions. With hundreds of withers firing, that gap is the danger. To be decided with the
owner:
- refill the offhand the same tick a totem pops (Meteor's AutoTotem does this; ours would need to as
  well)
- retreat **earlier** when many withers are in range or the server is lagging (low TPS, high ping),
  not only when spares run out
- keep the number of withers in range down, e.g. work the edge of the pile, not the middle

**Meteor Client: owner to test on 9b9t.**
- **Velocity** (no knockback): cancelling knockback can't be done fairly, since it means dropping the
  server's knockback packets. Plan: rely on Meteor's Velocity; build our own only if it doesn't work.
- **Criticals**: fakes the jump with position packets. It worked on 1.12.2, but modern servers often
  catch it. Zymbot's real jump crits don't need it.

### Warden (owner, 2026-10-25)

500 HP; its melee hit is huge even through armour, and its sonic boom goes through armour and shields.
**The bot never fights one head-on.** Tactics, in the owner's order of preference:

1. **Iron golems.** Build golems (4 iron blocks + a carved pumpkin) near the warden as decoys: the
   golem fights the warden while the bot stays out of its reach and sonic boom range. A golem
   doesn't last long against a warden, so expect to build several, or combine with 2.
2. **Pointed dripstone, and anvils alongside it.** Drop stalactites (and anvils) onto the warden from
   above; the damage grows with the fall height. The bot places them over the warden, then breaks what
   holds them up, staying out of sonic boom range the whole time. Golems are cheaper than anvils, so
   anvils aren't the first choice.
3. **Mace, Density V.** Smash damage grows with the fall height, but a **missed smash means fall
   damage and death**. The owner rules this out (2026-10-25); listed only for completeness.
4. **Entity cramming.** Too many mobs pushed into one block space take damage over and over until
   they die. Works when wardens are bunched up. 9b9t's `maxEntityCramming` gamerule decides how many
   it takes (vanilla: more than 24).
5. **Arrows from far away**, from beyond sonic boom range.
6. **A pit with trapdoors on top**: the warden is trapped in the pit.
7. **Potions** (e.g. splash Harming). The owner has none, so the owner or the bot would have to
   gather the ingredients first.

### Where they are: highways

Wardens and withers are **often on the highways** too (spawned there by other players). So the bot
runs into them in the middle of a route (§2), not only at a pile it is sent to.

**Decided (owner, 2026-10-25):** when a warden or wither is on the route, the bot **backs off and logs
out**, with the reason shown to the owner. The bot never fights on its own initiative. The owner then
chooses: prepare the bot by hand and switch it to wither or warden mode, or give it a detour.

**On a main axis** (the highways along x = 0 and z = 0; diagonals come later, the owner rarely uses them):
1. Back away from the warden or wither.
2. Get **off the axis, about 30 blocks in**, preferably into a **player-made side tunnel**. It uses
   tunnels that are already there and **never digs one**.
3. Log off. **No tunnel nearby → log off** straight away.
4. Show the owner the **coordinates of the warden or wither**: in the logout reason, and perhaps as a
   **temporary JourneyMap waypoint**.

**Later: gearing up by itself** before a fight. It always gears up **off the main axis** (in a
tunnel, like above), never on the highway.

## Later ideas

### Seed finding: biomes, structures, flat land, for two world versions (owner, 2026-10-30)

9b9t's seed is **-8076723744225505211** (public). The world has **1.12.2 chunks** and chunks from
whatever versions came between it and today (1.16, 1.18, 1.19 or 1.20: nobody knows which, or where
the old chunks end). An upgrade never regenerates existing chunks, and 1.18 changed terrain and biome
generation completely, so for the same seed and place, 1.12.2 and 1.18+ give different worlds.

**What to find:** ice biomes first (ice spikes for packed ice, frozen oceans for icebergs with blue ice:
the ice miner, §3), other biomes, structures, and flat land for mapart (§4). **Oceans count as flat
ground** (owner, 2026-10-30), so for mapart, flat land is mostly a biome search, which Cubiomes
answers for 1.12.2 without its terrain heights.

**Every result for both versions, clearly labelled** (owner): e.g.
`ice spikes — 1.12.2 generator: 2,340 1,100 (1,200 blocks) · 26.2 generator (1.18+ terrain): 4,810 -3,020
(3,400 blocks)`. Which one is right depends on when that land was first generated, which nobody knows,
so the bot shows both and says which one the loaded blocks agree with, once it's there.

**Two generators:**
- **1.18+ (labelled with the version we build, e.g. "26.2"):** the game's own generator, already in the
  client jar: biome and structure placement asked for directly, without loading the chunks. Always
  right for that version. Biomes added since 1.18 (mangrove and deep dark in 1.19, cherry grove in
  1.20, pale garden in 1.21.4) only exist in chunks generated that late; the label says so.
- **1.12.2: Cubiomes, as a helper program Zymbot talks to by itself (decided, owner, 2026-10-30:
  "an automated way of sending and receiving requests to Cubiomes on Windows").** Cubiomes is C, MIT
  licence (checked 2026-10-30), with years of checks against the real game, and covers 1.12.2's
  biomes and structures. Not a Java port.
  - A small program built from Cubiomes (`zymbot-seed.exe` on Windows first; Linux and macOS builds
    later for the bots), kept next to the mod, e.g. `<game dir>/zymbot/tools/`.
  - Zymbot starts it on the first seed question and keeps it running. Requests and answers are one
    line of JSON each over its standard input and output, e.g.
    `{"id":1,"mc":"1.12.2","seed":-8076723744225505211,"find":"biome","what":"ice_spikes","x":0,"z":0,"radius":20000}`
    → `{"id":1,"results":[{"x":2340,"z":1100,"distance":2563}]}`. It runs off the game thread, so a
    big search never freezes the game. Missing program: Zymbot says so and still gives the 1.18+ half.
  - Built from a pinned Cubiomes commit. **The Windows `.exe`: a GitHub Actions Windows runner**
    builds it and runs it against known answers before release (the repo is public, so the minutes are
    free). The Linux build is made here too: it produces the test answers for Zymbot's own tests, and
    the bots would use it.
  - Cubiomes also covers modern versions, so it can double-check the game's own 1.18+ answers.

**Mapart squares (from the game's map formula; owner to check one real map in game):** a scale-0
map covers 128 × 128 blocks from x = 128k − 64 to 128k + 63, and the same for z (e.g. a map holding
x 1000, z −3000 covers x 960–1087, z −3008 to −2881). So candidates are those fixed 8 × 8-chunk
squares, never any flat 128 × 128. A staircase map also needs the row just north of the square (its
north row's shade depends on the block north of it).
**"Flat enough"** (owner, 2026-10-30): mostly ocean, with small islands the bot clears first. Each
square scores its land share and the islands' height above sea level; it passes under adjustable
limits (defaults for the owner to pick).

**1.12.2 heights:** Cubiomes' `mapApproxHeight` (checked in its source, 2026-10-30) approximates the
surface for 1.8 to 1.17 with that era's own recipe: biome depth and scale blended, plus the seed's
terrain noise, on a 4 × 4-block grid (32 × 32 samples a map square). Good for ranking squares and
islands; an island under ~4 blocks across can slip between samples. So three checks, coarsest first:
Cubiomes (or the game's own generator for 1.18+, which has exact heights), then the owner's JourneyMap
cache where explored, then the loaded blocks on site, which decide. Optional: measure Cubiomes' error
against a real 1.12.2 world generated from the seed (a 1.12.2 server on Java 8).

**Which version is a place? (nobody knows where the old chunks end):**
- **Live:** once the bot is there, compare the loaded blocks with both generators' answer (where land
  and water are, the biome, the height) and label the chunk "matches 1.12.2" or "matches 1.18+".
  Remembered per chunk, so the bot learns the border as it goes.
- **From the owner's JourneyMap cache:** JourneyMap keeps a PNG per 512×512-block region of what was
  explored. Compare its land and water shapes with both generators' land and water for that region
  to label explored regions in bulk, before walking there. Worth trying: coastlines differ strongly
  between 1.12.2 and 1.18+.

Not built; a module of its own, after the ice miner's basics.

### Snapshot, build offline, ghost blocks back on 9b9t (owner, 2026-10-26)

Download the area around the owner on 9b9t, switch to singleplayer, build there with **Axiom**,
then go back to 9b9t and see what was built as **ghost blocks** to place for real. Needs Zymbot to
work alongside the owner's 9b9t client mods: Meteor Client, Sodium, Axiom and the rest.

Notes for when it comes up:
- This is close to the existing flow of a world downloader + **Litematica**'s ghost-block overlay;
  Zymbot's part could be the glue (snapshot, export the build as a schematic, load it on 9b9t).
- The ghost blocks are then a build plan the bot can place itself, the same thing mapart (§4) needs.

## Probes: `/zbot probe …` (owner, 2026-10-26)

Numbers the plans above depend on, measured on 9b9t. Local client commands: nothing goes to chat, and
only `despawn` acts. First batch built in 0.2.1 (2026-10-26): `tps`, `ping`, `ack`, `meteor`.

**First readings on 9b9t (2026-10-26):** ping 447 ms in the tab list (the owner's HUD: 216 ms; most
players list 2–3 s, so tab ping there is inflated by lag). **The game time in 9b9t's time updates
doesn't move like a clock**: it stays put for several updates, then jumps (`+0/2.3s +0/2.4s +0/2.0s
+255/2.8s`), so a meter on game time read 0 or 20. `TpsMeter` now counts updates as 20 ticks each
when that happens (as Meteor's `TickRate` does; it read 6.6–7.9, the owner saw ~5–11); unit-tested
on those numbers and **seen live** the same day: 8.9 TPS by counting (`+0/2.3s +0/2.1s +0/1.9s +0/2.7s`),
Meteor 6.1 (it averages over a longer stretch). Later the same session the server sent nothing for
31.8 s, then +867: under the first "impossible jump" limit, so it read 19.9 (Meteor 4.7); the limit is
now 25 ticks a second plus jitter, so that counts as a stall too (unit-tested; in the next jar, not seen live).

**`ack` on 9b9t (owner breaking and placing by hand, 2026-10-26): average 5.1 s, worst 7.1 s, last
1.9 s** (3 samples, TPS ~5). `PlaceTask` waits 1 s, so on 9b9t nearly every place would be called a
failure and retried. Over that session it swung widely: 21 samples averaged 3.15 s (worst 9.5 s), and
under an hour later the last 50 averaged 0.32 s (worst 0.47 s). **9b9t's lag spikes come and go**
(owner).

**Not needed now** (checked with the owner, 2026-10-26): `PlaceTask` only places a crafting table
(ZymCiv's ladder, `debug place table`), and with NoGhostBlocks off (the owner's setting) the client
shows a place at once, so its 1 s window never meets the server's lag. `BreakTask` is done when the
block is gone on the client (also at once) and allows 30 s, so the ice miner is fine on a lagging 9b9t;
a break the server undoes just shows up again. HighwayBuilder places its own blocks. **It matters for
mapart (§4)**, where a ghost block is a wrong pixel: there, rather than timing each place, build a
layer, reread it once the ack has passed (`probe ack`'s recent worst, plus a margin) and fix what's
wrong. Tab ping read 0 ms
just after joining (the server hadn't listed us yet), another reason not to lean on it.

| Command | Shows | Needed by |
|---|---|---|
| `probe tps` | Zymbot's TPS meter, Meteor's (`TickRate`), seconds since the last time packet | lag back-off, eating earlier when slow (§0), checks our meter against Meteor's |
| `probe ping` | the latency the server lists for us (tab list) | totem refill gap (§5), eating (§0) |
| `probe ack` | average and worst time from a block break or place to the server's confirmation (block-change ack), over the last ones seen | `PlaceTask`'s fixed 1 s confirm window (should grow with this); NoGhostBlocks |
| `probe meteor` | which relevant Meteor modules are on, with warnings where they'd fight Zymbot: AutoEat with rotten flesh still blacklisted, KillAura or aim modules on, NoGhostBlocks off | §0 eating, §3, §5 |
| `probe setbacks` (later) | server teleports (rubber-banding) in the last minute | lag, anticheat |
| `probe server` (later) | brand, view and simulation distance, chunk arrival | routes, highway riding |
| `probe despawn` (later) | drops two items ~30 s apart and times each | the 2-minute question below |

**Meteor's NoGhostBlocks** (World; `breaking`, `placing`): the client stops predicting breaks and
places, so a block changes only when the server's update arrives. Without it, a place the server
rejects still looks done (a ghost block) and `PlaceTask`'s check passes; with it the check is honest,
but a lagging server can take longer than the 1 s window. So: NoGhostBlocks on, and the confirm window
scaled by `ack`.

## To test in game

9b9t doesn't run vanilla rules everywhere. Test on 9b9t later (possibly with an MCP for Fabric that
lets Claude drive the game):

| What | Expected | Why it matters |
|---|---|---|
| Dropped item despawn time | **2 minutes** (owner), not vanilla's 5. **No ClearLag-style warning in chat** (owner, 2026-10-25); still to check whether each item has its own timer or all items are cleared at once | ice miner: drops in the ocean must be picked up within 2 minutes, so it mines no more over water than it can collect in time; mapart: wrongly placed blocks, the same |
| `maxEntityCramming` for withers | **100** on 9b9t's 1.12.2 days (owner); unknown now | wither and warden cramming (§5) |
| Meteor's Velocity and Criticals | worked on 1.12.2 | §5 knockback and crits |
| Meteor's AutoEat | owner judges whether it's good enough | shared eating (§0) |
| Meteor's Jesus (walking on water / lava) | **doesn't work: confirmed** on 9b9t (owner, 2026-10-26); not worth trying again | so Baritone's `assumeWalkOnWater` / `assumeWalkOnLava` must stay off (Zymbot already sets `assumeWalkOnWater = false`; `assumeWalkOnLava` is off by default), and water and lava crossings stay Zymbot's own moves (§1) |

### Next MCP Fabric test: Meteor's Freecam (owner, 2026-10-29)

**Bug:** with Meteor's Freecam on and `/zbot start` running, a mob hit the player and Zymbot didn't
react; "it only reacts to the camera". Not reproduced or understood yet. What Meteor's source says
(read 2026-10-29), to check against in the test:
- Freecam takes the movement keys for the camera and releases them for the game (`unpress()`, and it
  cancels the key events), so Zymbot doesn't see the owner's WASD as "a human took the controls".
- Zymbot moves by pressing the same key bindings (`holdKeys` → `keyUp.setDown`); those aren't key
  events, so they should still move the player, not the camera.
- Mouse turns go to the camera (`EntityMixin` → `changeLookDirection`); Zymbot turns the player
  directly (`setYRot`, `lookAt`), so that shouldn't matter.
- Freecam settings that could explain it: `rotate` (turns the player to whatever the camera looks
  at: fights Zymbot's aim), and `toggle-on-damage` (would end Freecam at the first hit instead).

**The test, singleplayer over MCP Fabric:** `/zbot start`, Freecam on (default settings, then
`rotate` on), a zombie spawned next to the player. Watch the decision log (`/zbot status`) and the
player: does `fight-or-flee` trigger at all, does the player turn and hit, does it walk, or does
something turn back to where the camera looks. Then the same with `rotate` off and the camera far
away.

## Order

1. §0: 1.21.11 build, `anarchy` profile (Phase 0 plan above)
2. §2: Routes
3. §3: Ice miner
4. §4: Mapart
5. §5: Fights, whenever the owner chooses (possibly before mapart): ghasts first, then withers and wardens

The 26.2 client build (once "later") is done: 9b9t runs 26.2, and Zymbot builds for it (§0).
