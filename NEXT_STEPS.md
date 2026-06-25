# ADAS — Next Steps Plan

> Hand-off plan for continuing the project in a fresh session. Self-contained:
> a new chat can read this file and have full context. Product = the on-device
> Android app (`android_app/`). Phone is the edge node; no cloud dependency.

## Current state (as of last session)

- **Critical bug fixed:** `InferenceEngine.kt > parseOutput()` was dividing the
  YOLOv8 TFLite box coords by `INPUT_SIZE` (320) even though the export already
  emits them normalized to [0,1]. That collapsed every box into the top-left
  corner and the degenerate-box filter then dropped them all → no detections.
  Now fixed (coords used directly). **This fix is not yet verified on a device.**
- **Repo trimmed to Android scope:** removed `legacy_python_edge/`,
  `cloud_backend/`, redundant model binaries (`yolov8n.onnx/.pt`,
  `yolov8n_saved_model/`, calibration `.npy`), and `app-debug.apk`.
  `esp32_firmware/` kept as an archived reference.
- **Kept tooling:** `export_model.py` (YOLOv8n → TFLite), `check_tflite_scale.py`
  (verified the model output is normalized [0,1] — cx/cy/w/h range ~0.01–1.0).
- `.gitignore` hardened + normalized to LF. All changes are in one local commit
  (not pushed).
- Active model: stock COCO `yolov8n.tflite` (320px) in
  `android_app/app/src/main/assets/`. 80 COCO classes.

## Open issues still in the report (not yet fixed)

- `AdasViewModel.confidenceThreshold` (0.45) is never used; `InferenceEngine`
  hardcodes `0.40f`. Pick one source of truth.
- `InferenceEngine` never calls `interpreter.close()` → native leak across
  screen teardown. Add `close()` and call it from `AdasCameraScreen` `onDispose`.
- `requirements.txt` still lists torch/opencv/python-can from the deleted Python
  pipeline — trim to just what `export_model.py` / `check_tflite_scale.py` need.

---

## Phase 1 — Verify the detection fix (DO THIS FIRST)

Nothing below matters until the core loop is confirmed working.

1. Build: `cd android_app && ./gradlew assembleDebug`
   (JDK 17+, Android SDK API 36). APK → `app/build/outputs/apk/debug/`.
2. Install on a real phone (`./gradlew installDebug` or sideload) and grant
   camera permission.
3. Point at a street / or a screen showing cars & people.
4. **Acceptance:** boxes land on the correct objects at roughly correct size,
   labels/confidence look sane, no crash, usable frame rate.

**Likely next bug if boxes are offset/mis-sized:** `preprocessBitmap()` stretches
the frame to 320×320 (ignores aspect ratio), while the preview uses
`FILL_CENTER` (center-crop). Boxes are correct in normalized space but can
misalign against the cropped preview. Fix by either letterboxing the input
(pad to square, then undo the pad/scale on output coords) or matching the
preview scale type to the preprocessing. Verify with a static test image first.

## Phase 2 — Harden the app loop (small, high-confidence)

- Wire `viewModel.confidenceThreshold` into `InferenceEngine` (remove the
  hardcoded constant) OR delete the dead ViewModel flow.
- Add `InferenceEngine.close()` → call from `AdasCameraScreen` `onDispose`.
- Trust-but-verify the FPS/latency HUD readout.
- Graceful failure when `yolov8n.tflite` / `coco_labels.txt` asset is missing.
- (Optional) basic instrumentation test that feeds a known image and asserts a
  plausible detection.

## Phase 3 — Make it valuable for Indian roads (the actual thesis)

Stock COCO is the "Western data" problem the README criticizes. The
differentiated work is data + fine-tuning.

1. Collect/label Indian dashcam clips: autorickshaws, cattle, two-wheeler
   swarms, unmarked speed bumps, hand-painted signs, night/monsoon.
2. Fine-tune YOLOv8n on that set (start from `yolov8n.pt`).
3. Re-export: `python export_model.py --imgsz 320` (add `--int8` with a
   calibration set for speed). Confirm output is still normalized with
   `check_tflite_scale.py`.
4. Drop the new `.tflite` into `assets/`, update `coco_labels.txt` if classes
   changed, rebuild, re-verify (Phase 1 loop).
- **Acceptance:** measurably better detection on India-specific hazards vs stock.

## Phase 4 — Close the data loop (Phase 3 of README roadmap)

- OBD-II via ELM327 BLE adapter.
- GNSS tag on each anomaly event.
- On-device PII blurring (face + license plate) BEFORE any upload.
- Selective Wi-Fi/LTE upload of anomaly clips only. `AdasViewModel.triggerUpload()`
  is the existing stub for this.

## Phase 5 — Cloud / temporal layer (only when uploads are real)

Rebuild the ConvLSTM temporal scorer (was removed; recoverable from git history
at the pre-cleanup commit) once the phone actually uploads clips. Don't build it
before there's data to score.

---

## Guardrails / gotchas

- Don't let roadmap "vision" items (cloud platform, DePIN tokenomics, B2B
  dashboard) pull focus until Phases 1–3 are solid.
- The model output is confirmed normalized [0,1] — never re-introduce a
  `/INPUT_SIZE` divide in `parseOutput()`.
- `.gitignore` had CRLF endings that silently broke its patterns; keep it LF.
- Removed code lives in git history (pre-cleanup commit) if you need it back.

## First task for the new chat

"Build the debug APK and walk me through verifying detections render correctly
on-device (Phase 1)." Then proceed to Phase 2.
