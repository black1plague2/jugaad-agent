# Federated on-device learning across three iQOO phones

Date: 2026-09-12. Owner: orchestrator. Status: approved decisions, implementation in progress.

## Goal

Three identical phones (Snapdragon 8 Elite Gen 5 / SM8850, 16 GB RAM) each train the fault
classifier on their own labelled readings, then periodically merge their models over WiFi
Direct (no cloud, no server, works in airplane mode). Every phone ends every sync round with a
better classifier than it started with, and the app keeps learning from technician labels.

## Decisions (approved by founder 2026-09-12)

1. **Runtime: LiteRT / TensorFlow Lite 2.16.1 with on-device training signatures.** Not
   ExecuTorch: its Android training bindings are experimental and the prebuilt AAR does not
   ship them (inference-only). TFLite signature-based training has been stable since TF 2.7.
   The existing ExecuTorch classifier files stay untouched, flag off.
2. **Model: a small trainable head on baseline-relative features, not a from-scratch CNN.**
   Each phone has tens of samples, not thousands. A 128x128 CNN trained from scratch on that
   overfits and averages badly; a 16.7k-parameter MLP on the already-verified 256-d log-mel
   statistics trains in milliseconds, FedAvg-merges cleanly, and lets the live demo show a
   full label -> train -> sync -> all-three-phones-updated loop in seconds. Input is
   `feature - baseline.meanFeature`, so the head learns fault signatures as deltas from each
   machine's own healthy fingerprint and transfers across machines. A frozen YAMNet
   embedding is the documented upgrade if data grows (INPUT_DIM is the only constant that
   changes).
3. **Transport: WiFi Direct (`WifiP2pManager`) + plain TCP sockets.** Requires adding the
   `INTERNET` permission (Android gates all sockets on it). Pitch wording changes from "no
   INTERNET permission" to "no cloud, peer-to-peer only, works in airplane mode".
4. **Aggregation: FedAvg weighted by local sample count, one round per sync session, with an
   accept-guard** (a phone rejects a merged model that scores >10 points worse than its local
   model on its own samples, unless it has <5 samples).
5. **Group topology: persistent WiFi Direct group.** One phone creates the group (group owner =
   aggregator), the other two join once (one system dialog each). The group stays up for the
   session, so later syncs are socket-only and need no dialogs. GO runs a foreground service
   that listens; clients sync on demand and on a 15-minute WorkManager schedule.

## Pipeline design

- **Constraint:** correctness of four packages built in parallel by different agents against
  one contract, on a codebase that has never been compiled. Shape: contract-first parallel
  build, then a single integration compile pass.
- **Failure modes:** agent invents a LiteRT/WifiP2p API -> caught by the compile pass; TFLite
  conversion needs SELECT_TF_OPS -> A1 must report it, orchestrator adds the flex AAR;
  two agents edit the same file -> ownership matrix below; WiFi Direct untestable on this
  machine -> loopback socket tests now, on-device checklist after install; compile-fix agent
  "fixes" by deleting features -> brief forbids behaviour changes and requires a per-fix
  report; FedAvg sim does not converge -> A1 reports numbers, orchestrator tunes lr/epochs.
- **Ledger:**
  - A1 Python head + export + FedAvg simulation: delegate (sonnet). Verifiable by re-running.
  - A2 Kotlin `fl` package + wiring: delegate (sonnet). Verifiable by JVM tests + compile.
  - A3 Kotlin `p2p` package + manifest: delegate (sonnet). Verifiable by protocol tests + compile.
  - A4 Compose Learn screen + label chips: delegate (sonnet). Verifiable by compile + device.
  - Toolchain (JDK 17, SDK, adb): orchestrator. Few commands, license already approved.
  - Compile-fix pass after A1-A4: delegate (sonnet, Bash). Noisy, well-bounded.
  - Plan, contract, integration review, docs: orchestrator.

## File ownership (no agent touches another's files)

| Agent | Owns |
|---|---|
| A1 | `ml/fl/**`, `app/src/main/assets/models/fl_head.tflite` |
| A2 | `app/src/main/java/com/jugaad/agent/fl/**`, `app/src/test/java/com/jugaad/agent/fl/**`, `ml/classifier/FaultClassifier.kt` (additive), `domain/model/InferenceBackend.kt` (additive), `domain/usecase/DiagnoseUseCase.kt` (hook only), `ml/ModelInstaller.kt` (additive), `di/ServiceLocator.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| A3 | `app/src/main/java/com/jugaad/agent/p2p/**`, `app/src/test/java/com/jugaad/agent/p2p/**`, `app/src/main/AndroidManifest.xml` |
| A4 | `app/src/main/java/com/jugaad/agent/ui/learn/**`, `ui/nav/Routes.kt`, `ui/nav/JugaadNavGraph.kt`, `ui/assets/AssetListScreen.kt` (entry point only), `ui/result/ResultScreen.kt` + `ResultViewModel.kt` (label row only) |

## Shared contract

### Feature vector fed to the head (INPUT_DIM = 257)

```
x[0..255] = analysis.feature[i] - baseline.meanFeature[i]      // log-mel per-band mean/std deltas
x[256]    = (analysis.imuIndex - baseline.imuIndexMean) * 10.0 // IMU 5-60 Hz energy-fraction delta, rescaled
```

Labels: `0 Healthy, 1 Rotor Imbalance, 2 Airflow Obstruction` — must match `FaultClass.index`.

### Head architecture and weight layout (WEIGHT_COUNT = 16707)

```
Dense(257 -> 64, relu)  W1 [257,64] row-major, b1 [64]
Dense(64 -> 3)          W2 [64,3]   row-major, b2 [3]
softmax on output
flat weights = concat(W1.flatten(), b1, W2.flatten(), b2)   // 16448 + 64 + 192 + 3 = 16707 float32
```

Initialisation is baked into the exported `.tflite` (fixed seed 7) so all three phones start
from identical weights — FedAvg from round 0 is coherent.

### TFLite signatures (exact keys and shapes; all float32; fixed batch, no dynamic shapes)

| key | inputs | outputs |
|---|---|---|
| `infer` | `x: [1,257]` | `probs: [1,3]`, `logits: [1,3]` |
| `train` | `x: [8,257]`, `y: [8,3]` one-hot | `loss: [1]` |
| `get_weights` | (none — pass a dummy `dummy: [1]`) | `w: [16707]` |
| `set_weights` | `w: [16707]` | `ok: [1]` |

`train` does one plain SGD step (lr = 0.05, mean softmax cross-entropy). No Adam, no
momentum, no save/restore string ops — persistence is `get_weights` -> our own float file ->
`set_weights`. Conversion target: `TFLITE_BUILTINS` only with
`experimental_enable_resource_variables = True`. If builtins-only conversion is impossible,
A1 reports it instead of silently adding SELECT_TF_OPS.

Callers pad the last training batch to 8 by repeating samples.

### Weight file / wire encoding

Little-endian float32, no header, exactly WEIGHT_COUNT floats (66 828 bytes).

### On-device storage (all under `filesDir/fl/`)

```
fl/samples.jsonl      one JSON object per line: {id, assetId, x:[257], label:int|null, source:"HUMAN"|"AUTO"|"PENDING", ts}
fl/local_weights.bin  current weights (after last train or accepted merge)
fl/state.json         {round:int, sampleCount:int, lastTrainedMs:long, lastLoss:float, lastAcc:float, deviceId:string}
```

`deviceId` = random 8-hex generated once and stored in state.json.

### Kotlin API contract — package `com.jugaad.agent.fl` (A2 builds, A3/A4 consume)

```kotlin
object FlConstants { const val INPUT_DIM = 257; const val N_CLASSES = 3; const val TRAIN_BATCH = 8;
                     const val WEIGHT_COUNT = 16707; const val LR = 0.05f; const val DEFAULT_EPOCHS = 10;
                     const val IMU_SCALE = 10f; const val MODEL_ASSET = "models/fl_head.tflite" }

object FeatureDelta { fun build(feature: FloatArray, imuIndex: Double, baseline: Baseline): FloatArray } // 257

object WeightsCodec { fun encode(w: FloatArray): ByteArray; fun decode(b: ByteArray): FloatArray }

object FedAvg { fun merge(contributions: List<Pair<FloatArray, Int>>): FloatArray } // weighted by sample count

enum class SampleSource { HUMAN, AUTO, PENDING }
data class FlSample(val id: String, val assetId: String, val x: FloatArray, val label: Int?, val source: SampleSource, val ts: Long)

class SampleStore(dir: File) {
  fun addPending(id: String, assetId: String, x: FloatArray)
  fun label(id: String, label: Int, source: SampleSource = SampleSource.HUMAN): Boolean
  fun labelled(): List<FlSample>          // label != null
  fun counts(): IntArray                  // size N_CLASSES
  fun pendingCount(): Int
}

class FlModel(modelFile: File) : AutoCloseable {
  fun infer(x: FloatArray): FloatArray                 // probs[3]
  fun trainStep(xBatch: Array<FloatArray>, yBatch: Array<FloatArray>): Float  // exactly TRAIN_BATCH rows
  fun getWeights(): FloatArray
  fun setWeights(w: FloatArray)
  override fun close()
}

data class FlState(val round: Int, val sampleCount: Int, val lastTrainedMs: Long, val lastLoss: Float, val lastAcc: Float, val deviceId: String)

class LocalTrainer(model: FlModel, store: SampleStore, stateFile: File, weightsFile: File) {
  val state: StateFlow<FlState>
  suspend fun train(epochs: Int = FlConstants.DEFAULT_EPOCHS): FlState   // trains on labelled(), saves weights+state
  fun evaluate(w: FloatArray? = null): Float   // accuracy on labelled() with given or current weights; 0f if none
  fun currentWeights(): FloatArray
  fun applyMerged(w: FloatArray, newRound: Int)  // setWeights + save + state.round = newRound
}

/** Composition holder. ServiceLocator exposes `val flRuntime: StateFlow<FlRuntime?>` — null until warmUp() has loaded the model. */
class FlRuntime(val model: FlModel, val store: SampleStore, val trainer: LocalTrainer, val ready: Boolean)

class LiteRtFaultClassifier(model: FlModel) : FaultClassifier   // backend = InferenceBackend.LITERT
```

Additive changes A2 makes to existing types:

```kotlin
// FaultClassifier.kt — add
data class Input(val logMel: FloatArray, val feature: FloatArray, val imuIndex: Double, val baseline: Baseline)
fun classify(input: Input): Prediction? = classify(input.logMel)   // default keeps old impls working

// InferenceBackend.kt — add
LITERT("CPU", "LiteRT · federated head (CPU)")
```

DiagnoseUseCase hook (A2): after `features.analyze`, build `x = FeatureDelta.build(...)`,
call `classifier.classify(Input(...))` instead of `classify(logMel)`, then
`store.addPending(diagnosis.id, assetId, x)`; if `status == HEALTHY && anomaly.score <= 0.5 * T1`
also `store.label(diagnosis.id, 0, AUTO)`. DiagnoseUseCase gets an optional
`sampleStore: SampleStore?` constructor parameter (null = no-op, keeps tests unchanged).

### Kotlin API contract — package `com.jugaad.agent.p2p` (A3 builds, A4 consumes)

```kotlin
class WifiDirectManager(context: Context) {
  data class Peer(val name: String, val address: String, val status: Int)
  data class GroupInfo(val formed: Boolean, val isGroupOwner: Boolean, val ownerAddress: String?)
  val peers: StateFlow<List<Peer>>
  val group: StateFlow<GroupInfo>
  fun start()            // register receiver, init channel
  fun stop()
  fun discover()
  fun connect(address: String)
  fun createGroup()
  fun removeGroup()
}

enum class SyncRole { GROUP_OWNER, CLIENT }
data class SyncResult(val role: SyncRole, val peers: Int, val roundBefore: Int, val roundAfter: Int,
                      val accepted: Boolean, val accBefore: Float, val accAfter: Float, val message: String)

object SyncProtocol { const val PORT = 8988; /* framing: magic "JGFL", u8 version=1, u8 type, u32 jsonLen, json, u32 floatCount, floats LE */ }

class FedAvgCoordinator(trainer: LocalTrainer) {
  suspend fun runAsClient(ownerAddress: String): SyncResult
  suspend fun serveOnce(serverSocket: ServerSocket, windowMs: Long = 5000): SyncResult  // accept clients for window, merge, reply
}

class FlSyncService : Service   // foreground, type connectedDevice; runs serveOnce in a loop while GO
object SyncScheduler { fun enable(context: Context); fun disable(context: Context); fun isEnabled(context: Context): Boolean } // WorkManager periodic 15 min
```

Accept-guard lives in `FedAvgCoordinator`: `accepted = local.sampleCount < 5 || accAfter >= accBefore - 0.10f`.

## Device facts (verified via adb 2026-09-12)

Two phones authorised for adb: serials `10BFAT1SUF000XP`, `10BFAT1U0F000XP`. Both: model
I2501 (iQOO 15), `ro.soc.model=SM8850`, Android 16 (API 36), 15.6 GB MemTotal,
`feature:android.hardware.wifi.direct` present. Third phone: USB debugging not enabled yet —
install its APK via share/sideload or enable debugging. Toolchain on this machine:
JDK 17 `H:/IQOO Hackathon/tools/jdk17`, SDK `H:/IQOO Hackathon/tools/android-sdk`
(platform-tools r37, android-35, build-tools 35.0.0), `local.properties` written.

## Verification plan

- A1: `python ml/fl/fedavg_sim.py` prints per-round accuracy for 3 clients; final merged
  accuracy >= 0.9 on synthetic data; `fl_head.tflite` exists, 60-80 KB, 4 signatures.
- A2/A3: `./gradlew :app:testDebugUnitTest` passes new tests (FeatureDelta, WeightsCodec,
  FedAvg, SampleStore, SyncProtocol loopback).
- Integration: `./gradlew :app:assembleDebug` green; APK installs on both debug-enabled phones;
  `adb logcat -s JUGAAD:*` shows `LiteRT head loaded` and a train/sync line.
- On-device (founder): phone A `Create group`, B/C `Connect`; label 5+ readings on each;
  `Train now` on each; `Sync now` on B and C; all three show the same round number and
  the merged accuracy line.

## Result (verified 2026-09-12)

- `./gradlew :app:assembleDebug` green after four one-line fixes (two pre-existing: `inline` on
  interface members in `Outcome.kt`, missing `getValue` import in `SpectrogramView.kt`).
- `./gradlew :app:testDebugUnitTest`: 26/26 (8 suites incl. the two pre-existing ones), after
  `unitTests.isReturnDefaultValues = true` for `android.util.Log`.
- Installed on both adb phones; logcat `LiteRT head loaded` on both.
- Two-phone device test (agent-driven over adb, synthetic labelled samples 30/phone, non-IID):
  `fl train: n=30 epochs=10 …` on both; group formed (owner 192.168.49.1); two FedAvg rounds:
  `fl sync: owner round 0 -> 1, 1 client(s), accepted=true` / `client round 0 -> 1` and the same
  for `1 -> 2`; both phones end on round 2. Screenshots in `tools/devtest/`.
- Findings carried into v2: discovery names are ambiguous (three "iQOO 15"), show address
  suffix; role chip must derive from the group flow, not the peer list (it emptied briefly after a
  sync while the socket link stayed up). Git Bash needs `MSYS_NO_PATHCONV=1` for adb paths.

## Open items

- Office Kit / landscape, threshold calibration, provenance rebuild: unchanged, see HANDOFF.md.
- QNN NPU path: unchanged, out of scope for the FL work (head is too small to benefit).
