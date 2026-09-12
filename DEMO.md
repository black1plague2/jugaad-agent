# Jugaad Agent, demo run sheet

## Setup (before you present)

- [ ] Same APK on all three phones (`bash tools/reset-phones.sh --keep-equipment` wipes learning
      data and reinstalls; the staged copy is `H:\IQOO Hackathon\jugaad-agent-debug.apk`).
- [ ] All phones on the same WiFi (or, without a router, WiFi on for WiFi Direct).
- [ ] Group settings on each phone: give the nodes readable names (for example FAN-A, FAN-B,
      FAN-C); the defaults are `I2501-xxxx`.
- [ ] Equipment used for rehearsals on the table is flagged "Bench / test equipment". Real demo
      equipment is not.
- [ ] A small running machine: desk fan, pump, or a laptop fan. A fan you can partly block with
      card is ideal; a coin taped to a blade gives an imbalance.
- [ ] Grant microphone and notification prompts on first launch. Pre-grant before presenting.
- [ ] Screen-mirror one phone with vivo Office Kit; the federated screens work in landscape.

## Live script (about 4 minutes)

1. **Equipment.** New equipment, type "fan", pick the catalogue entry, name it. Pre-check: all
   four sensors show a rate.
2. **Reference.** Phone flat on the housing, machine running clean, three clips.
3. **Healthy reading.** Take a reading: Healthy, low score, spectrogram heatmap.
4. **Fault.** Coin on a blade or card over half the intake, take a reading: Warning or Critical,
   score up, "Likely issues" ranked from the catalogue with actions, notification proposal.
   Confirm the label chip: this reading is now training data on this phone.
5. **Network, one tap.** On phone A open Federated network, Devices, tap "Serve as owner".
   Within seconds phones B and C read "Joined FAN-A, next sync in 55 s" and A reads "3 active
   devices". Nothing was tapped on B or C.
6. **Learning.** Sync tab on any phone: rounds per variant, held-out accuracy. Performance tab:
   the eight architectures and the standings. Train now on a phone with labels; the next sync
   shows MERGE in the activity list and, once a challenger wins twice, PROMOTE.
7. **Resilience.** Stop the service on A; B and C read "Waiting for an owner on this WiFi". Tap
   "Serve as owner" on C: A and B join C by themselves. (Leave the automatic failover to the talk
   track: after three failed syncs the next node promotes itself.)
8. **History and share.** History shows both readings with thumbnails; share the report PNG.

## Proof lines (keep a terminal visible)

```bash
adb -s <serial> logcat -s JUGAAD:*
```

```
JUGAAD  LiteRT heads loaded: base, small, deep, noise, balanced, uncertain, distill; strategies: ...
JUGAAD  lan: advertising 'FAN-A' on port 8988                      (owner)
JUGAAD  lan: found 'FAN-A' id=... at 192.168.x.y:8988               (client)
JUGAAD  auto-join: syncing with 'FAN-A' at 192.168.x.y             (client)
JUGAAD  fl sync: client variants=[base, small], rounds {base=4} -> {base=5}, promoted=null
JUGAAD  fl sync: owner 2 node(s) merged [base, deep, small], promoted=null
JUGAAD  diagnosis <id> status=CRITICAL score=... issues=3
JUGAAD  diagnosis <id>: bench equipment, not used for training     (only on bench-flagged equipment)
```

## Talking points

- No cloud, no server, no account: the phones are the network. Airplane mode plus WiFi Direct
  still works.
- One phone is a four-sensor instrument: microphone, accelerometer, gyroscope, magnetometer over
  the same three seconds; magnetometer evidence separates electrical faults from mechanical ones.
- Per-machine reference plus self-calibrating thresholds: no training data is needed for the
  first Healthy / Warning / Critical verdict; the catalogue explains it; the classifier improves it.
- Eight learning strategies compete on every phone; the network promotes whichever wins on the
  shared held-out set. Experimental nodes try new strategies without risking the stable ones.
- Set one phone as owner and the rest join by name within seconds; if the owner disappears, the
  network heals itself.
- Honest data: bench tests never train the model, and every claim in the docs has a log line.

## If something goes wrong

- A phone lists no owner: check it is on the same WiFi; the access point must allow client to
  client traffic (otherwise use Create group on the owner and Discover, Connect on the others).
- Two owners visible: stop the service on one of them; clients say so under Devices.
- A phone shows "Sync failed": it retries by itself; "Sync now" forces it.
- Fresh start: `bash tools/reset-phones.sh --keep-equipment <serial>` on that phone.
