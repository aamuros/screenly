# Screenly testing

[ROADMAP.md](ROADMAP.md) defines acceptance gates. Source inspection establishes implementation;
host tests establish only the tested policies. Emulator results do not establish physical-phone
behavior. Future procedures below are plans; AI/end-to-end tests do not exist yet.

## Host checks and Android Studio

From the repository root with README's SDK/JDK requirements configured:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests com.screenly.app.ScreenObservationStateTest --tests com.screenly.app.ObservationSanitizerTest
./gradlew :app:lintDebug
```

The helper's targeted host checks are `./gradlew :app:testDebugUnitTest --tests com.screenly.app.AccessibleScreenAssistantTest --tests com.screenly.app.BubbleDockingTest`.
Use targeted tests for observation-policy/sanitizer changes; use
`./gradlew :app:testDebugUnitTest` when broader coverage is justified. Select future planner/
controller tests by their actual class names after implementation.

Android Studio: sync Gradle, select the `app` debug configuration and target device, Run to
install/launch, and inspect Build/Test output and Logcat. Run unit classes from `app/src/test`
for host checks. Emulators provide preliminary checks; phone gates remain required.
`ExampleInstrumentedTest` checks the application ID only;
`./gradlew :app:connectedDebugAndroidTest` requires a device/emulator and does not verify
observation, overlays or guidance. Run only when instrumentation testing is requested.

Meaningful current unit coverage: 8 revision/selection/bounds/scheduling tests and 6 sanitizer
tests. The extra arithmetic template test provides no Screenly feature evidence.

### Four-panel manual regression checks (not yet device-verified)

1. Re-enable the Screenly accessibility service after installing, if prompted for the
   screenshot capability. Verify the black outlined bubble can slide vertically and snap
   to either screen edge without forcing a top or bottom corner.
2. Open each of the four compact panels. Verify their animation, close button, Android
   Back, keyboard resizing, internal scrolling, rotation and underlying touch behavior.
3. Ask AI: type, Send, follow up and Recapture. Inspect actual capture status. Text must
   be labeled as accessibility-derived, not screenshot vision.
4. Explain: enter, Refresh, and expand a detected control. It should only list currently
   accessible labels and should not claim to recognize unseen pixels.
5. Guide Me: enter Enable dark mode, follow its current instruction, manually tap in the
   app, then Check my screen. Unchanged screens should not advance; ambiguous results
   must not report completion. Try Cancel and Select a control manually.
6. Privacy: inspect offline/model/capture status, clear the chat and temporary data,
   and test the accessibility settings shortcut. No capture should continue silently.
7. Repeat on a protected/secure app screen and after lock, rotation, service disable
   and a rapid sequence of capture requests. Confirm no fabricated answer or orphaned
   overlays, and record results and limitations with the device model and commit.

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
| M3 (future) | Build runtime integration; isolatable missing/corrupt model and failure tests | Exact artifact on demo phone; cold/warm load and repeated inference; offline restart, close/reload and errors |
| M4 (future) | Recorded sanitized fixtures: parser/rules, malformed schema/types, invalid/noncandidate indices, fallback/no-match and stale responses | Real offline model on labelled snapshots; report selection quality and unsupported cases against agreed schema |
| M5 (future) | Delayed MockPlanner/state tests: stop, goal replacement, unchanged observations, A→B→A, lock/reconnect during planning | Real-model multistep goal, manual actions, navigation during inference, corroborated completion |
| M6 (future) | Exact final artifact build and relevant regressions | Offline restart, repeated demo rehearsals, reliability/lifecycle checks and performance measurements |

### M1/M2 phone checklist

1. Open Settings; confirm one outlined floating bubble and current observations in Logcat. Missing labels/
   IDs must not crash. See [preserved physical checklist](../VERIFICATION.md#f-remaining-risks-and-physical-checklist)
   for additional diagnostics and known limitations.
2. Choose Guide Me, then Select a control manually; select an enabled row/button in the picker and compare the outline with actual edges. Tap inside:
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

### M3/M4 model and snapshot evaluation (future)

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

## Integrated branch: local AI verification

The `feat/local-ai` unit and instrumentation test suites are included in this integration branch.
See [LOCAL_AI.md](LOCAL_AI.md) and [NAVIGATION_BACKEND.md](NAVIGATION_BACKEND.md) for
model integrity verification, ADB provisioning, smoke-test commands and standalone navigation evaluation.
A successful source merge is not a substitute for an APK build or real-device inference run.
