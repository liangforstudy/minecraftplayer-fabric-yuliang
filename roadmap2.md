# Villager entity roadmap (draft)

Status: **ideas, 2026-09-28**, from a discussion with the owner. Nothing is scoped or built; this
sits after Phase 3 (gather / craft ladder), which most of it depends on.

The vision: the bot becomes an NPC villager that navigates, gathers and **builds by itself**, such
as a giant castle or map art, when it (or its village) levels up.

## 1. Looking like a villager (a pack decision)

- A **morph mod** makes the player look (and maybe act) like a mob. Candidates:
  - **Woodwalkers**, a descendant of Identity
  - **Identity**
  - similar mods
- **Catch:** Zymbot is client-side only, but a disguise must be seen by everyone, so a morph mod
  needs its **server** part. That's fine on the owner's server, and only works elsewhere if they run it too.
- Woodwalkers-style mods also give the mob's abilities; a villager has none worth having, so this is
  cosmetic. That's fine.
- **Version:** doesn't have to match today. Like the rest of Zymbot (Stonecutter, multi-version by
  design), the morph support can be ported back and forth between Minecraft versions (owner,
  2026-09-28).
- **Separate from civfabric:** the morph is its own optional feature, not part of the civfabric pack.
  It's used only where the server owner allows it (owner, 2026-09-28). Zymbot must work the same
  without it; the morph is a look, not a dependency.
- **Baritone vs the morph's body:** a villager's eye height and hitbox differ from a player's, so
  Baritone's reach, clearance and step checks may be wrong. We may need our own **small patch**, a
  separate compat mixin or file, so Baritone works while morphed. Test the plain morph first.
  Zymbot's own water code assumes a player's body too: swimming ashore, diving (reach 3.5) and
  wading use the eye height (`headInWater`) and a player's reach. Retest those morphed (2026-09-30).
- **Unlocking the villager shape:** the bot shouldn't have to "earn" it. Get it instantly through an op
  command, or by editing the mod's player data or config (JSON / NBT), e.g. at world setup. Check
  which the chosen mod offers.
- **Decided (owner, 2026-10-01): Cobblemon side = [Cobblemon: Synchro Machine](https://modrinth.com/mod/cobblemon-synchro-machine)**
  (`synchro-fabric-1.8.1.jar`, Fabric 1.21.1, client **and** server, ARR licence). Morph into any party
  Pokémon (its model, stats, moves, typing; flight and water breathing from the Pokémon's stats); its
  hitbox follows the Pokémon's real size, so the Baritone / water-code retests above apply. Keys: G team
  screen, Y transform into what's in the crosshair, Z/X/C/V moves (H is its move HUD, also voice chat's key).
  **Vanilla mobs (villager): still undecided.**
  **First live test (2026-10-01):** the bot needs the mod too (without it: "Registry entry
  cobblemon_synchro_machine:synchro_machine is missing", dropped on join). The owner morphed Bot1 with
  `/synchro morph Bot1 Quaquaval hisuian level=35 gender=male scale_modifier=1.1`, then hit it: Bot1
  retreated as usual (`danger: modpack`) and settled in 23 s. Walking, swimming, diving and graves
  while morphed are still to test.
  - **Swimming morphed works (2026-10-01):** following the owner across water, Bot1 (Quaquaval) planned
    its own swim (`no dry path … 21 blocks left`), crossed, failed the last step onto a ledge ("gave up 4
    blocks short", as unmorphed), then `swimming to dry land` and climbed out without spinning.
  - **Diving morphed works (2026-10-01):** as Quaquaval, grave 10 blocks under at 6 52 -154: `diving to the
    grave …` at 23:14:43, `done: pick up my grave` 2 s later. (The morph survived death because the owner
    turned that Synchro Machine setting on; it's off by default, so a respawn normally unmorphs.)
  - **Water breathing (2026-10-01):** as Quaquaval its air never drops, so the drowning reflex never
    fires and it can stay under water — the owner: works as intended. Diving's own limits then are the
    15 s timeout, not air; a water breather could dive deeper and longer if that's ever needed.
  - **Swimming may be free for a water-type morph:** the hunger meter read swimming **0.00 food/min**
    (unmorphed ~2.6) — only 0.4 min of data, so confirm with a longer swim. If it holds, the route
    planner could cost water as cheap (not ×3, `swim_cost_blocks`) while morphed into a water type,
    read from the morph's type or simply from the measured swim cost.

## 2. Building structures from schematics

- Baritone already builds: `#build <file>` for a schematic file, and `#litematica` for the loaded
  Litematica placement. Use that; don't write our own builder.
- It needs `allowPlace` / `allowBreak`, which Zymbot keeps **off** everywhere. Plan: switch them on only
  inside the build's bounding box, only while a build task runs. Check Baritone 1.11.3's
  `buildOnlySelection` / `buildIgnoreBlocks` / `buildInLayers` settings.
- **Materials are the Zymbot part:** Baritone builds only from the inventory and stops when it runs
  out. Zymbot reads the schematic's material list, then gathers, crafts or fetches it from chests in
  batches, and hands Baritone the next layer. This rides on Phase 3's ladder.
- **Level up (not decided):** the build list itself can be the levels, e.g. lvl 1 hut, lvl 3 walls,
  lvl 5 castle. Or base it on an existing mod. MineColonies is the known colony mod, but as far as
  we know it has no Fabric 1.21.1 build. Open.

## 3. Getting stuck on half blocks

- In 1.12.2, Baritone's Litematica building got stuck on slabs and trapdoors. **Retest on 1.21.1:**
  a small test build with slabs, trapdoors, stairs and carpets on flat ground.
- Whatever the result, detect it: "no closer to the goal in N s" (like the fidget watchdog and the
  "3 tries" pattern), then back off, re-path, and log `[decision] stuck — because …`.

## 4. Parkour, but only when a miss is safe

Owner's rule: jump when close to the ground; avoid the risk when high up with no water below. In
1.12.2 Baritone sometimes missed jumps (lag, TPS, network).

- Baritone's switches (`allowParkour`, `allowParkourAscend`, `allowParkourPlace`) are
  all-or-nothing.
- **Fall-risk cost:** a mixin into Baritone's parkour movement cost makes a jump impossible when a
  miss would fall more than `maxFallHeightNoWater` (3) with no water under the gap. Safe jumps stay
  and risky ones get pathed around. Risk: it hooks Baritone internals, so recheck it on each update.
- **Lag gate:** parkour off when TPS < `lagtps` or ping is high (the missed-jump cause). This is safe
  to ship first; the mod already measures TPS.
- Check first: Baritone upstream may have fixed the missed jumps since 1.12.2.

## Rough order

1. **Phase 4:** safe parkour (lag gate first, then fall-risk cost), stuck detection, half-block retest.
2. **Phase 5:** schematic building (scoped Baritone build plus material logistics) and the level list.
3. **Any time:** choose the morph mod (owner's call; needs the server side).

## Open questions

- Which morph mod? It needs a server-side install on every server the bot plays as a villager.
- Level-up system: our own build list, or an existing mod?
- First build target to test with (a small hut? a map-art tile?).

## Research (2026-09-28)

### Morph mods (web search; versions from Modrinth listings, not installed or tried)

| mod | loaders / 1.21.1 | notes |
|---|---|---|
| **Woodwalkers** | Fabric & Quilt, `1.21-fabric-5.3.2` (1.21–1.21.1, Oct 2024) | Identity lineage; kill a mob to unlock its shape, gains its abilities |
| **ReMorphed** | Fabric, Forge, NeoForge, Quilt; 1.21–1.21.5 | started as an Identity fork, now its own mod; seems the most actively maintained |
| **Morph** (iChun) | a Fabric version exists; 1.21.1 not confirmed | acquire a mob (or player) by killing it |
| **MobMorph** | NeoForge only on 1.21.1 | not for us (Fabric) |
| **Gamingbarn's Morphs** ⭐ | data pack (also a mod), `/trigger morph` | **owner: promising** (2026-09-28). A data pack runs only on the server and needs nothing on clients, so the bot's client needs nothing extra. `/trigger` is a vanilla command, so the bot can morph itself; op commands or scoreboards could hand out the villager. Check how it shows the disguise (vanilla tricks like invisibility plus a display or passenger entity?) and what that does to Baritone and hitboxes |

Next: read Woodwalkers' and ReMorphed's commands and config for an op/JSON unlock, and check eye
height/hitbox handling. Download only with the owner's OK.

### Baritone 1.11.3 building (read from our API jar, `baritone.api.Settings` / `IBuilderProcess`)

- **API:** `IBuilderProcess`
  - `build(name, File, origin)`: a schematic file
  - `build(name, ISchematic, origin)`: **a schematic we make in code**, no file or mod needed
  - `buildOpenLitematic(i)`: the i-th placement loaded in Litematica; **needs Litematica**
  - `buildOpenSchematic()`
  - `pause()` / `resume()` / `isPaused()`
  - `clearArea(a, b)`
  - `getApproxPlaceable()`: what it can place from the inventory now, **useful for material logistics**
  - `getMinLayer()` / `getMaxLayer()`
- **Schematic helpers in the API:** `FillSchematic`, `WallsSchematic`, `ShellSchematic`,
  `ReplaceSchematic`, `MaskSchematic`, `CompositeSchematic`, plus `Rotated` and `Mirrored`
  variants. Simple shapes (walls, a floor, a castle's outer shell) can be made without any file.
- **Build settings:**
  - **layers:** `buildInLayers`, `layerOrder`, `layerHeight`, `startAtLayer`, `skipFailedLayers`
  - **scope:** `buildOnlySelection`, `buildIgnoreExisting`, `buildIgnoreDirection`
  - **placement:** `buildSchematicRotation`, `buildSchematicMirror`, `schematicOrientationX/Y/Z`
  - **repeat:** `buildRepeat`, `buildRepeatCount`, `buildRepeatSneaky`
  - **map art:** `mapArtMode`
  - **scan:** `builderTickScanRadius`
  - **fixes:** `breakCorrectBlockPenaltyMultiplier`, `placeIncorrectBlockPenaltyMultiplier`
  - **file type:** `schematicFallbackExtension`
- **Parkour settings:** `allowParkour`, `allowParkourAscend` and `allowParkourPlace` are plain on/off
  switches. There is no fall-height rule, which confirms §4 needs our own cost patch.
- **Litematica:** only `buildOpenLitematic` needs the mod. Baritone should be able to read
  `.litematic` files itself through `build(name, File, …)`; verify in a test. Not in the pack today.
