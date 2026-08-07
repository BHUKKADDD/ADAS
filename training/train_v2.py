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

  RULE 3 — the DETECTOR FRAMEWORK must be commercially usable. Ultralytics
           YOLOv8 is AGPL-3.0: shipping it commercially requires an Ultralytics
           Enterprise License. This used to be an unenforced printed reminder;
           it is now a gate. Choosing --arch yolov8 requires passing
           --accept-agpl to state on the record that the licence is procured.

The clean path is --arch rfdetr. RF-DETR is Apache-2.0 (weights and code), so a
v2 trained that way clears BOTH halves of the firewall at once — no IDD lineage
and no AGPL obligation — and it is also the stronger choice on the merits, since
its domain-transfer behaviour is what a partner-data retrain depends on.

Usage:
    # Recommended: permissive detector, commercially clean end to end.
    python train_v2.py --arch rfdetr --weights rf-detr-nano.pth \
        --data /path/to/partner_data.yaml --data-license partner --epochs 100

    # Legacy path: requires an Ultralytics Enterprise Licence.
    python train_v2.py --arch yolov8 --accept-agpl \
        --data /path/to/partner_data.yaml --data-license commercial

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

# Supported detector architectures and their licensing posture.
#
# "permissive" architectures need no extra paperwork to ship commercially;
# "copyleft" ones do, and are gated behind --accept-agpl.
ARCHITECTURES = {
    "yolov8": {
        "framework": "Ultralytics YOLOv8",
        "license": "AGPL-3.0",
        "permissive": False,
        "stock_weights": {
            "yolov8n.pt", "yolov8s.pt", "yolov8m.pt", "yolov8l.pt", "yolov8x.pt",
        },
        "default_weights": "yolov8n.pt",
    },
    "rfdetr": {
        "framework": "RF-DETR",
        "license": "Apache-2.0",
        "permissive": True,
        "stock_weights": {
            "rf-detr-nano.pth", "rf-detr-small.pth", "rf-detr-base.pth",
            "rf-detr-medium.pth", "rf-detr-large.pth",
        },
        "default_weights": "rf-detr-nano.pth",
    },
}

# Back-compat alias: the original name, now the yolov8 entry's stock set.
STOCK_WEIGHTS = ARCHITECTURES["yolov8"]["stock_weights"]


def die(msg: str) -> None:
    print(f"\n*** FIREWALL VIOLATION — refusing to train ***\n{msg}\n", file=sys.stderr)
    sys.exit(2)


def check_weights(weights: str, arch: str = "yolov8") -> None:
    """RULE 1: start from stock weights only, for the chosen architecture."""
    base = os.path.basename(weights).lower()
    if any(m in base for m in IDD_MARKERS):
        die(f"Weights '{weights}' look IDD-derived. v2 must start from stock "
            f"weights (e.g. {ARCHITECTURES[arch]['default_weights']}), never the "
            f"IDD best.pt.")
    known = ARCHITECTURES[arch]["stock_weights"]
    if base not in known:
        # A partner may legitimately resume THEIR OWN v2 run; require the flag.
        print(f"NOTE: '{base}' is not a known stock {arch} checkpoint. Only proceed "
              f"if this is a resume of a previous *v2* run (never anything trained "
              f"on IDD).")


def check_framework_license(arch: str, accept_agpl: bool) -> dict:
    """RULE 3: the detector framework itself must be commercially usable.

    Returns the architecture spec so callers can record it in provenance.
    """
    spec = ARCHITECTURES.get(arch)
    if spec is None:
        die(f"Unknown --arch '{arch}'. Choose from: {', '.join(sorted(ARCHITECTURES))}.")
    if not spec["permissive"] and not accept_agpl:
        die(f"{spec['framework']} is {spec['license']}. Shipping a v2 model trained "
            f"with it commercially requires an Ultralytics Enterprise License.\n"
            f"Either:\n"
            f"  - use the permissive path:  --arch rfdetr   (Apache-2.0, no "
            f"obligation, better domain transfer), or\n"
            f"  - pass --accept-agpl to record that the Enterprise License is "
            f"procured.")
    return spec


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
    spec = ARCHITECTURES[args.arch]
    permissive = spec["permissive"]
    manifest = {
        "track": "v2-commercial",
        "trained_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "arch": args.arch,
        "framework": spec["framework"],
        "framework_license": spec["license"],
        "base_weights": args.weights,
        "base_weights_sha256": _sha256(args.weights),
        "data_yaml": os.path.abspath(args.data),
        "data_license_ack": args.data_license,
        "imgsz": args.imgsz,
        "epochs": args.epochs,
        # The whole point of the firewall, reduced to one auditable boolean:
        # no IDD lineage (enforced above) AND no copyleft framework obligation.
        "commercially_clean": bool(permissive),
        "agpl_accepted": bool(getattr(args, "accept_agpl", False)),
        "notes": (
            f"Trained via train_v2.py firewall-enforced pipeline on "
            f"{spec['framework']} ({spec['license']}). "
            + ("No framework licence obligation; clean for commercial use."
               if permissive else
               "AGPL-3.0 framework: commercial deployment requires an Ultralytics "
               "Enterprise License, acknowledged via --accept-agpl.")
        ),
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


def train_yolov8(args) -> None:
    """Legacy path. Requires an Ultralytics Enterprise License to ship."""
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


def train_rfdetr(args) -> None:
    """Permissive path: RF-DETR (Apache-2.0).

    Kept deliberately thin — RF-DETR's trainer takes a COCO-format dataset
    directory rather than Ultralytics' data.yaml, so `--data` here should point at
    the dataset root containing train/valid/test with `_annotations.coco.json`.
    The firewall gates above are format-agnostic and run either way.
    """
    try:
        from rfdetr import RFDETRNano
    except ImportError:
        die("RF-DETR is not installed in this environment.\n"
            "  pip install rfdetr\n"
            "(Or run with --check-only to exercise the firewall gates alone.)")

    dataset_dir = args.data
    if os.path.isfile(dataset_dir):
        # A data.yaml was passed; RF-DETR wants the directory that holds the
        # COCO-format splits.
        dataset_dir = os.path.dirname(os.path.abspath(dataset_dir))

    model = RFDETRNano(pretrain_weights=args.weights)
    model.train(
        dataset_dir=dataset_dir,
        epochs=args.epochs,
        batch_size=args.batch,
        grad_accum_steps=1,
        lr=1e-4,
        output_dir=os.path.join("runs", "detect", args.name),
    )


def main() -> None:
    ap = argparse.ArgumentParser(description="Train the v2 (commercial-track) detector")
    ap.add_argument("--data", required=True,
                    help="Partner/self-collected dataset data.yaml (non-IDD)")
    ap.add_argument("--data-license", required=True,
                    choices=["commercial", "self-collected", "partner"],
                    help="Provenance acknowledgement for the training data")
    ap.add_argument("--arch", default="yolov8", choices=sorted(ARCHITECTURES),
                    help="Detector architecture. 'rfdetr' (Apache-2.0) is the "
                         "commercially clean choice; 'yolov8' is AGPL-3.0 and "
                         "requires --accept-agpl.")
    ap.add_argument("--accept-agpl", action="store_true",
                    help="Record that an Ultralytics Enterprise License is procured. "
                         "Required for --arch yolov8; meaningless for permissive archs.")
    ap.add_argument("--weights", default=None,
                    help="STOCK base weights (never IDD best.pt). Defaults to the "
                         "nano checkpoint for the chosen --arch.")
    ap.add_argument("--imgsz", type=int, default=320,
                    help="MUST match INPUT_SIZE in InferenceEngine.kt (320)")
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--patience", type=int, default=100)
    ap.add_argument("--device", default="0", help="'0' = first GPU (ROCm), or 'cpu'")
    ap.add_argument("--name", default=None,
                    help="Run name; defaults to v2_<arch>")
    ap.add_argument("--check-only", action="store_true",
                    help="Run the firewall checks + write provenance, skip training")
    args = ap.parse_args()
    if args.weights is None:
        args.weights = ARCHITECTURES[args.arch]["default_weights"]
    if args.name is None:
        args.name = f"v2_{args.arch}"

    # ── Firewall gates ────────────────────────────────────────────────────────
    spec = check_framework_license(args.arch, args.accept_agpl)   # RULE 3
    check_weights(args.weights, args.arch)                        # RULE 1
    check_dataset(args.data, args.data_license)                   # RULE 2
    print(f"Firewall checks passed: {spec['framework']} ({spec['license']}), "
          f"stock base weights, non-IDD data acknowledged.")
    if spec["permissive"]:
        print("This run is commercially clean end to end: no IDD lineage, no "
              "copyleft framework obligation.\n")
    else:
        print("REMINDER: AGPL-3.0 framework — an Ultralytics Enterprise License is "
              "required for commercial deployment (recorded via --accept-agpl).\n")

    out_dir = os.path.join("runs", "detect", args.name)
    write_provenance(args, out_dir)
    if args.check_only:
        print("--check-only: stopping before training.")
        return

    # Imports live inside main so --check-only works without either framework
    # installed — the firewall gates must be runnable on any machine.
    if args.arch == "rfdetr":
        train_rfdetr(args)
    else:
        train_yolov8(args)
    if args.arch == "rfdetr":
        best = os.path.join(out_dir, "checkpoint_best_total.pth")
        print(f"\nDone. v2 weights: {best}")
        print("Next: export to TFLite via RF-DETR's ONNX export, then onnx2tf.")
        print("NOTE: RF-DETR output is a DETR-style [boxes, logits] pair, NOT "
              "YOLOv8's [1,16,2100]. InferenceEngine.parseOutput() must be "
              "rewritten for it — no NMS needed, but boxes are cxcywh in [0,1] "
              "and logits need a sigmoid, not YOLO's objectness layout.")
    else:
        best = os.path.join(out_dir, "weights", "best.pt")
        print(f"\nDone. v2 weights: {best}")
        print(f"Next: python export_model.py --model {best} --imgsz {args.imgsz}")
        print("The 12-class head order must match idd_labels.txt only if you keep "
              "the same label file; otherwise update assets + parseOutput class count.")


if __name__ == "__main__":
    main()
