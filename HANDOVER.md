# ADAS — Session Handover

Pick up the **ADAS** project: an on-device AI dashcam for Indian roads. The product is
the Android app in `android_app/` (Kotlin + Compose + TFLite); the phone is the edge node
(no cloud dependency). New: `cloud/` holds the Phase-4 model-agnostic server scaffolds.
Repo root (WSL): `/mnt/c/Users/MANISH KUMAR/Desktop/Engineering Projects and Learning/ADAS`.

## Current state (2026-07-06)

**Phase 3 — every item now has a working, on-device-verified slice:**

| Item | Status |
| --- | --- |
| YOLOv8n/IDD 12-class model, INT8 3.2 MB, 12–15 FPS on Galaxy A55 | ✅ done (verified off a monitor only — outdoor road test still open) |
| OBD-II ELM327 **BLE** telemetry (`obd/` package) | 🟡 speed-first slice (PID 010D) verified via built-in **simulator** toggle in Settings; real-adapter test pending (no hardware) |
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
(see `cloud/README.md`):**

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

- `origin/main` = local `main` = `6a37ec5` at last check. **An auto-commit-AND-PUSH tool runs
  on this machine**: it committed+pushed `d6ada55` (OBD feature) and `6a37ec5` (HANDOVER +
  `.claude/settings.local.json`) without being asked. Assume anything in the working tree may
  get swept to GitHub. Branch first if you don't want that.
- **Uncommitted right now:** the 3 Phase-3 features (geo/upload/privacy + VM/overlay/analyzer/
  manifest edits), `cloud/ingestion/`, this file, and any newer Phase-4 scaffolds.
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

## Open threads (pick up any)

1. **Phase 4 build-out (current thread):** more model-agnostic infra — B2B-ify the ingestion
   dashboard (auth/tenants/filters), VLM fine-tuning harness (LoRA + projection MLP scaffold),
   3D scene-graph annotation scaffold, v2 training entry point with firewall guards baked in.
2. **Commit the uncommitted work** (all verified; mind the auto-push tool).
3. **Phase-3 depth gaps:** plate blur + MediaPipe swap + stored-frame redaction (needs REC
   implemented first), extra OBD PIDs (010C RPM etc. — one-line additions), real ELM327 adapter
   test, **outdoor road test** (biggest validation gap; device + logcat/screencap harness only).
4. **v2 commercial track:** gated on partner/data — see firewall.
