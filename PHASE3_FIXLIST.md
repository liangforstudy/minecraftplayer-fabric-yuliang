# Phase 3 fixlist

Bugs and changes found while live-testing Phase 3. Living doc — delete an entry once it is fully
fixed (git history keeps it).

## 1. `/zbot grave` opens someone else's grave, then pauses (2026-09-27, 0.1.35)

**Seen:** the owner typed `/zbot grave` standing near a grave that wasn't theirs. It opened the
grave's screen, then the bot paused (a human at the keys) and the log said
`failed: picking up my grave at 97 62 -34 — because the grave at 97 62 -34 didn't open — not ours?`.
It picks the *nearest* civfabric grave within 16 blocks, whoever it belongs to.

**Fix:** split into two commands:
- `/zbot grave` — **my** grave only: match the grave's owner (name/UUID on the block entity or
  the screen title) to this account; skip everyone else's, fall back to the death spot.
- `/zbot grave loot` — take items from **someone else's** grave, on purpose. Never automatic.
  - `/zbot grave loot` — any nearby grave (not ours).
  - `/zbot grave loot <player> [<player> …]` — only those players' graves; names autocomplete
    (tab list + roster), as many as you like.
  - `/zbot grave loot <me>` = `/zbot grave` (same code path, owner-filter = self); only
    `/zbot grave` also walks to the death spot when no grave is in range.
  - `/zbot grave loot except <player> [<player> …]` — any nearby grave *but* theirs (blacklist).
    Proposed shape for the owner's whitelist/blacklist idea: one-off per command, nothing saved;
    a saved list in `zymbot.json` only if it turns out to be needed.

Also: opening a screen must not count as "a human took the controls" (the pause).

## 2. `/zbot start` on the owner's client turns them into a Bot (2026-09-27)

Not a bug in Bot1, but easy to trip: the owner typed `/zbot start` (to try an order) and their
Teammate client started playing itself — ran its own regroup to spawn and announced as a Bot, so
Bot1 found no Teammate and also went to spawn. Consider: on a Teammate account, `/zbot start` asks
to confirm (or debug orders like `punch`/`survey` work without starting).

**Open questions (owner):**
- a) Confirm on `/zbot start` for a Teammate account, let debug orders run without start, or both?
- b) Should regroup also walk to a **Bot** when no Teammate is around (today it only walks to
  Teammates, so two bots each go to spawn)?
- c) ~~Name~~ → `/zbot grave loot` (owner, 2026-09-27).
- d) Something feels missing after `/zbot start` on a Teammate (owner can't name it yet). Ideas: an
  on-screen "bot is driving" indicator; the roster saying "Teammate (bot driving)" rather than "Bot".

## 4. ~~`/zbot punch` "picked up nothing" the tick the block broke~~ — fixed in source, not yet built

Drops reach the client a few ticks after the block goes; BreakTask now waits for the drop (or the pickup) to arrive, up to the 5 s collect window,
before deciding there are none — no guessed delay (owner: lag and networking vary). Blocks that drop nothing wait the full 5 s; tree felling should skip it per leaf. Delete once live-tested.

## 5. Regroup log: stale reason, no arrival line (2026-09-27, 0.1.35 — regroup itself PASSED)

Bot1 walked ~58 blocks to the owner and stopped beside them. But the reason said "was at 101, -33
(bus, 35 s ago)", where the owner used to be, and it went straight to "idle" with no "done: regrouped
with …" line. Fix: use the freshest position (seen > bus) in the reason; log the arrival.

## 6. One-shot orders on a Teammate account (2026-09-27)

The owner typed `/zbot grave` on their own (Teammate) client without `/zbot start` and got "this
account is a Teammate — orders only work on a Bot account". For a human, "walk me to my grave and
pick it up" is a handy errand. Proposal: on a Teammate, a one-shot order (`grave`, `grave loot`,
`goto`, `come`, `punch`) borrows the controls for that one task and hands them back when done (or when
you touch a key), with no `/zbot start` and without announcing as a Bot. Standing jobs
(`follow`, the planner) still need `start`. May be the "something missing" of #2 d.

## Built, needs live test

- #3 `/zbot debug survey`: the background scan prints in chat when done; an old one is labelled "Ns ago, N blocks X of here".
- #7 pause message: new wording (`notifyLocal`), unit-tested.
- #8 `/zbot debug …` (terrain, block, foods, punch, survey), `/zbot see` = status, `/zbot see <bot>` over the bus (STATUS → STATUS_LINE, 5 s backstop) — both clients need the new build; check roster autocomplete.
