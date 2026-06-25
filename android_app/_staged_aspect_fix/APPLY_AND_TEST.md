# Staged fix — bounding-box alignment (Phase 1 fallback)

**Status:** staged, NOT applied. Your live source is untouched so the Phase 1
build still verifies the existing `parseOutput()` fix in isolation. Apply this
**only if** boxes render but are offset / mis-sized / rotated.

## What it fixes

Three separate causes of misalignment, none of which the current code handles:

1. **Anisotropic stretch** — `preprocessBitmap()` squashed the frame to 320×320,
   distorting object shapes and hurting detection quality. (Note: a pure stretch
   does *not* by itself shift boxes in normalized space — it mainly costs
   accuracy.) → replaced with **letterboxing** (aspect-preserving pad to square),
   and the pad/scale is undone on output coords so boxes come back normalized to
   the original frame.
2. **Preview vs overlay mismatch** — preview used `FILL_CENTER` (center-crops the
   frame) while the overlay drew across the full view. → preview switched to
   `FIT_CENTER`; the overlay now maps boxes into the same letterboxed content rect.
3. **Missing rotation** — inference ran on the raw sensor buffer, ignoring
   `rotationDegrees`, so detections could be rotated vs the display. → the frame
   is rotated to display orientation before inference, and its aspect ratio is
   reported to the overlay.

## Files

- `InferenceEngine.kt`   — letterbox preprocessing + un-letterbox in `parseOutput`
- `FrameAnalyzer.kt`     — rotate to display orientation + report frame aspect
- `DetectionOverlay.kt`  — map boxes into the FIT_CENTER content rect
- `AdasCameraScreen.kt`  — `FIT_CENTER` + wire `onFrameAspect`
- `AdasViewModel.kt`     — **not provided as a full file**; add the snippet below

## Apply

1. Commit or stash your current state first (so this is reversible):
   ```
   git add -A && git commit -m "Phase 1: verified base detection fix"
   ```
2. Copy the four staged files over their originals in
   `app/src/main/java/com/example/adas/`:
   - `InferenceEngine.kt`, `FrameAnalyzer.kt`, `DetectionOverlay.kt`, `AdasCameraScreen.kt`
3. Add this to **`AdasViewModel.kt`**, in the `// ── Detection Results ──` section
   (right after the `detections` StateFlow is a good spot):
   ```kotlin
   // Aspect ratio (width/height) of the upright camera frame; 0 = unknown.
   // Lets the overlay map boxes into the FIT_CENTER letterbox rect.
   private val _frameAspect = MutableStateFlow(0f)
   val frameAspect: StateFlow<Float> = _frameAspect.asStateFlow()

   fun updateFrameAspect(ratio: Float) {
       _frameAspect.value = ratio
   }
   ```
4. Rebuild: `.\gradlew assembleDebug`

## Test (do the static-image check first, per the plan)

1. **Static image, before the phone:** drop a known photo (street with a couple
   of cars/people) into `assets/`, load it as a `Bitmap`, run `engine.detect()`,
   and log the boxes. Confirm coords are sane (e.g. a car on the right side has
   `left`/`right` near the right, not collapsed to a corner) before trusting the
   live overlay. This isolates the inference math from camera/preview variables.
2. **On device:** install, point at cars/people. Boxes should sit on the objects
   at correct size and orientation, in both portrait and landscape.

## Rollback

`git checkout -- app/src/main/java/com/example/adas/` (or `git reset --hard` to
the commit from step 1).

## Caveats — verify on device

- Tuned for the **back camera** (`DEFAULT_BACK_CAMERA`). A front camera would
  also need horizontal mirroring.
- Rotating every frame adds a small per-frame cost; if FPS drops noticeably,
  consider rotating only the model input or handling rotation via CameraX
  target rotation instead.
- The letterbox un-pad math assumes the TFLite export emits coords normalized to
  `[0,1]` of the input square (confirmed earlier via `check_tflite_scale.py`).
  Never re-introduce a `/INPUT_SIZE` divide.
