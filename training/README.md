# Phase 3 — Fine-tune YOLOv8n for Indian roads (IDD)

Stock COCO `yolov8n` has no `autorickshaw`, `rider`, `animal`, or `vehicle_fallback`
classes — the exact India-specific road users this app exists to flag. Phase 3
fine-tunes YOLOv8n on the **India Driving Dataset (IDD Detection)** and swaps the
result into the Android app.

Pipeline: **IDD (VOC) → YOLO format → fine-tune → TFLite → verify → app assets**.

Compute target: local **AMD Radeon RX 7700 XT** via **PyTorch-ROCm in WSL2**.

---

## 0. One-time environment (WSL2 Ubuntu) — ✅ DONE (2026-07-01)

**Already built and GPU-verified** — venv at `~/adas-train` (WSL-native ext4), torch
`2.10.0+rocm7.2.4` reporting `is_available: True` on the RX 7700 XT with a passing GPU
matmul. To use it in a fresh shell, just:
`source ~/adas-train/bin/activate && source training/env.sh`. Re-run the steps below
only if you need to rebuild the venv.

The **system-level ROCm stack is machine-wide** (installed for the Commentator project:
ROCm 7.2.4 + `rocdxg` routing to the GPU via `/dev/dxg`) — **we do not reinstall it.**
The ADAS venv is *separate* and reuses the same AMD wheel source + env vars (do NOT
install into the Commentator's `ai_engine/.venv` — that project pins its deps).

```bash
# In WSL2 Ubuntu 24.04:
cd "/mnt/c/Users/MANISH KUMAR/Desktop/Engineering Projects and Learning/ADAS"
python3 -m venv ~/adas-train && source ~/adas-train/bin/activate   # WSL-native FS (fast), NOT in-project

# torch/torchvision from AMD's FLAT wheel repo — use --find-links, NOT --index-url
# (--index-url 404s on this layout). Pin the +rocm builds so PyPI can't clobber them.
pip install --find-links https://repo.radeon.com/rocm/manylinux/rocm-rel-7.2.4/ \
    torch==2.10.0 torchvision==0.25.0

pip install -r requirements.txt        # ultralytics, tensorflow, numpy, Pillow

# The GPU is only visible with the gfx spoof + DXG detection env vars:
source training/env.sh
python -c "import torch; print(torch.cuda.is_available(), torch.cuda.get_device_name(0))"
# -> True  AMD Radeon RX 7700 XT   (reported through the CUDA API; Ultralytics uses that)
```

**Always `source training/env.sh` in the shell before running `train.py`** — it sets
`HSA_OVERRIDE_GFX_VERSION=11.0.0` (presents the gfx1101 7700 XT as a supported gfx1100)
and `HSA_ENABLE_DXG_DETECTION=1` (routes HSA to `/dev/dxg`; required on ROCm < 7.13).
Without them `torch.cuda.is_available()` is `False` and training silently falls to CPU.
If the GPU still isn't found, re-run the Commentator's `ai_engine/setup_rocm_wsl.sh`
(idempotent) to repair the system ROCm layer. `--device cpu` works as a slow fallback.

## 1. Get the data

Download **IDD Detection** from https://idd.insaan.iiit.ac.in/ (free, registration
required — it's citable in the thesis). Extract `IDD_Detection.tar.gz`; you should
get a folder with `JPEGImages/`, `Annotations/`, and `ImageSets/Main/`.

> **License:** IDD is **non-commercial research use only**. Do not commit or
> redistribute the dataset or the converted `idd_yolo/` copy (both git-ignored). The
> resulting model inherits this restriction. See [DATASET_LICENSE.md](../DATASET_LICENSE.md).

## 2. Convert IDD (VOC) → YOLO

```bash
python training/idd_to_yolo.py \
    --idd-root /path/to/IDD_Detection \
    --out datasets/idd_yolo \
    --symlink            # omit if the dataset lives on /mnt/c (Windows FS)
```

Produces `datasets/idd_yolo/{images,labels}/{train,val}/`, plus `data.yaml` and
`classes.txt`. It prints the per-class box counts and warns about any IDD class
name it didn't map (extend `IDD_TO_YOLO` in the script if you want those).

**The 12 output classes (order is fixed — it defines the model's class IDs):**

```
0 person   1 rider   2 car    3 truck   4 bus    5 motorcycle
6 bicycle  7 autorickshaw     8 animal  9 traffic_sign
10 traffic_light   11 vehicle_fallback
```

## 3. Fine-tune

```bash
python training/train.py --data datasets/idd_yolo/data.yaml --epochs 100
```

`--imgsz` defaults to **320** to match `INPUT_SIZE` in `InferenceEngine.kt` — do
not change one without the other. Best weights land at
`runs/detect/idd_yolov8n/weights/best.pt`.

## 4. Export to TFLite + verify

```bash
python export_model.py --model runs/detect/idd_yolov8n/weights/best.pt --imgsz 320
# add --int8 (needs a calibration set) once accuracy is confirmed, for speed.

python check_tflite_scale.py     # confirm cx/cy/w/h still print in ~[0,1]
```

`export_model.py` auto-copies the result to
`android_app/app/src/main/assets/yolov8n.tflite`. **Never re-introduce a
`/INPUT_SIZE` divide in `parseOutput()`** — the export stays normalized to [0,1].

## 5. Wire the new model into the app  ⚠️ REQUIRED

The retrained model has **12 classes, not 80**, so the app's hardcoded shapes and
labels must change or every detection will be mislabeled / misparsed:

- **`assets/coco_labels.txt`** — replace its contents with
  `datasets/idd_yolo/classes.txt` (the 12 names, in order).
- **`InferenceEngine.kt`**:
  - `NUM_CLASSES = 80` → `12`.
  - The output buffer `Array(1) { Array(84) { FloatArray(NUM_ANCHORS) } }` — the
    `84` is `4 + 80`; with 12 classes it becomes `4 + 12 = 16`. Update it (and the
    doc comment) to match the new export's output shape.
  - Re-check `NUM_ANCHORS` against the export: for `imgsz=320` it's 2100.
- **`AdasViewModel.kt`** alert logic uses COCO names (`dog`/`cat`, etc.). Update
  `dangerLabels`/`cautionLabels` to the new set — e.g. `animal` replaces
  `dog`/`cat`, and consider adding `autorickshaw`/`rider`.
- **IDD attribution (license-required):** add a visible IDD credit in the app
  (about / settings screen) — the IDD license requires attribution for "other media"
  once the IDD-trained model ships. Link to https://idd.insaan.iiit.ac.in/. See
  [DATASET_LICENSE.md](../DATASET_LICENSE.md).

## 6. Re-verify on device (Phase 1 loop)

Rebuild in WSL (`bash gradlew assembleDebug`), install via Windows `adb`, point at
Indian traffic. **Acceptance:** measurably better detection on India-specific
hazards (autorickshaws, animals, two-wheeler swarms) than stock COCO.
