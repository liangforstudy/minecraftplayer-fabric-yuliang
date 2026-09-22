# Fix list

Known bugs, found in testing, not yet fixed. Remove an entry when its fix is committed.

## 3. Headless Bot1 often times out on its first joins

- **Found:** 2026-09-22, LAN tests (rig, not Zymbot).
- **Seen:** after a fresh standby, `connect` → "CONNECTED THEN DROPPED"; the host logs
  `Bot1 … lost connection: Timed out`. 18:04 (1 attempt), 18:25–18:27 (3 attempts); the next try
  got in each time.
- **Suspected:** the headless client is still busy on its first join (registry sync, resource
  work with 89 mods) past the server's 30 s login timeout. Not yet investigated.
- **Fix idea:** `connect` retries a timed-out join by itself (2–3 tries, a few seconds apart), and
  reports the attempts.

## 5. Nettle tea cup doesn't heal — Farm & Charm, not Zymbot

- **Seen:** 2026-09-22, drank two at health 5; health stayed 5.
- **Suspected cause:** Instant Health with duration 0 in a food item — never applied (see
  SURVIVAL_EARLY_GAME §1.3 note). Upstream; report in your own words once confirmed by hand.

## Fixed in 0.1.9 — still to re-test in game

- **#6** a quick death (death + respawn between two ticks) left the old order running → now also
  detected from the last-death location. Re-test: `goto` far, `/kill` mid-walk, it must stay put.
- **#7** critical health was blind to a new attacker for 30 s after "can't help" → now rechecks
  every second. Re-test: critical with no heal, then an attacker → retreats within ~1 s.

Fixed and verified (details in git history, commit "Zymbot 0.1.9"): #1 stale "last heard" after a
hard stop, #2 drowning at the shore, #4 two bites per eat.
