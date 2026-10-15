# Finding the world roadmap (draft)

Status: **plan, 2026-10-15**, from the owner. Step 0 is done and step 1 dropped; nothing else is built yet.

The idea (owner, 2026-10-15): the UDP multicast **local bus is off by default**. A bot joins a server
**knowing nothing** about other players, bots or places. It finds them **in the game**: it looks
around, remembers landmarks, and searches outward **breadth-first** until it meets players, bots and
places worth knowing.

Landmarks include towns / villages, farms, the bot's own bed, other players' beds, and similar places.

## 0. Bus off by default — done (2026-10-15)

- `transports.local_bus` now defaults to **false** (`ZymbotConfig.Transports`).
- Config **schema 5**: an older `zymbot.json` with `local_bus: true` is switched off **once**, with a
  warning in the log. Setting it back to `true` afterwards is kept. This applies to bot1/bot3 and the
  owner's client on their next start.
- The code stays. Turning the bus on still gives the old behaviour, which is useful for tests and for
  the owner's own debugging.

### What stops working with the bus off

| Feature | Uses the bus for | With the bus off |
|---|---|---|
| Regroup (PHASE2 §1) | teammates' `HELLO` positions, `WHERE` | no positions: probe (if `chat` is on), then walk to world spawn and wait |
| Roster (`HELLO`, `ROSTER`) | knowing who is a Zymbot | a bot can't tell a bot from a human; it only sees "players" |
| `/zbot summon`, auto-summon on LAN | `SUMMON` to bots at the title screen | **broken**: those bots aren't on a server yet, so nothing in-game can reach them |
| `/zbot see <bot>`, `/zbot watch` | `STATUS`, `STATUS_LINE`, `WATCH` | broken for other bots; `send.bat` and the log files still work |
| Knocked-out alert | `DOWNED` | teammates aren't told; nearby humans still get the `/msg` |

**Decided (owner, 2026-10-15): owner tools stay off with the bus too.** No separate switch for
`SUMMON` / `STATUS` / `WATCH`; the owner uses `send.bat` and the log files instead. Turning
`local_bus` back on brings them all back.

## 1. What a bot can know without being told

| Source | Gives | Range |
|---|---|---|
| Tab list | **who** is online, not where | whole server |
| Entities in sight | players (and mobs) with positions | entity tracking range, about 48–128 blocks depending on the server |
| Loaded blocks | landmarks (§2) | the loaded chunks; the survey scans 56 blocks today (`SurveyCatalog.RADIUS`) |
| Own respawn point | **its own bed** | always known |
| World spawn | a meeting point everyone shares | always known |
| Chat | names, `!where` answers from humans | whole server |

## 2. Landmarks

The survey (`survey/`) already finds crafting tables, furnaces, chests, beds and campfires around
the bot, once. A landmark is a **remembered** survey result: kind, position, when it was seen, and
how sure the bot is.

| Landmark | How to recognise it |
|---|---|
| **Village / town** | a bell, or several beds plus villager workstations (lectern, composter, …) within ~48 blocks; villagers in sight; dirt-path blocks |
| **Farm** | farmland with crops, or fenced animals; one block of farmland counts for little, a field counts for a lot |
| **Own bed** | the bot's respawn point (exact, no guessing) |
| **Someone else's bed** | **Limit:** vanilla doesn't store a bed's owner on the block. A bed is "someone's" when it is outside a village, near other player-made blocks (chest, table, torches, glass), or when a player is seen sleeping in it or standing next to it at night |
| **Base / camp** | a cluster of player-made blocks: chest + table + furnace, torches, planks or glass in shapes nature doesn't make |
| **Players / bots** | an entity in sight, with name and time |
| **Paths and light** | dirt paths, torch lines, campfire smoke: not destinations, but **clues** pointing to people |
| **World spawn, portals** | fixed meeting points |

Landmarks are saved per server (`VersionedStore`, beside the roster) and fill in the existing stub
`WorldView.nearest(tag)`, which today always returns empty ("Empty until the map exists").

## 3. The breadth-first search

- **Grid:** the world is split into cells about the size of one survey (64×64 blocks).
- **Anchor:** the first known place: own bed if there is one, else where the bot joined, else world spawn.
- **Breadth-first:** visit the unexplored cells **ring by ring** out from the anchor, so near things
  are always found before far ones. Within a ring, go to the cell **nearest the bot** first; plain
  BFS order would zig-zag across the ring.
- **At each cell:** survey it, record landmarks and players, mark it explored.
- **Clues jump the queue (best-first):** a path, torch line, smoke, or a player seen at the edge of
  sight puts its cell at the front. Pure BFS ignores clues; it should be tried that way first, so
  the gain can be measured.
- **Limits:** a radius cap (config, e.g. 512 blocks to start), back to the anchor before night,
  hazards and the leash as today, and a pause when there's work to do (the ladder comes first, as in
  Regroup).
- **Stop or switch:** when a goal is met, e.g. "found a player or bot" for a regroup, or "found a
  village" for the planner.

Regroup becomes: (a) anyone in sight → walk to them; (b) else the nearest remembered landmark where
people were seen; (c) else run the BFS out from the anchor; (d) else world spawn as today.

## 4. Steps

| Step | What | Test |
|---|---|---|
| 0 | Bus off by default, schema 5 | ✅ unit test; live: bot starts, no "local bus on" line in the log |
| 1 | ~~Owner-tools split~~ — decided against (§0) | — |
| 2 | Landmark memory: save survey hits per server, `nearest(tag)` | unit: survey → store → `nearest("bed")` |
| 3 | Village, farm and camp recognisers on survey results | unit, on hand-built block lists; live near a village |
| 4 | Explored-cell grid + ring BFS as a task, with radius and night limits | unit on a fake world; live: logs ring by ring, comes home before night |
| 5 | Regroup uses steps 2–4 instead of bus positions | live: Bot1 300 blocks away, bus off → finds the owner by searching |
| 6 | Clue-first ordering (paths, torches, smoke) | compare against step 4: time to first player |
| 7 | (later) Bots that **meet in the world** swap landmark maps by `/msg` | two bots, one has seen the village → both know it |

## 5. Open questions for the owner

1. ~~**Owner tools**~~ — decided: off with the bus (§0).
2. **Saved memory:** the roster and (soon) landmarks are saved per server. Does "knows nothing" mean
   only on its **first** join, or should each session start empty?
3. **The `/msg` probe** (PHASE2, `chat` transport, already off by default) whispers roster members
   by name. Is that in-game enough to keep, or is it also "knowledge"?
4. **Other players' beds:** fine to infer them (§2), knowing that this guesses?
5. **Recognising bots vs humans** without `HELLO`: does it matter? If yes: a bot can tell from an
   in-game sign (a `/msg` handshake when in sight, or the account roles in its own config).
