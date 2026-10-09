# Frozen held-out corpus

Captured 2026-10-10 on Screenly_M3_API30 (API 30, 1080×2148 application viewport)
using `adb shell uiautomator dump`. Clock and Contacts were absent from the existing
navigation fixtures; these cases were authored and frozen before this evaluation.
No production rules, prompts, model, or validation were tuned against these cases.

Sources: `clock.xml` (Clock tab), `stopwatch.xml` (after opening Stopwatch tab without
starting it), `contacts.xml` (empty Contacts list). The recorded transition Clock →
Stopwatch supplies the two-step `Start stopwatch` task. Step two is teacher-forced:
it is evaluated even when step one fails, and the task passes only if both selections
are correct in the same repetition. This measures replay navigation, not live end-to-end
completion or whether a stopwatch subsequently runs.

`cases.json` freezes goals and expected original preorder indices. Non-null means a
specific next control advances the goal; null means safe abstention, never completion.
Exact and paraphrased goals share the same captured hierarchy. The duplicate Start,
removed create label, and disabled create cases are explicitly constructed variants;
they are not claimed as naturally occurring app recordings. A removed label has a
latent target annotation but abstention is the correct observable behavior.

The test parses copied attributes and ancestry and uses production `guidanceSnapshot`
for candidate eligibility and label enrichment. These are UIAutomator accessibility
hierarchies, not direct ScreenlyAccessibilityService extraction recordings: metadata and
node visibility can differ. The snapshots contain no contacts, passwords or editable
inputs. Timestamp/date display labels are frozen public clock data.

Clock has six allowed controls. Candidate evaluation remains capped at three and retains
production ordering. Missing evaluation of a correct target is reported separately from
model reasoning and validation failures. Nothing expands that budget for scores.

Rules-only uses the exact production candidate fallback (canonicalization and the same
empty verified routes for these non-Settings packages). Test-only generation throws a
local-inference-unavailable error, so it never invokes JNI. The hybrid uses the real
unchanged model. Both receive identical goals, elements, indices, package and empty history.
Each prompt has a fresh conversation. Repetitions reuse the engine/default sampler;
three repetitions are descriptive evidence, not an independent generalization estimate.
