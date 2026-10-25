# 9b9t roadmap (draft)

Status: **plan, 2026-10-25**, from talks with the owner. Nothing here is built yet.

Why: ZymBot (`lol.zym.zymbot`, a separate project) is further up the ZymCiv milestone ladder than
this repo ([BOT_BEHAVIOUR.md](../../BOT_BEHAVIOUR.md#the-milestone-ladder)). So Zymbot also learns
jobs for **9b9t**, an anarchy server: ice for boat highways, staircase mapart, routes, and later
fights with withers and wardens.

**9b9t allows bots** (owner, 2026-10-25). Mapart is built at a safe spot far from spawn.

## 0. Versions and builds

| Server | Server version | Client we build | Modules |
|---|---|---|---|
| ZymCiv | 1.21.1 (modded) | 1.21.1 | ZymCiv: pack loader, class XP, milestone ladder, ... |
| 9b9t | 26.2 | **1.21.11** (joins through the server's version support) | 9b9t: routes, ice miner, mapart, fights |

- Stonecutter gets a second version, `1.21.11`, beside `1.21.1` (`mod/settings.gradle.kts`,
  `mod/stonecutter.properties.toml`).
- **Modules are switched on and off at the Stonecutter level**: per-version properties and `//? if`
  blocks. The 1.21.1 build turns on the ZymCiv modules, the 1.21.11 build turns on the 9b9t ones.
- `core` stays plain Java and is shared. General reflexes (interrupt stack, drowning, stranded,
  fidget watchdog, `BreakTask`, `PlaceTask`, decision log) serve both servers.
- Baritone: the official repo has a `1.21.11` branch, and the owner has a 1.21.11 jar.

### Shared: eating (all 9b9t modules: ice miner, mapart, fights)

- **Meteor Client's AutoEat first.** If the owner finds it unsatisfactory, we write our own.
- Zymbot already has its own eating (`HungerInterrupt`, `EatTask`, `FoodChooser`). Only one of the two
  may run at a time, or they fight over the hotbar. In the 9b9t build, Zymbot's eating is off while
  Meteor's AutoEat is in use.
- Food and thresholds come from the config (e.g. rotten flesh for the ice miner, §3).
- **Later:** a 26.2 client build, once Fabric and Baritone support it. 26.x uses unobfuscated
  Mojang names, so expect real work in the adapter (`mod/src`).

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

### Warden

The owner is still researching tactics. 500 HP; its melee hit is huge even through armour, and its
sonic boom goes through armour and shields. Avoiding a head-on fight matters.

## Order

1. §0: 1.21.11 build, ZymCiv modules off in it
2. §2: Routes
3. §3: Ice miner
4. §4: Mapart
5. §5: Fights, whenever the owner chooses (possibly before mapart)
6. Later: the 26.2 client build
