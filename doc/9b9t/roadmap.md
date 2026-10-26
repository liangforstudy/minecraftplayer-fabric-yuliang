# 9b9t roadmap (draft)

Status: **plan, 2026-10-25**, from talks with the owner. Nothing here is built yet.

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
- **Later:** a 26.2 client build, once Fabric and Baritone support it. The project already uses
  Mojang names, as 26.x does, but expect real work in the adapter (`mod/src`) all the same.

### Profiles (decided, owner, 2026-10-25)

Stonecutter only builds the adapter (`mod/src`) per version; `core` is shared, so ZymCiv code can't be
left out of it at compile time (not without making `core` per-version too, which isn't worth it).
Instead, **Stonecutter sets a profile per version** (`zymciv` for 1.21.1, `anarchy` for 1.21.11), the
adapter passes it to `Bot`, and `Bot` wires only that profile's modules. The ZymCiv code is in the
jar but never runs on 9b9t.

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
3. **Profiles**: a profile constant per version, `Bot` wiring modules from it, and `core` tests
   checking that `anarchy` never creates anything on the off-list.
4. **Smoke test** on the owner's own Prism client (1.21.11 instance with Meteor, Baritone 1.17.0 and
   Zymbot): first a singleplayer or LAN world (join, `/zbot status`, a Baritone walk, break one
   block, emergency stop), then join 9b9t and stand idle.

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

## 1. Baritone findings (`1.21.11` branch, checked 2026-10-25)

- **No slipperiness handling.** Movement and path execution don't account for ice friction, so the
  bot overshoots and slides on ice. Zymbot does short moves on ice itself: no sprinting, stopping short
  of the target.
- **It never breaks regular ice.** `MovementHelper.avoidBreaking` has
  `b == Blocks.ICE // ice becomes water` built in, with no setting to turn it off. That holds even
  with Silk Touch. So `#mine ice` can't mine regular ice (packed and blue ice are allowed).
  → **Zymbot picks and breaks every ice block itself** (`BotDigging`). Baritone only walks, on
  solid ground.

## 2. Routes (named waypoints, A → B → C → D → E)

Baritone goes straight to one goal. Routes walk a list of points in order.

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
   - food, XP bottles, chorus fruit
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

## To test in game

9b9t doesn't run vanilla rules everywhere. Test on 9b9t later (possibly with an MCP for Fabric that
lets Claude drive the game):

| What | Expected | Why it matters |
|---|---|---|
| Dropped item despawn time | **2 minutes** (owner), not vanilla's 5. **No ClearLag-style warning in chat** (owner, 2026-10-25); still to check whether each item has its own timer or all items are cleared at once | ice miner: drops in the ocean must be picked up within 2 minutes, so it mines no more over water than it can collect in time; mapart: wrongly placed blocks, the same |
| `maxEntityCramming` for withers | **100** on 9b9t's 1.12.2 days (owner); unknown now | wither and warden cramming (§5) |
| Meteor's Velocity and Criticals | worked on 1.12.2 | §5 knockback and crits |
| Meteor's AutoEat | owner judges whether it's good enough | shared eating (§0) |

## Order

1. §0: 1.21.11 build, `anarchy` profile (Phase 0 plan above)
2. §2: Routes
3. §3: Ice miner
4. §4: Mapart
5. §5: Fights, whenever the owner chooses (possibly before mapart)
6. Later: the 26.2 client build
