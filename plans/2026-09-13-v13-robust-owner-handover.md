# v13: robust owner/client handover (2026-09-13)

## The constraint being attacked

The founder reports that the owner/client transfer does not work properly. The phones' logcat
from the v12 build (`aee266c8c5985245`, 207/207 tests) confirms it:

| Id | Symptom | Evidence (logcat, JUGAAD tag) |
|---|---|---|
| H1 | A flaps owner/client every ~3.5 min for 20+ min | A 06:24 to 06:45: 4 x `client failed: SocketTimeoutException: Read timed out` to B, `lan: advertising 'I2501-f0c7'`, 60 s later `owner: yielding to I2501-a783, lower id`, repeat |
| H2 | The owner it yields to is not running | B and C have no JUGAAD line from 06:01 to 07:04 (process frozen); B's mDNS record is still resolvable because the OS mDNS responder, not the app, answers it |
| H3 | Two phones take over at once and chase each other | C 07:07:36 `lan: advertising 'I2501-fd22'`, then `auto-join: sync with 'I2501-f0c7' failed ... EHOSTUNREACH` while A was also advertising |
| H4 | Failure streak survives a role change | B serves with `consecutiveSyncFailures 6` in `node.json`; the moment it becomes a client, one failure triggers failover |
| H5 | Processes freeze | `dumpsys activity processes`: A `curProcState=19` (cached) while awake; no phone on `deviceidle whitelist`; HANDOFF v10 note: owner wake lock `DISABLED ... mIsFrozen` |

Root cause: every handover decision treats an mDNS advertisement or a local failure count as
proof of liveness. Neither is.

## Design

1. **Liveness probe.** New `SyncProtocol` frame PING, answered PONG (carrying the owner's
   deviceId) by the owner's accept loop immediately, without opening a merge window and without
   counting as a client. `OwnerProbe.isAlive(host, port, timeoutMs)` on the client side.
2. **Step-down** (`FlSyncService.checkStepDown`): yield only to a lower-id advertised owner that
   answered PING on two consecutive checks. A frozen owner never answers, so no yield.
3. **Takeover** (`SyncNow` -> `Failover`): before taking over, PING the last owner and every
   advertised owner. If any answers, do not take over; point `lastOwnerAddress` at the
   lowest-id live one and reset the streak. Take over only when nobody answers.
4. **Owner pick** (`AutoJoin`, `SyncNow.resolveOwner`): with several advertised owners, pick the
   lowest-id one that answers PING instead of waiting for a tap; fall back to the last owner.
5. **Streak hygiene:** `consecutiveSyncFailures = 0` on takeover, on step-down and when the
   service starts serving.
6. **Screen-off freeze (H5):** a Devices tab row that opens the system "ignore battery
   optimisations" dialog for this app. The founder taps Allow on the phone; nothing is applied
   from adb. Verified separately with screens off; if the OS still freezes the process, record it.

Pure decisions stay pure functions with the probe passed in, so they are table-tested.

## Failure modes and what catches each

| Failure mode | What catches it |
|---|---|
| PING breaks an existing sync session | JVM protocol test: PING then a full client session on one server socket |
| A PING stalls the owner's merge window | Test: PING during an open window returns within the probe timeout |
| Decision logic regresses | Table tests for step-down, takeover and pick with alive/dead probes |
| Works in tests, not on phones | On-device scenarios below, each with a log line |
| Data lost on install | `install -r` only; counts of equipment, baselines, samples before and after |

## On-device scenarios (main session)

- S1 steady state: one owner, two clients merging.
- S2 owner killed (`am force-stop` on the owner): exactly one client takes over, the other joins
  it, no flapping for 5 min.
- S3 old owner returns: the lower id resumes, the other yields only after PONG, one owner remains.
- S4 frozen owner (`am freeze`-like: screen off, or `kill -STOP`): no client yields to it.

## Found during on-device verification (07:2x-07:4x), design additions

| Id | Defect | Evidence |
|---|---|---|
| H6 | Automatic failover can never start the owner service from the background | A 07:46:27 `auto-join: sync threw` `ForegroundServiceStartNotAllowedException: startForegroundService() not allowed due to mAllowStartForeground false` at `Failover.takeOver` -> `FlSyncService.start`; node.json stays `CLIENT`, streak 3 |
| H7 | After the streak reaches the threshold nobody retries: A stops syncing, C waits for A forever; fleet has no owner | A and C: no JUGAAD line after 07:46:27 / 07:46:20 for 3+ min, `8988` not listening anywhere |
| H8 | The OS freezes the app while the screen sleeps even with a foreground service and "Allow background usage" on | `cgroup.events` `frozen 1` on A, B, C at 07:40; zero JUGAAD lines in 4 min asleep; `deviceidle whitelist` empty after the founder's taps |

7. **Mesh service always on.** `FlSyncService` runs whenever the FL runtime is up and auto-join
   is on, started while the app is in the foreground (launch). It has two modes, CLIENT (no
   server, no locks) and OWNER (server socket, advertise, wake and WiFi locks, step-down check).
   Takeover, step-down, "Serve as owner" and "stop serving" switch the mode in-process; nothing
   calls `startForegroundService` from the background. A start that is refused is caught and
   logged, never escapes.
8. **Failover retries.** While no live owner is advertised and the streak is at or above the
   threshold, auto-join runs a failover attempt every interval (probe last owner, then take over
   if this node is the lowest candidate). If no owner has appeared after the streak exceeds the
   threshold by 2, this node takes over regardless of id order; the step-down rule resolves any
   resulting second owner.
9. **H8 is a platform limit here:** screen-off syncing is not claimed. The demo keeps screens on.

## Delegate versus build

| Item | Decision |
|---|---|
| Implementation of 1 to 6 plus JVM tests | One Sonnet agent, owns `p2p/*`, `fl/NodeConfig.kt`, Devices tab file, manifest, tests |
| Install, role changes, scenarios S1 to S4, final verification | Main session |

## Result (2026-09-13, 09:01 phone clock)

Evidence: `tools/devtest14/REPORT.md`.

- 226 JVM tests, 0 failures (207 at start). APK `8b16736736b1d236` on A, B and C via `install -r`;
  equipment, baselines, history and `samples.jsonl` count and md5 unchanged across all four installs.
- H6 (background `startForegroundService` refused) and H7 (no retry, ownerless fleet) were found on
  the phones during this pass, not in tests; items 7 and 8 fix them.
- Verified on the phones with screens awake: S1 steady state; S2 owner force-stopped, A owner in
  58 s and C joined; S3 old owner relaunched, lower id resumes and the other yields only after PONG;
  S4 owner frozen with `kill -STOP`, nobody yields to it, A owner in 1 min 45 s with no second
  owner; S4b frozen owner resumed, single owner again in 30 s. Before the fail-fast change the same
  S4 took 9 min with a 60 s two-owner window.
- H8 is a platform limit: with screens asleep the app's cgroup is frozen on all three phones even
  with a foreground service and "Allow background usage" on. Screen-off syncing is not claimed;
  the Devices row says to keep screens on.
- A's `iQOO coffee` `asset.json` restored (md5 `f6cfd7b3ef16`), visible in the equipment list.
- Not verified: WiFi Direct only (no router) handover; handover with more than three phones.
- Fleet at the end: B owner, A and C clients, 0 crash-buffer entries.
