# HACKATHON.md — provenance, planning, and rubric plan

iQOO Hackathon 2026 × Reskilll — Chennai City Battle, **12–13 Sept 2026**.

---

## 1. Original-work rule (this is the #1 disqualification risk)

> *All submitted code must be written during the event window. Pre-built apps or
> large pre-written modules are not allowed. Organizers can check commit history.
> Spikes must stay in a separate `spikes/` folder that is never submitted.*

**What this repo is:** a pre-event **reference spike** on branch
`spike/pre-event-reference`. It exists to de-risk the design, not to be submitted.

**What to do at the event:**

1. `git checkout --orphan main` → `git rm -rf .` → first real commit is an empty
   Android project scaffold you create live.
2. Rebuild in this order, **retyping** (not copy-pasting) each file, committing
   after each coherent chunk (target: a commit every 20–40 min, 30–60 commits
   total across the team):
   1. Gradle scaffold + manifest + dark theme
   2. `sensor/` — AudioCapture, ImuCapture, CaptureCoordinator
   3. `ml/signal/` — Hann, MelFilterBank, LogMelSpectrogram, ImuVibrationIndex
   4. `ml/anomaly/` — AnomalyScorer, Thresholds  ← **must-have milestone**
   5. `data/` + `domain/` — JSON store, repos, models, use cases
   6. `ui/` — screen by screen (AssetList → Create → Detail → Baseline → Diagnose → Result)
   7. `viz/` — spectrogram + report PNG
   8. `ml/` Python — logmel_reference, model, train, export
   9. ExecuTorch + Gemma reflection wrappers (only if time)
3. Never `git merge` / `git cherry-pick` / `git add` from
   `spike/pre-event-reference` onto `main`. Keep the spike in a separate clone if
   that temptation is real.
4. If the organizers allow a `spikes/` folder in the repo, put a copy there and
   add it to `.gitignore` for the submission zip.

**Team composition:** confirm no mixing of students and working professionals in
one team (explicit rule).

---

## 2. Red Light / Green Light planning (~55% Red Light = phone-only via Office Kit)

Tag every task before you start:

| Tag | Meaning | Examples |
|-----|---------|----------|
| 🟢 GREEN-ONLY | needs a laptop / Android Studio | Gradle changes, ExecuTorch export, first APK build, debugging a crash |
| 🟣 EITHER | doable phone-through-Office-Kit with a keyboard | writing a self-contained Kotlin file, editing `DEMO.md`, UI copy, prompt tuning, manual QA, calibration runs |
| 🔴 RED-OK | phone only | capturing baseline/fault audio on the demo machine, running the app, HackTracker-visible sensor + on-device-AI usage, screen-mirror layout checks |

Front-load 🟢 work into Green Light windows. During Red Light, do capture/QA/
calibration and phone-side coding. **One person owns Office Kit from hour 1** —
develop and demo through it so the 10% telemetry accrues the whole event.

---

## 3. HackTracker checklist (25% auto-measured — cannot be faked in the pitch)

Creative phone use (15%) + Office Kit usage (10%).

- [ ] Ship with `jugaad.executorch.enabled=true` and a real `fault_cnn_xnnpack.pte`
      from the **first** installed build. Heuristic-only ≠ on-device AI to telemetry.
- [ ] `DiagnoseUseCase` runs the classifier on **every** diagnose (label hidden
      when Healthy) — already wired; keep it that way.
- [ ] Do 20+ real diagnose runs on the demo machine over the event (not just at
      the end) so mic + accelerometer + on-device inference all show sustained use.
- [ ] Use the camera for more than one nameplate: re-shoot per asset, and add a
      second asset. If time: a "scan the machine" step that takes 3 frames.
- [ ] Develop and demo through **vivo Office Kit** on an external monitor every day.
- [ ] Add a landscape / large-screen layout for `ResultScreen` and drop the hard
      `portrait` lock, or set `android:resizeableActivity="true"` — Office Kit
      desktop should not letterbox a portrait strip.

---

## 4. Demo de-risking

- [ ] **Calibrate on the actual machine.** Capture ~8 healthy clips + ~5 faulty
      clips (coin taped to a fan blade = imbalance; card over ~half the intake =
      airflow). Set `T1 = p95(healthy score)`, `T2 = halfway to mean(faulty
      score)` via the in-app calibration flow. Do **not** ship the raw defaults.
- [ ] Verify `AudioSource.UNPROCESSED` is honoured on the iQOO 15 (`logcat` prints
      the source). If it falls back to `VOICE_RECOGNITION`, that's fine — baseline
      and diagnose use the same source — but note it in the pitch.
- [ ] Time the full "Healthy → introduce fault → Critical" segment. It's ~25–30 s,
      not "<10 s". Narrate accordingly.
- [ ] Fallback tree, all already implemented — confirm each on device:
      LiteRT head fail → Heuristic; Gemma off → TemplateAdvisor; no reference → hard block;
      no owner on WiFi → WiFi Direct group; owner gone → next node serves; Office Kit fail →
      run on the phone screen.
- [ ] Pre-grant mic + camera before you present.
- [ ] `adb logcat -s JUGAAD:*` piped to a visible terminal for the proof lines
      (`lan: advertising`, `auto-join: syncing with`, `fl sync: owner N node(s) merged`).

---

## 5. Pitch order (10%)

1. Impact first: SME rotating equipment, no CapEx, no cloud, no training data.
2. One phone = two sensors fused over the same 3 s (mic + accelerometer).
3. Live: baseline → healthy → inject fault → Critical + one-sentence advice.
4. Offline proof: put the phones in airplane mode with WiFi Direct and run the whole demo, or
   watch that no request leaves the device. Do not claim `INTERNET` is absent from the manifest:
   it is declared, because Android gates even a local socket on it and the phone to phone sync
   needs one.
5. On-device AI: backend chip = `NPU · QNN` / `CPU · XNNPACK`; logcat proof.
6. Roadmap: multi-machine fleet view, trend history, more fault classes.
