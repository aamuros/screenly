# Screenly 2.0: Offline-first implementation and acceptance plan

Branch: `feat/screenly2-offline-foundation`, based on the verified UI inspector
`fix/ui-extraction-parent-rows`. This is an **integration preview**, not a
production-certified Android assistant.

## Implemented here

* **Structured accessibility catalog.** Parent-child relationships from Android
  are preserved. A clickable parent uses its child's title/summary, keeping
  the original parent index and pixel bounds. Nested switches are independent
  controls. Duplicate names are preserved as ambiguous rather than deduplicated.
  The Debug Explain panel now displays switch ON/OFF state.
* **User-guided navigation.** The bounded rule fallback selects only supported
  controls, distinguishes settings rows from switches, refuses to flip an already
  satisfied switch, and recognizes common dark-mode and brightness wording.
  A permitted step is highlighted; Screenly never taps another app.
* **Persistent Guide Me goal.** Closing the overlay leaves the live session in
  the accessibility service. The explicitly entered active goal and numeric step
  are saved privately in SharedPreferences, excluded from backup. No UI snapshot,
  password, screenshot or chat transcript is written to this persistent store.
  On process recreation the app re-evaluates the current screen instead of
  resurrecting an old highlight. Cancel/completion/privacy clear deletes the goal.
* **Bundled offline OCR.** `com.google.mlkit:text-recognition:16.0.1` runs on
  explicit screenshot-based actions. Bitmap buffers are released after async OCR;
  OCR text is transient and is not automatically considered a tappable control.
  A screenshot failure falls back to accessibility. Explain's **Refresh screen**
  runs OCR and shows textual evidence. Screenshots do not enter the model store.
* **Optional real text generation.** The app integrates the repo's previously
  evaluated Gemma 3 1B INT4 `.litertlm` backend through LiteRT-LM 0.10.2.
  `MainActivity` allows the developer/user to import the *exact* licensed model
  through the Android document picker; it streams, verifies SHA-256 and byte size,
  and publishes privately only after verification. Ask AI can produce a bounded
  text answer from the actual local model, clearly marked as *unverified prose*.
  When unavailable, it falls back to rule-based and OCR-assisted replies. The
  model is not included in Git or the APK and is never downloaded in-app.
* **Monochrome launcher icon.** Android adaptive icon uses Screenly's existing
  black/white floating-bubble artwork rather than Android's green template. The
  actual launcher safe zone and OEM icon masks still need visual checks.

## Known limitations: DO NOT describe as solved

* The production model/runtime **has not been chosen**. Qwen3-0.6B GGUF and
  SmolLM2-360M are *benchmark candidates*, not integrated models. This branch
  runs the existing Gemma option only when a verified artifact is imported.
* The historical LiteRT-LM ARM64 API 35 emulator SIGILL cannot be recovered by
  catching an exception. This branch disables native inference on matching
  ARM64 API 35+ emulators; real-device chipset validation is still required.
* Ask AI prose is **not a verified navigation plan**. Guide Me uses conservative
  accessibility-based controls; model output does not directly produce taps,
  highlights, or completion claims.
* OCR recognizes Latin-script text, not visual semantics or icons. Recognized
  text is not automatically associated with interactive controls. Some apps
  expose inaccessible or protected screens and must be safely declined.
* A 4 GB-RAM physical device benchmark, offline-native smoke test, latency test,
  power test, screenshot privacy test, and system lifecycle regression have
  **not been executed** as part of this branch. Automated unit tests and
  build workflow are provided but need actual execution.
* Model import uses a single temporary file in app-private storage, verifies
  its exact checksum, and may require approximately 1.2 GB available storage
  when replacing a model. Do not import a replacement while the accessibility
  service is currently using the existing model.

## Windows first build

```powershell
git status --short
git fetch origin
git switch --track origin/feat/screenly2-offline-foundation
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:installDebug --offline
```

If the first command reports local modifications, stash them before switching
branches. The first online build retrieves the new ML Kit, LiteRT-LM and coroutines
dependencies. Subsequent builds normally reuse the Gradle cache.

## First manual scenarios

1. Explain on Settings home: Network & internet appears as one actionable row,
   with its summary and original bounds.
2. Explain on Display & touch: Dark theme settings row and Switch appear as
   distinct controls; ON/OFF follows the actual switch state.
3. Explain -> Refresh: offline OCR identifies text and reports line count. Switch
   status still comes from accessibility; OCR-only text is never a tap target.
4. Guide Me, goal "Enable dark mode": highlight only an OFF switch, then manually
   flip it. An already-ON switch must not be highlighted as an enable action.
5. Guide Me, goal "Make the screen dark": run the same state checks.
6. Close the bubble panel; reopen Guide Me: goal remains. Navigate elsewhere and
   reopen: previous coordinates are never blindly reused.
7. Stop the service and restart: restore the goal, re-examine the new screen.
   On completion/cancel/Privacy -> Clear saved Guide Me goal, saved goal is gone.
8. Disable network. Without imported model: accessibility and bundled OCR still
   work. With verified model on supported hardware: Ask AI genuinely generates
   an offline answer; no network permission or external endpoint is used.
9. Test protected screens, keyboard focus, rapid open/close, rotation, screen
   lock, repeated screenshots, service revocation and app process death.
10. Compare recorded UI snapshots to manually labeled fixtures from 4 GB devices
    and third-party apps. Record failures instead of hiding/refusing them.

## Release acceptance gates (targets, not achieved results)

| Gate | Required outcome |
|---|---|
| Build & unit tests | APK compiles; JVM suite passes; no duplicate node-index mapping |
| UI mapping | >=95% actionable control precision in 100 manually annotated controls |
| Switch state | >=98% ON/OFF correctness over 100 real states |
| OCR | >=95% character accuracy on readable Latin-script labels |
| Navigation safety | 0 wrong accepted targets in 300 adversarial decisions |
| Task coverage | >=85% supported next-step decisions on held-out tasks; refusal tracked separately |
| Persistence | 30/30 panel-close, screen-change, restart and clear-history scenarios |
| Device budget | No OOM/crash on three 4 GB Android phones; loaded-process PSS under 1.5 GiB |
| Warm latency | P90 <=8 seconds for next-step inference on selected hardware |
| Privacy | Screenshots/text never sent over the network; passwords/sensitive views excluded |
| Long-run | 100 multi-step sessions without runtime crash or leaked panels |

No single synthetic fixture suite establishes generalization to arbitrary apps.
