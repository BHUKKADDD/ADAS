<div align="center">

# ADAS — AI Dashcam for Indian Roads

**A full-stack, on-device Advanced Driver Assistance System built for India's chaotic, unstructured traffic — powered by a native Android app running real-time object detection entirely on your smartphone's camera, with zero cloud dependency.**

[![Android](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#android-adas-app)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#tech-stack)
[![TFLite](https://img.shields.io/badge/Inference-TensorFlow%20Lite-FF6F00?style=for-the-badge&logo=tensorflow&logoColor=white)](#inference-engine)
[![CameraX](https://img.shields.io/badge/Camera-CameraX%201.4-4285F4?style=for-the-badge&logo=google&logoColor=white)](#camerax-pipeline)
[![APK](https://img.shields.io/badge/Download-Debug%20APK%20%7E27MB-4CAF50?style=for-the-badge)](#download--install)
[![Personal Project](https://img.shields.io/badge/Project-Personal-FF4081?style=for-the-badge&logo=github&logoColor=white)](#)

</div>

---

## Table of Contents

- [The Problem — Why India Needs Its Own ADAS](#the-problem--why-india-needs-its-own-adas)
- [The Solution — Phone as the Edge Node](#the-solution--phone-as-the-edge-node)
- [System Architecture](#system-architecture)
- [Android ADAS App](#android-adas-app)
  - [CameraX Pipeline](#camerax-pipeline)
  - [Inference Engine](#inference-engine)
  - [Detection Overlay](#detection-overlay)
- [ESP32-P4 Firmware](#esp32-p4-eye-firmware-archived)
- [Download & Install](#download--install)
- [Build from Source](#build-from-source)
- [Plugging in a Real Model](#plugging-in-a-real-tflite-model)
- [Tech Stack](#tech-stack)
- [Roadmap](#roadmap)
- [License](#license)

---

## The Problem — Why India Needs Its Own ADAS

Standard ADAS systems are trained on orderly Western roads — clear lane markings, rule-following drivers, predictable intersections. They **consistently fail in India**.

| Problem | Root Cause |
|---------|-----------|
| False emergency braking | Autorickshaws cutting lanes, hand-painted road signs |
| Sensor confusion | Missing lane markings, unmarked speed bumps |
| Animal blindness | Cows, dogs, camels as static road obstacles |
| Weather failure | Monsoon rain, dust storms, unlit night roads |
| Intersection deadlock | Unregulated multi-way junctions |

> **The bottleneck isn't compute power — it's the absence of India-specific, edge-case-rich training data.**

Western open datasets (nuScenes, Waymo, KITTI) contain essentially zero representation of Indian road conditions.

---

## The Solution — Phone as the Edge Node

Instead of dedicated hardware, we turn every Android phone into an ADAS sensor:

- **Camera** — High-resolution sensor already built in
- **NPU/GPU** — Runs quantized TFLite models at real-time frame rates
- **Connectivity** — Wi-Fi / LTE already present for selective cloud upload
- **Power** — Charged via car USB / wireless pad

This eliminates the need for a Raspberry Pi, ESP32, or any dedicated hardware — your daily driver phone **is** the edge node.

---

## System Architecture

### Full System — Edge to Cloud

```mermaid
flowchart TB
    classDef phone    fill:#1a1a2e,stroke:#3DDC84,stroke-width:2px,color:#3DDC84,font-weight:bold
    classDef camera   fill:#0f3460,stroke:#00E5FF,stroke-width:2px,color:#00E5FF
    classDef ml       fill:#16213e,stroke:#FF6F00,stroke-width:2px,color:#FF9800
    classDef overlay  fill:#0f3460,stroke:#E040FB,stroke-width:2px,color:#E040FB
    classDef cloud    fill:#1a1a2e,stroke:#4285F4,stroke-width:2px,color:#64B5F6
    classDef data     fill:#0d1b2a,stroke:#69F0AE,stroke-width:2px,color:#69F0AE
    classDef decision fill:#1a0a2e,stroke:#FF4081,stroke-width:2px,color:#FF8A80

    subgraph PHONE ["Android ADAS App — On-Device"]
        direction TB
        CAM["Rear Camera\nMIPI Sensor"]:::camera
        CX["CameraX\nImageAnalysis"]:::camera
        FA["FrameAnalyzer\nBackground Coroutine"]:::camera
        IE["InferenceEngine\nTFLite INT8 · YOLOv8n @ 320px"]:::ml
        DET["Detection Results\nlabel · confidence · boundingBox"]:::ml
        OV["DetectionOverlay\nCompose Canvas · Transparent"]:::overlay
        PV["PreviewView\nFull-Screen Camera Feed"]:::camera
    end

    subgraph CLOUD ["Cloud Backend"]
        direction LR
        ING["Ingestion API"]:::cloud
        CONV["ConvLSTM\nTemporal Scorer"]:::cloud
        ANN["Annotation Store\n3D Scene Graphs"]:::data
        VLM["VLM Fine-tuning\nLoRA + Projection MLP"]:::data
        DASH["B2B SaaS\nDashboard"]:::cloud
    end

    CAM --> CX --> FA --> IE --> DET --> OV
    PV -. "rendered behind" .-> OV

    PHONE -- "Wi-Fi / LTE\nAnomaly clips only" --> ING
    ING --> CONV --> ANN --> VLM --> DASH
```

### On-Device Inference Pipeline

```mermaid
flowchart LR
    classDef hw     fill:#0f3460,stroke:#00E5FF,stroke-width:2px,color:#00E5FF
    classDef thread fill:#16213e,stroke:#FF9800,stroke-width:2px,color:#FF9800
    classDef infer  fill:#1a0a2e,stroke:#FF6F00,stroke-width:2px,color:#FF9800,font-weight:bold
    classDef ui     fill:#0f3460,stroke:#E040FB,stroke-width:2px,color:#E040FB
    classDef gate   fill:#1a1a2e,stroke:#FF4081,stroke-width:2px,color:#FF8A80

    CAM["Camera\nMIPI"]:::hw
    ANAL["ImageAnalysis\nuse-case"]:::hw
    PROXY["ImageProxy\nYUV_420_888"]:::thread
    BMP["Bitmap\nConversion\ntoBitmap"]:::thread
    INF["TFLite\nInterpreter\nDispatchers.Default"]:::infer
    FILT{"Confidence\n≥ 0.4?"}:::gate
    OVER["DetectionOverlay\nCompose Canvas\nDispatchers.Main"]:::ui
    DROP["Drop Frame"]:::gate

    CAM --> ANAL --> PROXY --> BMP --> INF --> FILT
    FILT -- Yes --> OVER
    FILT -- No --> DROP
```

### Cloud Anomaly Scoring Pipeline

```mermaid
flowchart TD
    classDef ingest fill:#0f3460,stroke:#4285F4,stroke-width:2px,color:#64B5F6
    classDef score  fill:#16213e,stroke:#FF6F00,stroke-width:2px,color:#FF9800
    classDef store  fill:#0d1b2a,stroke:#69F0AE,stroke-width:2px,color:#69F0AE
    classDef gate   fill:#1a0a2e,stroke:#FF4081,stroke-width:2px,color:#FF8A80

    UPLOAD["Anomaly Clip Upload\n.npz — frames + telemetry"]:::ingest
    LOAD["Load Clip\nCloudAnomalyScorer"]:::score
    CONV["ConvLSTM\nSequence Inference\n~500K params"]:::score
    SCORE{"Temporal\nScore ≥ 0.6?"}:::gate
    HIGH["HIGH Priority\nFast-track for annotation"]:::store
    LOW["STANDARD\nQueue for batch review"]:::store
    ANNOT["Annotation Store\n3D Scene Graphs + Labels"]:::store
    VLM["VLM Training Pipeline\nLoRA Fine-tuning"]:::store

    UPLOAD --> LOAD --> CONV --> SCORE
    SCORE -- Yes --> HIGH --> ANNOT
    SCORE -- No  --> LOW  --> ANNOT
    ANNOT --> VLM
```

---

## Android ADAS App

The app is written in **Kotlin with Jetpack Compose**. It is a single-activity application that:

1. Requests camera permission at runtime
2. Opens the rear camera full-screen via `CameraX PreviewView`
3. Extracts frames on a background coroutine via `CameraX ImageAnalysis`
4. Runs TFLite detection inference without blocking the UI thread
5. Draws colored bounding boxes on a transparent Compose Canvas overlay

### CameraX Pipeline

**[`AdasCameraScreen.kt`](android_app/app/src/main/java/com/example/adas/AdasCameraScreen.kt)**

Binds two CameraX use-cases simultaneously in a `LaunchedEffect`:

| Use-case | Purpose |
|----------|---------|
| `Preview` | Renders the live camera feed into a `PreviewView` |
| `ImageAnalysis` | Delivers `ImageProxy` frames to `FrameAnalyzer` |

```kotlin
val imageAnalysis = ImageAnalysis.Builder()
    .setResolutionSelector(ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build())
    .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST) // drop stale frames
    .build()
    .also { it.setAnalyzer(executor, analyzer) }

cameraProvider.bindToLifecycle(lifecycleOwner, BACK_CAMERA, preview, imageAnalysis)
```

**[`FrameAnalyzer.kt`](android_app/app/src/main/java/com/example/adas/FrameAnalyzer.kt)**

Implements `ImageAnalysis.Analyzer`. Uses CameraX's built-in `ImageProxy.toBitmap()` extension for efficient YUV→Bitmap conversion, then launches inference on `Dispatchers.Default`.

```kotlin
override fun analyze(image: ImageProxy) {
    val bitmap = image.toBitmap()   // CameraX built-in extension
    image.close()
    analyzerScope.launch {
        val results = engine.detect(bitmap)
        withContext(Dispatchers.Main) { onResults(results) }
    }
}
```

### Inference Engine

**[`InferenceEngine.kt`](android_app/app/src/main/java/com/example/adas/InferenceEngine.kt)**

Provides a `suspend fun detect(bitmap: Bitmap): List<Detection>` that runs on `Dispatchers.Default`.

- **Mock mode (default):** Ships with animated deterministic bounding boxes (vehicle, person, motorcycle) so the overlay renders and can be validated immediately without a model file.
- **Real mode:** Drop a `.tflite` file in `assets/` and uncomment the `Interpreter` block. See [Plugging in a Real Model](#-plugging-in-a-real-tflite-model).

```kotlin
data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF  // normalized [0,1] coordinates
)
```

### Detection Overlay

**[`DetectionOverlay.kt`](android_app/app/src/main/java/com/example/adas/DetectionOverlay.kt)**

A Compose `Canvas` composable layered over the `PreviewView`. On every new `List<Detection>` it draws:

- **Colored bounding box** — each class has a distinct color (Cyan = vehicle, Pink = person, Amber = motorcycle)
- **Corner accent marks** — tactical HUD-style corner brackets
- **Label badge** — filled rectangle with class name + confidence percentage

Color mapping:
```kotlin
val labelColors = mapOf(
    "vehicle"    to Color(0xFF00E5FF),  // Cyan
    "person"     to Color(0xFFFF4081),  // Pink
    "motorcycle" to Color(0xFFFFD740),  // Amber
    "truck"      to Color(0xFF69F0AE),  // Green
    "bus"        to Color(0xFFE040FB),  // Purple
)
```

> **Note:** The cloud backend (ConvLSTM temporal scorer, ingestion API, annotation
> pipeline) and the original Raspberry Pi Python edge pipeline are **planned/archived**
> and are no longer part of this repository — see the [Roadmap](#roadmap) (Phase 4).
> The shipping product is the on-device Android app.

---

## ESP32-P4-EYE Firmware (Archived)

An intermediate hardware exploration — a FreeRTOS/C++ firmware scaffold for the ESP32-P4-EYE microcontroller. Archived in `esp32_firmware/` for reference.

| File | Role |
|------|------|
| `main.cpp` | `app_main()` entry point — spawns FreeRTOS tasks |
| `camera_task.cpp` | MIPI-CSI camera capture stub |
| `inference_task.cpp` | ESP-DL TFLite inference stub |
| `h264_encoder_task.cpp` | Hardware H.264 encoder / rolling buffer stub |
| `can_obd_task.cpp` | TWAI (CAN bus) OBD-II communication stub |
| `kalman_tracker.cpp` | C++ Kalman filter implementation |
| `yolov8_quantization_guide.md` | Steps to convert `.pt` → INT8 `.tflite` for ESP-DL |

> The ESP32-P4 architecture was superseded by the Android app, which provides superior processing power, camera quality, and connectivity with zero additional hardware cost.

---

## Download & Install

> Works on any Android phone running **Android 7.0 (API 24) or higher**.

**Direct Download:**
[Download app-debug.apk](./app-debug.apk) (~27 MB)

### Option 1 — ADB (Recommended)

```powershell
# Enable Developer Options → USB Debugging on your phone
# Connect via USB, then run from the project root:

$env:PATH += ";C:\Users\MANISH KUMAR\AppData\Local\Android\Sdk\platform-tools"
adb install android_app\app\build\outputs\apk\debug\app-debug.apk
```

### Option 2 — Direct Sideload

1. Transfer `app-debug.apk` to your phone (USB / Google Drive / email)
2. **Settings → Apps → Special App Access → Install Unknown Apps** → allow your file manager
3. Tap the APK file to install

> **Debug build only.** For a release build, run `./gradlew assembleRelease` and sign with your keystore.

---

## Build from Source

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| JDK | 17+ | Gradle build system |
| Android SDK | API 36 | Build target |

### Steps

```bash
# 1. Clone the repo
git clone https://github.com/BHUKKADDD/ADAS.git
cd ADAS/android_app

# 2. Build the debug APK
./gradlew assembleDebug

# APK output:
# app/build/outputs/apk/debug/app-debug.apk

# 3. Install directly (device must be connected via ADB)
./gradlew installDebug
```

On first build, Gradle will automatically download all dependencies (CameraX, TFLite, Accompanist). This takes ~2–3 minutes.

---

## Plugging in a Real TFLite Model

The `InferenceEngine` ships with mock detections. Replacing them with a real model takes 3 steps:

### Step 1 — Export YOLOv8n to TFLite

```bash
pip install ultralytics
yolo export model=yolov8n.pt format=tflite imgsz=320
# Output: yolov8n_float32.tflite (or int8 if calibration provided)
```

For maximum performance on Android, use INT8 quantization:
```bash
yolo export model=yolov8n.pt format=tflite imgsz=320 int8=True
```

### Step 2 — Add to Assets

```
android_app/app/src/main/assets/yolov8n.tflite
```

### Step 3 — Uncomment Interpreter Code

In [`InferenceEngine.kt`](android_app/app/src/main/java/com/example/adas/InferenceEngine.kt), uncomment the `Interpreter` block and replace the mock output section with real tensor I/O:

```kotlin
// 1. Load model
val model = FileUtil.loadMappedFile(context, "yolov8n.tflite")
val options = Interpreter.Options().apply { numThreads = 4 }
val interpreter = Interpreter(model, options)

// 2. Preprocess bitmap → input tensor
// 3. Run: interpreter.run(inputTensor, outputBuffer)
// 4. Parse output boxes + scores → List<Detection>
```

---

## Tech Stack

### Android App

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose |
| Camera | CameraX 1.4.2 (Preview + ImageAnalysis) |
| ML Inference | TensorFlow Lite 2.16 |
| Concurrency | Kotlin Coroutines |
| Permissions | Accompanist Permissions 0.37 |
| Build System | Gradle 9.1 + AGP 9.0 |
| Min SDK | API 24 (Android 7.0) |
| Target SDK | API 36 |

### Model Tooling (PC, one-time)

| Component | Technology |
|-----------|-----------|
| Export | Ultralytics YOLOv8 Nano → TFLite (`export_model.py`) |
| Verification | `check_tflite_scale.py` (TFLite output sanity check) |

> **License note:** Ultralytics YOLOv8 is **AGPL-3.0**. Fine for this project's
> non-commercial v1; any commercial v2 needs an Ultralytics Enterprise License or a
> permissively-licensed detector. The Ultralytics dependency is isolated to `train.py` and
> `export_model.py` (the data-conversion step and YOLO label format are framework-agnostic),
> so the swap is a small, contained change — see the [Commercial firewall](#roadmap).

---

## Roadmap

- [x] **Phase 1 — Python Edge MVP** *(Complete)*
  - [x] Threaded OpenCV capture with ring buffer
  - [x] YOLOv8 Nano inference + active learning uncertainty scoring
  - [x] OBD-II telemetry simulation (10 Hz mock)
  - [x] Memory-safe pipeline with GC and resource monitoring

- [x] **Phase 1.5 — Advanced Edge Intelligence** *(Complete)*
  - [x] Multi-object Kalman tracker with persistent IDs
  - [x] Trajectory anomaly detection (erratic motion scoring)
  - [x] Adaptive threshold with automatic environment classification
  - [x] Rolling clip buffer for temporal context capture
  - [x] ConvLSTM spatio-temporal anomaly scorer (cloud-side)
  - [x] 78 automated tests with full coverage

- [x] **Phase 2 — Android ADAS App** *(Complete)*
  - [x] Native Android app (Kotlin + Jetpack Compose)
  - [x] Full-screen CameraX rear camera preview
  - [x] Asynchronous background inference pipeline (Coroutines)
  - [x] TensorFlow Lite inference engine scaffold
  - [x] Real-time transparent bounding box overlay with class colors
  - [x] Runtime camera permission gating
  - [x] Debug APK built and ready to install

- [ ] **Phase 3 — Real Model Integration (v1 · IDD)** *(model live on-device)*
  - [x] YOLOv8n `.tflite` fine-tuned on the India Driving Dataset (IDD) — 12 India-
        specific classes; integrated and **verified on a Galaxy A55** (autorickshaw
        detected @ 92%, a class stock COCO cannot output)
  - [x] INT8 quantization + release build for speed — 12–15 FPS on a Galaxy A55
        (up from ~5 FPS debug/float32; 3.2 MB model, confidence threshold retuned
        0.40 → 0.30 for quantized score compression)
  - [ ] OBD-II Bluetooth integration via ELM327 BLE adapter
  - [ ] GNSS geolocation tagging on each anomaly packet
  - [ ] Selective Wi-Fi / LTE upload to cloud ingestion API
  - [ ] On-device PII blurring (face + license plate, MediaPipe)

---

> ### ⚠️ Commercial firewall — v1 (research) → v2 (commercial)
>
> Everything above runs on the **v1 model, fine-tuned on the India Driving Dataset
> (IDD)**, licensed for **non-commercial research use only** (see
> [DATASET_LICENSE.md](DATASET_LICENSE.md)). **Every phase below is commercial and so
> cannot use the v1 model.**
>
> **Go-to-market — license the pipeline, not the model.** The commercial offering is the
> **method + pipeline + engineering know-how**, not the IDD-trained weights. A partner
> (OEM / fleet / insurer) brings their **own** road data; the pipeline
> ([`idd_to_yolo.py`](training/idd_to_yolo.py) → [`train.py`](training/train.py) →
> [`export_model.py`](export_model.py)) trains a **v2 model on that data**, which is
> cleanly theirs to commercialize. The IDD dataset and the v1 `.tflite` never change
> hands, so the non-commercial license is never triggered.
>
> Two hard rules for any commercial v2 build:
> 1. **Train from stock `yolov8n.pt`, never from the IDD `best.pt`** — fine-tuning off the
>    IDD checkpoint would make the result an IDD derivative and drag the non-commercial
>    license back in.
> 2. **Ultralytics YOLOv8 is AGPL-3.0.** Commercial use needs an
>    [Ultralytics Enterprise License](https://www.ultralytics.com/license) (the partner's to
>    procure) **or** a swap to a permissively-licensed detector. The pipeline isolates
>    Ultralytics to two thin scripts, so the swap is a small, contained change.

- [ ] **Phase 4 — Cloud Platform** *(requires v2 model)*
  - [ ] **Source commercial-use data & train v2** — either license the pipeline to a
        partner who supplies their own road data, or self-collect / commercially-license a
        dataset; train the **v2 model** (from stock weights) to replace IDD
  - [ ] Data lake ingestion pipeline (AWS S3 / GCS)
  - [ ] 3D scene graph annotation pipeline
  - [ ] VLM fine-tuning infrastructure (LoRA + Projection MLP)
  - [ ] B2B SaaS dashboard for OEM customers

- [ ] **Phase 5 — Consumer & Scale** *(requires v2 model)*
  - [ ] DePIN tokenomics (data contribution rewards)
  - [ ] Usage-based insurance API integration
  - [ ] Driver drowsiness detection (face landmark model)
  - [ ] Forward collision warning audio alerts
  - [ ] Fleet management portal
  - [ ] Multi-market expansion (Southeast Asia, Africa, LATAM)

---

## Repository Structure

```
ADAS/
├── android_app/                        # Native Android ADAS App
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/example/adas/
│   │   │   │   ├── MainActivity.kt     # Entry point + permission gate
│   │   │   │   ├── AdasCameraScreen.kt # CameraX binding + layout
│   │   │   │   ├── FrameAnalyzer.kt    # Background frame extractor
│   │   │   │   ├── InferenceEngine.kt  # TFLite wrapper (mock + real)
│   │   │   │   ├── DetectionOverlay.kt # Compose Canvas overlay
│   │   │   │   └── Detection.kt        # Data class
│   │   │   └── assets/                 # ← Put yolov8n.tflite here
│   │   └── build/outputs/apk/debug/
│   │       └── app-debug.apk           # Prebuilt APK (~27 MB)
│   ├── build.gradle.kts
│   └── gradle/libs.versions.toml
│
├── esp32_firmware/                     # ESP32-P4 C++ firmware (archived)
│   └── main/
│       ├── main.cpp
│       ├── camera_task.cpp
│       ├── inference_task.cpp
│       ├── h264_encoder_task.cpp
│       ├── can_obd_task.cpp
│       └── kalman_tracker.cpp
│
├── export_model.py                     # YOLOv8n → TFLite export (run on PC)
├── check_tflite_scale.py               # TFLite output sanity check
├── requirements.txt
└── README.md
```

---

## License

**Code:** MIT — see [LICENSE](LICENSE).

**Training data & fine-tuned model:** The Phase 3 model is fine-tuned on the
[India Driving Dataset (IDD)](https://idd.insaan.iiit.ac.in/), licensed for
**non-commercial research use only**. The resulting `yolov8n.tflite` is a derivative
of IDD and is therefore **non-commercial / research-use only — NOT covered by the MIT
license above**. Cite IDD (Varma et al., WACV 2019) in any work that uses it, do not
redistribute the dataset, and blur faces / license plates in any published sample
images. Full terms and the code-vs-model split are in
[DATASET_LICENSE.md](DATASET_LICENSE.md).

> ⚠️ **Commercialization:** the commercial roadmap (B2B SaaS, DePIN, insurance) **cannot**
> use the IDD-trained model. The intended path is to **license the pipeline** to a partner
> who trains a **v2 model on their own data** (from stock weights, not the IDD checkpoint).
> Note that **Ultralytics YOLOv8 is AGPL-3.0** — commercial use requires an Ultralytics
> Enterprise License or a permissive-detector swap. See the [Commercial firewall](#roadmap).

---

<div align="center">

**A personal project by [Sushant](https://github.com/sushant-mishra-dtu)**

**Making India's roads safer, one edge case at a time.**

</div>
