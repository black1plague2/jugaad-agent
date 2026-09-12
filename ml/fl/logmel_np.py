"""
Pure numpy/scipy reimplementation of ml/logmel_reference.py's log_mel() and
feature_256(), for environments without librosa (this venv: TF 2.16.1 + numpy
+ scipy only, no librosa -- see mafaulda_ingest.py's module docstring for why).

Reproduces librosa's algorithm exactly rather than approximating it:
  - STFT: n_fft=2048, hop=1024, periodic Hann window, center=False (no padding;
    frame i covers samples [i*hop, i*hop+n_fft)) -- same as
    librosa.stft(y, n_fft=2048, hop_length=1024, window="hann", center=False).
  - Mel filterbank: HTK mel scale (mel = 2595*log10(1+f/700)), triangular
    filters, norm=None (no area normalization) -- same construction as
    librosa.filters.mel(sr=44100, n_fft=2048, n_mels=128, fmin=20, fmax=11000,
    htk=True, norm=None).

Constants are hand-copied from logmel_reference.py (not imported -- that module
does `import librosa` at top level, which would make this module unimportable
too in a librosa-less venv). Keep these in lock-step with logmel_reference.py
if either changes.
"""
import numpy as np

SR = 44100
N_FFT = 2048
HOP = 1024
N_MELS = 128
SPEC_FRAMES = 128
FMIN = 20.0
FMAX = 11000.0
CLIP_SAMPLES = SR * 3          # 132300
LOG_FLOOR = 1e-6


def _hz_to_mel_htk(f):
    return 2595.0 * np.log10(1.0 + np.asarray(f, dtype=np.float64) / 700.0)


def _mel_to_hz_htk(m):
    return 700.0 * (10.0 ** (np.asarray(m, dtype=np.float64) / 2595.0) - 1.0)


def _mel_filterbank(sr, n_fft, n_mels, fmin, fmax):
    """HTK mel filterbank, norm=None -- matches librosa.filters.mel exactly."""
    n_bins = n_fft // 2 + 1
    fft_freqs = np.linspace(0.0, sr / 2.0, n_bins)

    mel_min, mel_max = _hz_to_mel_htk(fmin), _hz_to_mel_htk(fmax)
    mel_pts = np.linspace(mel_min, mel_max, n_mels + 2)
    hz_pts = _mel_to_hz_htk(mel_pts)

    fdiff = np.diff(hz_pts)
    ramps = np.subtract.outer(hz_pts, fft_freqs)  # (n_mels+2, n_bins)

    weights = np.zeros((n_mels, n_bins), dtype=np.float64)
    for i in range(n_mels):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        weights[i] = np.maximum(0.0, np.minimum(lower, upper))
    return weights.astype(np.float32)


_MEL_BASIS = _mel_filterbank(SR, N_FFT, N_MELS, FMIN, FMAX)

_HANN = (0.5 - 0.5 * np.cos(2.0 * np.pi * np.arange(N_FFT) / N_FFT)).astype(np.float32)


def _stft_power(y):
    """|STFT|^2, center=False, periodic Hann -- (n_bins, n_frames)."""
    n = len(y)
    n_frames = 1 + (n - N_FFT) // HOP if n >= N_FFT else 0
    n_bins = N_FFT // 2 + 1
    power = np.empty((n_bins, max(n_frames, 0)), dtype=np.float32)
    for i in range(n_frames):
        start = i * HOP
        frame = y[start:start + N_FFT] * _HANN
        spec = np.fft.rfft(frame, n=N_FFT)
        power[:, i] = (np.abs(spec) ** 2).astype(np.float32)
    return power


def log_mel(y: np.ndarray) -> np.ndarray:
    """float waveform in [-1, 1] -> (N_MELS, SPEC_FRAMES) float32 log-mel image.

    Same contract as logmel_reference.log_mel; verified to match it to
    float32 precision when librosa is available (see test_logmel_np.py).
    """
    if len(y) < CLIP_SAMPLES:
        y = np.pad(y, (0, CLIP_SAMPLES - len(y)))
    y = y[:CLIP_SAMPLES].astype(np.float32)

    power = _stft_power(y)
    mel = _MEL_BASIS @ power
    mel = np.log(mel + LOG_FLOOR)

    if mel.shape[1] < SPEC_FRAMES:
        pad = SPEC_FRAMES - mel.shape[1]
        mel = np.pad(mel, ((0, 0), (0, pad)), mode="edge")
    mel = mel[:, :SPEC_FRAMES]
    return mel.astype(np.float32)


def feature_256(mel: np.ndarray) -> np.ndarray:
    mean = mel.mean(axis=1)
    std = mel.std(axis=1)
    return np.concatenate([mean, std]).astype(np.float32)


if __name__ == "__main__":
    rng = np.random.RandomState(0)
    y = rng.uniform(-1, 1, size=CLIP_SAMPLES).astype(np.float32)
    mel = log_mel(y)
    feat = feature_256(mel)
    print("log-mel:", mel.shape, "feature-256:", feat.shape)
