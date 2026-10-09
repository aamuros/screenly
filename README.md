# Screenly

Screenly is an Android assistant for navigating unfamiliar apps. The intended MVP observes
an app through accessibility, accepts a goal such as “Enable dark mode,” and uses local AI
to suggest one control at a time. Screenly validates and highlights the control; the user
performs the action, and Screenly observes the next screen. Inference must work offline.

## Current status

The current app is a **manual accessibility prototype**: sanitized screen observations, a
72dp outlined assistant icon that snaps to screen corners, a compact animated four-action native menu, an element picker under **Guide Me**, and touch-through highlighting. Goal input, AI
inference, and multistep guidance are not implemented.

M1 (accessibility) and M2 (overlays) are **IMPLEMENTED — UNVERIFIED** against full acceptance:
automated and API 35 Pixel Tablet emulator evidence exists, but physical-device verification
is outstanding. M0 contract setup is **IN PROGRESS**; M3–M6 are **NOT STARTED**. See the
[roadmap](docs/ROADMAP.md) and preserved [verification report](VERIFICATION.md).

## Stack and scope

- Implemented: Kotlin, native Android, Compose activity, AccessibilityService,
  AccessibilityNodeInfo, WindowManager accessibility overlays, one Gradle `app` module.
- Planned: Google LiteRT-LM, quantized Gemma 3 1B pending compatibility/benchmarking,
  Coroutines/StateFlow, deterministic guidance, LlmPlanner and RulePlanner.
- No authentication, backend, cloud inference, database, or automatic taps. No network
  permission is requested. Models and provisioning are not included yet.

## Structure

```text
app/src/main/java/com/screenly/app/
  MainActivity.kt                  Service status and accessibility-settings entry
  ScreenlyAccessibilityService.kt Observation, event scheduling, service lifecycle
  AccessibleUiElement.kt           Element values, bounds checks, label sanitation
  ScreenObservation.kt             Snapshot equality and selection revisions
  ScreenlyOverlay.kt               Native bubble, menu, picker and highlight windows
  FloatingAssistantViews.kt        Live four-action menu and information panels
app/src/main/res/xml/              Accessibility service configuration
app/src/test/                     Observation-policy and sanitizer tests
app/src/androidTest/              Template app-context test only
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

Open Android Settings, tap the assistant icon, choose **Guide Me**, select an enabled clickable row, then manually tap the
highlighted control. Drag the bubble to dock it at the nearest screen corner. Selection does not activate it. Scroll/navigation should clear the
selection; Screenly's own activity hides overlays.

## Shared documentation

- [Codex instructions and ownership](AGENTS.md)
- [Milestones, parallel work, and collaboration workflow](docs/ROADMAP.md)
- [Actual architecture and proposed contracts](docs/ARCHITECTURE.md)
- [Verification procedures and result recording](docs/TESTING.md)
- [Existing M2 verification report](VERIFICATION.md)
