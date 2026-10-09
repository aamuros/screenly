# Screenly architecture

## Implemented foundation

One Kotlin application module (`com.screenly.app`); no backend, database or network permission.

An isolated `ai/LocalInference` component now verifies a private `.litertlm` file and uses
LiteRT-LM 0.10.2 on CPU. Its suspend initialization/generation/close calls run on IO with a
mutex; it reuses the engine and closes per-prompt conversations. Only an opt-in instrumented
smoke test consumes it. No activity/service/overlay or planner integration exists, and real
phone inference remains unverified. See [M3 implementation and evidence](LOCAL_AI.md).

```text
MainActivity (Compose) → Android accessibility settings / enabled-service status
Accessibility events → ScreenlyAccessibilityService → ScreenObservation
                                                     ↓
                                              ScreenlyOverlay
                                          bubble → manual picker
                                          validated element → highlight
                                                     ↓
                                         user touches the target app
```

The service observes the active, otherwise focused, **application** window; overlay windows
and Screenly's activity are excluded. It copies values and recycles nodes on API 30–32; no
live nodes/events survive capture. Traversal is limited to 500 node/child slots and depth 40.
Password subtrees and, on API 34+, accessibility-data-sensitive subtrees are skipped;
editable values/descendants are omitted. Filtering relies on the target app's metadata. Other labels
are normalized to at most 160 UTF-16 characters without splitting a surrogate pair. Only
changed snapshots are logged, in debug builds.

Events use a 300 ms notification timeout plus a main-thread Handler with a 250 ms minimum
capture interval. Explicit picker refresh bypasses the interval. The service retains its
latest observation for event/log comparison; the overlay owns `ScreenObservationState`
for selection validation. Neither retains observation history.

`ScreenlyOverlay` manages native View bubble, picker and highlight windows using
`TYPE_ACCESSIBILITY_OVERLAY`. Bubble/picker are touchable and non-focusable; highlight is
non-touchable. Candidates must be enabled/clickable with positive bounds intersecting the
display. Bounds are absolute screen pixels; drawing subtracts the overlay's actual screen
origin. Picker labels may come from a contained element; selection uses the candidate's own
bounds. No code activates a target-app control.

## Actual data and revision contracts

| Existing type | Actual fields / behavior |
| --- | --- |
| `AccessibleUiElement` | Nullable `text`, `contentDescription`, `className`, `viewId`; Boolean `clickable`, `enabled`, `checked`, `scrollable`; Int `left`, `top`, `right`, `bottom` in screen pixels |
| `ScreenObservation` (internal) | `packageName: String`, `windowId: Int`, `elements: List<AccessibleUiElement>`; structural equality; no timestamp/version or element ID |
| `ScreenObservationState` (internal) | Nullable `snapshot`, `revision: Long` starting at zero; private setters; `update`, `invalidateSelection`, `clear`, `canSelect` |

`update` increments revision on changed snapshots. Navigation/target scroll/window invalidation
increments it even if the next hierarchy is identical. `clear` removes the snapshot and
increments revision. `canSelect` checks revision/snapshot equality, membership, enabled/clickable
state and positive bounds. Overlay checks also require display intersection and current picker
identity, with a refresh before highlighting. A→B→A cannot restore an old picker selection
within that state instance.

Screen-off, unavailable root, own activity, interruption, rotation and teardown clear
observations/overlays. Observation requires an interactive display and unlocked keyguard.
Revision counters are per-state-instance; reconnect does **not** supply a global generation.
Disposal/instance guards currently protect manual callbacks only. See
[VERIFICATION.md](../VERIFICATION.md) for emulator evidence and untested phone behavior.

## Planned responsibilities (not implemented)

| Component role | Owner | Minimal responsibility |
| --- | --- | --- |
| ScreenObserver | Developer 1 | Role inside current service: publish copied sanitized observations/invalidations; no new framework needed |
| OverlayManager | Developer 1 | Role fulfilled by current `ScreenlyOverlay`; draw only validated current targets; clear on invalidation |
| GuidanceController | Developer 1 | Own goal, version, request identity and deterministic state; coordinate observer, Planner and overlay |
| Planner / MockPlanner | Shared contract / Developer 1 mock | Platform-independent next-step API; deterministic mock enables work without a model |
| LocalAI / LlmPlanner | Developer 2 | Local runtime lifecycle/inference, bounded prompts, constrained response parsing |
| RulePlanner | Developer 2 | Deterministic fallback using the same API; return unable when no safe rule applies |

These are responsibility names, not claims of existing classes. Keep the single module;
Coroutines/StateFlow are intended for asynchronous planning/state publication, while current
service scheduling uses Handler. No extra architecture library is needed. AI selects candidates;
Android owns freshness, coordinates, lifecycle and drawing.

## M0 contract proposal — requires joint agreement and implementation

Reuse existing fields instead of introducing incompatible duplicates. **ScreenElement** is
the shared conceptual name for `AccessibleUiElement`; agree whether to retain its name or add
an alias (none exists). **ScreenSnapshot** would wrap a copied observation with a version key.
This is a documentation proposal, not an implemented or agreed Kotlin API:

```kotlin
internal data class SnapshotKey(val sessionId: Long, val revision: Long)
internal data class ScreenSnapshot(val key: SnapshotKey, val observation: ScreenObservation)
internal interface Planner {
    suspend fun plan(goal: String, snapshot: ScreenSnapshot): PlannerResult
}
internal sealed interface PlannerResult {
    val snapshotKey: SnapshotKey
    data class Next(override val snapshotKey: SnapshotKey, val elementIndex: Int) : PlannerResult
    data class Complete(override val snapshotKey: SnapshotKey, val reason: String) : PlannerResult
    data class Unable(override val snapshotKey: SnapshotKey, val reason: String) : PlannerResult
}
```

- Session identity changes on observer/controller restart; revision changes on content changes
  and selection invalidation. Publisher owns this key and an immutable copied element list.
  Session identity prevents revision reuse after reconnect.
- Next indexes the exact observation list. Prompts enumerate only allowed enabled/clickable/
  in-display candidates using original indices. Index is not a persistent ID or model coordinate.
- Results echo the input key. Controller also tracks request/goal generations locally, rejecting
  replaced/cancelled requests on an unchanged screen. The model cannot choose request identity.
- Agree a bounded wire schema for Next/Complete/Unable. Parser validates variant, types, index
  and candidate membership; runtime attaches the trusted input key. Invalid output may invoke
  RulePlanner, whose results get the same validation. Exhausted fallback yields Unable.
- Before drawing, refresh and require current session/revision/request/goal, membership and
  Android state/bounds checks. Cancel old jobs and discard late results; cancellation alone is
  insufficient. Never pass live AccessibilityNodeInfo to the planner.
- Complete is a suggestion, requiring observed task-specific evidence or explicit user
  confirmation before success. Missing evidence must not silently complete a goal.

Pending agreement: names/visibility and wrapper vs adaptation, wire schema/prompt limits,
session/request lifecycle, completion evidence and fallback rules. Merge contracts during M0
before dependent work. This task introduces no Kotlin APIs.

## Proposed guidance transitions

| State / trigger | Next behavior |
| --- | --- |
| Idle + goal | AwaitingScreen; request fresh usable observation |
| AwaitingScreen + usable snapshot | Planning with captured key/request/goal generation |
| Planning + valid Next | Highlight target → AwaitingUserAction |
| AwaitingUserAction + screen change | Clear target, refresh → Planning |
| Planning + Complete | AwaitingConfirmation; check observed evidence or ask user |
| AwaitingConfirmation + corroboration | Completed |
| Planning + Unable/runtime failure | Error with reason; retry fresh or stop |
| Active state + invalidation/lock/disconnect | Clear target, cancel request → AwaitingScreen; resume only with fresh data |
| Any state + stop/new goal | Cancel old request, clear target → Idle / AwaitingScreen |

State names are proposed. Unchanged observations must not repeat inference while awaiting the
user. Same-screen actions with no observed change need manual recheck/confirmation; a screen
change alone cannot prove task success.

## Proposed model lifecycle

For future guidance integration, LocalAI's proposed states are Unloaded → Loading → Ready or
Failed, with explicit resource close. The current isolated component has no published state
flow. Provision the documented INT4 `.litertlm` candidate privately using ADB; the APK has no
model. Exact binary/runtime/device compatibility still requires physical validation.

Initialize/infer off the main thread, bound/serialize requests and handle cancellation without
leaving old callbacks eligible to draw. Reuse the loaded model within the agreed session;
close on owner teardown. Test actual runtime cancellation/close behavior. Record model/backend/
device, load/inference timing and memory before setting budgets. Missing/corrupt-model handling
and fallback stay local. Developer downloads are separate from offline acceptance: the
installed, provisioned app must restart and infer without a connection.
