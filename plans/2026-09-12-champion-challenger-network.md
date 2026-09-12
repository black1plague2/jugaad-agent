# Champion/challenger federated network, Fiori design language, on-phone visualisation

Date: 2026-09-12. Builds on `2026-09-12-federated-on-device-learning.md` (v1: single head, FedAvg,
WiFi Direct). Status: contract approved by orchestrator under the founder's goal; implementation in
progress.

## Goal (founder, 2026-09-12)

Phones run different approaches and improve automatically; everything is visualised on the phone;
efficient and accurate; proper design language ("SAP conventions"); more phones can join; a
decentralised self-improving network whose experimental nodes try other ways and get adopted when
they improve accuracy.

## Interpretations (state them; the founder can redirect)

1. **"SAP conventions" = SAP Fiori for Android design language (Horizon Evening, dark) + SAP Plant
   Maintenance vocabulary.** Semantic states Positive / Critical / Negative / Informative / Neutral,
   KPI tiles, object cells, status labels, section headers, 8 dp shapes, tabular numerals.
   Vocabulary: Asset -> Equipment, Baseline -> Reference measurement, Diagnose -> Take reading,
   Diagnosis -> Measurement document, History -> Measurement history, Advice -> Notification
   proposal, Learn -> Model training, Network -> Federated network.
2. **"Different approaches" = champion/challenger.** Every node trains the champion variant.
   Experimental nodes additionally train one challenger variant (different architecture or training
   recipe). Challengers are scored on held-out data across the network at every sync; one that beats
   the champion by >= 3 points for 2 consecutive rounds is promoted network-wide automatically.
3. **"Add more phones"** = install the APK, open Federated network, Discover, Connect. The owner
   assigns the new node a challenger automatically. Ownership can move: any node can create a group
   and continue from its last replicated network state.
4. **"Visualise everything, on the phone"** = a Federated network dashboard screen (KPI tiles,
   variant leaderboard with sparklines, node registry, activity log, local confusion matrix),
   landscape-capable so it also fills the external monitor (Office Kit).

## Design Read and dials (taste-skill §0/§1; §13 says native product UI uses the real system)

Reading this as: industrial condition-monitoring product UI for maintenance technicians and
hackathon judges, with an SAP Fiori Horizon Evening enterprise language, leaning toward Material 3
Compose themed with Fiori tokens and semantic status colours.

`DESIGN_VARIANCE 3` (predictable grid), `MOTION_INTENSITY 3` (state transitions; one motivated
draw-in on sparklines, fires once; everything honours reduced motion via
`LocalAccessibilityManager`/`Settings.Global.ANIMATOR_DURATION_SCALE` == 0 -> no animation),
`VISUAL_DENSITY 7` (tabular numbers, hairlines over cards, KPI tiles breathe).

Anti-tells that transfer: no em-dashes anywhere in UI strings (use hyphen or comma); no decorative
status dots (dots only for real state, one per section); no neon glows; off-black not #000; one
accent; one radius system; empty/loading/error states on every list; every animation motivated.

### Fiori Horizon Evening tokens (values aligned to SAP Horizon dark where known; these are the
### app's design tokens, not a licence claim)

```
Background          #12171C     Surface            #1D232A     Surface elevated  #29323C
Hairline / divider  #3A4552     Text primary       #EAECEE     Text secondary    #8396A8
Text disabled       #5B6B7C     Brand / Informative #4DB1FF    Positive          #5DC122
Critical (warning)  #FF9A2E     Negative           #FF5361     Neutral           #8396A8
Tint alpha for status label backgrounds: 0.16 of the status colour on Surface.
Radius: 8 dp for cards, tiles, buttons, inputs; chips pill. Nothing else.
Type: Material 3 scale with the system font (SAP "72" is not bundled); all numerals
FontFeatureSettings "tnum". KPI value: headlineMedium; labels: labelMedium uppercase off (no
tracking games).
```

MachineStatus mapping: HEALTHY -> Positive, WARNING -> Critical, CRITICAL -> Negative, unknown ->
Neutral. Backend/engine chips -> Informative.

## Model spec v2 (binding for B1 Python and B2 Kotlin)

All variants share the v1 input (257-d baseline-relative feature), labels 0/1/2, the four signature
keys/shapes, fixed batch 8, SGD lr 0.05, seed 7 init, float32 little-endian weights, layout = per
layer `W (row-major) then b`, concatenated in layer order.

| id | asset file | layers | weightCount | recipe (Kotlin-side, mirrored in Python sim) |
|---|---|---|---|---|
| `base` | `models/fl_head_base.tflite` | [257,64,3] | 16707 | epochs 10, no noise, natural class mix |
| `small` | `models/fl_head_small.tflite` | [257,32,3] | 8355 | epochs 10 |
| `deep` | `models/fl_head_deep.tflite` | [257,64,32,3] | 18691 | epochs 10 |
| `noise` | `models/fl_head_base.tflite` (same graph, separate interpreter = separate weights) | [257,64,3] | 16707 | epochs 10, Gaussian input noise sigma 0.15 added to x before each train step (training only) |
| `balanced` | `models/fl_head_base.tflite` (separate interpreter) | [257,64,3] | 16707 | epochs 10, each batch samples uniformly per class (with replacement) |

Champion at install: `base`. Challengers: `small, deep, noise, balanced`. `fl_head.tflite` (v1) is
removed; B1 exports the three files above. A variant's train signature is identical to v1's; the
recipes live outside the graph so no new ops are needed.

Held-out split (Kotlin, per node, deterministic): `isValidation(id) = ((id.hashCode() and
0x7fffffff) % 4 == 0)`; 25% validation. `evaluate` returns held-out accuracy; nodes with fewer than
4 validation samples report `valAcc = -1` (excluded from network scoring). Train accuracy is still
computed and shown, labelled as such.

## Network protocol v2 (binding for B3; B2 provides the state types)

Version byte = 2. Framing unchanged from v1 (`"JGFL"`, u8 version, u8 type, u32 jsonLen + JSON
header, u32 floatCount + floats LE). Types: `HELLO=1, WEIGHTS=2, MERGED=3, NETWORK=5, DONE=6`.

Client session: `HELLO` (header: node card) -> one `WEIGHTS` per held variant (header carries
`variantId, round, nTrain, nVal, trainAcc, valAcc`) -> `DONE`. Owner reply: one `MERGED` per variant
the client holds or is newly assigned (header carries `variantId, round`) -> `NETWORK` (JSON only:
the full `NetworkState`) -> `DONE`.

Owner merge, per variant: FedAvg weighted by `nTrain` over contributors reporting that variant this
window plus the owner's own copy; `round[v] = max(rounds seen for v) + 1`. Owner then updates the
`NetworkState`: node cards (lastSeen, metrics), leaderboard `netAcc[v]` = mean of reported `valAcc`
weighted by `nVal` (only `valAcc >= 0`; eligible when total `nVal >= 8`), history (last 8 rounds),
`wins[v]` = consecutive eligible rounds with `netAcc[v] >= netAcc[champion] + 0.03`, promotion when
`wins[v] >= 2` -> `championId = v`, event `PROMOTE`, all `wins` reset. Assignment: any node card
without a challenger (and mode EXPERIMENTAL) gets the least-populated challenger among the
non-champion variants; event `ASSIGN`. Events keep the newest 50.

Every node applies the accept-guard per variant (v1 rule), adopts `championId` (switches the
classifier), adopts its assignment unless pinned, persists `NetworkState` to `fl/network.json`.
A node that later becomes owner seeds its `NetworkState` from that file.

## Kotlin API contract v2

### package `com.jugaad.agent.fl` (B2 builds; B3/B5 consume)

```kotlin
data class VariantSpec(val id: String, val asset: String, val layers: IntArray, val weightCount: Int,
                       val epochs: Int, val noiseSigma: Float, val balanced: Boolean, val label: String, val description: String)
object FlVariants { val ALL: List<VariantSpec>; val CHAMPION_DEFAULT = "base"; fun byId(id: String): VariantSpec; val challengers: List<VariantSpec> }

// unchanged from v1: FlConstants (INPUT_DIM, N_CLASSES, TRAIN_BATCH, LR, IMU_SCALE), FeatureDelta, WeightsCodec, FedAvg, SampleSource, FlSample
class SampleStore(dir: File) { /* v1 API + */ val revision: StateFlow<Int> /* bumps on every label()/addPending() */; fun labelledTrain(): List<FlSample>; fun labelledVal(): List<FlSample> }

class FlModel(modelFile: File, val spec: VariantSpec) : AutoCloseable { /* v1 API; weight length checks use spec.weightCount */ }

data class VariantMetrics(val variantId: String, val round: Int, val nTrain: Int, val nVal: Int, val trainAcc: Float, val valAcc: Float, val lastLoss: Float, val lastTrainedMs: Long)

class VariantTrainer(val spec: VariantSpec, model: FlModel, store: SampleStore, dir: File) {
  val metrics: StateFlow<VariantMetrics>
  suspend fun train(): VariantMetrics            // applies the spec's recipe; saves fl/weights_<id>.bin + fl/metrics_<id>.json
  fun evaluateVal(w: FloatArray? = null): Float  // held-out accuracy, -1 when nVal < 4
  fun evaluateTrain(w: FloatArray? = null): Float
  fun confusionVal(): Array<IntArray>            // 3x3 on held-out with current weights
  fun currentWeights(): FloatArray
  fun applyMerged(w: FloatArray, newRound: Int)
  fun infer(x: FloatArray): FloatArray
}

enum class NodeMode { EXPERIMENTAL, STABLE }
data class NodeConfig(val deviceId: String, val name: String, val mode: NodeMode, val pinnedChallenger: String?, val autoTrain: Boolean, val autoSync: Boolean)
data class NodeCard(val deviceId: String, val name: String, val mode: NodeMode, val challenger: String?, val isOwner: Boolean,
                    val champRound: Int, val champValAcc: Float, val challValAcc: Float, val nTrain: Int, val nVal: Int, val lastSeenMs: Long)
data class VariantStanding(val variantId: String, val netAcc: Float, val nVal: Int, val nodes: Int, val history: List<Float>, val wins: Int, val round: Int)
enum class EventType { JOIN, SYNC, PROMOTE, TRAIN, ASSIGN, INFO }
data class NetworkEvent(val ts: Long, val type: EventType, val text: String)
data class NetworkState(val championId: String, val nodes: List<NodeCard>, val standings: Map<String, VariantStanding>,
                        val assignments: Map<String, String>, val events: List<NetworkEvent>, val updatedMs: Long)
// all of the above @Serializable; NetworkState has companion fun empty(championId = "base")

object Promotion {   // pure, unit-tested; used by the owner in p2p
  data class Report(val deviceId: String, val variantId: String, val nVal: Int, val valAcc: Float)
  fun update(state: NetworkState, reports: List<Report>, roundByVariant: Map<String, Int>, nowMs: Long): NetworkState  // standings, wins, promotion, PROMOTE event
  fun assign(state: NetworkState, node: NodeCard): NetworkState  // least-populated challenger, ASSIGN event
}

class FlRuntime(val store: SampleStore, val trainers: Map<String, VariantTrainer>, val config: StateFlow<NodeConfig>,
                val network: StateFlow<NetworkState>, dir: File) {
  val championId: StateFlow<String>
  val challengerId: StateFlow<String?>         // from config.pinnedChallenger, else network.assignments[deviceId]
  fun heldVariantIds(): List<String>           // champion + challenger (if EXPERIMENTAL)
  fun trainer(id: String): VariantTrainer
  suspend fun trainHeld(): List<VariantMetrics>
  fun updateConfig(f: (NodeConfig) -> NodeConfig)
  fun applyNetwork(state: NetworkState)        // persist, switch champion, adopt assignment
  fun localNodeCard(isOwner: Boolean): NodeCard
  fun addEvent(type: EventType, text: String)  // local-only events (TRAIN etc.), merged into state.events
}

class AutoTrainer(runtime: FlRuntime, scope: CoroutineScope) { fun start(); fun stop() }  // observes store.revision, debounce 8 s, trainHeld() when config.autoTrain, then emits TRAIN event; if autoSync and a client group exists, requests SyncBus.requestSync
class LiteRtFaultClassifier(runtime: FlRuntime) : FaultClassifier   // delegates to trainer(championId).infer
```

`ServiceLocator.flRuntime: StateFlow<FlRuntime?>` unchanged. `ModelInstaller.Installed` gains
`flHeads: Map<String, File>` keyed by asset file name.

### package `com.jugaad.agent.p2p` (B3 builds; B5 consumes)

```kotlin
// unchanged: WifiDirectManager, SyncRole, SyncScheduler, SyncWorker (now uses the new coordinator), FlSyncService
data class SyncResult(val role: SyncRole, val peers: Int, val variants: List<String>, val roundsBefore: Map<String, Int>, val roundsAfter: Map<String, Int>,
                      val accepted: Map<String, Boolean>, val valBefore: Map<String, Float>, val valAfter: Map<String, Float>, val promoted: String?, val message: String)
object SyncProtocol { const val PORT = 8988; const val VERSION = 2; /* types above; Header @Serializable with nullable fields: deviceId, name, mode, challenger, variantId?, round, nTrain, nVal, trainAcc, valAcc; NETWORK carries NetworkState JSON in a `network` field */ }
interface FlPeer { val config: NodeConfig; val network: NetworkState; fun heldVariantIds(): List<String>; fun metrics(id: String): VariantMetrics; fun weights(id: String): FloatArray;
                   fun evaluateVal(id: String, w: FloatArray?): Float; fun applyMerged(id: String, w: FloatArray, round: Int); fun applyNetwork(s: NetworkState); fun hasVariant(id: String): Boolean }
class RuntimePeer(runtime: FlRuntime) : FlPeer
class FedAvgCoordinator(peer: FlPeer) { suspend fun runAsClient(ownerAddress: String): SyncResult; suspend fun serveOnce(server: ServerSocket, windowMs: Long = 5000): SyncResult }
object SyncBus { val last: MutableStateFlow<SyncResult?>; val serving: MutableStateFlow<Boolean>; val requestSync: MutableSharedFlow<Unit> }
```

### package `com.jugaad.agent.ui.common.fiori` (B4 builds; B5 consumes)

```kotlin
enum class Semantic { POSITIVE, CRITICAL, NEGATIVE, INFORMATIVE, NEUTRAL }
fun MachineStatus.semantic(): Semantic
@Composable fun FioriKpiTile(label: String, value: String, unit: String? = null, delta: String? = null, deltaSemantic: Semantic = NEUTRAL, modifier: Modifier = Modifier)
@Composable fun FioriStatusLabel(text: String, semantic: Semantic, modifier: Modifier = Modifier)
@Composable fun FioriObjectCell(title: String, subtitle: String? = null, status: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier)
@Composable fun FioriSectionHeader(title: String, action: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier)
@Composable fun FioriKeyValueRow(key: String, value: String, valueSemantic: Semantic? = null)
@Composable fun FioriEmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null)
@Composable fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, semantic: Semantic = INFORMATIVE, animateOnce: Boolean = true)  // Canvas polyline, min/max autoscale, draw-in once (0.6 s, FastOutSlowIn), static when animations disabled
@Composable fun HorizontalMeter(fraction: Float, semantic: Semantic, modifier: Modifier = Modifier)   // 4 dp bar on hairline track
@Composable fun ConfusionGrid(matrix: Array<IntArray>, labels: List<String>, modifier: Modifier = Modifier)  // 3x3, cell tint by row-normalised value, tabular numbers
object FioriColors { val Background, Surface, SurfaceElevated, Hairline, TextPrimary, TextSecondary, TextDisabled, Brand, Positive, Critical, Negative, Neutral: Color; fun of(s: Semantic): Color }
```

`JugaadTheme` maps these into Material 3 `darkColorScheme` (background/surface/surfaceVariant/
outline/primary/onSurface/onSurfaceVariant/error) with `Shapes(8 dp everywhere)` and tabular
numerals in the `Typography` bodies that carry numbers. Existing shared widgets (`StatusPill`,
`BackendIndicator`, `Widgets.kt` SectionCard/MetricRow) are re-skinned onto these tokens so all
eight legacy screens pick up the language without being rewritten.

### Screens (B5 builds)

Route `Routes.NETWORK = "network"` replaces the v1 `learn` entry. One entry from the equipment list
top bar (hub icon, "Federated network"). Screen has two tabs: **Network** and **Training** (the v1
Learn content, re-skinned onto Fiori components). `BoxWithConstraints`: width >= 720 dp -> two
columns.

Network tab: KPI row (Champion held-out accuracy, Best challenger with delta vs champion, Nodes
online, Round); Leaderboard section: one `FioriObjectCell` per variant (label, architecture +
recipe, `Sparkline(history)`, status label Champion / Candidate (wins 1/2) / Trailing / No data);
Nodes section: one cell per `NodeCard` (name, mode + challenger, val acc, round, last seen relative,
status label Owner / Client / Stale > 30 min); Activity: last 10 events; Local: confusion grid of
the champion on this node's held-out set. Empty states when the network has one node or no labels.

Training tab: node identity (name, mode toggle Experimental/Stable, challenger picker: Auto or one
of the challengers), dataset counts (train/val per class, pending), per-held-variant metrics, Train
now, Auto-train switch, peers/group/sync controls from v1, last sync card, Auto-sync switch.

Manifest: remove `android:screenOrientation="portrait"` from MainActivity (B5 owns this one line).

## File ownership

| Agent | Owns |
|---|---|
| B1 | `ml/fl/**`, `app/src/main/assets/models/*.tflite` (removes v1 `fl_head.tflite`) |
| B2 | `app/src/main/java/com/jugaad/agent/fl/**` (rewrite allowed), `app/src/test/.../fl/**`, `ml/ModelInstaller.kt`, `di/ServiceLocator.kt`, `domain/usecase/DiagnoseUseCase.kt` (hook only) |
| B3 | `app/src/main/java/com/jugaad/agent/p2p/**`, `app/src/test/.../p2p/**` |
| B4 | `ui/theme/**`, `ui/common/**` (incl. new `ui/common/fiori/`), vocabulary/labels in `ui/assets`, `ui/assetdetail`, `ui/baseline`, `ui/checklist`, `ui/createasset`, `ui/diagnose`, `ui/history`, `ui/result`, `MainActivity.kt` strings, `res/values/strings.xml` |
| B5 | `ui/learn/**`, `ui/network/**` (new), `ui/nav/**`, `AndroidManifest.xml` (orientation line only) |

## Result (verified 2026-09-12)

- Build green; 44/44 JVM tests (11 suites). Both phones: `LiteRT heads loaded: base, small, deep,
  noise, balanced`, no crashes.
- Two-phone device test with 48 non-linear synthetic labelled samples per phone (A 24/14/10,
  B 10/14/24), B pinned to `deep`, A auto-assigned `small` by the owner: four sync rounds;
  round 2 both challengers "Candidate 1/2"; **round 3: `deep` promoted** ("deep promoted to
  champion (netAcc=0.833, was base)" on both phones, sync log `promoted=deep`); round 4 champion
  87.5%. Accept-guard observed rejecting a merged `base` on B (75.0% -> 58.3%). Landscape
  two-column layout confirmed. Screenshots in `tools/devtest2/`.
- Fixed after the test: `WifiDirectManager.start()` now requests connection info and peers so a
  group formed before the process started is reflected (was: `createGroup` BUSY, no Owner UI);
  KPI tiles laid out 2x2 (labels wrapped mid-word four-across); "No data" rows show `--`; variant
  subtitle no longer repeats the architecture.

## Verification

- B1: `python ml/fl/network_sim.py` prints per-round standings for 3 nodes with assigned
  challengers, shows the promotion rule firing (or honestly not firing) on synthetic data; pytest
  parametrised over the three exported files (layout pin, round trip, train lowers loss).
- B2/B3: JVM tests for `Promotion.update/assign`, held-out split, recipe batching (balanced,
  noise), protocol v2 round trip with multi-variant sessions over loopback, assignment on join.
- Integration: `assembleDebug` green, all unit tests green, install on both phones, logcat shows
  `LiteRT heads loaded: base, small, deep` and, after a two-phone sync, `fl sync: … variants=[base,
  small] …` plus a `PROMOTE` event once a challenger wins twice (device test agent drives it with
  synthetic samples engineered so `deep` or `noise` wins).
- Design: every screen passes the transferred pre-flight items (no em-dashes, one accent, 8 dp
  everywhere, semantic colours only through `Semantic`, empty/loading/error states present,
  landscape layout of the Network screen verified by screenshot on a phone in landscape).
