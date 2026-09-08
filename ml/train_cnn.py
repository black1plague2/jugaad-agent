"""
Train FaultCNN on a folder of labelled 3-second clips.

Expected layout (wav/flac/ogg, any sample rate, mono or stereo):

    ml/data/
      Healthy/             *.wav
      Rotor Imbalance/     *.wav
      Airflow Obstruction/ *.wav

Usage:
    python train_cnn.py --data data --epochs 40 --out out/fault_cnn.pt

Notes
-----
* Features are computed with logmel_reference.log_mel + standardise, so the
  training distribution matches exactly what the phone feeds ExecuTorch.
* If you only have healthy audio, you can still ship: the app's anomaly path
  needs no model. Synthesise the two fault classes by augmentation (low-freq
  tone for imbalance, band-limited noise for airflow) — see `--synth`.
"""
import argparse
import glob
import os
import random

import numpy as np
import soundfile as sf
import librosa
import torch
from torch.utils.data import Dataset, DataLoader

from logmel_reference import log_mel, standardise, SR, CLIP_SAMPLES
from model import FaultCNN, LABELS


def load_audio(path):
    y, sr = sf.read(path)
    if y.ndim > 1:
        y = y.mean(axis=1)
    if sr != SR:
        y = librosa.resample(y, orig_sr=sr, target_sr=SR)
    return y.astype(np.float32)


def random_crop(y):
    if len(y) <= CLIP_SAMPLES:
        return np.pad(y, (0, CLIP_SAMPLES - len(y)))
    start = random.randint(0, len(y) - CLIP_SAMPLES)
    return y[start:start + CLIP_SAMPLES]


def synth_fault(y, kind):
    """Cheap physical-ish augmentation to bootstrap fault classes from healthy audio."""
    t = np.arange(CLIP_SAMPLES) / SR
    y = random_crop(y).copy()
    if kind == 1:  # rotor imbalance: strong 1x running tone ~30 Hz + harmonics
        f0 = random.uniform(22, 45)
        y += 0.20 * np.sin(2 * np.pi * f0 * t) + 0.08 * np.sin(2 * np.pi * 2 * f0 * t)
    elif kind == 2:  # airflow obstruction: band-limited hiss 1.5-5 kHz
        noise = np.random.randn(CLIP_SAMPLES).astype(np.float32)
        b = librosa.effects.preemphasis(noise)
        y += 0.15 * (b / (np.abs(b).max() + 1e-6))
    return np.clip(y, -1.0, 1.0)


class ClipDataset(Dataset):
    def __init__(self, items, synth=False, train=True):
        self.items = items
        self.synth = synth
        self.train = train

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        path, label = self.items[i]
        y = load_audio(path)
        if self.synth and label != 0:
            y = synth_fault(y, label)
        else:
            y = random_crop(y)
        if self.train:
            y = y * random.uniform(0.8, 1.2)                       # gain jitter
            if random.random() < 0.3:
                y = y + 0.002 * np.random.randn(len(y)).astype(np.float32)
        mel = standardise(log_mel(y))
        return torch.from_numpy(mel)[None, :, :], label


def gather(data_dir, synth):
    items = []
    for idx, name in enumerate(LABELS):
        files = []
        for ext in ("wav", "flac", "ogg", "mp3"):
            files += glob.glob(os.path.join(data_dir, name, f"*.{ext}"))
        if not files and synth and idx != 0:
            # borrow healthy files, label as the fault, augment at load time
            files = []
            for ext in ("wav", "flac", "ogg", "mp3"):
                files += glob.glob(os.path.join(data_dir, LABELS[0], f"*.{ext}"))
        items += [(f, idx) for f in files]
    random.shuffle(items)
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--synth", action="store_true", help="synthesise fault classes from healthy audio")
    ap.add_argument("--out", default="out/fault_cnn.pt")
    args = ap.parse_args()

    items = gather(args.data, args.synth)
    if not items:
        raise SystemExit(f"No audio found under {args.data}/<class>/")
    split = int(len(items) * 0.85)
    tr, va = items[:split], items[split:]
    print(f"{len(tr)} train / {len(va)} val clips")

    dl_tr = DataLoader(ClipDataset(tr, args.synth, True), batch_size=args.batch, shuffle=True, num_workers=2)
    dl_va = DataLoader(ClipDataset(va, args.synth, False), batch_size=args.batch, num_workers=2)

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    net = FaultCNN(len(LABELS)).to(dev)
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, args.epochs)
    lossf = torch.nn.CrossEntropyLoss()

    best = 0.0
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    for ep in range(args.epochs):
        net.train()
        for x, y in dl_tr:
            x, y = x.to(dev), y.to(dev)
            opt.zero_grad()
            loss = lossf(net(x), y)
            loss.backward()
            opt.step()
        sched.step()

        net.eval()
        correct = total = 0
        with torch.no_grad():
            for x, y in dl_va:
                x, y = x.to(dev), y.to(dev)
                correct += (net(x).argmax(1) == y).sum().item()
                total += y.numel()
        acc = correct / max(total, 1)
        print(f"epoch {ep + 1:02d}  val_acc {acc:.3f}")
        if acc >= best:
            best = acc
            torch.save({"state_dict": net.state_dict(), "labels": LABELS}, args.out)
    print(f"best val_acc {best:.3f} -> {args.out}")


if __name__ == "__main__":
    main()
