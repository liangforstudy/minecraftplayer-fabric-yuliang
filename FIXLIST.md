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
- **Fix idea:** the host holds the auto-summon until its own server keeps up (smoothed tick time
  under 50 ms for ~10 s), instead of summoning into the lag.

## 5. Nettle tea cup doesn't heal — Farm & Charm, not Zymbot

- **Seen:** 2026-09-22, drank two at health 5; health stayed 5.
- **Suspected cause:** Instant Health with duration 0 in a food item — never applied (see
  SURVIVAL_EARLY_GAME §1.3 note). Upstream; report in your own words once confirmed by hand.

## Fixed

Fixed and verified (details in git history, commit "Zymbot 0.1.9"): #1 stale "last heard" after a
hard stop, #2 drowning at the shore, #4 two bites per eat, #6 quick death left the order running
(re-tested live 2026-09-22: `goto`, `/kill` mid-walk → order cancelled, idle after respawn), #7 a
benched reflex blind to a new attacker (re-tested: retreat 2 s after a provoked zombie's hit —
but it walked, and the zombie kept up; 0.1.10 runs instead).
