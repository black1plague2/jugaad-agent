"""
Offline pretraining + evaluation for the public-dataset pipeline (brief items
4-5; plans/2026-09-12-sensors-strategies-datasets.md Decision 5/6).

1. Leave-one-rpm-bin-out cross-validation of the `base` [260,64,3] head:
   (a) random (seed-7) init vs (b) a SimCLR-style contrastively pretrained
   first layer, both fine-tuned with the same SGD+weight-decay recipe
   (head_model.py's train(), called directly on the tf.Module so early
   stopping can inspect held-out loss every epoch -- the exported .tflite
   train signature itself is unchanged, fixed batch 8).
2. Whichever init wins on mean held-out accuracy (ties -> random) trains the
   final base/small/deep heads on ALL data (real MAFAULDA-derived + synthetic
   class 2), 30 epochs early-stopped. `small`'s first layer is 260->32 (the
   contrastive projection is pretrained at width 64 to match base/deep, whose
   first layer is 260->64), so `small` cannot receive the contrastive init by
   construction -- it always trains from the seed-7 random init, per the
   brief's "keep seed-7 init for any variant you could not train".
3. Final weights are baked into the exported .tflite files via
   export_fl_head.export_all(init_weights_by_id=...) and also dumped as
   ml/data/mafaulda/pretrained_<variant>.bin (float32 LE).

Run: python pretrain.py
       python pretrain.py --include-field   # also folds in ml/data/field/samples.jsonl
                                             # (field_ingest.py's on-device pulls; see README)
"""
import argparse
import json
import pathlib
import time

import numpy as np
import tensorflow as tf

import dataset
import export_fl_head
from head_model import FlHead, TRAIN_BATCH, N_CLASSES

DATA_DIR = pathlib.Path(__file__).resolve().parents[1] / "data" / "mafaulda"
FIELD_PATH = pathlib.Path(__file__).resolve().parents[1] / "data" / "field" / "samples.jsonl"
# Below this many field samples, a dedicated leave-one-out CV fold isn't meaningful
# (see README "Field data ingestion"): they still join the training set, just never
# as anyone's held-out test set.
FIELD_MIN_FOR_OWN_BIN = 20

MAX_EPOCHS = 30
PATIENCE = 5
CONTRASTIVE_STEPS = 200
CONTRASTIVE_BATCH = 64
CONTRASTIVE_LR = 1e-3
PROJ_DIM = 32
TEMPERATURE = 0.1
NOISE_SIGMA = 0.1
BAND_DROPOUT = 0.10
MIXUP_LAMBDA = 0.2


# ---------------------------------------------------------------- training --

def flatten_weights(head: FlHead) -> np.ndarray:
    parts = []
    for W, b in zip(head.Ws, head.bs):
        parts.append(W.numpy().reshape(-1))
        parts.append(b.numpy().reshape(-1))
    return np.concatenate(parts).astype(np.float32)


def forward_logits(head: FlHead, x: np.ndarray) -> np.ndarray:
    return head._forward(tf.constant(x, dtype=tf.float32)).numpy()


def loss_of(head: FlHead, x: np.ndarray, y_onehot: np.ndarray) -> float:
    logits = forward_logits(head, x)
    logits = logits - logits.max(axis=1, keepdims=True)
    logp = logits - np.log(np.exp(logits).sum(axis=1, keepdims=True))
    return float(-(y_onehot * logp).sum(axis=1).mean())


def predict(head: FlHead, x: np.ndarray) -> np.ndarray:
    return np.argmax(forward_logits(head, x), axis=1)


def iter_batches(x, y, batch_size, rng):
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


def train_supervised(head, x_train, y_train, x_val, y_val, max_epochs=MAX_EPOCHS,
                      patience=PATIENCE, seed=0):
    """SGD+wd (head.train(), 8-sample batches) with early stopping on held-out
    loss, best-epoch weight restore. Returns the number of epochs actually run."""
    rng = np.random.RandomState(seed)
    y_train_1h = np.eye(N_CLASSES, dtype=np.float32)[y_train]
    y_val_1h = np.eye(N_CLASSES, dtype=np.float32)[y_val] if len(x_val) else None

    best_loss = np.inf
    best_flat = None
    no_improve = 0
    epoch_ran = 0
    for epoch in range(max_epochs):
        epoch_ran = epoch + 1
        for xb, yb in iter_batches(x_train, y_train_1h, TRAIN_BATCH, rng):
            head.train(x=xb, y=yb)
        if y_val_1h is not None and len(x_val) > 0:
            val_loss = loss_of(head, x_val, y_val_1h)
            if val_loss < best_loss - 1e-6:
                best_loss = val_loss
                best_flat = flatten_weights(head)
                no_improve = 0
            else:
                no_improve += 1
                if no_improve >= patience:
                    break
    if best_flat is not None:
        set_flat_weights(head, best_flat)
    return epoch_ran


def set_flat_weights(head: FlHead, flat: np.ndarray):
    i = 0
    for W, b in zip(head.Ws, head.bs):
        n = int(np.prod(W.shape))
        W.assign(flat[i:i + n].reshape(W.shape))
        i += n
        n = int(b.shape[0])
        b.assign(flat[i:i + n])
        i += n


def macro_f1(y_true, y_pred, n_classes=N_CLASSES):
    f1s = []
    for c in range(n_classes):
        tp = int(np.sum((y_pred == c) & (y_true == c)))
        fp = int(np.sum((y_pred == c) & (y_true != c)))
        fn = int(np.sum((y_pred != c) & (y_true == c)))
        prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        rec = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1s.append(2 * prec * rec / (prec + rec) if (prec + rec) > 0 else 0.0)
    return float(np.mean(f1s))


def confusion_matrix(y_true, y_pred, n_classes=N_CLASSES):
    cm = np.zeros((n_classes, n_classes), dtype=np.int64)
    for t, p in zip(y_true, y_pred):
        cm[t, p] += 1
    return cm


def stratified_split(y, val_frac=0.2, seed=0):
    rng = np.random.RandomState(seed)
    train_idx, val_idx = [], []
    for c in np.unique(y):
        idx = np.where(y == c)[0]
        rng.shuffle(idx)
        n_val = max(1, int(round(len(idx) * val_frac))) if len(idx) >= 5 else 0
        val_idx.extend(idx[:n_val].tolist())
        train_idx.extend(idx[n_val:].tolist())
    train_idx = np.array(train_idx, dtype=np.int64)
    val_idx = np.array(val_idx, dtype=np.int64)
    rng.shuffle(train_idx)
    rng.shuffle(val_idx)
    return train_idx, val_idx


# ------------------------------------------------------------- contrastive --

def augment(x, rng, n_dims_drop):
    noise = rng.normal(0.0, NOISE_SIGMA, size=x.shape).astype(np.float32)
    out = x + noise
    n, d = out.shape
    mask = np.ones_like(out)
    for i in range(n):
        drop = rng.choice(d, size=n_dims_drop, replace=False)
        mask[i, drop] = 0.0
    out = out * mask
    perm = rng.permutation(n)
    out = (1.0 - MIXUP_LAMBDA) * out + MIXUP_LAMBDA * out[perm]
    return out.astype(np.float32)


def nt_xent_loss(z1, z2, temperature=TEMPERATURE):
    z1 = tf.math.l2_normalize(z1, axis=1)
    z2 = tf.math.l2_normalize(z2, axis=1)
    n = tf.shape(z1)[0]
    reps = tf.concat([z1, z2], axis=0)
    sim = tf.matmul(reps, reps, transpose_b=True) / temperature
    sim = sim - tf.eye(2 * n, dtype=sim.dtype) * 1e9
    labels = tf.concat([tf.range(n, 2 * n), tf.range(0, n)], axis=0)
    loss = tf.nn.sparse_softmax_cross_entropy_with_logits(labels=labels, logits=sim)
    return tf.reduce_mean(loss)


def contrastive_pretrain(x_real, hidden_dim, input_dim, steps=CONTRASTIVE_STEPS,
                          batch_size=CONTRASTIVE_BATCH, seed=0):
    """SimCLR-style pretraining of a single Dense(input_dim->hidden_dim, relu)
    layer via NT-Xent over two augmented views (Gaussian noise 0.1, 10% band
    dropout, mixup 0.2). Returns (W1, b1) as numpy arrays."""
    rng = np.random.RandomState(seed)
    tf.random.set_seed(seed)
    n_drop = max(1, int(round(BAND_DROPOUT * input_dim)))

    limit1 = np.sqrt(6.0 / (input_dim + hidden_dim))
    W1 = tf.Variable(rng.uniform(-limit1, limit1, size=(input_dim, hidden_dim)).astype(np.float32))
    b1 = tf.Variable(np.zeros(hidden_dim, dtype=np.float32))
    limit2 = np.sqrt(6.0 / (hidden_dim + PROJ_DIM))
    W2 = tf.Variable(rng.uniform(-limit2, limit2, size=(hidden_dim, PROJ_DIM)).astype(np.float32))
    b2 = tf.Variable(np.zeros(PROJ_DIM, dtype=np.float32))
    opt = tf.keras.optimizers.Adam(learning_rate=CONTRASTIVE_LR)

    n = len(x_real)
    bs = min(batch_size, n)
    last_loss = None
    for step in range(steps):
        idx = rng.choice(n, size=bs, replace=(n < bs))
        xb = x_real[idx]
        v1 = augment(xb, rng, n_drop)
        v2 = augment(xb, rng, n_drop)
        with tf.GradientTape() as tape:
            h1 = tf.maximum(tf.matmul(v1, W1) + b1, 0.0)
            z1 = tf.matmul(h1, W2) + b2
            h2 = tf.maximum(tf.matmul(v2, W1) + b1, 0.0)
            z2 = tf.matmul(h2, W2) + b2
            loss = nt_xent_loss(z1, z2)
        grads = tape.gradient(loss, [W1, b1, W2, b2])
        opt.apply_gradients(zip(grads, [W1, b1, W2, b2]))
        last_loss = float(loss.numpy())
        if step % 50 == 0 or step == steps - 1:
            print(f"    contrastive step {step:4d}: NT-Xent loss={last_loss:.4f}")
    return W1.numpy(), b1.numpy()


def load_field_samples(path=FIELD_PATH):
    """Loads field_ingest.py's `ml/data/field/samples.jsonl` as (x, label) arrays.
    `x` is used exactly as pulled -- the phone already writes baseline-relative
    deltas (see FeatureDelta.kt), unlike the MAFAULDA clips dataset.py rebaselines
    itself. Skips anything not 260-d or unlabelled; returns empty arrays (never
    raises) if the file is absent, e.g. field_ingest.py hasn't been run yet."""
    if not path.exists():
        return np.zeros((0, 260), dtype=np.float32), np.zeros((0,), dtype=np.int64)
    xs, ys = [], []
    with path.open("r", encoding="utf-8") as f:
        for raw in f:
            raw = raw.strip()
            if not raw:
                continue
            try:
                obj = json.loads(raw)
            except (json.JSONDecodeError, ValueError):
                continue
            x, label = obj.get("x"), obj.get("label")
            if not isinstance(x, list) or len(x) != 260 or label is None:
                continue
            xs.append(x)
            ys.append(int(label))
    if not xs:
        return np.zeros((0, 260), dtype=np.float32), np.zeros((0,), dtype=np.int64)
    return np.asarray(xs, dtype=np.float32), np.asarray(ys, dtype=np.int64)


# --------------------------------------------------------------------- CV --

def run_cv(ds, w1_c, b1_c, n_bins):
    bin_idx = ds["bin_idx"]
    x260 = ds["x260"]
    label = ds["label"]

    results = {"random": [], "contrastive": []}
    cms = {"random": np.zeros((N_CLASSES, N_CLASSES), dtype=np.int64),
           "contrastive": np.zeros((N_CLASSES, N_CLASSES), dtype=np.int64)}

    for h in range(n_bins):
        test_mask = bin_idx == h
        train_mask = ~test_mask
        x_test, y_test = x260[test_mask], label[test_mask]
        x_trainfull, y_trainfull = x260[train_mask], label[train_mask]
        if len(x_test) == 0 or len(np.unique(y_trainfull)) < N_CLASSES:
            print(f"  fold {h}: skipped (test={len(x_test)}, train classes={np.unique(y_trainfull)})")
            continue

        tr_idx, val_idx = stratified_split(y_trainfull, val_frac=0.2, seed=1000 + h)
        x_tr, y_tr = x_trainfull[tr_idx], y_trainfull[tr_idx]
        x_val, y_val = x_trainfull[val_idx], y_trainfull[val_idx]

        for mode in ("random", "contrastive"):
            head = FlHead([260, 64, 3])
            if mode == "contrastive":
                head.Ws[0].assign(w1_c)
                head.bs[0].assign(b1_c)
            epochs = train_supervised(head, x_tr, y_tr, x_val, y_val, seed=2000 + h)
            preds = predict(head, x_test)
            acc = float(np.mean(preds == y_test))
            f1 = macro_f1(y_test, preds)
            cms[mode] += confusion_matrix(y_test, preds)
            results[mode].append({"fold": h, "acc": acc, "f1": f1, "epochs": epochs, "n_test": len(x_test)})
            print(f"  fold {h} [{mode:11s}] n_test={len(x_test):3d} epochs={epochs:2d} acc={acc:.3f} macroF1={f1:.3f}")

    return results, cms


# ------------------------------------------------------------------- main --

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--include-field", action="store_true",
                     help="fold ml/data/field/samples.jsonl (field_ingest.py's on-device "
                          "pulls) into the training set; default behaviour is unchanged "
                          "when omitted -- see README 'Field data ingestion'")
    args = ap.parse_args()

    t0 = time.time()
    print("building dataset (baseline-relative 260-d + synthetic class 2) ...")
    ds = dataset.build_dataset()
    print(f"total samples: {len(ds['x260'])}")
    for b, row in ds["counts"].items():
        print(f"  bin {b}: healthy={row[0]} imbalance={row[1]} airflow(synthetic)={row[2]}")

    n_bins = len(ds["counts"])

    if args.include_field:
        x_field, y_field = load_field_samples()
        if len(x_field) == 0:
            print("\n--include-field: ml/data/field/samples.jsonl is missing or empty -- nothing to add")
        else:
            if len(x_field) >= FIELD_MIN_FOR_OWN_BIN:
                field_bin = n_bins  # one past the last real rpm bin -> its own leave-one-out fold
                print(f"\n--include-field: {len(x_field)} field samples (>= {FIELD_MIN_FOR_OWN_BIN}) "
                      f"-> own CV bin {field_bin}")
                n_bins += 1
            else:
                field_bin = -1  # sentinel: always in the training pool, never anyone's test fold
                print(f"\n--include-field: {len(x_field)} field samples (< {FIELD_MIN_FOR_OWN_BIN}) "
                      f"-> added to every fold's training pool, no dedicated held-out fold")
            ds["x260"] = np.concatenate([ds["x260"], x_field], axis=0)
            ds["label"] = np.concatenate([ds["label"], y_field], axis=0)
            ds["bin_idx"] = np.concatenate(
                [ds["bin_idx"], np.full(len(x_field), field_bin, dtype=ds["bin_idx"].dtype)])
            ds["source"] = np.concatenate([ds["source"], np.array(["field"] * len(x_field))])

    real_mask = ds["source"] == "real"
    x_real = ds["x260"][real_mask]

    print(f"\ncontrastive pretraining ({CONTRASTIVE_STEPS} steps, Adam {CONTRASTIVE_LR}, "
          f"hidden=64) on {len(x_real)} real clips ...")
    w1_c, b1_c = contrastive_pretrain(x_real, hidden_dim=64, input_dim=260, seed=0)

    print("\nleave-one-bin-out cross-validation (base head, random vs contrastive init):")
    results, cms = run_cv(ds, w1_c, b1_c, n_bins)

    print("\n=== cross-validation summary ===")
    means = {}
    for mode in ("random", "contrastive"):
        accs = [r["acc"] for r in results[mode]]
        f1s = [r["f1"] for r in results[mode]]
        means[mode] = float(np.mean(accs)) if accs else 0.0
        print(f"{mode:11s}: mean acc={means[mode]:.4f}  mean macroF1={np.mean(f1s) if f1s else 0.0:.4f}  "
              f"(n_folds={len(accs)})")
        print(f"  confusion matrix (rows=true, cols=pred, classes 0/1/2):\n{cms[mode]}")

    if abs(means["random"] - means["contrastive"]) < 1e-9:
        rng = np.random.RandomState(42)
        winner = rng.choice(["random", "contrastive"])
        print(f"\ntie on mean accuracy -> random tie-break: '{winner}'")
    else:
        winner = "contrastive" if means["contrastive"] > means["random"] else "random"
        print(f"\nwinning init: '{winner}' (mean acc {means[winner]:.4f} vs "
              f"{means['random' if winner == 'contrastive' else 'contrastive']:.4f})")

    # ---- final training on ALL data ----
    print(f"\ntraining final heads on ALL data ({len(ds['x260'])} samples) with '{winner}' init "
          f"where architecturally compatible ...")
    all_x, all_y = ds["x260"], ds["label"]
    tr_idx, val_idx = stratified_split(all_y, val_frac=0.2, seed=7777)
    x_tr, y_tr = all_x[tr_idx], all_y[tr_idx]
    x_val, y_val = all_x[val_idx], all_y[val_idx]

    layers_by_variant = {"base": [260, 64, 3], "small": [260, 32, 3], "deep": [260, 64, 32, 3]}
    seed_offset = {"base": 0, "small": 1, "deep": 2}
    init_weights_by_id = {}
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    for variant_id, layers in layers_by_variant.items():
        head = FlHead(layers)
        use_contrastive = winner == "contrastive" and layers[1] == 64  # base, deep; not small (32-wide)
        if use_contrastive:
            head.Ws[0].assign(w1_c)
            head.bs[0].assign(b1_c)
        elif winner == "contrastive" and layers[1] != 64:
            print(f"  {variant_id}: winning init is 'contrastive' but first hidden layer is "
                  f"{layers[1]}-wide (contrastive proj is 64-wide) -- keeping seed-7 random init")
        epochs = train_supervised(head, x_tr, y_tr, x_val, y_val, seed=3000 + seed_offset[variant_id])
        preds_val = predict(head, x_val)
        acc_val = float(np.mean(preds_val == y_val))
        print(f"  {variant_id}: init={'contrastive' if use_contrastive else 'random'} "
              f"epochs={epochs} val_acc={acc_val:.4f}")
        flat = flatten_weights(head)
        init_weights_by_id[variant_id] = flat
        bin_path = DATA_DIR / f"pretrained_{variant_id}.bin"
        flat.astype("<f4").tofile(bin_path)
        print(f"    wrote {bin_path} ({bin_path.stat().st_size} bytes)")

    print("\nbaking trained weights into exported .tflite files ...")
    written = export_fl_head.export_all(init_weights_by_id=init_weights_by_id)
    for path in written:
        print(f"  wrote {path} ({path.stat().st_size} bytes)")

    print(f"\ndone in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
