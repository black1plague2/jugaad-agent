# Federated learning head — export & verification

Builds and exports the trainable heads used by the champion/challenger on-device
federated learning feature (see "Model spec v2" and "Network protocol v2" in
`plans/2026-09-12-champion-challenger-network.md`, which generalises the v1
`plans/2026-09-12-federated-on-device-learning.md` "Shared contract"). This
directory is independent of the rest of `ml/` (no shared deps with the
ExecuTorch CNN pipeline).

## Environment

Verified working combination: **Python 3.12.8 + tensorflow==2.16.1** (Windows wheel).

```bash
cd "ml"
py -3.12 -m venv .venv
.venv\Scripts\activate          # Windows
pip install -r fl/requirements-fl.txt
```

## Variant table

`variants.py` holds the champion/challenger table (id, asset file, layer dims,
weight count, epochs, noise sigma, balanced flag, label, description) and
asserts each variant's weight count against `head_model.compute_weight_count`.
`base`/`small`/`deep` map to three distinct architectures/asset files;
`noise` and `balanced` reuse `base`'s graph (`fl_head_base.tflite`) and only
differ in training recipe (applied in numpy, outside the graph).

**v3 update** (`plans/2026-09-12-sensors-strategies-datasets.md`): input grows
257 -> 260 (`INPUT_DIM = 260`; dims 256-259 are the four sensor deltas --
accel/gyro/mag-index/mag-rms, gyro/mag are 0 for any dataset without those
channels), weight counts recomputed (base 16899, small 8451, deep 18883), and
`train()` now applies weight decay (`w -= lr * (grad + 1e-4 * w)`). See
"Public-dataset pretraining" below for `mafaulda_ingest.py`/`dataset.py`/
`pretrain.py`, which bake trained (not just seed-7 random) weights into the
exported files.

## 1. Export the .tflite files

```bash
cd fl
python export_fl_head.py
```

Writes `app/src/main/assets/models/fl_head_base.tflite` (~85 KB, [260,64,3]),
`fl_head_small.tflite` (~49 KB, [260,32,3]) and `fl_head_deep.tflite` (~98 KB,
[260,64,32,3]), each with signatures `infer`, `train`, `get_weights`,
`set_weights`, converted `TFLITE_BUILTINS`-only (no SELECT_TF_OPS -- see
"Conversion notes" below). Deletes the v1 `fl_head.tflite`. Random seed-7
init unless `pretrain.py` (below) has baked in trained weights.

## 2. Run the simulations

```bash
python fedavg_sim.py     # v1 sanity check: 3-client FedAvg against fl_head_base.tflite
python network_sim.py    # v2: 3-node champion/challenger network over 6 rounds
```

`fedavg_sim.py` is unchanged in behaviour from v1, just retargeted at
`fl_head_base.tflite` (same [257,64,3]/16707-weight architecture as the old
`fl_head.tflite`, which export_fl_head.py now deletes).

`network_sim.py` drives the three exported assets via
`tf.lite.Interpreter` signature runners (no tf.Module access). Every node
always trains the champion (`base`); each node additionally holds one
challenger, assigned round-robin over `[small, deep, noise, balanced]`
(node0=small, node1=deep, node2=noise -- with only 3 nodes for 4 challengers,
`balanced` goes unassigned this run, exactly as the contract's real
least-populated-challenger assignment rule would leave it for a 4th node).
Synthetic data is mildly non-linear (class 2, Airflow Obstruction, is an
XOR-like interaction of two feature-band groups) so `deep`'s extra layer has
a real, non-guaranteed chance against `base`/`small`. Implements the
promotion rule (nVal-weighted `netAcc`, eligibility >= 8 total nVal, `wins`
>= 2 consecutive qualifying rounds at a >= 0.03 margin, promotion resets all
`wins`) and prints a per-round table. Exits non-zero only if the champion's
final-round `netAcc` is below 0.85.

## 3. Run the tests

```bash
python -m pytest test_fl_head.py -v
```

Parametrised over the three exported files (fl_head_base/small/deep.tflite;
noise/balanced share base's asset): weight count against the table, set/get
round-trip, softmax normalization, a train step lowering loss, and a layout
pin that rebuilds every layer's W/b from the flat `get_weights()` vector in
numpy (generalised to N layers) and checks the forward pass matches the
tflite `infer()` output.

## Conversion notes

`tf.nn.relu`'s registered gradient lowers to the fused `ReluGrad` op, which has no
TFLite builtin equivalent, so a `TFLITE_BUILTINS`-only conversion of the `train`
signature failed with `ERROR_NEEDS_FLEX_OPS` on `ReluGrad`. Fix: the forward pass
in `head_model.py` uses `tf.maximum(z, 0.0)` instead of `tf.nn.relu(z)` --
numerically identical, but its gradient decomposes into primitive ops
(`GreaterEqual`, `SelectV2`) that TFLite builtins do support. No SELECT_TF_OPS
needed for any variant (`export_fl_head.py` reports loudly and re-raises if
that ever stops being true).

## Public-dataset pretraining (v3)

```bash
python mafaulda_download.py   # resume-safe: normal.zip + imbalance.zip -> ../data/mafaulda/
python mafaulda_ingest.py     # per-clip 256-d log-mel + accel index -> ../data/mafaulda/features.npz
python dataset.py             # sanity-print of the baseline-relative 260-d dataset (see below)
python pretrain.py            # CV, contrastive pretraining, final training, bakes weights + exports
```

`logmel_np.py` reimplements `logmel_reference.py`'s STFT + HTK-mel-filterbank
math in pure numpy/scipy (no librosa -- this venv deliberately doesn't have
it); `mafaulda_ingest.py` uses that, not `logmel_reference.py` directly, since
importing the latter would require librosa just to reach its constants.

`dataset.py` groups clips into 6 rpm bins (virtual "machines"), computes a
per-bin baseline (mean of 5 Healthy clips), and builds
`x = [feat - baseline (256), (accelIdx - baseAccel) * 10, 0, 0, 0]` (260-d;
gyro/mag dims are 0, MAFAULDA has neither channel). Airflow Obstruction
(class 2) has no public data (see `../data/DATASETS.md`), so `dataset.py`
adds synthetic class-2 samples from `network_sim.py`'s generator,
round-robin-assigned across bins.

`pretrain.py` runs leave-one-bin-out CV of the `base` head (random vs.
SimCLR-contrastively-pretrained first layer), picks the mean-accuracy winner
(ties -> random), trains final base/small/deep heads on all data with that
init (`small`'s first layer is 32-wide and can't take the 64-wide contrastive
projection, so it always trains from the seed-7 random init), bakes the
trained weights into the exported `.tflite` files via
`export_fl_head.export_all(init_weights_by_id=...)`, and writes
`../data/mafaulda/pretrained_<variant>.bin` (float32 LE) per variant. See
`../data/README.md` for the actual numbers from the last run.

## Field data ingestion (v4 §6)

```bash
python field_ingest.py               # pulls every adb-attached phone -> ../data/field/
python pretrain.py --include-field   # folds ../data/field/samples.jsonl into training
```

`field_ingest.py` enumerates `adb devices` (serials and `ip:port` entries alike)
and, for each, pulls `files/fl/node.json`, `files/fl/samples.jsonl` and
`files/fl/shared_samples.jsonl` from the debuggable app via `adb -s <dev>
exec-out run-as com.jugaad.agent.debug cat files/fl/<name>`. Keeps only
labelled, 260-d samples; tags each with `device` and `origin` (the line's own,
else the node's `deviceId`, else the device identifier); dedupes by `id`
across devices, first seen wins. Writes `../data/field/samples.jsonl` (merged)
and `../data/field/summary.json` (counts per device/label/origin/machine
type/source, total unique, pulled-at timestamp), and prints the same summary
as a table. A device that's offline or lacks the app is reported and skipped,
never crashes the run. Flags: `--devices a,b` to restrict, `--out` dir,
`--dry-run`. adb path: `$ADB_PATH` env var, else
`tools/android-sdk/platform-tools/adb(.exe)` next to the repo root, else
`adb` on `PATH`.

`pretrain.py --include-field` loads `../data/field/samples.jsonl` and appends
those samples (`x` used exactly as pulled -- the phone already writes
baseline-relative deltas, unlike the MAFAULDA clips `dataset.py` rebaselines
itself) to the training set built from MAFAULDA + synthetic class 2. With
**20 or more** field samples they become their own leave-one-out CV bin (one
past the last rpm bin), so the report shows how well the champion architecture
generalises to real phone data it never trained on. With fewer than 20 (too
few for a meaningful held-out estimate) they still join every fold's training
pool -- and the final all-data training set -- but never become anyone's test
set. Omitting the flag leaves training exactly as before.

## Files

- `variants.py` — the champion/challenger variant table (`VariantSpec`, `ALL`,
  `CHAMPION_DEFAULT`, `by_id`, `challengers`, `ASSET_FILES`).
- `head_model.py` — `FlHead(tf.Module)`, generalised to any `[257, h1, (h2,), 3]`
  layer list: N `(W, b)` pairs, four signatures (infer/train/get_weights/
  set_weights), fixed seed 7. `compute_weight_count(layers)` is shared with
  `variants.py`'s table assertion.
- `export_fl_head.py` — exports every distinct asset file in the variant
  table, builtins-only, resource variables enabled; deletes the v1
  `fl_head.tflite`.
- `fedavg_sim.py` — v1's 3-client FedAvg simulation, now against
  `fl_head_base.tflite`.
- `network_sim.py` — v2's 3-node, 6-round champion/challenger network
  simulation (FedAvg per variant, `netAcc`/`wins`/promotion).
- `test_fl_head.py` — pytest suite against the exported artifacts,
  parametrised over the three files (incl. a weight-decay-shrinks-a-
  zero-gradient-layer test).
- `mafaulda_download.py` — resume-safe downloader for normal.zip/imbalance.zip.
- `logmel_np.py` — librosa-free reimplementation of `logmel_reference.py`'s
  STFT + mel-filterbank math.
- `mafaulda_ingest.py` — per-clip feature extraction -> `features.npz`.
- `dataset.py` — rpm-bin grouping, baseline-relative 260-d samples, synthetic
  class-2 generation.
- `pretrain.py` — leave-one-bin-out CV, contrastive pretraining, final
  training, weight baking/export; `--include-field` folds in
  `../data/field/samples.jsonl`.
- `field_ingest.py` — pulls labelled samples off every adb-attached phone into
  `../data/field/samples.jsonl` + `summary.json`.
