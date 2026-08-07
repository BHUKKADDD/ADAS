# ADAS — Session Handover

Pick up the **ADAS** project: an on-device AI dashcam for Indian roads. The product is
the Android app in `android_app/` (Kotlin + Compose + TFLite); the phone is the edge node
(no cloud dependency). New: `cloud/` holds the Phase-4 model-agnostic server scaffolds.
Repo root (WSL): `/mnt/c/Users/MANISH KUMAR/Desktop/Engineering Projects and Learning/ADAS`.

## Current state (2026-08-05)

**Phase 3 — every item now has a working, on-device-verified slice:**

| Item | Status |
| --- | --- |
| YOLOv8n/IDD 12-class model, INT8 3.2 MB, 12–15 FPS on Galaxy A55 | ✅ done — **outdoor road test passed** (user-run, reported 2026-08-05). No longer an open gap. |
| OBD-II ELM327 **BLE** telemetry (`obd/` package) | 🟡 multi-PID: speed 010D + RPM 010C + coolant 0105 + throttle 0111 (generic `parseMode01`, round-robin poller, sim emits all four, Settings "Engine" row, fields ride in `AnomalyPacket`→JSONL lake). 22/22 parser tests + build green (2026-07-07); **on-device sim re-check pending** (A55 unreachable), real-adapter test pending (no hardware) |
| GNSS geotagging (`geo/`) | ✅ verified live (HUD readout + tags packets; also HUD speed fallback via GPS speed-over-ground) |
| Selective upload (`upload/`) | ✅ verified live: JSON `AnomalyPacket` → Wi-Fi-gated POST → HTTP 200 (metadata-only; no clip bytes yet — REC is still a stub) |
| PII blur (`privacy/`) | 🟡 face-only, framework `FaceDetector`, redacts the **live overlay**; verified live (opaque PII box on a real face). Plate blur + MediaPipe + stored-frame scrubbing open |

**The model:** 12 classes in order — person, rider, car, truck, bus, motorcycle, bicycle,
autorickshaw, animal, traffic_sign, traffic_light, vehicle_fallback
(`android_app/.../assets/idd_labels.txt`). Deployed at
`android_app/app/src/main/assets/yolov8n.tflite` (3.2 MB INT8). Trained weights:
`~/adas-data/runs/detect/idd_yolov8n/weights/best.pt` (WSL). mAP50 0.284 overall;
autorickshaw 0.49, rider 0.36; weak: traffic_light 0.05, animal 0.08, vehicle_fallback 0.03.
Confidence threshold 0.30 (INT8 compresses scores).

**Phase 4 (model-agnostic infra only — see firewall). All four scaffolds built + tested
(see `cloud/README.md`). ✅ RE-VERIFIED 2026-08-05: `cloud/tests/` now holds **80 committed
tests** (`python3 -m unittest discover -s cloud/tests -v`), all passing. They replace the
old ad-hoc "10/10"/"8/8" scripts that were never committed. Found and fixed one real bug in
the process: `?limit=<non-numeric>` raised inside `fetch()` and killed the handler thread,
dropping the connection with no response — any authenticated caller could trigger it.**

- `cloud/ingestion/server.py` — **multi-tenant** ingestion service (stdlib): API-key→tenant
  auth (`ADAS_API_KEYS="key:tenant,admin:*"`), tenant-isolated `/anomalies` + `/stats` +
  dashboard with device/label filters, SQLite auto-migration. Verified: phone→cloud live +
  10/10 auth/tenancy tests. Data in `cloud/ingestion/data/` (gitignored).
- `training/train_v2.py` — v2 commercial-track training entry, **firewall enforced in code**
  (refuses IDD-derived weights/data, requires provenance ack, writes `provenance.json`,
  `--check-only` mode). All 3 gates verified.
- `cloud/scene_graph/annotate.py` — packets → scene graphs (ego + road users + co-occurrence
  edges + risk tag mirroring the app's alert logic). Verified on the live lake (8/8).
- `cloud/vlm/finetune_vlm.py` — LoRA + projection-MLP harness, frozen backbones, trains
  adapters only; `--smoke` verifies loss decreases on real lake packets (needs `~/adas-train`
  venv). GOTCHA fixed: never LoRA-inject into `nn.MultiheadAttention` (it reads
  `out_proj.weight` directly, bypassing forward()).

**Architecture pattern (reuse it):** `TelemetrySource` interface (obd/) with real + simulated
impls behind ViewModel-owned StateFlows; all capability packages (`obd/`, `geo/`, `upload/`,
`privacy/`) are **decoupled from `InferenceEngine`** = model-agnostic = firewall-safe.

## Git / commit state — READ THIS

- `origin/main` = local `main` = `5c1be75`, **working tree clean** (verified 2026-08-05).
  Everything the previous handover listed as uncommitted — the 3 Phase-3 features
  (geo/upload/privacy), `cloud/ingestion/`, and all Phase-4 scaffolds — is committed and pushed.
- **An auto-commit-AND-PUSH tool runs on this machine**: it committed+pushed `d6ada55` (OBD
  feature) and `6a37ec5` (HANDOVER + `.claude/settings.local.json`) without being asked. Assume
  anything in the working tree may get swept to GitHub. Branch first if you don't want that.
- Git identity set repo-locally to BHUKKADDD (matches history). `gh` CLI not installed.

## CRITICAL gotchas

- **NCHW planar input:** TFLite input `[1,3,320,320]` — `preprocessBitmap` writes all R, all G,
  all B (interleaved = scrambled = zero detections). Output `[1,16,2100]` is **already
  normalized [0,1]** — never divide by INPUT_SIZE. FrameAnalyzer rotates frames upright;
  letterbox in / un-letterbox out; preview FIT_CENTER; overlay maps via `frameAspect`.
- **Build only in WSL** (`JAVA_HOME=$HOME/jdk17`, `ANDROID_HOME=$HOME/Android/Sdk`,
  `cd android_app && ./gradlew assembleDebug`). Windows Gradle is broken (AF_UNIX).
- **adb runs on Windows**, callable from WSL:
  `"/mnt/c/Users/MANISH KUMAR/AppData/Local/Android/Sdk/platform-tools/adb.exe"`.
  Wireless A55 (SM-A556E): if `adb devices` is empty, `adb.exe kill-server` then
  `adb.exe mdns services` — auto-reconnects in seconds (pairing persists). Phone screen sleep
  drops the connection; `input keyevent KEYCODE_WAKEUP` + swipe-up unlocks.
- **Stale adb reverse:** device port **8000 is permanently stuck** from a dead adb server
  (survives transport drops; only a device reboot clears it). For device upload tests:
  `adb reverse tcp:8137 tcp:8000` and temporarily point `AdasViewModel.uploadEndpoint` at
  `:8137` (source is back at `:8000`; the currently-installed APK still uses `:8137`).
- **Long WSL jobs:** Bash tool `run_in_background`, not `nohup &`.

## License firewall (decided, non-negotiable)

v1 model = IDD = **non-commercial research only**. Phase 4/5 = commercial = **v2 model required**.
Go-to-market: **license the pipeline, not the model** (partner brings data, owns their v2).
Hard rules: (1) v2 trains from **stock `yolov8n.pt`**, never IDD `best.pt`; (2) Ultralytics is
**AGPL-3.0** → Enterprise License or permissive-detector swap for commercial v2.
Model-agnostic infra (OBD/GNSS/upload/PII/ingestion/dashboard/VLM-harness) is fine to build
now — it never touches the model — but must not ship commercially bundled with the v1 IDD model.

## Environment

- VS Code **Remote-WSL** (Ubuntu). Training venv `~/adas-train` (torch ROCm 7.2.4, RX 7700 XT;
  `source training/env.sh` sets HSA overrides). Dataset `~/adas-data/` (outside repo, gitignored).
- Ingestion service: `python3 cloud/ingestion/server.py` → dashboard at `http://localhost:8000`.

## Roadmap — ALL EIGHT ITEMS IMPLEMENTED 2026-08-05

Ranked after a research pass on the India ADAS regulatory landscape and the 2026
detector/runtime ecosystem, then worked through in order. **Every item below is built,
wired and unit-tested: 149 Android unit tests + 80 cloud tests, all green.** What is *not*
done is on-road validation of the new features — none of this has run on the A55 yet.

Test commands:
```
cd android_app && ./gradlew testDebugUnitTest      # 149 tests
python3 -m unittest discover -s cloud/tests -v     # 80 tests
```

Per-item status follows; the rationale for each choice is preserved.

1. ✅ **`cloud/` test suite** — `cloud/tests/`, 80 tests (32 ingestion / 25 scene-graph /
   23 VLM). VLM tests skip cleanly without torch. **Found and fixed a real bug:**
   `?limit=<non-numeric>` raised inside `fetch()`, killing the handler thread and dropping
   the connection with no response, reachable by any authenticated caller.
2. ✅ **DDAWS — driver drowsiness & attention (`dms/`)**, 36 tests. MoRTH GSR 184(E) makes
   this **legally mandatory** in India for M2/M3/N2/N3: new models 2026-04-01, existing
   models **2026-10-01**. EAR + time-weighted PERCLOS + MAR yawn detection + head-yaw
   attention proxy, with a 5 s per-driver calibration (a narrow-eyed driver at their own
   baseline is explicitly tested not to alert). Wired into the ViewModel and a Settings
   section with a scripted-drowsy-driver simulator. **Touches no detector → firewall-clean.**
   ⚠️ **Remaining:** the real MediaPipe source. Needs `tasks-vision` + `face_landmarker.task`
   (~3 MB) — step-by-step note at the foot of `dms/FaceLandmarkSource.kt`. The analyzer
   behind the interface is production code; only the landmark feed is simulated.
3. ✅ **TTC-based FCW (`fcw/`)**, 32 tests. Replaced the center-zone presence test.
   Greedy-IoU tracking + Kalman filter + ego-corridor geometry + track maturity gating.
   **Design note worth keeping:** the filter tracks **inverse** box width, not width.
   `w = K/d`, so width *accelerates* on approach and a constant-velocity filter on it lags
   exactly when it matters — overstating TTC, i.e. warning late. `u = 1/w ∝ d` is linear for
   a constant-speed approach, making the filter exact. There is a regression test
   (`ttc estimate is not biased late`) guarding this.
4. ✅ **REC event recorder (`rec/`)**, 18 tests. Pre-roll ring buffer + post-roll, clip
   extension on re-trigger with a hard cap, cooldown, flush-on-teardown. Triggered
   automatically on DANGER and manually by the REC button.
   **Deviation from the original plan, deliberately:** buffers redacted frames from the
   existing ImageAnalysis stream rather than adding a CameraX `VideoCapture`. Reasons in
   order: (a) frames are redacted *before* storage so PII never lands on disk in the clear —
   a parallel VideoCapture writes the raw sensor stream, which the privacy roadmap forbids;
   (b) `Recorder` has no ring buffer anyway; (c) a second video use case contends with the
   front camera DDAWS wants. Trade: clips are ~15 FPS JPEG sequences, not H.264. MP4 muxing
   is a self-contained upgrade behind `EventRecorder.writeClip`.
5. ✅ **LDW (AIS-188) (`lane/`)**, 21 tests. Scanline gradient search + least-squares fit +
   hysteresis + 60 km/h speed gate (UNECE R130, which AIS-188 follows). Classical CV, no
   model, no licence. Unmarked roads report `NO_LANE` rather than guessing.
   **Fixed a real bug found by a test:** collinear-in-y points slipped past an absolute
   epsilon in float arithmetic and produced a wild slope; now guarded on y-variance.
   ⚠️ **Known false-positive source:** no turn-indicator signal is available over OBD-II, so
   a signalled lane change is indistinguishable from a drift.
6. ✅ **v2 permissive-detector track** — `training/train_v2.py` now takes
   `--arch {yolov8,rfdetr}`. **RULE 3 was an unenforced printed reminder and is now a gate:**
   `--arch yolov8` refuses to run without `--accept-agpl`. RF-DETR (Apache-2.0) clears
   *both* firewall halves at once, and provenance.json records `commercially_clean`. All
   four gate paths verified by hand. Full retrain still gated on partner data.
   ⚠️ RF-DETR output is DETR-style `[boxes, logits]`, **not** YOLOv8's `[1,16,2100]` —
   `InferenceEngine.parseOutput()` needs a rewrite for it (no NMS, cxcywh in [0,1], sigmoid
   on logits).
7. ✅ **GPU delegate** implemented in `InferenceEngine` with `CompatibilityList` probing and
   safe fallback (catches `Throwable` — OEM builds throw `LinkageError`), plus explicit
   delegate cleanup in `close()`. **NNAPI deliberately not used: deprecated in Android 15.**
   ⚠️ **Left OPT-IN, default stays XNNPACK CPU.** The model is INT8, and INT8-on-GPU is not
   automatically faster than INT8-on-XNNPACK — it needs measuring on the A55. Switching the
   safety path on an unmeasured assumption is the wrong trade. Flip via the `accelerator`
   constructor arg and compare against the 12–15 FPS baseline.
   (Also corrected the Settings sheet, which advertised the deprecated "NNAPI / GPU Auto".)
8. ✅ **Event narration (`narrate/`)**, 20 tests. Event-context assembly + VLM prompt
   rendering + a deterministic `TemplateNarrator` that ships today.
   ⚠️ **The Gemma 3n binding is NOT built.** It needs the LiteRT-LM Kotlin API (*not*
   MediaPipe's LLM Inference API — maintenance-only) and a ~1.5 GB `.litertlm` INT4 asset,
   too large to bundle, so it must be downloaded on first use. Note the template narrator is
   not merely a placeholder: it never hallucinates a road user that was not detected. The
   VLM's real value is describing what labels cannot — surface, weather, whether a
   pedestrian was already crossing — and that needs the frames.

**Deliberately NOT doing:** BSIS/MOIS (AIS-186/187) need side/front-mount cameras — not
phone-feasible.

## THE NEXT THING TO DO

**Take it on the road.** Everything above is unit-tested but none of it has run on the A55.
In rough order of value:

1. Install the fresh APK and drive it. Watch for FPS regression first: lane detection,
   JPEG encoding and face redaction now share the analysis thread with inference. If FPS
   drops below ~10, the lane-detection cadence (every 3rd frame) is the first dial to turn.
2. Confirm clips actually land in `filesDir/events/<ts>/` with `event.json`, and **eyeball a
   stored frame to verify the face redaction is really burnt in**, not just overlaid.
3. Sanity-check FCW against reality: it should stay silent past parked cars and vehicles in
   the next lane, and fire on genuine closing. Watch the TTC number in the alert.
4. Flip the DDAWS Settings simulator to see the whole severity ladder on the HUD, then wire
   the real MediaPipe source (item 2 above).
5. Measure the GPU delegate against the XNNPACK baseline and set the default from data.

Nothing was committed — the working tree holds all of it. Mind the auto-push tool.

## Smaller open items

- **Phase-3 depth gaps:** plate blur + MediaPipe swap + stored-frame redaction (needs REC
  first), real ELM327 adapter test (no hardware).
- **OBD on-device sim re-check:** extra PIDs done 2026-07-07 (RPM/coolant/throttle),
  bench-verified only — flip the Settings sim toggle on the A55 to eyeball the "Engine" row.
  Install the fresh APK first: it reverted `uploadEndpoint` to `:8000`.
