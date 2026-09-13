# v16: sensitivity profiles for small and light equipment (2026-09-13)

## The constraint being attacked

The founder wants small, light objects (the example given is a cardboard box such as an iPhone box)
to flag small vibration changes: a narrow healthy band and a wide warning and critical band. Today
every asset gets the same thresholds regardless of what it is.

## How thresholds work today (read from code by a Haiku agent, file:line in its report)

- Every new asset gets `t1 2.0`, `t2 4.0` from `Constants.DEFAULT_T1/T2`; `MachineCatalog.MachineType`
  has no threshold field, so the machine type chosen at creation has no effect.
- Score is `max(acousticScore, sensorScore)` (`AnomalyScorer.kt:131`), where
  `acousticScore = cosineDistance / spread` and `spread = radius.coerceAtLeast(spreadFloor)` with a
  **global** `spreadFloor 0.02` (`AnomalyScorer.kt:82,108`), and each sensor z-score divides by a
  **global** `zFloor 0.02` (`:122`). For a light object whose true healthy spread is below those
  floors, the floor, not the data, sets the score, which is exactly what hides small changes.
- Status is `score <= t1` Healthy, `<= t2` Warning, else Critical (`MachineStatus.kt:17-20`).
- Calibration rewrites `t1 = median + k1*MAD`, `t2 = median + k2*MAD` with global `k1 3`, `k2 6` and
  `MAD_FLOOR 0.05` (`CalibrationMath.kt:140-142`) and persists them (`CalibrateUseCase.kt:64`), so
  any hand-tightened thresholds would be undone at the first calibration. Bench assets are not
  calibrated.
- The threshold slider range is `0.5..8` (`AssetDetailScreen.kt:263-305`).

## Design

1. A per-asset `Sensitivity`: **Standard**, **High** (small or light machines), **Very high** (very
   small or light objects). Each profile is a single scale factor, 1.0, 0.5 and 0.25, applied
   consistently so the profiles stay proportional rather than being four unrelated magic numbers:
   - default thresholds: `t1 = 2.0 * f`, `t2 = 4.0 * f` (Standard 2.0/4.0, High 1.0/2.0,
     Very high 0.5/1.0);
   - `spreadFloor` and `zFloor`: `0.02 * f`, so the floor stops masking a light object's genuinely
     small healthy spread;
   - calibration multipliers `k1 * f`, `k2 * f` and the MAD floor `0.05 * f`, so calibrating keeps
     the asset at its chosen sensitivity instead of snapping back to Standard.
   The factor lives in one place in code and is covered by tests.
2. The machine type drives the default: machine types gain an optional `sensitivity` in
   `machines.json` (absent means Standard), and the catalogue gains a "Small or light object"
   type (box, small device, small enclosure) defaulting to Very high, using the generic fault set.
3. Create equipment shows a Sensitivity selector pre-filled from the chosen machine type, with one
   plain line per option saying what it does, and the user can override it. The equipment detail
   screen shows the sensitivity and lets it be changed; changing it rescales the thresholds.
4. **Existing assets do not change.** An asset JSON without a sensitivity field loads as Standard with
   its stored `t1`/`t2` untouched, so the coffee-machine capture keeps every threshold and history
   record exactly as it is.
5. The threshold slider range extends below 0.5 so a Very high asset (and a calibrated one) is not
   clamped.
6. Trade-off stated in the UI copy, not hidden: higher sensitivity catches smaller changes and also
   raises more warnings from handling noise, so a steady placement matters more.

## Rule this does not relax

A box vibrated on a desk is test equipment. It must be created with the "Bench / test equipment"
flag, which keeps its readings out of training, sharing and calibration. The sensitivity profile
still applies to a bench asset through its creation thresholds and floors, so the demo works.

## Failure modes and what catches each

| Failure mode | What catches it |
|---|---|
| Existing coffee assets silently change thresholds | Load test on an asset JSON with no sensitivity field; on device, read `asset.json` t1/t2 before and after install |
| Calibration snaps a High asset back to Standard numbers | Unit test: CalibrationMath with f 0.5 yields thresholds half of f 1.0 on the same scores |
| Floors still mask a light object | Unit test: AnomalyScorer with spread 0.005 scores higher under Very high than Standard |
| Very high thresholds clamped by the slider or `Thresholds.safe` | Unit test on `safe`; on device, detail screen reads 0.5 / 1.0 |
| Machine type default ignored | Create "Small or light object" on a phone and read the created `asset.json` |

## Delegate versus build

| Item | Decision |
|---|---|
| Threshold path recon | Haiku, done |
| Implementation across model, persistence, catalogue, scorer, calibration and UI, with tests | One Sonnet agent (the change is one coherent thread; no other agent runs, so it may build) |
| Install without data loss and on-device verification | Main session |

## Result (2026-09-13, 10:3x phone clock)

Implemented by one Sonnet agent across 17 files: `domain/model/Sensitivity.kt` (the single factor
table), `Asset.sensitivity`, `Thresholds.forSensitivity`, `AssetDto.sensitivity` defaulting to
STANDARD, `MachineType.sensitivity` plus a new `small_light_object` catalogue type (VERY_HIGH),
the floor scaled at the baseline build (`CaptureBaselineUseCase`, `RefreshBaselineUseCase`), a
per-call scaled `zFloor` in `DiagnoseUseCase` (the shared config is never mutated), scaled k1, k2
and MAD floor in `CalibrateUseCase`, a Sensitivity selector on Create equipment and a
`SensitivityCard` on the detail screen, slider range `0.05..8`. Auto-labelling already used the
per-asset `t1`, so it scales without change.

Build green, **244/244 JVM tests** (232 plus 12 new). Rebuilt independently by the main session,
not taken from the agent's report. Final APK `d10bd57f9c4d8276` on A, B and C via `install -r`,
no `FATAL EXCEPTION`.

Verified on the phones:
- **No existing threshold moved.** `asset.json` for all 14 assets across the three phones was read
  before and after the install and `diff` found them identical, including C's hand-set
  `laptop t1=2.5`.
- The Create equipment screen shows "Sensitivity" with Standard / High / Very high and their
  descriptions (`tools/devtest15/S04_create.xml`).
- Searching machine type "box" lists "Small or light object"; selecting it checks the Very high
  radio (`S08_selected.xml`, RadioButton checked=true). Nothing was created.
- The new type's description originally leaked developer wording ("reuses the generic fault set");
  it now reads "box, package, small device or light enclosure".

Not yet exercised on hardware: a reading on a Very high asset, and the detail-screen sensitivity
change. A vibrating-box demo should be created with the bench flag on.

## Result, hardware pass (2026-09-13, 10:4x-11:0x phone clock)

Three defects found on the phones and fixed; final APK `878eeb35cc4a7a2a`, 246/246 JVM tests, on
A, B and C via `install -r`. All 14 original assets read back identical before and after, and the
bench test asset used here was deleted through its confirmation dialog afterwards.

1. **Create equipment could not be completed.** The form was a fixed Column; the three
   Sensitivity options pushed the bench switch, "Create equipment" and Cancel below the screen and
   swipes did nothing. The form now scrolls (the machine-type result list is height-capped, so the
   nested scroll is safe). Verified: bench switch and Create reachable, a bench "Small or light
   object" created with `t1 0.5, t2 1.0, VERY_HIGH, benchTest true`.
2. **Sensitivity was applied twice.** On one untouched phone and one reference, Very high read
   `CRITICAL score=19.27` and Standard `HEALTHY score=1.13`: scaling the spread and z floors on top
   of the thresholds amplified the score 17x, dividing by 3-clip noise-sized stds (gyroIndexStd
   0.0098, magRmsStd 0.0032). The floors are no longer scaled; score is sensitivity-independent
   (tested), and a re-recorded reference saves `spread=0.0200` instead of `0.0050`.
3. **The profile factors sat below resting measurement noise.** Five still readings scored 0.90,
   1.12, 1.12, 1.97 and 6.36, so Very high 0.5 / 1.0 read Critical four times in five. Factors are
   now High 0.75 (1.5 / 3.0) and Very high 0.5 (1.0 / 2.0), with a test encoding those scores.

What the scores track: every score here is the sensor score and follows the vibration index. Quiet
desk readings (imu 0.10 to 0.23) scored 0.90 to 2.05; readings while the desk was disturbed (imu
0.40 to 0.45) scored 6.4 to 7.5, against a reference imu of 0.150. With the retuned Very high, quiet
readings land Healthy or Warning and real vibration lands Critical; one quiet reading at 2.05 just
crossed the 2.0 critical line. Thresholds cannot flag a change smaller than resting noise; reducing
that noise (longer captures, more reference clips) is the lever for finer detection, not thresholds.

Also verified on hardware in this pass: the reference measurement pre-roll (`preroll: done` then
three clips back to back with no tap), the reading pre-roll auto-navigating to its result, and the
detail-screen sensitivity change rescaling and persisting (Very high 0.5/1.0 to Standard 2.0/4.0).

v14 follow-up, verified on hardware the same morning: two owners healed by themselves (A
`owner: yielding to I2501-a783, lower id` then `p2p: removed own group after leaving owner mode`).
Stop on owner B at 10:50:01 persisted `lastRole CLIENT` and a 297 s opt-out, logged
`failover: automatic takeover suppressed, opted out after manual stop` at 10:50:06, and B's 8988
listener stayed at 0 for 180 s while A took over at 10:51:09 and B and C joined A as clients.

## Result, training-data integrity (2026-09-13, 11:1x-11:3x phone clock)

Read-only audit of all three phones' `samples.jsonl`, `shared_samples.jsonl`, metrics and
`network.json`: **zero** samples from bench-flagged equipment in any store or pool; every phone
training with held-out data for the first time (`nTrain 9, nVal 4, valAcc 0.750`, standings
`netAcc 0.75` on base, small, deep, noise); every labelled sample still class 0.

Defect found and fixed: deleting equipment removed only its folder, so its samples kept training
and spreading. Asset `712b007c` (generic, recorded on C 09:48 to 09:58, then deleted) still had 26
samples on C, 6 of them in A's and B's pools. Commit `34d6418` routes both delete buttons through
`ServiceLocator.deleteAssetAndSamples`, which removes the samples from this phone's store and pool;
the dialog now says shared copies on other phones stay. No startup cleanup: existing orphans such as
`712b007c` are left for the founder to decide. APK `83e536ab38db075e`, 252/252 JVM tests, on A, B, C.

Verified on phone A: a throwaway non-bench Very high item got a reference, then one reading added
one unlabelled sample (samples 8 to 9; unlabelled samples are never shared). Deleting it through the
dialog logged `asset delete: removed 1 own and 0 pooled samples for 25e6580c`, the folder was gone,
samples returned to 8, equipment to 8, and neither B nor C held any reference to `25e6580c`.
