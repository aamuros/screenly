---
license: apache-2.0
base_model: Qwen/Qwen2.5-1.5B-Instruct
pipeline_tag: text-generation
library_name: litert-lm
tags:
- chat
---

# litert-community/Qwen2.5-1.5B-Instruct

This model provides a few variants of
[Qwen/Qwen2.5-1.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct) that are ready for
deployment on Android using the
[LiteRT (fka TFLite) stack](https://ai.google.dev/edge/litert),
[MediaPipe LLM Inference API](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) and
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM).

## Use the models

### Colab

*Disclaimer: The target deployment surface for the LiteRT models is
Android/iOS/Web and the stack has been optimized for performance on these
targets. Trying out the system in Colab is an easier way to familiarize yourself
with the LiteRT stack, with the caveat that the performance (memory and latency)
on Colab could be much worse than on a local device.*

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/#fileId=https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/blob/main/notebook.ipynb)

### Android

#### Edge Gallery App
*   Download or build the [app](https://github.com/google-ai-edge/gallery?tab=readme-ov-file#-get-started-in-minutes) from GitHub.

*   Install the [app](https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery&pli=1) from Google Play.

*   Follow the instructions in the app.

#### LLM Inference API

*   Download and install
    [the apk](https://github.com/google-ai-edge/gallery/releases/latest/download/ai-edge-gallery.apk).
*   Follow the instructions in the app.

To build the demo app from source, please follow the [instructions](https://github.com/google-ai-edge/gallery/blob/main/README.md)
from the GitHub repository.

### iOS

*   Clone the [MediaPipe samples](https://github.com/google-ai-edge/mediapipe-samples)
    repository and follow the [instructions](https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/llm_inference/ios/README.md)
    to build the LLM Inference iOS Sample App using XCode.
*   Run the app via the iOS simulator or deploy to an iOS device.

## Performance

### Android

Note that all benchmark stats are from a Samsung S25 Ultra and multiple prefill signatures enabled.

<table border="1">
  <tr>
   <th style="text-align: left">Backend</th>
   <th style="text-align: left">Quantization scheme</th>
   <th style="text-align: left">Context length</th>
   <th style="text-align: left">Prefill (tokens/sec)</th>
   <th style="text-align: left">Decode (tokens/sec)</th>
   <th style="text-align: left">Time-to-first-token (sec)</th>
   <th style="text-align: left">Model size (MB)</th>
   <th style="text-align: left">Peak RSS Memory (MB)</th>
   <th style="text-align: left">GPU Memory (RSS in MB)</th>
   <th></th>
  </tr>
  <tr>
<td><p style="text-align: left">CPU</p></td>
<td><p style="text-align: left">fp32 (baseline)</p></td>
<td><p style="text-align: right">1280</p></td>
<td><p style="text-align: right">49.50</p></td>
<td><p style="text-align: right">10 tk/s</p></td>
<td><p style="text-align: right">21.25 s</p></td>
<td><p style="text-align: right">6182 MB</p></td>
<td><p style="text-align: right">6254 MB</p></td>
<td><p style="text-align: right">N/A</p></td>
<td><p style="text-align: left"><a style="text-decoration: none" href="https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_f32_ekv1280.task">&#128279;</a></p></td>
</tr>
<tr>
<td><p style="text-align: left">CPU</p></td>
<td><p style="text-align: left">dynamic_int8</p></td>
<td><p style="text-align: right">1280</p></td>
<td><p style="text-align: right">297.58</p></td>
<td><p style="text-align: right">34.25 tk/s</p></td>
<td><p style="text-align: right">3.71 s</p></td>
<td><p style="text-align: right">1598 MB</p></td>
<td><p style="text-align: right">1997 MB</p></td>
<td><p style="text-align: right">N/A</p></td>
<td><p style="text-align: left"><a style="text-decoration: none" href="https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task">&#128279;</a></p></td>
</tr>
<tr>
<td><p style="text-align: left">CPU</p></td>
<td><p style="text-align: left">dynamic_int8</p></td>
<td><p style="text-align: right">4096</p></td>
<td><p style="text-align: right">162.72 tk/s</p></td>
<td><p style="text-align: right">26.06 tk/s</p></td>
<td><p style="text-align: right">6.57 s</p></td>
<td><p style="text-align: right">1598 MB</p></td>
<td><p style="text-align: right">2216 MB</p></td>
<td><p style="text-align: right">N/A</p></td>
<td><p style="text-align: left"><a style="text-decoration: none" href="https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task">&#128279;</a></p></td>
</tr>
<tr>
<td><p style="text-align: left">GPU</p></td>
<td><p style="text-align: left">dynamic_int8</p></td>
<td><p style="text-align: right">1280</p></td>
<td><p style="text-align: right">1667.75 tk/s</p></td>
<td><p style="text-align: right">30.88 tk/s</p></td>
<td><p style="text-align: right">3.63 s</p></td>
<td><p style="text-align: right">1598 MB</p></td>
<td><p style="text-align: right">1846 MB</p></td>
<td><p style="text-align: right">1505 MB</p></td>
<td><p style="text-align: left"><a style="text-decoration: none" href="https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task">&#128279;</a></p></td>
</tr>
<tr>
<td><p style="text-align: left">GPU</p></td>
<td><p style="text-align: left">dynamic_int8</p></td>
<td><p style="text-align: right">4096</p></td>
<td><p style="text-align: right">933.45 tk/s</p></td>
<td><p style="text-align: right">27.30 tk/s</p></td>
<td><p style="text-align: right">4.77 s</p></td>
<td><p style="text-align: right">1598 MB</p></td>
<td><p style="text-align: right">1869 MB</p></td>
<td><p style="text-align: right">1505 MB</p></td>
<td><p style="text-align: left"><a style="text-decoration: none" href="https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task">&#128279;</a></p></td>
</tr>

</table>

*   For the list of supported quantization schemes see [supported-schemes](https://github.com/google-ai-edge/ai-edge-torch/tree/main/ai_edge_torch/generative/quantize#supported-schemes).
    For these models, we are using prefill signature lengths of 32, 128, 512 and 1280.
*   Model Size: measured by the size of the .tflite flatbuffer (serialization
    format for LiteRT models)
*   Memory: indicator of peak RAM usage
*   The inference on CPU is accelerated via the LiteRT
    [XNNPACK](https://github.com/google/XNNPACK) delegate with 4 threads
*   Benchmark is run with cache enabled and initialized. During the first run,
    the time to first token may differ.

