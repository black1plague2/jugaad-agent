# CONTEXT.md, how to continue this project in a fresh Claude session

Read this file first, then `HANDOFF.md` (current state), then `FEDERATED.md` (how the system
works and the demo run sheet). The files under `plans/` are the binding contracts for each goal;
each ends with a "Result" section that lists only verified facts. Do not re-derive state from
memory or from this file when a plan's Result section or a log line can answer it.

## What this is

Jugaad Agent is an offline Android app for the iQOO hackathon (event mid-September 2026). A phone
placed on a rotating machine records microphone, accelerometer, gyroscope and magnetometer,
compares the reading with that machine's own reference measurement, ranks likely issues from a
machine catalogue, and three (or more) phones train and merge fault models over WiFi Direct with
no cloud. Hardware: three iQOO 15 (Snapdragon 8 Elite Gen 5, 16 GB), Android 16.

## Hard rules (do not relax these)

1. Never train, share or calibrate on readings taken with the phones lying on a table or from
   synthetic device-test samples. Equipment used for tests is created with the "Bench / test
   equipment" flag on, which blocks sample capture at the source (`DiagnoseUseCase`). Field pulls
   of such data are named `*.DO-NOT-TRAIN.jsonl` and never go into `ml/fl/pretrain.py`. Wipe the
   phones with `tools/reset-phones.sh` before a demo.
2. Every claim in a plan's Result section, in `HANDOFF.md` or in a report is backed by a log
   line, a test, or a screenshot file. Never estimate a number into a table; leave it blank and
   say so.
3. Verify the outcome, not the acknowledgement: an `adb install` "Success" is not a working app,
   a returned sync result is not a merged model. Check the log line, the file on the phone, the
   screen.
4. The original-work rule in `HACKATHON.md`: this repository is the reference implementation;
   the event repository is rebuilt with incremental commits. Do not push to an event repository
   without the founder deciding the provenance approach first.
5. Surgical changes: touch only what the task needs, match the existing style, no speculative
   abstractions or configurability. UI strings use no em or en dashes.

## How the founder works with Claude

- One plan file per goal, `plans/<date>-<slug>.md`, written before code for anything ambiguous,
  irreversible or multi-agent. It states the constraint being attacked, the failure modes and
  what catches each, and a ledger of delegate-vs-build decisions. When the work lands, append a
  "Result" section with verified facts and defects found.
- Scoped implementation and analysis go to Sonnet subagents; pure search goes to Haiku; the
  orchestrator keeps architecture calls, plan authorship, anything that changes phone state, and
  final verification. Every delegation is a contract: the task in one sentence, input paths, the
  deliverable format, a maximum return length (about 40 lines), what to exclude, and "do not
  spawn subagents". Give each agent exclusive file ownership when several run at once.
- Say which kind of claim you are making: verified this session, read from a document, or
  inferred.
- Replies to the founder are short, lead with the outcome, link files as
  `[name](relative/path:line)`, put commands in `bash` code blocks.
- Push back once with evidence if a request rests on a wrong assumption, then follow the
  decision.

## Toolchain on a new machine

Nothing below is in git. The app builds with the Gradle wrapper (`gradlew`, `gradlew.bat`, the
wrapper jar are committed).

1. JDK 17 (Temurin works). The system Java on the founder's laptop is a JRE 8, so always export
   `JAVA_HOME` before `./gradlew`.
2. Android SDK command-line tools; accept licences; install `platform-tools`, `platforms;android-35` and a
   build-tools 35.x (`compileSdk = 35`, `minSdk = 29` in `app/build.gradle.kts`). Write `local.properties` with `sdk.dir=<absolute path>` (forward slashes on Windows).
3. Build and test:

```bash
export JAVA_HOME=/path/to/jdk17; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

   Expected: green build, 165/165 JVM tests (as of 2026-09-12 18:2x). The APK is
   `app/build/outputs/apk/debug/app-debug.apk`, package `com.jugaad.agent.debug`, activity
   `com.jugaad.agent.MainActivity`.
4. Python is only needed to re-bake the on-device heads or to run the network simulator:
   Python 3.12, `python -m venv ml/.venv`, TensorFlow 2.16.1 (must match the LiteRT runtime
   version in `gradle/libs.versions.toml`). Scripts: `ml/fl/export_fl_head.py` (bake heads),
   `ml/fl/mafaulda_ingest.py` + `ml/fl/pretrain.py` (MAFAULDA pretraining; see
   `ml/data/README.md` and `ml/data/DATASETS.md` for where the dataset comes from),
   `ml/fl/network_sim.py`, `ml/fl/field_ingest.py` (`--for-training` excludes bench equipment).
   The pretrained heads are committed under `app/src/main/assets/models/fl_head_*.tflite`, so
   the app builds without Python.

Founder's laptop layout (Windows): JDK at `H:/IQOO Hackathon/tools/jdk17`, SDK at
`H:/IQOO Hackathon/tools/android-sdk`, adb at `.../android-sdk/platform-tools/adb.exe`, repo at
`H:/IQOO Hackathon/jugaad-agent`, staged APK copy at `H:/IQOO Hackathon/jugaad-agent-debug.apk`.

## The phones

| Phone | Serial | Wireless adb (office LAN, 2026-09-12) |
|---|---|---|
| A | `10BFAT1SUF000XP` | `192.168.66.250:5555` |
| B | `10BFAT1U0F000XP` | `192.168.66.225:5555` |
| C | `10BFAX1C7P0010U` | `192.168.66.134:5555` |

The IPs belong to that network; on another network read them from the phone's WiFi details.
First-time wireless setup: plug the phone in once, `adb tcpip 5555`, unplug, `adb connect
<ip>:5555`. The founder prefers everything to run wirelessly; USB is the fallback when a phone
drops off. Do not run `svc wifi disable` in the foreground over a wireless link: the link dies
mid-command and the phone can be left with WiFi off (`tools/reset-phones.sh` runs its WiFi
cycle detached and reconnects; see the script).

```bash
bash tools/reset-phones.sh                  # wipe learning data and equipment on every device, reinstall, relaunch
bash tools/reset-phones.sh --keep-equipment 192.168.66.250:5555   # keep equipment, wipe learning data on one phone
adb -s 192.168.66.250:5555 logcat -s JUGAAD:*                   # every app log line is tagged JUGAAD
```

Driving the UI from adb (what the verification agents do): `adb -s <serial> exec-out screencap
-p > shot.png`, `adb shell uiautomator dump /sdcard/ui.xml` + `adb pull` for element bounds,
`adb shell input tap X Y`, `input swipe`, `input text`, `input keyevent 224` (wake) and `82`
(unlock) when the screen sleeps. Files on the phone: `adb shell run-as com.jugaad.agent.debug
ls files/fl` (`samples.jsonl`, `shared_samples.jsonl`, `node.json`, `network.json`, `state.json`,
weights), `files/assets/<id>/` (equipment, baseline, `calibration.json`). Evidence from past passes lives in `tools/devtestN/REPORT.md`; the PNGs
are not committed.

## Where things stand (2026-09-12, 18:2x IST)

- v1 to v5 and the v5.1 defect pass are implemented and built: APK sha256 prefix
  `acef994390dc5ece`, 165/165 JVM tests. All three phones run it on a wiped learning state; A
  has one bench-flagged piece of equipment ("Coffee machine (bench)"), B and C have none.
- The full change set since the initial commit `0b6ab1d` is committed in one commit on top of
  it (this file's commit). The on-device verification passes are summarised in `HANDOFF.md`
  ("Verified on hardware") and in each plan's Result section.
- In progress at the time of writing: E8, a focused on-device re-verification of the v5.1 fixes
  and of the federated flow from a clean state (3 nodes, recovery from a corrupt `network.json`,
  failover banner, Pre-check scroll, bench calibration hidden, single-line chips). Its report is
  `tools/devtest8/REPORT.md` if it exists; if it does not, run that pass again before claiming
  the fixes work on hardware.
- Open items: the founder's reading of "SAP conventions" (implemented as Plant-Maintenance
  vocabulary, visual language replaced by the Humane Minimalist Dark theme on request); after a
  failover the other clients must Discover and Connect to the new owner by hand; Airflow
  Obstruction has no public labelled data; the provenance rebuild and the Office Kit rehearsal
  are unchanged from the original handoff (`HACKATHON.md`, `DEMO.md`).

## Map of the code

- `app/src/main/java/com/jugaad/agent/`
  - `sensor/` capture (mic 44.1 kHz, accel, gyro, mag on one HandlerThread); `ml/` features
    (256 log-mel stats + sensor indices, 260-d baseline-relative vector), anomaly scoring
    (cosine to the reference + per-sensor z-scores), `ml/diagnosis/` evidence extraction and
    the rules engine over the machine catalogue.
  - `core/config/` `AppConfig` (every tunable, `assets/config/app_config.json`, layered
    defaults, device overrides, owner policy), `MachineCatalog` (`assets/config/machines.json`,
    13 machine types, 58 faults, keywords, evidence rules, actions).
  - `domain/usecase/` calibrate (median/MAD adaptive thresholds), diagnose (the bench guard
    lives here), reference capture and refresh.
  - `fl/` LiteRT on-device training: `FlVariants` (base, small, deep, noise, balanced,
    uncertain, distill, centroid), `VariantTrainer`, early stopping, held-out split, promotion
    (champion/challenger), `StrategyRank`, `Cohorts`, `TrainBudget`, `Recovery` (startup repair
    of corrupt JSON and stale weights), `SharedPool` (peer sample exchange), `AutoTrainer`.
  - `p2p/` WiFi Direct (`WifiDirectManager`), TCP protocol v2 (`SyncProtocol`, port 8988,
    "JGFL" framing), `FedAvgCoordinator` with accept guard, `SyncNow` (client sync with retries
    and backoff), `Failover`, `FlSyncService` (owner foreground service, wake and WiFi locks),
    `SyncWorker`/`SyncScheduler` (WorkManager watchdog), `SyncBus` (UI state).
  - `ui/` Humane Minimalist Dark theme (`ui/theme`, `ui/common/fiori/*` components, the
    directory name is historical), equipment flow (`assets`, `createasset`, `assetdetail`,
    `checklist`, `baseline`, `diagnose`, `result`, `history`), federated shell (`ui/network`:
    Network, Devices, Sync, Performance, Group settings).
- `app/src/test/` JVM tests (protocol, FedAvg, calibration maths, rules engine, sample store,
  recovery, failover).
- `ml/` Python: dataset ingest, pretraining, head export, network simulation. `ml/data/*` is
  ignored by git except the markdown.
- `plans/` contracts v1 (federated learning), v2 (champion/challenger network), v3 (sensors,
  strategies, datasets), v4 (catalogue, config, self-healing, enrichment), v5 (UI).
- `tools/reset-phones.sh`, `tools/devtestN/` evidence reports.

## Verifying a change end to end (the loop that has worked)

1. Build and run the JVM tests.
2. `bash tools/reset-phones.sh --keep-equipment <A> <B> <C>` to install on all phones from a
   clean learning state.
3. Launch a Sonnet verification agent with the checklist, the serials, the bench rule and the
   evidence requirement (screenshot or log line per claim), writing `tools/devtestN/REPORT.md`
   and returning at most 40 lines.
4. Fix defects with a Sonnet agent that owns the named files; rebuild; repeat step 2 and 3 for
   the fixed items only.
5. Append the verified facts to the plan's Result section and to `HANDOFF.md`.
