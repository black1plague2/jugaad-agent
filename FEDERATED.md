# Federated network: champion, challengers, and the phones that vote

Every phone trains the fault classifier on the readings its technician labels. Phones merge their
models over WiFi Direct, with no cloud and no server. Every node also runs a different experiment,
the network scores the experiments on held-out data, and a challenger that keeps winning becomes the
new champion on every phone automatically. New phones join by connecting.

## Model choice

| Question | Answer |
|---|---|
| Input | The reading's 256-d log-mel statistics minus the equipment's own reference measurement, plus the vibration-index delta. Faults become deviations from each machine's healthy fingerprint, so a model learnt on one fan transfers to another. |
| Runtime | LiteRT / TensorFlow Lite 2.16.1 with signature-based on-device training (`infer`, `train`, `get_weights`, `set_weights`), builtins-only graphs. ExecuTorch stays for the optional NPU inference demo; its Android training bindings are not in the prebuilt AAR. |
| Why small heads | Tens of samples per phone, not thousands. A 128x128 CNN from scratch overfits and merges badly. A 8k-19k parameter head trains in milliseconds, so the demo can show label, train, sync, and all phones updating in seconds. |

### Sensors (all local, one 3 s window)

| Sensor | What is measured | Where it lands in the 260-d input |
|---|---|---|
| Microphone, 44.1 kHz | 128-band log-mel, per-band mean and std | dims 0-255 as deltas from the reference measurement |
| Accelerometer | 5-60 Hz energy fraction of the magnitude (rotor-rate vibration) | dim 256 |
| Gyroscope | same algorithm on the angular-rate magnitude (rotational vibration) | dim 257 |
| Magnetometer | 3-25 Hz energy fraction of the field magnitude (motor-related field fluctuation; line frequency aliases at phone rates) and AC RMS in microtesla | dims 258-259 |

A missing sensor contributes 0 for both the reference and the reading, so the delta is 0. The
Pre-check screen lists each sensor with availability and measured rate; barometer, light and
proximity are listed as not used. The reference measurement stores the mean of every index.

### Variants (the "different approaches")

| id | kind | architecture | recipe | uses unlabelled data |
|---|---|---|---|---|
| `base` | MLP | 260-64-3, 16 899 weights | SGD lr 0.05 + weight decay 1e-4, early stopping | no |
| `small` | MLP | 260-32-3, 8 451 | same | no |
| `deep` | MLP | 260-64-32-3, 18 883 | same | no |
| `noise` | MLP | 260-64-3, own weights | + Gaussian input noise 0.15 while training | no |
| `balanced` | MLP | 260-64-3, own weights | + every batch sampled uniformly per class | no |
| `uncertain` | MLP | 260-64-3, own weights | batches favour low-confidence samples; auto-train fires only when a new reading has champion confidence < 0.6 (uncertainty-triggered fine-tuning) | no |
| `distill` | MLP | 260-64-3, own weights | EMA teacher (alpha 0.99 per epoch) pseudo-labels pending readings with confidence >= 0.9; soft targets (self-distillation, mean teacher) | yes |
| `centroid` | centroid | 3 x 260 class means | nearest-centroid on the frozen features, no backprop; FedAvg of centroids is exact | no |

Contrastive self-supervised pretraining runs offline on the public dataset (SimCLR-style views of
the same clip: noise, band dropout, mixup) to initialise the first layer of the MLP heads; the
phone has no encoder to pretrain, so it is not run on device. `ml/data/README.md` reports whether
the contrastive or the random init won the cross-validation and which one shipped.

Every node trains the champion. A node in **Experimental** mode also trains one challenger, assigned
by the group owner (least-populated challenger) or pinned by hand on the Training tab. A node in
**Stable** mode trains only the champion. Weights, rounds and metrics are kept per variant under
`filesDir/fl/`.

### Training honesty

Each node keeps a deterministic 25% held-out split. MLP variants train up to 30 epochs with weight
decay and stop early on held-out loss (patience 5, best epoch restored); with fewer than 4
held-out samples they train a fixed 10 epochs and report "no held-out data". Every variant shows
train accuracy, held-out accuracy, held-out loss, best epoch and the gap; a gap of 15 points or
more is labelled **Overfitting** on the phone. Held-out accuracy is what the network ranks on.

### Strategy ranking and cohorts

`score = 100 * (netAcc[v] - netAcc[champion]) - 0.002 * trainMs[v] + 2 * usesUnlabelled[v]`.
The Network tab lists every variant by score with the three terms visible, next to the
promotion status (Champion, Candidate n/2, Trailing, No data). Two KPI tiles compare the
**Experimental** and **Stable** node cohorts (count, mean champion held-out accuracy, mean round)
so the cost of running experiments on the stable path is visible; the champion is shared, so it
should be zero.

### Device budget

Training uses `min(4, cores)` interpreter threads on the default dispatcher. Auto-train is skipped
when the thermal status is MODERATE or worse, or battery is below 15% and not charging; the
reason is shown. The Device panel on the Network tab shows SoC, cores and threads, thermal
status, battery, memory, last training time per variant and model bytes per variant. Sensor
listeners use 100 ms batching.

### Calibrate thresholds

Every reading stores its anomaly score with the sample. Equipment detail shows the observed score
ranges of readings labelled Healthy and faulty and a **Calibrate** button: with both present,
`T1 = midpoint(max healthy, min faulty)` and `T2 = midpoint(T1, max faulty)` (at least 1.5 x T1);
with only healthy readings (5 or more), `T1 = 1.5 x p90(healthy)`, `T2 = 2 x T1`.

### Public dataset

MAFAULDA (UFRJ) `normal` + `imbalance` subsets (microphone and two triaxial accelerometers at
50 kHz) are converted to 44.1 kHz 3 s clips, run through the phone's exact log-mel front end
(`ml/logmel_reference.py`) and the accelerometer index algorithm, made baseline-relative per
rotation-speed bin, and used to pretrain the three MLP heads with leave-one-bin-out
cross-validation. No public source carries per-file airflow/clogging labels (MIMII pools fan
faults into one folder), so Airflow Obstruction remains synthetic in pretraining and is learnt
on device from technician labels. Result (2026-09-12): 382 real clips (49 healthy, 333
imbalance) plus 333 synthetic airflow clips across 6 rpm bins; leave-one-bin-out accuracy 0.894
with random init versus 0.874 with contrastive init, so the shipped heads use random init trained
on all data (held-out 0.95-0.96 for base/small/deep). Most confusion is healthy-vs-imbalance in
the lowest-rpm bin. Provenance, licence caveat, counts and the full table live in
`ml/data/README.md`; the survey in `ml/data/DATASETS.md`.

### How a challenger becomes champion

1. Each node keeps a deterministic 25% held-out split of its labelled samples
   (`hash(id) % 4 == 0`). Accuracies shown as "held-out" are computed on that split; a node with fewer
   than 4 held-out samples reports no score.
2. At every sync, nodes send per-variant weights with `nTrain`, `nVal`, train and held-out accuracy.
3. The owner merges each variant with FedAvg weighted by `nTrain`, then computes the network score
   `netAcc[v]` as the `nVal`-weighted mean of the held-out accuracies reported for `v` (eligible once
   the network has at least 8 held-out samples for it).
4. `wins[v]` counts consecutive eligible rounds where `netAcc[v] >= netAcc[champion] + 0.03`. At
   `wins >= 2` the owner promotes `v`: the replicated network state carries the new `championId`,
   every phone switches its classifier to it, and all streaks reset. The former champion keeps
   training as a candidate.
5. Each phone still applies the accept-guard per variant (reject a merged model more than 10 points
   worse on its own held-out set, unless it has fewer than 5 training samples).

The Python simulation `ml/fl/network_sim.py` runs this rule on three virtual nodes with a mildly
non-linear synthetic problem; on the seed shipped, `deep` wins twice and is promoted in round 2 (most
seeds keep `base`; the seed was chosen to show the mechanism and is labelled as such in the script).

### Machine catalogue and issue suggestions

Equipment is created with a machine type from `assets/config/machines.json` (13 types: coffee
or tea vending machine, desk fan, ceiling fan, exhaust fan, AC outdoor unit, refrigerator
compressor, water pump, washing machine, air compressor, diesel generator, 3D printer, server rack
fan, generic; searchable by keywords such as "filtra"). Every type carries 3-6 faults a
technician would recognise, each with keywords, an action sentence, a severity hint and evidence
rules over a fixed vocabulary of 17 metrics computed from every reading relative to the
equipment's own reference measurement (band deltas low/mid/high/very high, broadband, spectral
spread, peakiness, dominant Hz, line hum at 50/100 Hz, temporal variability, accelerometer /
gyroscope / magnetometer deltas, anomaly score, CNN class and confidence). A fault is suggested
when its satisfied-evidence weight reaches `diagnosis.minConfidence`; the Result screen shows the
top `diagnosis.maxIssues` as "Likely issues" with confidence, keywords and action, and the
notification proposal leads with the top one. Healthy readings never produce suggestions. The
learned classifier is one witness among the rules through the `cnnClass` metric.

### Configuration, not constants

Every tunable lives in `assets/config/app_config.json` (thresholds, calibration, sensor weights,
promotion, accept guard, training, recipes, sync, sharing, device budget, ranking, diagnosis,
feature bands). Layers: asset defaults, then `filesDir/config/overrides.json` on the device, then
the network policy: the owner publishes its non-device-local sections in the replicated network
state and every client applies them, so the whole network runs one policy. The Configuration
section on the Network tab lists every key with its effective value and source (default,
override, policy) and can reset device overrides.

### Adaptive calibration and drift

Readings store their anomaly score; after every label the equipment's thresholds are recomputed
with robust statistics (`T1 = median + k1 x MAD`, `T2 = median + k2 x MAD` over healthy scores,
tightened by the faulty scores when present, `T2 >= 1.5 x T1`). The anomaly score itself is the
maximum of the acoustic score and a sensor score built from per-sensor z-scores against the
reference measurement's own spread, so a vibration-only or magnetic-only change is detected even
when the microphone hears nothing. When the last `driftWindow` healthy readings score above
`driftRatio x T1`, the equipment shows "Reference measurement drifting" and, once enough healthy
readings with full features exist, the reference can be refreshed from them (automatically when
`calibration.autoRefreshBaseline`).

### Self-healing nodes

At startup every JSON under `fl/` and each equipment folder is parsed; a corrupt file is moved
aside (`.corrupt-<timestamp>`) and recreated, and weight files of the wrong length are moved to
`.stale`; each repair is a RECOVER event visible on the Network tab. A node remembers its role:
an owner restarts its sync service on launch (creating the WiFi Direct group again if needed);
a client syncs with retries and backoff (`sync.retryBackoffMs`). After `failoverAfterFailures`
consecutive failures, the client with the lowest device id among recently seen nodes takes over
as owner (FAILOVER event); other phones see "Owner changed to <name>: Discover, then Connect".
"Promote this node to owner" does the same on demand. The 15-minute worker doubles as a watchdog.

### Dataset enrichment from every node

After the weight exchange, phones that allow sharing (Data sharing switch) exchange labelled
samples: each node sends the ids it holds, the owner requests what it lacks and returns pool
samples the client lacks (capped by `sharing.batchSize` per session, pool capped by
`maxPoolSamples` / `maxPerOrigin`). Every node trains on its own labelled samples plus the peer
pool (`fl/shared_samples.jsonl`); own vs peer counts are shown on the Training tab. Samples carry
their origin node, machine type and anomaly score. `ml/fl/field_ingest.py` pulls these pools from
attached phones into `ml/data/field/` so the next offline retrain bakes real field data into the
shipped heads: label on any phone, shared to all, retrained into the next APK.

### What the champion listens to

Feature importance is the L2 norm of the champion's first-layer weights per input, grouped into
the configured mel bands plus the four sensors, normalised to 100 and shown as meters on the
Network tab. A feature schema version travels with every weight and sample exchange; nodes on a
different schema are ignored with an event instead of being merged.

### Device access for the demo laptop

All three phones are reachable over the office LAN with wireless adb (enabled once over USB with
`adb tcpip 5555`; reconnect with `adb connect <ip>:5555`): A `192.168.66.250`, B `192.168.66.225`,
C `192.168.66.134`. The WiFi Direct group uses its own interface and is unaffected.

## Network protocol v2

Framing: `"JGFL"`, u8 version = 2, u8 type, u32 JSON header length + header, u32 float count +
float32 little-endian weights. Client session: `HELLO` (node card), one `WEIGHTS` per held variant,
`DONE`. Owner reply: one `MERGED` per variant the client holds or was just assigned, `NETWORK` (the
full replicated state: champion, node cards, standings with 8-round history, assignments, last 50
events), `DONE`. Port 8988 over the WiFi Direct group link. `INTERNET` permission exists only
because Android gates sockets on it.

Any node can become owner: it seeds the network from its last replicated `fl/network.json`, so
rounds, standings and streaks continue.

## What you see on the phone

**Federated network** (hub icon on the Equipment list; two tabs; two columns from 720 dp so it fills
an external monitor in landscape):

- **Network tab.** KPI tiles: champion held-out accuracy, best challenger with delta, nodes online,
  round. Variant leaderboard: one row per variant with an 8-round sparkline and a status label
  (Champion, Candidate 1/2 or 2/2, Trailing, No data). Nodes: name, mode and challenger, held-out
  accuracy, round, last seen, Owner / Client / Stale. Activity: the last events (join, sync, train,
  assign, promote). This device: 3x3 confusion grid of the champion on the local held-out set.
- **Training tab.** Node name, Experimental / Stable, challenger picker (Auto or pinned), dataset
  counts per class (train / held-out / pending), per-variant metrics, Train now, Auto-train,
  Auto-sync after training, peer discovery and group controls, Start sync service (owner) or Sync
  now (client), the 15-minute scheduler, and the last sync card (per-variant rounds, accepted,
  held-out before and after, promotion if any).

Labels on every Result screen ("Measurement document") feed the training set; clearly healthy
readings are auto-labelled.

## Design language

Humane Minimalist Dark (inspired by the Stitch redesign, `plans/2026-09-12-v5-humane-minimalist-ui.md`):
canvas `#07080A`, raised cards `#111317`, floating sheets `#181B20`, hairlines at 6% white, text
white / `#9CA3AF` / `#64748B`; one crimson accent `#C30000` reserved for primary actions, the
active bottom-nav item, the champion's progress fill and critical notices; state colours Positive
`#48BB78`, Warning `#E0A437`, Negative `#FFB4AB` on `#93000A`, Informative `#B8C4FF`. Work Sans
(bundled variable font, OFL) with tabular numerals; 16 dp cards, 8 dp controls, 6 dp tags; tonal
layering and whitespace instead of borders; no glows or shadows. The federated area is a
bottom-nav shell with Network, Devices, Sync and Performance plus Group settings, mirroring the
four Stitch screens; every Stitch heatmap placeholder is a real heatmap: the product's own
spectrogram of the latest reading on Network, peer samples by origin on Devices, champion weight
importance on Sync, training activity by round on Performance. Vocabulary stays SAP Plant
Maintenance: Equipment, Reference measurement, Take reading, Measurement document, Measurement
history, Notification proposal. The component names under `ui/common/fiori/` were kept for source
stability; their tokens are the Humane palette.

## Run sheet, three or more phones

1. Same APK on every phone (`app/build/outputs/apk/debug/app-debug.apk`; a copy is staged at
   `H:\IQOO Hackathon\jugaad-agent-debug.apk` for sideloading a phone without USB debugging). WiFi
   on; airplane mode is fine.
2. On each phone: create equipment, run Pre-check (all four sensors should show a rate), capture
   the reference measurement, take readings healthy and with a fault injected (coin on a blade,
   blocked intake), and confirm labels on the Measurement document screen. 12+ labelled readings
   per phone gives 3+ held-out samples; 16+ gives a usable held-out score. Then Calibrate on the
   equipment detail screen so Warning/Critical thresholds match this machine.
3. Open Federated network. On the Training tab, Train now (or wait for Auto-train). Note each node's
   challenger.
4. Phone A: Create group, then Start sync service. Every other phone: Discover, Connect to A (rows
   show name plus MAC suffix), accept the one-time dialog on A.
5. Every client: Sync now. The Network tab on all phones now shows the same round, the node list,
   and the leaderboard with network scores.
6. Keep labelling and training. Each sync re-scores the challengers; a Candidate 2/2 becomes the
   champion on the next win, the Activity list shows `PROMOTE`, and every phone's classifier switches.
7. To add a phone later: install, Discover, Connect, Sync now. The owner assigns it a challenger.

## Proof lines

```bash
adb logcat -c
adb logcat -s JUGAAD:*
# LiteRT heads loaded: base, small, deep, noise, balanced
# fl train: variant=base n=24 epochs=10 loss=0.21 train=0.96 val=0.83
# fl sync: client round base 3 -> 4, deep 3 -> 4 accepted=... val ...
# fl sync: owner round 4, 2 client(s), variants=[base, small, deep] promoted=deep
```

## Files

| Path | Role |
|---|---|
| `ml/fl/variants.py`, `head_model.py`, `export_fl_head.py` | Variant table, N-layer head with the four signatures, export of the three `.tflite` files. |
| `ml/fl/test_fl_head.py`, `fedavg_sim.py`, `network_sim.py` | Layout pins per variant; FedAvg convergence; champion/challenger promotion simulation. |
| `app/.../fl/` | `FlVariants`, `FlModel`, `VariantTrainer`, `SampleStore` (held-out split), `NodeConfig`, `NetworkState`, `Promotion`, `FlRuntime`, `AutoTrainer`, `LiteRtFaultClassifier`. |
| `app/.../p2p/` | `SyncProtocol` v2, `FedAvgCoordinator`, `RuntimePeer`, `FlSyncService`, `SyncNow`, `SyncWorker`, `SyncScheduler`, `SyncBus`, `WifiDirectManager`. |
| `app/.../ui/common/fiori/` | Tokens and Fiori components (KPI tile, object cell, status label, sparkline, meter, confusion grid). |
| `app/.../ui/network/` | Federated network screen (Network and Training tabs). |
| `plans/2026-09-12-champion-challenger-network.md` | Binding contract for all of the above. |

## Known limits

- The first join to a group shows a system dialog on the owner; later syncs do not.
- Held-out scores on a handful of samples are noisy; the 2-consecutive-wins rule and the 8-sample
  eligibility floor are the guard, not a substitute for more labels.
- The owner must keep the app installed and the sync service running; if it leaves, another node
  creates a group and continues from its replicated state.
- The heads are too small to benefit from the NPU; the QNN path remains an inference-only option.

## v6: peer discovery by node name on the same WiFi (2026-09-12)

When the phones share a router, WiFi Direct pairing is no longer required. While
`FlSyncService` serves, the owner advertises the DNS-SD service `_jugaad-fl._tcp.` under its node
name (TXT `id=<deviceId>`) on the sync port; every phone with the federated shell open runs
discovery and lists the advertised owners under Devices > "Nearby on this WiFi" by node name with
a Sync button (`p2p/LanDiscovery.kt`). "Sync now" and the background scheduler go through
`SyncNow.asClient`, which picks, in order: an explicit host, the owner of a formed WiFi Direct
group, or after a 3 s scan the advertised owner that matches the last owner address or the only
one visible (two unknown owners: the user taps a name). A phone with no group can tap "Serve as
owner"; the service still forms a WiFi Direct group for phones without a router. The owner's own
advertisement is filtered out by device id; a name that resolves on both interfaces keeps the
router address over the 192.168.49.x group address. The status chip reads "Owner visible on WiFi"
before the first sync and "Synchronized" after it. Evidence: `tools/devtest9/REPORT.md`.
