# Standalone local navigation backend

Developer 2, 2026-10-10 (Asia/Manila), `feat/local-ai`, base commit `f7442d6` plus the
working changes identified by source/APK hashes in the linked evaluation environment files.
M4 remains IN PROGRESS. Shared Planner contracts, Android integration and physical acceptance
remain separate. This backend produces proposed indices only; it never taps, draws, or accesses
accessibility nodes.

## Implementation and supported scope

`ai/navigation/NavigationEngine.kt` accepts a goal, sanitized `AccessibleUiElement` values and
permitted **original** indices. It copies both lists before suspension and performs preparation
and validation on Default. A suspend function supplies local generation through the unchanged
`LocalInference` implementation. The existing runtime verifies the pinned private model,
initializes/generates/closes on IO, serializes native calls, reuses its engine, and closes each
conversation. The owner must close the engine in `finally`; cancellation cannot interrupt JNI.
No model/runtime/configuration, manifest, Developer 1 source, shared contract, module or
dependency was changed. Gradle only shares test fixture Kotlin sources between JVM/instrumentation.

The bounded prompt contains goal, optional screen context and candidate rows
`[originalIndex,[text,description],classSuffix,checked]`. Both distinct labels are retained.
All strings are escaped data. Literal allowed TAP replies and NONE are enumerated. Parsing
still accepts only canonical ASCII `TAP:<index>` or uppercase `NONE`, with surrounding
whitespace, a 32-unit response limit and checked Int conversion. No response repair is performed.

Structural input limits: nonblank goal <=160 UTF-16 units; <=500 elements; present string fields
<=160 units with valid surrogate pairs; distinct, in-range allowed indices with enabled/clickable
state and positive bounds. Prompt limits remain <=8 candidates, <=48 units per candidate label,
<=32-unit class suffix, <=1,000 units total. Reject oversized candidate information rather than
truncate it. Optional context includes at most two short nonclickable labels. These are character
limits, **not token counts**. Viewport eligibility remains the publisher's responsibility.

Every model/rule selection passes deterministic checks for allowed membership, labels,
ambiguity, conflicting exact matches and toggle state. Equal text can be distinguished by a
description only when the goal identifies that description. Enable/disable of a direct target
requires a known toggle class and an unsatisfied checked state. NONE never means completion.

The initial real evaluation exposed unrelated legal selections. The final validator therefore
requires an exact normalized goal/label match (including the existing small alias set), or a
unique explicit intermediate route for font size/dark mode through Display, Wi-Fi through
Network/Wi-Fi, and hotspot through Network/Hotspot & tethering. Multiple plausible routes or
an unavailable exact target abstain. Unsupported paraphrases/routes fail closed. This is a
deliberately limited vocabulary, **not a general semantic verifier or proof of a real app's
hierarchy**. The deterministic rule baseline still selects only exact targets, not intermediate
routes. Route expansion requires new fixtures and target-app evidence.

On prompt rejection, malformed/invalid output, NONE or `LocalInferenceException`, the engine
attempts rules once on the same captured input. Invalid structural inputs skip both generation
and fallback. Cancellation and programming errors propagate. Diagnostics retain raw output,
parsed index, validated model index, rejection, final source, fallback use and timing separately;
no rule success is counted as a model success. These diagnostics must not become UI instructions.

## Deterministic verification

Final focused build/test command:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  --tests 'com.screenly.app.ai.navigation.*' \
  --tests com.screenly.app.ai.LocalInferenceTest \
  :app:assembleDebugAndroidTest --console=plain --quiet
```

PASS: app and instrumentation APKs; **41 tests, zero failures/errors/skips**: Protocol 11,
Rules 10, Engine 11, LocalInference 9. No JNI/model is needed for these host tests.
Coverage includes strict syntax/indices, both labels, malformed inputs, prompt limits,
duplicate labels/routes, unavailable targets, state safeguards, fallback provenance, captured
lists, concurrent request isolation, cancellation and propagated programming errors.

Two development failures were corrected: AGP's built-in Kotlin source sets require `.kotlin`
registration for the shared fixtures; coroutine stack recovery can copy exceptions, so the
cancellation test checks type/message rather than reference identity. Final commands passed.
No full suite, lint, UI/E2E or physical tests were run.

## Real-model method and evidence

Optional command after the APK build above:

```sh
python3 scripts/evaluate-navigation.py --serial emulator-5554 --repetitions 3 \
  --output /tmp/screenly-navigation-evaluation
```

Requires an empty output directory, existing `Screenly_M3_API30` and the privately provisioned
Gemma model. The runner verifies the AVD/API, installs with `-r`, isolates accessibility,
enables airplane mode, disables Wi-Fi/data, waits for no default network, and restarts the app.
Instrumentation independently checks settings and active network throughout. Original settings
are restored in `finally`. This uses no browser, screenshots, overlay actions or target-app taps.

The underlying opt-in instrumentation is `com.screenly.app.ai.navigation.NavigationEvaluationTest`
with `-e navigationEvaluation true -e repetitions 3`. Adding `-e prepareOnly true` records fixtures
and exact prompts without loading the model. Raw records also remain in app-private
`cache/navigation-evaluation.jsonl`. The runner preserves them plus summary, commands, APK/source
hashes, network evidence and restoration results.

All fixture expectations and exact prompts are emitted before initialization. Repetitions use
fresh conversations on one initialized engine. Rules run independently; the combined pipeline
uses the **same** model output and records fallback. Raw model correctness requires canonical
syntax and a ground-truth match. Validated model correctness additionally requires acceptance;
rejected bad model outputs are not credited as correct model abstentions. Combined correctness
includes safe inability when abstention is expected, including preparation rejection. Denominators
exclude preparation rejection from model-call accuracy and retain it in backend/rule accuracy.

The first run used 32 synthetic fixtures, three repetitions (96 backend decisions, 84 native
generations). It passed execution checks but exposed **9 wrong accepted selections**: Sound or
Battery for missing, disabled or off-screen targets. Model correctness was 42/84; rules 81/96;
combined 87/96; 33 target rejections, no malformed responses/runtime failures. Initialization
was 2,474 ms; generation median 1,303 ms, range 393–2,436 ms. These failures motivated the
limited route guard above. [Initial evidence](verification/m4-standalone-2026-10-10/summary.json)
and [raw records](verification/m4-standalone-2026-10-10/raw.jsonl) are preserved.

The final run adds seven variations frozen before its model calls: reordered routes, missing
brightness, duplicate routes, unavailable targets despite a menu, and unsupported paraphrases.
There are 39 fixtures. The original cases informed the guard; this is a development evaluation,
not an independent benchmark.

### Final measured results

Environment: macOS/Temurin JDK 21.0.9; `Screenly_M3_API30`, emulator-5554, Google
sdk_gphone_arm64, Android 11/API 30, arm64-v8a. Gemma 3 1B IT INT4, SHA-256
`1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`, 584,417,280 bytes;
LiteRT-LM 0.10.2 CPU/four threads, 1,024 total tokens and unchanged sampler defaults.
The existing model/cache were retained; initialization is not a controlled cold-storage sample.
Source/APK identities are in [environment.json](verification/m4-standalone-final-2026-10-10/environment.json).

Instrumentation: **OK (1 test), 176.566 seconds**. Three repetitions of 39 fixtures give
117 backend decisions, 105 generated responses and 12 preparation rejections. An additional
four generation requests exercise concurrency, cancellation and reuse; they are excluded
from navigation quality/latency statistics. Full instrumentation, per-fixture goals/elements/
expectations/prompts, raw responses, validation/fallback, timings and outcomes are retained in
[raw.jsonl](verification/m4-standalone-final-2026-10-10/raw.jsonl) and
[summary.json](verification/m4-standalone-final-2026-10-10/summary.json).

| Check | Actual result / interpretation |
| --- | --- |
| Rules alone | 93/117 correct decisions (79.5%); no wrong selected targets |
| Raw model, before semantic validation | 48/105 correct decisions (45.7%); no canonical NONE responses |
| Model after validation, no fallback | 45/105 accepted correct decisions (42.9%); 60 rejected targets, zero wrong accepted targets |
| Combined backend | 108/117 correct decisions (92.3%); zero wrong selected targets; 9 incorrect abstentions |
| Invalid output rate | 0/105 malformed responses; 60/105 target validation rejections (57.1%); structural syntax success does not imply semantic success |
| Fallback frequency | 72/117 attempts (61.5%), including 12 preparation rejections; 18 rule selections (15.4%), all correct |
| Runtime failures | 0/105 evaluated calls; concurrent requests, cancelled work followed by reuse, repeated initialize and idempotent close PASS |
| Initialization | 2,238 ms, one sample including full integrity verification |
| Generation | Median 1,308 ms; range 1,222–2,510 ms; n=105; conversation creation and IO scheduling included |
| Independent rule time | Median 342 microseconds; range 88–3,495 microseconds; n=117 |
| Memory | Maximum observed endpoint PSS 1,108,431 KiB (~1.06 GiB); includes app, instrumentation and model; not isolated model memory or a continuous peak measurement |

All three runs of each fixture produced the same recorded output/outcome. This demonstrates
repeatability for these inputs/configuration, not deterministic sampling or broad reliability.
No token counts, tokens/second, thermal behavior or physical measurements were collected.
Runtime cancellation checks establish discarded results and subsequent reuse; they do not
establish interruption of an active native call. IO dispatch is source-verified; UI frame
responsiveness was not measured by this isolated harness.

The nine misses are three repeats each of: “Make the screen less bright,” “Make the screen dark,”
and a reordered Font size route. The model's dark-mode selection is actually correct but the
limited validator rejects that unsupported paraphrase; this accounts for the difference between
raw and validated accuracy. For the other two it chooses an unrelated element, rejected safely.
The reordered route has a valid Display candidate but exact-only rules abstain after rejection.
Supporting these cases requires explicit policy/coverage work rather than relaxing validation.

Offline settings were 1/0/0 with **no active default network** before/after the restarted
process and during evaluation. Original connectivity 0/1/1 and accessibility settings were
restored. Android retained a pending service binding after instrumentation. Toggling the service
and launching Screenly's own activity did not clear it; rebooting the dedicated emulator did.
The [final dump](verification/m4-standalone-final-2026-10-10/restored-accessibility-after-reboot.txt)
shows bound Screenly with empty binding/crashed entries, and
[settings after reboot](verification/m4-standalone-final-2026-10-10/restored-settings-after-reboot.json)
match the originals. Earlier pending-binding dumps are retained. The runner restores settings;
this API 30 test environment may also require `adb -s emulator-5554 reboot` afterward if service
binding remains pending. This is environment restoration, not a UI regression test.
[Host checks](verification/m4-standalone-final-2026-10-10/host-checks.json) retain the
exact command, unit report timestamps and unchanged Developer 1/native-runtime paths.

## Developer 1 integration after shared contracts

Keep one `LocalInference` and `NavigationEngine` per owning session. Bind generation with
`NavigationEngine { prompt -> inference.initialize(); inference.generate(prompt) }` so missing
or corrupt model initialization can reach the existing local fallback. Initialization is
idempotent; close in the session owner's `finally`, not after every decision. The evaluator
initializes separately so reported generation time excludes loading; callback timing includes
initialization if the lazy binding above initializes during its first call.

The eventual adapter passes copied sanitized elements plus publisher-approved original indices
and attaches the trusted captured snapshot key to its shared result. Null selection maps to
Unable with a bounded resource-mapped reason. It never maps NONE to Complete. Developer 1 owns
session/revision/request/goal validation, cancellation, fresh observation and viewport/bounds
revalidation before highlighting. The backend cannot detect screen changes during inference.

Ready for controlled adapter development once contracts are agreed; broad natural-language
navigation, actual accessibility snapshots, physical compatibility, live freshness rejection,
UI responsiveness under load and end-to-end manual navigation remain unverified. The API 35
native compatibility failure remains unresolved. Do not present the limited vocabulary or
synthetic results as reliable navigation across arbitrary Android apps.
