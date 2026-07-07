#!/usr/bin/env python3
"""
finetune_vlm.py — Phase-4 VLM fine-tuning harness (LoRA + projection MLP).

Scaffold for the Phase-4 "VLM fine-tuning infrastructure" roadmap item: adapt a
vision-language model to driving-scene understanding by training ONLY

  1. a **projection MLP** that maps frozen vision-encoder embeddings into the
     LLM's token-embedding space, and
  2. **LoRA adapters** injected into the (frozen) LLM's linear layers,

which is the standard parameter-efficient recipe (BLIP-2 / LLaVA style). The
backbone models stay frozen — so this file is pure *infrastructure* and carries
no dataset or model licensing of its own.

MODEL-AGNOSTIC / LICENSE FIREWALL: nothing here touches the v1 IDD detector.
Training data comes from the ingestion lake (`anomalies.jsonl`) — packets a
device chose to upload — turned into (scene-embedding, caption) pairs. When a
partner supplies real clips + a real VLM checkpoint, they drop in behind the
same interfaces.

Runs on the `~/adas-train` venv (PyTorch-ROCm). Two modes:

  --smoke     end-to-end pipeline test: tiny random backbone stand-ins, a few
              steps of LoRA+projection training on synthetic/lake data; asserts
              the loss goes down. No downloads, CPU-friendly, ~seconds.
  (default)   same loop, but expects real embeddings + a real checkpoint via
              --vision-dim/--llm-dim/--data; still runs without them by
              falling back to the lake-derived synthetic set.

Usage:
    source ~/adas-train/bin/activate
    python3 finetune_vlm.py --smoke
    python3 finetune_vlm.py --lake ../ingestion/data/anomalies.jsonl --epochs 3
"""
import argparse
import json
import math
import os
import sys

try:
    import torch
    import torch.nn as nn
    from torch.utils.data import DataLoader, Dataset
except ImportError:
    print("PyTorch not found. Activate the training venv first:\n"
          "  source ~/adas-train/bin/activate", file=sys.stderr)
    sys.exit(1)


# ── Adapters (the only trainable pieces) ─────────────────────────────────────

class ProjectionMLP(nn.Module):
    """Maps frozen vision embeddings -> LLM embedding space (BLIP-2/LLaVA style)."""

    def __init__(self, vision_dim: int, llm_dim: int, hidden: int = 0):
        super().__init__()
        hidden = hidden or max(vision_dim, llm_dim)
        self.net = nn.Sequential(
            nn.Linear(vision_dim, hidden),
            nn.GELU(),
            nn.Linear(hidden, llm_dim),
        )

    def forward(self, x):
        return self.net(x)


class LoRALinear(nn.Module):
    """Wrap a frozen nn.Linear with a trainable low-rank update:
    y = W0 x + (alpha/r) * B(A(x)).  Only A and B train."""

    def __init__(self, base: nn.Linear, r: int = 8, alpha: int = 16):
        super().__init__()
        self.base = base
        for p in self.base.parameters():
            p.requires_grad = False
        self.lora_a = nn.Linear(base.in_features, r, bias=False)
        self.lora_b = nn.Linear(r, base.out_features, bias=False)
        nn.init.kaiming_uniform_(self.lora_a.weight, a=math.sqrt(5))
        nn.init.zeros_(self.lora_b.weight)  # start as a no-op
        self.scale = alpha / r

    def forward(self, x):
        return self.base(x) + self.scale * self.lora_b(self.lora_a(x))


def inject_lora(module: nn.Module, r: int, alpha: int,
                targets=("q", "k", "v", "proj", "fc", "linear")):
    """Recursively replace target nn.Linear layers with LoRALinear wrappers.
    Returns the count of injected adapters.

    Skips nn.MultiheadAttention subtrees: MHA reads `out_proj.weight` directly
    (bypassing forward()), so a wrapper there breaks it. Real HF-style LLMs use
    plain nn.Linear q/k/v/o projections, which inject fine."""
    count = 0
    for name, child in list(module.named_children()):
        if isinstance(child, nn.MultiheadAttention):
            continue
        if isinstance(child, nn.Linear) and any(t in name.lower() for t in targets):
            setattr(module, name, LoRALinear(child, r=r, alpha=alpha))
            count += 1
        else:
            count += inject_lora(child, r, alpha, targets)
    return count


# ── Data: ingestion lake -> (scene embedding, caption) pairs ─────────────────

def caption_from_packet(p: dict) -> str:
    """Deterministic caption for an anomaly packet — the supervision target the
    real pipeline will replace with human/VLM-assisted annotations."""
    dets = p.get("detections") or []
    labels = ", ".join(d["label"] for d in dets) if dets else "no objects"
    speed = p.get("speedKmh")
    speed_txt = f"ego speed {speed} km/h" if speed is not None else "ego speed unknown"
    return f"Driving scene with {labels}; {speed_txt}."


class LakeDataset(Dataset):
    """Turns anomaly packets into (pseudo scene embedding, token-target) pairs.

    Until real clip frames flow through upload, the 'vision embedding' is a
    deterministic pseudo-embedding seeded from the packet content, so the
    pipeline shape is real end-to-end even though the pixels aren't here yet.
    """

    def __init__(self, packets, vision_dim: int, vocab: dict, max_len: int = 16):
        self.items = []
        for p in packets:
            cap = caption_from_packet(p)
            g = torch.Generator().manual_seed(abs(hash(json.dumps(p, sort_keys=True))) % (2**31))
            emb = torch.randn(vision_dim, generator=g)
            ids = [vocab.setdefault(w, len(vocab)) for w in cap.lower().split()][:max_len]
            ids += [0] * (max_len - len(ids))
            self.items.append((emb, torch.tensor(ids)))

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        return self.items[i]


def load_lake(path: str):
    packets = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    packets.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return packets


def synthetic_packets(n=32):
    labels = ["autorickshaw", "truck", "person", "motorcycle", "animal", "car"]
    out = []
    for i in range(n):
        out.append({
            "timestampMs": i,
            "speedKmh": (i * 7) % 90,
            "detections": [{"label": labels[i % len(labels)], "confidence": 0.5 + (i % 5) / 10}],
        })
    return out


# ── Tiny frozen "LLM" stand-in (replaced by a real checkpoint later) ─────────

class ToyLLM(nn.Module):
    """A minimal frozen transformer block + LM head. Stands in for the real
    (frozen) LLM so the LoRA/projection plumbing is exercised for real."""

    def __init__(self, llm_dim: int, vocab_size: int, max_len: int):
        super().__init__()
        self.tok = nn.Embedding(vocab_size, llm_dim)
        self.pos = nn.Embedding(max_len + 1, llm_dim)  # +1 for the vision token
        enc = nn.TransformerEncoderLayer(
            d_model=llm_dim, nhead=4, dim_feedforward=llm_dim * 2,
            batch_first=True, dropout=0.0,
        )
        self.blocks = nn.TransformerEncoder(enc, num_layers=2)
        self.head = nn.Linear(llm_dim, vocab_size)

    def forward(self, vision_token, ids):
        # Prepend the projected scene embedding as a soft "image token".
        tok = self.tok(ids)                                  # [B, L, D]
        x = torch.cat([vision_token.unsqueeze(1), tok], 1)   # [B, L+1, D]
        pos = torch.arange(x.size(1), device=x.device)
        x = self.blocks(x + self.pos(pos))
        return self.head(x[:, :-1])                          # predict next tokens


# ── Training loop ────────────────────────────────────────────────────────────

def run(args):
    device = "cuda" if (torch.cuda.is_available() and not args.cpu) else "cpu"
    print(f"device: {device}")

    # Data
    if args.lake and os.path.exists(args.lake):
        packets = load_lake(args.lake)
        print(f"loaded {len(packets)} packets from lake: {args.lake}")
        if len(packets) < 8:
            print("lake is small — padding with synthetic packets for the demo")
            packets += synthetic_packets(32 - len(packets))
    else:
        packets = synthetic_packets(64)
        print("no lake supplied — using synthetic packets")
    vocab = {"<pad>": 0}
    ds = LakeDataset(packets, args.vision_dim, vocab, args.max_len)
    dl = DataLoader(ds, batch_size=args.batch, shuffle=True)
    vocab_size = max(len(vocab) + 8, 64)

    # Models: frozen LLM + frozen (pseudo) vision encoder; trainable adapters.
    llm = ToyLLM(args.llm_dim, vocab_size, args.max_len).to(device)
    for p in llm.parameters():
        p.requires_grad = False
    n_lora = inject_lora(llm, r=args.lora_r, alpha=args.lora_alpha)
    proj = ProjectionMLP(args.vision_dim, args.llm_dim).to(device)
    llm.to(device)

    trainable = [p for p in list(llm.parameters()) + list(proj.parameters())
                 if p.requires_grad]
    n_train = sum(p.numel() for p in trainable)
    n_total = sum(p.numel() for p in llm.parameters()) + n_train
    print(f"LoRA adapters injected: {n_lora}  |  trainable params: {n_train:,} "
          f"({100 * n_train / n_total:.1f}% of {n_total:,})")

    opt = torch.optim.AdamW(trainable, lr=args.lr)
    loss_fn = nn.CrossEntropyLoss(ignore_index=0)

    first = last = None
    for epoch in range(args.epochs):
        total, steps = 0.0, 0
        for emb, ids in dl:
            emb, ids = emb.to(device), ids.to(device)
            logits = llm(proj(emb), ids)
            loss = loss_fn(logits.reshape(-1, logits.size(-1)), ids.reshape(-1))
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item()
            steps += 1
        avg = total / max(steps, 1)
        if first is None:
            first = avg
        last = avg
        print(f"epoch {epoch + 1}/{args.epochs}  loss {avg:.4f}")

    os.makedirs(args.out, exist_ok=True)
    ckpt = os.path.join(args.out, "adapters.pt")
    torch.save(
        {
            "projection": proj.state_dict(),
            "lora": {k: v for k, v in llm.state_dict().items() if "lora_" in k},
            "config": vars(args),
            "vocab_size": vocab_size,
        },
        ckpt,
    )
    print(f"adapters saved: {ckpt}")

    if args.smoke:
        assert last < first, f"smoke FAILED: loss did not decrease ({first:.4f} -> {last:.4f})"
        print(f"SMOKE PASSED: loss {first:.4f} -> {last:.4f}")


def main():
    ap = argparse.ArgumentParser(description="LoRA + projection-MLP VLM harness")
    ap.add_argument("--lake", default=None,
                    help="Path to ingestion anomalies.jsonl (default: synthetic data)")
    ap.add_argument("--vision-dim", type=int, default=512)
    ap.add_argument("--llm-dim", type=int, default=256)
    ap.add_argument("--max-len", type=int, default=16)
    ap.add_argument("--lora-r", type=int, default=8)
    ap.add_argument("--lora-alpha", type=int, default=16)
    ap.add_argument("--epochs", type=int, default=5)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--out", default="runs/vlm")
    ap.add_argument("--cpu", action="store_true")
    ap.add_argument("--smoke", action="store_true",
                    help="Fast end-to-end pipeline check; asserts loss decreases")
    args = ap.parse_args()
    if args.smoke:
        args.epochs = min(args.epochs, 5)
    run(args)


if __name__ == "__main__":
    main()
