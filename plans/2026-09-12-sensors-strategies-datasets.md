# v3: all sensors, honest training, public-data pretraining, strategy ranking, device budget

Date: 2026-09-12. Builds on v1 (`…-federated-on-device-learning.md`) and v2
(`…-champion-challenger-network.md`). Binding for agents C0-C6.

## Goal (founder, 2026-09-12)

Use every sensor we can, locally; set a baseline and test it; train properly without overfitting;
find and use a public dataset if one fits; rank the strategies (including the four single-device
techniques from the founder's table: EMA-teacher self-distillation, contrastive self-supervised
pretraining, uncertainty-triggered fine-tuning, frozen-backbone centroid adaptation) and test them
on experimental vs non-experimental nodes; use device resources properly; anything else that
improves the product end to end.

## Decisions

1. **Sensors.** Microphone (existing), accelerometer (existing), **gyroscope** and **magnetometer**
   added, all sampled over the same 3 s window on one HandlerThread with sensor batching. Barometer,
   light, proximity, ambient temperature carry no machine-condition signal and are not used (state
   this in the pre-check screen as "not used"). Camera stays as the nameplate photo.
2. **Feature vector grows from 257 to 260** (`INPUT_DIM = 260`), baseline-relative as before:
   ```
   x[0..255]  log-mel per-band mean/std deltas (unchanged)
   x[256]     (accelIndex   - baseline.imuIndexMean)    * 10   accelerometer 5-60 Hz energy fraction (unchanged)
   x[257]     (gyroIndex    - baseline.gyroIndexMean)   * 10   gyroscope |omega| 5-60 Hz energy fraction, same algorithm as accel
   x[258]     (magIndex     - baseline.magIndexMean)    * 10   magnetometer |B| 3-25 Hz energy fraction after linear detrend (phones sample B at 50-100 Hz; line frequency aliases, so the low band carries the rotor-related fluctuation)
   x[259]     (magRms       - baseline.magRmsMean) / max(baseline.magRmsMean, 1.0)   magnetometer AC RMS in microtesla, relative
   ```
   Missing sensor -> index 0 for both baseline and reading -> delta 0. `Baseline` gains
   `gyroIndexMean, magIndexMean, magRmsMean` (JSON defaults 0.0 so v1/v2 baselines still load).
3. **Variant table v3** (weight counts recomputed for 260 inputs; layout unchanged):

   | id | kind | asset | layers | weights | recipe | uses unlabelled |
   |---|---|---|---|---|---|---|
   | `base` | MLP | `fl_head_base.tflite` | [260,64,3] | 16899 | early stop, wd 1e-4 | no |
   | `small` | MLP | `fl_head_small.tflite` | [260,32,3] | 8451 | same | no |
   | `deep` | MLP | `fl_head_deep.tflite` | [260,64,32,3] | 18883 | same | no |
   | `noise` | MLP | base asset, own interpreter | [260,64,3] | 16899 | + Gaussian input noise 0.15 | no |
   | `balanced` | MLP | base asset, own interpreter | [260,64,3] | 16899 | + class-uniform batches | no |
   | `uncertain` | MLP | base asset, own interpreter | [260,64,3] | 16899 | batches drawn with probability proportional to (1 - max prob) + 0.1; auto-train fires only when a new sample with max prob < 0.6 arrives (founder table row 3) | no |
   | `distill` | MLP | base asset, own interpreter | [260,64,3] | 16899 | EMA teacher (alpha 0.99 per epoch) predicts PENDING samples; those with teacher max prob >= 0.9 join training with soft targets (`y` = teacher probs); labelled samples keep one-hot (founder table row 1) | yes |
   | `centroid` | CENTROID | none | - | 3*260 = 780 | per-class mean of x on labelled train samples; predict by softmax(-0.5 * squared distance / d) with d = 260; FedAvg of centroids weighted by nTrain is exact for means (founder table row 4) | no |

   Champion at install stays `base`. Challengers: the other seven. `centroid` trains in
   microseconds and is the efficiency floor.
4. **Training honesty.** The exported `train` signature applies SGD with weight decay:
   `w -= lr * (grad + 1e-4 * w)` (Python re-export). `VariantTrainer.train` runs up to 30 epochs,
   evaluates held-out loss after each epoch when `nVal >= 4`, keeps the best-epoch weights (early
   stopping, patience 5), and records `trainAcc`, `valAcc`, `valLoss`, `bestEpoch`, `gap = trainAcc -
   valAcc`. Gap >= 0.15 is shown as "Overfitting" (Critical) on the phone. With `nVal < 4` it trains a
   fixed 10 epochs and shows "no held-out data".
5. **Contrastive pretraining (founder table row 2) runs offline, not on the phone.** On the public
   dataset, C1 pretrains the first layer with a SimCLR-style objective (two augmented views of the
   same clip: Gaussian noise 0.1, random band dropout 10%, mixup 0.2 in feature space; projection
   head 32-d; NT-Xent, temperature 0.1), then fine-tunes with labels, and compares held-out accuracy
   against random init on a leave-one-machine-out split. Whichever wins ships as the baked-in
   initial weights of the three `.tflite` files (report both numbers). On-device contrastive
   training is out of scope (no encoder to pretrain on the phone; the front end is fixed DSP).
6. **Public dataset.** C0 surveys MAFAULDA (rotor imbalance, misalignment, bearing faults; 3-axis
   accelerometers + microphone, 50 kHz), MIMII (fan/pump/slider/valve normal vs abnormal, 16 kHz,
   fan abnormal = unbalance/voltage/clogging but not labelled per file), DCASE Task 2 sets,
   ToyADMOS, CWRU (vibration only), IDMT-ISA electric engine. Selection rule: real labels that map
   onto `[Healthy, Rotor Imbalance, Airflow Obstruction]`, microphone channel present, permissive
   licence, download <= 3 GB. Expected pick: MAFAULDA `normal` + `imbalance` subsets (Healthy,
   Rotor Imbalance) with MIMII fan as the Airflow candidate only if per-file clogging labels exist;
   otherwise Airflow Obstruction stays synthetic-only and the docs say so.
   Pipeline: resample/convert to 44.1 kHz mono, cut 3 s clips, features via `ml/logmel_reference.py`
   (parity with the phone), per-machine baseline = mean of 5 normal clips of that machine,
   x = clip - baseline (260-d, sensor deltas 0 unless the dataset has an accelerometer channel, in
   which case the accel index is computed with the phone's algorithm), leave-one-machine-out
   evaluation, train the three MLP heads, export, write `ml/data/README.md` with provenance,
   licence, counts, and the exact commands. Downloads live in `ml/data/` (git-ignored).
7. **Strategy ranking and cohorts.** `StrategyRank.score(v) = 100 * (netAcc[v] - netAcc[champion])
   - 0.002 * trainMs[v] + 2 * usesUnlabelled[v]` with `trainMs` the nTrain-weighted mean of reported
   training time in ms; the champion scores 0 by definition. Ranking is shown on the Network tab
   sorted by score with the three terms visible. Cohorts: `Cohorts.compute(state)` returns, for
   EXPERIMENTAL and STABLE node sets, count, mean champion held-out accuracy, mean round; shown as
   two KPI tiles ("Experimental nodes" / "Stable nodes") so the founder can see whether running
   experiments costs the stable path anything (it should not: the champion is shared).
   `Header` gains `trainMs: Long?`; `VariantStanding` gains `trainMs: Float`, `score: Float`,
   `usesUnlabelled: Boolean`.
8. **Device budget.** `TrainBudget`: threads = `min(4, availableProcessors)`; auto-train skipped
   when `PowerManager.currentThermalStatus >= THERMAL_STATUS_MODERATE` or battery < 15% and not
   charging; all training on `Dispatchers.Default`; sensor listeners use `maxReportLatencyUs =
   100_000` batching. Dashboard "Device" panel: SoC, cores, thermal status, battery, memory
   (`ActivityManager.MemoryInfo`), per-variant last train ms, model bytes per variant.
9. **Calibrate thresholds** (closes the v1 handoff's biggest live-demo risk). `FlSample` lines
   gain `score: Double?` (anomaly score at capture). `CalibrateUseCase(assetId)`: healthy scores =
   samples labelled 0, faulty = labelled 1 or 2; if both exist, `T1 = midpoint(max healthy, min
   faulty)`, `T2 = midpoint(T1, max faulty)` clamped so `T2 >= T1 * 1.5`; if only healthy exist,
   `T1 = 1.5 * p90(healthy)`, `T2 = 2 * T1`; needs >= 5 healthy. Writes `asset.thresholds`. Equipment
   detail shows observed ranges and a Calibrate button.
10. **Pre-check screen** lists every sensor with availability and measured rate after a 1 s probe:
    microphone, accelerometer, gyroscope, magnetometer; "not used" note for the rest.

## Kotlin API contract v3 (additive to v2 unless stated)

```kotlin
// sensor
class MotionCapture(context: Context) { data class Reading(val accel: Axes, val gyro: Axes?, val mag: Axes?, val accelRateHz: Double, val gyroRateHz: Double, val magRateHz: Double)
                                       data class Axes(val x: FloatArray, val y: FloatArray, val z: FloatArray); fun isAvailable(type: Int): Boolean; suspend fun capture(seconds: Int): Reading }
// ImuCapture stays as the accel implementation detail inside MotionCapture; CaptureCoordinator returns MotionCapture.Reading
object MagneticIndex { data class Result(val index: Double, val rmsMicroTesla: Double); fun compute(mag: FloatArray, rateHz: Double): Result }
// ml
FeatureExtractor.Analysis += gyroIndex: Double, magIndex: Double, magRms: Double
// domain
Baseline += gyroIndexMean: Double = 0.0, magIndexMean: Double = 0.0, magRmsMean: Double = 0.0
class CalibrateUseCase(assets: AssetRepository, store: SampleStore) { data class Result(val t1: Double, val t2: Double, val healthy: ClosedRange<Double>?, val faulty: ClosedRange<Double>?, val nHealthy: Int, val nFaulty: Int); suspend fun observe(assetId: String): Result?; suspend fun apply(assetId: String): Outcome<Result> }
// fl
FlConstants.INPUT_DIM = 260; FlConstants.SENSOR_DIMS = 4
FeatureDelta.build(feature, imuIndex, gyroIndex, magIndex, magRms, baseline): FloatArray  // 260
enum class VariantKind { MLP, CENTROID }
VariantSpec += kind: VariantKind, usesUnlabelled: Boolean, earlyStop: Boolean, weightDecayNote: String, focusUncertain: Boolean, selfDistill: Boolean
FlSample += score: Double? = null
SampleStore.addPending(id, assetId, x, score: Double?)
VariantMetrics += valLoss: Float, bestEpoch: Int, gap: Float, trainMs: Long
interface Strategy { val spec: VariantSpec; fun infer(x): FloatArray; suspend fun train(): VariantMetrics; fun currentWeights(): FloatArray; fun applyMerged(w, round); fun evaluateVal(w: FloatArray?): Float; fun evaluateTrain(w: FloatArray?): Float; fun confusionVal(): Array<IntArray>; val metrics: StateFlow<VariantMetrics> }
class VariantTrainer(...) : Strategy      // MLP kinds, recipes incl. uncertain + distill, early stopping
class CentroidStrategy(spec, store, dir) : Strategy   // weights = 780 floats [c0(260), c1(260), c2(260)]
FlRuntime.trainer(id): Strategy           // renamed type only; callers unchanged
object TrainBudget { data class Snapshot(val cores: Int, val threads: Int, val thermal: Int, val batteryPct: Int, val charging: Boolean, val availMemMb: Long, val totalMemMb: Long, val allowAutoTrain: Boolean, val reason: String); fun snapshot(context: Context): Snapshot }
object StrategyRank { fun score(netAcc: Float, champAcc: Float, trainMs: Float, usesUnlabelled: Boolean): Float; fun rank(state: NetworkState): List<VariantStanding> /* sorted desc, champion included at 0 */ }
object Cohorts { data class Stat(val nodes: Int, val meanChampValAcc: Float, val meanRound: Float); fun compute(state: NetworkState): Map<NodeMode, Stat> }
AutoTrainer: honours TrainBudget.allowAutoTrain; for `uncertain` only fires when the newest labelled/pending sample's champion max prob < 0.6
// p2p
SyncProtocol.Header += trainMs: Long? = null, usesUnlabelled: Boolean? = null
Promotion.update also fills VariantStanding.trainMs/score/usesUnlabelled via StrategyRank
```

## File ownership

| Agent | Owns |
|---|---|
| C0 | research only, writes `ml/data/DATASETS.md` |
| C1 | `ml/fl/**`, `ml/data/**` (new, git-ignored except `*.md`), `app/src/main/assets/models/*.tflite`, `ml/logmel_reference.py` (read-only use; may add a CLI flag) |
| C2 | `sensor/**`, `ml/FeatureExtractor.kt`, `ml/signal/MagneticIndex.kt` (new), `domain/model/Baseline.kt`, `domain/usecase/CaptureBaselineUseCase.kt`, `domain/usecase/CalibrateUseCase.kt` (new), `domain/usecase/DiagnoseUseCase.kt`, `data/**` (Baseline DTO), `core/Constants.kt`, `fl/FeatureDelta.kt`, `fl/FlConstants.kt`, `fl/FlSample.kt`, `fl/SampleStore.kt` (score field only), tests for these |
| C3 | rest of `fl/**` (variants table, VariantTrainer recipes, CentroidStrategy, Strategy interface, TrainBudget, StrategyRank, Cohorts, AutoTrainer, FlRuntime, metrics types, NetworkState/Promotion additions), `fl` tests, `di/ServiceLocator.kt` |
| C4 | `p2p/**` (Header fields, pass-through), p2p tests |
| C5 | `ui/network/**` (ranking, cohorts, device panel, overfit labels, 7-challenger picker), `ui/checklist/**` (sensor list), `ui/assetdetail/**` (calibrate), `ui/nav/**` if needed |
| C6 | device test only, screenshots under `tools/devtest3/` |

## Verification

- Python: pytest over the three re-exported files (260 inputs, wd step lowers loss and shrinks
  norms), `network_sim.py` still passes with 260-d data, dataset pipeline prints counts per
  machine/label and leave-one-machine-out accuracy for random vs contrastive init.
- Kotlin JVM: MagneticIndex on a synthetic 12 Hz sinusoid; FeatureDelta 260 with missing sensors;
  Calibrate math (both-present and healthy-only branches); early stopping picks the best epoch on a
  fake trainer seam; centroid merge equals weighted mean; StrategyRank ordering; Cohorts means;
  header v3 round trip.
- Device (C6): real sensor path on phone A: pre-check lists mic/accel/gyro/mag with rates; capture
  reference measurement (3 clips); take reading -> Healthy; play a loud tone from phone B next to A
  -> reading -> Warning or Critical; confirm labels; Calibrate; Train now (all variants incl.
  centroid and distill produce `fl train` lines with train/val/gap); sync two rounds; Network tab
  shows ranking with scores, cohort tiles, device panel; landscape screenshot.
