# Source this before running train.py in the ADAS training venv:  source training/env.sh
# Reuses the exact ROCm/WSL2 runtime env proven on this box for the Commentator project.
#
# Presents the RX 7700 XT (gfx1101) as a gfx1100 7900 XTX so ROCm loads supported kernels.
export HSA_OVERRIDE_GFX_VERSION=11.0.0
# WSL/ROCDXG: route the HSA runtime to the GPU via /dev/dxg. REQUIRED on ROCm < 7.13
# (system ROCm is pinned to 7.2.4); without it torch.cuda.is_available() is False in WSL.
export HSA_ENABLE_DXG_DETECTION=1
