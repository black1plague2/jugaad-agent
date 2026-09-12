# Jugaad Agent, handoff (state as of 2026-09-12, 22:1x IST)

Offline, on-device condition monitoring for rotating machines: a phone on the housing records
microphone + accelerometer + gyroscope + magnetometer, compares each reading with the machine's own
reference measurement, and three (or more) phones train and merge fault models over WiFi Direct
with no cloud. This file supersedes the original 10 Sep handoff; the plans in `plans/` are the
binding contracts and each ends with a "Result" section of verified facts.

## What exists (committed and pushed to `black1plague2/jugaad-agent`, branch `main`)

| Layer | State |
|---|---|
| Sensors | mic 44.1 kHz, accelerometer, gyroscope, magnetometer on one HandlerThread; 260-d baseline-relative feature (256 log-mel stats + 4 sensor indices); Pre-check lists sensors with measured rates |
| Anomaly | cosine score to the reference plus per-sensor z-scores; robust adaptive calibration (median/MAD) after every label; drift detection and reference refresh |
| Diagnosis | machine catalogue `assets/config/machines.json` (13 types, 58 faults, keywords, evidence rules, actions); `EvidenceExtractor` (17 metrics) + `RulesEngine` rank "Likely issues" on every non-healthy reading; notification proposal leads with the top issue |
| Learning | LiteRT 2.16.1 on-device training; 8 strategies (base, small, deep, noise, balanced, uncertain, distill, centroid) with early stopping, weight decay, held-out split, overfit gap; heads pretrained offline on MAFAULDA (`ml/data/README.md`) |
| Network | WiFi Direct group or plain WiFi LAN (owner advertised by node name over mDNS, `LanDiscovery`), protocol v2 framing; per-variant FedAvg with accept-guard; champion/challenger promotion (>= 3 pts, 2 rounds); node registry, standings, events, policy replication; peer sample exchange into a shared pool; failover and watchdog; retries with backoff |
| Config | every tunable in `assets/config/app_config.json` (defaults -> device overrides -> owner policy) |
| Self-healing | startup repair of corrupt JSON / stale weights, role restore, retries, failover, watchdog worker; auto-join (`AutoJoin`) re-syncs to whichever owner is advertised, every 60 s |
| UI | Humane Minimalist Dark (Stitch-inspired): bundled Work Sans, `#07080A` canvas, single crimson accent; equipment flow (list, create with machine-type search and bench flag, detail with calibration, pre-check, reference, reading, result with spectrogram heatmap and issues, history) and a federated shell with Network / Devices (auto-join state, Nearby on this WiFi, Serve as owner) / Sync / Performance + Group settings; every Stitch heatmap placeholder is a real heatmap |
| Guard | "Bench / test equipment" flag stops readings from becoming training, sharing or calibration data; `tools/reset-phones.sh` wipes phones before demos |

## Verified on hardware (three iQOO 15, Android 16, SM8850)

- v1 (12 Sep, 11:5x): two phones train, form a group, two FedAvg rounds.
- v2 (12:5x): non-IID synthetic labels, four rounds, `deep` promoted to champion on both phones.
- v3 handoff (other session, 14:xx): three phones, 257->260 migration fix, round 7.
- v4 (16:3x-17:2x): real-sensor path on A (catalogue search "filtra", pre-check, reference,
  Warning under default thresholds -> robust calibration T1 3.89 -> Healthy -> vibration
  injection -> Critical with magnetometer-dominant evidence and three ranked issues); sample
  sharing pools A 57 / B 55 / C 112; policy replicated; importance and device panels; manual
  promote-to-owner produced a FAILOVER event. Defects found: `Recovery.repair` missed a corrupt
  `network.json`, manual sync failures did not count toward automatic failover, the owner's
  sleeping screen stalled its server, Pre-check lacked the magnetometer row (fix agent H1 in
  progress at the time of writing; see the bottom of this file).
- v5 (17:3x): phones wiped and the Humane Minimalist build installed (sha `267a572d3672292a`),
  162/162 JVM tests; clean-state E2E pass (E7) proved the equipment flow and the bench guard (no
  `samples.jsonl`, "bench equipment, not used for training" logged) and listed 13 UI/functional
  defects; federated failures traced to WiFi Direct groups left over at OS level (two owners).
- v5.1 (18:2x): the 13 defects fixed (bench calibration card hidden, Pre-check scroll, detail
  refresh on resume, single-line chips/nav/buttons, honest Devices/Network status chip, Sync tab
  labels, MetricRow alignment, all 8 architectures listed on an empty standings table); dead
  `SyncBus.requestSync` removed (auto-sync after training goes through `SyncNow.asClient`);
  165/165 JVM tests; APK sha `acef994390dc5ece` installed on A, B, C after a learning-data wipe
  that kept the bench equipment. E8 (focused re-verification of those fixes) died on a Sonnet
  session-limit 429 before reporting, so the v5.1 fixes are verified by build and tests only.
- v6 (21:4x-21:5x phone clock): peer discovery by node name on the same WiFi (`LanDiscovery`,
  mDNS/DNS-SD next to WiFi Direct; Devices > "Nearby on this WiFi"; "Serve as owner"; automatic
  owner pick in `SyncNow`). Verified on the three phones from a wiped state: A serves and
  advertises, B and C list A by node name within 2 s without a tap, both sync over the LAN
  (A: `owner 2 node(s) merged`), "Sync now" with no owner chosen picks the only advertised owner,
  node registry shows all three by name. 168/168 JVM tests. Final APK sha `19db41fcdfde61ff`
  (adds the router-over-group-address preference) installed on A, B, C. Report:
  `tools/devtest9/REPORT.md`; contract: `plans/2026-09-12-v6-lan-discovery.md`.
- v7 (22:0x phone clock): auto-join (`p2p/AutoJoin.kt`, process-wide, `sync.autoJoin` /
  `sync.autoJoinIntervalMs` in `app_config.json`, one lock in `SyncNow`). Set one phone as owner
  ("Serve as owner"); every other phone on the WiFi joins it within about 3 s of discovery and
  re-syncs every 60 s, with the loop state shown under the Devices status chip. Verified: B and C
  joined A on launch with no tap; A stopped, C became owner, A and B joined C by themselves
  (`owner 2 node(s) merged`). 171/171 JVM tests. Final APK sha `4f255d832ddaace1` installed on A, B, C.

- v5.1 re-verified on hardware (E12, 22:2x-22:4x phone clock): the defect pass E8 never
  delivered. Corrupt-`network.json` recovery, Pre-check scroll and the magnetometer row, the
  hidden bench calibration card and single-line chips and nav labels all PASS on
  `4f255d832ddaace1`. The owner-unreachable banner FAILED: a stale self-owned WiFi Direct group
  suppressed it through `ui.group.isGroupOwner`, so a client with failing syncs saw only the
  "Sync failed" chip; it now gates on `ui.serving` (`ui/network/Format.kt:46`) and was re-verified
  with the stale group still held. Also fixed: the equipment list wrapped
  "Snapdragon 8 Elite Gen 5" mid-word across four lines because the right Column of
  `EngineBanner` had no weight (`ui/assets/AssetListScreen.kt:151`). Still open: a client sync
  merge discards the startup RECOVER event, so Resilience can read "Repaired: network.json" above
  "No recovery or failover events yet". 171/171 JVM tests. Report:
  `tools/devtest10/REPORT.md`. All three phones now run the fixed build `25182085859111ae`. This
  laptop lacks the founder's debug keystore, so each phone had to be uninstalled and reinstalled
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` on `install -r`); `files/` was tarred and restored on each,
  and equipment, baselines, history, weights and node identities were all verified back in place.
  Going back to a founder-signed build needs one uninstall per phone.
- Fleet state after that reinstall (E12, 23:0x phone clock): A and C both came back as owners
  because both had `"lastRole": "OWNER"`, and B could not reach A
  (`SocketTimeoutException ... port 8988 ... after 5000ms`; `ping` from B averaged 485 ms to A
  against 93 ms to C). A also held a stale WiFi Direct group that `removeGroup` refused to drop
  (`BUSY`), cleared with the detached WiFi cycle from `tools/reset-phones.sh`. Resolved to a single
  owner: C serves (`fl sync: owner 4 node(s) merged [base, deep]`), A and B are clients with
  `consecutiveSyncFailures 0` pointing at 192.168.66.134, B reads "Synchronized / Joined
  I2501-acba / 3 active devices / 1 owner", and no phone logged a `FATAL EXCEPTION`. Worth
  checking `node.json` on each phone before a demo: a phone whose `lastRole` is OWNER restores its
  owner service on launch, which is how the two-owner state arose.

## Device access

Wireless adb on the office LAN: A `192.168.66.250:5555` (10BFAT1SUF000XP), B `192.168.66.225:5555`
(10BFAT1U0F000XP), C `192.168.66.134:5555` (10BFAX1C7P0010U). Reconnect after a reboot with
`adb connect <ip>:5555`; if a phone forgets TCP mode, plug it in once and run `adb tcpip 5555`.
`tools/reset-phones.sh` cycles WiFi to drop stale WiFi Direct groups; over wireless adb that
cycle runs detached on the phone and the script reconnects afterwards (a foreground
`svc wifi disable` over a wireless link hangs adb and can leave WiFi off).
Toolchain on this laptop: JDK 17 `H:/IQOO Hackathon/tools/jdk17`, SDK
`H:/IQOO Hackathon/tools/android-sdk`; export `JAVA_HOME` before `./gradlew`.

```bash
export JAVA_HOME="H:/IQOO Hackathon/tools/jdk17"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest
bash tools/reset-phones.sh                  # wipe FL data + equipment, reinstall, relaunch (all devices)
bash tools/reset-phones.sh --keep-equipment # keep equipment, wipe learning data
adb -s 192.168.66.250:5555 logcat -s JUGAAD:*
```

Python (`ml/.venv`, TF 2.16.1): `ml/fl/export_fl_head.py` (bake heads), `pretrain.py`
(MAFAULDA leave-one-bin-out; `--include-field` for real field data only), `network_sim.py`,
`field_ingest.py` (`--for-training` excludes bench equipment; other pulls are `.DO-NOT-TRAIN`).

## Rules that must hold

- Never train, share or calibrate on readings taken with the phones on a table or on synthetic
  device-test samples: flag such equipment as bench, wipe before demos, keep `.DO-NOT-TRAIN` files
  out of `pretrain.py`.
- The original-work rule from `HACKATHON.md` still applies: this repo is a reference; the event
  repo is rebuilt with incremental commits.
- Keep the plans' "Result" sections honest: every claim there is backed by a log line or a test.

## Open items

- Confirm the founder's reading of "SAP conventions" (implemented as SAP Plant-Maintenance
  vocabulary; the Fiori skin was replaced by the Humane Minimalist Dark language on request).
- After a failover the other clients must Discover and Connect to the new owner (WiFi Direct needs
  a tap for a new pairing); a local "owner unreachable" banner tells them so.
- Airflow Obstruction has no public labelled data; it is learnt on device from technician labels.
- Office Kit external-monitor rehearsal, demo run sheet (`DEMO.md`, `FEDERATED.md` run sheet),
  and the provenance rebuild are unchanged from the original handoff.
