# Guide Me: offline Android integration

Date / tester / baseline / milestone: 2026-10-10 / Codex / `6d423d7`, uncommitted
changes on `fix/assistant-guidance-validation` / M5 IN PROGRESS. No branch switch, merge,
commit or push. The identified working sources and APKs are hashed in the
[model environment](verification/assistant-guidance-2026-10-10/model/environment.json).
This is a prototype integration authorized by the user; formal shared-contract/main review
and physical acceptance remain open.

## Implementation

The existing floating bubble, menu, compact panels, chat and manual picker are retained.
Guide Me binds `GuidanceController` to `NavigationEngine.decideCandidates` and the unchanged
`LocalInference` runtime. One model is lazily initialized/reused per accessibility-service
session; initialization, generation and serialized cleanup run off the main thread.
Cancellation discards work; synchronous JNI cannot be preempted.

The controller, snapshot adapter, candidate protocol and Settings policy were adapted from
`integration/reliable-guidance` (`fc865d9`). This was selective reuse, not a merge of its UI.
Copied parent indices label clickable rows using actual sanitized descendants. Original
candidate indices and Android bounds remain authoritative. Session, revision, request and
goal guards reject delayed responses, including A→B→A. Android refreshes and validates
eligibility, intent and bounds before drawing. Duplicate labels are not collapsed. Opening
settings cannot select their adjacent toggle; enable/disable requires a known unsatisfied toggle.

Guide Me requires no screenshot. Ask/Explain retain on-demand capture and their explicitly
accessibility-derived answers. The Guide panel stays clear of the target and passes outside
touches through. Screen changes clear stale targets and trigger fresh planning; unchanged
observations wait. Unavailable roots retain the goal while clearing selection. Lock, own
activity, rotation, interruption and teardown stop guidance/remove overlays.

MODEL and RULE provenance are shown separately. Invalid/unavailable inference attempts one
deterministic fallback; no unique safe candidate yields an uncertain/unavailable message.
Model output never proves completion. The completion button records the user's statement
and explicitly says Screenly has not independently verified it.

No runtime, model, dependency, network permission or automatic target tapping was added.
Only instrumentation injects user-equivalent taps. Routing is limited English vocabulary:
stock Settings summaries must advertise the destination. An Advanced menu advertising font
size is supported. The observed Google API 30 Display → Dark theme route is explicitly scoped
to that profile; the existing Google API 37 Internet route remains separately scoped.

## Automated results

Expected: both APKs build, unit regressions pass, lint has no errors, and unsafe/stale
decisions cannot authorize highlighting.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebugAndroidTest --console=plain --quiet
```

Actual: PASS; **94 JVM tests, zero failures/errors/skips; lint zero errors, 24 warnings**.
[Host results](verification/assistant-guidance-2026-10-10/host.json) list the classes.
Regression coverage includes duplicates beyond the native-call budget, unsafe/satisfied
toggles, row-versus-switch intent, original/high/reordered indices, copied ancestry,
changed bounds/viewport, stale sessions/revisions, noncancellable A→B→A responses,
goal replacement, stop, final refresh and model-failure fallback provenance.

Existing Android context/preparation tests and opt-in native smoke: **OK (3 tests)**,
[raw evidence](verification/assistant-guidance-2026-10-10/android-smoke.txt).
Offline smoke initialization was 398 ms; two real generations took 5,975/5,714 ms;
reuse and idempotent cleanup passed. Nonempty prose is not navigation-quality evidence.

## Live offline Settings workflows

Environment: macOS host; `Screenly_M3_API30`, Google `sdk_gphone_arm64`, Android 11/API 30,
ARM64; Gemma 3 1B IT INT4, 584,417,280 bytes, pinned SHA-256
`1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`;
LiteRT-LM 0.10.2, CPU/four threads, 1,024 tokens, unchanged sampler.

Airplane mode was on, Wi-Fi/data off, with no active default network. Tests enter a real
goal, require matching current instruction/outline, confirm panel non-obstruction, tap
through the overlay, reach the actual settings page and cancel guidance/clear highlights.
The font destination checks the actual size control; dark navigation asserts unchanged
night-mode state. These tests set goal text directly and do not verify keyboard typing.

| Workflow | Validated steps | Sources | Decision latency | Whole test |
| --- | --- | --- | --- | --- |
| Font size | Display → Advanced → Font size → size-control page | MODEL → MODEL → MODEL | 1,966 / 1,698 / 2,053 ms | 10.158 s |
| Wi-Fi | Network & internet → Wi-Fi → Use Wi-Fi page | RULE → MODEL | 1,706 / 2,029 ms | 7.715 s |
| Dark theme | Display → Dark theme → theme settings, no toggle | RULE → MODEL | 1,740 / 1,724 ms | 7.264 s |

Actual: all three PASS. These are single emulator samples, not phone benchmarks.
MODEL decisions are accepted local inference. RULE decisions in this provisioned run follow
rejected model targets, not missing-model failures. [Summaries and diagnostics](verification/assistant-guidance-2026-10-10/model/summary.json)
retain both. The panel's source label and process-filtered diagnostics independently identify provenance.

Separate missing-model run: all three PASS with **RULE-only steps**, `outcome=FAILED`.
Whole test times: 4.647 / 4.083 / 3.744 s. These are fallback results, not AI generation
performance. The pinned private file was temporarily renamed and restored in `finally`.
[Rule evidence](verification/assistant-guidance-2026-10-10/rule/summary.json) retains results/settings.

## Isolated real-model evaluation

One repetition of 16 previously frozen safety diagnostics produced **31 native candidate
responses**, 16 screen decisions, zero runtime failures. Raw/model decisions were correct
on **6/16** screens; accepted model results were one selection and five abstentions.
The validated combined engine was **16/16**, with one MODEL selection, five RULE selections
and ten safe abstentions; no wrong final target. Rules alone solved these fixtures.
This establishes policy safety on these inputs, not broad or improved intrinsic Gemma accuracy.

Initialization: 461 ms. Per-screen aggregate generation: median 1,061 ms, range 534–1,579 ms.
Maximum endpoint PSS: 1,084,289 KiB, including app/instrumentation/model; not continuous peak
or isolated model memory. Native concurrency, cancellation followed by reuse, and cleanup
passed; cancellation does not establish interruption of an active JNI call.
[Summary](verification/assistant-guidance-2026-10-10/candidate/summary.json) and
[raw prompts/responses](verification/assistant-guidance-2026-10-10/candidate/raw.jsonl) retain actual outcomes.

## Reproduction and remaining gates

Install both APKs with `adb install -r` to preserve the privately provisioned model. Gradle
connected-test cleanup can uninstall the application and delete it. Then run:

```sh
python3 scripts/verify-guidance.py --mode model --output /tmp/screenly-guidance-model
python3 scripts/verify-guidance.py --mode rule --output /tmp/screenly-guidance-rule
python3 scripts/evaluate-navigation.py --repetitions 1 --protocol candidate \
  --fixture-set availability --output /tmp/screenly-candidate
```

Use empty output directories and the dedicated emulator. The runners preserve connectivity,
accessibility settings and model provisioning. Instrumentation can asynchronously restore a
service entry after its own cleanup; final restoration removed that entry to match the recorded
pre-test `null/0` state. The final model hash matched the pinned artifact.

Earlier failures remain in [development evidence](verification/assistant-guidance-2026-10-10/development/).
The missing Advanced route was fixed from observed summary/ancestry evidence. Test fixes
covered stale similarly positioned outlines, animation/scroll-aware taps, service-capture
settling, Wi-Fi typography, and API 30 font/dark destination controls. Earlier partial results
are not final passing runs. An earlier dark paraphrase used RULE → RULE despite real inference.

Ready for prototype code review to the documented emulator scope. Physical acceptance,
OEM/language coverage, real typing, real-model lock/rotation/reconnect stress, and agreed phone
latency/memory budgets remain unverified. Existing API 35 native incompatibility remains open.
Supported navigation safely abstains beyond its policy; automatic completion/setting-value
verification is not implemented. No milestone is promoted to VERIFIED and main is unchanged.
