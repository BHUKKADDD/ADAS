#!/usr/bin/env python3
"""
train.py — Fine-tune YOLOv8n on the IDD (India Driving Dataset) YOLO set.

Phase 3 of the ADAS project. Runs locally on the AMD Radeon RX 7700 XT via
PyTorch-ROCm in WSL2 (device="0").

Prereqs:
  1. Run idd_to_yolo.py first to produce datasets/idd_yolo/data.yaml.
  2. Install PyTorch-ROCm (NOT the default PyPI CUDA build) — see training/README.md §0.
     Reuses the box's existing ROCm 7.2.4 wheel source; no reinstall of the driver layer.
  3. `source training/env.sh` in this shell FIRST (sets HSA_OVERRIDE_GFX_VERSION +
     HSA_ENABLE_DXG_DETECTION) — without it the GPU is invisible and training falls to CPU.
  4. Verify the GPU is visible BEFORE training:
        python -c "import torch; print(torch.cuda.is_available(), \
            torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU only')"
     Ultralytics reports the ROCm GPU through the CUDA API, so a True here means
     device="0" will use the 7700 XT.

Usage:
    python train.py --data datasets/idd_yolo/data.yaml --epochs 100
    python train.py --device cpu        # slow fallback to validate the pipeline
"""
import argparse

from ultralytics import YOLO


def main() -> None:
    ap = argparse.ArgumentParser(description="Fine-tune YOLOv8n on IDD (YOLO format)")
    ap.add_argument("--data", default="datasets/idd_yolo/data.yaml")
    ap.add_argument("--weights", default="yolov8n.pt",
                    help="Base weights to fine-tune from (downloads if absent)")
    ap.add_argument("--imgsz", type=int, default=320,
                    help="MUST match INPUT_SIZE in InferenceEngine.kt (320)")
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--patience", type=int, default=100,
                    help="Early-stop if val mAP hasn't improved in this many epochs")
    ap.add_argument("--device", default="0",
                    help="'0' = first GPU (ROCm), or 'cpu'")
    ap.add_argument("--name", default="idd_yolov8n",
                    help="Run name -> runs/detect/<name>/weights/best.pt")
    args = ap.parse_args()

    model = YOLO(args.weights)
    model.train(
        data=args.data,
        imgsz=args.imgsz,
        epochs=args.epochs,
        batch=args.batch,
        patience=args.patience,
        device=args.device,
        name=args.name,
    )

    best = f"runs/detect/{args.name}/weights/best.pt"
    print(f"\nDone. Best weights: {best}")
    print(f"Next: python export_model.py --model {best} --imgsz {args.imgsz}")


if __name__ == "__main__":
    main()
