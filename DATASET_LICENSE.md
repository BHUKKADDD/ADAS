# Dataset License & Attribution

## Training data: India Driving Dataset (IDD) — Detection

The Phase 3 model is fine-tuned on the **India Driving Dataset (IDD) Detection**
subset (IIIT Hyderabad). IDD is provided under a **non-commercial research license**
(the "Indian Driving Dataset Research Use License"). Using it places obligations on
this project and on anything trained from it.

### What the IDD license requires (summary — the EULA you accepted is authoritative)
- **Attribution.** Any work that uses the dataset must cite IDD (preferred
  publication below) or link to <https://idd.insaan.iiit.ac.in/>.
- **No redistribution of the data.** Do NOT distribute the dataset or any modified /
  reformatted copy of it — this explicitly includes our converted YOLO copy under
  `datasets/idd_yolo/` (it re-packages IDD's own images). Distributing *derivative
  works that are abstract representations* — e.g. a model trained on it — IS allowed,
  provided they do not let anyone recover the dataset.
- **Non-commercial only.** Academic / personal / research use only. No licensing,
  selling, or use intended to procure commercial gain.
- **Privacy.** Do not reproduce faces or license plates (blur them in any published
  sample images). Face / vehicle identification is prohibited.
- All rights not expressly granted are reserved by the IDD team.

### Consequence for THIS repo (important)
- The **code** is MIT-licensed. That license does **not** extend to the IDD-trained
  model.
- The fine-tuned `yolov8n.tflite` shipped in
  `android_app/app/src/main/assets/` is a **derivative of IDD** and is therefore
  **non-commercial, research-use only** — it is **NOT** covered by the repo's MIT
  license. Distributing the *model* is permitted (it's an abstract representation);
  distributing the *data* is not.
- ⚠️ **The commercial roadmap (B2B SaaS, DePIN, insurance API — README Phases 4–5)
  cannot use this v1 IDD model.** Those phases are gated on a **"v2" model trained from
  scratch on commercially-licensed or self-collected data** — see the "Commercial
  firewall" in the README roadmap. This model is **v1: research/non-commercial only.**

### Commercialization path (how to sell without breaching the IDD license)
The commercial offering is the **pipeline + methodology + engineering expertise**, not the
IDD model. A partner supplies their **own** road-scene data; our pipeline
(`training/idd_to_yolo.py` → `training/train.py` → `export_model.py`) trains a **v2 model**
on it that the partner owns outright. Because neither the IDD dataset nor the IDD-trained
`.tflite` is transferred, the IDD license is never triggered. Two rules keep it clean:
1. **Train v2 from stock `yolov8n.pt`, never from the IDD `best.pt`** — otherwise the v2
   model inherits the IDD-derivative (non-commercial) status.
2. See the third-party license note below — the *training framework* has its own commercial
   terms, independent of IDD.

## Third-party: Ultralytics YOLOv8 (AGPL-3.0)

The training/export pipeline depends on **Ultralytics YOLOv8**, licensed **AGPL-3.0**. This
is separate from — and, for commercialization, more restrictive than — the IDD license:

- **Non-commercial v1 (this repo):** AGPL-3.0 obligations are satisfied (code is public;
  nothing is distributed commercially).
- **Commercial v2:** AGPL-3.0 requires either open-sourcing the whole product **or** an
  **[Ultralytics Enterprise License](https://www.ultralytics.com/license)**. A commercial
  partner should procure that license, **or** the pipeline can be swapped to a
  permissively-licensed detector (Apache / BSD / MIT). The Ultralytics dependency is
  isolated to `training/train.py` and `export_model.py`; the data-conversion step
  (`training/idd_to_yolo.py`) and the YOLO label format are framework-agnostic and
  unaffected, so the swap is a small, contained change. Note the app's `parseOutput`
  assumes a YOLOv8-shaped output tensor, so staying within the YOLO family keeps the
  Android side unchanged.

### Preferred citation (verify the exact form on the IDD website)
> G. Varma, A. Subramanian, A. Namboodiri, M. Chandraker, C. V. Jawahar.
> "IDD: A Dataset for Exploring Problems of Autonomous Navigation in Unconstrained
> Environments." *IEEE Winter Conference on Applications of Computer Vision (WACV)*,
> 2019.

### Compliance checklist
- [x] Dataset, converted copy, and training-run outputs are git-ignored
      (see `.gitignore`).
- [ ] IDD citation shown in the app (about / settings) once the IDD-trained model
      is bundled — the license requires attribution for "other media".
- [ ] IDD attribution present in README and in any thesis / paper / demo / video.
- [ ] Faces and license plates blurred in any published screenshots or sample frames.
- [ ] Model artifacts clearly labeled non-commercial / research-use.
