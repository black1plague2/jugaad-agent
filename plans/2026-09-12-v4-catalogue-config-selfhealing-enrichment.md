# v4: machine catalogue + issue suggestions, config not constants, adaptive calibration, self-healing nodes, peer dataset enrichment, feature importance

Date: 2026-09-12. Builds on v1-v3 plans in this folder and on `H:/IQOO Hackathon/SESSION_HANDOFF.md`
(other session: 257->260 sample migration in `SampleStore`, three-phone sync verified, round 7,
champion `deep`). Binding for agents E1-E6. Scope is exactly the founder's list; nothing beyond it.

## Goal (founder, 2026-09-12)

Compatibility with many common machines with specific baselines; suggest what the issue might be
from keywords instead of a bare health indicator; calibration that adapts so everything odd is
detected; self-healing nodes that recover; dataset enriched from all users, model and features
improving over time; nothing hard-coded, everything configured; hardware used properly; scalable.

## Design

### 1. Machine catalogue (data, not code) — `app/src/main/assets/config/machines.json`

```
{ "schemaVersion": 1,
  "types": [ { "id": "coffee_vending", "label": "Coffee / tea vending machine",
               "keywords": ["coffee", "tea", "vending", "filtra", "dispenser", "boiler"],
               "expectedRotationHz": [0, 60], "notes": "pump + boiler + mixer motor + fan",
               "faults": [ { "id": "pump_cavitation", "label": "Pump cavitation or low water",
                             "keywords": ["gurgling", "rattle", "no water", "airlock"],
                             "evidence": [ {"metric": "lowBandDelta", "op": ">", "value": 0.6, "weight": 1.0},
                                           {"metric": "temporalVariability", "op": ">", "value": 0.4, "weight": 0.8},
                                           {"metric": "cnnClass", "op": "==", "value": 1, "weight": 0.5} ],
                             "action": "Check water supply and inlet filter; prime the pump.",
                             "severity": "WARNING" }, ... ] }, ... ,
             { "id": "generic", "label": "Other rotating machine", "keywords": [], ... } ] }
```

Types (E1 authors the content, minimum): coffee_vending, desk_fan, ceiling_fan, exhaust_fan,
ac_outdoor_unit, refrigerator_compressor, water_pump, washing_machine, air_compressor,
diesel_generator, printer_3d, server_rack_fan, generic. Each type: 3-6 faults with keywords,
evidence rules over the fixed metric names below, an action sentence, severity hint. The three
CNN classes map to catalogue faults through `cnnClass` evidence (0 healthy, 1 imbalance,
2 airflow), so the learned model is one witness among the rules, never the only one.

Evidence metric names (fixed vocabulary produced by `EvidenceExtractor`; rules may use only these):
`lowBandDelta, midBandDelta, highBandDelta, veryHighBandDelta, broadbandDelta, spectralSpread,
peakiness, dominantHz, lineHumDelta, temporalVariability, imuDelta, gyroDelta, magDelta,
magRmsDelta, anomalyScore, cnnClass, cnnConfidence`. Band edges and line frequencies come from
`app_config.json` (`features.bandGroups`, `features.lineHz`), never from code constants.

Rule scoring: `confidence = sum(weight of satisfied conditions) / sum(all weights)`; a fault is
suggested when confidence >= `diagnosis.minConfidence` (config, default 0.5); top
`diagnosis.maxIssues` (default 3) are shown, sorted by confidence, each with its matched keywords
and action. When status is HEALTHY nothing is suggested. `IssueSuggestion(faultId, label,
confidence, matchedKeywords, action, severity)` is persisted on the `Diagnosis`.

### 2. App configuration — `app/src/main/assets/config/app_config.json`

Every tunable that is a literal today moves here; Kotlin reads `AppConfig` (typed data class
tree, kotlinx.serialization, defaults = the current values). Layers, lowest to highest:
asset defaults -> `filesDir/config/overrides.json` (device) -> network policy (owner's
non-device-local sections replicated in `NetworkState.policy` as flattened `"section.key" ->
value`). Sections and keys (defaults in parentheses):

```
thresholds   { t1 (2.0), t2 (4.0), spreadFloor (0.02) }
calibration  { auto (true), k1 (3.0), k2 (6.0), minHealthy (5), healthyQuantile (0.9), driftWindow (8),
               driftRatio (0.5), autoRefreshBaseline (true), refreshMinHealthy (6) }
sensors      { zWeights { accel 1.0, gyro 0.8, mag 0.5, magRms 0.5 }, zFloor (0.02), sensorScoreScale (0.5) }
promotion    { margin (0.03), winsRequired (2), minNVal (8), historyLen (8) }
acceptGuard  { minTrain (5), maxDrop (0.10) }
training     { maxEpochs (30), fixedEpochs (10), patience (5), minVal (4), batch (8) }
recipes      { noiseSigma (0.15), distillAlpha (0.99), distillMinConf (0.9), uncertainTrigger (0.6), uncertainFloor (0.1) }
sync         { port (8988), windowMs (5000), connectTimeoutMs (5000), readTimeoutMs (30000), schedulerMinutes (15),
               staleMinutes (30), retryBackoffMs [2000, 5000, 15000], failoverAfterFailures (3), autoFailover (true) }
sharing      { enabled (true), maxPoolSamples (5000), maxPerOrigin (1500), batchSize (200) }
budget       { maxThreads (4), thermalMax (2 = MODERATE), minBatteryPct (15) }   // device-local, never replicated
ranking      { costPerMs (0.002), unlabelledBonus (2.0) }
diagnosis    { minConfidence (0.5), maxIssues (3) }
features     { schemaVersion (3), bandGroups { low [20,150], mid [150,1200], high [1200,6000], veryHigh [6000,11000] }, lineHz [50, 100] }
```

`AppConfig.load(context)` at startup; `AppConfig.effective: StateFlow<AppConfig>`;
`AppConfig.source(key)` = DEFAULT | OVERRIDE | POLICY for the Config panel. Owner publishes its
non-device-local sections as policy on every merge; clients apply them (event `INFO "policy
applied from <owner>"` when anything changed). Python mirrors the training/recipe scalars it
needs by reading the same JSON (`ml/fl/app_config.py`).

### 3. Adaptive calibration and "everything odd"

- `Baseline` += `imuIndexStd, gyroIndexStd, magIndexStd, magRmsStd` (defaults 0.0).
  `CaptureBaselineUseCase` fills them from the clips.
- `AnomalyScorer.score(...)` gains sensor inputs: `z_s = |delta_s| / max(std_s, zFloor)`;
  `sensorScore = sensorScoreScale * max_s(zWeight_s * z_s)`; `score = max(acousticScore,
  sensorScore)`; result carries both parts and which one dominated (`dominantSource`).
- `AdaptiveCalibration` (replaces one-shot `CalibrateUseCase.apply` math, same use case class):
  robust stats on healthy scores: `T1 = median + k1 * MAD`, `T2 = median + k2 * MAD` (MAD floored
  at 0.05); if faulty samples exist, `T1 = min(T1, midpoint(max healthy, min faulty))` when that
  midpoint > median, `T2 = max(T2, midpoint(T1, max faulty))`, always `T2 >= 1.5 * T1`. Runs
  automatically after every `label()` for that asset when `calibration.auto`, needs
  `minHealthy`. Per-asset record `assets/<id>/calibration.json` `{t1, t2, method, nHealthy,
  nFaulty, medianHealthy, madHealthy, drift, lastRunMs}`.
- Drift: mean anomaly score of the last `driftWindow` healthy-labelled readings > `driftRatio *
  T1` -> `drift = true` (banner "Reference measurement drifting"). If `autoRefreshBaseline` and at
  least `refreshMinHealthy` healthy readings carry absolute features, the baseline is recomputed
  from their absolute features (mean, spread as mean cosine distance to the new mean, sensor
  means/stds) and saved; event `INFO "reference refreshed for <asset>"`. `FlSample` += `abs:
  FloatArray?` (256 absolute log-mel stats), `absSensors: FloatArray?` (4 absolute indices),
  `origin: String?`, `machineTypeId: String?`.

### 4. Self-healing nodes

- `Recovery.repair(dir)` at startup: every JSON under `fl/` and `assets/*/` is parsed; a
  corrupt file is moved to `<name>.corrupt-<ts>` and recreated from defaults; weight files with
  the wrong length are moved to `.stale`; one `EventType.RECOVER` event per repair; summary in
  `RecoveryReport(repairedFiles, staleWeights)` exposed on `FlRuntime.recovery`.
- `NodeConfig` += `lastRole: NodeRole (NONE|OWNER|CLIENT)`, `lastOwnerAddress: String?`,
  `consecutiveSyncFailures: Int`, `shareSamples: Boolean (true)`. Role is updated on every
  successful `serveOnce`/`runAsClient`.
- Startup restore: `lastRole == OWNER` -> `FlSyncService.start`; the service, if the group is
  not formed after `requestConnectionInfo`, calls `createGroup()` and retries the bind with the
  configured backoff. `lastRole == CLIENT` -> nothing until the scheduler or the user syncs.
- Client sync = `SyncNow.asClient` with `sync.retryBackoffMs` attempts; failures increment the
  counter; success resets it. Failover: when `consecutiveSyncFailures >= failoverAfterFailures`
  and `sync.autoFailover` and this node has the lowest `deviceId` among nodes seen within
  `staleMinutes` excluding the previous owner -> `removeGroup(); createGroup(); FlSyncService.start`,
  `lastRole = OWNER`, event `FAILOVER "owner unreachable, <name> took over"`. Other nodes see the
  banner "Owner changed to <name>: Discover, then Connect" (WiFi Direct needs a tap on a new
  pairing). Manual "Promote this node to owner" button does the same on demand.
- `SyncWorker` (15 min, unique) becomes the watchdog too: OWNER and not serving -> start the
  service; CLIENT -> sync with backoff.
- `FlSyncService` publishes `SyncBus.serving`; the service and the worker never throw.

### 5. Dataset enrichment from every node (peer sample exchange)

- `SharedPool(dir)` = `fl/shared_samples.jsonl`, samples with `source = PEER` and `origin`.
  Caps from `sharing`: oldest evicted first, per-origin cap.
- Protocol v2 additions (same framing): `SAMPLE_IDS = 7` (JSON: `ids: [String]`), `SAMPLES = 8`
  (JSON: `samples: [FlSampleWire]`, `FlSampleWire = {id, origin, assetId, machineTypeId,
  x:[260], label, score, ts}`; no `abs` on the wire). Client session after `WEIGHTS…DONE`:
  client -> `SAMPLE_IDS` (its own labelled ids + pool ids); owner -> `SAMPLE_IDS` (ids it wants
  from the client, up to `batchSize`) then `SAMPLES` (pool samples the client lacks, up to
  `batchSize`); client -> `SAMPLES` (requested ones); both -> `DONE`. Only when both sides have
  `sharing.enabled && config.shareSamples`; v3 peers without the messages still work (owner treats
  `DONE` right after weights as "no sharing").
- Training set on every node = own labelled + pool (PEER samples). Held-out split still by id
  hash. Dataset counts on the phone show own vs peer. `featureSchemaVersion` in every WEIGHTS and
  SAMPLES header; mismatched contributions are ignored with an event.

### 6. Features and model improving over time

- `FeatureImportance.compute(w, spec, config)`: L2 norm of the first-layer columns per input dim,
  grouped into `bandGroups` + the four sensors, normalised to 100; top groups shown on the Network
  tab ("What the champion listens to"). Recomputed after every train/merge.
- Python: `ml/fl/field_ingest.py` pulls `fl/samples.jsonl` and `fl/shared_samples.jsonl` from
  every adb-attached phone into `ml/data/field/`, dedupes by id, reports counts per label,
  origin and machine type; `pretrain.py --include-field` adds them to the offline training set.
  The path from phones to the next exported heads is therefore: label on any phone -> shared to
  all nodes -> pulled into `ml/data/field/` -> retrained heads baked into the next APK.

### 7. Hardware and scale (already in place, keep honest)

Owner window accepts any number of clients; event log capped at 50, standings history 8, pool
and batch caps from config; thermal/battery budget; interpreter threads = min(maxThreads,
cores); all training on the default dispatcher; the nine model files total < 300 KB.

## Kotlin API contract v4 (additive to v1-v3)

```kotlin
// core/config
@Serializable data class AppConfig(val thresholds: Thresholds, val calibration: Calibration, val sensors: Sensors, val promotion: Promotion,
    val acceptGuard: AcceptGuard, val training: Training, val recipes: Recipes, val sync: Sync, val sharing: Sharing, val budget: Budget,
    val ranking: Ranking, val diagnosis: Diagnosis, val features: Features)   // nested @Serializable data classes with the defaults above
object ConfigStore { fun load(context: Context): AppConfig; val effective: StateFlow<AppConfig>; fun applyPolicy(policy: Map<String, String>): Boolean /* true if changed */;
                     fun policyOf(cfg: AppConfig): Map<String, String>; fun source(key: String): ConfigSource; fun resetOverrides(context: Context) }
enum class ConfigSource { DEFAULT, OVERRIDE, POLICY }
// core/config/MachineCatalog
@Serializable data class MachineType(val id: String, val label: String, val keywords: List<String>, val expectedRotationHz: List<Double>, val notes: String, val faults: List<FaultRule>)
@Serializable data class FaultRule(val id: String, val label: String, val keywords: List<String>, val evidence: List<Condition>, val action: String, val severity: String)
@Serializable data class Condition(val metric: String, val op: String, val value: Double, val weight: Double = 1.0)
object MachineCatalog { fun load(context: Context): List<MachineType>; fun byId(id: String): MachineType; fun search(query: String): List<MachineType> }
// domain
Asset += machineTypeId: String = "generic"
Diagnosis += issues: List<IssueSuggestion> = emptyList(), dominantSource: String = "acoustic", sensorScore: Double = 0.0
@Serializable data class IssueSuggestion(val faultId: String, val label: String, val confidence: Float, val matchedKeywords: List<String>, val action: String, val severity: String)
// ml/diagnosis
typealias Evidence = Map<String, Double>
object EvidenceExtractor { fun extract(analysis: FeatureExtractor.Analysis, baseline: Baseline, anomaly: AnomalyScorer.Result, pred: FaultClassifier.Prediction?, cfg: AppConfig): Evidence }
object RulesEngine { fun evaluate(type: MachineType, evidence: Evidence, cfg: AppConfig): List<IssueSuggestion> }
// ml/anomaly
AnomalyScorer.score(feature, base, thresholds, sensorDeltas: DoubleArray /*4*/, cfg: AppConfig): Result   // Result += sensorScore, acousticScore, dominantSource
// domain/usecase
class CalibrateUseCase(...) { suspend fun observe(assetId): CalibrationRecord?; suspend fun apply(assetId): Outcome<CalibrationRecord>; suspend fun autoRun(assetId) }
@Serializable data class CalibrationRecord(val t1: Double, val t2: Double, val method: String, val nHealthy: Int, val nFaulty: Int, val medianHealthy: Double, val madHealthy: Double, val drift: Boolean, val lastRunMs: Long)
class RefreshBaselineUseCase(assets, store, cfg) { suspend fun canRefresh(assetId): Int /* healthy readings with abs */; suspend fun refresh(assetId): Outcome<Baseline> }
// fl
FlSample += abs: FloatArray? = null, absSensors: FloatArray? = null, origin: String? = null, machineTypeId: String? = null
SampleStore.addPending(id, assetId, x, score, abs, absSensors, machineTypeId)
class SharedPool(dir: File, cfg: () -> AppConfig) { fun ids(): Set<String>; fun all(): List<FlSample>; fun addAll(samples: List<FlSample>): Int; fun count(): Int; fun countByOrigin(): Map<String, Int> }
enum class NodeRole { NONE, OWNER, CLIENT }
NodeConfig += lastRole: NodeRole = NONE, lastOwnerAddress: String? = null, consecutiveSyncFailures: Int = 0, shareSamples: Boolean = true
NetworkState += policy: Map<String, String> = emptyMap(), featureSchemaVersion: Int = 3
EventType += RECOVER, FAILOVER, SHARE
object Recovery { data class Report(val repairedFiles: List<String>, val staleWeights: List<String>); fun repair(filesDir: File): Report }
object FeatureImportance { data class Group(val name: String, val share: Float); fun compute(w: FloatArray, spec: VariantSpec, cfg: AppConfig): List<Group> }
FlRuntime += val pool: SharedPool; val recovery: Recovery.Report; fun trainingSamples(): List<FlSample> /* own labelled + pool */; fun importance(): List<FeatureImportance.Group>
// p2p
SyncProtocol += SAMPLE_IDS = 7, SAMPLES = 8; Header += featureSchemaVersion: Int? = null, ids: List<String>? = null, samples: List<FlSampleWire>? = null
@Serializable data class FlSampleWire(val id: String, val origin: String, val assetId: String, val machineTypeId: String?, val x: FloatArray, val label: Int, val score: Double?, val ts: Long)
object SyncNow { suspend fun asClient(context: Context): SyncResult? /* retries with backoff, updates NodeConfig role/failures, triggers failover */ }
object Failover { fun shouldTakeOver(config: NodeConfig, network: NetworkState, nowMs: Long, cfg: AppConfig): Boolean; suspend fun takeOver(context: Context): Boolean }
SyncResult += samplesSent: Int = 0, samplesReceived: Int = 0
```

## File ownership

| Agent | Owns |
|---|---|
| E1 | `app/src/main/assets/config/**`, `core/config/**` (new: AppConfig, ConfigStore, MachineCatalog + models), tests for them |
| E2a | `ml/diagnosis/**` (new), `domain/model/Asset.kt`, `domain/model/Diagnosis.kt`, `data/**` (Asset/Diagnosis DTOs), `ml/advisor/TemplateAdvisor.kt`, `ml/advisor/AdvicePrompt.kt`, `domain/usecase/DiagnoseUseCase.kt` (wires evidence, issues, sensor-aware scorer, abs into addPending, auto calibration hook), `domain/usecase/CaptureBaselineUseCase.kt` is E2b's |
| E2b | `ml/anomaly/**`, `domain/model/Baseline.kt`, `domain/usecase/CaptureBaselineUseCase.kt`, `domain/usecase/CalibrateUseCase.kt`, `domain/usecase/RefreshBaselineUseCase.kt` (new), `fl/FlSample.kt`, `fl/SampleStore.kt`, tests for these |
| E3a | `p2p/**`, p2p tests |
| E3b | rest of `fl/**` (Recovery, SharedPool, NodeConfig/NetworkState additions, FeatureImportance, FlRuntime, AutoTrainer, VariantTrainer/RecipeBatching/Promotion/TrainBudget/StrategyRank reading `AppConfig`), `fl` tests, `di/ServiceLocator.kt` (config load, recovery, role restore, catalogue) |
| E4 | `ui/createasset/**`, `ui/result/**`, `ui/assetdetail/**`, `ui/network/**`, `ui/common/fiori/**` (additive only) |
| E5 | `ml/fl/**`, `ml/data/**`, `app/src/main/assets/models/*.tflite` (the resumed C1 agent, then `field_ingest.py`) |
| E6 | device verification only, `tools/devtest4/**` |

Rule for every agent: read `AppConfig` for every number you would otherwise type; if a value you
need has no key, add the key to `app_config.json` AND to the `AppConfig` data class only if you
are E1, otherwise use `AppConfig` as shipped and report the missing key.

## Device access (2026-09-12, 16:05)

Wireless adb over the office LAN (PC 192.168.66.139): `10BFAT1SUF000XP` (A, owner) =
`192.168.66.250:5555`, `10BFAX1C7P0010U` (C) = `192.168.66.134:5555`, `10BFAT1U0F000XP` (B) = `192.168.66.225:5555`, all enabled with
`adb tcpip 5555` while on USB (no pairing code needed that way). Reconnect after a phone reboot
with `adb connect <ip>:5555`. The WiFi Direct
group (192.168.49.x) is a separate interface and is unaffected.

## Result so far (2026-09-12, 16:35)

- All six v4 agents landed; build green with 0 compile errors; 160/160 JVM tests across 31
  suites after two test-expectation fixes (T2 floor at 1.5 x T1 is by contract; the owner keeps
  its own pool samples).
- APK sha256 prefix `4904555a53680a92` installed on A/B/C over wireless adb; every phone logs
  `LiteRT heads loaded: base, small, deep, noise, balanced, uncertain, distill; strategies: … ,
  centroid`, no crashes.
- Pretrained heads from MAFAULDA are baked into the three `.tflite` files (random init won CV,
  0.894 vs 0.874).
- On-device v4 verification (E6, 16:35-17:23, `tools/devtest4/`): real-sensor path on A passed
  (catalogue "filtra", pre-check, reference, default-threshold Warning -> robust calibration
  T1 3.89 / T2 5.84 -> Healthy -> vibration injection -> Critical, magnetometer-dominant, three
  ranked issues, labelled); sharing pools A 57 / B 55 / C 112; POLICY sources on clients;
  importance and device panels. Defects: `Recovery.repair` accepted the bare word `garbage2`
  as JSON (kotlinx parses it as a primitive); manual Sync now and auto-sync bypassed `SyncNow`
  so failures never counted toward failover; the owner's sleeping screen stalled its server.
- H1 fixed all three (repair now requires a JSON object; every client sync goes through
  `SyncNow.asClient`; owner service holds a partial wake lock + high-perf WiFi lock; an
  "Owner unreachable" banner appears after the first failed sync). Build green, 165/165 tests,
  APK sha `ddb5e78ab3bce5ec`. `field_ingest.py` + `pretrain.py --include-field` exist; the only
  field pull so far is bench data and is quarantined as `.DO-NOT-TRAIN`.

## Verification

- JVM: config load/merge/policy/source; catalogue parses and every rule's metric is in the fixed
  vocabulary (test enumerates); rules engine on synthetic evidence; sensor z anomaly; robust
  calibration incl. drift; recovery repairs a corrupt JSON and a wrong-length weight file;
  shared pool caps/eviction; sample-exchange round trip over loopback; failover decision;
  feature importance grouping; schema-version rejection.
- Build green; install over wireless adb on all three; on device: create equipment of type
  "Coffee / tea vending machine" (picker searchable by "filtra"); reference measurement; readings
  healthy then with a fault (or synthetic) -> Result shows "Likely issues" with keywords and
  action; calibration.json written with method `robust`; corrupt `fl/network.json` via `run-as`,
  relaunch -> RECOVER event and app healthy; force-stop the owner -> a client fails 3 syncs ->
  FAILOVER event, new owner serving; sample sharing: counts of peer samples grow on every node
  after a sync; Network tab shows Config panel (policy values), feature importance, recovery
  status; screenshots in `tools/devtest4/`.
