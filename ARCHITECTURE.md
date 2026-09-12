# Jugaad Agent — End-to-End Architecture

## Project Overview
Offline, on-device condition monitoring for rotating machines. Three (or more) phones train and merge fault models over **WiFi Direct** with **zero cloud dependency**. Hardware: iQOO 15 (Snapdragon 8 Elite Gen 5, Android 16). Built with Kotlin + Jetpack Compose + TensorFlow Lite 2.16.1 on device.

---

## 1. Sensing → Feature → Anomaly → Diagnosis

### 1.1 Audio Capture (`sensors/AudioCapture.kt:33-88`)
- 44.1 kHz, mono, 16-bit PCM, `AudioSource.UNPROCESSED` (no AGC/NS/echo-cancel)
- 3-second capture → 132,300 samples → floats in [-1, 1] via `out[i] / 32768f`
- Falls back to `VOICE_RECOGNITION` if `UNPROCESSED` unavailable

### 1.2 Feature Extraction (`ml/FeatureExtractor.kt:31-54`)
```text
Input:  FloatArray audio + MotionCapture.Reading?
Output: Analysis(
  logMel: 128×128 image (row-major, mel-major)
  feature: 256-d vector (per-band mean[128] || per-band std[128])
  imuIndex: accel 5-60 Hz energy fraction
  imuDominantHz: dominant accel frequency
  gyroIndex: gyro 5-60 Hz energy fraction
  magIndex: mag 3-25 Hz energy fraction
  magRms: mag AC RMS µT
)
```
- `LogMelSpectrogram.compute()` → mel spectrogram → `featureVector()` → 256-d
- `ImuVibrationIndex.compute()` from magnitude × rateHz
- `MagneticIndex.compute()` from magnitude × rateHz

### 1.3 Anomaly Scoring (`ml/anomaly/AnomalyScorer.kt:89-147`)
```text
Baseline = mean of K healthy feature vectors
Spread   = mean cosine distance of each baseline vector to that mean (floored at 0.02)
Score    = cosineDistance(x, baselineMean) / spread

Thresholds: T1=2 → Healthy, T2=4 → Warning, >T2 → Critical

Sensor-aware overlay: z_s = |delta_s| / max(std_s, zFloor) per sensor;
sensorScore = sensorScoreScale × max(weight_s × z_s);
finalScore = max(acousticScore, sensorScore)
```

### 1.4 Diagnosis (`ml/diagnosis/EvidenceExtractor.kt:38-73` + `RulesEngine`)
- Extracts 13 evidence metrics: band deltas, broadbandDelta, spectralSpread, peakiness, dominantHz, lineHumDelta, imuDelta/gyroDelta/magDelta/magRmsDelta, anomalyScore, cnnClass/confidence
- `RulesEngine` ranks `IssueSuggestion` per machine type's fault rules from `machines.json`
- Vocabulary validated against `EvidenceExtractor` fixed metric set (`MachineCatalog.kt:55-58`)

### 1.5 Data Models
| Model | Purpose | Key File |
|---|---|---|
| `Asset` | Monitored equipment at `filesDir/assets/<id>/asset.json` | `domain/model/Asset.kt` |
| `Baseline` | Healthy fingerprint: 256-d mean + spread + rawStd + 4 IMU means/stds, 3 clips | `domain/model/Baseline.kt` |
| `Diagnosis` | One diagnose run: score + status + dominantSource + issues + CNN metadata + spectrogramPng path | `domain/model/Diagnosis.kt` |
| `IssueSuggestion` | Ranked fault cause: faultId + label + confidence + matchedKeywords + action + severity | `domain/model/IssueSuggestion.kt` |
| `FlSample` | Federated sample: 260-d x + label + source + ts + score + meta | `fl/SampleStore.kt` |

### 1.6 Bench Guard
- `Asset.benchTest=true` blocks capture at source (`DiagnoseUseCase`)
- `*.DO-NOT-TRAIN.jsonl` files never enter `ml/fl/pretrain.py`
- Phones wiped with `tools/reset-phones.sh` before demos

---

## 2. Configuration Layering (3-layer, lowest→highest)

1. **Asset defaults** — `assets/config/app_config.json`
   - DSP params: N_FFT=2048, N_MELS=128, MEL_FMIN=20Hz, MEL_FMAX=11kHz
   - Sensor weights (zWeights.accel/gyro/mag/magRms), zFloor, sensorScoreScale
   - bandGroups (low/mid/high/veryHigh Hz ranges), lineHz configured lines
   - sharing.batchSize, sync.port/windowMs/retryBackoffMs

2. **Device overrides** — `filesDir/config/overrides.json` (partial JSON merged on top)

3. **Network policy** — flattened `"section.key" -> value` strings from owner, wins over both above
   - `budget.*` keys always refused
   - Source tracked via `ConfigSource` enum (DEFAULT/OVERRIDE/POLICY)

**Flow**: `ConfigStore.load(context)` → merge defaults + overrides → `applyPolicy(policy)` from owner network state → `_effective: StateFlow<AppConfig>`.

---

## 3. Federated Learning Runtime

### 3.1 `FlRuntime` (`fl/FlRuntime.kt:15-149`)
Composition holder exposed by `ServiceLocator`. Owns champion/challenger selection on top of per-variant `Strategy` trainers.
- `trainers: Map<String, Strategy>` — one per installed variant
- `config: StateFlow<NodeConfig>` — deviceId, name, mode, pinnedChallenger
- `network: StateFlow<NetworkState>` — championId, assignments, nodes, events, policy
- `pool: SharedPool` — peer-sample pool (v4 plan §4/§5)
- `recovery: Recovery.Report` — startup repair report

**Build** (`FlRuntime.build()`): Creates one `Strategy` per `VariantSpec`:
- `CENTROID` → `CentroidStrategy` (no TFLite asset needed)
- `MLP` → `VariantTrainer` + `FlModel(assetFile)`

### 3.2 Sample Store (`fl/SampleStore.kt:22-186`)
JSONL at `files/fl/samples.jsonl`. Key behaviors:
- `addPending(id, assetId, x)` — appends new unlabeled sample
- `label(id, label)` — rewrites whole file (line-oriented format limitation)
- `labelled()` / `labelledTrain()` / `labelledVal()` — split: 75% train / 25% validation (hash-based: `(id.hashCode() and 0x7fffffff) % 4 == 0`)
- **Legacy normalization** (`Line.toSample()` → `FloatArray.normalizeToInputDim(id)`): zero-pads 257-d vectors to 260-d on load. Missing sensors → 0 delta per `FeatureDelta` contract.

### 3.3 Variant Training
- `VariantTrainer` trains on `store.labelled() + pool.all()` (not raw store)
- Batch size 8, epochs per `TrainBudget`, early stopping, weight decay
- Held-out split: 25% validation, overfit gap check
- `FlModel.infer(x)`: hard-requires `x.size == 260` (`FlModel.kt:21-23`)

### 3.4 Federated Merge + Promotion
- `FedAvg.merge(weighted contributions)` — weighted by `nTrain` across contributors + owner's own copy
- **Accept guard**: `nTrain < 5 || after >= before - 0.10f` — variant only accepted if trained on ≥5 samples OR new accuracy ≥ old - 0.10
- **Promotion**: champion/challenger after each sync round. If `championAfter != championBefore` → promote. Mode: EXPERIMENTAL allows pinned challenger.

### 3.5 Shared Pool (`fl/SharedPool.kt`)
- `all()`, `knownSampleIds()`, `acceptShared(ids)`, `poolSamplesExcept(except, batchSize)`
- v4 plan §5: peer sample enrichment during sync

### 3.6 Node & Network State
- `NodeConfig`: deviceId, name, mode (STABLE/EXPERIMENTAL), pinnedChallenger
- `NetworkState`: championId, assignments (deviceId→NodeMode), nodes (List[NodeCard]), events (last 50), policy
- `NodeCard`: round, valAcc, nTrain, nVal, lastSeenMs, mode, challenger, isOwner

---

## 4. P2P Sync — WiFi Direct + TCP v2 (JGFL framing)

### 4.1 Transport
- **WiFi Direct group**: one phone becomes GROUP_OWNER (fixed IP config). Clients discover → connect (accept dialog on owner's first join). Group persists across app restarts.
- **Fixed owner IP** + **port** from `Sync.port()` (config-driven)

### 4.2 Protocol v2 (`p2p/SyncProtocol.kt:29-153`)
Wire format: `"JGFL"` (4 ASCII bytes) + `u8 version=2` + `u8 type` + `u32 jsonLen` + JSON header (UTF-8) + `u32 floatCount` + floats as little-endian float32.

| Type | Value | Description |
|---|---|---|
| HELLO | 1 | deviceId, name, mode, challenger |
| WEIGHTS | 2 | variantId, round, nTrain, nVal, trainAcc, valAcc + float payload |
| MERGED | 3 | per-variant merged weights |
| NETWORK | 5 | full NetworkState |
| DONE | 6 | terminal marker |
| SAMPLE_IDS | 7 | sender's offered sample ids (v4) |
| SAMPLES | 8 | the sample payload (v4 plan §5) |

### 4.3 Owner Side (`FedAvgCoordinator.serveOnce()`)
1. Accept connections in `windowMs` (default from config)
2. Per client: read HELLO + WEIGHTS per held variant
3. Merge with weighted FedAvg across **all clients + owner's own copy**
4. Run promotion/assignment pipeline
5. Reply: MERGED per variant, optional SAMPLE_IDS/SAMPLES (sample exchange), NETWORK state, DONE

### 4.4 Client Side (`FedAvgCoordinator.runAsClient()`)
1. Connect to owner IP:port
2. Send HELLO + WEIGHTS per held variant + optional SAMPLE_IDS
3. Read replies: MERGED → apply if accept-guard passes → SAMPLES (if sharing) → NETWORK → DONE
4. `applyMerged(v, w, newRound)` if `nTrain < 5 || after >= before - 0.10f`

### 4.5 FlSyncService (`p2p/FlSyncService.kt:37-178`)
Foreground service:
- Binds server socket on port after WiFi Direct group forms
- Loop: `coordinator.serveOnce(socket)` → `SyncBus.last` update
- If role changes CLIENT→OWNER → update `lastRole`
- Creates notification: "Syncing federated model over WiFi Direct. Keeps the radio awake so peers can reach this phone."

### 4.6 Failover (`p2p/Failover.kt`)
Criteria for declaring owner dead + promotion. After failover, other clients must **manually** Discover→Connect to new owner (WiFi Direct requires tap for new pairing).

### 4.7 End-to-End Sync Round
```text
OWNER: accept → read HELLO+WEIGHTS → merge → promote → reply MERGED+NETWORK+SAMPLES/DONE
CLIENT: connect → send HELLO+WEIGHTS → receive MERGED → apply if guard passes → send SAMPLES → receive NETWORK+DONE
```

---

## 5. UI + Navigation + Storage

### 5.1 Navigation
`JugaadNavGraph.kt` + `Routes.kt` — 15+ screen routes:
- Assets, CreateAsset, Baseline, Diagnose, Result, History
- Network tab: Devices, Sync, Performance, Group Settings
- AssetDetail, FederatedShell

### 5.2 Screens (purpose + ViewModel)
| Screen Package | Purpose | ViewModel |
|---|---|---|
| `ui/assets` | Asset list + creation | `AssetListViewModel` |
| `ui/createasset` | Camera capture + asset creation | `CreateAssetViewModel` |
| `ui/baseline` | Baseline capture & management | `BaselineViewModel` |
| `ui/diagnose` | Real-time anomaly diagnosis | `DiagnoseViewModel` |
| `ui/result` | Diagnosis result with spectrogram + issues | `ResultViewModel` |
| `ui/history` | Historical diagnosis records | — |
| `ui/network/Network` | Federated network overview | `NetworkViewModel` |
| `ui/network/Devices` | Connected peer list | — |
| `ui/network/Sync` | Sync control + status | `SyncTab` |
| `ui/network/Performance` | Model importance/performance | — |
| `ui/network/GroupSettings` | Group/policy configuration | — |
| `ui/assetdetail` | Detailed asset view | `AssetDetailViewModel` |

### 5.3 UI Style
- Stitch-inspired Humane Minimalist Dark
- `#07080A` canvas, single crimson accent
- Work Sans font (bundled)
- No em/dashes in UI strings (hard rule)

### 5.4 Storage Layout
```
filesDir/
  assets/<id>/        → asset.json, baseline.json, history/<id>.json + .png
  fl/                 → samples.jsonl, shared_samples.jsonl, node.json, network.json, state.json, weights
  config/             → overrides.json (device-local)
  reports/ (cacheDir) → anomaly reports
```

---

## 6. Error Model + Build

### 6.1 Error Model
- `Outcome<E>` sealed class (Ok/E) — tagged `JUGAAD` per log line
- `Logx` provides per-module tags for every app log line
- `Outcome.kt` is the base; all capture/ML/sync returns Outcome

### 6.2 Build Configuration
| Parameter | Value |
|---|---|
| compileSdk | 35 |
| minSdk | 29 |
| Kotlin | Latest (project-tuned) |
| Compose | Latest |
| LiteRT | 2.16.1 |
| WorkManager | Latest |
| Tests | 165/165 JVM tests green (2026-09-12) |

**Build command**: `export JAVA_HOME=/path/to/jdk17; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:assembleDebug :app:testDebugUnitTest`

**APK**: `app/build/outputs/apk/debug/app-debug.apk`, package `com.jugaad.agent.debug`, activity `com.jugaad.agent.MainActivity`

**Python needed only** for: head baking (`ml/fl/export_fl_head.py`), MAFAULDA pretraining, network simulation, field ingest. Not required at runtime — TFLite heads committed at `app/src/main/assets/models/fl_head_*.tflite`.

### 6.3 On-Device ML Runtime
- TFLite `infer` signature: expects `x` input of shape `[1, 260]` → outputs `probs[3]`
- `train` signature: expects `[x_batch, y_batch]` of size `TRAIN_BATCH=8` → returns mean loss
- `get_weights`/`set_weights` signatures for federated weight wire format

---

## 7. End-to-End Data Flow

### 7.1 Single Reading Pipeline
```text
AudioCapture.record() 
  → FeatureExtractor.analyze(audio, motion) → 260-d FloatArray + sensor indices
    → AnomalyScorer.score(diagFeature, baseline) → MachineStatus + score + dominantSource
      → EvidenceExtractor.extract(analysis, baseline, anomalyResult, pred, cfg) → 13 evidence metrics
        → RulesEngine → ranked IssueSuggestion list → Diagnosis object
```

### 7.2 Federated Training + Sync Round
```text
Local:
  → SampleStore adds pending sample → label() → rewriteFile with 260-d normalized x
  → AutoTrainer.trainHeld() → VariantTrainer.train() on 260-d data → FlModel.infer/trainStep
  → FlModel.getWeights() → per-variant weights

Sync:
  → FedAvgCoordinator.runAsClient() or serveOnce() → JGFL v2 wire
  → Merge weighted across all contributors + owner's copy
  → Accept-guard pass/fail → Promotion if champion changed
  → Network state replicated → UI updated

Post-sync:
  → Both sides: updated champion, rounds, assignments in UI
  → Shared pool enriched with peer samples
```

---

## 8. Verified Facts (Result Section)

| # | Verified Fact |
|---|---|
| 1 | `assembleDebug` succeeds — BUILD SUCCESSFUL |
| 2 | `testDebugUnitTest` passes — 165/165 JVM tests, 0 failures |
| 3 | APK installed + launched on all three phones (A: `10BFAT1SUF000XP`, B: `10BFAT1U0F000XP`, C: `10BFAX1C7P0010U`) |
| 4 | Real E2E sync across WiFi Direct — logcat verified: owner+client messages with correct variant/round tracking |
| 5 | Seamless + clear on both phones — identical replicated state on Network tabs; champion deep 87.5% round 7; 3 nodes online |
| 6 | 3rd device joined (C) — auto-rejoined live group, synced successfully, 48 legacy 257-d samples migrated to 260-d on load |
| 7 | Legacy sample normalization fix: `SampleStore.normalizeToInputDim()` zero-pads 257-d → 260-d on load; root cause of prior sync failure (257-d vs 260-d TFLite input mismatch) |
| 8 | Failure messages no longer print `null` — `describe(e)` = `"<ClassName>: <message ?: connection closed by peer>"` |
| 9 | WiFi Direct group persists across app restarts; third device auto-rejoins without Discover/Connect |
| 10 | Config layering verified: defaults → device overrides → owner policy wins; budget keys always refused |

---

## 9. End-to-End Verification Loop (per CONTEXT.md)

1. Build + run JVM tests: `export JAVA_HOME=/path/to/jdk17; ./gradlew :app:assembleDebug :app:testDebugUnitTest`
2. Reset phones: `bash tools/reset-phones.sh --keep-equipment <A> <B> <C>`
3. Launch Sonnet verification agent with checklist + serials + bench rule → `tools/devtestN/REPORT.md`
4. Fix defects with Sonnet agent owning named files; rebuild; repeat steps 2-3
5. Append verified facts to plan's Result section + `HANDOFF.md`

---
*Architecture synthesized from full codebase analysis (182 Kotlin/Java files, 76+ markdown configs, 5 plan documents). All file:line references verified against actual source. No ML dependencies required for anomaly scoring (pure DSP math fallback). WiFi Direct + TCP JGFL v2 protocol verified end-to-end on three hardware devices.*