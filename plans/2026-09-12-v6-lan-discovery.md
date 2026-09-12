# v6: peer discovery by node name on the same WiFi network

Goal (founder, 2026-09-12 18:4x): phones on the same WiFi network must find each other reliably,
show each other by node name, and sync end to end without depending on the WiFi Direct pairing
dance (Discover, Connect, accept dialog on the owner). WiFi Direct stays as the no-router path.

Constraint: remaining session quota is small and the Sonnet tier already returned a session-limit
429, so the change is kept to one new file plus small edits, implemented directly by the
orchestrator, verified on the phones with log lines and screenshots, then pushed.

Failure modes and what catches them:
- mDNS blocked by the access point (client isolation): then TCP between phones is blocked too, so
  no LAN path can work; the WiFi Direct path is unchanged. Caught by the Nearby list staying
  empty on both clients; the report says so.
- Two owners advertising at once: the client only auto-picks when exactly one owner is visible
  or one matches the last owner address; otherwise the user taps the name. Caught by a unit
  test on the pure picker.
- NsdManager resolve concurrency ("already active" failures): resolves are serialised. Caught by
  the resolved host appearing in the Nearby list within a few seconds.
- Stale WiFi Direct group next to the LAN path: `SyncNow` still prefers a formed group whose
  owner address is set; LAN is the fallback. Caught by the log line naming the host used.

Ledger: implementation direct (Sonnet unavailable, context already loaded); verification direct
via adb; documentation direct.

## Design

- `p2p/LanDiscovery.kt`: `NsdManager` service type `_jugaad-fl._tcp.`; the owner registers a
  service named after its node name with TXT `id=<deviceId>` on `SyncProtocol.port()` while
  `FlSyncService` serves; clients run discovery and expose `StateFlow<List<LanPeer>>`
  (name, deviceId, host, port), resolving one service at a time. Pure helper
  `LanDiscovery.pickOwner(peers, lastOwnerAddress)`.
- `SyncNow.asClient(context, host = null)`: explicit host wins; else a formed WiFi Direct group;
  else a 3 s LAN discovery and `pickOwner`. The host actually used is logged and persisted as
  `lastOwnerAddress`.
- `FlSyncService`: after the server socket binds, advertise; unregister on destroy.
- `NetworkViewModel`: LAN discovery runs for the screen's lifetime; `syncWith(host)`; `syncNow()`
  no longer refuses when there is no WiFi Direct group.
- Devices tab: "Nearby on this WiFi" section listing peers by node name with a Sync button;
  Network tab status treats a successful LAN sync as Synchronized.
- Auto-join (founder, 22:0x: "I set one as the owner, the others get connected automatically"):
  `p2p/AutoJoin.kt`, a process-wide loop started with the FL runtime; pure `plan()` decides
  each turn (off / serving / no owner / ambiguous / sync now / wait), `SyncNow` gets a mutex so
  only one client session runs at a time, `sync.autoJoin` and `sync.autoJoinIntervalMs` in
  `app_config.json`, status line on the Devices tab via `SyncBus.autoJoin`.

## Verification

Build green, JVM tests green (the picker test added). On the phones: A serves; B and C list A by
its node name under "Nearby on this WiFi" without any tap; Sync from B and C succeeds with the
client log line naming A's LAN address and A's owner log line counting the merged node; A's
Devices tab shows 3 nodes by name. Evidence: `tools/devtest9/REPORT.md` and log lines quoted in
the Result section below.

## Result (2026-09-12, 21:5x phone clock)

Implemented as designed: `p2p/LanDiscovery.kt` (advertise, discover, serialised resolve, pure
`pickOwner`), `SyncNow.asClient(context, host)` with the three-step owner resolution,
`FlSyncService` advertising while serving, `NetworkViewModel.syncWith`/`lanPeers`, Devices tab
"Nearby on this WiFi" and "Serve as owner", `Format.syncStatus` "Owner visible on WiFi".
`LanDiscoveryTest` covers the picker (3 cases); 168/168 JVM tests.

On device (build `950940a001a56dae`, wiped learning state, B and C on USB, A wireless, all on the
office WiFi): A `lan: advertising 'I2501-6e00' on port 8988`; B `lan: found 'I2501-6e00'
id=6e000520 at 192.168.66.250:8988` one second later with no tap, C likewise; B and C
`fl sync: client target 192.168.66.250` then `rounds {base=0} -> {base=1}`; A `fl sync: owner 2
node(s) merged [base]`; A "Serving, 3 active devices", B "Synchronized, 3 active devices"; B's
Network tab lists I2501-6e00, I2501-6402, I2501-acba; C "Sync now" with nobody chosen: `lan scan
found 1 owner(s) [I2501-6e00], picked I2501-6e00`, round 2. The owner resolved its own service on
both interfaces (router and 192.168.49.1), which prompted the router-address preference in the
final build `19db41fcdfde61ff`. Screenshots and table: `tools/devtest9/REPORT.md`.

Auto-join (build `45f62e171aa9d608`, then `4f255d832ddaace1`): `AutoJoinTest` covers the pure
planner (immediate sync for a new owner, wait until the interval elapses for the same owner,
never while serving/off/ambiguous, last owner wins); 171/171 JVM tests. On device: B and C
joined A within 3 s of discovery on launch with no tap (`auto-join: syncing with 'I2501-6e00'`,
`owner 1 node(s) merged` twice on A); after A tapped Stop both showed "Waiting for an owner on
this WiFi" (`lan: lost 'I2501-6e00'`); after C tapped Serve as owner, A and B found and joined C
within 3 s (`auto-join: syncing with 'I2501-acba' at 192.168.66.134`, C `owner 2 node(s)
merged`). Defect found and fixed in `4f255d832ddaace1`: the former owner's status chip showed
its stale WiFi Direct group ("Group formed, not serving") instead of Synchronized. Table and
screenshots: `tools/devtest9/REPORT.md` (E10).

Not verified: behaviour on an access point with client isolation (no LAN path can work there;
WiFi Direct unchanged), two owners advertising at once (unit-tested only), the background
scheduler's LAN pick (same code path as "Sync now", not observed on device).
