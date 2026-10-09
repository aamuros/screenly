# Offline multimodal navigation — 2026-10-10

Status: **IMPLEMENTED — UNVERIFIED** for complete product acceptance. Native image compatibility is physically verified; general navigation quality is not. This report describes `feat/offline-multimodal-navigation`, not a release on `main`.

## Starting point and scope

Inspected the branch tips and their working-tree state before editing: `integration/reliable-guidance` (`fc865d9`, clean existing `.integration-worktree`), `integration/local-ai-android-guidance` (`6d423d7`, initially clean root), `feat/local-ai` (`dd87a44`), and `origin/feat/android-guidance` (`c76ff7c`). The reliable branch already connected real Gemma inference, immutable accessibility snapshots, revision validation and the native overlay. The newer merge contained screenshot/UI work but did not fully connect inference. The feature branch starts at the reliable tip and selectively extends its native assistant UI. Existing integration branches, their worktrees and `main` were preserved.

## Runtime and model compatibility gate

| Property | Verified configuration |
| --- | --- |
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.10.2` |
| Model | Official `google/gemma-3n-E2B-it-litert-lm`, `gemma-3n-E2B-it-int4.litertlm` |
| Repository revision | `c03b6f60b8da6c5400b6838a2cf26420f80c0a01` |
| Bytes | `3655827456` |
| SHA-256 | `2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6` |
| Native configuration | CPU, four threads; GPU vision encoder; 4096-token context; one image |
| Input API | `Contents.of(Content.ImageBytes(encodedPng), Content.Text(prompt))` |
| Device | Infinix X6837 / HOT 40 Pro; Android 13, API 33; `arm64-v8a` |
| Android library inspection | AAR minimum API 23; ARM64 and x86_64 native libraries. Screenly's image adapter requires API 30+ and ARM64. |
| License/download | Gated Gemma license acceptance and authenticated Hugging Face download; user confirmed authentication. No credential is committed. |

Sources: [version-specific Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.10.2/docs/api/kotlin/getting_started.md), [official artifact](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/tree/c03b6f60b8da6c5400b6838a2cf26420f80c0a01), [artifact metadata](https://huggingface.co/api/models/google/gemma-3n-E2B-it-litert-lm?blobs=true), [Gemma terms](https://ai.google.dev/gemma/terms).

Physical native test: [image-gate.txt](verification/offline-multimodal/image-gate.txt). The same prompt with separately generated red and blue PNG images produced `RED` and `BLUE`. The test also checked initialized-engine reuse, unload/reinitialization, text input and idempotent close. This proves genuine image inference for this artifact/runtime/device combination, rather than compilation alone.

Measured `/proc/meminfo`: total `7869828` KiB (~7.5 GiB), initial available `3670460` KiB (~3.5 GiB). Cold initialization including integrity verification took 26.744 seconds; repeated initialization took 0 ms. Red/blue inference took 25.336/25.212 seconds. Observed process PSS was 3,214,065/2,993,266 KiB. These are samples, not peak-memory guarantees. The device had enough available RAM for the tested calls, but memory pressure and long-session behavior remain unverified. The implementation unloads one engine before using the other.

Gemma 3 1B INT4 remains a separate, real text fallback: `584417280` bytes, SHA-256 `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`. Its existing file was preserved. Physical text smoke initialization/reuse/cleanup passed; free-form generations took roughly 44 seconds and contained semantic errors. Compact action choices reduced warmed fixture decisions to about four seconds, without converting them to rule-based routing.

## Current implementation

`LocalMultimodalInference` isolates availability, initialization, optional encoded image input, failures, unloading and cleanup. Production vision requires the exact pinned model plus a native verification certificate tied to model hash, runtime, device build fingerprint and configuration. The opt-in image test writes that certificate only after all checks pass. Importing new vision weights removes it. Other phones therefore keep real text inference until their native gate passes; importing a file alone does not claim native compatibility.

`ScreenPlanner` is the shared pipeline for Guide Me, Ask Screeny and Explain Screen. It gives AI the goal/question, current app, copied accessibility hierarchy/control capabilities and bounded suggestion history, plus a screenshot when allowed. Gemma 3n returns a strictly parsed action tuple. Gemma 1B chooses a strictly parsed ordinal from the current screen's supported actions. These choices are generated from control capabilities, without goal-specific routes. Supported outcomes are `NAVIGATE`, `ADJUST`, `TOGGLE`, `SCROLL`, `DONE` and `UNCERTAIN`. Model explanations are bounded; text action instructions use localized presentation strings.

Grounding checks original snapshot IDs, enabled state, display bounds, supported actions, range endpoints and toggle state. Only accessibility bounds can produce a highlight. A malformed or ungrounded image response produces uncertainty, rather than a deterministic replacement. The old Settings route tables and rule-selection fallback were disabled; historical evaluator types remain for compatibility. No app-control accessibility actions or gestures are performed automatically.

Screen capture uses Android's permitted accessibility screenshot API. Actual display dimensions and application-window bounds determine the crop. System-bar/cutout insets are excluded. Orientation/dimension mismatches are rejected rather than guessed. Images are aspect-preserving PNGs with a longest edge of at most 768 pixels and a 2 MB encoded budget. Hardware buffers and bitmaps close immediately; encoded buffers are erased after use. No screenshot is written to disk or uploaded.

The X6837 exposes a persistent system sidebar at display bounds `(1041,561)-(1080,945)`. Initial capture was conservatively blocked. The final implementation trims edge system/foreign-overlay windows out of the crop using their actual Android bounds, chooses the largest remaining valid rectangle and never expands it. Interior/full-screen occlusions still block capture. Two host tests verify that no excluded pixels can enter the resulting crop. This final geometry change has not yet passed a fresh live screenshot test.

The pipeline binds capture and inference to the same session/revision/observation, invalidates pending results on accessibility changes, and recaptures before accepting an image decision. Even unchanged accessibility trees cannot accept changed pixels. Exact pixel comparison is deliberately conservative: animations can cause abstention. Screenshot failures are surfaced and may use explicitly labeled text-only reasoning. Unavailable models and uncertain outputs are distinct states.

Password/sensitive subtrees are never read for text. Screens containing such nodes have no AI elements. Editable fields and descendants are omitted, and their presence blocks images. Bounded/truncated or stale traversal, keyboard windows, other application windows, interior/full-screen system/foreign-overlay occlusions and non-default displays block images. Android's secure-window denial is respected. The app has no Internet permission. Android 13 does not reliably identify every authentication or sensitive display; applications that fail to expose sensitivity can require additional user care and privacy review. A privacy control clears the assistant's transient goal/history/session. Production logging contains no screenshot pixels, UI text or generated answer.

The guidance controller records up to four prior suggestions and observed app transitions, distinguishing suggestions from confirmed actions. It rejects obsolete goal/session/request/revision results and repeated suggestions on an identical screen. `DONE` requests explicit user confirmation. General completion accuracy and multi-step recovery still require physical validation.

User-reported overlay regressions were addressed during physical testing: target content events immediately remove actionable highlights, while coalesced extraction determines whether screen data actually changed. Unchanged content events no longer cancel Ask Screeny. Menus survive selection invalidation on the same app, and real changes during a question show an explicit retry message. The pending caption is restored after keyboard/layout settling. Immutable planning snapshots are cached by revision and display size to reduce repeated main-thread hierarchy processing. These changes retain the final observation refresh and strict result grounding.

The final caption change additionally preserves pending questions during keyboard settling and keeps non-target explanations/status messages through unchanged content events. It clears a target instruction immediately when its highlight is invalidated. Screenshot preparation keeps the progress banner visible and excludes its measured window rectangle from the image crop; bubble/highlight surfaces remain hidden during capture. This addresses the user's report that “Checking this screen” flashed and vanished. The final caption change compiled successfully; its exact behavior still needs manual confirmation on the installed APK.

## Provisioning and verification

Use the activity's Import text model / Import image model actions to select already-downloaded licensed artifacts through Android's document picker. Imports stream to a private staging file, enforce the expected size/hash and atomically replace the model only after verification. Cancellation or corruption preserves the previous model. Models live in `noBackupFilesDir/models`; runtime caches are private. Do not rename other formats into `.litertlm`.

Host verification: `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and 39 focused JVM tests passed on 2026-10-10. After the final geometry/caption changes, a focused rerun of 17 tests passed (eight planner, five controller, two snapshot and two new crop tests). Tests cover strict parsing, direction-specific control grounding, stale accessibility/pixel rejection, buffer erasure, unavailable models, real-AI fallback orchestration, atomic model provisioning, controller history/repetition and privacy sanitization. Synthetic backend test doubles establish orchestration policies, not model quality.

Physical instrumentation uses an explicitly selected wireless ADB device and opt-in arguments:

```powershell
adb -s SERIAL shell am instrument -w -e class com.screenly.app.ai.MultimodalInferenceTest -e visionSmoke true com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -e class com.screenly.app.ai.ScreenPlannerNativeTest -e plannerBenchmark true com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -e class com.screenly.app.ScreenObservationInstrumentationTest,com.screenly.app.LiveScreenCaptureTest -e currentScreenSmoke true com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -e class com.screenly.app.AssistantInferenceUiTest -e assistantUi true com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
```

Live tests require manually enabled accessibility and a user-authorized, non-sensitive current screen. They click only Screenly controls and do not log private screen/answer content. The device's radios remained on for wireless ADB; radio-disabled restart/inference was **not tested**. No network inference exists in source, but this is distinct from an airplane-mode physical check.

The five Android node/privacy/network-permission tests passed. The first live capture run was skipped because the instrumentation service query did not find a bound service; its aggregate `OK (6 tests)` is not six physical passes. A subsequent live capture/menu attempt failed: Android retained Screenly in enabled settings but listed it under crashed services, with no bound service. The user manually re-enabled it. See [initial live run](verification/offline-multimodal/live-capture.txt) and [failed overlay attempt](verification/offline-multimodal/overlay-regression.txt).

[Live UI run after manual reconnection](verification/offline-multimodal/live-ui-reconnect.txt): Explain and Ask each displayed a real **TEXT_ONLY** answer, and the menu/content-event stability test passed. The screenshot test failed with `PRIVACY`, consistent with the measured system sidebar before the crop fix. Aggregate: three tests, one failed. No real answer text or screenshots were logged. Starting instrumentation restarts the target app process and can disconnect its accessibility service; the tests now wait up to 120 seconds for manual reconnection. Do not interpret that instrumentation-induced disconnect as proof of an independent production crash.

## Model comparison and quality limitations

| Candidate | Android/integration evidence | Memory/latency/image evidence | Decision |
| --- | --- | --- | --- |
| Gemma 3n E2B INT4 / LiteRT-LM 0.10.2 | Official format and supported API; native initialization, distinct-image inference and cleanup passed on X6837 | ~2.9–3.1 GiB observed PSS; ~25 seconds simple image calls; richer planner calls ~32–48 seconds | Implemented, compatibility gate passed on this phone; latency and navigation quality remain limitations |
| Existing Gemma 3 1B INT4 / LiteRT-LM | Existing pipeline and fresh physical text smoke passed | ~1.06 GiB fixture PSS; warmed compact choices ~4 seconds; no image capability | Preserved text fallback; semantic mistakes remain |
| SmolVLM/SmolVLM2 or Qwen2.5-VL / llama.cpp mtmd | Supported model families in [upstream mtmd documentation](https://github.com/ggml-org/llama.cpp/blob/master/tools/mtmd/README.md); would require GGUF plus matching vision projector and Android/JNI integration | Not provisioned or measured on this phone; image support in upstream is not an Android proof | Investigated alternative; no verified replacement or invented performance claim |

The frozen four-task benchmark compares identical accessibility snapshots with/without synthetic images: upload, folder creation, font adjustment, and dark-mode toggle. It is **not** testing installed unfamiliar applications. [Initial native benchmark](verification/offline-multimodal/planner-benchmark.txt) scored 3/4 for each backend and failed its required 4/4 basic-quality assertion. Gemma 3n's upload response was malformed and safely rejected; [diagnostic](verification/offline-multimodal/vision-planner-diagnostic.txt) captures only synthetic output. Gemma 1B chose the wrong font-size direction. Earlier wire-format failures are retained as failed evidence, not successes. A generic format example was subsequently added to the vision prompt; any rerun is recorded separately.

The [format-example rerun](verification/offline-multimodal/vision-planner-final.txt) again rejected a malformed upload response (extra trailing pipe) and correctly selected New folder. It then terminated with an Android input-dispatch timeout while the device was in use; no full accuracy or stability result is claimed. The stalled/crashed accessibility service and CPU/memory pressure require further investigation. A filtered Android last-ANR report identified a recents-animation input timeout, which is insufficient to attribute a precise native or main-thread cause. This attempt is failed evidence.

Acceptance is incomplete until native planner quality passes, real unfamiliar applications and multi-step goals are benchmarked, offline restart is checked, and sustained memory/latency is measured. The compatibility result does not establish intelligent navigation across arbitrary apps.
