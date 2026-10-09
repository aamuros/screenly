# Screenly

Screenly is an Android assistant for navigating unfamiliar apps. The intended MVP observes
an app through accessibility, accepts a goal such as “Enable dark mode,” and uses local AI
to suggest one control at a time. Screenly validates and highlights the control; the user
performs the action, and Screenly observes the next screen. Inference must work offline.

## Current status

This branch integrates the `feat/android-guidance` floating assistant with the
CPU LiteRT-LM local text runtime and model-validated navigation from `feat/local-ai`.
Ask AI, Explain, and Guide Me use local inference when the exact verified model has
been imported in the main Screenly activity. Without it, accessibility-only guidance
works offline. Screenshot capture is optional and ephemeral; the current model does
**not** interpret image pixels. The 584 MB model is distributed separately.
See [on-device AI setup](docs/ON_DEVICE_AI.md).

M1 (accessibility) and M2 (overlays) are **IMPLEMENTED — UNVERIFIED** against full acceptance:
automated and API 35 Pixel Tablet emulator evidence exists, but physical-device verification
is outstanding. M0 contracts remain **IN PROGRESS**; M3–M6 are **INTEGRATED BUT NOT PHYSICALLY VERIFIED** on this branch. See the
[roadmap](docs/ROADMAP.md) and preserved [verification report](VERIFICATION.md).

## Stack and scope

- Implemented: Kotlin, native Android, Compose activity, AccessibilityService,
  AccessibilityNodeInfo, WindowManager accessibility overlays, one Gradle `app` module.
- Integrated: CPU LiteRT-LM 0.10.2, Gemma 3 1B INT4 text inference and asynchronous guidance.
- Not yet verified: low-RAM devices, pixel-level vision and general production readiness.
- No backend, cloud inference or automatic taps. No network permission is requested.
  The model is imported once from local storage and verified before on-device inference.

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
  OnDeviceAssistant.kt             Bounded text prompts, model orchestration and target validation
  ai/                              Native LiteRT-LM runtime and navigation decision backend
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

## Persistent offline assistant sessions

Ask AI retains up to 24 recent messages in app-private, no-backup storage and
uses recent exchanges to answer follow-up questions. Guide Me retains the goal,
active instruction and up to 12 recent steps when you close the panel, navigate
between apps, lock/unlock or restart the AccessibilityService. Open the bubble
and choose Guide Me to resume; use **Check my screen** to continue. A restored
guide never reuses a previously highlighted element's screen coordinates.

**Privacy:** This small text history stays on the device in private storage and
is not automatically deleted when the service stops. Use **Privacy** →
**Clear chat and guide history** to delete it, or uninstall Screenly.
Screenshots and raw accessibility snapshots are never persisted.
The on-screen panel also preserves scroll position during routine updates.

## Offline model setup

Transfer the verified `gemma3-1b-it-int4.litertlm` file to the device and use
**Import local AI model** in Screenly. See [exact artifact and offline provisioning](docs/ON_DEVICE_AI.md).
The APK contains no model weights. AI generation requires a compatible device and an
installed verified model; without it the rule-based mode remains available.

## Shared documentation

- [Codex instructions and ownership](AGENTS.md)
- [Milestones, parallel work, and collaboration workflow](docs/ROADMAP.md)
- [Actual architecture and proposed contracts](docs/ARCHITECTURE.md)
- [Verification procedures and result recording](docs/TESTING.md)
- [Existing M2 verification report](VERIFICATION.md)
