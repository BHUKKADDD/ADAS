# Graph Report - ADAS  (2026-08-07)

## Corpus Check
- 82 files · ~56,402 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 891 nodes · 1500 edges · 62 communities (59 shown, 3 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 166 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `d44bafb1`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- DrowsinessAnalyzer
- .req
- LaneDepartureTest
- ObdBleManager
- _packet
- test_vlm.py
- .context
- ObdParsingTest
- EventBuffer
- finetune_vlm.py
- CollisionEstimatorTest
- AdasViewModel
- InferenceEngine
- server.py
- Box
- main.cpp
- SimulatedFaceLandmarkSource
- train_v2.py
- Detection
- CollisionSignals.kt
- .update
- EventRecorder
- ADAS — Handover (2026-06-26)
- UploadStatus
- ADAS Project — Bug Report
- KalmanTracker
- ADAS — AI Dashcam for Indian Roads
- AdasCameraScreen
- convert_annotation
- ADAS — Session Handover
- FrameAnalyzer
- ADAS Cloud Ingestion (Phase 4 scaffold) — multi-tenant
- ADAS Cloud (Phase 4) — model-agnostic infrastructure scaffolds
- GnssLocationProvider
- Training data: India Driving Dataset (IDD) — Detection
- Steps
- Phase 3 — Fine-tune YOLOv8n for Indian roads (IDD)
- .triggerUpload
- HomeScreen
- SettingsSheet
- export_model.py
- AdasScreen
- .detect
- SplashScreen
- MainScreenTest
- HudBottomBar
- AdasColors
- gradlew
- Android ADAS App
- System Architecture
- Plugging in a Real TFLite Model
- env.sh
- find-bugs.js
- Build from Source
- Download & Install

## God Nodes (most connected - your core abstractions)
1. `AdasViewModel` - 48 edges
2. `DrowsinessAnalyzer` - 39 edges
3. `DrowsinessAnalyzerTest` - 39 edges
4. `CollisionEstimatorTest` - 35 edges
5. `Box` - 28 edges
6. `ObdBleManager` - 25 edges
7. `EventBuffer` - 25 edges
8. `LaneDepartureTest` - 24 edges
9. `CollisionEstimator` - 23 edges
10. `ObdParsingTest` - 23 edges

## Surprising Connections (you probably didn't know these)
- `AdasCameraScreen()` --calls--> `DetectionOverlay()`  [INFERRED]
  android_app/app/src/main/java/com/example/adas/AdasCameraScreen.kt → android_app/app/src/main/java/com/example/adas/DetectionOverlay.kt
- `AdasCameraScreen()` --calls--> `EventLogScreen()`  [INFERRED]
  android_app/app/src/main/java/com/example/adas/AdasCameraScreen.kt → android_app/app/src/main/java/com/example/adas/EventLogScreen.kt
- `AdasCameraScreen()` --calls--> `Box`  [INFERRED]
  android_app/app/src/main/java/com/example/adas/AdasCameraScreen.kt → android_app/app/src/main/java/com/example/adas/fcw/CollisionSignals.kt
- `AdasCameraScreen()` --calls--> `FrameAnalyzer`  [INFERRED]
  android_app/app/src/main/java/com/example/adas/AdasCameraScreen.kt → android_app/app/src/main/java/com/example/adas/FrameAnalyzer.kt
- `AdasCameraScreen()` --calls--> `HudBottomBar()`  [INFERRED]
  android_app/app/src/main/java/com/example/adas/AdasCameraScreen.kt → android_app/app/src/main/java/com/example/adas/HudBottomBar.kt

## Import Cycles
- None detected.

## Communities (62 total, 3 thin omitted)

### Community 0 - "DrowsinessAnalyzer"
Cohesion: 0.06
Nodes (26): DmsConfig, DmsPhase, CALIBRATING, IDLE, MONITORING, NO_FACE, DmsState, DriverAlertLevel (+18 more)

### Community 1 - ".req"
Cohesion: 0.06
Nodes (18): IngestionTestBase, _packet(), The browser dashboard can't set headers, so ?key= must authenticate., A JSON array is valid JSON but not a packet., *' is a read-scope, not a writable tenant — writes land in 'default'., A filter must never widen visibility past the tenant scope., Device model is attacker-controlled; it must not render as markup., init_db() must upgrade a pre-tenancy database in place. (+10 more)

### Community 2 - "LaneDepartureTest"
Cohesion: 0.08
Nodes (17): Bitmap, LaneDetector, DepartureSide, LEFT, NONE, RIGHT, fitLaneLine(), LaneDepartureTracker (+9 more)

### Community 3 - "ObdBleManager"
Cohesion: 0.07
Nodes (23): CharPair, ByteArray, CoroutineScope, StateFlow, ObdBleManager, CoroutineScope, StateFlow, SimulatedTelemetrySource (+15 more)

### Community 4 - "_packet"
Cohesion: 0.07
Nodes (17): main(), Geometric relations hook. Packets carry no bboxes yet, so this returns     [] to, risk_of(), spatial_relations(), to_scene_graph(), _packet(), n detections -> n(n-1)/2 co-occurrence edges., Downstream (VLM harness) keys off this — bump deliberately. (+9 more)

### Community 5 - "test_vlm.py"
Cohesion: 0.06
Nodes (11): lora_b is zero-init, so the wrapped layer must match the base exactly., Same packet must always yield the same stand-in embedding, or the         'train, The whole point of the harness: only adapters move., Regression guard for 9956645. MHA must come out untouched AND working., TransformerEncoderLayer embeds an MHA — the real shape of the bug., TestCaptions, TestLakeDataset, TestLoRAInjection (+3 more)

### Community 6 - ".context"
Cohesion: 0.15
Nodes (5): EventContext, EventNarrator, NarrationObject, TemplateNarrator, EventNarratorTest

### Community 7 - "ObdParsingTest"
Cohesion: 0.12
Nodes (7): GattUuidTriple, parseCoolantC(), parseMode01(), parseRpm(), parseSpeedKmh(), parseThrottlePct(), ObdParsingTest

### Community 8 - "EventBuffer"
Cohesion: 0.15
Nodes (5): Clip, EventBuffer, TimedFrame, EventBufferTest, T

### Community 9 - "finetune_vlm.py"
Cohesion: 0.10
Nodes (19): caption_from_packet(), inject_lora(), LakeDataset, load_lake(), LoRALinear, main(), ProjectionMLP, Deterministic caption for an anomaly packet — the supervision target the     rea (+11 more)

### Community 10 - "CollisionEstimatorTest"
Cohesion: 0.18
Nodes (3): CollisionEstimator, FcwConfig, CollisionEstimatorTest

### Community 11 - "AdasViewModel"
Cohesion: 0.13
Nodes (6): android, AdasViewModel, Job, RectF, StateFlow, AndroidViewModel

### Community 12 - "InferenceEngine"
Cohesion: 0.15
Nodes (13): Accelerator, CPU, GPU, InferenceEngine, Bitmap, RectF, Letterbox, ByteBuffer (+5 more)

### Community 13 - "server.py"
Cohesion: 0.19
Nodes (13): BaseHTTPRequestHandler, fetch(), Handler, init_db(), load_keys(), main(), _now_iso(), Query rows visible to `tenant` ("*" = all), with optional filters. (+5 more)

### Community 14 - "Box"
Cohesion: 0.19
Nodes (11): AnomalyEvent, EventCard(), EventLogScreen(), FilterChip(), Box, AdasApp(), MainActivity, PermissionBullet() (+3 more)

### Community 15 - "main.cpp"
Cohesion: 0.12
Nodes (6): camera_task(), esp_err_t, init_camera(), can_obd_task(), esp_err_t, init_can_obd()

### Community 16 - "SimulatedFaceLandmarkSource"
Cohesion: 0.17
Nodes (9): DmsSourceState, ERROR, RUNNING, STARTING, STOPPED, FaceLandmarkSource, CoroutineScope, StateFlow (+1 more)

### Community 17 - "train_v2.py"
Cohesion: 0.22
Nodes (15): check_dataset(), check_framework_license(), check_weights(), die(), main(), RULE 3: the detector framework itself must be commercially usable.      Returns, RULE 2: dataset must be non-IDD and its provenance acknowledged., Record an auditable provenance manifest next to the run. (+7 more)

### Community 18 - "Detection"
Cohesion: 0.24
Nodes (9): Detection, DetectionOverlay(), drawDetection(), drawFaceRedaction(), Modifier, RectF, Size, Rect (+1 more)

### Community 19 - "CollisionSignals.kt"
Cohesion: 0.16
Nodes (4): corridorHalfWidth(), isInEgoPath(), ScaleKalman, timeToCollision()

### Community 20 - ".update"
Cohesion: 0.19
Nodes (9): CollisionAssessment, Threat, ThreatLevel, CAUTION, DANGER, NONE, Track, approximateDistanceMeters() (+1 more)

### Community 21 - "EventRecorder"
Cohesion: 0.26
Nodes (6): EventRecorder, Bitmap, ByteArray, RectF, StateFlow, RecorderState

### Community 22 - "ADAS — Handover (2026-06-26)"
Cohesion: 0.17
Nodes (11): ADAS — Handover (2026-06-26), Bigger arc (don't pull focus before Phase 2 solid), Build (copy-paste, run from Windows Git Bash or any shell), Cleanup done this session, Commit this first (next session), Critical environment fact — BUILD IN WSL, NOT WINDOWS, Install to phone (Windows adb — WSL can't reach USB), Next up — Phase 2 (small, high-confidence; from `ADAS plan.txt`) (+3 more)

### Community 23 - "UploadStatus"
Cohesion: 0.18
Nodes (10): AlertLevel, CAUTION, DANGER, NONE, AlertState, UploadStatus, FAILED, IDLE (+2 more)

### Community 24 - "ADAS Project — Bug Report"
Cohesion: 0.18
Nodes (10): 1. Android: bounding boxes double-normalized → no detections drawn  **[verified]**, 2. `MultiObjectTracker(min_hits=…)` is silently ignored  **[verified]**, 3. `test_temporal.py` hard-imports `torch`, breaking the test suite  **[verified]**, 4. Settings/threshold values are decorative, not wired up, ADAS Project — Bug Report, CRITICAL, HIGH, LOW / observations (+2 more)

### Community 25 - "KalmanTracker"
Cohesion: 0.24
Nodes (8): KalmanTracker, get_state, predict, Q, R, state, uncertainty, update

### Community 26 - "ADAS — AI Dashcam for Indian Roads"
Cohesion: 0.18
Nodes (11): ADAS — AI Dashcam for Indian Roads, Android App, ESP32-P4-EYE Firmware (Archived), License, Model Tooling (PC, one-time), Repository Structure, Roadmap, Table of Contents (+3 more)

### Community 27 - "AdasCameraScreen"
Cohesion: 0.24
Nodes (7): AdasCameraScreen(), GpsReadout(), Modifier, AlertBanner(), HudMetric(), HudTopBar(), Color

### Community 28 - "convert_annotation"
Cohesion: 0.38
Nodes (9): Counter, convert_annotation(), find_file(), load_split(), main(), norm_name(), Path, Lowercase and collapse whitespace so 'Traffic  Sign' == 'traffic sign'. (+1 more)

### Community 29 - "ADAS — Session Handover"
Cohesion: 0.20
Nodes (9): ADAS — Session Handover, CRITICAL gotchas, Current state (2026-08-05), Environment, Git / commit state — READ THIS, License firewall (decided, non-negotiable), Roadmap — ALL EIGHT ITEMS IMPLEMENTED 2026-08-05, Smaller open items (+1 more)

### Community 30 - "FrameAnalyzer"
Cohesion: 0.25
Nodes (6): FrameAnalyzer, Job, RectF, Closeable, ImageAnalysis, ImageProxy

### Community 31 - "ADAS Cloud Ingestion (Phase 4 scaffold) — multi-tenant"
Cohesion: 0.22
Nodes (8): ADAS Cloud Ingestion (Phase 4 scaffold) — multi-tenant, Auth & tenancy, Connecting the phone, Endpoints, License firewall — model-agnostic, Packet shape, Run, Storage

### Community 32 - "ADAS Cloud (Phase 4) — model-agnostic infrastructure scaffolds"
Cohesion: 0.22
Nodes (5): ADAS Cloud (Phase 4) — model-agnostic infrastructure scaffolds, Data flow, Subsystems, Tests, What's real vs. scaffold

### Community 33 - "GnssLocationProvider"
Cohesion: 0.36
Nodes (3): GeoLocation, GnssLocationProvider, StateFlow

### Community 34 - "Training data: India Driving Dataset (IDD) — Detection"
Cohesion: 0.25
Nodes (8): Commercialization path (how to sell without breaching the IDD license), Compliance checklist, Consequence for THIS repo (important), Dataset License & Attribution, Preferred citation (verify the exact form on the IDD website), Third-party: Ultralytics YOLOv8 (AGPL-3.0), Training data: India Driving Dataset (IDD) — Detection, What the IDD license requires (summary — the EULA you accepted is authoritative)

### Community 35 - "Steps"
Cohesion: 0.25
Nodes (7): 1. Export YOLOv8 to ONNX, 2. Prepare Calibration Dataset, 3. Use ESP-DL Quantization Tool, 4. Integrate into Firmware, Prerequisites, Steps, YOLOv8 Quantization for ESP32-P4-EYE (ESP-DL)

### Community 36 - "Phase 3 — Fine-tune YOLOv8n for Indian roads (IDD)"
Cohesion: 0.25
Nodes (8): 0. One-time environment (WSL2 Ubuntu) — ✅ DONE (2026-07-01), 1. Get the data, 2. Convert IDD (VOC) → YOLO, 3. Fine-tune, 4. Export to TFLite + verify, 5. Wire the new model into the app  ⚠️ REQUIRED, 6. Re-verify on device (Phase 1 loop), Phase 3 — Fine-tune YOLOv8n for Indian roads (IDD)

### Community 37 - ".triggerUpload"
Cohesion: 0.38
Nodes (3): AnomalyPacket, DetectionSummary, UploadClient

### Community 38 - "HomeScreen"
Cohesion: 0.43
Nodes (6): HomeScreen(), Color, Modifier, NavButton(), NavDivider(), StatCard()

### Community 39 - "SettingsSheet"
Cohesion: 0.62
Nodes (6): DriverMonitoringSection(), ObdTelemetrySection(), SettingsInfoRow(), SettingsSectionHeader(), SettingsSheet(), SettingsThemeRow()

### Community 40 - "export_model.py"
Cohesion: 0.33
Nodes (6): copy_to_assets(), export_yolov8_tflite(), Path, export_model.py — Export YOLOv8n to TFLite for Android ADAS App ===============, Download and export YOLOv8n to TFLite format.      Parameters     ----------, Copy the exported model to the Android assets directory.

### Community 41 - "AdasScreen"
Cohesion: 0.40
Nodes (5): AdasScreen, CAMERA, EVENT_LOG, HOME, PERMISSION_REQUEST

### Community 42 - ".detect"
Cohesion: 0.40
Nodes (3): FaceBlurrer, Bitmap, RectF

### Community 43 - "SplashScreen"
Cohesion: 0.60
Nodes (4): BootStep, BootStepRow(), Color, SplashScreen()

### Community 45 - "HudBottomBar"
Cohesion: 0.67
Nodes (3): HudBottomBar(), HudButton(), Color

### Community 47 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 48 - "Android ADAS App"
Cohesion: 0.50
Nodes (4): Android ADAS App, CameraX Pipeline, Detection Overlay, Inference Engine

### Community 49 - "System Architecture"
Cohesion: 0.50
Nodes (4): Cloud Anomaly Scoring Pipeline, Full System — Edge to Cloud, On-Device Inference Pipeline, System Architecture

### Community 50 - "Plugging in a Real TFLite Model"
Cohesion: 0.50
Nodes (4): Plugging in a Real TFLite Model, Step 1 — Export YOLOv8n to TFLite, Step 2 — Add to Assets, Step 3 — Uncomment Interpreter Code

### Community 51 - "env.sh"
Cohesion: 0.50
Nodes (3): HSA_ENABLE_DXG_DETECTION, HSA_OVERRIDE_GFX_VERSION, env.sh script

### Community 53 - "Build from Source"
Cohesion: 0.67
Nodes (3): Build from Source, Prerequisites, Steps

### Community 54 - "Download & Install"
Cohesion: 0.67
Nodes (3): Download & Install, Option 1 — ADB (Recommended), Option 2 — Direct Sideload

## Knowledge Gaps
- **124 isolated node(s):** `meta`, `confirmedBugs`, `NONE`, `CAUTION`, `DANGER` (+119 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AdasViewModel` connect `AdasViewModel` to `DrowsinessAnalyzer`, `GnssLocationProvider`, `LaneDepartureTest`, `ObdBleManager`, `.triggerUpload`, `HomeScreen`, `SettingsSheet`, `HudBottomBar`, `Box`, `SimulatedFaceLandmarkSource`, `Detection`, `.update`, `EventRecorder`, `UploadStatus`, `AdasCameraScreen`?**
  _High betweenness centrality (0.232) - this node is a cross-community bridge._
- **Why does `ObdBleManager` connect `ObdBleManager` to `AdasViewModel`?**
  _High betweenness centrality (0.061) - this node is a cross-community bridge._
- **Why does `EventRecorder` connect `EventRecorder` to `AdasViewModel`?**
  _High betweenness centrality (0.049) - this node is a cross-community bridge._
- **Are the 28 inferred relationships involving `DrowsinessAnalyzer` (e.g. with `.`a blink shorter than the threshold is not a microsleep`()` and `.`a brief mouth opening is not a yawn`()`) actually correct?**
  _`DrowsinessAnalyzer` has 28 INFERRED edges - model-reasoned connections that need verification._
- **Are the 17 inferred relationships involving `Box` (e.g. with `AdasCameraScreen()` and `.updateAlertState()`) actually correct?**
  _`Box` has 17 INFERRED edges - model-reasoned connections that need verification._
- **What connects `meta`, `confirmedBugs`, `NONE` to the rest of the system?**
  _124 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `DrowsinessAnalyzer` be split into smaller, more focused modules?**
  _Cohesion score 0.06139240506329114 - nodes in this community are weakly interconnected._