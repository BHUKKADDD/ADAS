# ADAS Cloud Ingestion (Phase 4 scaffold) — multi-tenant

The server-side counterpart to the app's on-device `UploadClient`. Receives
**anomaly packets** from the edge, persists them to a local "data lake", and
serves a **B2B multi-tenant** dashboard + query API.

## License firewall — model-agnostic

This service is **infrastructure**. It stores the detection *labels* the device
chose to send; it never runs, loads, or depends on the detector or the **v1 IDD
model**, so it does **not** trigger the IDD non-commercial license. It plugs into
a **v2** model unchanged. (See the repo root `README.md` "Commercial firewall".)

## Run

```bash
# keys map to tenants; "*" = admin (sees all tenants)
ADAS_API_KEYS="acme-key-1:acme,fleet-key-2:fleetco,root-key:*" python3 server.py
python3 server.py --port 9000      # no keys set -> generates+prints a dev admin key
```

Stdlib only — no pip installs.

## Auth & tenancy

Every request (except `/health`) needs an API key: `X-API-Key` header, or `?key=`
for the browser dashboard. Each key is bound to a tenant; **a tenant only sees its
own rows**. Ingests are stamped with the caller's tenant.

## Endpoints

| Method | Path         | Purpose                                                  |
|--------|--------------|----------------------------------------------------------|
| POST   | `/ingest`    | Accept one anomaly packet (JSON) → store, return id+tenant |
| GET    | `/`          | Dashboard (auto-refresh 5 s, tenant-scoped, filter form) |
| GET    | `/anomalies` | JSON rows; filters: `?device=&label=&limit=`             |
| GET    | `/stats`     | Tenant-scoped counts                                     |
| GET    | `/health`    | Unauthenticated liveness probe                           |

## Packet shape

Matches `com.example.adas.upload.AnomalyPacket`:

```json
{ "timestampMs": 1783360595941, "deviceModel": "SM-A556E",
  "speedKmh": 42, "latitude": 28.6480, "longitude": 77.3407,
  "accuracyM": 6.0,
  "detections": [ { "label": "autorickshaw", "confidence": 0.92 } ] }
```

## Connecting the phone

The app posts to `http://localhost:8000/ingest`; bridge the device with ADB
reverse. NOTE (this dev machine): device port 8000 has a stuck stale listener —
use 8137 and point `AdasViewModel.uploadEndpoint` at `:8137` for device tests:

```bash
adb reverse tcp:8137 tcp:8000    # then trigger UPLOAD in the app
```

(The app must also send `X-API-Key` once the server has keys configured — add the
header to `UploadClient.upload()` when wiring a real deployment.)

## Storage

- `data/anomalies.db` — SQLite (tenant column added by auto-migration).
- `data/anomalies.jsonl` — append-only raw "data lake" log (`_tenant` stamped).

Both are gitignored. Downstream consumers: `../scene_graph/annotate.py` (packets →
scene graphs) and `../vlm/finetune_vlm.py` (packets → VLM training pairs).

**Production upgrade path:** FastAPI/uvicorn, object storage (S3/GCS) for raw clip
bytes, Postgres/BigQuery for the lake, OIDC instead of static keys, per-tenant
rate limits + retention policies.
