# Handoff → Claude Code

Paste this as the opening message in a fresh Claude Code session run from the
repo root (`...\Engineering Projects and Learning\ADAS`).

---

## First prompt to use

> Read `ADAS plan.txt` for full context, then start Phase 1: build the debug APK
> and walk me through verifying detections render correctly on-device. The base
> detection fix in `InferenceEngine.parseOutput()` is committed but not yet
> verified on a device — confirm that first before anything else.

## Where things stand

- **Product:** on-device Android app in `android_app/`. Phone is the edge node,
  no cloud. Stock COCO `yolov8n.tflite` (320px) in `app/src/main/assets/`.
- **Base fix (committed, unverified):** `parseOutput()` no longer divides box
  coords by `INPUT_SIZE` — the export already emits normalized [0,1]. Phase 1
  exists to confirm this renders correctly on a device.
- **Git:** base commit `7886ca2`. Working tree has local edits to
  `InferenceEngine.kt` + `README.md`, and untracked: `ADAS plan.txt`,
  `NEXT_STEPS.md`, `android_app/_staged_aspect_fix/`.

## Phase 1 — build & verify (do first)

```
cd android_app
.\gradlew assembleDebug      # JDK 17+, Android SDK API 36
.\gradlew installDebug       # phone on USB, debugging enabled
```

APK → `app\build\outputs\apk\debug\app-debug.apk`. Point camera at a street or a
screen with cars/people. **Acceptance:** boxes on the right objects at roughly
right size, sane labels/confidence, no crash, usable FPS.

If `gradlew` can't find a JDK 17, set `JAVA_HOME` to Android Studio's bundled
JBR (`...\Android Studio\jbr`).

## If boxes render but are offset / mis-sized / rotated

A complete fix is **staged but not applied** in `android_app/_staged_aspect_fix/`
(it's outside the source set, so it does not affect the Phase 1 build). It
addresses three causes: anisotropic stretch (→ letterbox), `FILL_CENTER` vs
overlay mismatch (→ `FIT_CENTER` + mapped overlay), and missing frame rotation.
See `_staged_aspect_fix/APPLY_AND_TEST.md` for copy-over steps, the one
`AdasViewModel` snippet, the static-image test to run first, and rollback. It's
untested without a device — treat as a candidate.

## Then

- **Phase 2** (quick wins): wire `confidenceThreshold` (or delete the dead flow),
  add `InferenceEngine.close()` called from `AdasCameraScreen` `onDispose`,
  verify the FPS HUD, graceful handling of missing assets, trim `requirements.txt`.
- **Phase 3** (the actual thesis): collect/label Indian dashcam data, fine-tune
  YOLOv8n, re-export with `export_model.py`, re-verify with `check_tflite_scale.py`.

Full detail + Phases 4–5 and guardrails are in `ADAS plan.txt`.
