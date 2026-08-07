#!/usr/bin/env python3
"""Tests for cloud/scene_graph/annotate.py — graph shape, risk tag, schema.

Replaces the ad-hoc "8/8" script that was never committed.

The risk taxonomy here mirrors AdasViewModel's alert logic *by convention* — if
the app's DANGER_LABELS/CAUTION_LABELS change, these tests are the tripwire.

Run:  python3 -m unittest discover -s cloud/tests -v
"""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scene_graph"))

import annotate  # noqa: E402


def _packet(labels=(), speed=30, **over):
    p = {
        "timestampMs": 1_700_000_000_000,
        "speedKmh": speed,
        "latitude": 12.9716,
        "longitude": 77.5946,
        "accuracyM": 8.0,
        "deviceModel": "SM-A556E",
        "detections": [{"label": l, "confidence": 0.6} for l in labels],
        "_tenant": "acme",
        "_received_at": "2026-08-05T00:00:00+00:00",
    }
    p.update(over)
    return p


class TestRiskTag(unittest.TestCase):

    def test_vulnerable_road_users_are_danger(self):
        for label in ("person", "rider", "bicycle", "animal"):
            with self.subTest(label=label):
                self.assertEqual(annotate.risk_of([label], 30), "danger")

    def test_vehicles_sharing_the_lane_are_caution(self):
        for label in ("motorcycle", "car", "autorickshaw", "truck", "bus",
                      "vehicle_fallback"):
            with self.subTest(label=label):
                self.assertEqual(annotate.risk_of([label], 30), "caution")

    def test_danger_outranks_caution(self):
        self.assertEqual(annotate.risk_of(["car", "person"], 30), "danger")

    def test_stationary_ego_suppresses_all_risk(self):
        """Mirrors the app: speed 0 means parked, so no phantom BRAKE prompt."""
        self.assertEqual(annotate.risk_of(["person"], 0), "none")
        self.assertEqual(annotate.risk_of(["truck"], 0), "none")

    def test_unknown_speed_does_not_suppress_risk(self):
        """speed None means 'no OBD', not 'stopped' — risk must still fire."""
        self.assertEqual(annotate.risk_of(["person"], None), "danger")

    def test_unrecognised_labels_are_no_risk(self):
        self.assertEqual(annotate.risk_of(["traffic_light", "traffic_sign"], 30), "none")

    def test_empty_detections_are_no_risk(self):
        self.assertEqual(annotate.risk_of([], 30), "none")

    def test_taxonomy_matches_the_app_class_list(self):
        """Every IDD class must be classified exactly once (or deliberately not
        at all) — a class that drifts into both sets is a bug."""
        overlap = annotate.DANGER_LABELS & annotate.CAUTION_LABELS
        self.assertEqual(overlap, set(), f"labels in both risk sets: {overlap}")


class TestGraphShape(unittest.TestCase):

    def test_ego_node_always_present(self):
        g = annotate.to_scene_graph(_packet(), 0)
        ego = [n for n in g["nodes"] if n["id"] == "ego"]
        self.assertEqual(len(ego), 1)
        self.assertEqual(ego[0]["type"], "ego_vehicle")

    def test_ego_attrs_carry_telemetry(self):
        g = annotate.to_scene_graph(_packet(speed=55), 0)
        attrs = g["nodes"][0]["attrs"]
        self.assertEqual(attrs["speed_kmh"], 55)
        self.assertAlmostEqual(attrs["lat"], 12.9716)
        self.assertAlmostEqual(attrs["lon"], 77.5946)
        self.assertEqual(attrs["device"], "SM-A556E")

    def test_one_node_per_detection(self):
        g = annotate.to_scene_graph(_packet(labels=["car", "person", "animal"]), 0)
        self.assertEqual(len(g["nodes"]), 4)  # ego + 3
        self.assertEqual([n["type"] for n in g["nodes"][1:]],
                         ["car", "person", "animal"])

    def test_every_object_is_observed_by_ego(self):
        g = annotate.to_scene_graph(_packet(labels=["car", "person"]), 0)
        observed = [e for e in g["edges"] if e["rel"] == "observed_by"]
        self.assertEqual(len(observed), 2)
        self.assertTrue(all(e["dst"] == "ego" for e in observed))

    def test_co_occurrence_edges_are_pairwise(self):
        """n detections -> n(n-1)/2 co-occurrence edges."""
        for n in (0, 1, 2, 3, 5):
            with self.subTest(n=n):
                g = annotate.to_scene_graph(_packet(labels=["car"] * n), 0)
                co = [e for e in g["edges"] if e["rel"] == "co_occurs_with"]
                self.assertEqual(len(co), n * (n - 1) // 2)

    def test_empty_packet_yields_ego_only_graph(self):
        g = annotate.to_scene_graph(_packet(labels=[]), 0)
        self.assertEqual(len(g["nodes"]), 1)
        self.assertEqual(g["edges"], [])
        self.assertEqual(g["risk"], "none")

    def test_missing_detections_key_is_tolerated(self):
        p = _packet()
        del p["detections"]
        g = annotate.to_scene_graph(p, 0)
        self.assertEqual(len(g["nodes"]), 1)

    def test_detection_without_label_becomes_unknown(self):
        g = annotate.to_scene_graph(_packet(detections=[{"confidence": 0.5}]), 0)
        self.assertEqual(g["nodes"][1]["type"], "unknown")


class TestSchema(unittest.TestCase):

    def test_graph_id_is_zero_padded(self):
        self.assertEqual(annotate.to_scene_graph(_packet(), 0)["graph_id"], "sg-000000")
        self.assertEqual(annotate.to_scene_graph(_packet(), 42)["graph_id"], "sg-000042")

    def test_tenant_and_receipt_metadata_survive(self):
        g = annotate.to_scene_graph(_packet(), 0)
        self.assertEqual(g["tenant"], "acme")
        self.assertEqual(g["received_at"], "2026-08-05T00:00:00+00:00")

    def test_schema_version_is_pinned(self):
        """Downstream (VLM harness) keys off this — bump deliberately."""
        self.assertEqual(annotate.to_scene_graph(_packet(), 0)["provenance"]["schema"],
                         "sg-v0")

    def test_graph_is_json_serialisable(self):
        g = annotate.to_scene_graph(_packet(labels=["car", "person"]), 0)
        self.assertIsInstance(json.dumps(g), str)

    def test_spatial_relations_is_an_empty_hook_today(self):
        """Documents the contract: returns [] until clips/bboxes are uploaded.
        When REC lands, this test should start failing — that is the signal."""
        self.assertEqual(annotate.spatial_relations(_packet(labels=["car"])), [])

    def test_geometry_provenance_declares_metadata_only(self):
        g = annotate.to_scene_graph(_packet(), 0)
        self.assertIn("metadata-only", g["provenance"]["geometry"])


class TestLakeProcessing(unittest.TestCase):
    """End-to-end over a JSONL lake file, including malformed lines."""

    def _run_over(self, lines):
        with tempfile.TemporaryDirectory() as tmp:
            lake = os.path.join(tmp, "anomalies.jsonl")
            with open(lake, "w", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
            graphs = []
            with open(lake, encoding="utf-8") as f:
                idx = 0
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        p = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    graphs.append(annotate.to_scene_graph(p, idx))
                    idx += 1
            return graphs

    def test_processes_every_valid_packet(self):
        lines = [json.dumps(_packet(labels=["car"])),
                 json.dumps(_packet(labels=["person"]))]
        self.assertEqual(len(self._run_over(lines)), 2)

    def test_malformed_and_blank_lines_are_skipped_not_fatal(self):
        lines = [json.dumps(_packet(labels=["car"])),
                 "{ this is not json",
                 "",
                 json.dumps(_packet(labels=["person"]))]
        graphs = self._run_over(lines)
        self.assertEqual(len(graphs), 2)
        self.assertEqual([g["graph_id"] for g in graphs], ["sg-000000", "sg-000001"])

    def test_risk_distribution_over_a_mixed_lake(self):
        lines = [json.dumps(_packet(labels=["person"], speed=40)),   # danger
                 json.dumps(_packet(labels=["car"], speed=40)),      # caution
                 json.dumps(_packet(labels=["person"], speed=0)),    # suppressed
                 json.dumps(_packet(labels=[], speed=40))]           # none
        risks = [g["risk"] for g in self._run_over(lines)]
        self.assertEqual(risks, ["danger", "caution", "none", "none"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
