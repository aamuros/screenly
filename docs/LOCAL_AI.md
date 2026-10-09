# M3 local inference: implementation and pending phone validation

**Current backend, 2026-10-10:** [NavigationEngine and its offline evaluation](NAVIGATION_BACKEND.md)
are now implemented independently of the pending Planner contracts. The runtime/model
configuration and `LocalInference`/`LocalModel` source remain unchanged. Historical statements
below that require C0 before independent navigation evaluation are superseded by the current
user-authorized scope; shared adapters and Android integration still require agreement.

`feat/local-ai` adds an isolated `ai/LocalInference` component and an opt-in Android
instrumented smoke test. It does not connect inference to the activity, accessibility service,
overlay or a planner. **M3 is IMPLEMENTED — UNVERIFIED against full physical acceptance.**
The exact licensed model is now downloaded and verified. Real CPU inference and two offline
process restarts **PASS on an Android 11/API 30 ARM64 emulator**, with measured latency and
a loaded-process memory sample. The API 35 ARM64 emulator crashes in native CPU dispatch;
physical behavior and M1/M2 phone regressions remain unverified. Current results and raw
evidence are recorded in the final section below.

**Current development scope:** Use the working `Screenly_M3_API30` AVD for all
device-dependent development. Phone absence does not block emulator-based M4 progress.
[M4 preparation](M4_PLAN.md) now includes isolated prompt/parser/validation/rule helpers
and synthetic fixtures; shared contracts/adapters still await joint approval. Historical
physical-first next-step instructions below are superseded for this development task;
physical acceptance remains separately unverified. [Fresh M4 preparation checks](TESTING.md#m4-emulator-development-preparation--2026-10-09)
also rerun the existing smoke test; this does not measure model navigation accuracy.

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
source states a minimum runtime version for this exact binary. At initial selection its gated
bytes/header had not been downloaded. The now-verified artifact has `LITERTLM` container version
1.0.0; genuine API 30 CPU generation demonstrates compatibility with 0.10.2 and the configured
context limit on that guest. **Physical compatibility remains unverified.** Do not rename a
`.task` file to `.litertlm` or substitute a different export under this filename.

The downloaded 0.10.2 AAR was inspected: its manifest declares API 23 minimum, and its native
libraries are packaged for `arm64-v8a` and `x86_64` only. Screenly's API 30 minimum is unchanged.
A 32-bit-only phone cannot run this runtime. A 64-bit ABI/API match alone does not establish
RAM, chipset, thermal or latency suitability. The publisher reports CPU benchmarks on an
S24 Ultra; those are historical publisher results, not Screenly measurements or a minimum-RAM
guarantee. The demonstration phone's model/API/ABI/RAM/chipset and available memory are unknown.
The tested API 30 guest and failing API 35 guest are detailed below.

## Component lifecycle

Create one `LocalInference(context)` per owning session. Call suspending `initialize()`,
then `generate(text)` as needed, and always call suspending `close()` in `finally`.
Initialization, SHA-256 verification, conversations and native cleanup run on `Dispatchers.IO`.
A mutex serializes calls; repeated initialization retains the same engine. Each generation
uses and closes a fresh conversation. No live UI nodes/events are passed to the component.
Responses are extracted from `Message.contents.contents`, selecting only `Content.Text.text`
in order. Non-text content and channels are excluded; empty/whitespace-only answer text fails.
The component does not rely on `Message.toString()`.

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

## Initial implementation verification — 2026-10-09 (historical)

Tester: Codex for Developer 2. Baseline: `e754f7b`; implementation revision: `f25632d` on
`feat/local-ai`. Host: macOS, JDK 21, existing Android SDK/Gradle configuration.

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

## Follow-up verification — 2026-10-09 (before model access; historical)

Tester: Codex for Developer 2. Baseline: `f25632d` on `feat/local-ai`; the follow-up revision is
the commit containing this report. The user requested an Android emulator instead of a physical device.
Verdict: **IMPLEMENTED — UNVERIFIED**; model-dependent verification is blocked by the missing
licensed artifact. Emulator evidence does not establish physical-phone functionality.

### Confirmed issue and fix

`generate()` called `Message.toString()`. Inspection of the pinned 0.10.2 AAR with `javap`
confirmed `Message.getContents()`, `Contents.getContents()` and `Content.Text.getText()`;
the versioned source shows `toString()` joins all content values. Text-only responses are
rendered correctly by that version, but non-text diagnostic strings can incorrectly pass
the old nonblank-response check. The fix explicitly concatenates only text parts and rejects
absent/blank text. Three regression tests cover ordered text with non-text/channel exclusion,
non-text-only output and empty/whitespace-only output. They construct API values without
running JNI or processing images; these tests are not evidence of real model generation.

`LocalModel` integrity checks and serialized initialization/reuse/cleanup were inspected;
no additional confirmed implementation defect was found in those paths. No dependencies,
manifest permissions, accessibility/overlay code or observation contracts were changed.

### Fresh automated results

Command (exit **0**):

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --console=plain --quiet
```

| Check | Actual result | Evidence / limitation |
| --- | --- | --- |
| `:app:assembleDebug` | PASS | Debug APK built; source fix compiled |
| `:app:testDebugUnitTest` | PASS: 24 tests, 0 failures/errors/skips | 15 existing tests plus 9 local-AI tests; XML in `app/build/test-results/testDebugUnitTest` |
| `:app:lintDebug` | PASS: 0 errors, 12 warnings | XML in `app/build/reports/lint-results-debug.xml`; 2 plugin, 8 dependency and 2 newer-version notices |
| `:app:assembleDebugAndroidTest` | PASS | Test APK compiled; smoke test was not executed |
| Diff / documentation consistency | PASS | `git diff --check`; relative documentation links checked |

Debug APK: **78,297,976 bytes**, SHA-256
`af5844a4c88e854c18c737497938bbe67afa07587cec2724387839a0e62f1899`.
Test APK: **1,453,484 bytes**, SHA-256
`c1aa7a5c3c4cff526cef4b489759158a9ae637b839679537c2f5512d449c4083`.
Fresh tests exercised response extraction and missing/corrupt-file/lifecycle policies;
they did not execute native inference. Existing UI source and unit regressions are preserved;
no new UI functionality or physical M1/M2 checks were performed.

### Emulator setup and compatibility observations

Started the existing AVD without wiping data:

```sh
/Users/aamuros/Library/Android/sdk/emulator/emulator -avd Medium_Tablet -no-window -no-audio -no-boot-anim
adb devices -l
```

The emulator fell back to a cold boot because its saved snapshot renderer differed; boot
completed successfully. ADB reported `emulator-5554` in authorized `device` state.

| Specification | Observed value |
| --- | --- |
| AVD / reported model | `Medium_Tablet` / Google Pixel Tablet |
| Android / API | Android 15 / API 35; `sys.boot_completed=1` |
| ABI | `arm64-v8a` |
| SoC / hardware | `ro.soc.manufacturer=AOSP`, `ro.soc.model=ranchu`, `ro.hardware=ranchu`; virtual hardware, not a physical chipset |
| Emulator identity | `ro.boot.qemu=1` |
| Guest total memory | `MemTotal: 4015044 kB` (about 3.83 GiB) |
| Guest available memory | `MemAvailable: 2103320 kB` (about 2.01 GiB at inspection, not model memory use) |
| Guest free memory | `MemFree: 681080 kB` |
| Available `/data` storage | 3.8G reported by `df -h /data` |
| Runtime / candidate | LiteRT-LM 0.10.2; CPU, four threads, 1,024 total tokens; exact INT4 model/hash above |

The cached pinned AAR was freshly inspected: API 23 minimum and `arm64-v8a`/`x86_64` native
libraries. The guest meets the API/ABI packaging requirements. This does not verify native
loading, exact model compatibility, sufficient inference memory or phone performance.

### Missing model and deferred inference

Spotlight filename search and targeted searches of Downloads, Documents, Desktop,
AndroidStudioProjects and `~/.cache/huggingface/hub` found no `.litertlm` file. Those are the
searched locations, not a claim that every filesystem location was scanned. The device check:

```sh
adb -s emulator-5554 shell run-as com.screenly.app ls -l no_backup/models/gemma3-1b-it-int4.litertlm
```

returned **No such file or directory**. Therefore the actual model's size/SHA-256 could not
be checked, and no replacement/download/license bypass was attempted. In accordance with
the instruction to stop at unavailable model setup, provisioning, new APK installation and
all model-dependent smoke-test steps are deferred.

| Required result | Actual result |
| --- | --- |
| Native initialization | NOT RUN |
| Two real generations / actual generated responses | NOT RUN; no responses available |
| Engine reuse and native resource cleanup | NOT RUN; source/host lifecycle checks only |
| Initialization / generation latency | NOT MEASURED |
| Loaded-engine memory / native crashes / inference failures | NOT OBSERVED; no inference attempted |
| Offline process restart and repeated inference | NOT RUN |
| Physical phone tests | NOT RUN; current request uses an emulator |

The guest's connectivity settings were read: `airplane_mode_on=0`, `wifi_on=1`,
`mobile_data=1`. The emulator is not configured for offline acceptance. Connectivity settings
were left unchanged while model setup is blocked; there is no offline-inference PASS or skipped
test reported as success.

To unblock, sign in at [the publisher page](https://huggingface.co/litert-community/Gemma3-1B-IT),
review/accept the Gemma license and download `gemma3-1b-it-int4.litertlm` at revision
`a6306a4e292016480083b73b8dc6f3f939ae04c3`. Save it locally and provide its path. Require exactly
**584,417,280 bytes** and SHA-256
`1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`.
Then install the newly built APKs, provision with `SCREENLY_SERIAL=emulator-5554` using the
procedure above, run the enabled smoke test and review both generated answers. Configure and
verify offline state, restart the process and repeat the offline test twice, preserving full
instrumentation results and measured timings. Physical acceptance remains a separate gate.
**M4 is not ready and has not been started.**

Follow-up files: `ai/LocalInference.kt`, `ai/LocalInferenceTest.kt`, this report,
`docs/ROADMAP.md` and the current unit-coverage count in `docs/TESTING.md`.

## Completed emulator inference checks — 2026-10-09

Tester: Codex for Developer 2. Baseline: `f25632d` plus the typed-text extraction fix recorded
above, on `feat/local-ai`. The user completed Hugging Face CLI browser authorization and
accepted the model's license/access gate. No cloud inference was used. Developer downloads
and Android SDK setup occurred on the host; the Android application remains local-only.

### Model and APK provisioning: PASS

Downloaded the exact revision using the Hugging Face CLI:

```sh
uvx --from huggingface_hub hf download litert-community/Gemma3-1B-IT gemma3-1b-it-int4.litertlm \
  --revision a6306a4e292016480083b73b8dc6f3f939ae04c3 \
  --local-dir /Users/aamuros/Downloads/screenly-models --quiet
```

Host file: `/Users/aamuros/Downloads/screenly-models/gemma3-1b-it-int4.litertlm`.
Host and both emulator copies were independently checked: **584,417,280 bytes**, SHA-256
`1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`.
Both APK installs returned **Success** on each guest. The documented `run-as` transfer,
partial-file verification and rename succeeded in app-private `no_backup/models/` storage.
The model is outside Git and not bundled in either APK. Tested APK hashes are the follow-up
hashes above; source/dependencies were not changed during the real inference runs.

### API 35 native compatibility failure: FAIL

The enabled smoke test reached native `Engine.initialize()` and crashed before recording
initialization success or generating text. `am instrument` returned shell exit 0, but its
actual result was **`shortMsg=Process crashed.`**; it is not counted as a passed test.

Crash: **SIGILL / ILL_ILLOPC**, `liblitertlm_jni.so`, relative PC `0x127f444`, build ID
`5ae57c4840a756aee25e14435094931a`. NDK disassembly identified **`rdsvl x0, #1`**, an SME
instruction. The guest auxiliary vector advertised SME2 (`AT_HWCAP2=0x7f33f105383`) while
SME itself was absent, and executing this instruction trapped. A
[matching upstream emulator report](https://github.com/google-ai-edge/mediapipe/issues/6293)
describes the same SME dispatch problem. This is native runtime/emulator incompatibility;
Kotlin exception handling cannot catch a fatal native signal.

`-accel off` is ignored for ARM guests and did not change capabilities. Explicitly disabling
HVF/software emulation was rejected by emulator **37.1.11.0**: ARM64 requires hardware
acceleration. No JNI binary patch, feature shim, dependency downgrade or model substitution
was introduced. The reproducible working configuration uses a separate API 30 AVD.

### Working API 30 configuration

Installed the Google APIs ARM64 API 30 system image, revision 16, and created a separate
`Screenly_M3_API30` AVD from the `pixel_4` device definition. Existing AVD data was not wiped.

```sh
/Users/aamuros/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager 'system-images/android-30/google_apis/arm64-v8a'
/Users/aamuros/Library/Android/sdk/cmdline-tools/latest/bin/avdmanager create avd \
  -n Screenly_M3_API30 -k 'system-images;android-30;google_apis;arm64-v8a' -d pixel_4
/Users/aamuros/Library/Android/sdk/emulator/emulator -avd Screenly_M3_API30 \
  -no-window -no-audio -no-boot-anim -no-snapshot -memory 4096 -cores 4
```

AVD creation used the default **no** response to custom hardware-profile configuration.
The successful guest reports **Google `sdk_gphone_arm64`, Android 11/API 30, `arm64-v8a`,
hardware `ranchu`**, kernel `5.4.249-android11-2-00004-g212a7aaded26-ab10688362`.
There is no physical chipset; `ro.soc.model` was empty. Total guest RAM was
**4,021,996 KiB** (about 3.84 GiB). Its `AT_HWCAP2=0x183` does not advertise SME2; the same
unmodified LiteRT-LM 0.10.2 APK and verified model initialize and generate successfully.
Host hardware virtualization influences these timings; they are not phone benchmarks.

### Real generation, reuse and cleanup: PASS

Three enabled smoke-test executions each ended with **`OK (1 test)`**, no skips and successful
initialization, two native generation requests and cleanup. Each execution called `initialize()`
again on the same component before generation. Combined with the inspected non-null engine
reuse branch, this verifies the tested reuse behavior. Per-prompt conversations closed, and
engine `close()` plus repeated idempotent close returned successfully. This does not prove
absence of every native leak or cancellation race.

| Run / condition | Initialization, ms | Response 1, ms | Response 2, ms | Result |
| --- | ---: | ---: | ---: | --- |
| First API 30 run after provisioning; connectivity enabled | 1,474 | 5,857 | 6,124 | PASS |
| Offline process restart 1 | 836 | 6,273 | 5,698 | PASS |
| Offline process restart 2 | 421 | 5,667 | 5,667 | PASS |

Initialization includes SHA-256 verification; the first run followed ADB checksum reads, so it
is not a controlled cold-disk benchmark. Repeated restarts retained the model/runtime cache.
All six generations used **"What Android setting controls font size?"** and produced identical
nonblank text. The complete responses and instrumentation results are preserved in
[raw emulator evidence](verification/m3-emulator-2026-10-09.txt).

Actual answer excerpts:

> There isn't one single Android setting that controls font size directly.
>
> Settings > Display > Font Size: This setting controls font size across most Android apps.
>
> Settings > Theme > Font Size: This setting can be used by Android OS itself.

Text generation is real and names the relevant **Font Size** setting, but the opening sentence
contradicts that correct path, the answer invents/repeats other routes, and the theme claim is
not valid general Android guidance. **Response quality is limited; no navigation accuracy or
planner readiness is claimed.** Do not use smoke-test output as trusted instructions.

### Offline state and memory: PASS with limits

Used supported emulator shell commands to enable airplane mode and explicitly disable both
Wi-Fi and mobile data; no manual configuration was needed:

```sh
adb -s emulator-5554 shell cmd connectivity airplane-mode enable
adb -s emulator-5554 shell svc wifi disable
adb -s emulator-5554 shell svc data disable
```

Before and after both offline runs: **`airplane_mode_on=1`, `wifi_on=0`, `mobile_data=0`**.
`dumpsys connectivity` reported **`Active default network: none`** before and after testing.
Each run force-stopped the app process, then executed the smoke test with
`-e localAiSmoke true -e requireOffline true`. The test's pre/post offline guards passed.
All four offline generation requests succeeded from the private local model. This establishes
offline inference on the identified emulator; there was no packet capture or physical test.
Connectivity was restored afterward to original values **`0`, `1`, `1`**.

A `dumpsys meminfo com.screenly.app` sample during the first offline run, after initialization
and during generation, recorded **PSS 1,054,031 KiB (~1.01 GiB)**, **RSS 1,162,820 KiB
(~1.11 GiB)**, native-heap PSS **401,656 KiB**, and zero swap. These are loaded app-process
values including instrumentation/runtime/model, not isolated model memory or measured peaks.
A first sampling attempt after the connected run missed the process and is not counted.

### Final scope and remaining gates

**Emulator CPU loading, real text generation, private provisioning, reuse/close, and two offline
process restarts: VERIFIED on API 30. Full M3 status: IMPLEMENTED — UNVERIFIED.**
Fresh automated checks remain the 24-test/build/lint/instrumentation-compilation PASS above;
no source changed after those checks. No model bytes or credentials were added to Git.
Developer 1's implementation remains untouched. The original `Medium_Tablet` AVD was restarted
at `emulator-5556`; its pre-test accessibility settings were restored after the native crash.
`dumpsys accessibility` confirmed Screenly bound again, with no binding/crashed-service entries.
That restoration is not a full overlay/UI regression test. The dedicated API 30 AVD remains
at `emulator-5554`, with its original connectivity restored. The API 35 native dispatch crash is unresolved;
use `Screenly_M3_API30` for this demonstrated configuration, not the failing guest.

Remaining: physical load/offline/lifecycle and M1/M2 regression acceptance, phone RAM/chipset/
latency measurements and agreed budgets; separately, an upstream/runtime/emulator remedy for
the API 35 SME crash. Generated-answer quality is a recorded model limitation. **M4 remains
not started; full milestone acceptance has not been declared.**

## Physical verification attempt and host checks — 2026-10-09

Date / tester / commit / milestone: 2026-10-09, Codex for Developer 2,
`cfb893c11dcc59bcbfc42ff9c0bc83dfdd9e66ce` on `feat/local-ai`, M3 verification/M4 preparation.
Working tree was clean at inspection. Environment: macOS, Temurin JDK 21.0.9, existing SDK;
checks completed around 14:38–14:39 UTC. Only documentation changes follow these source checks.

### Fresh results

`adb devices -l` returned exit 0 with no devices (physical or emulator):

```text
List of devices attached

```

**Physical verification is blocked by the absence of a connected authorized phone.** No
phone Android version/API, ABI, RAM, chipset, available memory/storage or native compatibility
could be measured. No device provisioning, APK installation, LocalInferenceSmokeTest execution,
network-state change, offline restart, physical response, initialization/response timing,
loaded memory or device failure was observed in this attempt. These are NOT RUN/NOT MEASURED,
not failed inference tests. No emulator was started as a substitute.

| Check / command | Expected / actual | Evidence / limitation |
| --- | --- | --- |
| `wc -c < /Users/aamuros/Downloads/screenly-models/gemma3-1b-it-int4.litertlm` | PASS: 584,417,280 bytes | Existing host artifact located; not a new download or device copy |
| `shasum -a 256 /Users/aamuros/Downloads/screenly-models/gemma3-1b-it-int4.litertlm` | PASS: `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be` | Matches the pinned model/source record and LocalModel constants |
| Cached LiteRT-LM 0.10.2 AAR ZIP inspection | API 23 minimum; native `arm64-v8a` and `x86_64` | Fresh package inspection, not phone execution; app minimum remains API 30 |
| Debug APK ZIP inspection | `liblitertlm_jni.so` in `arm64-v8a` and `x86_64` | Other dependencies package some 32-bit libraries; that does not make the AI runtime 32-bit compatible |
| App/test APK build tasks | PASS, exit 0 | Both existing APK outputs available; compilation/build success does not establish native/device behavior |
| Targeted LocalInferenceTest rerun | PASS: 9 tests, 0 failures/errors/skips | XML timestamp `2026-10-09T14:38:10.464Z`; integrity, typed response and pre-native lifecycle policies only |
| Physical model/runtime compatibility, smoke and offline tests | NOT RUN | No connected phone; no generated text or physical timings/memory available |
| Android UI physical regressions | NOT RUN | Developer 1 source unchanged; this task does not verify overlays/accessibility on a phone |

Automated command (exit 0; `--rerun` forces the targeted test task):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --rerun --tests com.screenly.app.ai.LocalInferenceTest --console=plain --quiet
```

Unit evidence: `app/build/test-results/testDebugUnitTest/TEST-com.screenly.app.ai.LocalInferenceTest.xml`.
The only emitted build warning concerned SDK XML version 4 versus a reader supporting version 3;
it did not fail compilation/tests. No source/dependency changes justify a full suite or lint
rerun; the previous 24-test/lint results above remain historical, not fresh results here.

| APK | Fresh size / SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 78,297,976 bytes; `af5844a4c88e854c18c737497938bbe67afa07587cec2724387839a0e62f1899` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,453,484 bytes; `c1aa7a5c3c4cff526cef4b489759158a9ae637b839679537c2f5512d449c4083` |

Both APK identities match the prior successful emulator evidence. Runtime remains LiteRT-LM
0.10.2, CPU/four threads, 1,024 total tokens, unchanged exact Gemma INT4 model. No AI runtime,
model, shared contract, Developer 1 source, Gradle or manifest change was made.

### Readiness and next verification

Historical results remain: API 30 emulator genuine CPU inference passed three runs, including
two offline process restarts; initialization 421–1,474 ms, generation 5,667–6,273 ms and one
loaded-process PSS sample 1,054,031 KiB. Full actual responses and final JUnit outcomes are in
[raw emulator evidence](verification/m3-emulator-2026-10-09.txt). The answers include the
contradictory opening “There isn't one single Android setting that controls font size directly”
and an unsupported “Settings > Theme > Font Size” route. These are historical emulator
responses/measurements, not new phone results or navigation accuracy.

The historical API 35 ARM64 emulator SIGILL at SME `rdsvl` remains unresolved. It is not evidence
that every API 35 phone fails, and ABI/API eligibility is not proof of actual chipset/RAM/native
compatibility. No runtime replacement or model substitution was attempted.

**M3 remains IMPLEMENTED — UNVERIFIED for full acceptance.** Developer 2's next check is to
connect an authorized USB-debugging API 30+ phone with a runtime-supported 64-bit ABI, record
hardware/storage and follow the existing [provisioning](#reproducible-debug-provisioning-over-usb)
and [offline acceptance](#isolated-inference-and-offline-acceptance) procedures using the host
file above. Preserve complete instrumented results, actual answers, loaded-process memory,
first/subsequent load conditions, two offline process restarts, reuse/close and all failures.
Agree phone budgets from that evidence; Developer 1 handles physical M1/M2 regression checks.

M0 contracts are still proposals, with eligibility transport, authoritative session/revision,
immutable list/index semantics and completion evidence requiring joint approval. The
[M4 plan](M4_PLAN.md) defines a bounded prompt/schema, rejection/fallback policy, synthetic
fixture set, measurements, rule comparison and implementation order. **M4 remains NOT STARTED.**
Documentation/diff/link review completes this preparation; no branch merge or planner
implementation is part of it.

## Emulator-only M4 preparation — 2026-10-09

The current user request supersedes the physical-first next steps in the historical sections.
Developer 2 prepared pure Kotlin prompt, TAP/NONE parsing, target validation and conservative
rule helpers, 22 synthetic fixtures, 15 host unit tests and one Android helper test. **M4 is
IN PROGRESS**, while M0 shared contracts and RulePlanner/LlmPlanner adapters remain pending
joint approval. Developer 1's implementation and the runtime/model configuration are unchanged.

The existing `Screenly_M3_API30` AVD and already provisioned exact INT4 model were reused.
Both APK builds, the targeted units and the Android helper test pass. Two executions of the
existing M3 smoke test pass (four real responses); the confirmed offline rerun has 1/0/0
connectivity settings and no active default network before/after a process restart.
That run initialized in **944 ms** and generated in **6,987 / 8,351 ms**. The first run's
pre-test network was still disconnecting and is not counted as fully confirmed offline.
Complete outputs, setup/checker failures and successful state restoration are preserved in
[raw evidence](verification/m4-emulator-2026-10-09.txt); [verification details](TESTING.md#m4-emulator-development-preparation--2026-10-09)
record commands, identities, unit counts and limitations.

These are basic text-generation results, **not model navigation selections or latency**.
The same contradictory font-size response remains a quality limitation. Navigation accuracy,
LLM/fallback comparison and navigation latency are NOT MEASURED until approved contracts
enable the real fixture evaluator. Physical tests remain NOT RUN and do not block emulator
development. API 35 remains untouched. Next: jointly approve C0, merge contracts in a separately
authorized change, then implement the small adapters and run offline fixture evaluation as
specified in [M4_PLAN.md](M4_PLAN.md). M5 remains not started.
