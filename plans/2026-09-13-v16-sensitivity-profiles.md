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
