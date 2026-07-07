# ADAS Cloud (Phase 4) — model-agnostic infrastructure scaffolds

Server-side counterparts to the edge app. Everything here is **model-agnostic
infrastructure**: it consumes only what devices choose to upload (labels,
telemetry, GPS) and never runs, loads, or depends on the v1 IDD detector — so
none of it triggers the IDD non-commercial license. It all plugs into a **v2**
(commercially-licensed) model unchanged. See the root `README.md` "Commercial
firewall" and `training/train_v2.py` (the firewall-enforced v2 training entry).

## Subsystems

| Dir | What | Status |
|-----|------|--------|
| `ingestion/` | Multi-tenant anomaly ingestion API + SQLite/JSONL data lake + B2B dashboard (stdlib only) | Verified: phone→cloud loop live; 10/10 auth/tenancy/filter tests |
| `scene_graph/` | Anomaly packets → per-event scene graphs (ego + road users + relations + risk tag) | Verified against the live lake; geometric edges land when clip/bbox upload ships |
| `vlm/` | LoRA + projection-MLP VLM fine-tuning harness (frozen backbones, adapters only) | `--smoke` passes: loss ↓ on real lake packets; real checkpoints drop in behind the same interfaces |

## Data flow

```
app UploadClient ──POST /ingest──▶ ingestion (SQLite + anomalies.jsonl)
                                        │
                     ┌──────────────────┴──────────────────┐
                     ▼                                     ▼
        scene_graph/annotate.py                 vlm/finetune_vlm.py
        (scene_graphs.jsonl)                    (adapters.pt)
```

## What's real vs. scaffold

Real today: the wire format, auth/tenancy model, storage schema, graph schema,
and the LoRA/projection training loop. Scaffolded stand-ins, clearly marked in
code: SQLite for the lake (→ S3/GCS + Postgres), static API keys (→ OIDC),
pseudo scene embeddings + a toy frozen LLM in the VLM harness (→ real clip
frames + a real VLM checkpoint), metadata-only scene graphs (→ geometric
relations once clips/bboxes are uploaded).

Everything runs locally with zero pip installs except the VLM harness, which
needs the existing `~/adas-train` torch venv.
