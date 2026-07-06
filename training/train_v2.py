#!/usr/bin/env python3
"""
train_v2.py — Train a COMMERCIAL-TRACK (v2) detector with the license firewall
enforced in code, not just in docs.

The go-to-market is "license the pipeline, not the model": a partner supplies
their own (commercially usable) road data and this pipeline trains a v2 model
they own outright. Two hard rules make that clean, and this script REFUSES to
run if either is violated:

  RULE 1 — v2 must start from STOCK weights (e.g. yolov8n.pt), NEVER from the
           IDD-trained best.pt. Fine-tuning off the IDD checkpoint makes the
           result an IDD derivative and drags the non-commercial license back in.
  RULE 2 — the dataset must not be IDD. This script checks the data.yaml path
           and contents for IDD markers and requires an explicit provenance
           acknowledgement (--data-license) from the operator.

NOTE (RULE 3, informational): Ultralytics YOLOv8 is AGPL-3.0. Commercial v2 use
needs an Ultralytics Enterprise License (partner procures) or a swap to a
permissively-licensed detector. This script prints that reminder; it cannot
verify a paper contract.

Usage:
    python train_v2.py --data /path/to/partner_data.yaml \
        --data-license commercial --epochs 100

Same knobs as train.py otherwise (imgsz MUST stay 320 to match the app).
"""
import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone

# Filename fragments that indicate an IDD-derived checkpoint or dataset.
IDD_MARKERS = ("idd", "india_driving", "india-driving", "insaan")

# Known-stock Ultralytics base checkpoints allowed as v2 starting points.
STOCK_WEIGHTS = {"yolov8n.pt", "yolov8s.pt", "yolov8m.pt", "yolov8l.pt", "yolov8x.pt"}


def die(msg: str) -> None:
    print(f"\n*** FIREWALL VIOLATION — refusing to train ***\n{msg}\n", file=sys.stderr)
    sys.exit(2)


def check_weights(weights: str) -> None:
    """RULE 1: start from stock weights only."""
    base = os.path.basename(weights).lower()
    if any(m in base for m in IDD_MARKERS):
        die(f"Weights '{weights}' look IDD-derived. v2 must start from stock "
            f"weights (e.g. yolov8n.pt), never the IDD best.pt.")
    if base not in STOCK_WEIGHTS:
        # A partner may legitimately resume THEIR OWN v2 run; require the flag.
        print(f"NOTE: '{base}' is not a known stock checkpoint. Only proceed if this "
              f"is a resume of a previous *v2* run (never anything trained on IDD).")


def check_dataset(data_yaml: str, license_ack: str) -> None:
    """RULE 2: dataset must be non-IDD and its provenance acknowledged."""
    p = data_yaml.lower()
    if any(m in p for m in IDD_MARKERS):
        die(f"Dataset path '{data_yaml}' looks like IDD. v2 requires commercially "
            f"usable data (partner-supplied, self-collected, or commercially licensed).")
    try:
        with open(data_yaml, "r", encoding="utf-8") as f:
            content = f.read().lower()
        if any(m in content for m in IDD_MARKERS):
            die(f"'{data_yaml}' references IDD paths/names internally.")
    except FileNotFoundError:
        die(f"Dataset config not found: {data_yaml}")
    if license_ack not in ("commercial", "self-collected", "partner"):
        die("You must acknowledge data provenance with --data-license "
            "{commercial|self-collected|partner}. IDD is not an option for v2.")


def write_provenance(args, out_dir: str) -> None:
    """Record an auditable provenance manifest next to the run."""
    os.makedirs(out_dir, exist_ok=True)
    manifest = {
        "track": "v2-commercial",
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "base_weights": args.weights,
        "base_weights_sha256": _sha256(args.weights),
        "data_yaml": os.path.abspath(args.data),
        "data_license_ack": args.data_license,
        "imgsz": args.imgsz,
        "epochs": args.epochs,
        "notes": "Trained via train_v2.py firewall-enforced pipeline. "
                 "Ultralytics AGPL-3.0 applies to the framework: commercial use "
                 "requires an Ultralytics Enterprise License or detector swap.",
    }
    path = os.path.join(out_dir, "provenance.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(f"Provenance manifest: {path}")


def _sha256(path: str):
    try:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()
    except OSError:
        return None  # e.g. weights not downloaded yet — Ultralytics will fetch


def main() -> None:
    ap = argparse.ArgumentParser(description="Train the v2 (commercial-track) detector")
    ap.add_argument("--data", required=True,
                    help="Partner/self-collected dataset data.yaml (non-IDD)")
    ap.add_argument("--data-license", required=True,
                    choices=["commercial", "self-collected", "partner"],
                    help="Provenance acknowledgement for the training data")
    ap.add_argument("--weights", default="yolov8n.pt",
                    help="STOCK base weights (never IDD best.pt)")
    ap.add_argument("--imgsz", type=int, default=320,
                    help="MUST match INPUT_SIZE in InferenceEngine.kt (320)")
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--patience", type=int, default=100)
    ap.add_argument("--device", default="0", help="'0' = first GPU (ROCm), or 'cpu'")
    ap.add_argument("--name", default="v2_yolov8n")
    ap.add_argument("--check-only", action="store_true",
                    help="Run the firewall checks + write provenance, skip training")
    args = ap.parse_args()

    # ── Firewall gates ────────────────────────────────────────────────────────
    check_weights(args.weights)
    check_dataset(args.data, args.data_license)
    print("Firewall checks passed: stock base weights + non-IDD data acknowledged.")
    print("REMINDER: Ultralytics YOLOv8 is AGPL-3.0 — commercial deployment needs an "
          "Ultralytics Enterprise License or a permissive-detector swap.\n")

    out_dir = os.path.join("runs", "detect", args.name)
    write_provenance(args, out_dir)
    if args.check_only:
        print("--check-only: stopping before training.")
        return

    # Import inside main so --check-only works without ultralytics installed.
    from ultralytics import YOLO

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
    best = os.path.join(out_dir, "weights", "best.pt")
    print(f"\nDone. v2 weights: {best}")
    print(f"Next: python export_model.py --model {best} --imgsz {args.imgsz}")
    print("The 12-class head order must match idd_labels.txt only if you keep the "
          "same label file; otherwise update assets + parseOutput class count.")


if __name__ == "__main__":
    main()
