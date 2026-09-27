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

## 4. `/zbot punch` "picked up nothing" the tick the block broke — built, needs live test

Drops reach the client a few ticks after the block goes; BreakTask now waits for the drop (or the pickup) to arrive, up to the 5 s collect window,
before deciding there are none — no guessed delay (owner: lag and networking vary). Blocks that drop nothing wait the full 5 s; tree felling should skip it per leaf. Delete once live-tested.


## Built, needs live test (0.1.36)

- #4 `/zbot debug punch` waits for the drop, see above.
- #3 `/zbot debug survey`: the background scan prints in chat when done; an old one is labelled "Ns ago, N blocks X of here".
- #7 pause message: new wording (`notifyLocal`), unit-tested.
- #8 `/zbot debug …` (terrain, block, foods, punch, survey), `/zbot see` = status, `/zbot see <bot>` over the bus (STATUS → STATUS_LINE, 5 s backstop) — both clients need the new build; check roster autocomplete.
- #1 `/zbot grave` = our own grave only (owner = the grave block entity's `owner`/`owner_id`, sent to clients in its update tag); `grave loot [except] [<player> …]` shift-clicks other players' graves; no pause while any screen is open. Check the owner reads right, and a stranger's grave is skipped.
- #2 HELLO carries the role (3rd field; old clients still parse): roster shows "Teammate (bot driving)", regroup picks by role.
- #5 A bus position > 10 s old is asked about (WHERE) before the regroup sets off (5 s backstop); arrival logs `regrouped with <name>`.
- #6 On a Teammate, `goto`/`come`/`punch`/`grave`/`grave loot` borrow the controls for that order (`borrowed the controls` … `gave the controls back`); a movement key ends it.
