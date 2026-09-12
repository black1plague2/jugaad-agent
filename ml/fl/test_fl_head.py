"""
pytest suite against the EXPORTED .tflite files (not the tf.Module), parametrised
over the three exported assets (fl_head_base/small/deep.tflite; noise/balanced
share base's asset so are covered by it).

Run: pytest ml/fl/test_fl_head.py -v
"""
import pathlib

import numpy as np
import pytest
import tensorflow as tf

import variants

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"

N_CLASSES = 3
TRAIN_BATCH = 8


def _canonical_variants():
    """One VariantSpec per distinct exported asset file (mirrors export_fl_head.py)."""
    seen = {}
    for v in variants.ALL:
        seen.setdefault(v.asset, v)
    return list(seen.values())


VARIANTS = _canonical_variants()
VARIANT_IDS = [v.id for v in VARIANTS]


@pytest.fixture(scope="module", params=VARIANTS, ids=VARIANT_IDS)
def variant(request):
    return request.param


@pytest.fixture(scope="module")
def sigs(variant):
    interpreter = tf.lite.Interpreter(model_path=str(ASSETS_DIR / variant.asset))
    return {
        "infer": interpreter.get_signature_runner("infer"),
        "train": interpreter.get_signature_runner("train"),
        "get_weights": interpreter.get_signature_runner("get_weights"),
        "set_weights": interpreter.get_signature_runner("set_weights"),
    }


def test_weight_count_matches_table(variant, sigs):
    w = sigs["get_weights"](dummy=np.zeros([1], dtype=np.float32))["w"]
    assert w.shape == (variant.weight_count,)


def test_set_weights_roundtrip(variant, sigs):
    w0 = sigs["get_weights"](dummy=np.zeros([1], dtype=np.float32))["w"]
    rng = np.random.RandomState(42)
    w_new = rng.randn(variant.weight_count).astype(np.float32)

    ok = sigs["set_weights"](w=w_new)["ok"]
    assert ok.shape == (1,)
    assert ok[0] == pytest.approx(1.0)

    w_readback = sigs["get_weights"](dummy=np.zeros([1], dtype=np.float32))["w"]
    np.testing.assert_array_equal(w_readback, w_new)

    # restore original weights so other tests are unaffected by ordering
    sigs["set_weights"](w=w0)


def test_infer_probs_sum_to_one(variant, sigs):
    rng = np.random.RandomState(0)
    x = rng.randn(1, variant.layers[0]).astype(np.float32)
    out = sigs["infer"](x=x)
    probs = out["probs"]
    logits = out["logits"]
    assert probs.shape == (1, N_CLASSES)
    assert logits.shape == (1, N_CLASSES)
    assert np.sum(probs) == pytest.approx(1.0, abs=1e-5)


def test_train_step_lowers_loss(variant, sigs):
    rng = np.random.RandomState(1)
    x = rng.randn(TRAIN_BATCH, variant.layers[0]).astype(np.float32)
    labels = rng.randint(0, N_CLASSES, size=TRAIN_BATCH)
    y = np.eye(N_CLASSES, dtype=np.float32)[labels]

    loss_before = sigs["train"](x=x, y=y)["loss"][0]
    loss_after = sigs["train"](x=x, y=y)["loss"][0]

    assert loss_after < loss_before


def test_weight_decay_shrinks_zero_grad_weight(variant, sigs):
    """
    head_model.FlHead.train applies w -= lr * (grad + 1e-4 * w) to every
    variable. With x all-zero, dL/dW1 = x^T @ dz1 is exactly zero (the first
    layer's weight matrix gets zero gradient regardless of the loss/labels),
    so any change in its norm is decay alone, not gradient descent -- proof
    the decay term is actually wired in, not just present in a docstring.
    """
    w0 = sigs["get_weights"](dummy=np.zeros([1], dtype=np.float32))["w"].copy()
    rng = np.random.RandomState(7)
    w_init = (rng.randn(variant.weight_count).astype(np.float32)) * 0.5
    sigs["set_weights"](w=w_init)

    fan_in, fan_out = variant.layers[0], variant.layers[1]
    n_w1 = fan_in * fan_out
    norm_before = float(np.linalg.norm(w_init[:n_w1]))

    x = np.zeros((TRAIN_BATCH, variant.layers[0]), dtype=np.float32)
    rng_labels = np.random.RandomState(8)
    labels = rng_labels.randint(0, N_CLASSES, size=TRAIN_BATCH)
    y = np.eye(N_CLASSES, dtype=np.float32)[labels]

    for _ in range(2000):  # amplify the tiny per-step decay so it's unambiguous
        sigs["train"](x=x, y=y)

    w_after = sigs["get_weights"](dummy=np.zeros([1], dtype=np.float32))["w"]
    norm_after = float(np.linalg.norm(w_after[:n_w1]))

    assert norm_after < norm_before * 0.995, (
        f"expected weight-decay shrinkage on zero-gradient W1, got "
        f"norm_before={norm_before} norm_after={norm_after}"
    )

    sigs["set_weights"](w=w0)  # restore for other tests


def test_layout_pin_matches_numpy_forward(variant, sigs):
    """
    Rebuild every layer's W/b from the flat get_weights() vector using the
    contract's flatten order (per layer W row-major then b, concatenated in
    layer order) and check a numpy forward pass (matmul -> relu -> ... ->
    softmax) matches the tflite infer() signature's probs, within 1e-4.
    """
    w = sigs["get_weights"](dummy=np.zeros([1], dtype=np.float32))["w"]
    assert w.shape == (variant.weight_count,)

    layers = variant.layers
    Ws, bs = [], []
    i = 0
    for fan_in, fan_out in zip(layers[:-1], layers[1:]):
        n = fan_in * fan_out
        Ws.append(w[i:i + n].reshape(fan_in, fan_out))
        i += n
        n = fan_out
        bs.append(w[i:i + n])
        i += n
    assert i == variant.weight_count

    rng = np.random.RandomState(123)
    x = rng.randn(1, layers[0]).astype(np.float32)

    h = x
    n_layers = len(Ws)
    for idx, (W, b) in enumerate(zip(Ws, bs)):
        z = h @ W + b
        h = np.maximum(z, 0.0) if idx < n_layers - 1 else z
    logits_np = h
    exp = np.exp(logits_np - np.max(logits_np, axis=-1, keepdims=True))
    probs_np = exp / np.sum(exp, axis=-1, keepdims=True)

    out = sigs["infer"](x=x)
    probs_tflite = out["probs"]

    np.testing.assert_allclose(probs_np, probs_tflite, atol=1e-4)
