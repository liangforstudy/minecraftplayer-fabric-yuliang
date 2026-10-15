# Finding the world roadmap (draft)

Status: **plan, 2026-10-15**, from the owner. Step 0 is done; step 1 (the chat handshake) is built, waiting for a live test.

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
| Roster (`HELLO`, `ROSTER`) | knowing who is a Zymbot | replaced by the chat handshake (§4) |
| `/zbot summon`, auto-summon on LAN | `SUMMON` to bots at the title screen | **off**: refused with a message; start bots with play / run-bot |
| `/zbot see <bot>`, `/zbot watch` | `STATUS`, `STATUS_LINE`, `WATCH` | **off**: refused with a message; use the bot's own `/zbot status` (`send.bat`) or its log |
| Knocked-out alert | `DOWNED` | teammates aren't told; nearby humans still get the `/msg` |

**Decided (owner, 2026-10-15): nothing crosses the network with the bus off**, not even `/zbot see`
and `/zbot watch` from the owner's client. (0.1.44 briefly kept them on a position-free
`owner_tools` channel; that was a misreading and is removed.) A `BOT` account still refuses
`/zbot see <other bot>`. Turning `local_bus` back on brings everything back, for testing.
Bots tell bots from humans **in game chat** instead (§4).

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

## 4. Bot or human: the chat handshake

The owner's plan (2026-10-15): bots talk to each other **in game chat** to tell bots from humans. A
bot asks a player in a fixed format; **a player who doesn't reply in that format is a human.**

**Decided (owner, 2026-10-15):** only players a bot **meets in sight** are asked; whispering
strangers is fine; the owner's own client **stays silent**, so bots take the owner for a human.
**Built the same day** (`team/Handshake.java`, `HandshakeTest`):

- **Reach (owner, 2026-10-16):** every player the **server** sends us (`WorldView.playersInView()`,
  the client's player list, no distance cap), not our 32-block `nearby()`. Bot1 logs
  `X came into view — N blocks away` / `went out of view`, so the limit can be read off. Live, it was
  **32 blocks**: the owner hosts, and their render distance was 2 chunks; the integrated server sends
  players only within its view distance. A dedicated server uses its own `view-distance` (often ~150).
- **When:** the first time a Bot that is playing sees a player (`playersInView()`), once per player per
  world. Verdicts last the session for now; a "human" is asked again after 30 min. **Decided (owner,
  2026-10-16): in real runs a bot remembers who is a bot and who is a player** (saved per server, like
  the roster). Session-only is kept for testing, where leaving and rejoining the world is the quick reset.
- **The ask** is a whisper, never public chat: `/msg <player> zym? <nonce>`, where the nonce is a few
  random characters. A human sees one short, odd-looking line, so it says what it is:
  `zym? 4f2a (Zymbot bot check, no need to reply)`.
- **The answer:** a Bot that is playing whispers back `zym! <nonce> <tag>`. The tag is a
  signature of the nonce and both names made with the **team key** (16 characters), so a human
  copying the format can't fake it, and a Zymbot from another team (other key) is told apart.
  A bot's client keeps the ask, the answer and its own echoed `/msg` off its chat screen. The
  owner's client answers nothing and hides nothing; a stopped Bot (a human has it) is silent too.
- **Verdict:**

  | Reply | The player is |
  |---|---|
  | `zym!` with a good tag | a **bot on our team** |
  | `zym!` with a bad tag | a **Zymbot bot from another team** (or someone copying the format: never "ours") |
  | nothing in ~10 s, or anything else | a **human** |

- **Mistakes and limits:**
  - A bot that is lagging, loading or kicked misses the window and is taken for a human. One retry
    a minute later (a late first answer still counts until then), then "human".
  - **Rate limits:** at most one new ask every 3 s and one answer per asker every 5 s, on top of
    `ChatOut`'s one line per 1.5 s.
  - Servers whose `/msg` reads differently need the chat reader taught their format (PHASE2 §3
    "to verify first").
- **PHASE2 P2-4 changed** ("nobody outside the team is ever whispered to"): the ask goes to players
  not known yet, humans included. It carries no coordinates and no secret. Owner's yes, 2026-10-15.
- **Seen in** `/zbot status`: "players met (chat check): Bot3: a bot on our team, Bluetails_zym: a
  human", and in the log: `[decision] asked X in chat: bot or human?` / `X is a human — because …`.
- **Built on:** `ChatOut.whisper` and `ChatIn` exist. The sealed chat transport (PHASE2 §3) does
  not, and isn't needed: only the tag uses the key.

## 5. Steps

| Step | What | Test |
|---|---|---|
| 0 | Bus off by default, schema 5; nothing crosses the network with it off | ✅ unit tests; live 0.1.44 (see / watch then still on, removed after) |
| 1 | Chat handshake (§4): ask, answer, verdict, remembered per player | ✅ unit (`HandshakeTest`, 9 tests). **Live 2026-10-16 (0.1.46):** Bot1 met the owner, asked 00:39:31, again 00:40:31, "Bluetails_zym is a human" 00:40:41, shown in `/zbot status`. To do: Bot3 → team bot; Bot1's own "You whisper to …" echo showed on its chat. **Cause (0.1.47):** on the owner's offline LAN, whispers arrive as **system messages**, not player chat, so the first version never saw them (no bot answered another). Now both routes are handled (sender read from "X whispers to you: …"), and each chat-check line logs its route. Live: Bot1's echo hidden. Bot-to-bot to retest |
| 2 | Landmark memory: save survey hits per server, `nearest(tag)` | unit: survey → store → `nearest("bed")` |
| 3 | Village, farm and camp recognisers on survey results | unit, on hand-built block lists; live near a village |
| 4 | Explored-cell grid + ring BFS as a task, with radius and night limits | unit on a fake world; live: logs ring by ring, comes home before night |
| 5 | Regroup uses steps 1–4 instead of bus positions | live: Bot1 300 blocks away, bus off → finds the owner by searching |
| 6 | Clue-first ordering (paths, torches, smoke) | compare against step 4: time to first player |
| 7 | (later) Bots that **meet in the world** swap landmark maps by `/msg` | two bots, one has seen the village → both know it |

Until step 5, a bot with the bus off can't hear anyone, so regroup always ends at world spawn
(seen live 2026-10-15: Bot1 left its tree and swam to spawn). Stopping that walk with the bus off
is a small fix that can come first.

## 6. Open questions for the owner

1. ~~**Whispering strangers**~~ — decided: yes.
2. ~~**Who gets asked**~~ — decided: only players met in sight.
3. ~~**The owner's own client**~~ — decided: silent, a human to the bots.
4. **Saved memory:** the roster and (soon) landmarks are saved per server. Does "knows nothing" mean
   only on its **first** join, or should each session start empty?
5. **The `/msg` probe** (PHASE2, `chat` transport, already off by default) whispers roster members
   by name. Is that in-game enough to keep, or is it also "knowledge"?
6. **Other players' beds:** fine to infer them (§2), knowing that this guesses?
7. ~~**Recognising bots vs humans**~~ — decided: the chat handshake (§4).
