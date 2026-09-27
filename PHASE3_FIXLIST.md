# Phase 3 fixlist

Bugs and changes found while live-testing Phase 3. Living doc — delete an entry once it is fully
fixed (git history keeps it).

## 2. `/zbot start` on the owner's client — what's left (2026-09-27)

Role vs state is built (see "Built, needs live test"): a started Teammate still announces as a
Teammate, and one-shot orders no longer need `start` (#6). Still open for the owner:
- a) Should `/zbot start` on a Teammate account ask to confirm?
- b) Should regroup also walk to a **Bot** when no Teammate is around (today it only walks to
  Teammates, so two bots each go to spawn)?
- d) An on-screen "bot is driving" indicator?

## 3. `/zbot survey` prints the old survey, not the new one (2026-09-27, 0.1.35)

**Seen:** Bot1 at spawn typed `/zbot survey`: chat showed the survey from 838 s ago, taken where it
joined 85 blocks E — "spawn 85 blocks W", "tree 32 blocks NE" were true *there*, not here. The new
one (1 tree, 52 blocks NE) only reached the log. **Fix:** when the background scan finishes, print
it to whoever asked; label an old one with where it was taken ("838 s ago, 85 blocks E of here").

## 4. ~~`/zbot punch` "picked up nothing" the tick the block broke~~ — fixed in source, not yet built

Drops reach the client a few ticks after the block goes; BreakTask now waits for the drop (or the pickup) to arrive, up to the 5 s collect window,
before deciding there are none — no guessed delay (owner: lag and networking vary). Blocks that drop nothing wait the full 5 s; tree felling should skip it per leaf. Delete once live-tested.

## 7. Pause message wording (2026-09-27)

Now: "zymbot paused — you have the controls. It resumes 10s after you stop." Owner's wording:
"zymbot paused — you can move yourself around for now. It goes back to automation mode 10s after you
stop, or /zbot stop to stop the bot." The "resumes in 3s" line stays as is.

## 8. Command tidy-up (2026-09-27, owner suggestion, not a blocker)

- Debug commands under `/zbot debug …`: `terrain`, `block`, `foods`, `punch`, `survey` (owner named
  terrain + food; the rest to confirm).
- `/zbot see` = `/zbot status`.
- `/zbot see <bot>`: a Teammate reads a bot's status. Needs a new bus request/reply (STATUS), since the
  bot's state lives on the bot; the answer is shown in the asker's chat. Autocomplete from the roster.
- Update SKILL.md (both copies), PHASE1.md and `help()` together (SkillDocTest).

## Built, needs live test

- #1 `/zbot grave` = our own grave only (owner = the grave block entity's `owner`/`owner_id`, sent to clients in its update tag); `grave loot [except] [<player> …]` shift-clicks other players' graves; no pause while any screen is open. Check the owner reads right, and a stranger's grave is skipped.
- #2 HELLO carries the role (3rd field; old clients still parse): roster shows "Teammate (bot driving)", regroup picks by role.
- #5 A bus position > 10 s old is asked about (WHERE) before the regroup sets off (5 s backstop); arrival logs `regrouped with <name>`.
- #6 On a Teammate, `goto`/`come`/`punch`/`grave`/`grave loot` borrow the controls for that order (`borrowed the controls` … `gave the controls back`); a movement key ends it.
