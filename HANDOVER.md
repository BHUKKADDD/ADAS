# ADAS — Session Handover

Pick up the **ADAS** project: an on-device AI dashcam for Indian roads. The product is
the Android app in `android_app/`; the phone is the edge node (no cloud). Repo root:
`C:\Users\MANISH KUMAR\Desktop\Engineering Projects and Learning\ADAS`.

## Current state — Phase 3 COMPLETE

YOLOv8n fine-tuned on the **India Driving Dataset (IDD)**, 12 classes, exported to TFLite,
integrated into the app, and **verified live on a Samsung Galaxy A55** (autorickshaw
detected @ 92% — a class stock COCO can't output). All work is committed AND pushed to
`origin/main` (github.com/sushant-mishra-dtu/ADAS, HEAD `38f8a87`). Working tree clean.

**The model:** 12 classes in order — person, rider, car, truck, bus, motorcycle, bicycle,
autorickshaw, animal, traffic_sign, traffic_light, vehicle_fallback (see
`android_app/.../assets/idd_labels.txt`). Deployed at
`android_app/app/src/main/assets/yolov8n.tflite` (11.6 MB float32). Trained weights:
`~/adas-data/runs/detect/idd_yolov8n/weights/best.pt` (WSL). Overall mAP50 0.284;
autorickshaw 0.49, rider 0.36; weak: traffic_light 0.05, animal 0.08, vehicle_fallback 0.03.

## CRITICAL gotchas (these will bite a fresh session)

- **NCHW planar input:** the TFLite input is `[1,3,320,320]` = channel-planar. `preprocessBitmap`
  writes all R, then all G, then all B — NOT interleaved. (Interleaved -> scrambled input ->
  near-zero scores -> detects nothing.) If you ever re-export the model, re-verify input layout.
- **Output `[1,16,2100]` is already normalized [0,1]** — NEVER divide by INPUT_SIZE in `parseOutput`.
- **FrameAnalyzer rotates** the frame by `imageInfo.rotationDegrees` before inference (portrait
  frames reach the model sideways otherwise). Preprocessing **letterboxes** (aspect-preserving)
  and un-letterboxes output; preview is **FIT_CENTER**; overlay maps boxes via `AdasViewModel.frameAspect`.
- **Build only in WSL** (Windows AF_UNIX is broken -> Gradle fails). Install via Windows adb.
- **Long WSL jobs:** launch via the Bash tool's `run_in_background`, NOT `nohup &` (WSL reaps
  disowned processes when the launching `wsl.exe` exits).
- **WSL var gotcha:** drive WSL with `wsl.exe -d Ubuntu -- bash -s <<'EOF' ... EOF` heredoc;
  `bash -lc '...'` swallows `$VARS` (they come through empty).

## Environment (all already set up)

- **Build:** `wsl.exe -d Ubuntu -- bash -s` with `JAVA_HOME=$HOME/jdk17`,
  `ANDROID_HOME=$HOME/Android/Sdk`, then `cd .../android_app && bash gradlew assembleDebug`.
- **Training venv:** `~/adas-train` (WSL, torch `2.10.0+rocm7.2.4`, ultralytics, tensorflow),
  reuses the machine's system ROCm 7.2.4. Use: `source ~/adas-train/bin/activate && source training/env.sh`
  (env.sh sets `HSA_OVERRIDE_GFX_VERSION=11.0.0` + `HSA_ENABLE_DXG_DETECTION=1` for the RX 7700 XT).
- **Dataset:** `~/adas-data/IDD_Detection` (raw) + `~/adas-data/idd_yolo` (converted). OUTSIDE the
  repo, gitignored. IDD is non-commercial — never commit/redistribute it.
- **Device:** A55 (SM-A556E) over **wireless adb** (USB is flaky — Samsung reverts to charging
  each replug). Windows adb: `C:\Users\MANISH KUMAR\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Pair via **stdin**: `printf 'CODE\n' | adb pair 192.168.x.x:PORT` (inline-code form throws a
  protocol fault). Verify detections via `adb -s <ip:port> logcat -s InferenceEngine:D` and
  `adb exec-out screencap -p > shot.png`. (`gh` CLI is NOT installed.)

## License firewall (a decided constraint)

IDD is **non-commercial research use only**. The shipped model is **v1 = research-only, NOT MIT**
(code is MIT; model isn't). The commercial roadmap (B2B SaaS, DePIN, insurance — README Phases 4–5)
is walled off as **v2**, which requires a model trained on commercially-licensed/self-collected
data. See `DATASET_LICENSE.md` and the "Commercial firewall" in the README roadmap. Don't build
Phase 4/5 commercial features against the v1 IDD model.

## Training pipeline (`training/`)

`idd_to_yolo.py` (IDD VOC -> YOLO), `train.py` (fine-tune), `export_model.py` (-> TFLite, root),
`check_tflite_scale.py` (verify, root), `training/README.md` (full workflow).

## What's left (nothing is blocking — thesis is done)

1. INT8 re-export + release build for speed (~5 FPS debug -> 20 FPS target).
2. Real **outdoor** road test (only tested off a monitor so far).
3. Improve weak classes (more epochs / higher res / class balance).
4. (Optional) reconcile README <-> `ADAS plan.txt` phase-number mismatch (OBD/GNSS/PII/upload
   live under Phase 3 in README but Phase 4 in the plan).
5. **v2 track (commercial):** data-acquisition strategy, then Phase 4 (OBD-II/ELM327, GNSS,
   on-device PII blur, selective upload) and Phase 5 (cloud temporal scorer). All gated on v2 data.

**Note:** tooling auto-commits and pushes directly to `main`. If you want a PR-based flow,
branch off `main` for new work.

First thing to do: say which thread to pick up (INT8/perf, outdoor test, model accuracy, or the
v2 commercial-data plan).
