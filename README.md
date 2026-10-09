# Screenly

Screenly is an Android assistant for navigating unfamiliar apps. The intended MVP observes
an app through accessibility, accepts a goal such as “Enable dark mode,” and uses local AI
to suggest one control at a time. Screenly validates and highlights the control; the user
performs the action, and Screenly observes the next screen. Inference must work offline.

## Current status

The current app has sanitized accessibility observations, a 72dp outlined edge-docked
floating bubble, an animated four-action menu, compact panels for Ask AI, Explain, Guide Me,
and Privacy, and touch-through highlighting with the manual picker available as a fallback.
Ask AI and Explain provide explicitly labelled accessibility-based information.
Guide Me uses the existing Gemma 3 1B INT4/LiteRT-LM backend to evaluate sanitized
accessibility candidates. Android validates each decision, highlights the original control,
and replans after manual navigation. Deterministic fallback is identified separately. No
screenshot is required for guidance; Ask and Explain retain optional on-demand capture.
No vision model, cloud inference, network permission or automatic tapping is added.
Provision the pinned model using [LOCAL_AI.md](docs/LOCAL_AI.md). Missing models yield a safe
rule fallback or an unavailable message. Completion is explicitly user-confirmed, never
inferred from model output. The compact floating panels and manual picker are preserved.

This is an emulator-tested prototype, not a physically verified release. Shared main-branch
contracts and phone acceptance remain open. See [guidance integration](docs/GUIDANCE_INTEGRATION.md),
the [roadmap](docs/ROADMAP.md), and the historical [verification report](VERIFICATION.md).

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
  FloatingAssistantViews.kt        Live four-action menu and feature cards
  ScreenlyFeaturePanel.kt          On-demand screenshot lifecycle and panel controller
  AccessibleScreenAssistant.kt     Offline accessibility-only assistance and guidance
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

Open Android Settings, tap the assistant bubble to open the menu, and choose Ask AI, Explain,
Guide Me or Privacy. Guide Me accepts a goal and shows one instruction at a time, followed
by **Check my screen**. Use **Select a control manually** for the previous picker and
touch-through highlights. Drag and release the bubble to dock it on either side without
changing its vertical position. Screenly never taps the target app. If screenshot capability
is not enabled after updating, re-enable Screenly in Android Accessibility Settings.

## Shared documentation

- [Codex instructions and ownership](AGENTS.md)
- [Milestones, parallel work, and collaboration workflow](docs/ROADMAP.md)
- [Actual architecture and proposed contracts](docs/ARCHITECTURE.md)
- [Verification procedures and result recording](docs/TESTING.md)
- [Existing M2 verification report](VERIFICATION.md)

## Integrated local AI backend

The `feat/local-ai` backend has been merged into this branch alongside Android guidance UI.
See [LOCAL_AI.md](docs/LOCAL_AI.md) for the pinned offline model, provisioning and inference testing;
[NAVIGATION_BACKEND.md](docs/NAVIGATION_BACKEND.md) for the independent model navigation/evaluation harness;
and [M4_PLAN.md](docs/M4_PLAN.md) for its intended integration boundaries.
Guide Me now connects these components through validated Android snapshots and a lifecycle-managed
controller. Ask and Explain retain their accessibility-label behavior. See
[GUIDANCE_INTEGRATION.md](docs/GUIDANCE_INTEGRATION.md) for the implementation and emulator evidence;
physical-device verification remains outstanding.
