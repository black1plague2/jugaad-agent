# E9: LAN peer discovery by node name, on-device verification (2026-09-12, 21:4x-21:5x phone clock)

Build `950940a001a56dae` (168/168 JVM tests) installed on A, B, C via `tools/reset-phones.sh
--keep-equipment` (learning data wiped, node identities regenerated). A over wireless adb
(`192.168.66.250:5555`), B and C over USB. All three phones on the same office WiFi.
Equipment on the phones is bench-flagged only (A: "Coffee machine (bench)"; B: "iQOO laptop",
"iQOO coffee", both `benchTest: true`; C: none). No readings were taken in this pass.

| Step | Result | Evidence |
|---|---|---|
| A: Devices tab, tap "Serve as owner" (no WiFi Direct group existed) | PASS: status "Serving" | A log `lan: advertising 'I2501-6e00' on port 8988`; `A-01-devices-serving-3-nodes.png` |
| B, C: open Devices tab, no tap | PASS: "Nearby on this WiFi, 1 owner: I2501-6e00, 192.168.66.250" within 2 s | B log `lan: found 'I2501-6e00' id=6e000520 at 192.168.66.250:8988` (21:47:59, service started 21:47:58); C same at 21:48:34 |
| B, C: tap Sync on the nearby owner | PASS | B `fl sync: client target 192.168.66.250` then `client variants=[base], rounds {base=0} -> {base=1}`; C same; A `fl sync: owner 2 node(s) merged [base]` |
| Status after sync | PASS | A "Serving, 3 active devices"; B "Synchronized, 3 active devices" (`B-02-devices-synchronized.png`) |
| Node names in the registry | PASS | B Network tab lists I2501-6e00 (owner), I2501-6402, I2501-acba (`B-04-network-node-names.png`) |
| C: "Sync now" with no owner chosen (automatic pick) | PASS | C `fl sync: lan scan found 1 owner(s) [I2501-6e00], picked I2501-6e00`, then `rounds {base=1, deep=0} -> {base=2, deep=1}` |
| Owner sees its own advertisement | PASS: filtered out of the Nearby list ("0 owners" on A) | A log `lan: found 'I2501-6e00' ... at 192.168.66.250` and `... at 192.168.49.1` (both interfaces) |

Observation that led to a hardening after this pass: the owner's service resolves once per
interface, and the WiFi Direct group address (192.168.49.1) can overwrite the router address for
the same name. `LanDiscovery` now keeps a router address over a 192.168.49.x one; clients in this
pass only ever resolved the router address, so the sync path above was unaffected.

Final build `19db41fcdfde61ff` (router-address preference, 168/168 tests) installed on A, B, C with
`adb install -r` and re-checked: A's owner service restarted on launch and advertised
(`lan: advertising 'I2501-6e00' on port 8988`, 21:53:07), B listed A by name and synced
(`fl sync: client target 192.168.66.250`, `rounds {base=1, small=0} -> {base=3, small=1}`), A
`fl sync: owner 1 node(s) merged [base, noise, small]`, "Serving, 3 active devices".

## E10: auto-join (build `45f62e171aa9d608`, 171/171 tests; final `4f255d832ddaace1` after a status-label fix)

| Step | Result | Evidence |
|---|---|---|
| Install and launch all three; A's owner service restores itself on launch | PASS | A `lan: advertising 'I2501-6e00'` 22:01:48 |
| B and C join A with no tap | PASS: within 3 s of discovery | B `lan: found 'I2501-6e00'` 22:01:54, `auto-join: syncing with 'I2501-6e00' at 192.168.66.250` 22:01:57, `rounds {base=4, small=2} -> {base=5, small=3}`; C the same at 22:02:00/22:02:03; A `owner 1 node(s) merged` twice |
| Devices header on a joined client | PASS | B: "Synchronized", "Joined I2501-6e00, next sync in 55 s", "3 active devices" (`B-05-autojoin-joined.png`) |
| Owner stops (A taps Stop) | PASS | A, B `lan: lost 'I2501-6e00'`; B header "Waiting for an owner on this WiFi" |
| A different phone becomes owner (C taps Serve as owner) and the rest join it by themselves | PASS | C `lan: advertising 'I2501-acba'` 22:03:43; A and B `lan: found 'I2501-acba' at 192.168.66.134` 22:03:44, `auto-join: syncing with 'I2501-acba'` 22:03:46, both `client variants=... rounds ... -> {base=9, ...}`; C `owner 2 node(s) merged [base, deep, noise, small]`; C header "Serving; other phones on this WiFi join this one", A header "Joined I2501-acba, next sync in 40 s" (`A-06-autojoined-new-owner-C.png`) |
| Status label on the former owner | Defect, fixed in `4f255d832ddaace1`: A's chip read "Group formed, not serving" (its old WiFi Direct group) although it had joined C; `syncStatus` now reports Synchronized for a client with a successful sync, and a stale self-owned group no longer blocks manual "Sync now" | Re-checked on `4f255d832ddaace1`: after reinstall and launch, A and B auto-joined C again (`auto-join: syncing with 'I2501-acba'` 22:06:34 and 22:06:37, rounds {base=11} -> {base=12}); A header now "Synchronized", "Joined I2501-acba, next sync in 17 s", "3 active devices" (`A-07-former-owner-synchronized.png`) |

Not covered here (E8 died on a Sonnet session-limit 429 before reporting): recovery from a corrupt
`network.json`, the failover banner, Pre-check scroll, hidden bench calibration card, single-line
chips. Those v5.1 fixes are verified by build and unit tests only.
