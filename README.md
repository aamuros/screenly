# Screenly

Screenly is an Android assistant for navigating unfamiliar apps. The intended MVP observes
an app through accessibility, accepts a goal such as “Enable dark mode,” and uses local AI
to suggest one control at a time. Screenly validates and highlights the control; the user
performs the action, and Screenly observes the next screen. Inference must work offline.

## Current status

The integration branch connects sanitized observations, the floating assistant's **Guide Me**
goal input, local inference, deterministic validation, and touch-through highlighting.
Gemma evaluates controls individually with YES/NO answers; Android supplies original indices
and bounds. Unsupported or ambiguous decisions abstain. Verified rules can provide a step when
AI is unavailable, with separate test diagnostics. The manual picker is still available.
See [running and verifying the integration](docs/INTEGRATION.md) for supported scope and evidence.

M1 (accessibility) and M2 (overlays) are **IMPLEMENTED — UNVERIFIED** against full acceptance:
automated and API 35 Pixel Tablet emulator evidence exists, but physical-device verification
is outstanding. M0 contract setup is **IN PROGRESS**; M3 is **IMPLEMENTED — UNVERIFIED**;
M4 and M5 are **IN PROGRESS**; M6 is **NOT STARTED**. See the
[roadmap](docs/ROADMAP.md) and preserved [verification report](VERIFICATION.md).

## Stack and scope

- Implemented: Kotlin, native Android, Compose activity, AccessibilityService,
  AccessibilityNodeInfo, WindowManager accessibility overlays, one Gradle `app` module.
- Isolated M3: Google LiteRT-LM **0.10.2**, coroutines, CPU text inference; INT4 Gemma 3 1B
  candidate pending exact artifact/phone compatibility and offline benchmarking.
- Implemented integration: a coroutine GuidanceController with session/revision/request/goal
  checks. Shared Planner adapters and StateFlow remain unimplemented.
- No authentication, backend, cloud inference, database, or automatic taps. No network
  permission is requested. The APK contains no model; [ADB provisioning and M3 evidence](docs/LOCAL_AI.md)
  are documented separately.

## Structure

```text
app/src/main/java/com/screenly/app/
  MainActivity.kt                  Service status and accessibility-settings entry
  ScreenlyAccessibilityService.kt Observation, event scheduling, service lifecycle
  AccessibleUiElement.kt           Element values, bounds checks, label sanitation
  ScreenObservation.kt             Snapshot equality and selection revisions
  ScreenlyOverlay.kt               Native bubble, menu, picker, and highlight windows
  FloatingAssistantViews.kt        Live four-action menu and information panels
  GuidanceSnapshot.kt              Copied snapshots, original indices and derived row labels
  GuidanceController.kt            Goal, asynchronous requests and stale-result rejection
  ai/                             Local inference, candidate judgments and validation
app/src/main/res/xml/              Accessibility service configuration
app/src/test/                     Observation-policy and sanitizer tests
app/src/androidTest/              App-context test and opt-in local inference smoke test
docs/                             Shared plan, architecture, testing
VERIFICATION.md                   Preserved M2 audit and emulator evidence
```

## Develop, build, and install

Use Android Studio supporting Android Gradle Plugin **9.1.1**, Android SDK Platform **36.1**
(compile SDK; target SDK 36), SDK Platform Tools (`adb`), and a **JDK 21** Gradle daemon.
The wrapper pins Gradle **9.3.1**; Java source compatibility is 11. Configure the local SDK
through Android Studio/`local.properties`. Initial tool/dependency downloads may need internet;
this is separate from offline app inference.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
adb devices -l
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Replace `SERIAL` with a connected device serial. Runtime minimum: **Android 11 / API 30**.
Open Screenly → **Open Accessibility Settings** → **Screenly** under downloaded/installed
services → enable **Use Screenly**. If sideloading is restricted, use system **App info** →
**Allow restricted settings**, then retry. No “Display over other apps” permission is needed.

Open Android Settings, tap the assistant icon, choose **Guide Me**, and enter a goal.
Manually tap each verified highlighted control. Screen changes discard old selections and
trigger a fresh check. Guide Me also offers the original manual picker. Screenly's own
activity and the lock screen hide overlays; unavailable roots clear highlights and show waiting.

## Shared documentation

- [Codex instructions and ownership](AGENTS.md)
- [Milestones, parallel work, and collaboration workflow](docs/ROADMAP.md)
- [Actual architecture and proposed contracts](docs/ARCHITECTURE.md)
- [Verification procedures and result recording](docs/TESTING.md)
- [Existing M2 verification report](VERIFICATION.md)
- [M3 runtime, model provisioning and verification](docs/LOCAL_AI.md)
