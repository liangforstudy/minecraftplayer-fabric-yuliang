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

## Fixed

Fixed and verified (details in git history, commit "Zymbot 0.1.9"): #1 stale "last heard" after a
hard stop, #2 drowning at the shore, #4 two bites per eat, #6 quick death left the order running
(re-tested live 2026-09-22: `goto`, `/kill` mid-walk → order cancelled, idle after respawn), #7 a
benched reflex blind to a new attacker (re-tested: retreat 2 s after a provoked zombie's hit —
but it walked, and the zombie kept up; 0.1.10 runs instead).
