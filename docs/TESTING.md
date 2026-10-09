# Screenly testing

## Current standalone backend checks — 2026-10-10

The [backend report](NAVIGATION_BACKEND.md) records the latest focused build/unit results,
offline model evaluation and limitations. Fixtures under `app/src/sharedTest/java` are reused
by JVM tests and opt-in instrumentation, without adding dependencies. Run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest \
  --tests 'com.screenly.app.ai.navigation.*' \
  --tests com.screenly.app.ai.LocalInferenceTest \
  :app:assembleDebugAndroidTest --console=plain --quiet
python3 scripts/evaluate-navigation.py --serial emulator-5554 --repetitions 3 \
  --output /tmp/screenly-navigation-evaluation
```

The evaluator requires the provisioned `Screenly_M3_API30` emulator, an empty output directory,
and APKs freshly built by the preceding command. It records and restores connectivity and
accessibility settings, verifies offline state, and preserves raw outputs and separate model,
rule and fallback results. It installs APKs with `-r`, preserving the local model.
Instrumentation execution success is not a navigation accuracy threshold or physical acceptance.
Historical evidence and milestone procedures follow.

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
