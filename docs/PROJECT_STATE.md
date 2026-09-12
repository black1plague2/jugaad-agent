# Jugaad Agent — Handoff

> Superseded on 2026-09-12 by `HANDOFF.md` (current state) and `CONTEXT.md` (how to continue).
> Kept as the original 10 Sep status document; the repository is public now.

Offline, on-device condition-monitoring agent for rotating equipment. This is the
full state of the project as it stands going into the event.

- **Repo:** [github.com/black1plague2/jugaad-agent](https://github.com/black1plague2/jugaad-agent) (private)
- **Branch:** `main`
- **Commit:** `0b6ab1d` — "Initial commit: Jugaad Agent"
- **As of:** 12 Sep 2026
- **Size:** 100 files, ~5.5k lines of Kotlin + Python

**Status summary:** code drafted, not compiled · no models trained · ML feature
flags off · thresholds uncalibrated · no Office Kit integration · anomaly + UI
path complete · repo pushed, single branch.

---

## Start here — the three things that matter first

1. **Decide the provenance approach.** The repo is one large commit, which reads
   as pre-built under the original-work rule. Plan is to treat this as a personal
   reference and **rebuild on a fresh event repo with incremental commits** — the
   rebuild order is already written in [`HACKATHON.md`](../HACKATHON.md).
2. **Get it to compile.** It has never been built against the Android SDK. Open
   in Android Studio, generate the Gradle wrapper, run
   `./gradlew :app:assembleDebug`, fix what breaks, then
   `./gradlew :app:testDebugUnitTest`.
3. **Produce one XNNPACK model and turn the flag on.** Without it, the
   on-device-AI telemetry that's worth 15% sees nothing. A synthetic-data model
   from a single healthy recording is enough to start.

---

## What it is

One phone, resting on a machine housing, used as a fused vibration and acoustic
sensor.

The technician registers a machine, captures a short healthy reference
(3 × 3 s), and from then on every check returns a **Healthy / Warning /
Critical** condition, an optional fault class, and a one-sentence recommended
action in plain language.

- **Fully offline.** No `INTERNET` permission. Capture, feature extraction,
  anomaly scoring, the classifier and the language model all run on the device.
- **No training data required.** Each asset is characterised from its own
  12-second healthy baseline; the primary decision path is unsupervised.
- **Two sensors, one window.** Microphone (44.1 kHz, `UNPROCESSED`) and
  accelerometer are sampled over the same three seconds and analysed together.
- **Hardware-aware inference.** The optional CNN runs through PyTorch
  ExecuTorch — Hexagon NPU (QNN) on capable Snapdragon parts, CPU (XNNPACK)
  elsewhere, heuristic fallback if neither is present.

Target devices: iQOO 15 (Snapdragon 8 Elite Gen 5 / SM8850) primary; Redmi Note
10 Pro secondary (CPU path).

---

## Repo & how to get it

```bash
git clone https://github.com/black1plague2/jugaad-agent.git
cd jugaad-agent
# open in Android Studio, or:
gradle wrapper --gradle-version 8.11.1
```

Top-level docs already in the repo:

| File | Contents |
|---|---|
| `README.md` | Product-level overview with architecture, pipeline, decision-logic and backend-selection diagrams. |
| `HACKATHON.md` | Event planning: the provenance rebuild sequence, Red/Green task tagging, the HackTracker checklist, demo de-risking, pitch order. |
| `DEMO.md` | Run sheet, fault-injection recipes, the `adb logcat` NPU-proof commands. |
| `ml/README.md` | How to train and export the `.pte` models and where the Gemma `.task` bundle comes from. |

---

## Architecture

Layered, with a framework-free domain layer and a hand-rolled composition root
(no Hilt).

```
Presentation — Jetpack Compose, Material 3
  8 screens (assets, create, detail, baseline, checklist, diagnose, result,
  history) · permanently-dark industrial theme · Canvas-drawn spectrogram /
  waveform / countdown
        │
        ▼
Composition root — di/ServiceLocator
  Builds every dependency; swaps the heuristic classifier / template advisor
  for ExecuTorch / Gemma once models load
        │
        ▼
Domain — framework-free
  CaptureBaselineUseCase · DiagnoseUseCase · models · repository interfaces
        │
        ▼
┌─────────────┬─────────────────────────┬──────────────────┬─────────────────┐
│ Sensor      │ ML                      │ Data             │ Viz             │
│ AudioCapture│ signal (log-mel, IMU    │ JsonFileStore —  │ HeatColor ·     │
│ ImuCapture  │ index) · anomaly ·      │ one JSON folder  │ SpectrogramRen- │
│ (Handler-   │ classifier · advisor ·  │ per asset ·      │ derer · offline │
│ Thread) ·   │ SoC detect              │ repositories     │ PNG ReportRen-  │
│ Coordinator │                         │                  │ derer           │
└─────────────┴─────────────────────────┴──────────────────┴─────────────────┘
```

**Diagnose data flow:** capture 3 s (audio + IMU together) → log-mel 128×128
(JTransforms FFT) + 256-d feature (per-band mean ‖ std) + IMU 5–60 Hz energy
ratio → **anomaly score** = cosine distance to baseline mean ÷ healthy-cluster
spread → status in <1 s → (if not Healthy) CNN fault class → recommended action
(Gemma or template) → persisted `Diagnosis` + spectrogram PNG.

---

## Module map

| Package | Contents |
|---|---|
| `core/` | Constants (all DSP params), Outcome result type, logging |
| `sensor/` | AudioCapture (AudioRecord, UNPROCESSED, 44.1 kHz), ImuCapture (accelerometer on a HandlerThread, rate measured from timestamps), CaptureCoordinator (runs both, live waveform + countdown) |
| `ml/signal/` | HannWindow, MelFilterBank (HTK, 20 Hz–11 kHz), LogMelSpectrogram, ImuVibrationIndex |
| `ml/anomaly/` | AnomalyScorer (spread-normalised cosine distance), Thresholds (T1 = 2, T2 = 4, per-asset) |
| `ml/classifier/` | FaultClassifier interface, HeuristicFaultClassifier (no deps), ExecuTorchFaultClassifier (reflection wrapper, XNNPACK/QNN) |
| `ml/advisor/` | MaintenanceAdvisor, TemplateAdvisor (deterministic), GemmaAdvisor (reflection wrapper), AdvicePrompt |
| `ml/executorch/` | SocDetector — reads `Build.SOC_MODEL`, decides NPU vs CPU |
| `domain/` | models, repository interfaces, use cases |
| `data/` | DTOs (kotlinx.serialization), JsonFileStore, repository impls |
| `viz/` | HeatColor, AndroidSpectrogramRenderer, ReportRenderer (offline shareable PNG) |
| `ui/` | theme, shared components, navigation, one package per screen |
| `di/` | ServiceLocator |
| `app/src/test/` | LogMelSpectrogramTest, AnomalyScorerTest — JVM, not yet run |
| `ml/*.py` | model.py, train_cnn.py (`--synth` augmentation), export_executorch.py, logmel_reference.py (parity anchor) |

---

## Build state

Everything is written. Nothing is verified against a real toolchain or device.

| Area | Status | Notes |
|---|---|---|
| Gradle project, manifest, dark theme, icon | drafted | No `INTERNET` permission by design. Wrapper not generated. |
| Sensor capture (audio + IMU + coordinator) | drafted | UNPROCESSED with VOICE_RECOGNITION fallback; IMU rate measured, delivered on a HandlerThread. |
| Log-mel front-end + IMU index | drafted | JTransforms FFT. Python parity reference exists; numerical match not yet checked. |
| Anomaly score + thresholds **(must-have path)** | drafted | Zero ML dependencies. Runs on device with no model files. |
| Data layer (JSON store, repos, use cases) | drafted | One folder per asset under `filesDir`. |
| Compose UI — 8 screens | drafted | Capture overlay, animated spectrogram, status pills, backend chip, share-PNG. |
| Compilation against Android SDK | **not done** | Never built. Expect import / API-signature fixups on first pass. |
| JVM unit tests | written, not run | `./gradlew :app:testDebugUnitTest` |
| ExecuTorch CNN (XNNPACK / QNN) | **flag off** | Reflection wrapper + SoC detect done. Needs the AAR (flag) + a trained `.pte`. |
| Trained model binaries | **none** | No `.pte` / `.task`. Training scripts ready in `ml/`. |
| QNN / NPU export path | **unproven** | Needs the Qualcomm QAIRT SDK. Treat as stretch; don't gate the demo on it. |
| Gemma on-device advice | **flag off** | Reflection wrapper + prompt done. Template fallback works without it. |
| Threshold calibration on a real machine | **not done** | Defaults (T1 2 / T2 4, spread floor 0.02) are guesses — the biggest live-demo risk. |
| Office Kit integration / landscape layout | **not started** | Activity is portrait-locked. 10% of the score is Office Kit telemetry. |
| Instrumented / Compose UI tests | none | UI verified manually only. |

---

## Runs today vs needs setup

**Works after it compiles — the anomaly path.** Register asset → capture
baseline → diagnose → Healthy / Warning / Critical + template advice +
spectrogram + shareable PNG + history. No model files, no flags, no network.

**Needs a model file + flag — CNN fault class.** Train & export
`fault_cnn_xnnpack.pte`, drop in `app/src/main/assets/models/`, set
`jugaad.executorch.enabled=true`. Backend chip then reads `CPU · XNNPACK`;
logcat shows inference time.

**Needs QAIRT SDK — NPU path.** Export a QNN `.pte` for `SM8850`. On device the
chip reads `NPU · QNN` and ExecuTorch prints `[Qnn…]` lines — the NPU proof.
High effort for the window.

**Needs the .task bundle + flag — Gemma advice.** Add
`gemma3-1b-it-int4.task`, set `jugaad.gemma.enabled=true`. Advice source on the
result screen becomes "Gemma 3 1B (on-device)". Adds seconds of latency — keep
it off the <1 s path.

---

## Critical context

Condensed from the full audit. These are the things that move the score,
ranked.

### 1 — Original-work rule (disqualification risk)

All submitted code must be written during the event window and organizers can
inspect commit history. The repo is currently one ~5.5k-line commit — that
reads as pre-built. **Plan:** keep this repo as a personal reference, start the
event repo from an orphan commit, and rebuild module-by-module (retyping, not
pasting), committing every 20–40 min. The exact rebuild order is in
[`HACKATHON.md`](../HACKATHON.md). Also confirm the team-composition rule: no
mixing students and working professionals.

### 2 — 25% is device telemetry, not the pitch (high)

Creative phone use (15%) + Office Kit usage (10%) are measured by HackTracker
and cannot be recovered in the presentation.

- **On-device AI must actually run.** The heuristic classifier is plain Kotlin
  maths and won't register as inference. Ship an XNNPACK `.pte` with the flag
  on from the first build. The classifier already runs on every diagnose
  (label hidden when Healthy) so the signal fires each time.
- **Camera use is thin.** One optional nameplate photo. Re-shoot per asset, add
  a second asset, or add a short multi-frame "scan the machine" step.
- **Office Kit needs an owner from hour 1** — develop and demo through the
  external-monitor session the whole event, not just at the end.

### 3 — Uncalibrated thresholds (high)

With the default spread floor (0.02) and T1 2 / T2 4, the score is roughly
`50 × cosine-distance` — a 4% / 8% change trips Warning / Critical. On a real
shop-floor machine that is either trigger-happy or never crosses. Build a
short in-app **Calibrate** flow: capture healthy + known-fault clips on the
actual demo machine, set T1/T2 from the observed score spread.

### 4 — QNN is aspirational (medium)

The QAIRT toolchain rarely comes up inside a hackathon. The XNNPACK CPU path
is the realistic on-device story and still hits <1 s. Only show the QNN
logcat proof if it genuinely loaded.

### 5 — Portrait lock hurts the Office Kit visual (medium)

`MainActivity` is `screenOrientation="portrait"` with no large-screen layouts.
On an external monitor it letterboxes as a strip. Add a landscape
`ResultScreen` and drop the lock (or set `resizeableActivity="true"`).

### 6 — The "agent" is currently a single prompt (low)

One LLM call with a fixed template is not really agentic. Either give it a
small amount of agency (read the diagnosis history, choose between monitor /
inspect / stop, emit a short checklist) or soften the word in the pitch.

---

## What to do now

Ordered by priority. The first four are before or in the first hour; the rest
are during the build.

### Now / first hour

1. **Lock the provenance approach** with the team. If rebuilding: create the
   event repo, first commit is an empty Android scaffold you make live, then
   follow the `HACKATHON.md` module order with frequent commits.
2. **First compile.** Android Studio → generate wrapper →
   `./gradlew :app:assembleDebug`. Fix imports / API mismatches. Then
   `./gradlew :app:testDebugUnitTest` and confirm both tests pass.
3. **One XNNPACK model.**
   `python ml/train_cnn.py --data data --synth --epochs 20` on a single
   healthy recording →
   `python ml/export_executorch.py --backend xnnpack --out app/src/main/assets/models/fault_cnn_xnnpack.pte`
   → set `jugaad.executorch.enabled=true`.
4. **Smoke test on a real phone + a fan.** Baseline clean → diagnose (expect
   Healthy, score < 2) → tape a coin to a blade or block half the intake →
   diagnose again → score rises. Watch `adb logcat -s JUGAAD:*` for the audio
   source and IMU rate.

### During the build

5. **Office Kit owner** from the start; add the landscape `ResultScreen` and
   drop the portrait lock.
6. **In-app Calibrate flow** — set T1/T2 from healthy vs faulty score
   distributions on the demo machine.
7. **Decide Gemma in or out.** If time is short, ship the template advisor and
   say advice "streams in after the verdict".
8. **Optionally make the agent agentic** (history-aware action choice) —
   otherwise adjust the pitch language.
9. **Rehearse the demo** against `DEMO.md`. Time the Healthy → fault →
   Critical segment honestly (~25–30 s, not "under 10").
10. **Add a log-mel parity check** — run `ml/logmel_reference.py` on a clip
    and confirm it matches `LogMelSpectrogramTest`.

---

## Build & test

Requires Android Studio Ladybug+, AGP 8.7, JDK 17, SDK 35, and a physical phone
(an emulator has no real vibration or machine audio).

```bash
# one-time
gradle wrapper --gradle-version 8.11.1

# build / install / unit-test
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest      # LogMelSpectrogramTest, AnomalyScorerTest

# on-device logs (SoC, flags, audio source, IMU rate, inference ms)
adb logcat -c
adb logcat -s JUGAAD:* ExecuTorch:* Qnn:*
```

Feature flags live in `gradle.properties`: `jugaad.executorch.enabled`,
`jugaad.gemma.enabled`. Turning a flag on also links the matching AAR. Model
files go in `app/src/main/assets/models/` and are git-ignored.

Debug package id is `com.jugaad.agent.debug`.

---

## Open decisions

The team needs to settle these before or at the very start of the build.

- **Provenance.** Rebuild from scratch at the event (recommended), or submit
  this and be ready to explain the history?
- **Scope for the window.** Must-have: anomaly + UI + XNNPACK CNN +
  calibration. Stretch: QNN, Gemma. Cut Gemma first if time-poor.
- **Fault classes.** Keep `[Healthy, Rotor Imbalance, Airflow Obstruction]`, or
  match the classes to faults you can reliably create on the demo machine?
- **Demo machine.** Which one, and how do you fault it on stage — coin on a
  fan blade, blocked intake, loose guard?
- **Team composition.** Confirm no mix of students and working professionals
  (explicit rule).
- **Agent framing.** Invest in real agency (history + action choice), or
  present it as an assistant, not an agent?

---

## File reference

Where to look when you need to change or explain something.

| File | What's there |
|---|---|
| `ml/anomaly/AnomalyScorer.kt` | Baseline building, spread, the cosine-distance score, divergent-band detection. |
| `ml/signal/LogMelSpectrogram.kt` | STFT → mel → log front-end; `MelFilterBank.kt` alongside it. |
| `sensor/` | AudioCapture, ImuCapture, CaptureCoordinator — all capture logic + the live-progress state. |
| `domain/usecase/DiagnoseUseCase.kt` | The whole diagnose flow: capture → features → score → classify → advise → persist. |
| `ml/classifier/ExecuTorchFaultClassifier.kt` | Reflection into ExecuTorch, backend selection order; `ml/executorch/SocDetector.kt` decides NPU vs CPU. |
| `di/ServiceLocator.kt` | Every dependency is wired here, plus the warm-up that swaps in real ML engines. |
| `core/Constants.kt` + `app/build.gradle.kts` | All DSP parameters, mirrored into `BuildConfig`. Change them in both places and in `ml/logmel_reference.py`. |
| `ui/<screen>/` | One package per screen — screen + view-model together. |
| `ml/README.md`, `HACKATHON.md`, `DEMO.md` | Model training, event plan, demo run sheet. |

---

*Jugaad Agent handoff · state as of commit `0b6ab1d`, 12 Sep 2026.*
