# Navigation review and held-out benchmark — 2026-10-10

**Conclusion: the current Gemma integration provides no demonstrated navigation improvement
beyond the production rules.** It adds about 1.65 seconds per decision in this evaluation.
The three successful Settings workflows establish integration, not incremental AI capability.
Production implementation, model, prompts and safety checks were preserved. No merge or push.

## Latest-commit review

Reviewed `c5f4adbe83282a960aeb5649cff14ff32b829c38` on
`fix/assistant-guidance-validation`; the initial working tree was clean.

- **P2 — Semantic validation caps capability at the deterministic policy.**
  `CandidateNavigation.kt:77–127` computes approved candidates using the same rejection
  function applied to model proposals, then falls back to the unique approved candidate.
  Exact target/ambiguity/route restrictions leave no additional admissible targets in this
  corpus. Model provenance alone therefore does not establish additional navigation value.
  This is a capability limitation, not justification to remove safety checks.
- **P2 — Existing evaluation uses a different rules baseline.**
  `NavigationEvaluationTest.kt:134` calls `NavigationRules.select` directly, whereas live
  guidance uses canonicalization, verified routes and the candidate fallback. Its candidate
  route profile also omits SDK-specific hints. Historical comparisons are not a matched
  production-policy comparison. The new held-out harness exercises the exact fallback with
  test-only inference unavailable, rather than weakening the baseline.
- **P2 — Rapid reconnect may overlap native owners.** `ScreenlyOverlay.kt:126–136` closes
  asynchronously; `ScreenlyAccessibilityService.kt:41–45` immediately allocates a replacement.
  Mutex serialization is per runtime, so it does not prevent a new owner loading while the old
  JNI call/cleanup finishes. This resource/latency risk remains unverified under repeated live
  reconnects. It was not reproduced as a leak and no architecture change was made.

Source inspection found freshness checks, final recapture, original Android bounds, passive
highlighting, privacy filtering and manual completion intact. Cancellation cannot interrupt a
synchronous native call. Normal successful initialization stores the engine before checking
cancellation; conversations use `use`, and engine close runs under `NonCancellable + IO` with
its mutex. Partial native initialization failure cleanup still depends on LiteRT-LM's API;
this benchmark does not establish its native allocation behavior.

## Frozen benchmark and results

Three newly recorded UIAutomator accessibility hierarchies from Clock 6.2 (259367952) and
Contacts 1.7.31 on `Screenly_M3_API30`, API 30 ARM64. These apps were absent from the earlier
navigation fixtures. Nineteen cases were frozen before inference: six exact goals, six
paraphrases, missing targets, duplicate controls, unavailable/missing-label variants, and a
two-step Clock → Stopwatch task with the same goal across both screens. Three variants are
explicitly constructed from recordings. See the [corpus description](../app/src/androidTest/assets/navigation-heldout/README.md).

Both modes receive identical snapshots, goals, allowed indices, packages, empty route sets
and history. Production `guidanceSnapshot` supplies eligibility/enrichment. The rules-only
mode executes the actual production fallback without JNI. Gemma uses the unchanged INT4
artifact SHA-256 `1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be`,
LiteRT-LM 0.10.2, CPU/4 threads, 1024 tokens and default sampler. Airplane mode, Wi-Fi/data off,
and no active default network were recorded; original emulator settings were restored.

| Metric (3 repetitions; 57 decisions) | Rules-only | Rules + Gemma |
| --- | ---: | ---: |
| Correct final decisions, including abstentions | 33/57 | 33/57 |
| Correct actionable selections | 18/42 | 18/42 |
| Navigation task runs solved (excluding abstention-only cases) | 18/39 | 18/39 |
| Two-step task runs solved | 0/3 | 0/3 |
| Additional task runs solved / regressions | — | 0 / 0 |
| Incorrect final selections | 0 | 0 |
| Correct final abstentions | 15/15 | 15/15 |
| Raw model decisions correct, before validation/fallback | — | 12/57 (21.1%) |
| Raw unique wrong selections / multiple positive answers | — | 9 / 15 |
| Validated model selections | — | 0 |
| Fallback attempted / fallback selections | — | 57/57 / 18 |
| Model generation latency, median [min, max] | — | 1653 [1579, 6546] ms |

Raw decision correctness requires exactly the expected positive candidate, or all negatives
for an abstention case; malformed or unevaluated required targets count as failures. The 12
correct raw decisions are abstentions. Across 171 candidate judgments, 123 were correct,
33 were false positives and only 9 were correct positives; candidate accuracy must not be
mistaken for task accuracy. There were no malformed responses or runtime failures. Rules
elapsed time rounded to 0 ms median (0–4 ms range); model initialization was 560 ms.
Maximum sampled total process PSS was 1,092,069 KiB, not isolated model memory or peak memory.

Failure separation:

- **Missing accessibility data:** one deliberately removed target label, three repetitions.
  Abstention was correct; no planner can ground that target from the retained data.
- **Candidate coverage:** six actionable cases, 18 decisions, never evaluated the target due
  to the unchanged three-candidate budget/order. These are not evidence of weak reasoning
  about a target the model never saw.
- **Model reasoning/prompt:** 27 remaining decisions had incorrect raw judgments, including
  exact-label failures and ambiguous-control positives. The current prompt is framed around
  reaching a requested setting, which also limits its suitability for unfamiliar app tasks.
- **Restrictive semantic policy:** a separate deterministic oracle diagnostic rejects all
  six paraphrases and both multistep targets as `UNSUPPORTED_TARGET` even with a perfect
  proposed index. This overlaps the coverage/reasoning groups; it is a policy ceiling, not
  eight observed correct model decisions suppressed by validation. Observed correctly
  reasoned raw decisions blocked by validation: zero. Necessary ambiguity/state checks
  prevented erroneous selections and remain unchanged.

## Cleanup and verification evidence

Fresh results against the reviewed production source plus uncommitted evaluation code:

- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain --quiet`:
  PASS; all 94 existing host regressions, zero failures/errors/skips. Final application build
  and regression command also passed. Python syntax and diff whitespace checks passed.
- Offline benchmark instrumentation: PASS, 57 decisions / 171 native judgments. Cancel,
  serialized close, idempotent close, closed-runtime rejection and replacement load passed.
- `GuidanceCleanupTest`: PASS with real JNI. It invokes production service connection,
  reconnection and destruction callbacks on the main thread, cancels an owner-scope request,
  joins old cleanup, verifies the old runtime refuses work, loads a distinct replacement,
  then verifies destruction closes it. The test uses reflection for private ownership and a
  context-attached service, not an OS-bound accessibility connection. It does not establish
  live binder timing, native interruption, repeated overlap/OOM behavior or phone reliability.
- Deterministic frozen-corpus policy diagnostic: PASS. It performs no model inference.

[Raw responses, summaries, commands, APK/source hashes, corpus/app identities and cleanup logs](verification/navigation-heldout-2026-10-10/)
are retained. The benchmark APK identity is in `environment.json`; the subsequent diagnostic
APK identity is in `corpus-manifest.json`. Summary task metrics were corrected after collection
to exclude abstention-only cases; raw inference evidence was preserved.

Reproduce on the already provisioned dedicated emulator, preserving the model with `install -r`:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain --quiet
python3 scripts/evaluate-navigation.py --fixture-set heldout --protocol candidate --repetitions 3 --output /tmp/screenly-heldout-new
adb -s emulator-5554 shell am instrument -w -r -e class com.screenly.app.GuidanceCleanupTest -e guidanceCleanup true com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w -r -e class com.screenly.app.ai.navigation.HeldOutNavigationTest -e heldOutNavigation true -e prepareOnly true com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner
```

The runner requires an empty output directory and restores connectivity/accessibility settings.
Diagnostic fixtures are written to `cache/navigation-prepared.jsonl`. Do not use Gradle connected
test cleanup, which can uninstall the app and remove its private model.

Limitations: two unfamiliar apps, three recorded screens, correlated repeated/default-sampler
runs, UIAutomator rather than direct service captures, and teacher-forced multistep replay.
The second screen is scored even if the first decision fails; both must pass for task success.
No actual stopwatch start, independent completion verification, phone test or live reconnect
stress was claimed. Findings apply to this integration/configuration, not Gemma's general ability.

**Next priority:** define and evaluate grounded goal-to-intent normalization and bounded candidate
retrieval against new held-out tasks, retaining Android-owned indices, freshness, eligibility,
ambiguity/toggle checks and independently verified route evidence. Require measurable additional
safe task success before putting more inference on the guidance path. Keep deterministic rules
as the reliable baseline; separately close the rapid-reconnect resource-overlap test gap. Model
replacement or vision is not justified by these results.
