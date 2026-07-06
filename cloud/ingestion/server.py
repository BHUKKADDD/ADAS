#!/usr/bin/env python3
"""ADAS cloud ingestion service (Phase 4 scaffold) — model-agnostic, multi-tenant.

The server-side counterpart to the on-device `UploadClient`. Receives anomaly
packets from the app, persists them to a SQLite "data lake" (+ a JSONL append
log), and serves a minimal B2B dashboard and query API.

MODEL-AGNOSTIC / LICENSE FIREWALL: this service only stores the detection *labels*
the device chose to send. It never runs, loads, or depends on the detector or the
IDD model, so it does not trigger the IDD non-commercial license. It is plumbing
that will plug into a v2 model unchanged.

B2B tenancy: every request carries an API key (X-API-Key header, or ?key= for the
browser dashboard). Keys map to tenants; each tenant sees only its own rows. Keys
come from the ADAS_API_KEYS env var: "key1:tenantA,key2:tenantB,adminkey:*"
("*" = admin, sees all tenants). With no env var set, a dev key is generated and
printed at startup so local testing works out of the box.

Stdlib only — no pip installs. Production upgrade path (see README): FastAPI +
object storage (S3/GCS) for raw clips + Postgres/BigQuery for the lake + real
authn/z (OIDC) instead of static keys.

Run:  ADAS_API_KEYS="demo:acme" python3 server.py [--port 8000]
"""
import argparse
import html
import json
import os
import secrets
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(HERE, "data")
DB_PATH = os.path.join(DATA_DIR, "anomalies.db")
LAKE_PATH = os.path.join(DATA_DIR, "anomalies.jsonl")

# key -> tenant ("*" tenant = admin, sees everything)
API_KEYS: dict = {}


def load_keys():
    raw = os.environ.get("ADAS_API_KEYS", "").strip()
    if raw:
        for pair in raw.split(","):
            key, _, tenant = pair.strip().partition(":")
            if key:
                API_KEYS[key] = tenant or "default"
    else:
        dev = "dev-" + secrets.token_hex(8)
        API_KEYS[dev] = "*"
        print(f"WARNING: ADAS_API_KEYS not set — generated dev admin key: {dev}", flush=True)


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
    # Migration: add the tenant column to pre-tenancy databases.
    cols = [r[1] for r in con.execute("PRAGMA table_info(anomalies)")]
    if "tenant" not in cols:
        con.execute("ALTER TABLE anomalies ADD COLUMN tenant TEXT DEFAULT 'default'")
    con.commit()
    con.close()


def _now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def store(packet, tenant):
    """Persist one packet to SQLite + the JSONL lake. Returns the new row id."""
    dets = packet.get("detections") or []
    received = _now_iso()
    con = sqlite3.connect(DB_PATH)
    cur = con.execute(
        """INSERT INTO anomalies
           (ts_ms, device_model, speed_kmh, latitude, longitude,
            accuracy_m, detections, n_detections, received_at, tenant)
           VALUES (?,?,?,?,?,?,?,?,?,?)""",
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
            tenant,
        ),
    )
    con.commit()
    row_id = cur.lastrowid
    con.close()
    with open(LAKE_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps({**packet, "_received_at": received, "_tenant": tenant}) + "\n")
    return row_id


def fetch(tenant, device=None, label=None, limit=100):
    """Query rows visible to `tenant` ("*" = all), with optional filters."""
    q = "SELECT * FROM anomalies WHERE 1=1"
    params = []
    if tenant != "*":
        q += " AND tenant = ?"
        params.append(tenant)
    if device:
        q += " AND device_model LIKE ?"
        params.append(f"%{device}%")
    if label:
        q += " AND detections LIKE ?"
        params.append(f'%"label": "{label}%')
    q += " ORDER BY id DESC LIMIT ?"
    params.append(max(1, min(int(limit), 1000)))
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    rows = con.execute(q, params).fetchall()
    con.close()
    return [dict(r) for r in rows]


def stats(tenant):
    where, params = ("", [])
    if tenant != "*":
        where, params = " WHERE tenant = ?", [tenant]
    con = sqlite3.connect(DB_PATH)
    total = con.execute(f"SELECT COUNT(*) FROM anomalies{where}", params).fetchone()[0]
    devices = con.execute(
        f"SELECT COUNT(DISTINCT device_model) FROM anomalies{where}", params
    ).fetchone()[0]
    dets = con.execute(
        f"SELECT COALESCE(SUM(n_detections), 0) FROM anomalies{where}", params
    ).fetchone()[0]
    con.close()
    return {"anomalies": total, "devices": devices, "detections": dets}


# ── Dashboard (server-rendered, self-contained) ──────────────────────────────

def render_dashboard(tenant, key, device=None, label=None):
    s = stats(tenant)
    rows = fetch(tenant, device=device, label=label, limit=50)

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
            f"<tr>{td(r['id'])}{td(r['tenant'])}<td>{loc}</td>"
            f"<td>{html.escape(speed)}</td><td>{html.escape(labels)}</td>"
            f"{td(r['device_model'])}{td(r['received_at'])}</tr>"
        )

    tenant_chip = "ALL TENANTS (admin)" if tenant == "*" else f"TENANT: {tenant}"
    esc_key = html.escape(key)
    return f"""<!doctype html>
<html><head><meta charset="utf-8"><title>ADAS Ingestion — B2B</title>
<meta http-equiv="refresh" content="5">
<style>
  :root {{ color-scheme: dark; }}
  body {{ margin:0; font-family:ui-monospace,Menlo,Consolas,monospace;
          background:#0A0A0F; color:#B0BEC5; }}
  header {{ padding:20px 24px; border-bottom:1px solid #1c2530;
            display:flex; justify-content:space-between; align-items:center; }}
  h1 {{ margin:0; font-size:18px; letter-spacing:2px; color:#00E5FF; }}
  .sub {{ color:#546E7A; font-size:12px; margin-top:4px; }}
  .chip {{ background:#12121A; border:1px solid #00E5FF44; color:#00E5FF;
           border-radius:99px; padding:6px 14px; font-size:11px; letter-spacing:1px; }}
  .stats {{ display:flex; gap:16px; padding:20px 24px; flex-wrap:wrap; }}
  .card {{ background:#12121A; border:1px solid #1c2530; border-radius:12px;
           padding:16px 20px; min-width:120px; }}
  .card .n {{ font-size:28px; font-weight:bold; color:#ECEFF1; }}
  .card .l {{ font-size:10px; letter-spacing:1px; color:#546E7A; }}
  form {{ padding:0 24px 8px; display:flex; gap:8px; flex-wrap:wrap; }}
  input {{ background:#12121A; border:1px solid #1c2530; color:#ECEFF1;
           border-radius:8px; padding:8px 12px; font:inherit; font-size:12px; }}
  button {{ background:#00E5FF22; border:1px solid #00E5FF66; color:#00E5FF;
            border-radius:8px; padding:8px 16px; font:inherit; font-size:12px;
            letter-spacing:1px; cursor:pointer; }}
  table {{ width:100%; border-collapse:collapse; font-size:12px; }}
  th,td {{ text-align:left; padding:8px 24px; border-bottom:1px solid #14181f; }}
  th {{ color:#546E7A; font-size:10px; letter-spacing:1px; text-transform:uppercase; }}
  a {{ color:#69F0AE; text-decoration:none; }}
  .dim {{ color:#546E7A; }}
  .empty {{ padding:40px 24px; color:#546E7A; }}
</style></head><body>
<header>
  <div>
    <h1>🛰️ ADAS INGESTION</h1>
    <div class="sub">model-agnostic anomaly data lake · auto-refresh 5s</div>
  </div>
  <div class="chip">{html.escape(tenant_chip)}</div>
</header>
<div class="stats">
  <div class="card"><div class="n">{s['anomalies']}</div><div class="l">ANOMALIES</div></div>
  <div class="card"><div class="n">{s['detections']}</div><div class="l">DETECTIONS</div></div>
  <div class="card"><div class="n">{s['devices']}</div><div class="l">DEVICES</div></div>
</div>
<form method="get" action="/">
  <input type="hidden" name="key" value="{esc_key}">
  <input name="device" placeholder="filter: device model" value="{html.escape(device or '')}">
  <input name="label" placeholder="filter: detection label" value="{html.escape(label or '')}">
  <button>FILTER</button>
</form>
{'<div class="empty">No matching packets. POST to <code>/ingest</code> with your X-API-Key.</div>' if not rows else
 '<table><thead><tr><th>#</th><th>Tenant</th><th>Location</th><th>Speed</th>'
 '<th>Detections</th><th>Device</th><th>Received</th></tr></thead><tbody>' +
 ''.join(body_rows) + '</tbody></table>'}
</body></html>"""


# ── HTTP ─────────────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    server_version = "AdasIngest/0.2"

    def _send(self, code, body, content_type="application/json"):
        payload = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _auth(self, qs):
        """Resolve tenant from X-API-Key header or ?key= param. None = unauthorized."""
        key = self.headers.get("X-API-Key") or (qs.get("key") or [None])[0]
        if key and key in API_KEYS:
            return API_KEYS[key], key
        return None, None

    def do_GET(self):
        url = urlparse(self.path)
        qs = parse_qs(url.query)
        if url.path == "/health":  # unauthenticated liveness probe
            self._send(200, json.dumps({"status": "ok"}))
            return
        tenant, key = self._auth(qs)
        if tenant is None:
            self._send(401, json.dumps({"error": "missing or invalid API key "
                                                 "(X-API-Key header or ?key=)"}))
            return
        if url.path in ("/", "/dashboard"):
            device = (qs.get("device") or [None])[0]
            label = (qs.get("label") or [None])[0]
            self._send(200, render_dashboard(tenant, key, device, label),
                       "text/html; charset=utf-8")
        elif url.path == "/anomalies":
            device = (qs.get("device") or [None])[0]
            label = (qs.get("label") or [None])[0]
            limit = (qs.get("limit") or ["200"])[0]
            self._send(200, json.dumps(fetch(tenant, device, label, limit), indent=2))
        elif url.path == "/stats":
            self._send(200, json.dumps(stats(tenant)))
        else:
            self._send(404, json.dumps({"error": "not found"}))

    def do_POST(self):
        url = urlparse(self.path)
        if url.path != "/ingest":
            self._send(404, json.dumps({"error": "not found"}))
            return
        tenant, _ = self._auth(parse_qs(url.query))
        if tenant is None:
            self._send(401, json.dumps({"error": "missing or invalid API key"}))
            return
        if tenant == "*":
            tenant = "default"  # admin key ingests into the default tenant
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            packet = json.loads(raw)
            if not isinstance(packet, dict):
                raise ValueError("packet must be a JSON object")
        except Exception as e:
            self._send(400, json.dumps({"error": f"bad packet: {e}"}))
            return
        row_id = store(packet, tenant)
        print(
            f"[{_now_iso()}] ingested #{row_id} tenant={tenant} "
            f"device={packet.get('deviceModel')} "
            f"loc=({packet.get('latitude')},{packet.get('longitude')}) "
            f"speed={packet.get('speedKmh')} "
            f"detections={len(packet.get('detections') or [])}",
            flush=True,
        )
        self._send(200, json.dumps({"status": "ok", "id": row_id, "tenant": tenant}))

    def log_message(self, *args):
        pass  # quiet default access log; we print ingests ourselves


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    load_keys()
    init_db()
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    tenants = ", ".join(sorted(set(API_KEYS.values())))
    print(
        f"ADAS ingestion service on http://{args.host}:{args.port}  "
        f"(POST /ingest · GET /anomalies /stats · GET / dashboard · GET /health) "
        f"tenants: {tenants}",
        flush=True,
    )
    srv.serve_forever()


if __name__ == "__main__":
    main()
