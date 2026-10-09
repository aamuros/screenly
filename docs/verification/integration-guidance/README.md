# Integration verification — 2026-10-10

Source commit: `a536311a9f9b7d1b9e00deafa8d1d319cf2614b6` on
`integration/reliable-guidance`, based on main `e754f7b` and preserving the local-AI and
Android-guidance branches as merge parents. Commands, source hashes, artifact hashes,
unit classes and environment are recorded in [summary.json](summary.json).
Run instructions and supported scope are in [INTEGRATION.md](../../INTEGRATION.md).

## Fresh host results

Expected: compile both APKs, reject wrong/stale selections, retain existing safeguards.
Actual: debug and Android-test builds PASS; **75 focused JVM tests, zero failures/errors/skips**.
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

**All these runs used RULE fallback after a missing-model failure.**
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

## Real model evaluation

**NOT RUN.** The AI branch contains runtime/source/provisioning records, but no tracked
model binary. The historical downloaded file belongs to another developer's Mac. The Windows
checkout and current guest have no model. A later user-authorized request to the exact pinned
Hugging Face artifact returned **HTTP 401 Unauthorized**; no file bytes were downloaded and
no local Hugging Face credentials were available. Publisher authentication and accepted model
access terms, or an existing verified file, are required to continue.

The candidate evaluator's preparation mode PASS freezes all 16 prior failure fixtures and
their exact per-control prompts before any native call:
[instrumentation](candidate-prepare.txt), [frozen inputs](candidate-frozen.jsonl).
This run has zero native calls and establishes neither offline inference nor semantic quality.
After provisioning the pinned artifact, run with `protocol=candidate`, `fixtureSet=availability`,
and `repetitions=3`, without `prepareOnly`. Evaluate raw correctness, wrong selections,
correct abstentions, validated results, fallback source and latency separately.

Remaining integration review: shared contracts before any main merge; demonstration-phone
model/runtime compatibility, offline quality/latency/memory, real typing and physical privacy/
lifecycle checks. Native JNI work cannot be preempted by coroutine cancellation; request guards
discard its eventual stale result. Up to eight sequential candidate calls per screen may be
too slow on the phone. No accuracy or latency improvement is claimed without that measurement.
