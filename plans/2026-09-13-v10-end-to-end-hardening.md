# v10: end to end hardening, correctness and idiot-proofing (2026-09-13)

## The constraint being attacked

A full sweep of the app, the training pipeline and the UI, against live phones carrying real
coffee-machine data. No phone data is purged in this plan: the coffee readings, the labels and the
equipment stay exactly as captured. Every change here is code only.

Three read-only audits ran in parallel (one Haiku search, two Sonnet analyses) alongside an
on-device walk of every screen on phone A.

## Verified on the phones, before any change

Fleet: A `I2501-f0c7` client, 8 equipment, 8 samples, 3 labelled; B `I2501-a783` client, 3
equipment, 7 samples, 2 labelled; C `I2501-fd22` owner, 1 equipment, 3 samples, 0 labelled. All
three on build `9a3f4e9f866be300`, no `FATAL EXCEPTION`, all weights finite, no forbidden samples.
Full tar backups of all three taken first.

## Defects found

### D1, the node registry counts one phone many times (user visible)

`files/fl/network.json` on A and on C each hold **8 node entries with only 3 distinct
`deviceId`s**; `f0c78f35` (phone A) appears **6 times**, all with the same `lastSeenMs`. The
Devices tab therefore reads "8 active devices" and the Performance tab "Nodes online 8" while
three phones sit on the desk.

Cause, `p2p/FedAvgCoordinator.kt:247-259`: `sessions` is a plain `mutableListOf<ClientSession>()`
filled by `server.accept()` in a loop for the whole merge window, with no de-duplication by
`deviceId`. A phone that reconnects inside one window (an auto-join retry after a timeout is
enough) is recorded once per connection. The owner-side dedup at `:371` removes prior entries but
then appends one card per session, so the duplicates come straight back.

### D2, the same phone's weights are averaged several times (correctness, not cosmetic)

The same undeduplicated `sessions` list drives the FedAvg contributions at
`p2p/FedAvgCoordinator.kt:286-292`:

```kotlin
for (s in sessions) {
    val w = s.weights[v]
    val h = s.variants[v]
    if (w != null && h != null) {
        contributions.add(w to (h.nTrain ?: 0))
```

One phone connecting six times contributes its weights six times and its `nTrain` six times to
`totalN`, so it carries six times its proper share of the federated average. The round counter and
the "N node(s) merged" event text (`:412`, `:487`) are inflated by the same factor.

### D3, the accept guard is inert exactly when it is needed

`fl/AcceptGuard.kt:13-16`:

```kotlin
return nTrain < guard.minTrain || after >= before - guard.maxDrop.toFloat()
```

Every variant currently reports `valAcc = -1` (the "no held-out data" sentinel) because `nVal = 2`
is under `training.minVal = 4`. With `before = after = -1f` the comparison is
`-1 >= -1.1`, which is true, so every merge is accepted. The guard silently passes the sentinel
rather than recognising that there is no evidence either way.

### D4, nothing detects degenerate single-class training

Every label in the fleet is class 0. `fl/VariantTrainer.kt:246-260` counts `predicted == s.label`
with no check on label diversity, so `trainAcc` reads 1.0 for a model that has only learned to
answer "class 0" always. No guard exists in `VariantTrainer`, `EarlyStopping`, `RecipeBatching` or
`AcceptGuard`. The Sync and Performance tabs then present that 1.0 as if it meant something.

### D5, the train/validation split is not class aware

`fl/Strategy.kt:39-42` splits on `SampleStore.isValidation(id)`, which is
`(id.hashCode() and 0x7fffffff) % 4 == 0` (`fl/SampleStore.kt:184`). Deterministic per id, but a
hash bucket, not a stratified split: a given set of eight ids can land 8/0, and validation can
hold zero examples of a class that training has.

### D6, three destructive actions have no confirmation

- `ui/assetdetail/AssetDetailScreen.kt:232-234` "Delete equipment" deletes the asset and all its
  history on a single tap.
- `ui/network/GroupSettingsScreen.kt:93` "Leave group" tears the group down on a single tap.
- `ui/network/GroupSettingsScreen.kt:172-176` "Reset device overrides" wipes local config on a
  single tap.

"Promote this node to owner" in the same screen already uses an `AlertDialog`, so the pattern
exists and these three simply do not use it.

### D7, the navigation graph force-unwraps its arguments

`ui/nav/JugaadNavGraph.kt` lines 62, 78, 90, 106, 125, 126, 143: `entry.arguments!!.getString("assetId")!!`,
seven times. A dropped argument bundle on process restoration crashes to a black screen.

### D8, the equipment list status chip freezes

`ui/assets/AssetListViewModel.kt:28-40` fills `_lastStatus` once per asset id and never refetches,
so a new reading does not update the chip on the list until the process restarts.

### D9, a zero confidence is presented as a diagnosis

`ui/result/ResultScreen.kt:124-126` renders `"${it.label} (${...faultConfidence * 100}%)"` whenever
a fault class exists. `DiagnoseUseCase` defaults `faultConfidence` to `0f`, so the screen can read
"Rotor Imbalance (0.0%)", which a technician reads as a finding rather than as no evidence.

### D10, smaller items

`ui/common/fiori/FioriComponents.kt:144-158` `FioriKeyValueRow` gives neither side a weight,
`maxLines` or `overflow`, and is used for event sentences in `GroupSettingsScreen.kt:243`.
`ui/result/ResultViewModel.kt:78-99` swallows a failed share with only a log. `FioriKpiTile` and
`Sparkline` are dead code.

## Not defects, checked and sound

Champion promotion refuses to act on missing data: `fl/Promotion.kt:36,57,73` filters on
`valAcc >= 0f && nVal > 0`, so a `-1` variant is never eligible and two `-1` variants cannot beat
each other. `fl/SharedPool.kt:74-105` dedupes by sample id and enforces both
`sharing.maxPoolSamples` and `maxPerOrigin`, so the pool cannot grow without bound or admit the
same sample twice. Config keys are 1:1 with `AppConfig.kt`, Kotlin and Python agree on 260-d input
and 3 classes, and every variant's `weightCount` matches its baked head.

## Failure modes and what catches each

| Failure mode | What catches it |
|---|---|
| A phone still counted several times after the fix | Unit test merging a session list containing the same deviceId twice, asserting one contribution and one node card |
| Dedup drops a genuinely distinct phone | Unit test with two distinct deviceIds asserting both survive |
| Accept guard now rejects every merge and learning stalls | Unit test for the sentinel case and for the ordinary case, plus a real merge on hardware afterwards |
| A confirmation dialog blocks a flow the demo needs | Walk the three actions on the phone and confirm both the cancel and the confirm paths |
| Single-class detection fires on a legitimate two-class set | Unit test with two classes asserting training proceeds normally |

## Delegate versus build

| Item | Decision |
|---|---|
| UI robustness audit, training audit, dead-code sweep | Delegated, two Sonnet and one Haiku, complete |
| On-device walk of every screen | Main session, complete |
| D1 to D5, the federated and training fixes | Delegate to Sonnet, exclusive ownership of the fl and p2p files |
| D6 to D10, the UI hardening | Delegate to Sonnet, exclusive ownership of the ui files |
| Rebuild, install, and re-verify on the phones without purging data | Main session |

## Result (2026-09-13, 02:4x-02:5x phone clock)

Build green, **188 JVM tests, 0 failures** (baseline 179). APK `0608b2f46172836b` installed on all
three phones with `adb install -r`, so no data was touched: A still holds 8 equipment and 8
samples, B 3 and 7, C 1 and 3, and all 8 baselines and the whole coffee-machine capture survive.
Nothing was purged at any point in this pass, and full tar backups of all three phones were taken
before the first change.

Verified on the phones after installing:

| Defect | Result |
|---|---|
| D1 duplicate node registry | FIXED. Registry went from 8 entries with 3 distinct deviceIds (phone A counted 6 times) to **3 entries, 3 distinct ids** on both A and C. The Devices tab now reads "3 active devices" instead of "8" |
| D2 duplicate FedAvg contributions | FIXED by the same dedup: one session per deviceId now reaches the contributions, so one phone can no longer carry six times its share of the average |
| D3 accept guard inert on the sentinel | FIXED, and the agent found that neither call site was calling `AcceptGuard` at all: both `FedAvgCoordinator.kt:140` and `:334` carried their own hardcoded `nTrain < 5 || after >= before - 0.10f`, so fixing `AcceptGuard.kt` alone would have changed nothing at runtime. Both now call `AcceptGuard.accept` |
| D4 no single-class detection | FIXED: `isSingleClassTrainingSet()` warns instead of silently reporting `trainAcc = 1.0`. Training is not blocked, because a second class is still being collected |
| D5 stratified split | REVERTED on purpose, see below |
| D6 unguarded destructive actions | FIXED. "Delete equipment" now opens a dialog reading "Delete iQOO coffee" / "This removes iQOO coffee and all of its measurement history. This cannot be undone." with Cancel and Delete. Verified on the phone that Cancel leaves all 8 equipment, 8 history records and 8 samples in place. "Leave group" and "Reset device overrides" likewise confirmed |
| D7 nav argument force-unwraps | FIXED, all seven pop back to the equipment list instead of crashing |
| D8 frozen status chip | FIXED, refreshes on a 5 s cadence and through a new `refresh()`, with atomic `update {}` writes replacing the read-modify-write that could drop entries |
| D9 zero confidence read as a diagnosis | FIXED, plus a worse case found on the phone and fixed separately, below |
| D10 key/value row overlap | FIXED, the value is weighted and ellipsises at 3 lines |

### The two defects found by hand on the phone, not by the audits

The Result screen rendered **"CNN fault class: Healthy (100.0%)" directly under a Critical
status**, and the history row read **"score 4.46 . Healthy" beside a Critical chip**. Both came
from presenting `FaultClass.HEALTHY`, which is classifier class 0 and not a fault, as though it
were a finding; a technician reads that as permission to ignore a real alarm. `ResultScreen.kt`
now reads "no specific fault identified" and `HistoryScreen.kt:142` omits the class entirely when
it is HEALTHY. Both verified on device after the install.

### Why D5 was reverted

The stratified split worked, but it made train/validation membership depend on the size of the set
being split: the same sample sits in training when a node splits locally and in validation once the
sets are pooled. `CentroidMathTest.fedAvgOfTwoCentroidsWeightedByNTrainEqualsThePooledMean` caught
it exactly, the FedAvg of two centroids giving the correct 8.4 while the pooled centroid drifted to
10.0 because it had silently dropped a sample. FedAvg exactness under pooling is a load-bearing
property of a federated system and a nice-to-have stratification is not worth breaking it, so
`Strategy.kt` is back to the id-hash split and the D5 tests were removed. The underlying concern
stands and is recorded here: with few labelled samples a class can hash entirely to one side. The
honest fix is more labelled data, not a size-dependent split.

### The guards observed working in the field

```
fl sync: skipping base, no trained samples this round
fl sync: skipping small, no trained samples this round
fl sync: skipping noise, no trained samples this round
fl sync: client variants=[base, small], rounds {base=0, small=0} -> {base=0, small=0}
```

The v8 zero-sample guard refuses the merge and, crucially, the round counters stay at 0 rather than
advancing on an empty merge. No `FATAL EXCEPTION` on any phone, and every weight file finite.

### Still open, and not fixable in code

Every label in the fleet is still class 0. `trainAcc` will keep reading 1.0 and `valAcc` will keep
reading -1 until a second class exists and validation reaches `training.minVal` of 4. Calibration
on `iQOO coffee` shows "3 of 6 healthy readings with full features"; auto-labelling only fires for
a reading scoring at or under `0.5 * t1` (1.0 with the current defaults), which is why 0.92, 0.56
and 0.95 were labelled automatically and 1.04, 1.35, 1.84, 1.92 and 4.46 were not. Two more quiet
readings, or two manual confirmations on the Result screen, will trip calibration.

### Training loop proven end to end with a second class (190 tests)

Real fault data cannot be invented, but the stack that consumes it can be proved. A new
integration test, `app/src/test/java/com/jugaad/agent/fl/TwoClassLearningIntegrationTest.kt`,
drives the genuine training path with a separable two-class synthetic set sized so that both
splits clear the app's own gates:

- `nTrain` and `nVal` are both non-zero and `valAcc` is a real number, **1.0 (8 of 8)**, not the
  -1 sentinel;
- fresh held-out vectors from each cluster classify correctly as HEALTHY and ROTOR_IMBALANCE;
- the single-class detector correctly does NOT fire on the two-class set, and DOES fire on the
  all-HEALTHY set where `trainAcc` is the vacuous 1.0.

Honest limit: this exercises `CentroidStrategy`, which genuinely runs off device. `VariantTrainer`
wraps a real `org.tensorflow.lite.Interpreter` over a `.tflite` asset and cannot load on a plain
JVM, which is why no existing test covers it either. So the LiteRT-backed variants remain proved
only by the on-device round counters and by `ml/fl/fedavg_sim.py` (merged accuracy 0.817, 0.907,
0.950, 0.977, 0.980) and `ml/fl/network_sim.py` (promoted `noise` in round 2), both exit 0.

### D11, an untrained class can win a centroid prediction (found, not fixed)

`fl/CentroidStrategy.kt:55-56` keeps the previous centroid for any class with no samples this
round, and a class never seen keeps its initial all-zero centroid. `predict` at `:131-138` then
scans all `FlConstants.N_CLASSES` and takes the nearest centroid with no notion of whether a class
was ever trained:

```kotlin
for (cls in 0 until FlConstants.N_CLASSES) {
    val d = squaredDistance(x, c, cls)
    if (d < bestDist) { bestDist = d; best = cls }
}
```

The features are baseline-relative deltas, so a healthy machine sits near the origin, which is
exactly where an untrained centroid sits. The model can therefore return a fault class it has
never seen one example of. This showed up empirically while writing the integration test: with the
clusters placed near zero the held-out accuracy fell to 0.875 and the single-class `trainAcc` to
0.667, because low-valued HEALTHY samples were pulled to the stale zero centroid. Moving both
clusters away from the origin removed it.

Live relevance: the fleet has only class 0 samples, so the ROTOR_IMBALANCE and AIRFLOW_OBSTRUCTION
centroids are both all-zero right now. It only reaches a user while `centroid` is champion, and the
champion is `base`, but promotion can select `centroid`.

**FIXED.** Returning a class that has seen zero examples is wrong under any reading, so this did
not need a semantics debate. `CentroidStrategy` now carries a `trainedMask` over `N_CLASSES`;
`predict` skips untrained classes and `classify` gives them `Float.NEGATIVE_INFINITY` logits, so
they hold no probability mass and can never be the argmax. With nothing trained yet `classify`
returns all zeros and `predict` returns -1, which is safe: `predict` is private and only feeds the
confusion matrix and the accuracy count, and the public `infer` path cannot surface an untrained
class at all.

Two details worth keeping. Inferring "trained" from "the centroid is non-zero" was tried first and
rejected: it broke `CentroidMathTest.inferIsSoftmaxOverNegativeScaledSquaredDistance`, where a
class legitimately trains to an exact all-zero mean. So the mask is real state, persisted through
the existing `WeightsCodec` as `trained_<id>.bin` beside the weights, with no new storage
mechanism. For merged peer weights, `applyMerged` marks a class trained when its merged centroid
is non-zero, which is sound because `FedAvg.merge` only writes a non-zero result into a class slot
when some contributor had `nTrain > 0` for it.

194 JVM tests, 0 failures, with `CentroidMathTest` and `TwoClassLearningIntegrationTest` passing
unchanged. APK `22b407f044f01379` installed on all three phones with `install -r`; no crash, and
equipment, history and samples all still in place (A 8/8/8, B 3/16/7, C 1/3/3). The
`trained_<id>.bin` files appear at the next centroid training round; until then every class reads
untrained, which is the safe direction.
