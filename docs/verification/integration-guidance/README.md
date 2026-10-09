# Integration verification — 2026-10-10

Final source commit: `e1e032f76422e4469dc5b1c3ad2d23832ce6a6a3` on
`integration/reliable-guidance`, based on main `e754f7b` and preserving the local-AI and
Android-guidance branches as merge parents. Commands, source hashes, artifact hashes,
unit classes and environment are recorded in [summary.json](summary.json).
Run instructions and supported scope are in [INTEGRATION.md](../../INTEGRATION.md).

## Fresh host results

Expected: compile both APKs, reject wrong/stale selections, retain existing safeguards.
Actual: debug and Android-test builds PASS; **76 focused JVM tests, zero failures/errors/skips**.
Lint: **zero errors, 15 warnings**, including existing dependency/version, launcher resource
and unused string warnings. No toolchain or application dependency changes were made.

The regressions cover copied labels and original IDs, reordered/high indices, invalid responses,
unrelated choices, disabled/offscreen/missing controls, duplicate controls, satisfied toggles,
row versus adjacent switch intent, cancellation, changed goals, changed revisions/session,
and rule-fallback provenance. All 16 frozen availability diagnostic fixtures from
`test/m4-fixture-ui` are checked using adversarial synthetic YES responses. Those assertions
measure deterministic policy behavior, not Gemma accuracy.

## Fresh Android emulator results

Device: Google `sdk_gphone64_x86_64`, API 37, `emulator-5554`; **no physical phone**.
The native UI test enters a goal, observes two matching cyan outlines at Android-provided
bounds, injects user-equivalent taps through them, reaches the expected page, and stops
guidance. App code does not inject taps. The test sets goal text directly; real keyboard
typing, rotations, lock-screen behavior and broader OEM support are not covered here.

| Workflow | Expected / actual | Whole test time | Evidence |
| --- | --- | --- | --- |
| Font size | Display & touch → Display size & text → Font size; PASS | 7.379 s | [ui-font-release.txt](ui-font-release.txt) |
| Wi-Fi | Network & internet → Internet → Use Wi-Fi; PASS | 9.408 s | [ui-wifi-release.txt](ui-wifi-release.txt) |
| Dark theme | Display & touch → Dark theme → Use dark theme, without changing theme; PASS | 7.949 s | [ui-dark-release.txt](ui-dark-release.txt) |

**The archived runs above used RULE fallback after a missing-model failure.**
[Guidance diagnostics](guidance-provenance.txt) record `source=RULE outcome=FAILED`.
The logged milliseconds include the fast missing-file failure and rule work; they are not
LLM generation latency. Whole-test times above include UI setup and waiting.

On the Internet destination, UiAutomation could observe the Wi-Fi page while Screenly's
service had no accessible root. The Wi-Fi run explicitly verifies a stable waiting message
and no highlight, then returns to a readable page to stop. This hierarchy limitation remains;
privacy/window guards were not relaxed. The API 37 Google-only Internet route was verified
using observed controls, rather than inferred from the connected network name.

Earlier `ui-attempt-*`, `ui-font.txt`, `ui-wifi-final.txt` and `ui-dark-verified.txt` files preserve
failures during development. They are not final passing results. Attempt 3 ran an older test
APK after a failed build in the concurrently switched original checkout; no final claims rely
on it. Isolation, task-reset/test command fixes, unavailable-root handling and explicit
open-versus-toggle disambiguation resolved the relevant failures in the final runs.

The final provisioned-model runs also PASS, with two current bounds/outline checks, manual
touch-through, and assertions that the instruction caption does not cover the target:

| Workflow | Whole test time | Step sources | Evidence |
| --- | --- | --- | --- |
| Font size | 29.605 s | RULE → MODEL | [ui-font-native-final.txt](ui-font-native-final.txt) |
| Wi-Fi | 34.153 s | RULE → MODEL | [ui-wifi-native-final.txt](ui-wifi-native-final.txt) |
| Dark theme | 27.411 s | MODEL → RULE | [ui-dark-native-final.txt](ui-dark-native-final.txt) |

[Native diagnostics](guidance-native-final.txt) distinguish each model and fallback step.
Final observed decision times were 8.145/6.544 s for font size, 12.393/5.968 s for Wi-Fi,
and 7.273/6.053 s for dark theme. These are single emulator runs and include current model
work; they are not phone benchmarks. Caption text was shortened and moved away from the
verified bounds after an earlier native Wi-Fi outline check failed. The failing
`ui-wifi-native-budget3.txt` is preserved; its model selection was valid but the visible
outline test did not pass. The initial eight-call font run is also preserved in
`ui-font-native.txt`; it used roughly 24.480/14.115 s per step.

## Real model evaluation

**PASS: real offline native execution, not intrinsic model correctness.** The initial public
download request returned HTTP 401. After the user authorized downloading and supplied access,
the exact pinned artifact was downloaded and verified on the host and in private guest storage:
584,417,280 bytes; SHA-256
`1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`.
No credential is stored in source or result files. The local model is ignored by Git and is
not bundled in the APK. Direct Windows stdin transfer was truncated and rejected by integrity
checks; binary-safe ADB push and private copying succeeded.

[Native smoke evidence](gemma-api37-smoke/instrumentation.txt): offline loading, two real CPU
generations, reuse and cleanup PASS. Cold initialization took 14.776 s; total test 50.922 s.
Generation availability does not establish that its prose answer is correct.

[Candidate summary](gemma-api37-candidate/summary.json) and
[raw records](gemma-api37-candidate/raw.jsonl): **16 frozen synthetic screens × three repetitions**,
93 native candidate generations, 48 screen decisions, zero runtime failures. Raw canonical
next-action correctness was **12/48**, with nine wrong unique proposals and 24 ambiguous
positive sets. Multiple YES answers can be appropriate individual relevance answers for
duplicate controls; they still cannot identify a unique next action.

The validated combined engine was correct on **48/48** decisions: three MODEL selections,
15 RULE fallback selections and 30 correct abstentions; **zero wrong final selections**.
This supports the conservative policy for these fixtures, not a claim that Gemma became
smarter. Rules alone can solve this small vocabulary. Fixture generation time per screen
was median 1.8565 s, range 0.894–4.506 s; warm initialization 2.178 s; maximum sampled process
PSS 1,100,885 KiB. Real serialized concurrent calls and cancellation/reuse checks also PASS.
Original connectivity and accessibility settings were restored after both offline tests.

The fixture run used source `a536311` before the later UI fixes and call-budget reduction.
Every frozen screen has at most three candidates, so its recorded candidate prompts and
call set are identical under the final three-call budget. The final-budget unit regression
separately verifies full-set duplicates beyond the evaluated subset and priority for a
valid target with a high original index. The final native UI runs use source `e1e032f`.

The candidate evaluator's preparation mode PASS freezes all 16 prior failure fixtures and
their exact per-control prompts before any native call:
[instrumentation](candidate-prepare.txt), [frozen inputs](candidate-frozen.jsonl).
This preparation run has zero native calls and establishes neither offline inference nor semantic quality.
After provisioning the pinned artifact, run with `protocol=candidate`, `fixtureSet=availability`,
and `repetitions=3`, without `prepareOnly`. Evaluate raw correctness, wrong selections,
correct abstentions, validated results, fallback source and latency separately.

Remaining integration review: shared contracts before any main merge; demonstration-phone
model/runtime compatibility, offline quality/latency/memory, real typing and physical privacy/
lifecycle checks. Native JNI work cannot be preempted by coroutine cancellation; request guards
discard its eventual stale result. Final inference is bounded to three candidate calls,
but physical phone performance remains unmeasured. No general accuracy or phone latency
improvement is claimed from these synthetic fixtures and emulator checks.
