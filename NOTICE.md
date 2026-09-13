# Third-party notices

Jugaad Agent bundles or depends on the following. Licence texts are available at the linked
sources; the Work Sans licence ships in the APK at `assets/fonts/WorkSans-OFL.txt`.

| Component | Licence | Source |
|---|---|---|
| TensorFlow Lite (LiteRT) 2.16.1 | Apache 2.0 | https://github.com/tensorflow/tensorflow |
| AndroidX (Compose, CameraX, WorkManager, Navigation, Lifecycle) | Apache 2.0 | https://developer.android.com/jetpack |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache 2.0 | https://github.com/JetBrains/kotlin |
| JTransforms 3.1 | BSD 2-Clause | https://github.com/wendykierp/JTransforms |
| Work Sans (variable font) | SIL Open Font License 1.1 | https://github.com/weiweihuanghuang/Work-Sans |
| ExecuTorch (optional, off by default) | BSD 3-Clause | https://github.com/pytorch/executorch |
| MediaPipe GenAI (optional, off by default) | Apache 2.0 | https://github.com/google-ai-edge/mediapipe |

## MAFAULDA dataset

The shipped classifier heads (`assets/models/fl_head_*.tflite`) were pretrained on the MAFAULDA
Machinery Fault Database published by the Signals, Multimedia and Telecommunications Lab
(SMT), Federal University of Rio de Janeiro: https://www02.smt.ufrj.br/~offshore/mfs/

The database page states no licence, terms of use or citation requirement (checked
2026-09-13). Before commercial distribution, confirm terms with SMT/UFRJ or retrain the heads
on field data. Provenance and counts are in `ml/data/README.md`.
