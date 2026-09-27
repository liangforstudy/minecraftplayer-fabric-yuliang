# Fix list

Known bugs, found in testing, not yet fixed. Remove an entry when its fix is committed.

## 3. Headless Bot1 often times out on its first joins

- **Found:** 2026-09-22, LAN tests (rig, not Zymbot).
- **Seen:** after a fresh standby, `connect` → "CONNECTED THEN DROPPED"; the host logs
  `Bot1 … lost connection: Timed out`. 18:04 (1 attempt), 18:25–18:27 (3 attempts); the next try
  got in each time.
- **Cause (found 2026-09-22, 21:03):** the *host* is overloaded right after its world loads — its
  log shows `Can't keep up! … 11074ms or 221 ticks behind` between the time-outs; once Bot1 even
  `logged in` and then timed out. Both games load the 90-mod pack on one Mac at once.
- **Mitigated (0.1.6+):** auto-summon repeats every 15 s for 5 min, so a dropped join retries.
- **Tried (0.1.12):** the host holds the auto-summon until its own server keeps up (under 50 ms/tick
  for 5 s, 60 s at most). Live 21:37: it waited 40 s, then Bot1 still dropped **7 times** before
  getting in at 21:40:17 — so host lag isn't the main cause.
- **Now known:** every failed attempt logs `Received server config` (+ Visual Workbench config
  reload) and then **nothing for ~18 s** until the host says `Timed out`; the host isn't lagging
  then. Bot1's client stalls in the configuration phase.
- **Thread dump 4 s into a stall (21:46):** the main thread is in `Blocks.rebuildCache()` —
  recomputing every block state's shape cache after the server's registries arrive (huge with
  Macaw's & co.); the network thread is idle. Retries get faster as the JIT warms up.
- **Keep-alive theory — not confirmed:** vanilla 1.21.1 already answers keep-alives from the network
  thread (`sendWhen(…, !RenderSystem.isFrozenAtPollEvents(), 1 min)`), so a busy main thread
  shouldn't block them. 0.1.13 answers them unconditionally anyway and logs each one
  (`[zymbot] keep-alive … thread, frozen-at-poll=…`); the next failed join will show whether one
  arrived and how it was answered. 21:54: joined first try in 15 s — no keep-alive during it.
- **Keep-alive log verdict (22:04):** in both failed joins *no* keep-alive reached the client's
  handler at all — during the rebuild the client isn't reading the connection. So answering faster
  can't help; the keep-alive mixin was removed again in 0.1.16.
- **Warm-up tried and dropped (0.1.15):** running the rebuild twice at startup took **6.0 s and 6.1 s** —
  no JIT gain, so it can't make a join's rebuild quicker. Removed in 0.1.16.
- **0.1.34 (2026-09-27):** a failed summoned join retries 4× with backoff (10/20/40/80 s); after `play.bat` Bot1 got in on the first retry by itself.
- **So the real cause:** alone the rebuild is ~6 s; during joins it stalled ~20 s — the host game
  running on the same Mac at the same time (the owner's guess, now measured). Joins with the
  auto-summon's retries still get in. Expected to mostly vanish with bots on their own machine
  (the real setup); re-check there. Left open until then.

## 5. Nettle tea cup doesn't heal — Farm & Charm, not Zymbot

- **Seen:** 2026-09-22, drank two at health 5; health stayed 5.
- **Suspected cause:** Instant Health with duration 0 in a food item — never applied (see
  SURVIVAL_EARLY_GAME §1.3 note). Upstream; report in your own words once confirmed by hand.
- **Bot side (0.1.12):** a food's instant effect only counts as healing if its duration is ≥ 1
  tick, so the bot no longer keeps nettle tea back as a heal (or reaches for it at critical health).
- **For the report** (facts to put in your own words): Farm & Charm 1.1.23 on 1.21.1,
  `farm_and_charm:nettle_tea_cup`; its `EffectJugItem` is registered with Instant Health, duration
  0; drinking it at low health restores nothing. Instant effects applied through food only fire
  while duration ≥ 1, so a duration of 1 (or applying it directly, like a potion) would fix it.
  Ribwort tea (Regeneration, 60 ticks) is unaffected.

## 8. Ate bread (2) when an apple (4) was there — not reproduced

- **Seen:** 2026-09-22 23:1x, 0.1.20, hunger test with live values: chicken (6, right), then **bread**,
  then apple… Live values at the start: chicken 6, apple 4, bread/beef/potato 2, carrot/tea 1.
- **Not class-related:** civfabric's class food rules only cover 9 "meals" (cake needs FARMER 5);
  bread and apple have none.
- **Guess:** read mid-update — Spice of Fabric rewrites the values right after a bite.
- **Re-test (0.1.21, 23:3x): not reproduced** — 6 picks, each the highest value it saw at that moment
  (ties go to the one eaten least lately); values visibly fall after a bite and climb back as meals
  rotate out of Spice's 11. Most likely a read mid-update. The logging stays, so a repeat shows why.

## 9. Died to one zombie: ran too late, too slowly, then stopped running

- **Seen:** 2026-09-25 14:10, 0.1.24, Windows LAN world, Bot1 full health, no food, one summoned
  zombie 3 blocks away. Health 20 → 7 in 3 s (this pack's zombies hit far harder than vanilla), so
  the critical-health retreat (≤ 8) started with almost nothing left; it covered ~3 blocks in 5 s;
  at health 2 it logged "no healing food, and **nothing to run from**" with the zombie still on it —
  knocked out, died 17 s after the summon.
- **Why it stopped:** the attacker was remembered as a snapshot of where it stood at the first hit,
  for 10 s — later hits didn't refresh it, so the memory ran out mid-fight.
- **0.1.25:** the attacker is remembered by UUID and followed live, every hit (and a chase within
  16 blocks) refreshes the memory, an unnamed mob hit is pinned on the nearest hostile within 6
  blocks, the retreat re-plans when the attacker moves 6 blocks, and `/zbot danger` picks when to run:
  `modpack` (default) at the first hit; vanilla `easy`/`normal`/`hard` at critical health −2/±0/+4.
- **Live test of 0.1.25 (2026-09-26, test agent):** ran at the first hit and survived (health 11),
  never "nothing to run from" — but the retreat ended and restarted ~60 times in 36 s (~2 blocks/s):
  it started under 16 blocks and was done at 16, and a chasing zombie kept it on that line. It also
  ended in a lake ("no dry path") and then floated for minutes: only the drowning reflex acted, and
  it just surfaces.
- **0.1.26:** the retreat is done only at 32 blocks (starts under 16) and logs where it runs to; a new
  "stranded" reflex swims to the nearest place to stand (48 blocks) whenever the bot is afloat with no
  order; places to stand = any block with a top that doesn't hurt (gravel, slabs, panes, trapdoors…),
  including one layer of water on top (owner, 2026-09-26), head clear and dry.
- **Also:** 0.1.26 ignores repeat summons for 60 s after acting on one — a repeat mid-join (this pack
  takes 20–35 s to join) restarted the connection; Bot1 needed 7 tries on 2026-09-26.
- **Live test of 0.1.26 (2026-09-26, night):** one continuous retreat — but down a staircase of 1-4
  block ledges into the lake (each drop is within Baritone's limit of 3), and the new swim-to-shore
  reflex swam back to the zombie's shore (health 7), restarted 47 times bobbing in one-block
  shallows, and the retreat gave up at a fixed 20 s.
- **0.1.27:** the retreat tries straight away, then ±45°, then ±90° on dry ground only before it may
  swim, and gives up only after 20 s without gaining distance; "stranded" needs water under the feet
  too and 2 s afloat, and never picks a shore within 16 blocks of the last attacker (or 8 of a hostile).
- **Live test of 0.1.28 (13:00, night, healed, no food):** survived at health 11, stayed on dry
  land (y 72); three separate retreats as the zombie caught up again after each; hunger 11 → 6 from
  sprinting — a long chase without food is still a problem.
- **0.1.28:** a summoned join that fails (first join after a boot times out: the host allows 15 s,
  this pack's config data takes the bot ~16 s) is retried once; `connect.bat` retries once too.
- **Re-test:** deferred by the owner until all phases are built — each mode, in this pack and vanilla.## Fixed

Fixed and verified (details in git history, commit "Zymbot 0.1.9"): #1 stale "last heard" after a
hard stop, #2 drowning at the shore, #4 two bites per eat, #6 quick death left the order running
(re-tested live 2026-09-22: `goto`, `/kill` mid-walk → order cancelled, idle after respawn), #7 a
benched reflex blind to a new attacker (re-tested: retreat 2 s after a provoked zombie's hit —
but it walked, and the zombie kept up; 0.1.10 runs instead).

## 10. Regroups across water give up past render distance

- **Seen:** 2026-09-26, `failed: regrouping … — no path, even swimming — the pathfinder gave up 386 blocks
  short` (and 456) — the teammate was across the sea, beyond the loaded chunks.
- **Why:** the route planner and Baritone only see loaded chunks (render distance 6 ≈ 96 blocks).
- **Idea:** read Baritone's chunk cache (`gamedir/baritone/<server>/…`) so the planner sees terrain the
  bot has visited before. Unvisited ocean stays unknown either way.
