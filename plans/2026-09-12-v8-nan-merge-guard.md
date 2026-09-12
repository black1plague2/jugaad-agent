# v8: stop FedAvg from poisoning every model with NaN (2026-09-12)

## The constraint being attacked

The headline feature, federated self-improvement, currently destroys the models it merges. Every
FL variant that has been through a merge is all-NaN on all three phones, the champion (`base`)
included, and any reading then crashes the app on the main thread. The app's primary action is
broken fleet-wide, and the self-healing layer cannot see it.

## Evidence (collected on hardware, build `25182085859111ae`, see `tools/devtest10/REPORT.md`)

- `weights_base.bin` 16899/16899 floats NaN, `weights_small.bin` 8451/8451 NaN,
  `weights_deep.bin` 18883/18883 NaN, `weights_noise.bin` 16899/16899 NaN on A and C.
- `weights_balanced.bin`, `weights_uncertain.bin`, `weights_distill.bin` are healthy on all three
  phones, max absolute weight 0.8304. Those are exactly the variants that have never been merged.
  Every merged variant is NaN; every unmerged variant is fine.
- On B, `noise` and `deep` were still healthy while `base` and `small` were NaN, matching B's own
  `metrics_deep.json` / `metrics_noise.json` round counters of 0.
- Every variant on every phone: `nTrain: 0, nVal: 0, valAcc: -1.0, lastTrainedMs: 0`. No variant
  has ever trained, because all equipment is bench-flagged and sample capture is blocked at source.
- Owner standings: `netAcc: -1.0, nVal: 0, nodes: 0, wins: 0` for every variant, while round
  counters climb (base 57, deep 52, small 42, noise 29).
- Taking a reading on A: `AudioCapture done samples=132300`, `MotionCapture accel n=1431
  rate=461.2Hz gyro n=1431 rate=461.2Hz mag n=284 rate=92.2Hz`, then
  `FATAL EXCEPTION: main`,
  `kotlinx.serialization.json.internal.JsonEncodingException: Unexpected special floating-point
  value NaN` with `"faultConfidence": NaN` from `DiagnosisDto` (Dtos.kt) inside
  `DiagnosisRepositoryImpl$save`. The process restarted and the measurement was lost.
- Diagnosis records written earlier the same day are intact and finite
  (`faultConfidence` 0.5107919, 0.0, 1.0), so the pipeline worked before the merge count grew.
- The installed `files/models/fl_head_base.tflite` on A is byte-identical (md5
  `25601bf2839ee3a41c935ff37fdd5524`) to the committed `app/src/main/assets/models/fl_head_base.tflite`,
  so the baked heads are clean and only the merged `weights_*.bin` are poisoned.

## Root cause

`fl/FedAvg.kt:11-15`:

```kotlin
val totalN = contributions.sumOf { it.second }
for ((w, n) in contributions) {
    val weight = n.toFloat() / totalN
```

`n` is each contributor's `nTrain`. With no samples anywhere every `n` is 0, so `totalN` is 0 and
`weight` is `0f / 0` which is NaN for every contributor, poisoning every output element.
`p2p/FedAvgCoordinator.kt:299` guards only `contributions.isEmpty()`, never that the contributions
sum to a positive count, so a round in which nobody has trained still merges.

Nothing stops the NaN spreading: there is no finite check after merging, before persisting
(`fl/VariantTrainer.kt` `applyMerged` does `setWeights` then `saveWeightsFile`), after loading,
before sending to a peer, or on receiving a peer's weights (`p2p/WeightsCodec.kt` is a plain
byte/float conversion). `fl/Recovery.kt` `staleWeightFile` compares only file length against each
variant's `weightCount`, so an all-NaN file of the correct length passes as healthy.

Downstream, `LiteRtFaultClassifier` infers through the NaN weights, `confidence = probs[idx]`
becomes NaN, and `DiagnoseUseCase` assigns `faultConfidence = pred?.confidence ?: 0f`, where the
`?:` never fires because NaN is not null. kotlinx.serialization then refuses the non-finite value
and the save throws on the main thread.

## Failure modes and what catches each

| Failure mode | What catches it |
|---|---|
| A merge runs with zero total samples and yields NaN | Skip the merge when `totalN <= 0`; unit test asserting the weights are left untouched and the round does not advance |
| Non-finite weights reach the disk or a peer anyway, by some other arithmetic path | Reject a non-finite merge result before applying it; unit test feeding a NaN contribution |
| A phone already carries poisoned weights from before the fix | `Recovery` quarantines weight files containing non-finite values, so existing phones self-heal to the baked head on next launch; unit test on an all-NaN file of correct length |
| A NaN confidence still reaches the store from any source | Sanitise `faultConfidence` to a finite value at the point it is assigned; the reading must be saved rather than lost |
| The fix silently disables legitimate merging | Verify on hardware that rounds still advance between phones after the fix, and that the simulators still pass |

## Delegate versus build

| Item | Decision |
|---|---|
| Locate the NaN source and the missing guards | Delegated, Sonnet, done, findings quoted above |
| Recon the offline simulators | Delegated, Haiku, done |
| Run the simulators | Delegated, Sonnet, done: `fedavg_sim.py` merged accuracy 0.817, 0.907, 0.950, 0.977, 0.980 and `network_sim.py` promoted `noise` in round 2, both exit 0 |
| Implement the five guards with unit tests | Delegate to Sonnet, exclusive ownership of the listed files |
| Wipe or self-heal the phones, reinstall, verify end to end | Main session, phone state stays here |
| Decide whether a zero-sample round should skip or average uniformly | Main session: skip, because a round with no data carries no information and advancing the round counter would keep fabricating progress |

## Result

Pending.
