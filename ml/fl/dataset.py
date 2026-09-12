"""
Turns ml/data/mafaulda/features.npz (raw per-clip features from
mafaulda_ingest.py) into the 260-d baseline-relative training set described
in plans/2026-09-12-sensors-strategies-datasets.md Decision 6/item 3 of the
brief:

  1. Group files into 6 "virtual machines" by rpm bin (equal-width bins over
     the observed tachometer-derived rpm range).
  2. Per bin, baseline = mean of 5 normal (Healthy) clips' 256-d features and
     mean accel index.
  3. Every real clip in the bin becomes
       x = [feat - baselineFeat (256), (accelIdx - baseAccel) * 10, 0, 0, 0]
     (260-d; gyro/mag dims are 0 -- MAFAULDA has no gyro/mag channel).
  4. Synthetic class-2 (Airflow Obstruction, no public data for this class --
     see ml/data/DATASETS.md) samples are generated with network_sim.py's
     generator and round-robin-assigned to bins so every leave-one-bin-out
     fold still sees class 2.
"""
import pathlib

import numpy as np

import network_sim

DATA_DIR = pathlib.Path(__file__).resolve().parents[1] / "data" / "mafaulda"
FEATURES_PATH = DATA_DIR / "features.npz"

N_BINS = 6
N_BASELINE_CLIPS = 5
ACCEL_DELTA_SCALE = 10.0


def load_features(path=FEATURES_PATH):
    d = np.load(path, allow_pickle=False)
    return {
        "X256": d["X256"], "accelIndex": d["accelIndex"], "label": d["label"],
        "rpm": d["rpm"], "mass": d["mass"], "filename": d["filename"],
    }


def compute_bins(rpm, n_bins=N_BINS):
    edges = np.linspace(rpm.min(), rpm.max(), n_bins + 1)
    bin_idx = np.clip(np.digitize(rpm, edges[1:-1], right=False), 0, n_bins - 1)
    return edges, bin_idx


def build_baselines(X256, accelIndex, label, bin_idx, n_bins=N_BINS, n_baseline=N_BASELINE_CLIPS):
    """Per bin: mean of up to n_baseline Healthy (label==0) clips, deterministic
    (lowest sample index first). Returns (feat_baseline[n_bins,256], accel_baseline[n_bins])
    and prints a warning for any bin with fewer than n_baseline Healthy clips."""
    feat_baseline = np.zeros((n_bins, X256.shape[1]), dtype=np.float32)
    accel_baseline = np.zeros(n_bins, dtype=np.float32)
    for b in range(n_bins):
        idx = np.where((bin_idx == b) & (label == 0))[0]
        if len(idx) == 0:
            print(f"  WARNING: bin {b} has zero Healthy clips; baseline stays 0")
            continue
        if len(idx) < n_baseline:
            print(f"  WARNING: bin {b} has only {len(idx)} Healthy clips (< {n_baseline}); using all of them")
        chosen = idx[:n_baseline]
        feat_baseline[b] = X256[chosen].mean(axis=0)
        accel_baseline[b] = accelIndex[chosen].mean()
    return feat_baseline, accel_baseline


def build_real_x260(X256, accelIndex, bin_idx, feat_baseline, accel_baseline):
    n = len(X256)
    x260 = np.zeros((n, 260), dtype=np.float32)
    for i in range(n):
        b = bin_idx[i]
        x260[i, :256] = X256[i] - feat_baseline[b]
        x260[i, 256] = (accelIndex[i] - accel_baseline[b]) * ACCEL_DELTA_SCALE
        # dims 257..259 (gyro/mag) stay 0 -- no such channel in MAFAULDA
    return x260


def build_synthetic_class2(n_synthetic, n_bins=N_BINS, seed=99):
    """Reuses network_sim.py's XOR-like class-2 generator so synthetic Airflow
    Obstruction samples live in the same 260-d feature geometry as the rest
    of the champion/challenger simulation. Round-robin-assigned to bins."""
    rng = np.random.RandomState(seed)
    x, y = network_sim.gen_samples(n_synthetic, 2, rng)
    bin_idx = np.arange(n_synthetic) % n_bins
    label = np.full(n_synthetic, 2, dtype=np.int64)
    return x.astype(np.float32), label, bin_idx


def build_dataset(features_path=FEATURES_PATH, n_bins=N_BINS, synthetic_seed=99):
    """Returns dict: x260, label, bin_idx, source ('real'/'synthetic'), plus
    bin_edges and per-bin/per-class counts for reporting."""
    d = load_features(features_path)
    bin_edges, bin_idx = compute_bins(d["rpm"], n_bins)
    print(f"rpm bin edges: {[round(float(e), 1) for e in bin_edges]}")

    feat_baseline, accel_baseline = build_baselines(d["X256"], d["accelIndex"], d["label"], bin_idx, n_bins)
    x_real = build_real_x260(d["X256"], d["accelIndex"], bin_idx, feat_baseline, accel_baseline)
    label_real = d["label"].astype(np.int64)
    source_real = np.array(["real"] * len(x_real))

    n_class1 = int((label_real == 1).sum())
    x_syn, label_syn, bin_syn = build_synthetic_class2(n_class1, n_bins, synthetic_seed)
    source_syn = np.array(["synthetic"] * len(x_syn))

    x260 = np.concatenate([x_real, x_syn], axis=0)
    label = np.concatenate([label_real, label_syn], axis=0)
    bin_all = np.concatenate([bin_idx, bin_syn], axis=0)
    source = np.concatenate([source_real, source_syn], axis=0)

    counts = {}
    for b in range(n_bins):
        row = {}
        for cls in (0, 1, 2):
            row[cls] = int(((bin_all == b) & (label == cls)).sum())
        counts[b] = row

    return {
        "x260": x260, "label": label, "bin_idx": bin_all, "source": source,
        "bin_edges": bin_edges, "counts": counts, "rpm": d["rpm"],
        "filename": d["filename"],
    }


if __name__ == "__main__":
    ds = build_dataset()
    print(f"total samples: {len(ds['x260'])}")
    for b, row in ds["counts"].items():
        print(f"  bin {b}: healthy={row[0]} imbalance={row[1]} airflow(synthetic)={row[2]}")
