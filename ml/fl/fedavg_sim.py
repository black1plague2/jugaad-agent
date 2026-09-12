"""
Proves the EXPORTED fl_head.tflite learns under federated averaging.

Uses only tf.lite.Interpreter + get_signature_runner (no tf.Module access) so this
exercises exactly the artifact the Android app will load.

Synthetic data lives in the 260-d baseline-delta feature space described in
plans/2026-09-12-sensors-strategies-datasets.md ("Decision 2"): dims 0..255 are
the log-mel feature deltas (unchanged from v1/v2), dims 256..259 are the four
sensor deltas (accel/gyro/mag-index/mag-rms), generated as small
class-dependent shifts on top of a shared N(0, 0.2) noise floor:
  Healthy            ~ N(0, 0.3) on dims 0..255, dims 256..259 ~ N(0, 0.2)
  Rotor Imbalance    = Healthy + 1.5 on dims 0..15, + 0.4 on dims 128..143,
                       dims 256..259 += [1.0, 0.6, 0.3, 0.2]
  Airflow Obstruction = Healthy + 1.2 on dims 60..127, + 0.5 on dims 188..255,
                       dims 256..259 += [0.3, 0.2, 0.15, 0.1]

Three non-IID virtual clients (~40 samples each), a balanced 300-sample global
test set, 5 FedAvg rounds of (10 local epochs, batch 8, pad-by-repeat). Exits
non-zero if the final merged accuracy on the test set is below 0.90.
"""
import pathlib
import sys

import numpy as np
import tensorflow as tf

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
# v1's fl_head.tflite was removed by export_fl_head.py (see "Model spec v2" in
# plans/2026-09-12-champion-challenger-network.md); fl_head_base.tflite is now
# [260,64,3]/16899 weights (v3, plans/2026-09-12-sensors-strategies-datasets.md).
MODEL_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "models" / "fl_head_base.tflite"

INPUT_DIM = 260
N_CLASSES = 3
TRAIN_BATCH = 8
WEIGHT_COUNT = 16899
LOCAL_EPOCHS = 10
ROUNDS = 5
ACC_THRESHOLD = 0.9

SEED = 123


def make_signatures(model_path=MODEL_PATH):
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    return {
        "infer": interpreter.get_signature_runner("infer"),
        "train": interpreter.get_signature_runner("train"),
        "get_weights": interpreter.get_signature_runner("get_weights"),
        "set_weights": interpreter.get_signature_runner("set_weights"),
    }


def gen_samples(n, label, rng):
    """label: 0 Healthy, 1 Rotor Imbalance, 2 Airflow Obstruction."""
    x = rng.normal(0.0, 0.3, size=(n, INPUT_DIM)).astype(np.float32)
    x[:, 256:260] = rng.normal(0.0, 0.2, size=(n, 4)).astype(np.float32)
    if label == 1:
        x[:, 0:16] += 1.5
        x[:, 128:144] += 0.4
        x[:, 256:260] += np.array([1.0, 0.6, 0.3, 0.2], dtype=np.float32)
    elif label == 2:
        x[:, 60:128] += 1.2
        x[:, 188:256] += 0.5
        x[:, 256:260] += np.array([0.3, 0.2, 0.15, 0.1], dtype=np.float32)
    y = np.zeros((n, N_CLASSES), dtype=np.float32)
    y[:, label] = 1.0
    return x, y


def make_client_data(n_per_class_mix, rng):
    """n_per_class_mix: dict label -> count."""
    xs, ys = [], []
    for label, n in n_per_class_mix.items():
        x, y = gen_samples(n, label, rng)
        xs.append(x)
        ys.append(y)
    x = np.concatenate(xs, axis=0)
    y = np.concatenate(ys, axis=0)
    perm = rng.permutation(len(x))
    return x[perm], y[perm]


def make_test_set(n_per_class, rng):
    xs, ys = [], []
    for label in range(N_CLASSES):
        x, y = gen_samples(n_per_class, label, rng)
        xs.append(x)
        ys.append(y)
    x = np.concatenate(xs, axis=0)
    y = np.concatenate(ys, axis=0)
    perm = rng.permutation(len(x))
    return x[perm], y[perm]


def iter_batches(x, y, batch_size, rng):
    """Yield full batches, padding the final short batch by repeating samples."""
    n = len(x)
    order = rng.permutation(n)
    x, y = x[order], y[order]
    i = 0
    while i < n:
        end = min(i + batch_size, n)
        xb, yb = x[i:end], y[i:end]
        if len(xb) < batch_size:
            pad = batch_size - len(xb)
            idx = rng.choice(len(xb), size=pad, replace=True)
            xb = np.concatenate([xb, xb[idx]], axis=0)
            yb = np.concatenate([yb, yb[idx]], axis=0)
        yield xb.astype(np.float32), yb.astype(np.float32)
        i = end


def local_train(sigs, x, y, epochs, rng):
    last_loss = None
    for _ in range(epochs):
        for xb, yb in iter_batches(x, y, TRAIN_BATCH, rng):
            out = sigs["train"](x=xb, y=yb)
            last_loss = float(out["loss"][0])
    return last_loss


def evaluate(sigs, x, y):
    correct = 0
    for i in range(len(x)):
        probs = sigs["infer"](x=x[i:i + 1])["probs"][0]
        pred = int(np.argmax(probs))
        true = int(np.argmax(y[i]))
        correct += int(pred == true)
    return correct / len(x)


def fedavg_merge(weight_count_pairs):
    total = sum(n for _, n in weight_count_pairs)
    merged = np.zeros(WEIGHT_COUNT, dtype=np.float64)
    for w, n in weight_count_pairs:
        merged += w.astype(np.float64) * (n / total)
    return merged.astype(np.float32)


def main():
    rng = np.random.RandomState(SEED)

    # Non-IID class mixes, ~40 samples each.
    client_mixes = [
        {0: 28, 1: 8, 2: 4},   # 70/20/10
        {0: 8, 1: 28, 2: 4},   # 20/70/10
        {0: 4, 1: 8, 2: 28},   # 10/20/70
    ]
    client_data = [make_client_data(mix, rng) for mix in client_mixes]

    x_test, y_test = make_test_set(100, rng)  # 100 * 3 = 300, balanced

    global_sigs = make_signatures()
    global_weights = global_sigs["get_weights"](dummy=np.zeros([1], dtype=np.float32))["w"]

    client_sigs = [make_signatures() for _ in client_data]

    print(f"{'round':>5} | {'client accs (after local train)':>34} | {'merged acc':>10}")
    final_merged_acc = None
    for rnd in range(1, ROUNDS + 1):
        contributions = []
        client_accs = []
        for sigs, (x, y) in zip(client_sigs, client_data):
            sigs["set_weights"](w=global_weights)
            local_train(sigs, x, y, LOCAL_EPOCHS, rng)
            w_local = sigs["get_weights"](dummy=np.zeros([1], dtype=np.float32))["w"]
            contributions.append((w_local, len(x)))
            client_accs.append(evaluate(sigs, x, y))

        global_weights = fedavg_merge(contributions)
        global_sigs["set_weights"](w=global_weights)
        merged_acc = evaluate(global_sigs, x_test, y_test)
        final_merged_acc = merged_acc

        accs_str = ", ".join(f"{a:.3f}" for a in client_accs)
        print(f"{rnd:>5} | {accs_str:>34} | {merged_acc:>10.3f}")

    print()
    print(f"final merged accuracy on 300-sample balanced test set: {final_merged_acc:.3f}")
    if final_merged_acc < ACC_THRESHOLD:
        print(f"FAIL: final merged accuracy {final_merged_acc:.3f} < {ACC_THRESHOLD}")
        sys.exit(1)
    print("PASS")


if __name__ == "__main__":
    main()
