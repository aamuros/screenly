# M3 local inference: implementation and pending phone validation

`feat/local-ai` adds an isolated `ai/LocalInference` component and an opt-in Android
instrumented smoke test. It does not connect inference to the activity, accessibility service,
overlay or a planner. **M3 is IMPLEMENTED — UNVERIFIED:** no physical phone is connected,
no model has been provisioned here, and no real/offline inference result or timing exists.

## Runtime and model selection

Sources inspected on 2026-10-09:

- [Google's Android Kotlin guide](https://ai.google.dev/edge/litert-lm/android).
- [Official Kotlin guide and examples at v0.10.2](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.10.2/docs/api/kotlin/getting_started.md),
  including the CPU engine configuration and Gemma3-1B-IT demo.
- [Published Google Maven versions](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml)
  and [0.10.2 POM](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.10.2/litertlm-android-0.10.2.pom).
- [Publisher model card](https://huggingface.co/litert-community/Gemma3-1B-IT) and
  [artifact metadata](https://huggingface.co/api/models/litert-community/Gemma3-1B-IT?blobs=true).

| Item | Selected value / evidence |
| --- | --- |
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.10.2`, pinned; official Kotlin API |
| Async support | `kotlinx-coroutines-android:1.9.0`, explicitly declared at the runtime POM's version |
| Model | Gemma 3 1B Instruct, `gemma3-1b-it-int4.litertlm` from `litert-community/Gemma3-1B-IT` |
| Source revision | `a6306a4e292016480083b73b8dc6f3f939ae04c3` |
| Format / quantization | `.litertlm`, INT4; select this file, not `.task`, GGUF, safetensors, or an SoC-specific NPU export |
| Size | 584,417,280 bytes (about 557.3 MiB), from publisher LFS metadata |
| SHA-256 | `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be` |
| License | [Gemma terms](https://ai.google.dev/gemma/terms); gated publisher download requires the developer's acceptance |
| Backend | CPU, four threads; no GPU/NPU initialization or added native-library manifest declarations |
| Context | Engine limit of 1,024 total input/output tokens; prompts limited to 1,000 characters |
| Private model path | `context.noBackupFilesDir/models/gemma3-1b-it-int4.litertlm`; excluded from automatic backup |

`0.18.0` was the latest stable Maven release when inspected, but its POM requests Kotlin
2.4.0 and coroutines 1.11.0. The smaller integration pins `0.10.2`, whose API/example and
dependencies fit the existing compiler without upgrading Developer 1's toolchain.

The versioned Google example verifies `.litertlm` support and references this Gemma model
family. The publisher identifies this exact generic INT4 export, also named
`Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm` with the same hash. Neither inspected
source states a minimum runtime version for this exact binary. Its gated bytes/header were
not downloaded or inspected. **Exact artifact compatibility with 0.10.2 and the configured
context limit remains a phone-test gate**, not a claimed inference result. Do not rename a
`.task` file to `.litertlm` or substitute a different export under this filename.

The downloaded 0.10.2 AAR was inspected: its manifest declares API 23 minimum, and its native
libraries are packaged for `arm64-v8a` and `x86_64` only. Screenly's API 30 minimum is unchanged.
A 32-bit-only phone cannot run this runtime. A 64-bit ABI/API match alone does not establish
RAM, chipset, thermal or latency suitability. The publisher reports CPU benchmarks on an
S24 Ultra; those are historical publisher results, not Screenly measurements or a minimum-RAM
guarantee. The demonstration phone's model/API/ABI/RAM/chipset and available memory are unknown.

## Component lifecycle

Create one `LocalInference(context)` per owning session. Call suspending `initialize()`,
then `generate(text)` as needed, and always call suspending `close()` in `finally`.
Initialization, SHA-256 verification, conversations and native cleanup run on `Dispatchers.IO`.
A mutex serializes calls; repeated initialization retains the same engine. Each generation
uses and closes a fresh conversation. No live UI nodes/events are passed to the component.

Missing/unreadable, partial and wrong-hash files fail before native initialization with a
diagnostic naming the missing path/provisioning action or integrity problem. Native initialization,
generation and linkage errors propagate as `LocalInferenceException` with their cause. Calls
before initialization or after close fail explicitly; close is idempotent and runs even in a
cancelled coroutine. Empty generated text fails instead of substituting a canned response.

This small harness uses the official synchronous native API on IO. Coroutine cancellation
does **not** preempt a blocking JNI initialization/generation call: native work finishes before
the cancellation is delivered and conversations are closed. A successfully initialized engine
remains owned until `close()`, including cancellation during loading. Do not treat coroutine
timeouts as native interruption. Native cancellation behavior is not claimed to be tested.
Generation is bounded by the total context, with no separate output-token API in this version.

## Reproducible debug provisioning over USB

1. On the development computer, review/accept the Gemma terms at the publisher page. In
   **Files and versions**, select revision `a6306a4e292016480083b73b8dc6f3f939ae04c3` and
   download exactly `gemma3-1b-it-int4.litertlm`. This is developer provisioning; the Android
   app contains no download/authentication code and does not bundle the model in its APK.
2. Connect an unlocked physical phone with authorized USB debugging. Record compatibility
   information, then install the debug application and test APKs:

   ```sh
   SCREENLY_SERIAL='replace-with-device-serial'
   SCREENLY_MODEL='/absolute/path/to/gemma3-1b-it-int4.litertlm'
   adb devices -l
   adb -s "$SCREENLY_SERIAL" shell getprop ro.product.model
   adb -s "$SCREENLY_SERIAL" shell getprop ro.build.version.sdk
   adb -s "$SCREENLY_SERIAL" shell getprop ro.product.cpu.abilist
   adb -s "$SCREENLY_SERIAL" shell getprop ro.soc.manufacturer
   adb -s "$SCREENLY_SERIAL" shell getprop ro.soc.model
   adb -s "$SCREENLY_SERIAL" shell cat /proc/meminfo
   adb -s "$SCREENLY_SERIAL" shell df -h /data
   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
   adb -s "$SCREENLY_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s "$SCREENLY_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   wc -c < "$SCREENLY_MODEL"
   shasum -a 256 "$SCREENLY_MODEL"
   ```

   Require the exact byte count and hash above before continuing. Allow storage for the
   APKs, the model and runtime cache; RAM consumption is greater than model file size and
   must be measured on the phone. Some OEMs do not expose SoC properties; record chipset
   from the device specification in that case.
3. Copy into app-private storage with `run-as` (debug APK only). The single quoted remote
   redirection runs as the application, not the ADB shell UID. A partial copy is renamed
   only after transfer finishes:

   ```sh
   adb -s "$SCREENLY_SERIAL" shell run-as com.screenly.app mkdir -p no_backup/models
   adb -s "$SCREENLY_SERIAL" shell -T "run-as com.screenly.app sh -c 'cat > no_backup/models/gemma3-1b-it-int4.litertlm.partial'" < "$SCREENLY_MODEL"
   adb -s "$SCREENLY_SERIAL" shell run-as com.screenly.app sha256sum no_backup/models/gemma3-1b-it-int4.litertlm.partial
   adb -s "$SCREENLY_SERIAL" shell run-as com.screenly.app wc -c no_backup/models/gemma3-1b-it-int4.litertlm.partial
   ```

   Check the device hash/size against the same expected values, then publish the file:

   ```sh
   adb -s "$SCREENLY_SERIAL" shell run-as com.screenly.app mv no_backup/models/gemma3-1b-it-int4.litertlm.partial no_backup/models/gemma3-1b-it-int4.litertlm
   ```

   Stop inference before replacing its model. Installation with `-r` normally preserves
   private data; uninstall/clear-data removes the model and requires reprovisioning.
   `run-as` is a development procedure, not a release import UI.

## Isolated inference and offline acceptance

The test runs without launching MainActivity or manipulating accessibility/overlay controls.
It fails on missing/bad models when explicitly enabled; default instrumentation runs skip it.

```sh
adb -s "$SCREENLY_SERIAL" shell am instrument -w -r \
  -e class com.screenly.app.ai.LocalInferenceSmokeTest \
  -e localAiSmoke true \
  com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
```

It records device/API/ABIs, runtime/backend/model identity, initialization success/time,
two real generations of **"What Android setting controls font size?"**, each response/time,
failure phase/elapsed time/cause and cleanup status. Initialization timing includes the full
integrity check. Repeated initialization reuses the engine; generation calls start fresh
conversations. Nonblank text establishes generation only: review the actual answer separately
for correctness. Metrics appear as `INSTRUMENTATION_STATUS` fields and `ScreenlyLocalAI` Logcat.
Preserve the complete instrumentation output, including JUnit's final failures/skips, rather
than inferring success from an intermediate `PASS` metric.

For the **final physical offline test**, use USB ADB (not wireless ADB), enable airplane mode
in Android Settings, explicitly disable Wi-Fi and mobile data, and record their actual state.
Restart the process and rerun with the offline guard:

```sh
adb -s "$SCREENLY_SERIAL" shell settings get global airplane_mode_on
adb -s "$SCREENLY_SERIAL" shell settings get global wifi_on
adb -s "$SCREENLY_SERIAL" shell settings get global mobile_data
adb -s "$SCREENLY_SERIAL" shell dumpsys connectivity
adb -s "$SCREENLY_SERIAL" shell am force-stop com.screenly.app
adb -s "$SCREENLY_SERIAL" shell am instrument -w -r \
  -e class com.screenly.app.ai.LocalInferenceSmokeTest \
  -e localAiSmoke true -e requireOffline true \
  com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
```

The guard requires global values `1`, `0`, `0` before/after inference; it is a settings check,
not packet capture. Unknown/OEM-specific values fail conservatively and need investigation,
especially on dual-SIM devices. The APK still requests no network permission.

Repeat the offline command for a second process load with the runtime's cache retained;
distinguish first load, subsequent process load and same-engine reuse. Record run counts,
APK SHA-256, model SHA-256, output, initialization/response times, errors and memory from
`adb shell dumpsys meminfo com.screenly.app` while the engine is loaded. Instrumentation's
process ends after the test; sampling afterward will not measure loaded-engine memory.
Test close/reload with a new component/process. Recheck the existing physical M1/M2 checklist
in [TESTING.md](TESTING.md); source preservation and a build do not prove phone functionality.
Restore connectivity and accessibility service state after testing if force-stop changed it.

## Verification record — 2026-10-09

Tester: Codex for Developer 2. Baseline: `e754f7b`; implementation revision is the commit
containing this report on `feat/local-ai`. Host: macOS, JDK 21, existing Android SDK/Gradle configuration.

| Check | Expected / actual | Evidence / limitation |
| --- | --- | --- |
| App build | PASS | `:app:assembleDebug`; final combined command exited 0 |
| Host unit tests | PASS: 21 tests, 0 failures/errors/skips | Existing 15 tests plus 6 model integrity/failure/lifecycle checks; no fake JNI inference; XML reports in `app/build/test-results/testDebugUnitTest` |
| Lint | PASS: 0 errors, 12 warnings | `app/build/reports/lint-results-debug.xml`; all warnings are dependency/plugin update notices |
| Instrumentation APK compilation | PASS | `:app:assembleDebugAndroidTest`; compilation is not execution |
| ADB physical device | Phone available / NOT AVAILABLE | `adb devices -l` returned an empty list |
| Exact model native load/generation | NOT RUN | No phone or provisioned licensed model |
| Physical offline restart/inference | NOT RUN | Wi-Fi/mobile-data-disabled inference was not executed |
| Initialization/response/memory benchmark | NOT MEASURED | No invented durations, tokens/sec or RAM results |
| Existing Android UI features | Physical regression NOT RUN | Developer 1's source unchanged; historical emulator evidence remains in [VERIFICATION.md](../VERIFICATION.md) |

Fresh command:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain --quiet
```

The first run built the app but failed JUnit validation because a Kotlin test inferred a
non-void return type. Explicit `runBlocking<Unit>` corrected it before the successful rerun.
No compiler/type errors remain. There were no device, emulator, browser or UI runs.

Built debug APK: **78,276,841 bytes**, SHA-256
`a04baf785902ddba57d3783c30da63572712805ac26fd04c09fab6745516f053`.
Test APK: **1,453,484 bytes**, SHA-256
`c1aa7a5c3c4cff526cef4b489759158a9ae637b839679537c2f5512d449c4083`.
The debug APK includes both runtime ABIs; these are APK sizes, not memory measurements.

Files added/modified:

- `app/src/main/java/com/screenly/app/ai/LocalModel.kt`, `LocalInference.kt`:
  exact artifact validation and serialized local engine lifecycle/text generation.
- `app/src/test/java/com/screenly/app/ai/LocalInferenceTest.kt` and
  `app/src/androidTest/java/com/screenly/app/ai/LocalInferenceSmokeTest.kt`:
  host integrity/lifecycle tests and isolated real-model instrumentation.
- `app/build.gradle.kts`, `gradle/libs.versions.toml`: pinned runtime and async dependency.
- `README.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`, `docs/TESTING.md`,
  `docs/LOCAL_AI.md`: current status, usage/provisioning, evidence and remaining gates.

`MainActivity`, accessibility, overlays, observation/element contracts and the manifest are
unchanged. Gradle/documentation edits are shared-owner integration points for review.

Remaining gates (Developer 2, with Developer 1 for UI regressions): obtain the licensed artifact,
confirm exact binary/runtime pairing on an identified 64-bit phone, record RAM/chipset/free
storage and real offline load/generation/cleanup/reload results, measure performance, and agree
budgets. **Do not proceed to M4 navigation planning until M3's physical acceptance is validated.**
