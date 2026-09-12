"""
Exports every distinct asset file in the variant table (variants.ASSET_FILES) to
app/src/main/assets/models/*.tflite: fl_head_base.tflite, fl_head_small.tflite,
fl_head_deep.tflite. Deletes the v1 fl_head.tflite (replaced by the three files
above; see "Model spec v2" in plans/2026-09-12-champion-challenger-network.md).

Uses the standard TFLite on-device-training recipe: save the tf.Module with named
signatures via tf.saved_model.save, then convert from_saved_model(signature_keys=...)
targeting TFLITE_BUILTINS only with resource variables enabled -- same recipe as v1,
just looped over each distinct architecture in the table. If any variant needs flex
ops, this reports it loudly (and re-raises) instead of silently adding SELECT_TF_OPS.
"""
import pathlib
import tempfile

import tensorflow as tf

from head_model import FlHead
import variants

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"
V1_MODEL_PATH = ASSETS_DIR / "models" / "fl_head.tflite"

SIGNATURE_KEYS = ["infer", "train", "get_weights", "set_weights"]


def _canonical_by_asset():
    """One VariantSpec per distinct asset file (first occurrence in table order).

    noise/balanced reuse base's graph (same architecture; they only diverge in
    training recipe, applied outside the graph), so exporting base's variant
    also produces their asset file.
    """
    seen = {}
    for v in variants.ALL:
        seen.setdefault(v.asset, v)
    return seen


def export_variant(spec: variants.VariantSpec, init_weights=None) -> pathlib.Path:
    """init_weights: optional flat float32 vector to bake in instead of the
    seed-7 random init (see head_model.FlHead's init_weights docstring)."""
    head = FlHead(spec.layers, init_weights=init_weights)

    concrete_funcs = {
        "infer": head.infer.get_concrete_function(),
        "train": head.train.get_concrete_function(),
        "get_weights": head.get_weights.get_concrete_function(),
        "set_weights": head.set_weights.get_concrete_function(),
    }

    with tempfile.TemporaryDirectory() as tmp_dir:
        tf.saved_model.save(head, tmp_dir, signatures=concrete_funcs)
        converter = tf.lite.TFLiteConverter.from_saved_model(
            tmp_dir, signature_keys=SIGNATURE_KEYS
        )
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
        converter.experimental_enable_resource_variables = True
        try:
            tflite_model = converter.convert()
        except Exception:
            print(
                f"!!! variant '{spec.id}' layers={spec.layers} needs flex ops -- "
                f"TFLITE_BUILTINS-only conversion failed. See head_model.py's "
                f"tf.maximum-instead-of-tf.nn.relu note for the usual cause."
            )
            raise

    out_path = ASSETS_DIR / spec.asset
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(tflite_model)
    return out_path


def export_all(init_weights_by_id=None):
    """init_weights_by_id: optional {variant_id: flat float32 vector} map (see
    pretrain.py) -- variants not present fall back to the seed-7 random init."""
    init_weights_by_id = init_weights_by_id or {}
    written = []
    for spec in _canonical_by_asset().values():
        written.append(export_variant(spec, init_weights=init_weights_by_id.get(spec.id)))

    if V1_MODEL_PATH.exists():
        V1_MODEL_PATH.unlink()
        print(f"removed v1 model {V1_MODEL_PATH}")

    return written


if __name__ == "__main__":
    for path in export_all():
        size = path.stat().st_size
        print(f"wrote {path} ({size} bytes)")
        interpreter = tf.lite.Interpreter(model_path=str(path))
        sigs = interpreter.get_signature_list()
        for key in SIGNATURE_KEYS:
            print(" ", key, sigs.get(key))
