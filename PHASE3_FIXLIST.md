# Phase 3 fixlist

Bugs and changes found while live-testing Phase 3. Living doc: delete an entry once it is fully
fixed **and** live-tested (git history keeps it).

Status: **0.1.37 built, committed (`3485b38`, not pushed) and installed on both sides (owner's client +
Bot1), 2026-09-28.** It has the shallow-water fix. The 0.1.36 tests all passed live on 2026-09-28 except
`/zbot see Nobody`.

## Built in 0.1.36, needs a live test

Found in the 0.1.35 live test (2026-09-27). Check each, then delete its line.

| # | what | how to test | pass when |
|---|---|---|---|
| 1 | **PASS 2026-09-28** (own grave: Bot1 `chose Bot1's grave` → picked up at once, bread back; stranger's: owner got `no grave of mine in sight (skipped: Bot1) — walking back to where I died`, no pause) — `/zbot grave` = **our own** grave only (owner from the grave block entity's `owner` / `owner_id`) | stand near someone else's grave, `/zbot grave` | skips it (reply names who was skipped), falls back to the death spot; no pause while the screen is open |
| 1 | `/zbot grave loot` · `loot <player> […]` · `loot except <player> […]` | near another player's grave | opens it, shift-clicks everything, closes; `looted N of M stacks`; names tab-complete |

## Seen in the 2026-09-28 test (not fixed)

- **Regroup gave up fast after a respawn far away:** `failed: regrouping with Bluetails_zym … — no path, even swimming — the pathfinder gave up 169 blocks short` 2 s after respawning ~170 blocks off (server at 15 TPS, "planning less"). It retries after 60 s; check whether it should walk toward them through unloaded chunks instead of failing.
- **Stuck in water: FIXED, passed live in 0.1.37 on 2026-09-29.** It stepped out in 1 s and 4 s, with no "3 tries" loop. Was: the dry-land finder counts one-block
  shallows as shore (head above water, owner's 2026-09-26 rule), but WadingInterrupt ("idle, standing in
  water") still counts them as water. So it walked into the shallows at 97 -50 (sand at y 61, water at 62),
  was still wet, bobbed, and gave up after 3 tries, several times today. Owner spotted it ("probably the 1
  block water"). Now WadingInterrupt only accepts land with dry feet; the drowning and stranded reflexes
  still accept shallows. Test: `idleInWater_onlyShallowsNearby_doesntWalkIntoThem`. Built into 0.1.37; live test next session.
- `/zbot grave` falls back to a very old death spot (29 70 -191, far away). Consider forgetting a death spot once its grave has been picked up, or after N minutes, or asking first when it is far.

## Survey: give positions, and walk to what it found (2026-09-28) - to build

#3 passed live on 2026-09-28. The new survey printed in chat by itself, and the old one was labelled
"140s ago, 205 blocks S of here". But "nearest tree 14 blocks E" has no coordinates: `/zbot goto ~15 ~0`
got Bot1 within 2-3 blocks of the dark cherry tree (the owner confirmed it was in punching range), which was
luck of direction.
- `/zbot debug survey` prints coordinates: "tree 14 blocks E at 55 72 -196".
- `/zbot goto tree` (also `food`, `table`, `bed`, ...) walks to the nearest one the survey found, to
  reach. This is where Phase 3 foraging is heading anyway.

## Break and collect: other players (2026-09-28) - to build

#4 passed live: Bot1 broke `vinery:dark_cherry_log` at 40 71 -193 in 3.5 s and waited the full 5 s
for the drop (it used to give up the same tick). The owner, standing next to it, **took the drop**.
In real play players and mobs can snatch drops (owner). Already right: BreakTask won't swing at air.
It checks the block before starting and on arrival ("nothing to break", "the block changed while
walking there"), because swinging at air could look odd to a server's anticheat (owner).
- **Say who got it.** A drop it saw appear, then vanish without reaching its inventory: "the drop was
  taken (someone else picked it up)", not "picked up nothing". None ever appeared: "no drop".
- **Someone else breaks it mid-dig.** If another player breaks the block while Bot1 is digging, it
  turns to air and Bot1 logs "broke" as if it had. Tell the two apart: our own dig progress was near
  done, or the drop landed beside us.
- **Blocks players placed.** The owner put the same log back to test it. A `punch` order still breaks
  it (fine, it was ordered). But for the planner's own gathering on a civ server, a player-placed block
  can be someone's build: breaking it is griefing. Gathering should prefer natural blocks (whole trees
  with leaves, away from builds) and leave blocks near other players' builds and claims alone.
  Fits Phase 3's CLAIMs and tree rules.

Passed live 2026-09-28 and removed from the table: #2 (roster: "Teammate (bot driving)"), #6 (borrow, then a
movement key: "gave the controls back", no pause), #7 (pause wording, "resumes in 3s", resumed after 10 s),
#5 (a 46 s old bus position -> asked first -> "was at 201, -26 (bus, 0 s ago)" -> "regrouped with
Bluetails_zym"), #8 half (`/zbot see Bot1`: all status lines in the owner's chat the same second).
#8 `see Nobody` ("no answer from Nobody in 5s", 2026-09-29). **All 0.1.36 tests passed.**

## Regroup says nothing when the team is already near (2026-09-28) - to build

`/zbot regroup` with the owner 6 or 20 blocks away (in sight, within `regroup` 32) prints "looking for
the team — regrouping unless a teammate is within 32 blocks", then logs nothing at all, so it looked
ignored twice in the live test. Log it: `with the team already — Bluetails_zym 6 blocks away (within 32)`.

## Seen 2026-09-29

- **Spinning while swimming to dry land.** Right after the owner teleported Bot1 to them 7 times in 10 s:
  `swimming to dry land at 97 -71 — because out of its depth with nothing to do`, then 13 s later
  `holding still — because bobbing/spinning in place for 10 s (turned 15327°, moved 1.4 blocks, 26 ups
  and downs)`. The fidget watchdog's first live catch (PHASE2 had it "unseen live"), so the safety net
  works. But about 42 turns in 10 s means the surface-swim steering fights itself.
  **Cause (owner, watching it):** it tried to climb out at **beach corners**, where the land touches its
  shallow-water block only diagonally. Jumping out over a corner catches the hitbox on the two side
  blocks, so it keeps turning for an angle that never works.
  **Fix idea:** when choosing where to climb out, prefer a land block that is *straight* next to a water
  block (a face, not a corner), and steer to face it squarely before jumping. If only corners are near,
  swim one block along the shore first. Add a unit test with a corner-only shoreline.
  **Fixed in source 2026-09-29 (in 0.1.38, not built):** Stranded and Drowning only pick land with water
  straight beside it (`SurfaceTask.faceOnShore`). SurfaceTask swims to that water block first, then turns
  square to the land and climbs. Tests: `SurfaceTaskTest` (corner-only rejected, face-on chosen, nearest
  side wins). Live test next: a beach corner.
- **Starvation loop until foraging exists.** Bot1 respawns at hunger 8 (civfabric doesn't refill food).
  It had no food, swam to the owner (swimming burns ~2.6 food/min against 0.9 walking) and starved again.
  The respawn survey had found wild beetroots 14 blocks away. Phase 3 M1 (foraging) breaks the loop.
- **Console (Windows Terminal) passed:** F2 reopens the log pane on top (swap-pane works); R reopens the
  command pane with the backlog divider above the prompt; closing the tab stops the bot. Still to see:
  the console opening minimised (next `play.bat`).
- **Without Baritone** (tested, see ROADMAP #13): everything that moves fails cleanly; nothing crashes.

## Open questions for the owner

From #2 (`/zbot start` on a Teammate account):
- a) Should `/zbot start` on a Teammate account ask to confirm first?
- b) Should regroup also walk to a **Bot** when no Teammate is around? Today two bots each go to spawn.

## 9. "Bot is driving" indicator + `/zbot history` (owner, 2026-09-27) — planned, not built

Answers #2 d. Two parts: the **action bar** says what the bot is doing *now*; a **history screen**
shows what it *was* thinking. Chat stays clean: nothing is printed there, and nothing is ever sent
to the server. (Silent chat lines were considered and dropped: vanilla keeps only 100 chat lines,
so decisions would push real chat out, and the pack has no chat-history mod.)

### 9a. Action bar
- While Zymbot has this client's controls (`/zbot start`, or a one-shot order borrowing them):
  "Zymbot driving — <current task describe()>", refreshed about once a second (only when the text
  changes, plus a re-send before vanilla's ~3 s fade).
- While paused: "paused — resumes in 7s · /zbot stop to stop the bot". Gone when the human has the
  controls for good (stopped, or a Teammate not borrowing).
- `player.displayClientMessage(text, true)`: client-side only.
- Config `indicator_action_bar` (default on).

### 9b. History log (core, pure Java)
- New `core/brain/History`, fed by `DecisionLog.onRecord` plus new sources. Entries carry a **kind**:
  - DECISION: started / done / failed / other, from the verb
  - CONTROL: took the controls, paused, resumed, gave them back, borrowed / returned
  - TEAM: bus traffic worth seeing, e.g. heard HELLO/WHERE answers, see/watch asks, summons
- A ring buffer of `history_size` entries (default 2000, range 100–20000).
- Saved per world/server to `config/zymbot/history-<server>.log` (one line per entry, appended, and
  trimmed on load), so it survives a restart. Config `history_save` (default on).
- Search and filter are plain functions: `filter(kinds, text)` → entries, unit-tested.

### 9c. The screen (Fabric)
- `/zbot history`, plus a customizable keybind **left unbound by default** (owner: H is taken by
  Simple Voice Chat; set it in Options → Controls → Key Binds → Zymbot) opens
  `ZymbotHistoryScreen`, styled like `ZymbotSettingsScreen`.
- Rows, newest first:
  - time
  - icon: → started, ✔ done, ✖ failed, ⏸ control, 📡 team
  - the decision, with the "because" line dimmed underneath
  - long lines wrap
- Top: filter tabs [All] [Decisions] [Control] [Team], a search box (live filter) and ⟳ refresh. It
  also updates live while open.
- Bottom: "N entries · kept: last 2000", [Copy] (the visible entries to the clipboard, as plain text
  for bug reports) and [Done].
- Scroll with the mouse wheel or the scrollbar; Esc closes. A GUI scale / phone-narrow check isn't
  needed, but it must fit at GUI scale 4 on 1080p.

### 9d. Another bot's history: `/zbot history <bot>` (later, optional)
- Over the bus, like `see <bot>`: HISTORY request → the last N (e.g. 200) entries back as numbered
  lines. It opens the same screen, titled "Bot1 — what it was thinking". Needs both clients updated.
- Only if 9a–9c prove useful. The headless bot's own log already has everything.

### Settings
- `indicator_action_bar`, `history_size`, `history_save`: in Mod Menu → Zymbot, on a **new "Display" tab** (owner, 2026-09-28)
  and `/zbot set`, with ↺ reset like the other rows.

### Build order and tests
1. 9b core plus tests: ring buffer, kinds from verbs, filter/search, save/load with trimming.
2. 9a action bar. Test: a fake `Hands` notified on state changes.
3. 9c screen, then docs: SKILL.md (both copies), PHASE1.md, `help()`.
4. Live test: `/zbot start` → action bar; W → paused countdown; `/zbot history` shows the lot;
   search "grave"; restart the game → history still there.
5. 9d only if asked.

## Notes

- #10 (fixed 2026-09-28): `bots.ps1` / `bots.py` now check that `console.json`'s PID really is our relay for that bot, not just some process, because PIDs get reused. Both were tested on Windows with a fake stale file (Python 3.12 installed 2026-09-28); the `ps` branch of `bots.py` (Mac/Linux) is still untested.

- Tree felling (Phase 3) should skip the drop wait per leaf: most leaves drop nothing, and each would
  wait out the 5 s backstop.
- The new Fabric code compiles, but only a live test proves the grave owner is read right
  (`saveWithoutMetadata` → `owner` / `owner_id`).
