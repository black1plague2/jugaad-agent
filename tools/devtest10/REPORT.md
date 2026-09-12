# E12: the v5.1 defect pass that E8 never finished (2026-09-12, 22:2x-22:4x phone clock)

Checklist source: `plans/2026-09-12-v5-humane-minimalist-ui.md`, "Result, defect pass". These five
fixes had been verified by build and unit tests only; E8 died on a session-limit 429 before
reporting. Run from a second laptop (`C:/Users/GARV BANSAL/Documents/JugaadAgent`), phones over
wireless adb.

Pre-fix build under test: APK sha256 prefix `4f255d832ddaace1`, confirmed installed on all three
phones with `sha256sum $(pm path com.jugaad.agent.debug)` before anything was touched. Local
rebuild of the same commit `89f7f6c`: green, 171/171 JVM tests (counted from the 34 JUnit XML
files in `app/build/test-results/testDebugUnitTest`).

All equipment on the fleet is bench-flagged: A has `f204cdc4` "Coffee machine (bench)", B has
`eed6065d` "iQOO coffee" and `e518dd7a` "iQOO laptop", C has none, and every `asset.json` present
reads `"benchTest": true`. No reading was taken in this pass, and `ls files/fl` on all three
phones matched no `*sample*` file at all, so nothing here could reach training, sharing or
calibration.

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

All three phones run the fixed build `25182085859111ae` (commit `89f7f6c` plus the two fixes
above), confirmed with `sha256sum $(pm path com.jugaad.agent.debug)` on each. This laptop does not
have the founder's debug keystore, so `adb install -r` failed with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` on every phone and each had to be uninstalled and
reinstalled. `files/` was tarred first and restored after, and each restore was verified:

| Phone | Backup | Restored state |
|---|---|---|
| A | 38 entries | `f204cdc4` "Coffee machine (bench)" `"benchTest": true`, its baseline, 3 history records, all `weights_*.bin` / `metrics_*.json`, node identity `I2501-6e00` |
| B | 48 entries | `eed6065d` "iQOO coffee" and `e518dd7a` "iQOO laptop", both `"benchTest": true`, 17 fl files, node identity `I2501-6402` |
| C | 25 entries | no equipment (it had none), 17 fl files, node identity `I2501-acba` (`acbace15`) |

Going back to a founder-signed build needs one uninstall per phone.

No phone has a `samples.jsonl` or `shared_samples.jsonl`, and no reading was taken at any point in
this pass, so nothing here reached training, sharing or calibration.

## Fleet configuration after the reinstall

The reinstall left the fleet in an illogical state that had to be sorted out, and the diagnosis is
worth keeping:

- A and C both came up as owners, each advertising and listening on 8988, because both had
  `"lastRole": "OWNER"` in `node.json` and restore their owner service on launch.
- B could not sync with A at all: `fl sync: client failed: SocketTimeoutException: failed to
  connect to /192.168.66.250 (port 8988) ... after 5000ms`, repeatedly. A was genuinely listening
  (`/proc/net/tcp` state 0A on port 0x231C) and reachable, but `ping` from B measured
  min/avg/max 16.8 / 485.3 / 1415.2 ms to A against 57.6 / 92.7 / 120.4 ms to C, so connects were
  timing out on latency rather than on a closed port.
- A also held a stale WiFi Direct group (Network tab "Role: Owner", "Owner address:
  192.168.49.1"). "Leave group" could not clear it: `WifiDirectManager: removeGroup failed (BUSY)`
  on two attempts, and `createGroup failed (ERROR)` on the next launch. The documented remedy
  worked: the detached WiFi cycle from `tools/reset-phones.sh`
  (`nohup sh -c 'sleep 1; svc wifi disable; sleep 4; svc wifi enable' &`) plus an adb reconnect.
  Afterwards `ip -4 addr` on A showed only `wlan0 192.168.66.250`, with no `p2p0`, and the Network
  tab read Role "Not connected".
- Resolved by stopping A's sync service and syncing it to C once: A logged
  `auto-join: syncing with 'I2501-acba' at 192.168.66.134` then
  `fl sync: client variants=[base], rounds {base=37} -> {base=41}`.

End state, all verified: C is the sole owner (`"lastRole": "OWNER"`, 3 listening sockets on 8988,
`fl sync: owner 4 node(s) merged [base, deep]`); A and B are both
`"lastRole": "CLIENT", consecutiveSyncFailures 0, lastOwnerAddress 192.168.66.134`; B's Devices
tab reads "Synchronized", "Joined I2501-acba, next sync in 15 s", "3 active devices" and
"Nearby on this WiFi: 1 owner" (`E12_19_B_devices_converged.png`); the owner-unreachable banner is
correctly absent. No `FATAL EXCEPTION` in logcat on any of the three.

The `EngineBanner` fix was re-confirmed on B and C independently: the device-name node measures
180 tall and 573 wide on both, the same as on A (`E12_20_B_home.png`, `E12_21_C_home.png`).

Note for the next session: A and B each still show one listening socket on 8988 as clients, and
both had `"lastRole": "OWNER"` restore an owner service on launch after the reinstall. If a single
owner matters for a demo, check `node.json` on every phone before starting rather than trusting
the Devices tab alone.

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

## Stability check before the push (23:1x phone clock)

With all three screens awake and the app foregrounded, the loop runs: B logged
`auto-join: syncing with 'I2501-acba' at 192.168.66.134` then
`fl sync: client variants=[base, small], rounds {base=52, small=37} -> {base=53, small=38}`, and A
logged `fl sync: client variants=[base, noise], rounds {base=51, noise=24} -> {base=53, noise=25}`,
both within seconds. Final state on all three: same build `25182085859111ae`, zero
`FATAL EXCEPTION`, C `"lastRole": "OWNER"`, A and B `"lastRole": "CLIENT"` with
`consecutiveSyncFailures 0` pointing at 192.168.66.134.

Risk found while checking this, not caused by the fixes in this pass: with every screen asleep the
loop stalls. A kept trying on schedule and kept failing,
`fl sync: client failed: SocketTimeoutException: failed to connect to /192.168.66.134 (port 8988)
... after 5000ms` (23:14:01 to 23:14:12), while C was still listening on 8988 and
`FlSyncService` was still `isForeground=true`. The reason is visible in `dumpsys power` on C: its
wake lock read

```
PARTIAL_WAKE_LOCK 'jugaad:fl-sync' DISABLED (uid=10346 pid=511) mIsFrozen
```

The owner's process was frozen by the OS cached-app freezer despite the foreground service, so the
wake lock was disabled and the server socket went unserviced. `am get-standby-bucket` returned 10
and `dumpsys deviceidle whitelist` lists no `com.jugaad.agent.debug`, so the app is not exempt from
battery optimisation. Once the screens were woken the same lock read
`ACQ=-3m26s955ms LONG` and syncing resumed immediately.

This is the same failure family as the v4 note "the owner's sleeping screen stalled its server".
It does not affect a demo where someone is holding a phone, and every verified pass in this
repository ran with screens on. If unattended syncing is wanted, the owner at least needs a doze
exemption, which is a device setting rather than a code change and was deliberately not applied
here:

```bash
adb -s <device> shell dumpsys deviceidle whitelist +com.jugaad.agent.debug
```
