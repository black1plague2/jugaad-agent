# v14: owner handover over WiFi Direct with no router (2026-09-13)

## The constraint being attacked

v13 verified handover on a shared router LAN only. The founder asked for the same test with no
router, where the only link between phones is a WiFi Direct group. Read from code before running:
joining a group is manual (Devices > Discover > Connect, `WifiDirectManager.connect`,
`groupOwnerIntent = 0`); `Failover.takeOver` removes and re-creates this phone's group; nothing
reconnects a client to a new group automatically. Expected, to be confirmed or refuted on the
phones: detection and takeover happen automatically, rejoining needs a tap.

## Setup

- All three phones disconnected from "R-1116 wifi" by the founder (auto-connect off), WLAN on.
- Only B (`10BFAT1U0F000XP`) is visible over USB; A and C are not observable live. Their logs are
  pulled over wireless adb after the router is restored (logcat ring buffer, `node.json`).
- B is the lowest deviceId (`a783` < `f0c7` A < `fd22` C), so the scenario makes C the first
  owner and kills it: the predicted new owner is the phone on USB.

## Scenarios

| Id | Scenario | Pass means |
|---|---|---|
| W1 | C serves; A and B Connect to C over WiFi Direct | B logs `client variants=...` to 192.168.49.1; C merges |
| W2 | C force-stopped by the founder | B logs `failing fast`, streak, then advertising / group created, with no tap on B |
| W3 | A rejoins B | Record exactly which taps were needed and whether A merges with B |
| W4 | C relaunched | Record whether one owner remains and what taps it took |

## Failure modes and what catches each

| Failure mode | What catches it |
|---|---|
| Claiming A/C behaviour not observed | A/C rows cite logs pulled afterwards, with timestamps |
| Router reconnects mid-test | `cmd wifi status` on B before and after; A/C logs show 192.168.66.x targets |
| Data loss | No install, no wipe in this pass; counts checked at the end |

## Delegate versus build

All phone-state changes and observation stay in the main session; no code change is planned
unless a scenario fails, in which case a fix goes to a Sonnet agent with a contract.

## What the first attempt showed (09:28-09:41 phone clock)

The founder took the phones off the router; only B was ever visible on USB, and the phones
rejoined the router twice, so W1-W4 were not run as written. Logs pulled afterwards
(`tools/devtest14/logs/W-offrouter-*.txt`, `dumpsys wifip2p`) show:

| Id | Finding | Evidence |
|---|---|---|
| D1 | Every isolated phone made itself owner of its own WiFi Direct group; nothing joined either group | A 09:35:28 `lan: advertising 'I2501-f0c7'`, C 09:36:32 `failover: no owner appeared, taking over`; `dumpsys wifip2p` A and C `GroupCreatedState`, `isGroupOwner: true`, `192.168.49.1` |
| D2 | On these phones WiFi P2P sits in `P2pDisabledState` with WLAN on until an app uses it; the first call wakes it, overlapping calls log BUSY although the group does form | B 09:36 `curState=P2pDisabledState` with `Wifi is enabled`; A rec[78-80] 09:35:23-24 REMOVE_GROUP in P2pDisabledState, CREATE_GROUP -> InactiveState -> GroupNegotiationState; app log `createGroup failed (BUSY)` at the same time |
| D3 | "Stop" leaves `lastRole OWNER`: the Network tab reads Role Owner on a phone that is not serving, and the phone serves again on next launch | B `node.json` `lastRole OWNER`, `8988=0`, UI dump `Role` / `Owner` |
| D4 | An owner that yields keeps its WiFi Direct group | C 09:40:37 `owner: yielding to I2501-f0c7`, afterwards `GroupCreatedState isGroupOwner: true` |
| D5 | Back on one router the split healed with no tap | C `owner: yielding to I2501-f0c7, lower id` 09:40:37, 61 s after `lan: found 'I2501-f0c7'` 09:39:36 |

Every phone's WiFi Direct device name is "iQOO 15", so manual Connect cannot tell phones apart.

## Design (founder chose automatic joining)

10. **One mesh group identity.** `sync.meshNetworkName` (must start `DIRECT-xy-`) and
    `sync.meshPassphrase` (8-63 chars) in `app_config.json`. An owner creates its group with
    `WifiP2pConfig.Builder().setNetworkName().setPassphrase()` (API 29+, minSdk is 29), both in
    `FlSyncService` and `Failover.takeOver`; a client joins with the same credentials through
    `WifiP2pManager.connect`, which needs no pairing dialog.
11. **Join before take over.** When no LAN owner answers (no advertised owner, last owner does not
    answer PING), a client first joins the mesh group. If it joins and the group owner answers PING
    at the group owner address, it syncs there. Only if joining fails does the failover path
    continue, so a higher id that would otherwise take over after the "+2" override joins the
    lowest id's group instead. A returning former owner also joins first and serves only if no
    group owner answers ("first owner wins" on WiFi Direct; the lower-id step-down stays the LAN
    rule).
12. **P2P wake-up.** Serialise WiFi Direct calls (remove, create, connect) and treat a BUSY
    during wake-up as retryable, confirming the result from connection info rather than the
    listener code.
13. **D3 and D4.** "Stop" persists `lastRole CLIENT`; leaving OWNER mode removes this phone's
    group if it is the group owner.

## Decision (2026-09-13, 09:5x)

The founder will demo on the hotspot only. Items 10 to 12 (WiFi Direct mesh auto-join) are not
built; the implementation agent was stopped before it changed any file. WiFi Direct without a
router stays manual (Discover > Connect) and unverified. The v13 build `8b16736736b1d236` on A, B
and C is unchanged. D3 and D4 remain open.

## Result

- Not run as a no-router test; D1 to D5 above are the verified findings from the attempt.
- Fleet at 09:40:50: A `I2501-f0c7` owner (only listener on 8988), B and C clients, after C
  yielded to A with no tap once all three were back on the same network.
