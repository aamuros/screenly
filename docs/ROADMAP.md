# Screenly roadmap

Baseline inspected: `51f5fb7` on `main`, 2026-10-09. This change adds documentation only.
Checked boxes mean the stated task has evidence, not that an entire milestone is verified.
Procedures: [TESTING.md](TESTING.md). Contracts: [ARCHITECTURE.md](ARCHITECTURE.md).
Preserve [VERIFICATION.md](../VERIFICATION.md) as the historical audit; its “CONDITIONALLY
READY” wording maps to **IMPLEMENTED — UNVERIFIED** here.

Statuses: **NOT STARTED** = no milestone implementation; **IN PROGRESS** = partial work;
**IMPLEMENTED — UNVERIFIED** = implementation present with outstanding acceptance checks;
**BLOCKED** = a recorded prerequisite prevents progress; **VERIFIED** = all acceptance
criteria have evidence. Do not promote status from a task title, branch name or build alone.

## M0 — Shared Contracts & Parallel Development Setup

**Objective:** Give both branches stable observation/planning contracts and ownership.
**Owner:** Both. **Status: IN PROGRESS.**

- [x] Document ownership, branch workflow, existing types and a contract proposal in this change.
- [ ] Agree on ScreenSnapshot, ScreenElement mapping, Planner and PlannerResult.
- [ ] Implement/review contracts and merge into `main` (separate authorized task).
- [ ] Agree snapshot/request identity, result schema and completion evidence.
- [ ] Create branches/worktrees; demonstrate independent builds against merged contracts.

**Dependencies:** Existing element/observation types; joint review.
**Deliverable:** Merged minimal contracts and two independent development branches.
**Acceptance:** Developer 1 uses MockPlanner without a model; Developer 2 uses sanitized
snapshots without a service. Both use the same API and reject stale results.
**Verification:** Joint contract review, compile both consumers, test valid/invalid/stale
selections; TESTING M0.
**Evidence/gaps:** Documentation and actual observation/revision types exist. Named planner
contracts are absent; branches and shared API implementation are not established by this task.

## M1 — Android Accessibility

**Objective:** Obtain safe, bounded observations of the current application window.
**Owner:** Developer 1. **Status: IMPLEMENTED — UNVERIFIED.**

- [x] Declare/bind AccessibilityService and provide enablement/status activity.
- [x] Extract visible hierarchy metadata and screen-pixel coordinates.
- [x] Handle window/content/scroll events with bounded traversal and coalesced captures.
- [x] Filter sensitive/editable content; skip Screenly/overlay windows.
- [ ] Complete physical-device observation and lifecycle verification.

**Dependencies:** None for the foundation; M0 for planner-facing adaptation.
**Deliverable:** Service producing sanitized observations of a supported app.
**Acceptance:** Physical API 30+ phone yields usable labels/states/bounds, safe missing-data
handling and screen-change observations. Lock/disconnect clears observations; privacy and
traversal limits remain intact. Record unsupported hierarchies instead of claiming all apps work.
**Verification:** TESTING M1; sanitizer/revision tests and physical Logcat inspection.
**Evidence/gaps:** Service, manifest/XML and element/observation code exist. Existing audit
records API 35 emulator extraction/navigation. This audit passed a debug build and 14 targeted
tests. No physical results; live checked/disabled controls, API 30–32 pooling and arbitrary
third-party hierarchies remain unverified.

## M2 — Floating Assistant & Highlighting

**Objective:** Let the user select and manually act on a highlighted current control.
**Owner:** Developer 1. **Status: IMPLEMENTED — UNVERIFIED.**

- [x] Use `TYPE_ACCESSIBILITY_OVERLAY` for bubble, picker and highlight.
- [x] Implement interactive draggable bubble and non-interactive highlight layer.
- [x] Convert accessibility bounds using the overlay's actual screen origin.
- [x] Validate bounds and refresh before accepting a picker selection.
- [x] Clear stale highlights and clean up on screen/service lifecycle changes.
- [ ] Complete physical alignment, touch, cutout, lock and lifecycle verification.

**Dependencies:** M1; M0 for later AI selection integration.
**Deliverable:** Manual picker/highlight foundation; goal input belongs to M5.
**Acceptance:** One bubble, correct outline in portrait/landscape, highlight-area taps reach
the target app, navigation/scroll/lock clears stale UI, clean disable/re-enable on a phone.
**Verification:** TESTING M2 and preserved VERIFICATION physical checklist.
**Evidence/gaps:** `ScreenlyOverlay` and revision tests implement safeguards. Existing audit
records API 35 emulator alignment/tap-through, toggling, navigation/scroll, rotation,
screen-off and service checks. Physical touch, OEM behavior, cutouts, secure keyguard and
multiple displays remain untested. No new device checks were performed for this documentation.

## M3 — Local AI Integration

**Objective:** Load a compatible local model and infer without network access.
**Owner:** Developer 2. **Status: NOT STARTED.**

- [ ] Integrate LiteRT-LM with explicit initialization/error/close handling.
- [ ] Define local provisioning, model format, integrity, license and storage requirements.
- [ ] Load a compatible model; evaluate quantized Gemma 3 1B as the initial candidate.
- [ ] Verify offline inference, including restart in airplane mode.
- [ ] Benchmark initialization, inference, memory and APK/model size on the demo phone.

**Dependencies:** Agreed hardware and compatible runtime/model; M0 for planner integration.
Compatibility/provisioning investigation can proceed independently.
**Deliverable:** Reproducible local inference and recorded compatibility/benchmark results.
**Acceptance:** Exact model loads/responds offline on the chosen phone without blocking UI;
missing/corrupt model and runtime failures are safe. Agree latency/memory budgets from evidence.
**Verification:** TESTING M3; cold/warm loads, repeated inference, offline restart and errors.
**Evidence/gaps:** No runtime dependency, loader, artifact or benchmark exists. Format/backend,
device suitability and provisioning method remain open.

## M4 — AI Navigation Planner

**Objective:** Choose one allowed current element from a goal and sanitized snapshot.
**Owner:** Developer 2; Developer 1 reviews Android validation compatibility.
**Status: NOT STARTED.**

- [ ] Implement LlmPlanner and structured screen/goal prompts.
- [ ] Constrain selection to enumerated candidates and agreed result schema.
- [ ] Parse/validate malformed, out-of-range, unsupported and stale responses safely.
- [ ] Implement RulePlanner fallback with explicit safe failure when no rule applies.
- [ ] Add recorded sanitized snapshot tests and evaluate model selection quality.

**Dependencies:** M0 API; M3 for real LLM evaluation. Parser/rule tests can precede M3.
**Deliverable:** Validated selection/completion/unable results and snapshot fixtures.
**Acceptance:** No invented targets/coordinates or automatic actions. Deterministic tests
cover bad outputs/fallback; real-model evaluation reports correct/incorrect/unsupported cases.
Jointly agree the demo quality threshold.
**Verification:** TESTING M4; parser/rule tests plus separate offline-model fixture evaluation.
**Evidence/gaps:** No Planner/LlmPlanner/RulePlanner or AI fixtures/tests exist.

## M5 — End-to-End Guidance

**Objective:** Guide a goal across manual actions with deterministic state handling.
**Owner:** Developer 1; both integrate/test the real planner. **Status: NOT STARTED.**

- [ ] Add goal input and deterministic GuidanceController with observable state.
- [ ] Integrate MockPlanner first, independently of M3/M4.
- [ ] Connect real planner results to validated overlay targets.
- [ ] Detect changes, cancel old work and reject stale snapshot/goal/session results.
- [ ] Guide multistep navigation with stop/error/recovery behavior.
- [ ] Verify completion using observed target state or explicit user confirmation.

**Dependencies:** M0 + M1/M2 for mock flow; M3/M4 for real AI acceptance.
**Deliverable:** Goal → plan → highlight → manual action → fresh observation loop.
**Acceptance:** Agreed multistep demo completes on a phone using real offline AI. Rapid
navigation, goal replacement, lock and reconnect cannot revive old results. Completion is
corroborated, not inferred solely from model prose.
**Verification:** TESTING M5; delayed MockPlanner/state tests, physical real-model tasks and
deliberate navigation during inference.
**Evidence/gaps:** Existing change/revision handling protects the manual picker only, not AI
requests; there is no guidance controller or goal UI.

## M6 — Hackathon Testing & Demo

**Objective:** Produce a reproducible, reliable offline demonstration APK.
**Owner:** Both; Developer 1 leads Android reliability, Developer 2 model performance.
**Status: NOT STARTED.**

- [ ] Validate complete flow offline after process restart.
- [ ] Run physical end-to-end tasks and repeated lifecycle/reliability checks.
- [ ] Measure initialization/inference/end-to-end time and memory/size.
- [ ] Resolve demo-blocking failures; record supported device/app/task limitations.
- [ ] Build/install/rehearse the exact final APK with its required local model.

**Dependencies:** M1–M5 acceptance, chosen phone/app/task and agreed performance budgets.
**Deliverable:** Identified demo APK/model, reproducible setup and dated evidence.
**Acceptance:** TESTING final demo gates pass on the chosen phone: no network reliance, stale
highlight, blocked underlying touch, critical crash or false completion in rehearsal.
**Verification:** TESTING M6; identify commit/APK/model/device and record every rehearsal.
**Evidence/gaps:** Baseline debug APK builds; no AI demo or end-to-end proof exists.

## Parallel work and integration checkpoints

| Stage | Developer 1 — `feat/android-guidance` | Developer 2 — `feat/local-ai` | Integration gate |
| --- | --- | --- | --- |
| Now / M0 | Physical M1/M2 checklist; review snapshot/revision semantics | Runtime/model/device compatibility investigation; review schema | C0: agree/merge contracts before dependent code |
| After C0 | Goal UI, controller and overlays with MockPlanner | Model provisioning/load/benchmarks; parser, RulePlanner, fixtures | C1: shared fixtures/results pass both consumers |
| After C1 + M3/M4 | Integrate real results and manual-action loop | Tune prompts; runtime/fallback handling | C2: real offline physical M5 task passes |
| After C2 | Lifecycle/touch reliability and demo setup | Offline restart, selection quality, performance | C3: exact APK/model passes M6 rehearsal |

### Collaboration workflow

1. Agree shared contracts and merge their implementation into `main` before dependent work.
2. From that `main`, create the assigned branch in separate clones/worktrees:
   `git switch -c feat/android-guidance` or `git switch -c feat/local-ai`.
3. Run each Codex session in its own branch/worktree. Optional worktree setup from `main`:
   `git worktree add ../screenly-android -b feat/android-guidance main` and
   `git worktree add ../screenly-ai -b feat/local-ai main`. Use these instead of creating the
   branches in step 2; check existing branches/worktrees first.
4. Avoid modifying the other developer's files; coordinate contracts, Gradle and documentation.
5. Build/run relevant tests before merge; record required device checks.
6. Review PRs for integration/schema/dependency conflicts; verify the combined behavior.
7. Update roadmap checkboxes/status only from source and test evidence, with linked limitations.
8. Keep `main` at the latest verified working version **within its recorded verification
   scope**. The current baseline has emulator evidence; physical readiness remains open.
