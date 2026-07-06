#!/usr/bin/env python3
"""ADAS cloud ingestion service (Phase 4 scaffold) — model-agnostic.

The server-side counterpart to the on-device `UploadClient`. It receives anomaly
packets from the app, persists them to a SQLite "data lake" (+ a JSONL append
log), and serves a minimal dashboard and query API.

MODEL-AGNOSTIC / LICENSE FIREWALL: this service only stores the detection *labels*
the device chose to send. It never runs, loads, or depends on the detector or the
IDD model, so it does not trigger the IDD non-commercial license. It is plumbing
that will plug into a v2 model unchanged.

Stdlib only — no pip installs. Production upgrade path (see README): FastAPI +
object storage (S3/GCS) for raw clips + Postgres/BigQuery for the lake.

Run:  python3 server.py [--port 8000] [--host 0.0.0.0]
"""
import argparse
import html
import json
import os
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(HERE, "data")
DB_PATH = os.path.join(DATA_DIR, "anomalies.db")
LAKE_PATH = os.path.join(DATA_DIR, "anomalies.jsonl")


# ── Storage ──────────────────────────────────────────────────────────────────

def init_db():
    os.makedirs(DATA_DIR, exist_ok=True)
    con = sqlite3.connect(DB_PATH)
    con.execute(
        """CREATE TABLE IF NOT EXISTS anomalies (
               id            INTEGER PRIMARY KEY AUTOINCREMENT,
               ts_ms         INTEGER,
               device_model  TEXT,
               speed_kmh     INTEGER,
               latitude      REAL,
               longitude     REAL,
               accuracy_m    REAL,
               detections    TEXT,
               n_detections  INTEGER,
               received_at   TEXT
           )"""
    )
    con.commit()
    con.close()


def _now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def store(packet):
    """Persist one packet to SQLite + the JSONL lake. Returns the new row id."""
    dets = packet.get("detections") or []
    received = _now_iso()
    con = sqlite3.connect(DB_PATH)
    cur = con.execute(
        """INSERT INTO anomalies
           (ts_ms, device_model, speed_kmh, latitude, longitude,
            accuracy_m, detections, n_detections, received_at)
           VALUES (?,?,?,?,?,?,?,?,?)""",
        (
            packet.get("timestampMs"),
            packet.get("deviceModel"),
            packet.get("speedKmh"),
            packet.get("latitude"),
            packet.get("longitude"),
            packet.get("accuracyM"),
            json.dumps(dets),
            len(dets),
            received,
        ),
    )
    con.commit()
    row_id = cur.lastrowid
    con.close()
    with open(LAKE_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps({**packet, "_received_at": received}) + "\n")
    return row_id


def fetch(limit=100):
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    rows = con.execute(
        "SELECT * FROM anomalies ORDER BY id DESC LIMIT ?", (limit,)
    ).fetchall()
    con.close()
    return [dict(r) for r in rows]


def stats():
    con = sqlite3.connect(DB_PATH)
    total = con.execute("SELECT COUNT(*) FROM anomalies").fetchone()[0]
    devices = con.execute(
        "SELECT COUNT(DISTINCT device_model) FROM anomalies"
    ).fetchone()[0]
    dets = con.execute(
        "SELECT COALESCE(SUM(n_detections), 0) FROM anomalies"
    ).fetchone()[0]
    con.close()
    return {"anomalies": total, "devices": devices, "detections": dets}


# ── Dashboard (server-rendered, self-contained) ──────────────────────────────

def render_dashboard():
    s = stats()
    rows = fetch(50)

    def td(x):
        return f"<td>{html.escape('' if x is None else str(x))}</td>"

    body_rows = []
    for r in rows:
        dets = json.loads(r["detections"] or "[]")
        labels = ", ".join(
            f"{d['label']} {round(d.get('confidence', 0) * 100)}%" for d in dets
        ) or "—"
        if r["latitude"] is not None and r["longitude"] is not None:
            loc = (
                f'<a href="https://maps.google.com/?q={r["latitude"]},{r["longitude"]}" '
                f'target="_blank">{r["latitude"]:.5f}, {r["longitude"]:.5f}</a>'
                f' <span class="dim">±{int(r["accuracy_m"] or 0)}m</span>'
            )
        else:
            loc = '<span class="dim">—</span>'
        speed = "—" if r["speed_kmh"] is None else f'{r["speed_kmh"]} km/h'
        body_rows.append(
            f"<tr>{td(r['id'])}<td>{loc}</td><td>{html.escape(speed)}</td>"
            f"<td>{html.escape(labels)}</td>{td(r['device_model'])}"
            f"{td(r['received_at'])}</tr>"
        )

    return f"""<!doctype html>
<html><head><meta charset="utf-8"><title>ADAS Ingestion Dashboard</title>
<meta http-equiv="refresh" content="5">
<style>
  :root {{ color-scheme: dark; }}
  body {{ margin:0; font-family:ui-monospace,Menlo,Consolas,monospace;
          background:#0A0A0F; color:#B0BEC5; }}
  header {{ padding:20px 24px; border-bottom:1px solid #1c2530; }}
  h1 {{ margin:0; font-size:18px; letter-spacing:2px; color:#00E5FF; }}
  .sub {{ color:#546E7A; font-size:12px; margin-top:4px; }}
  .stats {{ display:flex; gap:16px; padding:20px 24px; flex-wrap:wrap; }}
  .card {{ background:#12121A; border:1px solid #1c2530; border-radius:12px;
           padding:16px 20px; min-width:120px; }}
  .card .n {{ font-size:28px; font-weight:bold; color:#ECEFF1; }}
  .card .l {{ font-size:10px; letter-spacing:1px; color:#546E7A; }}
  table {{ width:100%; border-collapse:collapse; font-size:12px; }}
  th,td {{ text-align:left; padding:8px 24px; border-bottom:1px solid #14181f; }}
  th {{ color:#546E7A; font-size:10px; letter-spacing:1px; text-transform:uppercase; }}
  a {{ color:#69F0AE; text-decoration:none; }}
  .dim {{ color:#546E7A; }}
  .empty {{ padding:40px 24px; color:#546E7A; }}
</style></head><body>
<header>
  <h1>🛰️ ADAS INGESTION</h1>
  <div class="sub">model-agnostic anomaly data lake · auto-refresh 5s</div>
</header>
<div class="stats">
  <div class="card"><div class="n">{s['anomalies']}</div><div class="l">ANOMALIES</div></div>
  <div class="card"><div class="n">{s['detections']}</div><div class="l">DETECTIONS</div></div>
  <div class="card"><div class="n">{s['devices']}</div><div class="l">DEVICES</div></div>
</div>
{'<div class="empty">No packets yet. POST one to <code>/ingest</code>.</div>' if not rows else
 '<table><thead><tr><th>#</th><th>Location</th><th>Speed</th><th>Detections</th>'
 '<th>Device</th><th>Received</th></tr></thead><tbody>' + ''.join(body_rows) +
 '</tbody></table>'}
</body></html>"""


# ── HTTP ─────────────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    server_version = "AdasIngest/0.1"

    def _send(self, code, body, content_type="application/json"):
        payload = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path in ("/", "/dashboard"):
            self._send(200, render_dashboard(), "text/html; charset=utf-8")
        elif self.path == "/health":
            self._send(200, json.dumps({"status": "ok", **stats()}))
        elif self.path.startswith("/anomalies"):
            self._send(200, json.dumps(fetch(200), indent=2))
        else:
            self._send(404, json.dumps({"error": "not found"}))

    def do_POST(self):
        if self.path != "/ingest":
            self._send(404, json.dumps({"error": "not found"}))
            return
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            packet = json.loads(raw)
            if not isinstance(packet, dict):
                raise ValueError("packet must be a JSON object")
        except Exception as e:
            self._send(400, json.dumps({"error": f"bad packet: {e}"}))
            return
        row_id = store(packet)
        dets = len(packet.get("detections") or [])
        print(
            f"[{_now_iso()}] ingested #{row_id} "
            f"device={packet.get('deviceModel')} "
            f"loc=({packet.get('latitude')},{packet.get('longitude')}) "
            f"speed={packet.get('speedKmh')} detections={dets}",
            flush=True,
        )
        self._send(200, json.dumps({"status": "ok", "id": row_id}))

    def log_message(self, *args):
        pass  # quiet default access log; we print ingests ourselves


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    init_db()
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(
        f"ADAS ingestion service on http://{args.host}:{args.port}  "
        f"(POST /ingest · GET /anomalies · GET / dashboard · GET /health)",
        flush=True,
    )
    srv.serve_forever()


if __name__ == "__main__":
    main()
