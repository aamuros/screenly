# On-device AI integration

This branch combines `feat/android-guidance` with the LiteRT-LM 0.10.2 and
navigation-validation components from `feat/local-ai`. The floating panels now
invoke on-device text generation asynchronously when a verified model is
installed. Without a model, the existing offline accessibility rules remain active.

## Exact model and offline provisioning

- **Model:** [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT),
  file `gemma3-1b-it-int4.litertlm` from revision
  `a6306a4e292016480083b73b8dc6f3f939ae04c3`.
- **Model size:** 584,417,280 bytes.
- **SHA-256:** `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`.
- **License:** Google's Gemma terms. Accept terms at the model publisher before downloading.
- **Runtime:** CPU LiteRT-LM 0.10.2, ARM64 or x86_64. Android 11/API 30 minimum.
- **Storage:** `noBackupFilesDir/models/gemma3-1b-it-int4.litertlm`.

The model is NOT bundled in the APK. It is much larger than an ordinary GitHub
source file. Provision it once, including via offline USB or local file transfer:

1. On a connected development computer, accept the publisher terms and download the
   exact file above. Check its SHA-256 with `shasum -a 256 gemma3-1b-it-int4.litertlm`.
2. Transfer the file to the phone, for example with
   `adb push gemma3-1b-it-int4.litertlm /sdcard/Download/`.
3. Open Screenly, press **Import local AI model**, choose the transferred file,
   and allow the import and full SHA-256 verification to finish.
4. Enable Screenly's AccessibilityService. Open an app and test
   **Ask AI**, **Explain**, and **Guide Me**.
5. Turn on airplane mode, force-stop and reopen Screenly, then test again.
   No network permission or on-device model download is required.

An incorrect, incomplete or tampered model is rejected before installation or loading.
Importing uses a streaming file copy to avoid loading hundreds of MB into Java heap.

## Product behavior and boundaries

- **Ask AI:** answers questions grounded in sanitized Android accessibility labels.
- **Explain:** generates a short explanation of visible accessible controls.
- **Guide Me:** constrained response parsing (`TAP:index` or `NONE`),
  validated clickable elements, local rule fallback, explicit user action,
  and existing overlay highlighting; no automatic taps.
- **Privacy:** text generation stays inside the application and is ephemeral.
  The model is loaded from app-private no-backup storage.
- **Screenshot:** taken on request and immediately discarded; **this model is
  text-only, so it does not interpret screenshot pixels**. If capture is blocked,
  text-based accessibility assistance can still work.
- **Safety:** output is discarded if a screen revision or request changes.
  No on-screen navigation is treated as proof of task completion.
  Validation remains Android-side rather than trusting model-produced coordinates.
- **Limitations:** a native runtime may be incompatible or too slow on specific
  devices. The existing `feat/local-ai` evidence reported an API 35 ARM64 emulator
  crash and only API 30 ARM64 emulator success. Physical-device and low-RAM
  verification are still necessary. The model may also hallucinate explanations.

## Verification

GitHub Actions performs:
```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For genuine offline acceptance, also measure cold-start latency, repeated guidance
accuracy, peak RSS, RAM pressure and battery use on 2 GB and 3 GB phones.
The current 584 MB model cannot be claimed suitable for very old phones without
hardware measurements. For lower-resource production use, benchmark a smaller
135M-360M text model and a compatible native GGUF runtime behind the same app
interface, or retain the accessibility-only mode.

This branch is an **integrated prototype**, not a validated general-release
offline vision assistant. Do not claim otherwise until the physical-device,
model distribution and screenshot-vision acceptance gaps are resolved.
