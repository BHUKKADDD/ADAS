# ADAS Project — Bug Report

Reviewed: Python edge (`legacy_python_edge`), cloud backend, ESP32 firmware, Android app.
Findings are ordered by severity. Items marked **[verified]** were reproduced or confirmed empirically during review.

---

## CRITICAL

### 1. Android: bounding boxes double-normalized → no detections drawn  **[verified]**
**File:** `android_app/.../InferenceEngine.kt` → `parseOutput()`

The YOLOv8n TFLite model output for `cx, cy, w, h` is **already normalized to [0, 1]**. I confirmed this against the actual model in assets:

```
Output shape: [1, 84, 2100]
row0 cx min/max: 0.0097 .. 0.988
row1 cy min/max: 0.0106 .. 0.990
row2 w  min/max: 0.0193 .. 1.037
row3 h  min/max: 0.0179 .. 0.993
```

But the code divides those values by `INPUT_SIZE` (320) a second time:

```kotlin
val cxNorm = cx / INPUT_SIZE   // cx is already 0..1 → becomes ~0.003
val bwNorm = bw / INPUT_SIZE
```

Effect: every box collapses to a ~0.003-wide sliver in the top-left corner. The later guard
`if (box.width() < 0.01f || box.height() < 0.01f) continue` then discards essentially **all** of them, so the overlay shows nothing (or garbage). This breaks the core feature of the app.

**Fix:** treat the values as already normalized — remove the `/ INPUT_SIZE`:
```kotlin
val left = cx - bw / 2f
val top  = cy - bh / 2f
val right = cx + bw / 2f
val bottom = cy + bh / 2f
```
(The `scaleX`/`scaleY` computed at the top of `parseOutput` are also unused dead code — a leftover from this same scaling confusion.)

---

## HIGH

### 2. `MultiObjectTracker(min_hits=…)` is silently ignored  **[verified]**
**File:** `legacy_python_edge/src/kalman_tracker.py`

`MultiObjectTracker.__init__` stores `self._min_hits`, but track confirmation is decided by
`KalmanBoxTracker.is_confirmed`, which compares against the **module-level constant**:

```python
@property
def is_confirmed(self) -> bool:
    return self.hits >= DEFAULT_MIN_HITS   # ignores the tracker's min_hits
```

So `MultiObjectTracker(min_hits=1)` (or any value) has no effect. Verified:
```
MultiObjectTracker(min_hits=1); after 1 frame → confirmed tracks returned: 0   # expected 1
```
The existing test masks this because it feeds 3 frames (hits reaches the default of 3 regardless).

**Fix:** pass `min_hits` into each `KalmanBoxTracker` and have `is_confirmed` use the instance value.

---

## MEDIUM

### 3. `test_temporal.py` hard-imports `torch`, breaking the test suite  **[verified]**
**File:** `legacy_python_edge/tests/test_temporal.py` (line 17: `import torch`)

The module imports `torch` at top level. When PyTorch isn't installed, pytest fails at **collection**
(`ModuleNotFoundError: No module named 'torch'`), aborting the whole run — even though the project is
explicitly designed to run without torch (`temporal_model.py` falls back to `MockTemporalScorer`) and the
file's own docstring says it is "fully self-contained."

**Fix:** import torch lazily inside the torch-only tests, or guard with
`pytest.importorskip("torch")` so the torch-free tests still run.

### 4. Settings/threshold values are decorative, not wired up
**File:** `android_app/.../AdasViewModel.kt`

`confidenceThreshold` (0.45) is exposed but never consumed — `InferenceEngine` hardcodes its own
`CONFIDENCE_THRESHOLD = 0.40f`. The flow is also built as `MutableStateFlow(0.45f).asStateFlow()` with no
backing reference, so it can never change. Same pattern for `showDistanceEstimates`, `showScanLine`,
`hudOpacity` (these last three are at least read by the overlay). Net effect: the two thresholds disagree
and neither is adjustable.

---

## LOW / observations

5. **TFLite `Interpreter` never closed.** `InferenceEngine` has no `close()`, and `AdasCameraScreen`
   creates it via `remember { InferenceEngine(context) }` but never releases it on dispose → native
   interpreter leak across screen recompositions/teardown.

6. **`ClipBuffer` only captures pre-event frames.** The docstring promises frames "BEFORE and AFTER" the
   event, and `_buffer_capacity` budgets `+ post_event_frames`, but `capture_clip()` returns only the last
   `pre` frames — post-event context is never collected. Doc/behavior mismatch.

7. **ESP32 firmware is non-functional scaffolding.** All `CAM_PIN_*` are `-1`, so `init_camera()` always
   returns `ESP_ERR_NOT_SUPPORTED` and `camera_task` immediately `vTaskDelete`s itself; the capture/
   inference/encoder queues are all commented out. Expected for a stub, but the board pins must be filled
   in before anything runs. (`kalman_tracker.cpp` also uses a constant-position model — no velocity term.)

8. **Minor races on stat counters.** `ClipBuffer._frames_buffered` and `CameraModule` counters are
   incremented outside their locks. Benign (stats only), but technically a data race.

9. **`DataLogger` filename collisions.** Filenames are `YYYYMMDD_HHMMSS_mmm`; two events in the same
   millisecond overwrite each other. Low probability at 15 fps, but possible for clip+packet bursts.

---

## What works well
- `adaptive_threshold.py` and `kalman_tracker.py` core math are sound; 51/51 of the runnable unit tests pass.
- IoU, greedy matching, box round-trip conversions, and the trajectory analyzer are correct.
- `cloud_scorer.py` and `temporal_model.py` (incl. mock fallback) are well structured.
