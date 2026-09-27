# Phase 3 fixlist

Bugs and changes found while live-testing Phase 3. Living doc: delete an entry once it is fully
fixed **and** live-tested (git history keeps it).

Status: **0.1.36 built, committed (`0cb9adf`, not pushed) and installed on both sides (owner's
client + Bot1), 2026-09-27. Nothing below is live-tested yet.**

## Built in 0.1.36, needs a live test

Found in the 0.1.35 live test (2026-09-27). Check each, then delete its line.

| # | what | how to test | pass when |
|---|---|---|---|
| 1 | `/zbot grave` = **our own** grave only (owner from the grave block entity's `owner` / `owner_id`) | stand near someone else's grave, `/zbot grave` | skips it (reply names who was skipped), falls back to the death spot; no pause while the screen is open |
| 1 | `/zbot grave loot` · `loot <player> […]` · `loot except <player> […]` | near another player's grave | opens it, shift-clicks everything, closes; `looted N of M stacks`; names tab-complete |
| 2 | HELLO carries the role (3rd field) | owner `/zbot start` on their own client, then Bot1 `/zbot roster` | owner shows as "Teammate (bot driving)", not "Bot"; Bot1's regroup still walks to them |
| 3 | `/zbot debug survey` prints the new scan when done | Bot1 walks somewhere, then `/zbot debug survey` | old one labelled "Ns ago, N blocks X of here", new one arrives in chat |
| 4 | `/zbot debug punch` waits for the drop to arrive (5 s backstop), no guessed delay | punch a grass block | `picked up 1 dirt`, not "picked up nothing" |
| 5 | regroup asks WHERE first when the bus position is > 10 s old; logs the arrival | owner walks > 32 blocks away, `/zbot regroup` | reason gives the owner's *current* spot; ends with `regrouped with Bluetails_zym` |
| 6 | one-shot orders on a Teammate: `goto` / `come` / `punch` / `grave` / `grave loot` borrow the controls | owner types `/zbot debug punch ~1 ~-1 ~` on their own client, no `/zbot start` | `borrowed the controls` … `gave the controls back`; a movement key ends it early; `follow` still refused |
| 7 | pause wording | owner `/zbot start`, then press W | "zymbot paused — you can move yourself around for now. It goes back to automation mode 10s after you stop, or /zbot stop to stop the bot." |
| 8 | `/zbot debug …` group; `/zbot see` = status; `/zbot see <bot>` over the bus | owner `/zbot see Bot1` | Bot1's status lines in the owner's chat within a second; `see Nobody` → "no answer … in 5s" after 5 s |

## Open questions for the owner

From #2 (`/zbot start` on a Teammate account):
- a) Should `/zbot start` on a Teammate account ask to confirm first?
- b) Should regroup also walk to a **Bot** when no Teammate is around? Today two bots each go to spawn.
- d) An on-screen "bot is driving" indicator?

## Notes

- Tree felling (Phase 3) should skip the drop wait per leaf: most leaves drop nothing, and each would
  wait out the 5 s backstop.
- The new Fabric code compiles, but only a live test proves the grave owner is read right
  (`saveWithoutMetadata` → `owner` / `owner_id`).
