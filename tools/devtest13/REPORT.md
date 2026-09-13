# devtest13: end-to-end verification against spec (2026-09-13, 04:10-06:05 phone clock)

Contract: `plans/2026-09-13-v12-e2e-verification.md`. Every row cites a log line, a command output
or a PNG under `tools/devtest13/shots/` (PNGs are not committed).

## Access

| Phone | Serial | WiFi adb | USB | Installed APK |
|---|---|---|---|---|
| A | 10BFAT1SUF000XP | 192.168.66.250:5555 (had lost TCP mode, restored with `adb tcpip 5555` over USB) | yes | `aee266c8c5985245` |
| B | 10BFAT1U0F000XP | 192.168.66.225:5555 | seen at 04:13, not attached at the end | `aee266c8c5985245` |
| C | 10BFAX1C7P0010U | 192.168.66.134:5555 | never attached this session | `aee266c8c5985245` |

Start of session: all reachable phones on `22b407f044f01379` (`sha256sum` of `pm path`). Every
install was `adb install -r`, since the local debug keystore matches.

## Two-class data

The founder said the fault readings had been captured. They are not on any phone:
`samples.jsonl` labels were A `3 x 0, 5 null`, B `2 x 0, 5 null`, C `3 null`, with the newest
sample from 02:24. No real two-class round could be verified.

## Build

`./gradlew :app:assembleDebug :app:testDebugUnitTest`: 194 tests at the start, **207 tests, 0
failures** at the end (summed from `app/build/test-results/testDebugUnitTest/*.xml`). On this
machine the "Unable to establish loopback connection" trap is fixed by
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/jtmp` alone (the user profile path contains a
space); no javaagent needed.

## Spec audit (Sonnet, read-only)

34 requirements from v4, v5, v6, v8, v10 and FEDERATED.md checked against code and
`app_config.json`: 0 gaps. It missed string literals outside `ui/**`; see F7.

## Defects found and fixed

| Id | Defect | Evidence before | Fix | Verified after |
|---|---|---|---|---|
| F1 | Two owners forever after a failover: B took over at 03:32 (`FAILOVER` event "owner unreachable, I2501-a783 took over", ts 1789250559285) and C resumed serving at 05:11; nothing made either step down | `*:8988` LISTEN on B and C; C `owner 1 node(s) merged` 05:11:31 | `Failover.shouldStepDown` (lowest deviceId keeps serving, same rule as takeover) checked in the `FlSyncService` loop, debounced over two checks | C 05:26:11 `owner: yielding to I2501-a783, lower id`, node.json `lastRole CLIENT`, `lastOwnerAddress 192.168.66.225`; B 05:26:20 `owner 2 node(s) merged` |
| F2 | A client goes silent when mDNS drops a live owner | A 05:26:19 `lan: lost 'I2501-a783'`, then no JUGAAD line for 4 min while awake, while C kept syncing to B | `AutoJoin.plan` syncs to `lastOwnerAddress` when no owner is advertised and failover has not triggered | A 05:47:54 and C 05:47:42 `auto-join: owner not advertised, trying last owner 192.168.66.225`, then `client variants=...` |
| F3 | Network tab showed Role Owner / 192.168.49.1 on client C (stale WiFi Direct group) | `C-02-network.png` | Role and owner address read `serving` and node config | `v2-C-network.png`: Owner address 192.168.66.225, Role Client |
| F4 | Pre-check "Reference measurement captured" Pending for equipment with a baseline until Run checks was tapped | `A-11-precheck.png` | baseline loaded on screen entry | `v2-B-precheck.png`: Pass on B iQOO coffee |
| F5 | Calibration card said "No observed samples yet" above "3 of 6 healthy readings with full features" | `A-07-coffee-calib.png` | empty state only at zero; progress shows the calibration count; refresh count labelled as such | `v2-A-coffee-calib.png`: "Healthy count 3", "3 of 5 labelled healthy readings needed to calibrate", "3 of 6 healthy readings with full sensor features needed to refresh the reference measurement" |
| F6 | A vertical scroll that crosses the T1/T2 slider saved new thresholds with no confirmation | Happened during this pass: iQOO coffee on A went from t1 2.0 to 3.0 in `asset.json` | slider edits local state only; "Save thresholds" / "Discard" | C `iPhone box texting`: drag from the thumb shows "T1 = 3.0" and "Not saved yet. Save thresholds or discard to keep the stored values." while `asset.json` keeps `"t1": 2.0`; Discard restores "T1 = 2.0" (`v2-C-slider-dragged.png`, `v2-C-slider-discarded.png`) |
| F7 | HEALTHY shown as a finding on the detail card ("Warning / 2.90 / Fault Healthy / Likely bearing wear"); same in the share report image; em dashes in user-visible strings (`ReportRenderer` title and placeholder, `InferenceBackend.NONE`, a `DiagnoseUseCase` error) | B `table` detail dump | HEALTHY omitted in `AssetDetailScreen` and `ReportRenderer`; dashes replaced | B `table` card now: Warning, Anomaly score, "Likely bearing wear (100%)...", no Fault row (UI dump, `v2-B-table-lastdoc.png`). Report image not re-rendered on device |

Not a defect: C 05:11:31 and B 05:24:07 `fl sync: failed to reply to a client` (`EOFException`),
a client that had already timed out while the owner was frozen; the merge was already persisted.

## Screens checked and passing

- A equipment list (8 items), iQOO coffee History (8 rows, 4.46 shown Critical with no class):
  `A-09-history.png`.
- Critical result: B iQOO coffee 47.26 reads "Critical", "CNN fault class: no specific fault
  identified", no Healthy (UI dump, `v2-B-result-critical.png`). The walk's `A-10-result-critical.png`
  is actually the 1.84 Healthy reading, so that row of the walk proved nothing.
- B Devices as owner: "Serving", "3 active devices" (`B-03-devices.png`).
- No em or en dashes and no clipped text seen on the walked screens; `FATAL EXCEPTION` count 0 on
  A, B and C after every install.

## Owner freeze, re-checked

Reproduced on the new build: with B and C asleep, `dumpsys power` showed
`PARTIAL_WAKE_LOCK 'jugaad:fl-sync' DISABLED ... mIsFrozen` on both, and queued connections sat
unaccepted on 8988 (Recv-Q 4 on B, 1 on C). The loop resumed once the screens were woken. Still
unfixed by choice: no doze whitelist was applied.

## Data

Counts (equipment dirs, baselines, history files, samples, shared, weights, metrics) and md5 of
`samples.jsonl` and `weights_base.bin` were identical before and after each of the four installs
on all three phones (`JugaadAgent-backups/20260913/counts-*.txt`). `weights_base.bin` md5
`12039dc05f6e` on all three; every `metrics_*.json` still `nTrain 0`, `valAcc -1`.

**One loss, caused by this session, open:** on A the iQOO coffee `asset.json` was overwritten
with an empty file by a failed restore (the push used a path adb could not read, and the
redirect ran anyway). The app's `Recovery` moved it to `asset.json.corrupt-1789258951988`.
`baseline.json`, 16 history files, the nameplate and every sample are intact. The original is in
`JugaadAgent-backups/20260913/xA/files/assets/31ec57ac/asset.json` (identical to the pre-session
file apart from the t1 slip, which it predates). Restoring it was blocked by the permission
classifier and waits for the founder.
