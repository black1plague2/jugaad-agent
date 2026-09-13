# devtest14: robust owner/client handover (2026-09-13, 07:10-09:01 phone clock)

Contract: `plans/2026-09-13-v13-robust-owner-handover.md`. Logs under `tools/devtest14/logs/`
(JUGAAD tag, pulled with `logcat -d -v time`), screenshot `A-devices-final.png`. Logs and PNGs
are not committed.

## Start state

- All three phones on `aee266c8c5985245` (`sha256sum` of `pm path`), 207/207 JVM tests.
- Backups before any change: `Documents/JugaadAgent-backups/20260913-v13/phone{A,B,C}.tar`.
- A's `iQOO coffee` `asset.json` restored with the founder's approval from
  `JugaadAgent-backups/20260913/xA` (md5 `f6cfd7b3ef16` on laptop and phone); the equipment list
  UI dump on A shows "iQOO coffee" again, 8 equipment, 0 corrupt or FATAL lines after relaunch.
  The failed restore in v12 was Git Bash rewriting `/data/local/tmp` into a Windows path; use
  `MSYS_NO_PATHCONV=1`.

## Defects found (pre-v13 logs `logs/pre-v13-*.txt`, S2 on the first v13 build `logs/S2-*.txt`)

| Id | Defect | Evidence |
|---|---|---|
| H1 | A flapped owner/client every ~3.5 min for 20+ min | A 06:24-06:45: 4 x `Read timed out` to B, `lan: advertising 'I2501-f0c7'`, 60 s later `owner: yielding to I2501-a783, lower id`, repeat |
| H2 | It yielded to an owner that was not running | B and C: no JUGAAD line 06:01-07:04; B's mDNS record still resolved |
| H3 | Two phones took over and chased each other | C 07:07:36 advertising, then `auto-join: sync with 'I2501-f0c7' failed ... EHOSTUNREACH` |
| H4 | Failure streak survived a role change | B serving with `consecutiveSyncFailures 6` in `node.json` |
| H6 | Automatic failover could never start the owner service | A 07:46:27 `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false` at `Failover.takeOver` |
| H7 | After that nobody retried; the fleet had no owner | A and C silent after 07:46, no phone listening on 8988 |
| H8 | The OS freezes the app while the screen sleeps | `cgroup.events` `frozen 1` on A, B and C at 07:40 (B with a foreground service); zero JUGAAD lines in 4 min asleep, also after "Allow background usage" was switched on by the founder; `deviceidle whitelist` stayed empty |

## Fixes (Sonnet, reviewed in the main session)

PING/PONG liveness frame answered by the owner's accept loop outside the merge window
(`OwnerProbe`); step-down, takeover and owner pick require a PONG; streak reset on every role
change; `FlSyncService` always on in CLIENT or OWNER mode, switched in-process so a takeover never
starts a foreground service from the background; failover retry every 15 s while failing, with
a "no owner appeared" override for non-lowest nodes after two extra attempts; ping before each
client session so a dead or frozen owner fails in 3 s instead of 30 s per retry. Main session
added a guard against a second serve loop in `onStartCommand` and corrected the Devices row text.

## Build

226 JVM tests, 0 failures, 0 errors (from `app/build/test-results/testDebugUnitTest/*.xml`).
Final APK `8b16736736b1d236` on A, B and C via `adb install -r`.

## Scenarios on the phones (screens kept awake with `input keyevent 224` every 20 s)

| Id | Scenario | Build | Result | Evidence |
|---|---|---|---|---|
| S1 | Steady state | `33feee6b` and `8b167367` | PASS | B `owner 1 node(s) merged` 09:00:47 and 09:01:00; A and C `client variants=...` 09:00:47 / 09:01:01; only B listens on 8988; `isForeground=true` on all three |
| S2 | Owner force-stopped | `823698bb` | PASS, 4 min | B killed 08:01:12; A `lan: advertising 'I2501-f0c7'` 08:05:18; C `auto-join: syncing with 'I2501-f0c7'` 08:05:19; merges to 08:08:41 |
| S2 | same | `33feee6b` | PASS, 58 s | B killed 08:51:12; A advertising 08:52:10; C `client variants` 08:52:17; only A listening (`logs/S2-fast-*.txt`) |
| S3 | Old owner relaunched | `823698bb` | PASS | B relaunched 08:09:28; C synced to B 08:09:33; A `owner: yielding to I2501-a783, lower id` 08:11:20 after PONG; `node.json` A and C CLIENT -> .225, B OWNER |
| S4 | Owner frozen (`kill -STOP`) | `823698bb` | PASS, 9 min, 60 s split | B stopped 08:15:50; nobody yielded to it; A and C both advertised ~08:24:46; C `owner: yielding to I2501-f0c7` 08:25:48 |
| S4 | same | `33feee6b` | PASS, 1 min 45 s, no split | B stopped 08:44:27; A and C `fl sync: owner 192.168.66.225 did not answer ping, failing fast` every 18 s; A advertising 08:46:12; C `client variants` 08:46:22; C never advertised (`logs/S4-fast-*.txt`) |
| S4b | Frozen owner resumed (`kill -CONT`) | `823698bb` | PASS, 5.5 min | B's mDNS record reappeared on A 08:33:36; A yielded 08:34:55 |
| S4b | same | `33feee6b` | PASS, 30 s | CONT 08:48:57; C to B 08:49:02; A yielded 08:49:24; only B listening |
| S5 | Screens off | `aee266c8` / v13 | NOT SUPPORTED | H8 above; platform limit on this iQOO build, the Devices row now says so |

`logcat -b crash` has 0 `com.jugaad` entries on A, B and C at 09:01. B's logd recorded no lines
from its app process after the 08:09 relaunch (0 entries for that pid in every buffer), so B's
side of S3 and later is evidenced by its `node.json`, `network.json` SYNC events, the 8988
listener and the clients' logs.

## Data

Equipment, baselines, history files, `samples.jsonl` line count and md5 identical before and
after each of the four installs on every phone: A 8 / 8 / 16 / 8 `b8460ba9`, B 3 / 3 / 32 / 7
`22be3a9b`, C 1 / 1 / 6 / 3 `e1222b78`.

## Fleet at the end

B `I2501-a783` owner, A `I2501-f0c7` and C `I2501-fd22` clients pointing at 192.168.66.225.
