# Screenly

Screenly is an Android assistant for navigating unfamiliar apps. The intended MVP observes
an app through accessibility, accepts a goal such as “Enable dark mode,” and uses local AI
to suggest one control at a time. Screenly validates and highlights the control; the user
performs the action, and Screenly observes the next screen. Inference must work offline.

## Current status

This integration combines `feat/offline-multimodal-navigation` and
`feat/on-device-ai-integration` without replacing the verified screenshot/planner
interfaces with the older text-only floating-panel implementation.

- **Local AI:** Gemma 3 1B INT4 text inference; optional Gemma 3n E2B screenshot
  inference on explicitly certified compatible devices. Both models are imported
  as verified local files and run without a cloud connection. **Neither model is
  bundled in the APK.**
- **Ask AI / Explain:** Answers are grounded in captured accessibility information,
  with permitted screenshot vision when available. Historical answers are held in
  memory while processing. Images are never written to disk or uploaded.
- **Guide Me:** A bounded goal controller, validated actionable targets, strict
  snapshot freshness checks and touch-through highlights. Users do the tapping;
  Screenly does not automatically operate other apps.
- **Saved history:** Recent text questions/answers, the last guide goal and prior
  displayed instructions remain in private no-backup storage across overlay
  recreation. The saved goal can be rechecked from a **fresh** screen, but previous
  coordinates, image bytes, native model state and selection tokens are not resumed.
  **Privacy → Clear assistant session** deletes the saved text.
- **Launcher:** Polished monochrome home screen; real Accessibility settings
  shortcut, local text/vision model import, and status cards. The floating assistant
  hides over Screenly's own launcher and shows in the app being navigated.
- **Visual identity:** Launcher, bubble, and home use the original
  `design/floating-assistant/bubble-icon.png` artwork.

The app is **INTEGRATED, NOT PRODUCTION VERIFIED**. The earlier physical-device
image compatibility check on an Infinix HOT 40 Pro and other prior model/UI
verifications apply to their documented versions, not to this merged release.
This branch still needs an updated physical-device acceptance pass, memory/latency
benchmarks (especially for 4 GB RAM), OEM regression tests and live multi-step
quality measurement. See [multimodal evidence](docs/OFFLINE_MULTIMODAL.md),
[existing integration evidence](docs/INTEGRATION.md) and
[the test plan](docs/TESTING.md).

## Stack and scope

- Kotlin, Compose launcher, native Android AccessibilityService and WindowManager
  floating overlay; one Gradle application module.
- CPU LiteRT-LM **0.10.2** text inference with optional certified native
  vision, kotlinx.coroutines, locally verified model provisioning and a strict
  ScreenPlanner/GuidanceController boundary.
- Screenshots are transient and privacy-gated; no cloud inference, Internet
  permission, automatic taps, account sign-in or remote database.
- Minimum supported platform is Android 11/API 30. Native vision depends on
  the device and exact model/runtime; importing alone never enables an
  unverified vision runtime.

## Code map

```text
app/src/main/java/com/screenly/app/
  MainActivity.kt                Launcher actions and model provisioning
  ScreenlyHome.kt                Polished status dashboard and onboarding
  ScreenlyAccessibilityService.kt  Privacy-aware accessibility extraction
  ScreenlyOverlay.kt             Floating bubble, captured answers and Guide Me
  AssistantHistoryStore.kt       Bounded private local conversation/step history
  GuidanceController.kt          Goal/revision/request validation
  ScreenImageCapture.kt          Ephemeral screenshot crop and secure-gate policy
  ai/                             Local Gemma inference and constrained planner
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
Open Screenly → **Enable Screenly** → **Screenly** under downloaded/installed
services → enable **Use Screenly**. If sideloading is restricted, use system **App info** →
**Allow restricted settings**, then retry. No “Display over other apps” permission is needed.

Open Android Settings, tap the assistant icon, choose **Guide Me**, and enter a goal.
Manually tap each verified highlighted control. Screen changes discard old selections and
trigger a fresh check. Guide Me also offers the original manual picker. Screenly's own
activity and the lock screen hide overlays; unavailable roots clear highlights and show waiting.

## Integrating the two feature branches

The working merge branch is `integration/multimodal-screenly-main`. Resolve
the two independent `ScreenlyOverlay`, `MainActivity` and assistant state
implementations by retaining the multimodal ScreenPlanner pipeline and
bringing across the compatible monochrome home, launcher icons and text-only
history adapter. It intentionally does **not** copy the older
`ScreenlyFeaturePanel` or `OnDeviceAssistant` classes, which would create
a competing assistant implementation instead of enabling vision. Prior feature
branches remain intact.

## Shared documentation

- [Codex instructions and ownership](AGENTS.md)
- [Milestones, parallel work, and collaboration workflow](docs/ROADMAP.md)
- [Actual architecture and proposed contracts](docs/ARCHITECTURE.md)
- [Verification procedures and result recording](docs/TESTING.md)
- [Existing M2 verification report](VERIFICATION.md)
- [M3 runtime, model provisioning and verification](docs/LOCAL_AI.md)
