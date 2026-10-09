# Reliable navigation integration

Branch: `integration/reliable-guidance`, created from main `e754f7b`. It preserves
`feat/local-ai` (`dd87a44`) and the fetched floating UI work (`3a972e6`) as merge commits.
The experiment branch was inspected, including the measured failures of its global YES/NO
availability gate; that unsafe gate and its comparison models were not adopted.

The reliability change is a narrower decision task plus an independent safety policy.
Gemma answers YES/NO for each bounded candidate instead of inventing a TAP index when
the target is absent. Android attaches each answer to the captured original ID, rejects
ambiguous or unrelated matches, and independently permits only exact targets or verified
Settings routes. A unique deterministic fallback is recorded as RULE, never as a model
success. Immutable snapshots and goal/request/session/revision checks reject delayed work;
only Android's original bounds reach the overlay.

During development another process switched the original checkout to `feat/backend-tests`
with conflicts. Integration source was preserved in `.integration-worktree`; the original
checkout and the user's `scripts/run-emulator.ps1` were left untouched thereafter. Run
commands from the integration worktree, not the conflicted checkout.

## Run on a phone

1. Build/install the debug APK using JDK 21 and Android SDK 36.1:

   ```sh
   ./gradlew :app:assembleDebug
   adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Provision the exact pinned Gemma 3 1B INT4 `.litertlm` artifact using
   [the private ADB procedure](LOCAL_AI.md#reproducible-debug-provisioning-over-usb).
   The app has no model download or bundled model. The binary is not tracked in the AI branch.
   A host download was later authorized by the user; the publisher returned HTTP 401 and
   requires a local authenticated account with accepted access terms before it can proceed.
   Without it, only deterministic verified steps can work; the UI identifies AI unavailability.
   Keep the exact documented size/hash. LiteRT-LM remains 0.10.2, CPU/four threads, 1,024 tokens.
3. Open Screenly, open Accessibility Settings, enable Screenly, then return to Android Settings.
4. Tap the assistant bubble → **Guide Me** → enter **How do I change my font size?** →
   **Start guidance**. Tap the highlighted control yourself. Screenly refreshes after navigation
   and may highlight the next verified control. It never taps a target application.
5. Open Guide Me for **Recheck screen**, **Stop guidance**, **I completed my goal**, or
   **Choose a control manually**. A suggested control never establishes completion by itself.

Other English demo goals: **Find and open Wi-Fi settings**, **Open dark-theme settings**.
Direct targets use exact labels/limited aliases. Intermediate routing is limited to Google/
stock Android Settings and menus whose actual accessible summary names the destination.
The API 37 Google Settings profile also includes the **Internet → Wi-Fi** route observed on
the test emulator: Internet's summary is the connected network name, so it is not interpreted
as a destination hint. This exception is unavailable on other APIs/manufacturers.
Other manufacturers, languages, missing menus or unusable hierarchies may require manual
navigation. There are no unverified eGovPH routes. “Open” does not authorize flipping an
already-visible toggle. Enable/disable actions require a recognized, unsatisfied toggle.
When a settings entry and adjacent switch share a label, an explicit open request selects
the entry and an enable/disable request selects the appropriate switch. Duplicate entries
or duplicate switches remain ambiguous.

## Verification commands

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
  --tests 'com.screenly.app.ai.navigation.*' --tests 'com.screenly.app.Guidance*Test' \
  --tests com.screenly.app.ScreenObservationStateTest \
  --tests com.screenly.app.ObservationSanitizerTest \
  --tests com.screenly.app.ai.LocalInferenceTest :app:lintDebug --console=plain --quiet
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r -e class com.screenly.app.GuidanceUiTest \
  -e guidanceUi true com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
```

The default UI workflow is font size. Add `-e workflow wifi` or `-e workflow dark` for the
other demo routes. Each run checks two real outlines and the final settings page.
On this API 37 guest, add `-e expectUnavailableInternet true` for Wi-Fi: after reaching
the page, the test verifies safe waiting and cleared highlights, then returns to the homepage
to stop guidance. Internet's root remains unavailable to Screenly's service on this guest.

On this Windows host, Gradle initially selected an IDE JRE missing `jlink`. The working command
uses `gradlew.bat --no-daemon -Porg.gradle.java.installations.auto-detect=false` and
`"-Porg.gradle.java.installations.paths=C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot"`
before the same tasks. No project toolchain/dependency upgrade is needed.

The UI test is opt-in and intended for a dedicated stock Settings emulator. It temporarily
enables the service and restores the original settings in `finally`. Only test code injects
touches, using current Android bounds; the application contains no action injection. The test
checks screenshot outline pixels at two consecutive controls and taps through the overlay.
It is not a broad OEM, rotation, lock-screen or physical-phone acceptance test.

For **real Gemma quality**, provision the model, build both APKs, verify airplane mode on,
Wi-Fi/data off and no active network, then invoke `NavigationEvaluationTest` with
`-e navigationEvaluation true -e protocol candidate -e repetitions 3`. Exact per-candidate
prompts are recorded before initialization. Raw YES/NO judgments, model matches, rejections,
fallback source and summed generation latency are recorded separately in
`cache/navigation-evaluation.jsonl`. Keep synthetic fixtures separate from device UI results.
The legacy TAP/NONE evaluator remains available without the protocol argument.
Add `-e fixtureSet availability` to evaluate the exact 16 frozen diagnostic failures from
`test/m4-fixture-ui`, including NFC states and missing/duplicate targets. They are also used
by a JVM regression with deliberately adversarial YES responses; that is policy verification,
not measured Gemma accuracy.

## Evidence and limits

The accompanying [verification directory](verification/integration-guidance/) preserves UI
attempts and a machine-readable host/device summary. Read its results before claiming success.
Fresh emulator rule fallback does not establish Gemma accuracy or offline LLM acceptance.
No model replacement, retraining, new application dependencies or cloud inference occurs.
The user-authorized host download of the same pinned model is currently blocked by HTTP 401
from the gated publisher; no model bytes were downloaded. Physical and real Gemma acceptance
are still unverified.

Shared contract/main merge review, the actual provisioned model on the demonstration phone,
its latency/memory budgets, API/runtime compatibility, and physical privacy/lifecycle regressions
remain required. All broader navigation is unsupported unless the current validation policy
can establish a unique safe next action. Independent candidate evaluation bounds inference
to eight controls, but can take up to eight native calls; no phone latency budget is claimed.
