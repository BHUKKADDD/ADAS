#!/usr/bin/env python3
"""
idd_to_yolo.py — Convert the India Driving Dataset (IDD Detection, Pascal-VOC
format) into a YOLO-format dataset for fine-tuning YOLOv8n.

Phase 3 of the ADAS project: stock COCO has no autorickshaw / rider / animal /
"vehicle fallback" classes, which are exactly the India-specific road users the
app needs to flag. IDD Detection provides them. This script:

  1. Reads IDD's VOC XML annotations + train/val split lists.
  2. Maps IDD class names -> our target YOLO class set (see IDD_TO_YOLO).
  3. Writes YOLO-format labels + a flattened image tree + data.yaml + classes.txt.

Input layout (as extracted from IDD_Detection.tar.gz):

    <idd-root>/
        JPEGImages/<drive>/<frame>.jpg
        Annotations/<drive>/<frame>.xml
        ImageSets/Main/train.txt        # lines: "<drive>/<frame>" (no extension)
        ImageSets/Main/val.txt

Output layout (YOLO / Ultralytics):

    <out>/
        images/train/<drive>__<frame>.jpg
        images/val/<drive>__<frame>.jpg
        labels/train/<drive>__<frame>.txt
        labels/val/<drive>__<frame>.txt
        classes.txt        # one class per line -> becomes the app's labels file
        data.yaml          # pass this to train.py

Usage:
    python idd_to_yolo.py --idd-root /path/to/IDD_Detection --out datasets/idd_yolo
    # On a Linux/WSL-native filesystem, add --symlink to skip copying images.
"""
from __future__ import annotations

import argparse
import random
import shutil
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

# --- Class mapping -----------------------------------------------------------
# Raw IDD <name> (lowercased, single-spaced) -> our target class.
# Rare/edge vehicle types are folded into "vehicle_fallback" (IDD's own catch-all).
# Any <name> not in this table is reported at the end so you can extend it.
IDD_TO_YOLO = {
    "person": "person",
    "rider": "rider",
    "car": "car",
    "truck": "truck",
    "bus": "bus",
    "motorcycle": "motorcycle",
    "motorbike": "motorcycle",
    "bicycle": "bicycle",
    "autorickshaw": "autorickshaw",
    "auto rickshaw": "autorickshaw",
    "animal": "animal",
    "traffic sign": "traffic_sign",
    "traffic light": "traffic_light",
    "vehicle fallback": "vehicle_fallback",
    "caravan": "vehicle_fallback",
    "trailer": "vehicle_fallback",
    "train": "vehicle_fallback",
}

# Fixed output order -> these indices become the model's class IDs and MUST match
# the app's labels file (assets/coco_labels.txt). Keep this order stable across
# re-runs, or the app will mislabel every detection.
CLASS_NAMES = [
    "person", "rider", "car", "truck", "bus", "motorcycle",
    "bicycle", "autorickshaw", "animal",
    "traffic_sign", "traffic_light", "vehicle_fallback",
]
CLASS_INDEX = {name: i for i, name in enumerate(CLASS_NAMES)}


def norm_name(raw: str) -> str:
    """Lowercase and collapse whitespace so 'Traffic  Sign' == 'traffic sign'."""
    return " ".join(raw.strip().lower().split())


def find_file(base: Path, rel: str, exts) -> Path | None:
    for ext in exts:
        p = base / f"{rel}{ext}"
        if p.exists():
            return p
    return None


def convert_annotation(xml_path: Path, img_path: Path, out_lbl: Path,
                       unmapped: Counter, class_counter: Counter) -> int:
    """Parse one VOC XML -> YOLO label file. Returns the #boxes written."""
    root = ET.parse(xml_path).getroot()

    size = root.find("size")
    w = h = None
    if size is not None:
        w = float(size.findtext("width") or 0) or None
        h = float(size.findtext("height") or 0) or None
    if not w or not h:
        # Some XMLs omit <size>; fall back to the actual image dimensions.
        from PIL import Image
        with Image.open(img_path) as im:
            w, h = im.size

    lines = []
    for obj in root.findall("object"):
        target = IDD_TO_YOLO.get(norm_name(obj.findtext("name") or ""))
        if target is None:
            unmapped[norm_name(obj.findtext("name") or "")] += 1
            continue
        bb = obj.find("bndbox")
        if bb is None:
            continue
        xmin = float(bb.findtext("xmin")); ymin = float(bb.findtext("ymin"))
        xmax = float(bb.findtext("xmax")); ymax = float(bb.findtext("ymax"))
        # Defensive: some IDD boxes list corners out of order; normalize then clip
        # to the image so the resulting YOLO coords are guaranteed within [0, 1].
        if xmax < xmin: xmin, xmax = xmax, xmin
        if ymax < ymin: ymin, ymax = ymax, ymin
        xmin = min(max(xmin, 0.0), w); xmax = min(max(xmax, 0.0), w)
        ymin = min(max(ymin, 0.0), h); ymax = min(max(ymax, 0.0), h)

        # VOC corner box -> YOLO normalized center box (already in [0, 1] after clip).
        cx = ((xmin + xmax) / 2) / w
        cy = ((ymin + ymax) / 2) / h
        bw = (xmax - xmin) / w
        bh = (ymax - ymin) / h
        if bw <= 0 or bh <= 0:
            continue

        lines.append(f"{CLASS_INDEX[target]} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}")
        class_counter[target] += 1

    out_lbl.write_text("\n".join(lines) + ("\n" if lines else ""))
    return len(lines)


def load_split(idd_root: Path, split: str):
    # IDD Detection ships split lists at the dataset root (train.txt / val.txt);
    # a classic VOC layout puts them under ImageSets/Main/. Check both.
    for candidate in (idd_root / f"{split}.txt",
                      idd_root / "ImageSets" / "Main" / f"{split}.txt"):
        if candidate.exists():
            return [ln.strip() for ln in candidate.read_text().splitlines() if ln.strip()]
    return None


def main() -> None:
    ap = argparse.ArgumentParser(description="Convert IDD Detection (VOC) -> YOLO format")
    ap.add_argument("--idd-root", required=True, type=Path,
                    help="Extracted IDD_Detection folder (contains JPEGImages/ + Annotations/)")
    ap.add_argument("--out", type=Path, default=Path("datasets/idd_yolo"))
    ap.add_argument("--symlink", action="store_true",
                    help="Symlink images instead of copying (faster; Linux/WSL-native FS only)")
    ap.add_argument("--val-frac", type=float, default=0.1,
                    help="Val fraction used ONLY if IDD has no ImageSets/Main split lists")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    ann_base = args.idd_root / "Annotations"
    img_base = args.idd_root / "JPEGImages"
    if not ann_base.exists() or not img_base.exists():
        raise SystemExit(
            f"Expected {ann_base} and {img_base}.\n"
            "Point --idd-root at the extracted IDD_Detection folder."
        )

    train_list = load_split(args.idd_root, "train")
    val_list = load_split(args.idd_root, "val")
    if train_list is None or val_list is None:
        all_rel = [str(p.relative_to(ann_base).with_suffix("")) for p in ann_base.rglob("*.xml")]
        random.Random(args.seed).shuffle(all_rel)
        n_val = int(len(all_rel) * args.val_frac)
        val_list, train_list = all_rel[:n_val], all_rel[n_val:]
        print(f"[i] No ImageSets/Main split found — random "
              f"{1 - args.val_frac:.0%}/{args.val_frac:.0%} train/val split.")

    unmapped: Counter = Counter()
    class_counter: Counter = Counter()
    for split, rels in (("train", train_list), ("val", val_list)):
        out_img_dir = args.out / "images" / split
        out_lbl_dir = args.out / "labels" / split
        out_img_dir.mkdir(parents=True, exist_ok=True)
        out_lbl_dir.mkdir(parents=True, exist_ok=True)

        n_img = n_miss = 0
        for rel in rels:
            xml_path = find_file(ann_base, rel, [".xml"])
            img_path = find_file(img_base, rel, [".jpg", ".jpeg", ".png"])
            if xml_path is None or img_path is None:
                n_miss += 1
                continue

            stem = rel.replace("/", "__").replace("\\", "__")
            out_img = out_img_dir / f"{stem}{img_path.suffix}"
            convert_annotation(xml_path, img_path, out_lbl_dir / f"{stem}.txt",
                               unmapped, class_counter)
            if not out_img.exists():
                if args.symlink:
                    out_img.symlink_to(img_path.resolve())
                else:
                    shutil.copy2(img_path, out_img)
            n_img += 1
        print(f"[{split}] {n_img} images converted"
              + (f" ({n_miss} skipped: missing image/xml)" if n_miss else ""))

    # data.yaml + classes.txt (classes.txt becomes the app's labels file)
    (args.out / "data.yaml").write_text(
        "# Auto-generated by idd_to_yolo.py — pass this to train.py\n"
        f"path: {args.out.resolve().as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        f"nc: {len(CLASS_NAMES)}\n"
        f"names: {CLASS_NAMES}\n"
    )
    (args.out / "classes.txt").write_text("\n".join(CLASS_NAMES) + "\n")

    print("\nClass distribution (boxes):")
    for name in CLASS_NAMES:
        print(f"  {name:16s} {class_counter.get(name, 0)}")
    if unmapped:
        print("\n[!] Unmapped IDD class names (add to IDD_TO_YOLO if you want them):")
        for name, n in unmapped.most_common():
            print(f"  {name!r}: {n}")
    print(f"\nDone -> {args.out.resolve()}")
    print(f"Next: python train.py --data {(args.out / 'data.yaml').as_posix()}")


if __name__ == "__main__":
    main()
