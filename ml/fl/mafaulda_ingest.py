"""
MAFAULDA ingest: normal.zip (Healthy) + imbalance.zip (Rotor Imbalance) ->
ml/data/mafaulda/features.npz. See plans/2026-09-12-sensors-strategies-datasets.md
Decision 6 and ml/data/DATASETS.md for provenance/URLs/licence.

Column order (confirmed against the first normal/*.csv file, 250000 rows x 8
cols, comma-separated, no header): tachometer(0), underhang accel x/y/z(1-3),
overhang accel x/y/z(4-6), microphone(7) -- matches DATASETS.md's channel
description and the brief's stated order exactly. Column 0 has the widest,
most bimodal swing (min -1.13 / max 5.11 V, consistent with a Hall-sensor
tachometer pulse train riding a ~0 V baseline); columns 1-3 and 4-6 have the
graduated x>y>z variance pattern of two triaxial accelerometer mounts.

Does NOT use ml/logmel_reference.py directly: that module does `import
librosa` at the top, and librosa is deliberately not installed in this venv
(brief: "TF 2.16.1, numpy, pytest installed; pip install scipy if needed, no
librosa"). logmel_np.py reimplements the same STFT + HTK-mel-filterbank +
log + mean/std math in pure numpy/scipy so the numbers match without the
dependency -- see that module's docstring.

Per file:
  microphone -> resample_poly(441, 500) 50kHz->44.1kHz -> first 3s (132300
    samples) -> scale to [-1,1] by the resampled file's own max abs -> 256-d
    log-mel feature (logmel_np).
  underhang accel x/y/z -> resample_poly(8, 1000) 50kHz->400Hz (built-in
    anti-alias FIR) -> first 3s (1200 samples) -> magnitude -> mirrored
    ImuVibrationIndex.compute (linear detrend, Hann window, rFFT, 5-60Hz
    energy fraction). NOTE: app/.../ml/signal/{Detrend,HannWindow}.kt do not
    exist yet in this repo (referenced by ImuVibrationIndex.kt but owned by
    C2's sensor workstream) -- mirrored here as the standard textbook
    definitions (least-squares linear detrend == scipy.signal.detrend, and a
    *symmetric* Hann window, numpy.hanning) since the exact source couldn't
    be read. Flagged as an assumption in the run report.
  tachometer -> dominant FFT frequency (bin of max power, DC excluded) over
    the full 5s at 50kHz -> rpm = hz * 60.
"""
import io
import pathlib
import re
import sys
import time
import zipfile

import numpy as np
import pandas as pd
from scipy.signal import resample_poly, detrend

import logmel_np

DATA_DIR = pathlib.Path(__file__).resolve().parents[1] / "data" / "mafaulda"
OUT_PATH = DATA_DIR / "features.npz"

SRC_SR = 50_000.0
MIC_SR = 44_100
ACCEL_SR = SRC_SR * 8 / 1000  # 400.0 Hz, resample_poly(8, 1000)
CLIP_S = 3.0
MIC_CLIP_SAMPLES = int(MIC_SR * CLIP_S)      # 132300
ACCEL_CLIP_SAMPLES = int(ACCEL_SR * CLIP_S)  # 1200

IMU_BAND_LOW_HZ = 5.0
IMU_BAND_HIGH_HZ = 60.0

COL_TACH = 0
COL_UNDERHANG = [1, 2, 3]
COL_OVERHANG = [4, 5, 6]
COL_MIC = 7

MASS_RE = re.compile(r"([\d.]+)g")


def imu_vibration_index(accel_mag, sample_rate_hz, low_hz=IMU_BAND_LOW_HZ, high_hz=IMU_BAND_HIGH_HZ):
    """Mirrors app/.../ml/signal/ImuVibrationIndex.kt's compute(): linear
    detrend -> Hann window -> real FFT -> band-energy / total-energy (DC bin
    excluded from both sums), fraction in [0, 1]."""
    n = len(accel_mag)
    if n < 8 or sample_rate_hz <= 0:
        return 0.0
    x = detrend(accel_mag, type="linear")
    w = np.hanning(n)  # symmetric Hann (see module docstring's assumption note)
    x = x * w
    spec = np.fft.rfft(x)
    power = np.abs(spec) ** 2
    bin_hz = sample_rate_hz / n
    hz = np.arange(len(power)) * bin_hz
    total = power[1:].sum()  # exclude DC (k=0), matches Kotlin's `if (k==0) continue`
    if total <= 0:
        return 0.0
    band_mask = (hz >= low_hz) & (hz <= high_hz)
    band_mask[0] = False
    band = power[band_mask].sum()
    return float(np.clip(band / total, 0.0, 1.0))


def dominant_freq_hz(sig, sample_rate_hz):
    x = np.asarray(sig, dtype=np.float64) - np.mean(sig)
    spec = np.fft.rfft(x)
    power = np.abs(spec) ** 2
    power[0] = 0.0
    k = int(np.argmax(power))
    return k * sample_rate_hz / len(sig)


def process_file(raw_bytes, label, mass):
    df = pd.read_csv(io.BytesIO(raw_bytes), header=None)
    arr = df.to_numpy(dtype=np.float64)
    if arr.shape[1] != 8:
        raise ValueError(f"expected 8 columns, got {arr.shape[1]}")

    tach = arr[:, COL_TACH]
    rpm = dominant_freq_hz(tach, SRC_SR) * 60.0

    mic = arr[:, COL_MIC]
    mic_rs = resample_poly(mic, 441, 500)  # 50kHz -> 44.1kHz
    mic_clip = mic_rs[:MIC_CLIP_SAMPLES]
    if len(mic_clip) < MIC_CLIP_SAMPLES:
        mic_clip = np.pad(mic_clip, (0, MIC_CLIP_SAMPLES - len(mic_clip)))
    peak = np.max(np.abs(mic_rs)) if len(mic_rs) else 0.0
    mic_norm = (mic_clip / peak).astype(np.float32) if peak > 0 else mic_clip.astype(np.float32)
    mel = logmel_np.log_mel(mic_norm)
    feat256 = logmel_np.feature_256(mel)

    axes = []
    for c in COL_UNDERHANG:
        axes.append(resample_poly(arr[:, c], 8, 1000))  # 50kHz -> ~400Hz, anti-aliased
    ax, ay, az = axes
    mag = np.sqrt(ax ** 2 + ay ** 2 + az ** 2)
    mag_clip = mag[:ACCEL_CLIP_SAMPLES]
    if len(mag_clip) < ACCEL_CLIP_SAMPLES:
        mag_clip = np.pad(mag_clip, (0, ACCEL_CLIP_SAMPLES - len(mag_clip)))
    accel_idx = imu_vibration_index(mag_clip, ACCEL_SR)

    return feat256, accel_idx, rpm


def iter_members(zip_path, label):
    with zipfile.ZipFile(zip_path) as zf:
        bad = zf.testzip()
        if bad is not None:
            raise RuntimeError(f"{zip_path}: corrupt member {bad}")
        for info in zf.infolist():
            if info.is_dir() or not info.filename.lower().endswith(".csv"):
                continue
            m = MASS_RE.search(info.filename)
            mass = float(m.group(1)) if m else 0.0
            with zf.open(info) as f:
                raw = f.read()
            yield info.filename, raw, mass


EXPECTED_SIZES = {"normal.zip": 325_307_137, "imbalance.zip": 2_212_894_986}


def _ready(path):
    """True only if the zip is fully downloaded (size match) -- an in-progress
    partial download has no valid end-of-central-directory record yet, and
    processing a truncated imbalance.zip is exactly the "too slow" fallback
    the brief allows (process what completed, say so), not a silent partial
    read of a corrupt archive."""
    if not path.exists():
        return False
    expected = EXPECTED_SIZES.get(path.name)
    actual = path.stat().st_size
    if expected is not None and actual < expected:
        print(f"WARNING: {path.name} is {actual}/{expected} bytes (still downloading or truncated) -- skipping this run")
        return False
    return True


def main():
    normal_zip = DATA_DIR / "normal.zip"
    imbalance_zip = DATA_DIR / "imbalance.zip"

    jobs = []
    if _ready(normal_zip):
        jobs.append((normal_zip, 0))
    else:
        print(f"WARNING: {normal_zip} not ready, skipping Healthy class")
    if _ready(imbalance_zip):
        jobs.append((imbalance_zip, 1))
    else:
        print(f"WARNING: {imbalance_zip} not ready, skipping Rotor Imbalance class")

    feats, accel_idxs, labels, rpms, masses, filenames = [], [], [], [], [], []
    t0 = time.time()
    n_done = 0
    n_failed = 0
    for zip_path, label in jobs:
        print(f"-- {zip_path.name} (label={label}) --")
        for filename, raw, mass in iter_members(zip_path, label):
            try:
                feat256, accel_idx, rpm = process_file(raw, label, mass)
            except Exception as e:
                n_failed += 1
                print(f"  FAILED {filename}: {e}")
                continue
            feats.append(feat256)
            accel_idxs.append(accel_idx)
            labels.append(label)
            rpms.append(rpm)
            masses.append(mass)
            filenames.append(filename)
            n_done += 1
            if n_done % 25 == 0:
                elapsed = time.time() - t0
                print(f"  {n_done} files processed ({elapsed:.0f}s elapsed)")

    if not feats:
        print("no files processed, aborting")
        sys.exit(1)

    X256 = np.stack(feats).astype(np.float32)
    accelIndex = np.array(accel_idxs, dtype=np.float32)
    label = np.array(labels, dtype=np.int64)
    rpm = np.array(rpms, dtype=np.float32)
    mass = np.array(masses, dtype=np.float32)
    filename = np.array(filenames)

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        OUT_PATH, X256=X256, accelIndex=accelIndex, label=label, rpm=rpm,
        mass=mass, filename=filename,
    )

    print()
    print(f"processed {n_done} files ok, {n_failed} failed, elapsed {time.time() - t0:.0f}s")
    for lbl, name in [(0, "Healthy"), (1, "Rotor Imbalance")]:
        n = int((label == lbl).sum())
        print(f"  class {lbl} ({name}): {n} files")
    print(f"rpm range: {rpm.min():.1f} - {rpm.max():.1f}")
    print(f"wrote {OUT_PATH} ({OUT_PATH.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
