# v12: end-to-end verification against spec, no data loss (2026-09-13)

## The constraint being attacked

The founder asked for proof that the whole build matches its plans end to end on the three
phones, reachable over both WiFi adb and USB, with every screen showing what it should, and
without deleting any data or training state already on the phones.

The claimed two-class capture was checked first and is not on any phone (A 3 x class 0 + 5
unlabelled, B 2 x class 0 + 5 unlabelled, C 3 unlabelled; no sample newer than 02:24). So this
pass verifies everything that exists; it does not and cannot verify real two-class training.

## Safety

- Full `files/` tar backups before any change: `Documents/JugaadAgent-backups/20260913/phone{A,B,C}.tar`
  (A 77 entries, B 75, C 38).
- No `tools/reset-phones.sh`, no uninstall, no Delete equipment, no Leave group, no readings or
  labels taken by agents. Updates only with `adb install -r` (local APK sha matches the phones).
- Phone-state changes (role, install, WiFi) stay in the main session.

## Defects found at the start of the pass

| Id | Defect | Evidence |
|---|---|---|
| E1 | Two owners at once: B serving on 8988 since 03:32, C restarted its owner service at 05:11; A syncs to C | `ss -ltn` shows `*:8988` LISTEN on B and C; B `node.json` `lastRole OWNER`, `consecutiveSyncFailures 3`; C logcat `owner 1 node(s) merged` at 05:11:31 and 05:12:16 |
| E2 | C logs `fl sync: failed to reply to a client` | C logcat 05:11:31.268 |

## Failure modes and what catches each

| Failure mode | What catches it |
|---|---|
| A fix passes tests but not on the phone | Logcat line and screenshot per claim after `install -r` |
| Data lost during install | Before/after counts of equipment, baselines, history files, `samples.jsonl` lines |
| A UI claim from an agent is invented | Every row in the report names a PNG or a log line |
| Spec drift hidden by green tests | Sonnet audit maps each plan requirement to file:line |

## Delegate versus build

| Item | Decision |
|---|---|
| Split-brain root cause analysis (E1) | Sonnet, read-only |
| Spec-to-code audit of plans v6, v7, v8, v10 | Sonnet, read-only |
| Fixes | Sonnet with exclusive file ownership, after the analysis |
| Install, role changes, final verification on the phones | Main session |
| UI walk with screenshots (navigation only) | Sonnet, after the fleet has one owner |

## Result (2026-09-13, 06:05 phone clock)

Evidence: `tools/devtest13/REPORT.md`.

- 207 JVM tests, 0 failures (194 at start). APK `aee266c8c5985245` on A, B and C via `install -r`.
- All three phones on WiFi adb; A also on USB. B and C were not attached by USB at the end.
- Spec audit: 34 requirements, 0 gaps in code; string literals outside `ui/` were missed and fixed (F7).
- Seven defects fixed and verified on a phone: split-brain owners (F1), client silent after an mDNS
  loss (F2), Network tab stale role (F3), Pre-check reference Pending (F4), contradictory
  calibration card (F5), scroll saving thresholds (F6), HEALTHY as a finding on the detail card
  plus dashes in user-visible strings (F7; the share image fix is verified by build only).
- Fleet at the end: B owner (only `FlSyncService`), A and C clients merging every ~65 s, 0 crashes.
- Owner freeze with screens asleep reproduced; unfixed by choice.
- No fault-class readings exist on any phone, so two-class training remains unverified.
- Data counts and checksums unchanged across all installs, except A iQOO coffee `asset.json`,
  emptied by a failed restore in this session and quarantined by `Recovery`; restore from
  `JugaadAgent-backups/20260913/xA/...` is pending the founder's approval.
