"""
Champion/challenger federated network simulation ("Network protocol v2" in
plans/2026-09-12-champion-challenger-network.md).

3 nodes, 6 rounds, driven only through tf.lite.Interpreter signature runners on
the EXPORTED assets (fl_head_base/small/deep.tflite) -- same approach as
fedavg_sim.py, no tf.Module access.

Every node always trains the champion recipe (`base`). Each node additionally
holds exactly one challenger (the real contract's `challengerId` is singular
per node), assigned round-robin / least-populated-first at setup:
  node0 -> small, node1 -> deep, node2 -> noise
`balanced` is exported (it shares fl_head_base.tflite) and its recipe is fully
implemented below, but with only 3 nodes for 4 challengers it is left
unassigned this run -- exactly what the contract's real assignment rule
(least-populated challenger among non-champion variants) would do; a 4th node
joining would pick it up.

Recipes (numpy, outside the graph -- no new ops needed):
  noise    -- Gaussian noise N(0, 0.15) added to x before every train step.
  balanced -- each batch draws an equal share of samples per class, with
              replacement.

Promotion rule:
  netAcc[v] = nVal-weighted mean of this round's held-out accuracies
              (nodes with nVal < 4 excluded; variant eligible only when
              total nVal >= 8)
  wins[v]   = consecutive eligible rounds with netAcc[v] >= netAcc[champion] + 0.03
  promotion at wins[v] >= 2 -> championId = v, all wins reset.

Synthetic data is mildly non-linear so `deep` has a real (not guaranteed)
chance against `base`/`small`: class 2 (Airflow Obstruction) is an XOR-like
interaction of two band groups -- it fires when bandA and bandB disagree in
sign, a boundary a single hyperplane can't draw. Class 1 (Rotor Imbalance)
stays linearly separable, as in fedavg_sim.py.

Exits non-zero only if the champion's final-round netAcc < 0.85.
"""
import pathlib
import sys

import numpy as np
import tensorflow as tf

import variants

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"

N_CLASSES = 3
TRAIN_BATCH = 8
N_NODES = 3
ROUNDS = 6
SAMPLES_PER_CLASS = 34  # per node
PROMOTE_MARGIN = 0.03
PROMOTE_WINS = 2
MIN_NODE_NVAL = 4
MIN_TOTAL_NVAL = 8
HOLDOUT_MOD = 4  # deterministic 25% holdout: local sample index % 4 == 0
FINAL_ACC_THRESHOLD = 0.85

CHAMPION_ID = "base"
CHALLENGER_IDS = ["small", "deep", "noise", "balanced"]
# Static round-robin / least-populated assignment for N_NODES=3 nodes.
NODE_CHALLENGERS = [CHALLENGER_IDS[i % len(CHALLENGER_IDS)] for i in range(N_NODES)]

SEED = 123

# XOR-like class-2 band groups (disjoint from class-1's dims 0:16, 128:144).
BAND_A = slice(60, 66)
BAND_B = slice(200, 206)
BAND_SHIFT = 0.60


def make_signatures(asset_rel_path):
    interpreter = tf.lite.Interpreter(model_path=str(ASSETS_DIR / asset_rel_path))
    return {
        "infer": interpreter.get_signature_runner("infer"),
        "train": interpreter.get_signature_runner("train"),
        "get_weights": interpreter.get_signature_runner("get_weights"),
        "set_weights": interpreter.get_signature_runner("set_weights"),
    }


def gen_samples(n, label, rng):
    """label: 0 Healthy, 1 Rotor Imbalance (linear), 2 Airflow Obstruction (XOR-like).

    Dims 256..259 are the four sensor deltas (accel/gyro/mag-index/mag-rms,
    plans/2026-09-12-sensors-strategies-datasets.md Decision 2), generated as
    small class-dependent shifts on a shared noise floor.
    """
    x = rng.normal(0.0, 0.3, size=(n, 256)).astype(np.float32)
    sensor = rng.normal(0.0, 0.2, size=(n, 4)).astype(np.float32)
    if label == 1:
        x[:, 0:16] += 1.5
        x[:, 128:144] += 0.4
        sensor += np.array([1.0, 0.6, 0.3, 0.2], dtype=np.float32)
    elif label == 2:
        sign = rng.choice([-1.0, 1.0], size=n).astype(np.float32)
        x[:, BAND_A] += (sign * BAND_SHIFT)[:, None]
        x[:, BAND_B] += (-sign * BAND_SHIFT)[:, None]
        sensor += np.array([0.3, 0.2, 0.15, 0.1], dtype=np.float32)
    x_full = np.concatenate([x, sensor], axis=1)
    y = np.zeros((n, N_CLASSES), dtype=np.float32)
    y[:, label] = 1.0
    return x_full, y


def make_node_dataset(n_per_class, rng):
    xs, ys = [], []
    for label in range(N_CLASSES):
        x, y = gen_samples(n_per_class, label, rng)
        xs.append(x)
        ys.append(y)
    x = np.concatenate(xs, axis=0)
    y = np.concatenate(ys, axis=0)
    order = rng.permutation(len(x))
    x, y = x[order], y[order]
    is_val = (np.arange(len(x)) % HOLDOUT_MOD) == 0  # deterministic 25% holdout
    return x[~is_val], y[~is_val], x[is_val], y[is_val]


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


def balanced_batches(x, y, batch_size, rng, n_batches):
    """Each batch samples an equal share of examples per class, with replacement."""
    labels = np.argmax(y, axis=1)
    class_idx = [np.where(labels == c)[0] for c in range(N_CLASSES)]
    base_count = batch_size // N_CLASSES
    counts = [base_count + (1 if c < batch_size % N_CLASSES else 0) for c in range(N_CLASSES)]
    for _ in range(n_batches):
        parts = [rng.choice(class_idx[c], size=cnt, replace=True) for c, cnt in enumerate(counts)]
        idx = np.concatenate(parts)
        rng.shuffle(idx)
        yield x[idx].astype(np.float32), y[idx].astype(np.float32)


def local_train(sigs, x, y, spec, rng):
    n_batches = max(1, -(-len(x) // TRAIN_BATCH))  # ceil(n / batch_size)
    for _ in range(spec.epochs):
        batches = (
            balanced_batches(x, y, TRAIN_BATCH, rng, n_batches)
            if spec.balanced
            else iter_batches(x, y, TRAIN_BATCH, rng)
        )
        for xb, yb in batches:
            if spec.noise_sigma > 0:
                xb = xb + rng.normal(0.0, spec.noise_sigma, size=xb.shape).astype(np.float32)
            sigs["train"](x=xb.astype(np.float32), y=yb)


def evaluate(sigs, x, y):
    if len(x) == 0:
        return -1.0
    correct = 0
    for i in range(len(x)):
        probs = sigs["infer"](x=x[i:i + 1])["probs"][0]
        correct += int(np.argmax(probs) == np.argmax(y[i]))
    return correct / len(x)


def fedavg_merge(contribs, weight_count):
    total = sum(n for _, n in contribs)
    merged = np.zeros(weight_count, dtype=np.float64)
    for w, n in contribs:
        merged += w.astype(np.float64) * (n / total)
    return merged.astype(np.float32)


class Node:
    def __init__(self, node_id, challenger_id, rng):
        self.id = node_id
        self.challenger_id = challenger_id
        self.held = [CHAMPION_ID, challenger_id]
        self.x_train, self.y_train, self.x_val, self.y_val = make_node_dataset(
            SAMPLES_PER_CLASS, rng
        )
        self.n_train = len(self.x_train)
        self.n_val = len(self.x_val)
        self.sigs = {vid: make_signatures(variants.by_id(vid).asset) for vid in self.held}


def main():
    rng = np.random.RandomState(SEED)
    nodes = [Node(i, NODE_CHALLENGERS[i], rng) for i in range(N_NODES)]

    print("node assignments (round-robin / least-populated over the 4 challengers, 3 nodes):")
    for n in nodes:
        print(
            f"  node{n.id}: champion=base challenger={n.challenger_id} "
            f"nTrain={n.n_train} nVal={n.n_val}"
        )
    unassigned = sorted(set(CHALLENGER_IDS) - set(NODE_CHALLENGERS))
    if unassigned:
        print(f"  unassigned this run (would go to a 4th node): {unassigned}")
    print()

    all_ids = [CHAMPION_ID] + CHALLENGER_IDS
    central_weights = {}
    for vid in all_ids:
        spec = variants.by_id(vid)
        tmp_sigs = make_signatures(spec.asset)
        central_weights[vid] = tmp_sigs["get_weights"](dummy=np.zeros([1], np.float32))["w"]

    champion_id = CHAMPION_ID
    wins = {vid: 0 for vid in CHALLENGER_IDS}
    promotions = []
    final_champ_net_acc = None

    col_w = 9
    header = (
        f"{'rnd':>3} | {'champ':>8} | "
        + " ".join(f"{vid:>{col_w}}" for vid in all_ids)
        + " | wins | event"
    )
    print(header)
    print("-" * len(header))

    for rnd in range(1, ROUNDS + 1):
        reports = {vid: [] for vid in all_ids}

        for node in nodes:
            for vid in node.held:
                spec = variants.by_id(vid)
                sigs = node.sigs[vid]
                sigs["set_weights"](w=central_weights[vid])
                local_train(sigs, node.x_train, node.y_train, spec, rng)
                w_local = sigs["get_weights"](dummy=np.zeros([1], np.float32))["w"]
                val_acc = evaluate(sigs, node.x_val, node.y_val)
                reports[vid].append(
                    {"node": node.id, "w": w_local, "n_train": node.n_train,
                     "n_val": node.n_val, "val_acc": val_acc}
                )

        for vid, reps in reports.items():
            if reps:
                contribs = [(r["w"], r["n_train"]) for r in reps]
                central_weights[vid] = fedavg_merge(contribs, variants.by_id(vid).weight_count)

        net_acc = {}
        for vid in all_ids:
            reps = [r for r in reports[vid] if r["n_val"] >= MIN_NODE_NVAL]
            total_nval = sum(r["n_val"] for r in reps)
            if total_nval >= MIN_TOTAL_NVAL:
                net_acc[vid] = sum(r["val_acc"] * r["n_val"] for r in reps) / total_nval
            else:
                net_acc[vid] = None

        event = "-"
        champ_net = net_acc.get(champion_id)
        for vid in CHALLENGER_IDS:
            if vid == champion_id:
                continue
            beats_champion = (
                net_acc[vid] is not None
                and champ_net is not None
                and net_acc[vid] >= champ_net + PROMOTE_MARGIN
            )
            wins[vid] = wins[vid] + 1 if beats_champion else 0
            if wins[vid] >= PROMOTE_WINS:
                champion_id = vid
                wins = {k: 0 for k in wins}
                event = f"PROMOTE {vid}"
                promotions.append((rnd, vid))

        row_accs = " ".join(
            f"{net_acc[vid]:>{col_w}.3f}" if net_acc[vid] is not None else f"{'-':>{col_w}}"
            for vid in all_ids
        )
        wins_str = ",".join(f"{vid}={wins[vid]}" for vid in CHALLENGER_IDS)
        print(f"{rnd:>3} | {champion_id:>8} | {row_accs} | {wins_str} | {event}")

        if rnd == ROUNDS:
            final_champ_net_acc = net_acc.get(champion_id)

    print()
    if promotions:
        for rnd, vid in promotions:
            print(f"promotion: '{vid}' promoted to champion in round {rnd}")
    else:
        print("no promotion fired over 6 rounds")
    print(f"final champion: '{champion_id}', netAcc={final_champ_net_acc}")

    if final_champ_net_acc is None or final_champ_net_acc < FINAL_ACC_THRESHOLD:
        print(f"FAIL: final champion netAcc {final_champ_net_acc} < {FINAL_ACC_THRESHOLD}")
        sys.exit(1)
    print("PASS")


if __name__ == "__main__":
    main()
