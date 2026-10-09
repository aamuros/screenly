# M4 offline model comparison — API 30 — 2026-10-10

Date / tester / commit / scope: 2026-10-10 (Asia/Manila; raw host timestamps are
2026-10-09 UTC), Codex for Developer 2. Source baseline
`6e0af6039177bfb7292abbd32feb0edc3160a2ec` on `test/m4-fixture-ui`, plus the
instrumentation-only changes described below. M4 remains **IN PROGRESS**.

## Decision and measured results

**No tested model/configuration qualifies for general Android control selection.**
All three produced **0/15 correct abstentions**. Keep Gemma as the working
experimental baseline: Qwen2.5 matched its poor semantic accuracy at higher cost,
and Qwen3 was faster but selected the wrong target even when Font size was present.
Do not promote any of these results to live overlay guidance.

| Model | Correct decisions | Correct next action | Correct abstention | Wrong target | Invalid | Runtime failure |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Gemma 3 1B INT4 | 5/20 | 5/5 | 0/15 | 15 | 0 | 0 |
| Qwen3 0.6B INT4 non-thinking | 0/20 | 0/5 | 0/15 | 20 | 0 | 0 |
| Qwen2.5 1.5B Instruct INT8 | 5/20 | 5/5 | 0/15 | 15 | 0 | 0 |

No model generated canonical `NONE`; every actual inference selected an allowed
index. There were zero incorrect abstentions and zero fallback uses. Each model
also had 5/5 correct preparation rejections on `blocked-targets`, which do not
count as model abstentions or correct decisions.

| Scenario / expected | Gemma, all five runs | Qwen3, all five runs | Qwen2.5, all five runs |
| --- | --- | --- | --- |
| Target available / Font size index 1 | `TAP:1\n` — correct | `TAP:2` — Display size, wrong | `TAP:1` — correct |
| Missing font target / NONE | `TAP:1\n` — Battery, wrong | `TAP:1` — Battery, wrong | `TAP:0` — Sound, wrong |
| Duplicate Continue / NONE | `TAP:1\n` — arbitrary Continue, wrong | `TAP:0` — arbitrary Continue, wrong | `TAP:0` — arbitrary Continue, wrong |
| Unavailable Wi-Fi / NONE | `TAP:1\n` — Sound, wrong | `TAP:1` — Sound, wrong | `TAP:1` — Sound, wrong |
| No eligible candidates / no inference | INPUT_REJECTED | INPUT_REJECTED | INPUT_REJECTED |

The table uses JSON-style `\n` to preserve Gemma's actual trailing newline;
trimming surrounding whitespace is the existing parser behavior. All selected
indices pass syntax/membership validation. Their semantic mistakes demonstrate
why a valid index cannot be accepted as sufficient evidence of navigation safety.

### Performance and memory

Each scored model has 20 timing/memory samples. Generation includes conversation
creation, native response generation and typed token diagnostics. Initialization
includes full model hashing and native initialization. Combined call time also
includes endpoint memory collection, but excludes engine closing. These are wall
clock timings with existing disk/runtime caches and variable host load, not TTFT,
token/sec, a sustained-session benchmark, or phone performance budgets. Probes
warm the alternative caches; Gemma had pre-existing caches. Model order was
Gemma, Qwen3, Qwen2.5 after the two probes. No cross-model speed significance is
claimed from this small sequential run.

| Model | Generate median (range), ms | Initialize median (range), ms | Combined median (range), ms | Post-generation PSS median / max, MiB | Process RSS high-water, MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Gemma 3 1B INT4 | 1361.5 (1295–2344) | 1594 (1446–2820) | 3032 (2809–5236) | 1078.9 / 1085.2 | 1189.9 |
| Qwen3 0.6B INT4 non-thinking | 447.5 (319–2162) | 1447 (723–4088) | 2294.5 (1060–6328) | 954.9 / 976.5 | 1081.5 |
| Qwen2.5 1.5B Instruct INT8 | 3027 (1951–10069) | 2040.5 (1187–6444) | 5038.5 (3399–16386) | 2038.1 / 2054.7 | 2105.8 |

PSS/RSS cover the whole instrumented application process, including framework,
model mappings, native graphs/caches and inference buffers. PSS is sampled before
load, after initialization and after generation; it is **not a continuously
measured peak**. RSS high-water is the kernel's process-lifetime maximum across
all repetitions, not a per-call or model-only peak. Private dirty/clean and RSS
endpoint values are retained per run. Baseline process memory can remain elevated
between engine closes; no unsupported subtraction is presented as model-only RAM.

Compatibility probes are excluded from timing/accuracy statistics but matter for
capacity planning: Qwen3's probe reached **1,058.4 MiB PSS / 1,164.1 MiB RSS
high-water**; Qwen2.5's probe reached **2,304.2 MiB PSS / 2,403.5 MiB RSS
high-water**. Thus the highest observed PSS including probes was about **1.06 GiB
Gemma, 1.03 GiB Qwen3, 2.25 GiB Qwen2.5**. These endpoint measurements cannot
establish the minimum RAM requirement or rule out larger transient peaks.
Qwen2.5 ran within this 3.84 GiB guest but leaves substantially less deployment
headroom, without improving decision accuracy.

### Independent rule baseline

| Evaluator | Correct next action | Correct abstention | Correct decisions | Wrong target / invalid |
| --- | ---: | ---: | ---: | ---: |
| `NavigationRules.select` (future RulePlanner helper) | 5/5 | 15/15 | 20/20 | 0 / 0 |

These identical rule results were independently recorded alongside every model
and did not influence generation, parsing or model scoring. There is still no
shared RulePlanner adapter. This is limited exact-label/alias behavior on four
simple fixtures, not proof of semantic multistep navigation; the historical
broader rule fixture evaluation includes unsupported menu routes.

### Recommendation

1. **Narrow the immediate demo to a curated fixture/manual walkthrough.** The
   tested direct Font size selection is demonstrated only for Gemma and Qwen2.5;
   arbitrary missing/ambiguous/unavailable screens remain unsupported. Keep
   deterministic rule behavior visibly separate from actual LLM output. Preserve
   Gemma's known working configuration rather than spending more RAM/latency for
   the same 25% decision accuracy.
2. **Evaluate validated task-specific hints as a separate experiment before any
   live guidance.** Constrain supported goals to documented Android menu routes,
   include only trustworthy task/state hints, and require the same missing,
   duplicate and unavailable-target abstention checks on held-out screens and
   reordered candidates. Do not supply fixture answers, loosen the parser, or
   relabel rule filtering as improved model accuracy. Hints have not been
   implemented or measured here and are not established as a fix.
3. **Investigate fine-tuning if abstention still fails after that bounded
   evaluation.** A task dataset must balance correct selections with explicit
   NONE examples, ambiguity, disabled/missing targets and already-satisfied states,
   with independent held-out validation. A fine-tuned checkpoint would need its
   own export, hash/license and 0.10.2/device compatibility checks. No training,
   runtime upgrade or further milestone implementation was performed.

There is no reliable model winner to deploy from this comparison. Physical
hardware checks, broader/held-out accuracy and agreed performance/quality budgets
remain open; unchanged M0 shared-contract gates and M5 scope still apply.

### Raw evidence

- Gemma: [instrumentation output](gemma-benchmark.txt), [25 raw records](gemma-benchmark.jsonl).
- Qwen3: [instrumentation output](qwen3-nothink-int4-benchmark.txt), [25 raw records](qwen3-nothink-int4-benchmark.jsonl), [separate compatibility probe](qwen3-nothink-int4-probe.jsonl).
- Qwen2.5: [instrumentation output](qwen25-instruct-int8-benchmark.txt), [25 raw records](qwen25-instruct-int8-benchmark.jsonl), [separate compatibility probe](qwen25-instruct-int8-probe.jsonl).
- [Machine-readable comparison](summary.json), regenerated with `python3 docs/verification/m4-model-comparison/summarize.py`.

All three benchmark invocations and both probes report **OK (1 test)**. In total,
75 benchmark evaluations comprise 60 scored LLM generations and 15 input
rejections; the two probes add two unscored generations. Raw records preserve
exact prompts, goal/allowed indices/expected result, unmodified response/text
parts/code points, role/channels/tool calls, token counts, strict outcome,
accepted index, independent rules, timings and memory. Only the stage label was
added when extracting instrumentation JSON. Historical runs are not mixed into
these fresh results. Full probe `.txt` outputs are also retained alongside JSONL.

## Environment and method

The existing **Screenly_M3_API30** AVD, `emulator-5554`, Android 11/API 30,
`arm64-v8a`, was reused without wiping its data or replacing the runtime.
The guest reports 4,021,996 KiB total RAM (3.84 GiB), no swap.
[Environment and APK identities](environment.json) record exact APK bytes/hashes,
model hashes, starting available memory, storage, AVD/API/ABI and source commit.
These are fresh emulator measurements; no physical-phone performance or acceptance
is established.

The unchanged fixtures are `display-font`, `missing-font`, `ambiguous`,
`wifi-unavailable`, and `blocked-targets`. Each model receives **five repetitions
per fixture**: 20 actual LLM decisions plus five rejected empty-candidate inputs.
The latter must never enter JNI and are excluded from LLM accuracy/abstention
counts. One extra font-size compatibility probe per Qwen model is retained
separately and excluded from benchmark statistics. This task evaluates the same
four decision scenarios as the previous 5/20 Gemma investigation; it does not
claim coverage of all 22 fixtures or real accessibility hierarchies.

The existing prompt builder, JSON payload, original indices, strict parser,
allowed-target validation and fixture expectations are unchanged. Each model
receives byte-identical application prompts for the same fixture; the summarizer
asserts equality across every recorded call. Expected answers are never placed
in the prompt. Disabled Wi-Fi is excluded from allowed candidates exactly as in
the existing evaluation. No case normalization, response repair, semantic rule
filter, retry or rule substitution is applied. `NONE` means abstention, never
completion. Rule results are recorded independently.

Every repetition verifies the pinned model, initializes a fresh engine, uses a
fresh conversation, then closes both. All engines use **LiteRT-LM 0.10.2,
CPU/four threads, 1,024 total input/output tokens**, no system instruction,
no tools or automatic tool calling, and the existing null sampler configuration.
Model/runtime sampling defaults and seeds are not explicitly standardized;
these results compare the current default configurations, not controlled sampling
experiments. Embedded model-specific chat templates/tokenizers necessarily differ.
Qwen3's selected artifact prefills a closed empty thinking block; the application
adds no special Qwen instruction and strips no generated text or channels.

Airplane/Wi-Fi/mobile-data settings were **1/0/0**, with no active default network.
The instrumentation asserts settings and `activeNetwork == null` before and after
each repetition. [Before](offline-before.txt) and [after](offline-after.txt)
network dumps preserve the offline condition. Accessibility was temporarily
disabled for isolated benchmarking; original settings **0/1/1** and Screenly
service enablement/binding were restored afterward. See
[original settings](original-settings.json), [restored settings](restored-settings.json)
and [restored service state](restored-accessibility.txt). Restoration is not an
accessibility/overlay UI regression test.

## Artifact selection, licenses and compatibility

[Selected artifact manifest](selected-artifacts.json) records exact source URLs,
immutable repository revisions, filenames, quantization, sizes, SHA-256 hashes,
licenses and private device paths. Published LFS hashes were checked against
both downloaded host bytes and provisioned device bytes. The unchanged Gemma
private file was also hashed. No model bytes are committed or bundled in the APK.

| Candidate | Exact published artifact | Quantization | Bytes / MiB | Source revision |
| --- | --- | --- | ---: | --- |
| Existing Gemma 3 1B IT | `gemma3-1b-it-int4.litertlm` | INT4 | 584,417,280 / 557.34 | `a6306a4e292016480083b73b8dc6f3f939ae04c3` |
| Qwen3 0.6B, non-thinking | `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm` | Dynamic INT4, block 32, FP32 activations | 347,251,840 / 331.17 | `6aa2daf8aba4aa456797fb8040b36a3948bcfda7` |
| Qwen2.5 1.5B Instruct | `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` | Dynamic INT8 | 1,597,931,520 / 1,523.91 | `19edb84c69a0212f29a6ef17ba0d6f278b6a1614` |

Qwen2.5 was requested conditionally as a **quantized** compatible artifact;
this INT8 file satisfies that condition. The reviewed publisher inventory offers
FP32 and INT8 `.litertlm` files, not an INT4 `.litertlm` export. The 6.18 GB FP32
file was not attempted on this RAM-limited guest; `.task`/`.tflite` files were
not renamed or substituted. The compatible INT8 artifact successfully completed
the probe, so no format/runtime/memory blocker prevented its benchmark.

Gemma retains its existing gated [Gemma terms](https://ai.google.dev/gemma/terms)
and previously provisioned/accepted artifact; no new gated download or license
acceptance was attempted. Qwen3 and Qwen2.5 publisher cards identify Apache 2.0,
confirmed against pinned upstream [Qwen3 license](qwen3-LICENSE.txt) and
[Qwen2.5 license](qwen25-LICENSE.txt), with upstream revisions retained in their
metadata files. Distribution must retain the applicable license/notice obligations.

Availability and conversion claims are preserved in publisher
[Qwen3 INT4 metadata](qwen3-int4-metadata.json)/[card](qwen3-int4-card.md),
[Qwen2.5 metadata](qwen25-metadata.json)/[card](qwen25-card.md), and
[Gemma metadata](gemma-metadata.json). The separate generic Qwen3
[card](qwen3-card.md) describes newer exports tested with runtime 0.13.1/0.17.1;
those statements were not treated as proof of 0.10.2 compatibility, and those
artifacts were not substituted for the selected non-thinking INT4 file.

The exact Qwen artifacts have `LITERTLM` container headers reporting version
1.5.0; [host integrity records](artifact-integrity.json) preserve the header bytes.
Neither selected Qwen card gives a minimum runtime version. Extension, ABI and
header inspection alone were insufficient: separate offline Android probes
successfully initialized **these exact binaries on the pinned 0.10.2 CPU runtime**
and returned real model text before repeated benchmarking. The versioned
[runtime guide](runtime-kotlin-guide.md) supports the API usage, not a compatibility
guarantee for arbitrary artifacts. No production runtime, Gradle or compiler
upgrade was needed. Compatibility is established only for this tested API 30
ARM64 configuration, not API 35, another export, another backend, or a phone.

## Verification and provenance

Application and test APK builds passed:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --quiet
./gradlew :app:testDebugUnitTest --tests 'com.screenly.app.ai.navigation.*' --console=plain --quiet
```

Fresh targeted host results: **21 tests, 0 failures/errors/skips** (Protocol 9,
Rules 6, LabRunner 6). [Host check record](host-checks.json) records commands/results.
No full suite, browser, screenshots, full lint or physical tests ran. An initial
host Python TLS trust-store failure was corrected by using system curl with TLS
verification enabled. An early Qwen2.5 device hash read preceded completion of its
copy; final device hashes verify both completed files. Neither setup issue was
counted as a model runtime failure.

The opt-in instrumentation command for each model is:

```sh
adb -s emulator-5554 shell am force-stop com.screenly.app
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.screenly.app.ai.navigation.NavigationLabInferenceTest \
  -e navigationLab true -e comparisonModel MODEL -e recordFailures true \
  -e repetitions 5 \
  -e fixtureIds display-font,missing-font,ambiguous,wifi-unavailable,blocked-targets \
  com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
```

`MODEL` is `gemma`, `qwen3-nothink-int4`, or `qwen25-instruct-int8`.
Probes use `-e repetitions 1 -e fixtureIds display-font`.
[Exact device commands and setup/restoration outputs](commands.txt) are retained.
Instrumentation PASS means calls executed, offline/provenance checks passed,
and parsed selections were eligible; it does **not** assert semantic accuracy.
`recordFailures=true` allows diagnosed model failures to be recorded without
substituting rules; unexpected exceptions/native crashes still fail instrumentation.

Only two instrumentation source files changed: `NavigationLabInferenceTest.kt`
adds explicit experimental model selection, failure recording and memory samples;
`ComparisonInference.kt` holds pinned alternatives and isolated cache/model paths.
Gemma still delegates to the existing `LocalInference`. Alternatives exist only
in `androidTest`, under `no_backup/experimental-models` and per-model test caches;
the debug lab and production `LocalModel` remain Gemma. No Developer 1 source,
shared contract, production inference/prompt/parser, Gradle or runtime change,
branch merge, live-overlay integration, M5 work or milestone promotion occurred.
