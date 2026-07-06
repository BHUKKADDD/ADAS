# ADAS Cloud Ingestion (Phase 4 scaffold)

The server-side counterpart to the app's on-device `UploadClient`. Receives
**anomaly packets** from the edge, persists them to a local "data lake", and
serves a dashboard + query API.

## License firewall — model-agnostic

This service is **infrastructure**. It stores the detection *labels* the device
chose to send; it never runs, loads, or depends on the detector or the **v1 IDD
model**, so it does **not** trigger the IDD non-commercial license. It plugs into
a **v2** model unchanged. (See the repo root `README.md` "Commercial firewall".)

## Run

```bash
python3 server.py            # listens on 0.0.0.0:8000 (stdlib only, no pip installs)
python3 server.py --port 9000
```

Endpoints:

| Method | Path         | Purpose                                             |
|--------|--------------|-----------------------------------------------------|
| POST   | `/ingest`    | Accept one anomaly packet (JSON) → store, return id |
| GET    | `/`          | Server-rendered dashboard (auto-refresh 5s)         |
| GET    | `/anomalies` | Last 200 anomalies as JSON                          |
| GET    | `/health`    | Liveness + counts                                   |

## Packet shape

Matches `com.example.adas.upload.AnomalyPacket`:

```json
{ "timestampMs": 1783360595941, "deviceModel": "SM-A556E",
  "speedKmh": 42, "latitude": 28.6480, "longitude": 77.3407,
  "accuracyM": 6.0,
  "detections": [ { "label": "autorickshaw", "confidence": 0.92 } ] }
```

## Connecting the phone

The app posts to `http://localhost:8000/ingest`; bridge the device to this server
with ADB reverse:

```bash
adb reverse tcp:8000 tcp:8000    # then trigger UPLOAD in the app
```

## Storage

- `data/anomalies.db` — SQLite (queried by the dashboard/API).
- `data/anomalies.jsonl` — append-only raw "data lake" log.

Both are gitignored. **Production upgrade path:** FastAPI/uvicorn for the API,
object storage (S3/GCS) for raw clip bytes, and Postgres/BigQuery for the lake;
add auth + per-tenant isolation for the B2B SaaS dashboard.
