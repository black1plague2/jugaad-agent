# E12: the v5.1 defect pass that E8 never finished (2026-09-12, 22:2x-22:4x phone clock)

Checklist source: `plans/2026-09-12-v5-humane-minimalist-ui.md`, "Result, defect pass". These five
fixes had been verified by build and unit tests only; E8 died on a session-limit 429 before
reporting. Run from a second laptop (`C:/Users/GARV BANSAL/Documents/JugaadAgent`), phones over
wireless adb.

Pre-fix build under test: APK sha256 prefix `4f255d832ddaace1`, confirmed installed on all three
phones with `sha256sum $(pm path com.jugaad.agent.debug)` before anything was touched. Local
rebuild of the same commit `89f7f6c`: green, 171/171 JVM tests (counted from the 34 JUnit XML
files in `app/build/test-results/testDebugUnitTest`).

Equipment on A is bench-flagged only: `f204cdc4` "Coffee machine (bench)", `"benchTest": true`.
No reading was taken in this pass and `files/fl` on A holds no `samples.jsonl` or
`shared_samples.jsonl` at all, so nothing here could reach training, sharing or calibration.

| # | Item | Result | Evidence |
|---|---|---|---|
| 1 | Recovery from a corrupt `network.json` | PASS | `echo garbage2 > files/fl/network.json`, force-stop, relaunch: `Recovery: corrupt file network.json -> network.json.corrupt-1789232290027` then `Recovery: repaired corrupt file network.json` (22:28:10.032). `ls files/fl`: fresh `network.json` 10921 bytes next to the 9-byte quarantined file. Group settings > Resilience shows a "Repaired / network.json" row and the event row "1 min ago / repaired corrupt file: network.json" (`E12_06_recover_event.png`) |
| 2 | Failover ("owner unreachable") banner | FAIL on `4f255d832ddaace1`, fixed and re-verified on `25182085859111ae` | See Defect 1 |
| 3 | Pre-check scroll and magnetometer row | PASS | One screen-level scrollable node, bounds height 2958, spanning the whole body (`E12_10_precheck_top.xml`); no nested scroller. Sensor rows read Microphone 44100.0 Hz, Accelerometer 461.3 Hz, Gyroscope 461.3 Hz, Magnetometer 92.2 Hz, each "Available" (`E12_11_precheck_mag.xml`). "Run checks" flipped all four checks from Pending to Pass (`E12_12_precheck_runchecks.xml`) |
| 4 | Bench calibration card hidden | PASS, both directions | On bench `f204cdc4` the screen ends "Last measurement document" then "Delete equipment"; the strings `calibrat`, `threshold`, `t1`, `t2` are absent from the whole dump (`E12_08_bench_detail_bottom.xml`), and the informative banner "Bench equipment: readings are not used for training, sharing or calibration" is shown. Positive control: bench toggled off, "Calibration", "T1 = 2.0  T2 = 4.0", "Calibrate thresholds" and "Refresh reference measurement" all appear (`E12_09_nonbench_calibcard.xml`); toggled straight back on, `asset.json` reads `"benchTest": true` |
| 5 | Single-line chips and nav labels | PASS for chips and nav; one related layout defect found and fixed | Bottom nav all single line at bounds height 60: Network w=199, Devices w=181, Sync w=112, Performance w=304 (`E12_02_network.xml`). Chips all single line at height 68: "Synchronized", "Sync failed", "Serving", "Healthy", "Pass", "Pending", and "Group formed, not serving" at w=1036. See Defect 2 |

## Defect 1: the owner-unreachable banner was suppressed by a stale self-owned group

Checklist item 2 failed on the build under test. Phone A was operating as a LAN client of C
(`I2501-acba`, 192.168.66.134) and its syncs were failing:

- `files/fl/node.json`: `"lastRole": "CLIENT"`, `"consecutiveSyncFailures": 1`
- logcat: `fl sync: client failed: SocketTimeoutException: Read timed out` (22:36:30.067)
- Devices tab chip: "Sync failed"; status line "Joining I2501-acba"

Yet the string "Owner unreachable" was absent from the Devices dump (`E12_13_devices.xml`) and
the Network dump (`E12_14_network_nobanner.xml`). Cause: A still held a stale self-owned WiFi
Direct group (Network tab "Role: Owner", "Owner address: 192.168.49.1"), and
`OwnerUnreachableBanner` returned early on `ui.group.isGroupOwner`. The banner was therefore
hidden in exactly the situation it exists for. E10 had already corrected this staleness for the
status chip in `syncStatus` but the banner's guard was left behind.

Fix, `ui/network/Format.kt:46`: gate on `ui.serving` instead of `ui.group.isGroupOwner`, so only
a node that is actually serving suppresses the banner.

Re-verified on `25182085859111ae`: with `"consecutiveSyncFailures": 3` and the same stale group
still held (chip "Group formed, not serving", Role "Owner", owner address 192.168.49.1), the
banner renders on both tabs, text "Owner unreachable (3 failed syncs): Discover and Connect to
another node, or promote this node in Group settings" (`E12_17_devices_banner.png`,
`E12_18_network_banner_ok.png`). While A was genuinely serving it stayed correctly suppressed
(`E12_16_network_banner.xml`, chip "Serving").

## Defect 2: the device name wrapped mid-word on the equipment list

Not on the checklist, found while checking item 5. `EngineBanner` on the equipment list rendered
"Snapdragon 8 Elite Gen 5" as four lines reading "Snapdr", "agon 8", "Elite", "Gen 5": the text
node measured 360 tall and about 65 wide (`E12_01_home.png`). Cause: in the Row the left Column
has `Modifier.weight(1f)` but the right Column had no weight, so the right Column was measured
first at its intrinsic width and starved the weighted left one.

Fix, `ui/assets/AssetListScreen.kt:151`: give the right Column `Modifier.weight(1f)` as well.
Re-verified: the name now occupies two lines breaking at a space, node 180 tall and 573 wide,
with "CNN . LiteRT . federated head (CPU)" at 166 and "LLM . template" at 83, nothing clipped
and nothing ellipsised (`E12_15_fixed_home.png`).

## Defect 3: a client sync merge discards the startup RECOVER event (found, not fixed)

On the first corruption run the repair logged and the "Repaired / network.json" row appeared, but
the Resilience events list read "No recovery or failover events yet". Reading the file back
showed `network.json` holding 40 events, 37 SYNC and 3 ASSIGN, and zero RECOVER.

A second run isolated it: after `echo garbage3` and relaunch, `network.json` contained exactly one
event of type RECOVER, and it was still there when polled at 2, 4, 6, 10, 20 and 35 s
(quarantined as `network.json.corrupt-1789232486815`, 22:31:26.819). So the event is written
correctly and then lost: a client sync merge replaces the local event list with the owner's,
taking the startup RECOVER entry with it.

Left unfixed on purpose: the repair itself, its log lines and the recovery report row all work,
and changing which side wins an event-list merge is a replication-semantics decision for the
founder, not a layout fix. The user-visible symptom is a screen that says "Repaired:
network.json" directly above "No recovery or failover events yet".

## Phone state after this pass

- Phone A runs `25182085859111ae` (commit `89f7f6c` plus the two fixes above). This laptop does
  not have the founder's debug keystore, so `adb install -r` failed with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and A had to be uninstalled and reinstalled. `files/` was
  tarred first (38 entries) and restored after: equipment `f204cdc4` with `"benchTest": true`,
  its baseline, its 3 history records and every `weights_*.bin` / `metrics_*.json` are back, and
  the two `network.json.corrupt-*` test artefacts were deleted afterwards. Going back to a
  founder-signed build needs one uninstall on A.
- Phones B and C were not touched and still run `4f255d832ddaace1`.
- A's sync service was left stopped (it was stopped to test the banner); A's `node.json` still
  has `"lastRole": "OWNER"`, so it will serve again on next launch.
- A's pre-corruption event history is gone: the first corruption run wiped it and the list
  refilled from the owner on the next merge.

## Build notes for this laptop

There is no JDK 17 here; the build used the Android Studio JBR, `openjdk 21.0.10`, with Gradle
8.11.1 and AGP 8.7.3, and the source and jvm targets stay at 17. On this Windows build
(10.0.26200) Gradle dies with `java.io.IOException: Unable to establish loopback connection`
unless `C:/tools/noafunix-agent.jar` is loaded into every JVM involved, and the repo's own
`org.gradle.jvmargs` in `gradle.properties` overrides the user-level one that carries it:

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"; export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_OPTS="-javaagent:C:/tools/noafunix-agent.jar"
./gradlew :app:assembleDebug :app:testDebugUnitTest -I /path/to/noafunix.init.gradle -Dorg.gradle.jvmargs="-Xmx3072m -Dfile.encoding=UTF-8 -javaagent:C:/tools/noafunix-agent.jar"
```

`JAVA_OPTS` covers the launcher, `-Dorg.gradle.jvmargs` the daemon, and the init script
(`allprojects { tasks.withType(Test).configureEach { jvmArgs "-javaagent:..." } }`) the forked
test JVM, which otherwise fails `:app:testDebugUnitTest` on its own socket to the daemon.
