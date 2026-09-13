# v15: get-ready pre-roll before every recording (2026-09-13)

## The constraint being attacked

The founder wants the phone steady before any data is captured. Today a tap starts capture at
once: `CaptureOverlay` (ui/common/CaptureOverlay.kt) shows the capture's own seconds left
(`CaptureProgress.secondsLeft`), so the first seconds of a reference measurement or a reading are
recorded while the technician is still placing the phone. Recording entry points (read from code):
reference measurement (`ui/baseline/BaselineScreen.kt`), reading (`ui/diagnose/DiagnoseScreen.kt`),
and the "resting still" probe on Pre-check (`ui/checklist/ChecklistScreen.kt:103`,
`motionCapture.capture(1)`, run on "Run checks").

## Design

1. One shared pre-roll in `ui/common`: a full-screen overlay in the same visual language as
   `CaptureOverlay`, header "Get ready", a one-line placement instruction, a large 3, 2, 1 at one
   second each ("Recording starts in"), and a Cancel button. No sensor, microphone or capture call
   runs during it.
2. When it reaches zero, the existing capture starts; `CaptureOverlay`'s header reads "Recording"
   with the existing label (reference measurement / reading) so the two phases are distinct.
3. Applies to all three entry points. The silent sensor-rate probe that runs on entering Pre-check
   is not a user recording and gets no pre-roll.
4. No sound or vibration at or near capture start (they would enter the microphone and
   accelerometer data). No em or en dashes in strings.
5. Leaving the screen or Cancel during the pre-roll aborts without starting capture.

## Failure modes and what catches each

| Failure mode | What catches it |
|---|---|
| Capture starts before the countdown ends | logcat: capture start line timestamp at least 3 s after the tap; screenshots of each phase |
| Cancel still records | UI walk: Cancel, then no new history entry and no capture log line |
| A recording path missed | grep for every capture call site; walk each on a phone |
| Pre-roll pollutes data | no sound or haptic added (code review) |

## Delegate versus build

| Item | Decision |
|---|---|
| Implementation (ui/common, baseline, diagnose, checklist screens and their view models) | Sonnet |
| Install and on-phone walk | Main session, on phones A and B only (the founder is testing on C); readings taken for the walk use bench equipment so nothing enters training |

## Result (2026-09-13, 10:1x phone clock)

Implemented by a Sonnet agent: `ui/common/PreRoll.kt` (countdown controller), `PreRollOverlay.kt`,
`KeepScreenOn.kt` (the screen was never kept awake during capture before), and the pre-roll wired
into reference measurement, reading and Pre-check "Run checks". `CaptureOverlay` now reads
"Recording". One tap covers the whole capture: the reference measurement already ran all clips in
one call and a finished reading already navigated to its result, so no extra tap existed to remove.

Build green, 232/232 JVM tests, APK `b9aa123d1026ae2a` installed with `install -r` on A, B and C;
no `FATAL EXCEPTION`, equipment and history counts unchanged (A 8/8, B 3/16, C 3/28).

Verified on phone A:
- Overlay strings on screen: "GET READY", "Place the phone flat on the machine housing and let go.",
  "Recording starts in", "3/2/1", "Cancel" (`tools/devtest15/P06_count_a.png`).
- Full countdown, Pre-check "Run checks": `preroll: start (resting check)` 10:11:53.484 then
  `preroll: done, capture starting (resting check)` 10:11:56.491, a 3.007 s gap.
- Cancel, reading: `preroll: start (reading)` 10:13:03.709 then `preroll: cancelled (reading)`
  10:13:05.658, no `AudioCapture` or `MotionCapture` line, history 8 to 8 and samples 8 to 8.
- A full reading was deliberately not taken: A's equipment is non-bench, so a reading with the
  phone on a desk would enter training.

Same build also carries the v14 D3/D4 handover follow-up (Stop persists `lastRole CLIENT` and a
300 s automatic-takeover opt-out, leaving owner mode removes this phone's WiFi Direct group), with
six new FailoverTest cases. Unit-tested; not yet exercised on hardware.
