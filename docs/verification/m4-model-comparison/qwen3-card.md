---
language:
- en
license: apache-2.0
license_link: https://huggingface.co/Qwen/Qwen3-0.6B/blob/main/LICENSE
pipeline_tag: text-generation
base_model: Qwen/Qwen3-0.6B
base_model_relation: quantized
library_name: litert-lm
tags:
- litert-lm
- litertlm
- qwen
- Qwen3
---
# litert-community/Qwen3-0.6B

Main model card: [Qwen/Qwen3-0.6B](https://huggingface.co/Qwen/Qwen3-0.6B)

This repository contains LiteRT-LM variants of Qwen3-0.6B for Android and desktop deployment.

## Available Artifacts

| File | Quantization | Context | Size |
|---|---|---:|---:|
| `Qwen3-0.6B.litertlm` | dynamic INT8 weights, float KV | 4096 | 586 MB |
| `Qwen3-0.6B.mediatek.mt6993.litertlm` | a16w8 NPU-targeted | 4096 | 992 MB |
| `qwen3_0_6b_mixed_int4.litertlm` | TorchAO mixed INT4, float KV | 2048 | 474.61 MiB |
| `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` | dynamic INT4 (block-32) weights, float KV | 4096 | 329 MB |


## Conversion Notes

The mixed INT4 `.litertlm` artifact was produced with a TorchAO-based quantize-first recipe from the original Hugging Face checkpoint. This is a mixed quantization layout rather than a uniform all-INT4 model: eligible linear projection weights are stored as blockwise INT4 with group size 32 and floating-point scales, token embedding weights use weight-only INT8 quantization, and normalization/reduction paths plus KV cache tensors remain floating point.

The mixed INT4 bundle also uses LiteRT-LM StableHLO composite ops for attention/cache execution, including `odml.runtime_bmm` and `odml.cache_update`.

`Qwen3-0.6B.litertlm` is a separate dynamic INT8 artifact. It was converted through the LiteRT Torch (`litert-torch`) path and quantized with AI Edge Quantizer. This artifact is independent from `qwen3_0_6b_mixed_int4.litertlm`, which uses the TorchAO-based mixed INT4 recipe described above.

`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` is a separate dynamic INT4 variant (block-32 weights, FP32 activations). It was converted through LiteRT Torch (`litert-torch`) path and quantized with AI Edge Quantizer. This artifact incorporates LiteRT-LM GPU graph optimizations, including composite ops for RoPE, SwiGLU and KV-cache update, fused QKV, and fused Gate/Up projections, and is configured with static prefill memory allocation (prefill lengths 128 and 1024). It was re-exported on 2026-09-20 from `litert-torch` main (`731ef0a`). Its chat template is LiteRT-LM's Qwen3 template at [`a8178d7d`](https://github.com/google-ai-edge/LiteRT-LM/blob/a8178d7dca6ac4d472476c9d76e482d8c8cbee3c/models/qwen3/LlmMetadataProto.pbtext), which accepts message content as a string or as a list of parts, so the file runs on LiteRT-LM 0.17.1 and on current main builds.


## Android Performance Examples

These are representative measurements from retail devices to give a rough sense of on-device runtime behavior, not a direct comparison between hardware platforms. All numbers were collected with LiteRT-LM's `litert_lm_advanced_main` launched from an adb command line on the connected device; they are not app-level measurements from an integrated Android application.

Hardware benchmark disclosure: Results were measured by us on retail devices purchased through normal channels. These results are not affiliated with, sponsored by, endorsed by, or verified by Samsung, vivo, Qualcomm, MediaTek, Google, MLCommons, or Hugging Face. Results depend on device SKU, OS build, thermal state, battery mode, backend, model quantization, runtime version, and benchmark settings.

### `qwen3_0_6b_mixed_int4.litertlm`

Context: 2048. Shape: 256 prefill tokens / 256 decode tokens. Rows use LiteRT-LM v0.13.1. Values report the warmed iteration from a two-iteration run unless noted.

| Example device | Backend | Prefill (tok/s) | Decode (tok/s) | TTFT (s) | Peak Private Footprint |
|---|---|---:|---:|---:|---:|
| Samsung SM-S937U1 | GPU OpenCL | 1844.95 | 69.38 | 0.150 | 585 MB |
| vivo V2502A | GPU OpenCL | 1055.89 | 22.34 | 0.285 | 1856 MB |
| TECNO LJ9 | GPU OpenCL | 637.01 | 33.51 | 0.430 | 1832 MB |
| Samsung SM-S937U1 | CPU | 576.59 | 12.90 | 0.520 | 2895 MB |
| TECNO LJ9 | CPU | 231.15 | 8.33 | 1.230 | 2890 MB |

### `Qwen3-0.6B.litertlm`

Context: 4096. Samsung and TECNO rows use 256 prefill tokens / 256 decode tokens with LiteRT-LM v0.13.1. The vivo rows are previously published 4096-context reference results; TTFT, peak footprint, and exact prompt/decode shape were not recorded in this update.

| Example device | Backend | Prefill (tok/s) | Decode (tok/s) | TTFT (s) | Peak Private Footprint |
|---|---|---:|---:|---:|---:|
| Samsung SM-S937U1 | GPU OpenCL | 646.33 | 25.31 | 0.440 | 2940 MB |
| TECNO LJ9 | GPU OpenCL | 254.24 | 12.10 | 1.090 | 4283 MB |
| vivo V2502A | GPU OpenCL | 580 | 21 | - | - |
| Samsung SM-S937U1 | CPU | 212.07 | 13.02 | 1.280 | 2697 MB |
| TECNO LJ9 | CPU | 95.14 | 9.32 | 2.800 | 2699 MB |
| vivo V2502A | CPU | 165 | 9 | - | - |

### `Qwen3-0.6B.mediatek.mt6993.litertlm`

Context: 4096. This is a previously published MediaTek MT6993 NPU reference result; TTFT, peak footprint, and exact prompt/decode shape were not recorded in this update.

| Example device | Backend | Prefill (tok/s) | Decode (tok/s) | TTFT (s) | Peak Private Footprint |
|---|---|---:|---:|---:|---:|
| vivo V2502A | NPU | 1472 | 36 | - | - |

## Desktop Smoke Benchmark

Benchmarked on AMD Radeon AI PRO R9700 via LiteRT-LM WebGPU with 256 prefill tokens and 32 decode tokens.

| Backend | Prefill (tok/s) | Decode (tok/s) | TTFT (s) | Peak Private Footprint |
|---|---:|---:|---:|---:|
| GPU WebGPU | 4257.13 | 142.07 | 0.07 | 803 MB |

## Use the model

### Try It (Desktop/CLI)

Install [uv](https://docs.astral.sh/uv/getting-started/installation/) and run:

```bash
uv tool install litert-lm
uvx litert-lm run --from-huggingface-repo=litert-community/Qwen3-0.6B qwen3_0_6b_mixed_int4.litertlm --prompt="What is the capital of France?"
```

### Edge Gallery App
1. **Get the App**: Install the [app](https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery&pli=1) from Google Play or download the latest APK from the [GitHub releases page](https://github.com/google-ai-edge/gallery/releases).
2. **Importing the Model**: Navigate to the **Model manager** within the app and click the **"+" (plus)** icon in the bottom-right corner. Two options will appear:
   * **Import from HF (Recommended)**: Select this option, and a dialog box will appear showing an example Hugging Face model URL. Enter the HF link for the desired `.litertlm` model and click submit. The model will then appear in your list, and you can proceed to download it (a Hugging Face account login is required).
   * **From local model file**: First, download the `.litertlm` model directly to your Android device, OR download it to your computer and push it via ADB (e.g., `adb push Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm /sdcard/Download/`). Then, select this option, choose the downloaded file from your storage, configure your preferred parameters, and tap **"Import"**.
For full details on importing models and other features, see the [Edge Gallery App Wiki](https://github.com/google-ai-edge/gallery/wiki).
To build the demo app from source, please follow the [instructions](https://github.com/google-ai-edge/gallery/blob/main/README.md) from the GitHub repository.


## Integration

Ready to integrate this into your product? Get started in the [LiteRT-LM documentation](https://ai.google.dev/edge/litert-lm/overview).

### Citation

```
@misc{qwen3technicalreport,
      title={Qwen3 Technical Report},
      author={Qwen Team},
      year={2025},
      eprint={2505.09388},
      archivePrefix={arXiv},
      primaryClass={cs.CL},
      url={https://arxiv.org/abs/2505.09388},
}
```
