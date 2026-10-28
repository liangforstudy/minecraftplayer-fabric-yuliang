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
- **26.2 client build: built 2026-10-28, not yet tried in a world.** Third Stonecutter version
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
  - **Next (owner, in a 26.2 world; no MCP there):** `/zbot status`, `probe tps` (does the game time
    still jump without translation?), break blocks then `probe ack`, a short `goto`, Mod Menu →
    Zymbot. The world-time, block-ack and boss-bar hooks only attach in a world.
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
  the likely cause. Not fixed; watch for it.
- **The lag back-off flaps**: TPS read 14–15 and 19–20 by turns (each a few seconds), so "planning
  less" / "planning normally" logged every 3–10 s, right at the `lagtps` 15 line. Wants some
  hysteresis (e.g. back to normal only above 17) so the log isn't flooded on 9b9t. It also read 1 TPS
  in the first second of the first try (just after a stall), which was fair.

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

- **Waypoint**: a name plus absolute `x y z`, saved per server.
- **Route**: a name plus an ordered list of waypoints. Many routes can share waypoints, and each takes
  its own path.
- Commands (draft):
  - `/zbot wp add <name> <x> <y> <z>` (or `here`), `/zbot wp remove <name>`, `/zbot wp list`
  - `/zbot route add <name> <wp> <wp> ...`, `/zbot route remove <name>`, `/zbot route list`
  - `/zbot route go <name>`, `/zbot route stop`
- Each leg is a `RouteTask`, so it keeps the existing water handling (Baritone walks, Zymbot takes over
  for water crossings), stranded checks and lag back-off.
- **Resumes at the current leg** after a death, a disconnect or an interrupt (eating, retreating),
  instead of starting again from the first waypoint.
- Later: actions at a waypoint (pause, open a chest, wait for chunks to load).

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
- **Roadmap, later:** gather materials itself **only if the stash has been griefed**.

Open: the owner has much more to add here (schematic source, slab rules, map size and multi-map
art, stash layout, how progress is checked and saved).

## 5. Fights: withers and wardens

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
- **Wither clearing** and **warden** are separate fights.

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

## Order

1. §0: 1.21.11 build, `anarchy` profile (Phase 0 plan above)
2. §2: Routes
3. §3: Ice miner
4. §4: Mapart
5. §5: Fights, whenever the owner chooses (possibly before mapart)
6. Later: the 26.2 client build
