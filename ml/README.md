# Jugaad Agent — model training & export

The Android app ships and demos **without any of these files** (anomaly score +
template advice run on pure Kotlin + JTransforms). Everything here is for the
optional CNN and LLM paths.

## 0. Environment

```bash
cd ml
python -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## 1. Sanity-check the front-end matches the phone

```bash
python logmel_reference.py path/to/a_clip.wav
```

`LogMelSpectrogram.kt` is written to produce the same 128×128 image. If you change
`N_FFT / HOP / N_MELS / FMIN / FMAX`, change it in **three** places:
`app/build.gradle.kts` (buildConfigField), `core/Constants.kt`, and this file.

## 2. Train the CNN

Put labelled 3-second clips under `ml/data/<class>/`:

```
data/Healthy/*.wav
data/Rotor Imbalance/*.wav
data/Airflow Obstruction/*.wav
```

```bash
python train_cnn.py --data data --epochs 40 --out out/fault_cnn.pt
```

Only have healthy recordings? Bootstrap the fault classes with augmentation:

```bash
python train_cnn.py --data data --synth --epochs 40 --out out/fault_cnn.pt
```

## 3. Export to ExecuTorch

**CPU (XNNPACK) — always do this one:**

```bash
python export_executorch.py --ckpt out/fault_cnn.pt --backend xnnpack \
  --out ../app/src/main/assets/models/fault_cnn_xnnpack.pte
```

**NPU (QNN / Snapdragon 8 Elite Gen 5 HTP) — needs the Qualcomm QAIRT SDK:**

```bash
export QNN_SDK_ROOT=/opt/qcom/aistack/qairt/<version>
python export_executorch.py --ckpt out/fault_cnn.pt --backend qnn --soc SM8850 \
  --out ../app/src/main/assets/models/fault_cnn_qnn.pte
```

## 4. Gemma 3 1B (advice)

Download `gemma3-1b-it-int4.task` (MediaPipe LLM Inference bundle, 4-bit) from the
LiteRT / MediaPipe model page or convert with the AI Edge Torch converter, then:

```bash
cp gemma3-1b-it-int4.task ../app/src/main/assets/models/
```

## 5. Enable in the app

`gradle.properties`:

```properties
jugaad.executorch.enabled=true   # after adding the .pte file(s)
jugaad.gemma.enabled=true        # after adding the .task file
```

Rebuild. On launch, `logcat -s JUGAAD` prints which backend loaded; the QNN path
also emits `[Qnn...]` lines from ExecuTorch — that is the NPU proof for the demo.
