---
license: apache-2.0
base_model: Qwen/Qwen3-0.6B
base_model_relation: quantized
library_name: litert-lm
pipeline_tag: text-generation
tags:
  - litert-lm
  - litertlm
  - on-device
  - edge
  - android
  - int4
  - quantized
  - qwen3
language:
  - en
  - zh
---

# Qwen3-0.6B — INT4 · LiteRT-LM

On-device **INT4** builds of [Qwen/Qwen3-0.6B](https://huggingface.co/Qwen/Qwen3-0.6B)
in the **`.litertlm`** format for Google's
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) runtime
(Android / iOS / desktop / embedded).

At ~332 MB each (≈7× smaller than the fp32 source), these load fast and keep a
small memory footprint on phones.

## Two flavours — pick your thinking behaviour

Qwen3 is a hybrid reasoning model. Because LiteRT-LM bakes the prompt format
into the file, the thinking mode is fixed per file, so this repo ships **both**:

| File | Thinking | Best for |
|------|----------|----------|
| `qwen3_0.6b_q4_block32_ekv1280.litertlm` | **ON** (default Qwen3) | quality / reasoning; emits `<think>…</think>` then the answer |
| `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm` | **OFF** | fast, short, direct answers — ~2× quicker on a CPU backend; no chain-of-thought to filter out |

- The **standard** file embeds Qwen3's Jinja chat template (faithful ChatML; if
  your runtime can pass `enable_thinking`, it stays controllable).
- The **no-think** file prefills a closed empty `<think></think>` into the
  assistant turn, so the model answers directly. This was built for a wearable
  health-assistant where a phone streams short replies to a watch.

## Build details

Converted with [`litert-torch`](https://github.com/google-ai-edge/litert-torch)
(the generative API, formerly `ai-edge-torch`) from the original PyTorch checkpoint.

| Setting | Value |
|---------|-------|
| Source checkpoint | `Qwen/Qwen3-0.6B` (bf16) |
| Quantization | `dynamic_int4_block32` (INT4 weights, block size 32, fp32 activations) |
| Size | ~2.2 GiB → **329 MiB** (≈6.9× smaller) |
| Prefill signatures | `8, 64, 128, 256, 512, 1024` (runtime picks the smallest that fits) |
| KV cache max length | `1280` |
| KV layout | transposed · `mask_as_input=true` |
| Stop tokens | `151645` (`<|im_end|>`), `151643` (`<|endoftext|>`) |
| Tokenizer | bundled from `tokenizer.json` |

> Multiple prefill signatures matter for latency: with a single large signature
> every prompt is padded to it. The exponential set above lets a short prompt run
> a tiny prefill instead of always paying the 1024-token cost.

### Prompt format

Standard Qwen3 ChatML. The **no-think** file pre-closes thinking on the
assistant side:

```
<|im_start|>user
{user message}<|im_end|>
<|im_start|>assistant
<think>

</think>

{answer}<|im_end|>
```

## Usage (LiteRT-LM, Android / Kotlin)

```kotlin
val engine = Engine(
    EngineConfig(
        modelPath = "/path/to/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        backend = Backend.CPU(),
        cacheDir = context.cacheDir.absolutePath,
    )
).apply { initialize() }

val conversation = engine.createConversation(
    ConversationConfig(
        systemInstruction = Contents.of("You are a helpful, concise assistant."),
        samplerConfig = SamplerConfig(temperature = 0.8, topK = 40, topP = 0.9),
    )
)

conversation.sendMessageAsync("How did I sleep last night?")
    .collect { msg -> /* stream text */ }
```

Stop tokens and the prompt format are embedded in each file — no extra runtime
configuration required. With the standard (thinking-on) file you may want to
strip the leading `<think>…</think>` block before display.

## Reproduce

```python
import json
from litert_torch.generative.examples.qwen import qwen3
from litert_torch.generative.utilities import converter, export_config as ec_lib
from litert_torch.generative.layers import kv_cache as kv_utils

CKPT = "Qwen3-0.6B"  # local HF snapshot
ec = ec_lib.ExportConfig()
ec.kvcache_layout = kv_utils.KV_LAYOUT_TRANSPOSED
ec.mask_as_input = True

common = dict(
    output_path=".",
    prefill_seq_len=[8, 64, 128, 256, 512, 1024],
    kv_cache_max_len=1280,
    quantize="dynamic_int4_block32",
    export_config=ec,
    output_format="litertlm",
    hf_tokenizer_model_path=f"{CKPT}/tokenizer.json",
    stop_token_ids=[151645, 151643],
)

# Standard (thinking on): bake the Qwen3 Jinja template
tpl = json.load(open(f"{CKPT}/tokenizer_config.json"))["chat_template"]
converter.convert_to_litert(
    qwen3.build_0_6b_model(CKPT), output_name_prefix="qwen3_0.6b",
    jinja_prompt_template=tpl, **common)

# No-think: prefill a closed empty think block in the assistant turn
converter.convert_to_litert(
    qwen3.build_0_6b_model(CKPT), output_name_prefix="qwen3_0.6b_nothink",
    user_prompt_prefix="<|im_start|>user\n", user_prompt_suffix="<|im_end|>\n",
    model_prompt_prefix="<|im_start|>assistant\n<think>\n\n</think>\n\n",
    model_prompt_suffix="<|im_end|>\n", **common)
```

## Training data, provenance & privacy

This is a **format/precision conversion only** — no fine-tuning or additional
training was performed. Weights and behaviour are inherited directly from
[`Qwen/Qwen3-0.6B`](https://huggingface.co/Qwen/Qwen3-0.6B); see the
[Qwen3 technical report](https://arxiv.org/abs/2505.09388) for that model's
pretraining data.

No new training data was introduced by this contribution, so there is **no
additional dataset to summarise**. Because no data was collected or used here,
there is **no PII involved in this submission** — the conversion operates purely
on the public base-model weights and tokenizer.

## Intended use & limitations

- **Use:** offline assistants on phones / edge devices — chat, summaries, simple
  Q&A over provided context.
- **No-think file:** not for complex multi-step reasoning or math (thinking is
  off). Use the standard file or a larger Qwen3 for that.
- **Quantization:** INT4 introduces some quality loss vs the fp32/INT8 source.
- Inherits the capabilities, biases, and limitations of `Qwen/Qwen3-0.6B`.

## License

Apache 2.0, inherited from [Qwen/Qwen3-0.6B](https://huggingface.co/Qwen/Qwen3-0.6B).

## Acknowledgements

- [Qwen team](https://huggingface.co/Qwen) for Qwen3-0.6B
- [Google AI Edge](https://github.com/google-ai-edge) for LiteRT-LM and litert-torch
