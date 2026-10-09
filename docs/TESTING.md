# Screenly testing

[ROADMAP.md](ROADMAP.md) defines acceptance gates. Source inspection establishes implementation;
host tests establish only the tested policies. Emulator results do not establish physical-phone
behavior. Isolated AI smoke and M4 helper tests now exist; shared planner/end-to-end
integration remains planned. [M3 provisioning, commands and evidence](LOCAL_AI.md) distinguish compilation from
real physical offline inference.

## Host checks and Android Studio

From the repository root with README's SDK/JDK requirements configured:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests com.screenly.app.ScreenObservationStateTest --tests com.screenly.app.ObservationSanitizerTest
./gradlew :app:lintDebug
```

Use targeted tests for observation-policy/sanitizer changes; use
`./gradlew :app:testDebugUnitTest` when broader coverage is justified. Select future planner/
controller tests by their actual class names after implementation.

Android Studio: sync Gradle, select the `app` debug configuration and target device, Run to
install/launch, and inspect Build/Test output and Logcat. Run unit classes from `app/src/test`
for host checks. The current user-authorized M4 development scope uses `Screenly_M3_API30`;
phone absence does not block emulator checks. Physical milestone gates remain unverified.
`ExampleInstrumentedTest` checks the application ID only;
`./gradlew :app:connectedDebugAndroidTest` requires a device/emulator and does not verify
observation, overlays or guidance. Run only when instrumentation testing is requested.

Meaningful current unit coverage: 8 revision/selection/bounds/scheduling tests and 6 sanitizer
tests, plus 9 local model integrity/response/failure/lifecycle tests and 15 navigation preparation
tests over 22 synthetic fixtures. The extra arithmetic template test
provides no Screenly feature evidence. Native inference is checked separately by the opt-in
`ai/LocalInferenceSmokeTest`; host tests do not load the real model.

## Physical-device setup (manual)

Use a phone running **Android 11 / API 30+**, a data-capable USB cable, Developer options/USB
debugging and authorized `adb`. Record model, API, RAM, ABI and free storage before AI testing;
no AI minimum RAM/GPU requirement is confirmed. Choose a demo phone and target app/version.
API 35 emulator evidence does not establish older API, OEM, cutout or secure-lock behavior.

```sh
adb devices -l
adb -s SERIAL shell getprop ro.product.model
adb -s SERIAL shell getprop ro.build.version.sdk
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL logcat -s ScreenlyAccessibility:D AndroidRuntime:E '*:S'
```

Replace `SERIAL` with the phone serial. Enable Screenly via its accessibility-settings button
and accept Android's prompt; resolve sideload restricted settings via system App info if
needed. Screenly should show **Enabled** on return; this does not prove a usable target
hierarchy. No separate overlay permission is needed. Use non-sensitive test screens: debug
Logcat contains ordinary non-editable app labels.

## Milestone verification

| Milestone | Automated / source checks | Physical or manual procedure |
| --- | --- | --- |
| M0 | After implementation, compile both consumers; test index/key validation and wrong session/revision/request/goal rejection | Jointly review/merge API; build both branches and check shared fixtures |
| M1 | Build; sanitizer/observation-policy tests; inspect extraction/privacy limits | Enable service; inspect Settings/chosen app labels/descriptions/class/IDs/states/bounds, missing fields, checked/disabled/scrollable controls; navigate/scroll, unavailable root and safe recovery; lock suppression |
| M2 | Build; revision/bounds tests; inspect window type/touch flags | Bubble/picker checklist below, portrait/landscape/cutout alignment, touch pass-through, lock and disable/re-enable |
| M3 (implemented, unverified) | Build runtime integration; model integrity/failure/lifecycle unit tests; compile isolated smoke test | Exact artifact on demo phone; cold/warm load and repeated inference; offline restart, close/reload and errors; [commands and evidence](LOCAL_AI.md) |
| M4 (preparation) | Synthetic fixtures and implemented prompt/parser/rule/index checks; shared-key/fallback adapter checks after M0 | API 30 helper test and M3 smoke; real offline navigation fixture evaluation after approved contracts |
| M5 (future) | Delayed MockPlanner/state tests: stop, goal replacement, unchanged observations, A→B→A, lock/reconnect during planning | Real-model multistep goal, manual actions, navigation during inference, corroborated completion |
| M6 (future) | Exact final artifact build and relevant regressions | Offline restart, repeated demo rehearsals, reliability/lifecycle checks and performance measurements |

### M1/M2 phone checklist

1. Open Settings; confirm one **S** bubble and current observations in Logcat. Missing labels/
   IDs must not crash. See [preserved physical checklist](../VERIFICATION.md#f-remaining-risks-and-physical-checklist)
   for additional diagnostics and known limitations.
2. Select an enabled row/button in the picker; compare outline with actual edges. Tap inside:
   underlying app receives the touch. Test near status bar/cutout and in landscape. Partially
   visible controls must not produce an unrelated highlight.
3. Open/close picker at least five times, drag bubble, use **Clear highlight**, touch outside
   picker. Expect normal app touch and dismissal, no duplicates or selection/drag auto-action.
   If needed inspect `adb -s SERIAL shell dumpsys window windows`: highlight has
   `NOT_TOUCHABLE`; bubble does not.
4. Select, then scroll, navigate, rapidly switch apps and return to the same screen. Selection
   clears; old picker callbacks do not revive it. Rotate/reselect and check alignment again.
   Record actual clearing behavior/latency.
5. Enter Screenly: overlays disappear. Return to Settings: one fresh bubble. Disable service
   with picker open: all windows disappear. Re-enable cleanly; repeat and inspect crash logs.
   Force-stop requires manual re-enablement, not automatic restart.
6. Screen off, wake securely locked, then unlock: no observations/target overlays behind lock;
   no old highlight after unlock. Use an already PIN/password-configured phone and never record
   credentials. Exercise unavailable-root recovery.

### M3 model validation and future M4 snapshot evaluation

Record LiteRT-LM version/backend, exact artifact format/quantization/hash, source/license,
provisioning, hardware and free storage. Check initialization/inference, missing/corrupt file,
close/reload and cancellation. Measure cold/warm initialization, repeated inference latency,
memory and APK/model size, including run counts/conditions. Set budgets jointly from evidence.

Provision locally, enable airplane mode and explicitly disable Wi-Fi/mobile data, then restart
app/service and infer. Record network state and fallback use. RulePlanner success alone does
not verify offline LLM inference. Developer downloads are allowed; runtime must not download
or contact a service.

Use deliberate, non-sensitive sanitized snapshot fixtures conforming to the merged contract.
Record goal, app/version, allowed candidate indices and expected result/acceptable set. Include
empty/ambiguous screens, repeated labels, disabled/nonclickable and off-screen controls.
Parser/rule tests are deterministic; real-model evaluations are separate. Report correct/total,
invalid outputs, fallback use, failures and latency. Sanitizer tests do not verify live
sensitive-node filtering on a phone.

[M4 preparation](M4_PLAN.md) records implemented isolated helpers/synthetic tests, pending
joint contract/protocol decisions, thin planner adapters and paired rule/model measurements.
Shared APIs and model navigation evaluation remain unimplemented/unmeasured.

### M5 stale rejection and completion (future)

Delay MockPlanner, change screen, navigate A→B→A, replace goal, stop, lock or disconnect/
reconnect. Return the old response: session/revision/request/goal checks reject it and no old
rectangle appears. Repeat during real phone inference. Current picker tests cover the manual
foundation only; add controller tests when M5 is implemented.

For “Enable dark mode,” record initial state, goal, proposed/actual targets, manual actions
and updated observations. Complete from the model is insufficient: verify the setting or
obtain user confirmation. Unchanged hierarchy must not repeat planning or falsely complete.
Record unsupported tasks.

## Final demo acceptance (M6)

- Identify commit, APK/hash, provisioned model/hash, phone and target app/version.
- Start reproducibly; complete agreed multistep task with real offline AI, also after restart.
- Accurate outlines and touch pass-through; navigation/rotation/lock/goal replacement discard
  stale work; stop and disable/re-enable clean up without crashes/duplicate bubbles.
- Completion has observed/user-confirmed evidence; missing model/unusable hierarchy is clear
  and recoverable, never an invented action.
- Record repeated rehearsal results, agreed performance-budget compliance and limitations.
  Task, repeat count, supported hardware and budgets still need joint agreement.

## Evidence and result recording

Preserved [VERIFICATION.md](../VERIFICATION.md), dated 2026-10-09, reports a debug build,
15 full-suite unit tests, lint (0 errors, 10 dependency-version notices) and listed API 35
Pixel Tablet emulator checks. These are historical reports, not fresh physical tests.
It records no physical phone tests, secure-keyguard test or formal CPU/ANR benchmark, and
an unavailable Settings Internet-page root.

Documentation audit, 2026-10-09, application baseline `51f5fb7`: ran
`./gradlew :app:assembleDebug :app:testDebugUnitTest --tests com.screenly.app.ScreenObservationStateTest --tests com.screenly.app.ObservationSanitizerTest --console=plain --quiet`.
Exit 0; XML reports show **14 tests, 0 failures/errors/skips**. No lint, instrumentation,
emulator or physical checks were rerun. Application code/configuration was unchanged.

Append future results here, or link a dedicated report for substantial evidence; avoid a
new document for every small check. Use:

```text
Date / tester / commit / milestone:
Environment: host or phone model/API/app; model/runtime/backend when relevant
Check: exact command or numbered steps; APK/model identity for device runs
Expected / actual: PASS, FAIL or NOT RUN; observations, timing and run counts
Evidence: test report, sanitized diagnostics or linked verification report
Limitations / remaining failure / owner / next check:
```

Keep FAIL/NOT RUN cases visible. Update ROADMAP from linked evidence; check off physical
tests only after physical results. Open decisions: model compatibility/provisioning, demo
hardware/app/task, runtime memory/latency budgets and AI quality thresholds.

Physical verification attempt, 2026-10-09, source baseline `cfb893c` on `feat/local-ai`:
`adb devices -l` returned no devices. Host model size/SHA-256 matched the pinned artifact;
`:app:assembleDebug`, `:app:assembleDebugAndroidTest` and a forced targeted `LocalInferenceTest`
rerun passed (9 tests, 0 failures/errors/skips). No physical provisioning, installation,
inference, offline checks or device metrics ran. Full command, identities, compatibility
inspection and NOT RUN results: [latest M3 report](LOCAL_AI.md#physical-verification-attempt-and-host-checks--2026-10-09).
Historical emulator/build/lint results above are not new physical results. M3 remains
IMPLEMENTED — UNVERIFIED; M0 approval and M4 implementation remain outstanding.

## M4 emulator development preparation — 2026-10-09

Date / tester / commit / milestone: 2026-10-09, Codex for Developer 2,
`a7eb1c436b90282565340a2c316efa404b1aad45` plus uncommitted M4 preparation on `feat/local-ai`.
Environment: macOS/JDK 21, existing SDK; `Screenly_M3_API30` at `emulator-5554`,
Android 11/API 30, ARM64, `ro.boot.qemu=1`. Model/runtime unchanged: Gemma 3 1B INT4,
584,417,280 bytes, SHA-256 `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`,
LiteRT-LM 0.10.2, CPU/four threads, 1,024 total tokens. Existing private model reused;
no model download, runtime replacement, Gradle changes or Developer 1 source edits.

Fresh host command (exit 0, final run after fixture layout/format corrections):

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests 'com.screenly.app.ai.navigation.*' :app:assembleDebugAndroidTest --console=plain --quiet
```

| Check | Expected / actual | Evidence / limitation |
| --- | --- | --- |
| Debug and instrumentation APK builds | PASS | Compilation/packaging only |
| NavigationProtocolTest | PASS: 9 tests, 0 failures/errors/skips | Prompt quoting/limits, parser negatives, original-index/allowed-set checks; no JNI |
| NavigationRulesTest | PASS: 6 tests, 0 failures/errors/skips | 22 synthetic fixture assertions plus matching/toggle/fallback safeguards; no AI |
| NavigationPreparationTest on API 30 | PASS: 1 test | Prompt/parser/target/rule helpers run on Android; no native inference |
| Existing LocalInferenceSmokeTest | PASS: 2 executions, 4 real responses | Basic text generation/reuse/close only; not a navigation fixture evaluation |
| Confirmed offline smoke rerun | PASS: 1 execution, 2 responses | Airplane/Wi-Fi/data 1/0/0 and no active default network before/after a force-stopped process restart |
| Model navigation accuracy/latency, LLM+fallback comparison | NOT RUN / NOT MEASURED | M0 unapproved; shared Planner/adapters intentionally absent |
| Physical phone, full Android UI regressions, API 35 crash investigation | NOT RUN | Current scope uses API 30; no phone prerequisite for this development task |

Unit XML timestamps: `2026-10-09T15:01:22.585Z` and `2026-10-09T15:01:22.610Z`,
under `app/build/test-results/testDebugUnitTest/TEST-com.screenly.app.ai.navigation.*.xml`.
No full suite or lint rerun; new source compiled and the targeted checks passed.

Installed and final rebuilt APK identities match:

| Artifact | Bytes / SHA-256 |
| --- | --- |
| app-debug.apk | 78,298,034 / `01553ce405e1ef88dd34a494b7edefb5443bd51db619ba830f96be98bfc2af50` |
| app-debug-androidTest.apk | 1,456,883 / `cb106cdc2412133089865dc8219493284e1e1e289db11f38eb4a85bbc7266a1b` |

Fresh device commands (full setup/output/restoration in the linked evidence):

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.screenly.app.ai.navigation.NavigationPreparationTest \
  com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am force-stop com.screenly.app
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.screenly.app.ai.LocalInferenceSmokeTest \
  -e localAiSmoke true -e requireOffline true \
  com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
```

Both smoke executions report `OK (1 test)` and cleanup PASS. The first settings-only run
initialized in **850 ms** and generated in **6,753 / 6,136 ms**. Its initial network dump
still showed an active default network during asynchronous disconnection; it is not counted
as fully confirmed offline acceptance. The rerun waited for no active default network,
then initialized in **944 ms** and generated in **6,987 / 8,351 ms**. These are four whole-response
M3 smoke timings, including conversation creation; initialization includes hashing. They are
not navigation planner latency, token/sec, phone measurements or memory benchmarks.
Full answers retain the contradictory font-size opening recorded in M3; nonblank text does
not establish useful navigation selection. No model accuracy was inferred from these answers.

The passing rule fixture assertions establish **18/22 decisions matching the provisional
navigation labels**, with **9/13 expected target selections**, **9/9 expected abstentions**,
zero wrong-target/invalid selections, and **13 total rule abstentions** (including four
unsupported semantic menu routes). This is deterministic rule-only coverage, not AI accuracy.
Four of the 22 fixtures reject prompt preparation; the rule baseline still examines them.
Fixture labels need joint review before real-model comparison; no quality threshold is agreed.

Restoration: original global connectivity **0/1/1** and enabled accessibility-service setting
restored. Writing unchanged service settings did not clear its post-instrumentation crashed
state; toggling the enabled-service setting with a quoted empty remote value restored binding.
A restoration checker falsely expected the component class in `Bound services`, which actually
reports `label=Screenly`; final dumps confirm bound Screenly, empty binding/crashed entries
and an active service record. These setup/checker failures are preserved in the evidence,
not JUnit failures. This restoration is not a full overlay/accessibility regression test.

Evidence: [complete emulator outputs and restoration](verification/m4-emulator-2026-10-09.txt).
Limitations / owner / next check: **M4 IN PROGRESS**, M0 approval pending; Developer 2's next
step is joint review of [contract decisions and implementation order](M4_PLAN.md), then
merged shared contracts, thin RulePlanner/LlmPlanner adapters and actual offline navigation
fixture evaluation on the same API 30 AVD. Developer 1 supplies immutable observations,
allowed original indices, authoritative keys and independent freshness/request/goal validation.
No shared contracts implemented/merged, no M5 work, no physical acceptance claimed.

## Debug fixture lab — 2026-10-09

Date / tester / commit / scope: 2026-10-09, Codex for Developer 2; preparation committed as
`f7442d6`, then `test/m4-fixture-ui` created from it. Lab changes are uncommitted on that
branch. User-authorized debug-only manual fixture/model testing is independent of M0;
no shared Planner APIs, Developer 1 source, main manifest, Gradle or runtime changes.
Environment: same macOS/JDK/SDK and `Screenly_M3_API30`, API 30 ARM64. Existing private
Gemma INT4 model and LiteRT-LM 0.10.2 CPU/four-thread configuration reused unchanged.

Files: debug manifest, `NavigationLabActivity.kt`, `NavigationLabRunner.kt`, lab string
resources, `NavigationLabRunnerTest.kt`, opt-in `NavigationLabInferenceTest.kt`; existing
fixtures moved into `src/debug` and protocol/rule tests into `src/testDebug` for shared
debug data without packaging the lab in release builds. Architecture, roadmap, Local AI,
M4 plan and this testing document updated; raw evidence added below.

Build/test command (exit 0):

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests 'com.screenly.app.ai.navigation.*' :app:assembleDebugAndroidTest --console=plain --quiet
```

After source relocation, incremental Kotlin outputs initially omitted the two moved test
classes and reported only six runner tests. Generated-class inspection confirmed the
omission. A `:app:sourceSets` diagnostic task was unavailable with this AGP version.
Forced targeted compilation with incremental compilation disabled corrected test discovery:

```sh
./gradlew :app:compileDebugUnitTestKotlin --rerun -Pkotlin.incremental=false :app:testDebugUnitTest --rerun --tests 'com.screenly.app.ai.navigation.*' --console=plain --quiet
```

Final result: **21 tests, zero failures/errors/skips**: NavigationProtocolTest 9,
NavigationRulesTest 6, NavigationLabRunnerTest 6. Tests establish that valid wrong selections
remain distinct from the rule answer; invalid/noncandidate outputs, NONE and runtime failures
cannot display rule success as a model target; rejected prompts skip generation and cancellation
propagates. No JNI in these host tests. No full suite, release build or lint run.

Fresh instrumented command:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.screenly.app.ai.navigation.NavigationLabInferenceTest \
  -e navigationLab true \
  com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
```

| Check | Actual / limitation |
| --- | --- |
| Debug and test APK builds | PASS |
| Host navigation checks | PASS: 21 tests |
| Real offline fixture harness | PASS: `OK (1 test)`; passing means execution/validation, not accuracy |
| Model fixture result | `display-font`, expected index 1; raw `None`; INVALID; no target selected |
| Rule baseline | Index 1, independently matching this fixture; not counted as AI success |
| Model call time | 1,927 ms including integrity/load and generation; one sample, not isolated generation latency |
| Offline state | Global 1/0/0, no active default network before/after; guarded in test |
| Lab launch | PASS: ActivityManager reports NavigationLabActivity resumed with its window |
| Manual UI taps, visual/layout checks, rotation/cancellation on device | NOT RUN; user can now test the open lab; source and host cancellation checks only |
| Full fixture accuracy, multiple-run latency/memory, physical device | NOT MEASURED / NOT RUN |

This one model call produced no correct target, one invalid response and zero canonical
abstentions. It is not an overall navigation accuracy benchmark. Protocol/prompt quality
still needs investigation; parser strictness was preserved rather than repairing the answer.
No deterministic fallback was presented as model output.

Installed APK identities:

| Artifact | Bytes / SHA-256 |
| --- | --- |
| app-debug.apk | 79,640,138 / `eec30500f91b1f694f47fe098d6b6df3c63624c2c82f8287cccdc90c5677c4bd` |
| app-debug-androidTest.apk | 1,466,463 / `293f80b1e34ee43af3dfd736a93736b311169266c1466a5ef57aef77fe5d6591` |

Original connectivity 0/1/1 and service enablement/binding restored; no crashed-service
entries. Lab then launched for manual testing. This is not full Developer 1 UI regression
verification. No model bytes committed, no push/merge or M5 implementation.
Evidence: [complete offline lab execution](verification/m4-lab-api30-2026-10-09.txt).
Next: manually exercise the [lab](M4_PLAN.md#debug-only-manual-fixture-lab), collect real
responses across fixtures, then review protocol/prompt changes. Shared integration still
requires Developer 1's C0 approval and freshness/eligibility publication.


## M4 response investigation — 2026-10-09

Date / tester / commit / milestone: 2026-10-09, Codex for Developer 2, `f7442d6` plus the
existing lab and response investigation on `test/m4-fixture-ui`; M4 remains IN PROGRESS.
Environment: `Screenly_M3_API30`, API 30 ARM64; unchanged Gemma 3 1B INT4 / LiteRT-LM 0.10.2
CPU/four-thread/1,024-token configuration. Original private model reused and device hash checked.

Expected / actual: application and test APK builds PASS; 31 targeted AI/navigation unit tests
PASS (0 failures/errors/skips). Final offline instrumentation PASS for execution/strict
validation, `OK (1 test)`; 20 real model calls plus 5 input rejections. Font-size original
prompt returned literal `None`, invalid, 5/5; final explicit-reply prompt selected index 1,
correct, 5/5. Missing, ambiguous and disabled-target expectations FAIL: 15/15 valid but wrong
AI selections, no canonical NONE. The rule baseline remains separate; no fallback used.
The all-blocked fixture skips inference in all five runs. No invalid output is repaired.

Evidence: [root cause, exact prompts, changes, identities, commands and timing tables](verification/m4-response-investigation-api30-2026-10-09.md),
[105 per-run raw records across original, experiments and final](verification/m4-response-investigation-api30-2026-10-09.jsonl).
Final font generation: median 875 ms, range 353–1,333; combined load/generation median 2,163 ms,
range 746–2,778; virtual-device/cached wall times with diagnostics, not controlled phone budgets.
Offline settings/network verified before/after and restored to 0/1/1; original Screenly service
rebound and final lab launched. No accessibility/overlay source, shared contracts, Gradle,
model bytes/version or runtime version changed.

Limitations / remaining failure / owner / next check: model semantic abstention is unreliable
on these fixtures; Developer 2 needs an evidence-based quality/configuration decision before
production planner integration. M0 approval remains pending. No physical tests, live screen
capture, broad fixture benchmark or Developer 1 UI regressions ran. Instrumentation success
is not model quality acceptance. Failed prompt experiments and corrected diagnostic setup
errors are explicitly retained in the report; no milestone is promoted from this result.

## M4 offline model comparison — 2026-10-10

Date / tester / commit / scope: 2026-10-10 Asia/Manila (2026-10-09 UTC), Codex for
Developer 2, `6e0af6039177bfb7292abbd32feb0edc3160a2ec` plus instrumentation-only
comparison changes on `test/m4-fixture-ui`. Environment: existing Screenly_M3_API30,
API 30 ARM64, 3.84 GiB RAM/no swap, LiteRT-LM 0.10.2 CPU/four threads/1,024 tokens.

Expected / actual: application and test APK builds PASS; targeted navigation host
checks PASS (21 tests, zero failures/errors/skips). Exact host/device model hashes
PASS. Qwen3 INT4 non-thinking and Qwen2.5 Instruct INT8 compatibility probes PASS
on the unchanged runtime. All three five-repetition scenario harnesses PASS for
execution/offline/strict validation, **FAIL for semantic abstention**. Gemma scored
5/20 correct decisions, Qwen3 0/20, Qwen2.5 5/20; each had 0/15 correct abstentions.
Wrong-target selections: 15, 20, 15 respectively; zero invalid outputs/runtime
failures. Each model also rejected the all-blocked input 5/5 without inference.
Rules separately matched 20/20 on the four decision fixtures; never substituted.

Evidence: [comparison, measured latency/memory, exact artifacts/licenses, commands,
APK identities, raw outputs and recommendation](verification/m4-model-comparison/README.md).
60 scored generations, 15 rejected inputs, two unscored compatibility generations;
all raw records retained. Generation medians: Gemma 1,361.5 ms, Qwen3 447.5 ms,
Qwen2.5 3,027 ms. Highest observed endpoint PSS including compatibility probes:
about 1.06 / 1.03 / 2.25 GiB respectively; sampled process memory, not model-only
or continuously measured peak RAM. Offline settings/network guarded per repetition;
original 0/1/1 connectivity and Screenly accessibility binding restored.

Limitations / remaining failure / owner / next check: no model recommended for
general UI guidance. Preserve Gemma as experimental baseline, narrow the demo to
curated/manual fixtures, then separately evaluate validated task-specific hints
or investigate fine-tuning with held-out abstention cases. No physical/UI regressions,
full suite or broad accuracy claim. Developer 1 source, shared contracts, production
model/runtime/Gradle unchanged; alternatives remain in androidTest/private experimental
paths. M4 stays IN PROGRESS; no live-overlay integration, merges or M5 implementation.

## M4 availability-first diagnostic — 2026-10-10

Date / tester / commit / scope: 2026-10-10 Asia/Manila (2026-10-09 UTC), Codex for
Developer 2; `6e0af6039177bfb7292abbd32feb0edc3160a2ec` plus the preserved comparison
and new debug/test-only diagnostic on `test/m4-fixture-ui`. Same Screenly_M3_API30,
API 30 ARM64, exact Gemma INT4 hash, LiteRT-LM 0.10.2 CPU/four threads/1,024 tokens.

Expected / actual: debug/test APK builds PASS; **8 focused routing/provenance tests
PASS**, zero failures/errors/skips. Preparation-only manifest pass and actual offline
instrumentation both PASS for execution/eligibility/routing, `OK (1 test)`.
Before inference, froze four original and twelve new synthetic variations plus
exact prompts/expectations/source hashes. Five repetitions per fixture per arm:
160 paired evaluations, **190 real generations** (80 baseline, 80 availability,
30 conditional selection). No tuning or expected-answer edits after model outputs.

Semantic result: **FAIL for reliable general selection**. Original cases improve
5/20 → 15/20; frozen new variations 10/60 → 35/60; total baseline 15/80 versus
availability-first 50/80. Gate alone: 60/80 correct, ten false YES and ten false NO.
Combined protocol: 10/30 correct target selections, 40/50 correct abstentions,
20 wrong targets, ten incorrect abstentions, zero invalid/runtime failures.
Baseline: 15/30 correct selections, zero correct abstentions, 60 wrong targets,
five invalid syntax responses, zero runtime failures. Separate rules match 80/80
labels in each arm without substitution. An analytical always-abstain reference
also scores 50/80, illustrating the limits of aggregate accuracy on this case mix.

Evidence: [frozen inputs, raw stage outputs, detailed results, timings and next-step
recommendation](verification/m4-availability-diagnostic/README.md). Whole instrumented
run 562.61 seconds. Median summed generation / load+decision: baseline 1,408 / 3,100 ms;
gated 1,418.5 / 3,146 ms; one/two-call mixture with cache-dependent emulator timings.
Original connectivity and Screenly service binding restored; offline assertions
before/after every arm. No physical/UI regressions, full suite, lint or new memory
benchmark. One initial fake-exception test compile error was corrected before the
passing build; no model call was involved.

Limitations / remaining failure / owner / next check: Developer 2's global availability
gate is diagnostic only. Wrong original-index choices, label-dependent ambiguity and
switch-state mistakes persist; useful target coverage regresses. Proposed next
experiment: per-candidate semantic matching with trusted input-index binding and
separately scored cardinality/state checks; not implemented here. Fine-tuning remains
an investigation option if semantic/state errors persist. Production protocol/model/
runtime, existing fixtures, Developer 1 source and shared contracts unchanged.
No live guidance, branches merged, training, M5 work or milestone promotion.
