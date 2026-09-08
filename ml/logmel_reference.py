"""
Reference log-mel front-end — the ground truth the on-device Kotlin
implementation (app/.../ml/signal/LogMelSpectrogram.kt) is matched against.

Keep these constants in lock-step with app/build.gradle.kts buildConfigField
values and app/.../core/Constants.kt.
"""
import numpy as np
import librosa

SR          = 44100
N_FFT       = 2048
HOP         = 1024
N_MELS      = 128
SPEC_FRAMES = 128
FMIN        = 20.0
FMAX        = 11000.0
CLIP_SAMPLES = SR * 3          # 132300
LOG_FLOOR   = 1e-6

# HTK mel, no filter normalisation — mirrors MelFilterBank.kt.
_MEL_BASIS = librosa.filters.mel(
    sr=SR, n_fft=N_FFT, n_mels=N_MELS, fmin=FMIN, fmax=FMAX, htk=True, norm=None
)


def log_mel(y: np.ndarray) -> np.ndarray:
    """float waveform in [-1, 1] -> (N_MELS, SPEC_FRAMES) float32 log-mel image."""
    if len(y) < CLIP_SAMPLES:
        y = np.pad(y, (0, CLIP_SAMPLES - len(y)))
    y = y[:CLIP_SAMPLES].astype(np.float32)

    stft = librosa.stft(
        y, n_fft=N_FFT, hop_length=HOP, window="hann", center=False
    )
    power = (np.abs(stft) ** 2).astype(np.float32)          # (1025, frames)
    mel = _MEL_BASIS @ power                                # (128, frames)
    mel = np.log(mel + LOG_FLOOR)

    # Fix the time axis to exactly SPEC_FRAMES (edge-pad / truncate).
    if mel.shape[1] < SPEC_FRAMES:
        pad = SPEC_FRAMES - mel.shape[1]
        mel = np.pad(mel, ((0, 0), (0, pad)), mode="edge")
    mel = mel[:, :SPEC_FRAMES]
    return mel.astype(np.float32)


def feature_256(mel: np.ndarray) -> np.ndarray:
    """(128, 128) log-mel -> 256-d [per-band mean || per-band std]  (AnomalyScorer input)."""
    mean = mel.mean(axis=1)
    std = mel.std(axis=1)
    return np.concatenate([mean, std]).astype(np.float32)


def standardise(mel: np.ndarray) -> np.ndarray:
    """Per-image standardisation used as the CNN input (see ExecuTorchFaultClassifier.kt)."""
    m, s = mel.mean(), mel.std()
    return ((mel - m) / max(s, 1e-6)).astype(np.float32)


if __name__ == "__main__":
    import sys, soundfile as sf
    y, sr = sf.read(sys.argv[1])
    if y.ndim > 1:
        y = y.mean(axis=1)
    if sr != SR:
        y = librosa.resample(y, orig_sr=sr, target_sr=SR)
    mel = log_mel(y)
    print("log-mel:", mel.shape, "min %.3f max %.3f" % (mel.min(), mel.max()))
    print("feature-256:", feature_256(mel).shape)
