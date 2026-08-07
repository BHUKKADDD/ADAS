#!/usr/bin/env python3
"""Tests for cloud/vlm/finetune_vlm.py — LoRA injection, adapters, lake dataset.

Skipped wholesale when PyTorch is absent, so the suite still runs on a bare
system. To run these:  source ~/adas-train/bin/activate

The headline test here is `test_multihead_attention_is_never_injected` — a
regression guard for the bug fixed in 9956645. nn.MultiheadAttention reads
`out_proj.weight` directly instead of calling forward(), so wrapping its
internals in a LoRALinear silently breaks the module.

Run:  python3 -m unittest discover -s cloud/tests -v
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vlm"))

try:
    import torch
    import torch.nn as nn
    HAVE_TORCH = True
except ImportError:  # pragma: no cover - environment dependent
    HAVE_TORCH = False

# finetune_vlm calls sys.exit(1) at import time when torch is missing, so it can
# only be imported behind the guard above.
if HAVE_TORCH:
    import finetune_vlm as fv


@unittest.skipUnless(HAVE_TORCH, "PyTorch not installed (source ~/adas-train/bin/activate)")
class TestLoRAInjection(unittest.TestCase):

    def test_multihead_attention_is_never_injected(self):
        """Regression guard for 9956645. MHA must come out untouched AND working."""
        class WithMHA(nn.Module):
            def __init__(self):
                super().__init__()
                self.attn = nn.MultiheadAttention(16, 2, batch_first=True)

        m = WithMHA()
        n = fv.inject_lora(m, r=4, alpha=8)
        self.assertEqual(n, 0, "LoRA must not be injected into an MHA subtree")
        self.assertIsInstance(m.attn, nn.MultiheadAttention)
        self.assertIsInstance(m.attn.out_proj.weight, torch.Tensor)

        x = torch.randn(2, 4, 16)
        out, _ = m.attn(x, x, x)  # would raise if out_proj had been wrapped
        self.assertEqual(out.shape, (2, 4, 16))

    def test_transformer_encoder_still_runs_after_injection(self):
        """TransformerEncoderLayer embeds an MHA — the real shape of the bug."""
        enc = nn.TransformerEncoderLayer(d_model=16, nhead=2, dim_feedforward=32,
                                         batch_first=True, dropout=0.0)
        model = nn.TransformerEncoder(enc, num_layers=2)
        fv.inject_lora(model, r=4, alpha=8)
        out = model(torch.randn(2, 4, 16))
        self.assertEqual(out.shape, (2, 4, 16))

    def test_targeted_linear_layers_are_wrapped(self):
        class Block(nn.Module):
            def __init__(self):
                super().__init__()
                self.q_proj = nn.Linear(8, 8)
                self.k_proj = nn.Linear(8, 8)
                self.v_proj = nn.Linear(8, 8)
                self.fc1 = nn.Linear(8, 8)

        m = Block()
        n = fv.inject_lora(m, r=4, alpha=8)
        self.assertEqual(n, 4)
        self.assertIsInstance(m.q_proj, fv.LoRALinear)
        self.assertIsInstance(m.fc1, fv.LoRALinear)

    def test_untargeted_layers_are_left_alone(self):
        class Block(nn.Module):
            def __init__(self):
                super().__init__()
                self.classifier = nn.Linear(8, 8)  # not in the target name list

        m = Block()
        self.assertEqual(fv.inject_lora(m, r=4, alpha=8), 0)
        self.assertIsInstance(m.classifier, nn.Linear)

    def test_injection_recurses_into_nested_modules(self):
        class Inner(nn.Module):
            def __init__(self):
                super().__init__()
                self.q_proj = nn.Linear(8, 8)

        class Outer(nn.Module):
            def __init__(self):
                super().__init__()
                self.a = Inner()
                self.b = Inner()

        self.assertEqual(fv.inject_lora(Outer(), r=4, alpha=8), 2)


@unittest.skipUnless(HAVE_TORCH, "PyTorch not installed")
class TestLoRALinear(unittest.TestCase):

    def test_starts_as_an_exact_no_op(self):
        """lora_b is zero-init, so the wrapped layer must match the base exactly."""
        base = nn.Linear(8, 8)
        x = torch.randn(3, 8)
        expected = base(x).clone()
        wrapped = fv.LoRALinear(base, r=4, alpha=8)
        torch.testing.assert_close(wrapped(x), expected)

    def test_base_weights_are_frozen_and_adapters_are_not(self):
        wrapped = fv.LoRALinear(nn.Linear(8, 8), r=4, alpha=8)
        self.assertFalse(any(p.requires_grad for p in wrapped.base.parameters()))
        self.assertTrue(all(p.requires_grad for p in wrapped.lora_a.parameters()))
        self.assertTrue(all(p.requires_grad for p in wrapped.lora_b.parameters()))

    def test_scale_is_alpha_over_r(self):
        self.assertAlmostEqual(fv.LoRALinear(nn.Linear(4, 4), r=8, alpha=16).scale, 2.0)

    def test_adapter_changes_output_once_trained(self):
        wrapped = fv.LoRALinear(nn.Linear(8, 8), r=4, alpha=8)
        with torch.no_grad():
            wrapped.lora_b.weight.fill_(0.1)
        x = torch.randn(3, 8)
        self.assertFalse(torch.allclose(wrapped(x), wrapped.base(x)))

    def test_adapter_is_a_small_fraction_of_base_params(self):
        base = nn.Linear(256, 256)
        wrapped = fv.LoRALinear(base, r=8, alpha=16)
        trainable = sum(p.numel() for p in wrapped.parameters() if p.requires_grad)
        frozen = sum(p.numel() for p in wrapped.base.parameters())
        self.assertLess(trainable, frozen * 0.1)


@unittest.skipUnless(HAVE_TORCH, "PyTorch not installed")
class TestProjectionMLP(unittest.TestCase):

    def test_maps_vision_dim_to_llm_dim(self):
        proj = fv.ProjectionMLP(vision_dim=64, llm_dim=32)
        self.assertEqual(proj(torch.randn(5, 64)).shape, (5, 32))

    def test_hidden_defaults_to_the_larger_dim(self):
        proj = fv.ProjectionMLP(vision_dim=64, llm_dim=32)
        self.assertEqual(proj.net[0].out_features, 64)


@unittest.skipUnless(HAVE_TORCH, "PyTorch not installed")
class TestCaptions(unittest.TestCase):

    def test_caption_lists_labels_and_speed(self):
        cap = fv.caption_from_packet(
            {"speedKmh": 40, "detections": [{"label": "autorickshaw"},
                                            {"label": "person"}]})
        self.assertIn("autorickshaw, person", cap)
        self.assertIn("ego speed 40 km/h", cap)

    def test_caption_handles_no_detections(self):
        self.assertIn("no objects", fv.caption_from_packet({"speedKmh": 0}))

    def test_caption_handles_missing_speed(self):
        self.assertIn("ego speed unknown", fv.caption_from_packet({"detections": []}))


@unittest.skipUnless(HAVE_TORCH, "PyTorch not installed")
class TestLakeDataset(unittest.TestCase):

    def test_pseudo_embedding_is_deterministic_per_packet(self):
        """Same packet must always yield the same stand-in embedding, or the
        'training' signal is pure noise."""
        p = {"speedKmh": 10, "detections": [{"label": "car"}]}
        a = fv.LakeDataset([p], vision_dim=16, vocab={"<pad>": 0})[0][0]
        b = fv.LakeDataset([p], vision_dim=16, vocab={"<pad>": 0})[0][0]
        torch.testing.assert_close(a, b)

    def test_different_packets_get_different_embeddings(self):
        v = {"<pad>": 0}
        a = fv.LakeDataset([{"speedKmh": 10, "detections": []}], 16, v)[0][0]
        b = fv.LakeDataset([{"speedKmh": 90, "detections": []}], 16, v)[0][0]
        self.assertFalse(torch.allclose(a, b))

    def test_token_ids_are_padded_to_max_len(self):
        ds = fv.LakeDataset([{"speedKmh": 10, "detections": []}], 16,
                            {"<pad>": 0}, max_len=24)
        self.assertEqual(ds[0][1].shape, (24,))

    def test_vocab_grows_across_packets(self):
        vocab = {"<pad>": 0}
        fv.LakeDataset([{"speedKmh": 10, "detections": [{"label": "autorickshaw"}]}],
                       16, vocab)
        self.assertGreater(len(vocab), 1)

    def test_dataset_length_matches_packet_count(self):
        packets = fv.synthetic_packets(7)
        self.assertEqual(len(fv.LakeDataset(packets, 16, {"<pad>": 0})), 7)

    def test_load_lake_skips_malformed_lines(self):
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "lake.jsonl")
            with open(path, "w", encoding="utf-8") as f:
                f.write(json.dumps({"speedKmh": 1}) + "\n")
                f.write("{ broken\n")
                f.write("\n")
                f.write(json.dumps({"speedKmh": 2}) + "\n")
            self.assertEqual(len(fv.load_lake(path)), 2)


@unittest.skipUnless(HAVE_TORCH, "PyTorch not installed")
class TestToyLLM(unittest.TestCase):

    def test_forward_predicts_one_logit_per_input_token(self):
        llm = fv.ToyLLM(llm_dim=32, vocab_size=64, max_len=8)
        logits = llm(torch.randn(2, 32), torch.zeros(2, 8, dtype=torch.long))
        self.assertEqual(logits.shape, (2, 8, 64))

    def test_gradients_reach_adapters_but_not_frozen_backbone(self):
        """The whole point of the harness: only adapters move."""
        llm = fv.ToyLLM(llm_dim=32, vocab_size=64, max_len=8)
        for p in llm.parameters():
            p.requires_grad = False
        fv.inject_lora(llm, r=4, alpha=8)
        proj = fv.ProjectionMLP(16, 32)

        logits = llm(proj(torch.randn(2, 16)), torch.zeros(2, 8, dtype=torch.long))
        logits.sum().backward()

        self.assertIsNone(llm.tok.weight.grad, "frozen embedding must not accumulate grad")
        lora_grads = [p.grad for n, p in llm.named_parameters()
                      if "lora_" in n and p.requires_grad]
        self.assertTrue(lora_grads)
        self.assertTrue(any(g is not None and g.abs().sum() > 0 for g in lora_grads))


if __name__ == "__main__":
    unittest.main(verbosity=2)
