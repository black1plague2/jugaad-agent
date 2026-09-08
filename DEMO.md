# Jugaad Agent — demo run sheet

## Setup (before you present)

- [ ] `./gradlew :app:installDebug` on the iQOO 15.
- [ ] Grant the microphone prompt on first launch.
- [ ] Have a small running machine: desk fan, pedestal fan, pump, or a laptop with
      a spinning HDD/fan. A fan you can partly block with card is ideal.
- [ ] Optional: models in `app/src/main/assets/models/` + flags on (see README).
- [ ] Screen-mirror to the projector with **vivo Office Kit**. The result screen
      is laid out for 3 m readability — big pill, big number.

## Live script (~3 min)

1. **Create asset** → name it "Exhaust Fan". Snap the nameplate (optional).
2. **Capture baseline** with the fan running clean. Phone flat on the housing.
   3 clips, ~12 s. Point out "healthy spread" — the width of the healthy cluster.
3. **Diagnose** once, untouched → **Healthy**, score < 2. Show the heartbeat
   spectrogram.
4. Introduce a fault:
   - *Imbalance*: tape a coin to one fan blade, or nudge the guard so it rattles.
   - *Airflow*: hold a piece of card over ~half the intake.
5. **Diagnose** again → **Warning/Critical**, score rises. CNN label appears
   (only shown when not healthy). Advice sentence updates.
6. **Share Report** → system share sheet → send the PNG to yourself. Fully offline.
7. Open **History** — the two runs, newest first, with status pills.
8. **Go / no-go checklist** — show the pre-flight checks (mic, accelerometer,
   baseline, phone resting still).

## Proving the NPU path

```bash
adb logcat -c
adb logcat -s JUGAAD:* ExecuTorch:* Qnn:* QnnExecuTorch:*
```

On launch you should see:

```
JUGAAD  SoC: model=SM8850 ... htpCapable=true (Snapdragon 8 Elite Gen 5)
JUGAAD  ExecuTorch: loading fault_cnn_qnn.pte for NPU path
JUGAAD  ExecuTorch: loaded fault_cnn_qnn.pte  backend=NPU
Qnn...  <QNN backend init lines from ExecuTorch>
JUGAAD  ExecuTorch: infer <N>ms backend=NPU -> Rotor Imbalance 0.xx
```

The **backend indicator** chip on the result screen reads `NPU · QNN`. Force the
CPU path for comparison by renaming `fault_cnn_qnn.pte` — the chip switches to
`CPU · XNNPACK` and latency is still < 1 s.

## Talking points

- No `INTERNET` permission — grep the merged manifest live.
- One phone, no extra hardware: mic = acoustic sensor, accelerometer = contact
  vibration sensor, fused over the same 3 seconds.
- Anomaly score works with **zero** trained models — robust fallback for any
  machine on the floor. CNN + Gemma are additive.
- Everything (features, score, CNN, LLM, report) is on-device.
