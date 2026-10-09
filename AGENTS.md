# Codex instructions — Screenly

## Purpose and constraints

Build an offline Android assistant that observes accessible UI, plans the next element,
highlights it, and waits for the user's manual action. No automatic interaction, cloud
inference, backend, authentication, or database. Use one application module. Gemma 3 1B is
an initial candidate, not a confirmed compatible or performant model.

Read [ARCHITECTURE.md](docs/ARCHITECTURE.md) for current code and proposed contracts,
[ROADMAP.md](docs/ROADMAP.md) for scope, and [TESTING.md](docs/TESTING.md) for verification.
**Do not implement future milestones unless explicitly asked.** A roadmap entry is not
authorization to implement it.

## Existing architecture

- `MainActivity`: Compose service status and accessibility-settings launch.
- `ScreenlyAccessibilityService`: application-window extraction, event coalescing, privacy
  filtering, and lifecycle cleanup using a main-thread Handler.
- `AccessibleUiElement`: immutable element values with screen-pixel bounds.
- `ScreenObservation` / `ScreenObservationState`: current snapshot and revision-based
  selection validation, owned by `ScreenlyOverlay`.
- `ScreenlyOverlay`: native View bubble/picker/highlight, not Compose overlay windows.
- No Planner, LocalAI, goal input, or GuidanceController exists yet. Coroutines/StateFlow
  integration is planned; do not describe it as current architecture.

## Ownership and stable interfaces

| Owner / branch | Responsibility |
| --- | --- |
| Developer 1 / `feat/android-guidance` | Accessibility service/config, observation/extraction, activity and goal UI, bubble/highlights, guidance state, screen changes, stale rejection, MockPlanner integration, physical Android tests |
| Developer 2 / `feat/local-ai` | LiteRT-LM, model provisioning/benchmarks, LlmPlanner, prompts, response parsing/validation, RulePlanner, AI snapshot tests, offline inference tests |
| Both | Shared contracts, build/dependency configuration, integration, end-to-end verification, demo, documentation |

The existing element/observation files are Developer 1's platform foundation and inputs to
M0 contract review. Agree and merge shared contracts into `main` before either branch depends
on them. The architecture's proposal is not an implemented API. Do not silently rename fields,
change bounds/ID semantics, or change result variants. Coordinate shared-file and Gradle edits;
keep tasks within developer ownership. Flag required cross-owner edits before expanding scope.
Follow the collaboration workflow in ROADMAP.

## Editing and code quality

- Inspect task-relevant code and working-tree changes before editing. Use the smallest context
  that makes the change safe; prefer targeted `rg` searches and reads. Do not reread unchanged
  files or explore unrelated trees, dependencies, caches, or generated outputs.
- Use Kotlin's official style, clear names, small functions, immutable snapshot values, and
  existing Android APIs. Use resources for user-facing strings. Separate model decisions from
  deterministic Android operations; never trust model-supplied coordinates.
- Preserve privacy filtering, bounded traversal, node lifetime handling, touch pass-through,
  revision checks, and idempotent cleanup. Never retain live accessibility nodes/events for AI.
- When AI work is requested, keep inference off the main thread and tie jobs/model resources
  to a defined lifecycle. Handle empty roots, bad results, cancellation and model failures.
- Prefer simple classes/interfaces when needed. Do not add DI frameworks, extra modules,
  repositories, generic abstractions, new libraries, or opportunistic refactors.
- Do not modify unrelated components or overwrite another developer's changes.
- Default tools: files/search/edit/shell/git and targeted checks. No web/docs MCP, browser,
  screenshots or visual inspection unless requested or local evidence is insufficient.
  Do not start a dev server or browser/E2E tests unless explicitly requested. Do not spawn
  subagents for ordinary single-scope tasks.

## Verification and progress

- Build application changes with `./gradlew :app:assembleDebug`; run the smallest relevant
  compile/typecheck, lint or unit check from TESTING. Do not add tests that mirror implementation
  or run full suites/E2E by default. Documentation-only changes need diff/link/consistency review.
- Inspect a failure and correct its cause before rerunning. Do not ignore compiler errors
  or skip necessary checks. After targeted verification succeeds, stop.
- Report changes, files touched, checks/results and limitations. Separate source inspection,
  fresh automated results, historical reports, emulator results, physical results and assumptions.
  A build does not prove device functionality.
- Check off roadmap tasks only with source/test evidence. Use its five statuses; **VERIFIED**
  requires all acceptance criteria, including physical checks where specified. Record date,
  commit, command/device, expected/actual result and limitations using TESTING's result format
  or a linked verification report. Never invent results or benchmarks.
- Keep `main` at the latest version verified to the documented scope. The current emulator-tested
  baseline is not a physically verified release.
