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
| `ingestion/` | Multi-tenant anomaly ingestion API + SQLite/JSONL data lake + B2B dashboard (stdlib only) | Verified: phone→cloud loop live; 32 committed tests (auth/tenancy/filters/storage/migration) |
| `scene_graph/` | Anomaly packets → per-event scene graphs (ego + road users + relations + risk tag) | Verified against the live lake; 25 committed tests; geometric edges land when clip/bbox upload ships |
| `vlm/` | LoRA + projection-MLP VLM fine-tuning harness (frozen backbones, adapters only) | `--smoke` passes: loss ↓ on real lake packets; 23 committed tests; real checkpoints drop in behind the same interfaces |

## Tests

```
python3 -m unittest discover -s cloud/tests -v
```

80 tests, run from the repo root. Stdlib only — no pip installs — except the 23
`test_vlm.py` cases, which **skip cleanly** when PyTorch is absent. To run those
too: `source ~/adas-train/bin/activate` first. Tests never touch
`cloud/ingestion/data/`; the DB and lake paths are redirected to a temp dir, and
the HTTP tests bind an ephemeral port.

Worth knowing about two of them:

- `test_multihead_attention_is_never_injected` guards the LoRA bug fixed in
  `9956645` — `nn.MultiheadAttention` reads `out_proj.weight` directly, bypassing
  `forward()`, so wrapping it silently breaks the module.
- `test_spatial_relations_is_an_empty_hook_today` asserts scene graphs carry no
  geometric edges. **It is meant to fail once REC ships clip/bbox upload** — that
  failure is the signal to implement the hook, not a regression.

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
