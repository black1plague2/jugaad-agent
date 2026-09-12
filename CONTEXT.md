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

1. A JDK 17 or 21. Temurin 17 works; so does the JBR bundled with Android Studio
   (`<studio>/jbr`, openjdk 21.0.10), which is what the 13 Sep session used. The source and jvm
   targets stay at 17 either way. Never rely on the system Java: export `JAVA_HOME` before every
   `./gradlew`.
2. Android SDK command-line tools; accept licences; install `platform-tools`, `platforms;android-35` and a
   build-tools 35.x (`compileSdk = 35`, `minSdk = 29` in `app/build.gradle.kts`). Write `local.properties` with `sdk.dir=<absolute path>` (forward slashes on Windows).
3. Build and test:

```bash
export JAVA_HOME=/path/to/jdk17; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

   Expected: green build, **194/194 JVM tests** (as of 2026-09-13 03:xx). The APK is
   `app/build/outputs/apk/debug/app-debug.apk`, package `com.jugaad.agent.debug`, activity
   `com.jugaad.agent.MainActivity`.

   Two traps that cost a whole session on a fresh Windows machine:

   - **`java.io.IOException: Unable to establish loopback connection`.** On some Windows builds
     (seen on 10.0.26200) Gradle cannot open its own selector because the JDK's AF_UNIX socketpair
     fails. The workaround is a tiny javaagent that forces
     `sun.nio.ch.UnixDomainSockets.supported` to false, and it has to reach **three** JVMs: the
     launcher (via `JAVA_OPTS`), the daemon (via `-Dorg.gradle.jvmargs`, because the repo's own
     `gradle.properties` overrides any user-level one), and the forked test JVM (via an init
     script adding `jvmArgs`, or `:app:testDebugUnitTest` fails on its own socket to the daemon).
     If your machine does not have this bug, ignore all of it and just run `./gradlew`.
   - **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** The app is signed with each machine's own
     `~/.android/debug.keystore`, which is not in git. Installing from a different machine than
     the one that last installed needs `adb uninstall` first, which wipes the phone's data, so
     **tar `files/` off the phone and restore it afterwards** (see "Moving the app to a phone
     from a new machine" below).
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

## Where things stand (2026-09-13, 03:xx IST)

- v1 to v11 implemented. APK sha256 prefix **`22b407f044f01379`**, **194/194 JVM tests**, installed
  on all three phones. Verified on hardware, not inferred.
- **Real field data exists now.** Phone A carries the coffee-machine capture: 8 pieces of
  equipment (the whole drinks menu: Hot water, Tea, Strong Coffee, iQOO coffee, Hot Milk, Black
  Coffee, Strong Tea, Black Tea), every one with a `baseline.json`, and 8 readings on
  `iQOO coffee` (7 Healthy, 1 Critical at score 4.46 which produced three ranked catalogue issues).
  Phone B has 3 equipment and 7 samples, phone C 1 and 3. **Do not wipe these.**
- **The single blocker for classification is a second class.** Every label on the fleet is class 0
  (HEALTHY). Until `ROTOR_IMBALANCE` or `AIRFLOW_OBSTRUCTION` examples are captured off a real
  machine, `trainAcc` reads a vacuous 1.0 and `valAcc` stays at the -1 sentinel, no promotion can
  fire, and the classifier cannot classify. That is a data gap, not a code gap: the whole path
  below it is tested (`TwoClassLearningIntegrationTest` trains a genuine two-class set to
  `valAcc 1.0` with correct held-out inference). See `plans/2026-09-13-v9-first-real-dataset.md`
  for the capture targets.
- Auto-labelling only fires for a reading scoring at or under `0.5 * t1` (1.0 with the current
  defaults), which is why three of A's eight readings labelled themselves and the rest did not.
  Manual labels come from "Confirm label" on the Result screen.
- Calibration has not run on any asset. It needs `calibration.minHealthy` (5) samples with
  `label == 0` on that one asset; `iQOO coffee` has 3 and the detail screen shows the progress.
  Until it runs, thresholds stay at the untuned defaults T1 2.0 / T2 4.0, and since that machine's
  healthy spread is 0.56 to 1.92 nothing ever lands in the WARNING band.
- Federated state: C (`I2501-fd22`) is owner, A (`I2501-f0c7`) and B (`I2501-a783`) are clients,
  registry holds exactly 3 nodes. The zero-sample merge guard is active and visible in logcat as
  `fl sync: skipping base, no trained samples this round`, with round counters correctly held.
- Known risk, unfixed by choice: with every screen asleep the OS freezes the owner process despite
  its foreground service (`dumpsys power` shows the wake lock `DISABLED ... mIsFrozen`) and clients
  time out on port 8988. Fine for a demo where someone is holding a phone. Unattended syncing would
  need `dumpsys deviceidle whitelist +com.jugaad.agent.debug`, which was deliberately not applied.
- Open items unchanged: the founder's reading of "SAP conventions"; after a failover clients must
  Discover and Connect to the new owner by hand; Airflow Obstruction has no public labelled data;
  the provenance rebuild and the Office Kit rehearsal (`HACKATHON.md`, `DEMO.md`).

## Moving the app to a phone from a new machine

Because the debug keystore is per machine, a build from a different laptop cannot update an
existing install. Back up, uninstall, install, restore:

```bash
adb -s <dev> exec-out run-as com.jugaad.agent.debug tar -c files > phone.tar   # back up first
adb -s <dev> uninstall com.jugaad.agent.debug
adb -s <dev> install -g app/build/outputs/apk/debug/app-debug.apk
adb -s <dev> shell am start -n com.jugaad.agent.debug/com.jugaad.agent.MainActivity   # creates files/
adb -s <dev> shell am force-stop com.jugaad.agent.debug
adb -s <dev> push phone.tar /sdcard/p.tar
adb -s <dev> shell "cat /sdcard/p.tar | run-as com.jugaad.agent.debug tar -x"
adb -s <dev> shell rm /sdcard/p.tar
```

Then check the restore: equipment count, `baseline.json` per asset, history record count and
`wc -l files/fl/samples.jsonl`. `install -g` grants RECORD_AUDIO so the Pre-check passes without a
manual permission tap.

**Never wipe a phone to get out of trouble without asking.** `tools/reset-phones.sh` with no
`--keep-equipment` deletes equipment and the captured readings. It also resets phones one at a
time, so a freshly wiped phone can re-sync poisoned or stale state from a peer that has not been
wiped yet; wipe with nobody serving as owner, or wipe all phones before relaunching any of them.

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
  - `p2p/` WiFi Direct (`WifiDirectManager`), `LanDiscovery` (mDNS by node name), `AutoJoin`
    (process-wide loop that syncs to the advertised owner), TCP protocol v2 (`SyncProtocol`, port 8988,
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
