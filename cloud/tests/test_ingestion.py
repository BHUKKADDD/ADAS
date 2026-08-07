#!/usr/bin/env python3
"""Tests for cloud/ingestion/server.py — auth, tenancy, filters, storage, schema.

Replaces the ad-hoc "10/10" script that was never committed. Everything here
runs against a real ThreadingHTTPServer on an ephemeral port, with DB/lake paths
redirected into a temp dir so the developer's `cloud/ingestion/data/` is never
touched.

Run:  python3 -m unittest discover -s cloud/tests -v
"""
import json
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ingestion"))

import server  # noqa: E402


def _packet(**over):
    p = {
        "timestampMs": 1_700_000_000_000,
        "deviceModel": "SM-A556E",
        "speedKmh": 42,
        "latitude": 12.9716,
        "longitude": 77.5946,
        "accuracyM": 8.0,
        "detections": [{"label": "autorickshaw", "confidence": 0.71}],
    }
    p.update(over)
    return p


class IngestionTestBase(unittest.TestCase):
    """Boots the real server on an ephemeral port against a temp data dir."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        server.DATA_DIR = cls.tmp.name
        server.DB_PATH = os.path.join(cls.tmp.name, "anomalies.db")
        server.LAKE_PATH = os.path.join(cls.tmp.name, "anomalies.jsonl")
        # Module-global `print` shadows the builtin, silencing the ingest log.
        server.print = lambda *a, **k: None

        server.API_KEYS.clear()
        server.API_KEYS.update({"k-acme": "acme", "k-beta": "beta", "k-admin": "*"})
        server.init_db()

        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.port = cls.srv.server_address[1]
        cls.thread = threading.Thread(target=cls.srv.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        cls.thread.join(timeout=5)
        cls.tmp.cleanup()

    def req(self, method, path, key=None, body=None, raw_body=None):
        """Returns (status, decoded_body). Never raises on HTTP error codes."""
        url = f"http://127.0.0.1:{self.port}{path}"
        data = None
        if raw_body is not None:
            data = raw_body.encode("utf-8")
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, method=method)
        if key:
            req.add_header("X-API-Key", key)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8")

    def ingest(self, key, packet):
        status, body = self.req("POST", "/ingest", key=key, body=packet)
        return status, json.loads(body)


class TestAuth(IngestionTestBase):

    def test_health_needs_no_key(self):
        status, body = self.req("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["status"], "ok")

    def test_get_without_key_is_401(self):
        for path in ("/", "/dashboard", "/anomalies", "/stats"):
            with self.subTest(path=path):
                status, _ = self.req("GET", path)
                self.assertEqual(status, 401)

    def test_get_with_unknown_key_is_401(self):
        status, _ = self.req("GET", "/stats", key="not-a-real-key")
        self.assertEqual(status, 401)

    def test_post_without_key_is_401(self):
        status, _ = self.req("POST", "/ingest", body=_packet())
        self.assertEqual(status, 401)

    def test_key_via_query_param_works(self):
        """The browser dashboard can't set headers, so ?key= must authenticate."""
        status, _ = self.req("GET", "/stats?key=k-acme")
        self.assertEqual(status, 200)

    def test_unknown_path_is_404(self):
        status, _ = self.req("GET", "/nope", key="k-admin")
        self.assertEqual(status, 404)
        status, _ = self.req("POST", "/nope", key="k-admin", body={})
        self.assertEqual(status, 404)


class TestIngestValidation(IngestionTestBase):

    def test_valid_packet_returns_id_and_tenant(self):
        status, body = self.ingest("k-acme", _packet())
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "ok")
        self.assertEqual(body["tenant"], "acme")
        self.assertIsInstance(body["id"], int)

    def test_malformed_json_is_400(self):
        status, body = self.req("POST", "/ingest", key="k-acme", raw_body="{not json")
        self.assertEqual(status, 400)
        self.assertIn("bad packet", json.loads(body)["error"])

    def test_non_object_json_is_400(self):
        """A JSON array is valid JSON but not a packet."""
        status, _ = self.req("POST", "/ingest", key="k-acme", raw_body="[1, 2, 3]")
        self.assertEqual(status, 400)

    def test_packet_with_no_detections_is_accepted(self):
        status, body = self.ingest("k-acme", _packet(detections=[]))
        self.assertEqual(status, 200)
        self.assertIsInstance(body["id"], int)

    def test_admin_key_ingests_into_default_tenant(self):
        """'*' is a read-scope, not a writable tenant — writes land in 'default'."""
        status, body = self.ingest("k-admin", _packet())
        self.assertEqual(status, 200)
        self.assertEqual(body["tenant"], "default")


class TestTenancy(IngestionTestBase):

    def setUp(self):
        self.ingest("k-acme", _packet(deviceModel="ACME-PHONE",
                                      detections=[{"label": "truck", "confidence": 0.6}]))
        self.ingest("k-beta", _packet(deviceModel="BETA-PHONE",
                                      detections=[{"label": "animal", "confidence": 0.8}]))

    def rows(self, key):
        status, body = self.req("GET", "/anomalies", key=key)
        self.assertEqual(status, 200)
        return json.loads(body)

    def test_tenant_sees_only_own_rows(self):
        acme = self.rows("k-acme")
        self.assertTrue(acme)
        self.assertTrue(all(r["tenant"] == "acme" for r in acme))

    def test_tenant_cannot_see_other_tenants_devices(self):
        acme_devices = {r["device_model"] for r in self.rows("k-acme")}
        self.assertNotIn("BETA-PHONE", acme_devices)

    def test_admin_sees_all_tenants(self):
        tenants = {r["tenant"] for r in self.rows("k-admin")}
        self.assertIn("acme", tenants)
        self.assertIn("beta", tenants)

    def test_stats_are_tenant_scoped(self):
        _, acme = self.req("GET", "/stats", key="k-acme")
        _, admin = self.req("GET", "/stats", key="k-admin")
        self.assertLess(json.loads(acme)["anomalies"], json.loads(admin)["anomalies"])


class TestFilters(IngestionTestBase):

    def setUp(self):
        self.ingest("k-acme", _packet(deviceModel="PIXEL-9",
                                      detections=[{"label": "person", "confidence": 0.9}]))
        self.ingest("k-acme", _packet(deviceModel="SM-A556E",
                                      detections=[{"label": "bus", "confidence": 0.5}]))

    def test_device_filter_is_a_substring_match(self):
        _, body = self.req("GET", "/anomalies?device=PIXEL", key="k-acme")
        rows = json.loads(body)
        self.assertTrue(rows)
        self.assertTrue(all("PIXEL" in r["device_model"] for r in rows))

    def test_label_filter_matches_inside_detections_json(self):
        _, body = self.req("GET", "/anomalies?label=bus", key="k-acme")
        rows = json.loads(body)
        self.assertTrue(rows)
        for r in rows:
            labels = [d["label"] for d in json.loads(r["detections"])]
            self.assertIn("bus", labels)

    def test_label_filter_excludes_non_matching(self):
        _, body = self.req("GET", "/anomalies?label=bus", key="k-acme")
        for r in json.loads(body):
            labels = [d["label"] for d in json.loads(r["detections"])]
            self.assertNotIn("person", labels)

    def test_filters_cannot_cross_tenant_boundary(self):
        """A filter must never widen visibility past the tenant scope."""
        self.ingest("k-beta", _packet(deviceModel="PIXEL-9"))
        _, body = self.req("GET", "/anomalies?device=PIXEL", key="k-acme")
        self.assertTrue(all(r["tenant"] == "acme" for r in json.loads(body)))

    def test_non_numeric_limit_does_not_crash_the_endpoint(self):
        status, _ = self.req("GET", "/anomalies?limit=abc", key="k-acme")
        self.assertNotEqual(status, 500)
        self.assertIn(status, (200, 400))


class TestStorage(IngestionTestBase):

    def test_row_fields_round_trip(self):
        p = _packet(deviceModel="ROUNDTRIP", speedKmh=7, latitude=1.5, longitude=2.5)
        self.ingest("k-acme", p)
        _, body = self.req("GET", "/anomalies?device=ROUNDTRIP", key="k-acme")
        row = json.loads(body)[0]
        self.assertEqual(row["speed_kmh"], 7)
        self.assertAlmostEqual(row["latitude"], 1.5)
        self.assertAlmostEqual(row["longitude"], 2.5)
        self.assertEqual(row["n_detections"], 1)
        self.assertIsNotNone(row["received_at"])

    def test_jsonl_lake_gets_an_annotated_line(self):
        self.ingest("k-beta", _packet(deviceModel="LAKE-CHECK"))
        with open(server.LAKE_PATH, encoding="utf-8") as f:
            lines = [json.loads(l) for l in f if l.strip()]
        match = [l for l in lines if l.get("deviceModel") == "LAKE-CHECK"]
        self.assertTrue(match)
        self.assertEqual(match[-1]["_tenant"], "beta")
        self.assertIn("_received_at", match[-1])

    def test_n_detections_matches_payload(self):
        dets = [{"label": "car", "confidence": 0.5},
                {"label": "rider", "confidence": 0.4},
                {"label": "animal", "confidence": 0.3}]
        self.ingest("k-acme", _packet(deviceModel="MULTIDET", detections=dets))
        _, body = self.req("GET", "/anomalies?device=MULTIDET", key="k-acme")
        self.assertEqual(json.loads(body)[0]["n_detections"], 3)

    def test_fetch_limit_is_clamped_to_1000(self):
        self.assertLessEqual(len(server.fetch("*", limit=99999)), 1000)


class TestDashboard(IngestionTestBase):

    def test_dashboard_renders_html(self):
        status, body = self.req("GET", "/?key=k-acme")
        self.assertEqual(status, 200)
        self.assertIn("<!doctype html>", body.lower())
        self.assertIn("ADAS INGESTION", body)

    def test_dashboard_shows_tenant_scope_chip(self):
        _, acme = self.req("GET", "/?key=k-acme")
        self.assertIn("TENANT: acme", acme)
        _, admin = self.req("GET", "/?key=k-admin")
        self.assertIn("ALL TENANTS", admin)

    def test_dashboard_escapes_device_supplied_strings(self):
        """Device model is attacker-controlled; it must not render as markup."""
        self.ingest("k-acme", _packet(deviceModel="<script>alert(1)</script>"))
        _, body = self.req("GET", "/?key=k-acme")
        self.assertNotIn("<script>alert(1)</script>", body)
        self.assertIn("&lt;script&gt;", body)


class TestSchemaMigration(unittest.TestCase):
    """init_db() must upgrade a pre-tenancy database in place."""

    def test_tenant_column_is_added_to_legacy_db(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = os.path.join(tmp, "legacy.db")
            con = sqlite3.connect(db)
            con.execute(
                """CREATE TABLE anomalies (
                       id INTEGER PRIMARY KEY AUTOINCREMENT,
                       ts_ms INTEGER, device_model TEXT, speed_kmh INTEGER,
                       latitude REAL, longitude REAL, accuracy_m REAL,
                       detections TEXT, n_detections INTEGER, received_at TEXT)"""
            )
            con.execute(
                "INSERT INTO anomalies (device_model, n_detections) VALUES ('OLD', 1)")
            con.commit()
            con.close()

            saved_db, saved_dir = server.DB_PATH, server.DATA_DIR
            server.DB_PATH, server.DATA_DIR = db, tmp
            try:
                server.init_db()
                con = sqlite3.connect(db)
                cols = [r[1] for r in con.execute("PRAGMA table_info(anomalies)")]
                self.assertIn("tenant", cols)
                row = con.execute(
                    "SELECT device_model, tenant FROM anomalies").fetchone()
                con.close()
            finally:
                server.DB_PATH, server.DATA_DIR = saved_db, saved_dir

            self.assertEqual(row[0], "OLD")
            self.assertEqual(row[1], "default", "legacy rows must backfill to 'default'")

    def test_init_db_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = os.path.join(tmp, "fresh.db")
            saved_db, saved_dir = server.DB_PATH, server.DATA_DIR
            server.DB_PATH, server.DATA_DIR = db, tmp
            try:
                server.init_db()
                server.init_db()  # must not raise "duplicate column"
                con = sqlite3.connect(db)
                cols = [r[1] for r in con.execute("PRAGMA table_info(anomalies)")]
                con.close()
            finally:
                server.DB_PATH, server.DATA_DIR = saved_db, saved_dir
            self.assertEqual(cols.count("tenant"), 1)


class TestKeyLoading(unittest.TestCase):

    def setUp(self):
        self.saved = dict(server.API_KEYS)
        self.saved_env = os.environ.get("ADAS_API_KEYS")

    def tearDown(self):
        server.API_KEYS.clear()
        server.API_KEYS.update(self.saved)
        if self.saved_env is None:
            os.environ.pop("ADAS_API_KEYS", None)
        else:
            os.environ["ADAS_API_KEYS"] = self.saved_env

    def test_parses_key_tenant_pairs(self):
        server.API_KEYS.clear()
        os.environ["ADAS_API_KEYS"] = "k1:alpha,k2:beta,admin:*"
        server.load_keys()
        self.assertEqual(server.API_KEYS,
                         {"k1": "alpha", "k2": "beta", "admin": "*"})

    def test_key_without_tenant_falls_back_to_default(self):
        server.API_KEYS.clear()
        os.environ["ADAS_API_KEYS"] = "lonely"
        server.load_keys()
        self.assertEqual(server.API_KEYS["lonely"], "default")

    def test_unset_env_generates_a_dev_admin_key(self):
        server.API_KEYS.clear()
        os.environ.pop("ADAS_API_KEYS", None)
        server.print = lambda *a, **k: None
        server.load_keys()
        self.assertEqual(len(server.API_KEYS), 1)
        key, tenant = next(iter(server.API_KEYS.items()))
        self.assertTrue(key.startswith("dev-"))
        self.assertEqual(tenant, "*")


if __name__ == "__main__":
    unittest.main(verbosity=2)
