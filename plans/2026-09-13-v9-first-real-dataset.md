# v9: the first real labelled dataset (2026-09-13)

## The constraint being attacked

The federated learning has never trained on anything. Every variant on every phone has read
`nTrain: 0, nVal: 0, valAcc: -1.0, lastTrainedMs: 0` for the whole life of the project, so the
champion/challenger machinery, the accept guard and the promotion rules have never been exercised
on real data. The first labelled capture session (phone B, 2026-09-12 late evening) produced 16
samples. This plan states why those 16 cannot train a classifier, and what a usable set looks like.

## What was captured (verified on phone B, `files/fl/samples.jsonl`, 16 records, 89719 bytes)

| Equipment | id | benchTest | samples | labels | anomaly scores |
|---|---|---|---|---|---|
| iQOO coffee | `8aaa8003` | false | 7 | 2 x class 0, 5 unlabelled | 1.82, 3.37, 1.43, 0.81, 3.65, 0.93, 47.26 |
| garv iphone | `550451b5` | false | 5 | 1 x class 0, 4 unlabelled | 2.65, 0.36, 2.43, 1.74, 2.81 |
| table | `384eec39` | false | 4 | 2 x class 0, 2 unlabelled | 0.54, 0.68, 1.54, 2.90 |

Feature vectors are well formed: `x` is 260-d, `abs` 256-d, `absSensors` 4-d, matching
`features.schemaVersion`. All three assets have a `baseline.json`; none has a `calibration.json`.
All 16 have `source: PENDING` and `origin: null`.

## Why these 16 cannot classify anything

1. **One class only.** All 5 labelled samples are label `0`, which is `FaultClass.HEALTHY`
   (`domain/model/FaultClass.kt:5`). There are zero examples of `ROTOR_IMBALANCE` (1) and zero of
   `AIRFLOW_OBSTRUCTION` (2). A classifier fitted to a single class learns no decision boundary; it
   predicts HEALTHY for everything and scores 100 percent on its own training set, which is why a
   green accuracy number here would be actively misleading.
2. **Below the app's own gates.** `acceptGuard.minTrain` is 5 and `training.minVal` is 4, and
   `VariantTrainer.valAcc` returns -1 whenever the held-out split has fewer than `minVal` samples
   (`fl/VariantTrainer.kt:138-141`). With 5 labelled samples the split cannot fill both sides, so
   held-out accuracy stays -1, early stopping never engages (`VariantTrainer.kt:69`), and
   `promotion.minNVal` of 8 means no champion/challenger promotion can ever fire.
3. **Nine of the 16 samples are inadmissible under the project's hard rule.** `table` and
   `garv iphone` are both `benchTest: false`, so their readings are being admitted as training
   data. The binding rule in `CLAUDE.md` and `CONTEXT.md` is "never train, share or calibrate on
   readings taken with the phones lying on a table or from synthetic device-test samples". A phone
   resting on a table, and a second phone used as the subject, are precisely those cases. Three of
   the five labels come from these two assets, so only 2 admissible labels remain.
4. **One reading is almost certainly spoiled.** `iQOO coffee` has an anomaly score of 47.26 where
   its six siblings sit between 0.81 and 3.65, a 13x outlier consistent with the phone being moved
   or knocked during the 3-second capture.

A fifth issue to settle while collecting: the labelled HEALTHY samples span 0.54 to 3.37, which
crosses `t1 = 2.0` and runs most of the way to `t2 = 4.0`. Either the thresholds are wrong for
these machines or the labels are inconsistent. No asset has been calibrated, so the thresholds are
still the untuned defaults.

## What a usable first dataset looks like

Targets, chosen so every gate in the app can actually fire rather than being the bare minimum:

| Requirement | Target | Why this number |
|---|---|---|
| Distinct classes | at least 2, preferably all 3 | one class cannot define a boundary |
| Labelled samples per class | 12 or more | leaves 8 or more for the held-out split after a train/val cut, clearing `promotion.minNVal` of 8 |
| Labelled samples total | 36 or more for 3 classes | clears `acceptGuard.minTrain` 5 and `training.minVal` 4 with room for the split |
| Distinct machines per class | 2 or more | a single machine lets the model learn the machine instead of the fault |
| Readings per machine while healthy, before any fault | 6 or more | the reference measurement and the median/MAD calibration both need a healthy population |
| Bench-flagged equipment in the training set | zero | hard rule |

## Equipment that qualifies

Real rotating machinery, running, with the phone pressed flat against the housing or frame:
a coffee or vending machine while its pump or grinder runs, a desk or exhaust fan, a water pump, a
washing machine on spin, an extractor hood. Not a table, not another phone, not a phone lying next
to a machine.

Inducing the two fault classes safely, which is also how the catalogue describes them:

- `ROTOR_IMBALANCE`: add a small mass off-centre to a fan blade, a strip of tape is enough, and
  capture while it runs. Remove it afterwards.
- `AIRFLOW_OBSTRUCTION`: partially block a fan's intake or a hood's filter with card, leaving the
  motor loaded but not stalled. This class has no public labelled data, which is exactly why it has
  to be learnt from technician labels on device.

## Steps

1. Fix the NaN merge guards first (`plans/2026-09-12-v8-nan-merge-guard.md`). Until `FedAvg.kt:14`
   stops dividing by a zero sample count, any sync between phones that have not yet trained turns
   every merged variant into NaN, and a NaN champion crashes the app on the next reading. Verified
   twice on hardware, including once within two minutes of a full wipe.
2. Do not let any phone serve as owner until step 1 lands and real labels exist. The fleet is
   currently clean and nobody is advertising, which is the safe state to collect in.
3. Re-flag `table` and `garv iphone` as bench equipment, or delete them. Their 9 samples must leave
   the training set either way.
4. Delete the 47.26 outlier reading on `iQOO coffee`.
5. For each machine: create it as non-bench equipment with its real machine type from the
   catalogue, capture the reference measurement while it runs healthy, take 6 or more healthy
   readings, label each one HEALTHY on the Result screen, then let the robust calibration set `t1`
   and `t2` from that healthy population before collecting any fault readings.
6. Induce each fault, capture 12 or more readings per class across at least 2 machines, and label
   each one as it is taken. Labelling is what moves a sample from `source: PENDING` into the
   training set.
7. Only then bring a second phone in as owner and let a merge run, and check that `nTrain` and
   `nVal` become non-zero and `valAcc` leaves -1 before believing any round counter.
8. Pull with `ml/fl/field_ingest.py --for-training`, which excludes bench equipment, and keep every
   other pull named `*.DO-NOT-TRAIN.jsonl`.

## Failure modes and what catches each

| Failure mode | What catches it |
|---|---|
| A round counter climbs while nothing has trained | Read `metrics_<variant>.json` for `nTrain`, `nVal`, `lastTrainedMs`, never the Network tab's round number |
| Table or device-test readings reach training | `benchTest: true` on every test asset, and a scan of `samples.jsonl` for the asset ids before ingest |
| A model learns the machine rather than the fault | 2 or more machines per class, and a held-out split that holds out a whole machine where possible |
| Labels disagree with the thresholds | Calibrate from the healthy population before collecting faults, and check `calibration.json` exists |
| A spoiled capture skews calibration | Review the anomaly score of every reading; investigate anything more than a few times its siblings |

## Delegate versus build

| Item | Decision |
|---|---|
| Inspect the captured samples and labels | Main session, done, evidence above |
| Confirm the training gates and the held-out rule from source | Main session, done, file:line above |
| The NaN merge guards and their unit tests | Delegate to Sonnet, contract already written in the v8 plan |
| Capturing and labelling the readings | Founder, on real machines |
| Verifying the first real training round on hardware | Main session |

## Result

Pending.
