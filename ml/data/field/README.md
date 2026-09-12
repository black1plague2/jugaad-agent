# Field samples

Pulled from the phones with `ml/fl/field_ingest.py`.

`*.bench-*.DO-NOT-TRAIN.jsonl` files were captured with the phones lying on a table during
device tests (synthetic and injected-vibration readings). They are NOT machine data. Do not pass
them to `pretrain.py --include-field`. `field_ingest.py --for-training` only writes
`samples.jsonl` from equipment that is not flagged as bench/test; anything else is written with
the `.DO-NOT-TRAIN` suffix.

Concretely: `field_ingest.py` without `--for-training` pulls everything and writes a dated,
unfiltered `samples.<date>.DO-NOT-TRAIN.jsonl` / `summary.<date>.json` -- safe to inspect, never
to train on. `field_ingest.py --for-training` additionally pulls each sample's
`files/assets/<assetId>/asset.json` from its origin device and drops any sample whose asset has
`benchTest: true` (set from the equipment's "Bench / test equipment" toggle in the app) before
writing the plain `samples.jsonl` / `summary.json` that `pretrain.py --include-field` reads; it
prints how many samples were skipped as bench.
