#!/usr/bin/env python3
"""
annotate.py — Phase-4 scene-graph annotation pipeline (scaffold).

Converts ingested anomaly packets into per-event **scene graphs**: nodes for the
ego vehicle and every detected road user, edges for the relations we can assert
from the packet metadata. This is the substrate the VLM harness (and the future
3D pipeline) trains against.

Today's packets are metadata-only (labels + confidence + ego speed/GPS — no
bounding boxes or clip frames yet), so the graphs carry observation and
co-occurrence relations plus a risk tag mirroring the app's alert logic. When
clip bytes/bboxes start flowing through upload, the `spatial_relations` hook
upgrades these to real geometric edges (left-of / in-lane / distance-binned)
without changing the output schema.

MODEL-AGNOSTIC: consumes only what devices uploaded; never touches the detector
or the IDD dataset.

Usage:
    python3 annotate.py --lake ../ingestion/data/anomalies.jsonl
    python3 annotate.py --lake ... --out graphs.jsonl --pretty-sample
"""
import argparse
import json
import sys

# Mirrors the app's road-user taxonomy (kept in sync by convention). The danger
# set now lives in android_app/.../fcw/CollisionSignals.kt as VULNERABLE_LABELS,
# where on-device it buys a longer time-to-collision warning threshold rather than
# an unconditional alert. Here it stays a coarse risk tag: packets carry no
# bounding boxes yet, so there is nothing to compute a TTC from server-side.
DANGER_LABELS = {"person", "rider", "bicycle", "animal"}
CAUTION_LABELS = {"motorcycle", "car", "autorickshaw", "truck", "bus", "vehicle_fallback"}


def risk_of(labels, speed_kmh):
    if speed_kmh == 0:
        return "none"  # stationary — mirrors the app's alert suppression
    if any(l in DANGER_LABELS for l in labels):
        return "danger"
    if any(l in CAUTION_LABELS for l in labels):
        return "caution"
    return "none"


def spatial_relations(packet):
    """Geometric relations hook. Packets carry no bboxes yet, so this returns
    [] today; once frames/bboxes are uploaded, emit left_of / in_lane_of /
    distance-binned edges here. The schema downstream already accepts them."""
    return []


def to_scene_graph(packet, idx):
    dets = packet.get("detections") or []
    labels = [d.get("label", "unknown") for d in dets]
    speed = packet.get("speedKmh")

    nodes = [{
        "id": "ego",
        "type": "ego_vehicle",
        "attrs": {
            "speed_kmh": speed,
            "lat": packet.get("latitude"),
            "lon": packet.get("longitude"),
            "gps_accuracy_m": packet.get("accuracyM"),
            "device": packet.get("deviceModel"),
        },
    }]
    edges = []
    for i, d in enumerate(dets):
        nid = f"obj{i}"
        nodes.append({
            "id": nid,
            "type": d.get("label", "unknown"),
            "attrs": {"confidence": d.get("confidence")},
        })
        edges.append({"src": nid, "rel": "observed_by", "dst": "ego"})
        # Co-occurrence between simultaneous detections.
        for j in range(i):
            edges.append({"src": nid, "rel": "co_occurs_with", "dst": f"obj{j}"})
    edges.extend(spatial_relations(packet))

    return {
        "graph_id": f"sg-{idx:06d}",
        "source_ts_ms": packet.get("timestampMs"),
        "tenant": packet.get("_tenant"),
        "received_at": packet.get("_received_at"),
        "risk": risk_of(labels, speed),
        "nodes": nodes,
        "edges": edges,
        "provenance": {
            "pipeline": "scene_graph/annotate.py",
            "schema": "sg-v0",
            "geometry": "none (metadata-only packets; awaiting clip/bbox upload)",
        },
    }


def main():
    ap = argparse.ArgumentParser(description="Anomaly packets -> scene graphs")
    ap.add_argument("--lake", required=True, help="ingestion anomalies.jsonl")
    ap.add_argument("--out", default="scene_graphs.jsonl")
    ap.add_argument("--pretty-sample", action="store_true",
                    help="Print the first graph pretty-printed")
    args = ap.parse_args()

    n_in = n_out = 0
    risks = {}
    first = None
    with open(args.lake, "r", encoding="utf-8") as f, \
         open(args.out, "w", encoding="utf-8") as out:
        for line in f:
            line = line.strip()
            if not line:
                continue
            n_in += 1
            try:
                packet = json.loads(line)
            except json.JSONDecodeError:
                continue
            g = to_scene_graph(packet, n_out)
            if first is None:
                first = g
            risks[g["risk"]] = risks.get(g["risk"], 0) + 1
            out.write(json.dumps(g) + "\n")
            n_out += 1

    print(f"packets read: {n_in}  ·  scene graphs written: {n_out}  ->  {args.out}")
    print(f"risk distribution: {risks}")
    if args.pretty_sample and first:
        print("\n--- sample graph ---")
        json.dump(first, sys.stdout, indent=2)
        print()


if __name__ == "__main__":
    main()
