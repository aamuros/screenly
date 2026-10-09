# Screenly architecture

## Implemented foundation

One Kotlin application module (`com.screenly.app`); no backend, database or network permission.

```text
MainActivity (Compose) → Android accessibility settings / enabled-service status
Accessibility events → ScreenlyAccessibilityService → ScreenObservation
                                                     ↓
                                              ScreenlyOverlay
                                      bubble → four-action floating menu
                                         │                 │
                                    manual picker    ScreenlyFeaturePanel
                                         │                 │
                                   pass-through       explicit screenshot
                                     highlight        and accessible labels
                                         ↓                 ↓
                                   user interacts    offline validated guidance
```

The service observes the active, otherwise focused, **application** window, with a fallback to the previously observed app if the floating input panel takes focus; overlay windows
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

### Compact panels and screenshot boundary

The outlined 72dp bubble docks to the nearest horizontal edge while retaining its
vertical release position. Its 220dp menu expands into compact native Ask AI, Explain,
Guide Me and Privacy cards. These 280dp-wide panels scroll internally above 300dp in
height. They accept keyboard input and allow touches outside their bounds to pass through.
The existing touch-through manual picker remains reachable under Guide Me.

The accessibility service declares `canTakeScreenshot`. Screenly requests a screenshot
only after Ask Send, Explain, or Refresh. Capture first hides the panel and keyboard,
then validates and immediately releases the in-memory hardware buffer. Guide Me uses no screenshots. It never saves or
uploads screen images. Service disconnect invalidates outstanding capture requests.
Protected screens can refuse capture, and users may need to re-enable Screenly when the
new accessibility capability is added.

`AccessibleScreenAssistant` remains the accessibility-label helper for Ask/Explain.
Guide Me uses `GuidanceController` → `NavigationEngine.decideCandidates` → `LocalInference`.
The overlay owns one runtime and coroutine scope per service session. Initialization, inference
and cleanup run off the main thread; native calls are serialized and cannot be preempted.
Model and deterministic rule decisions use the same safety policy and retain distinct provenance.

`GuidanceSnapshot` copies observations and candidate lists, enriches unlabeled clickable rows
using copied ancestry, and retains original indices and bounds. Session/revision/request/goal
checks reject late work; a fresh capture and eligibility/intent validation precede drawing.
Unchanged screens wait for manual action. Unavailable roots clear targets and retain the goal;
lock, own activity, interruption, rotation and teardown stop guidance and remove overlays.
Guide Me needs no screenshot. Its completion button records user confirmation and explicitly
does not claim independently verified success. See [current evidence](GUIDANCE_INTEGRATION.md).

## Actual data and revision contracts

| Existing type | Actual fields / behavior |
| --- | --- |
| `AccessibleUiElement` | Nullable `text`, `contentDescription`, `className`, `viewId`; Boolean `clickable`, `enabled`, `checked`, `scrollable`; Int `left`, `top`, `right`, `bottom` in screen pixels; nullable copied `parentIndex` |
| `ScreenObservation` (internal) | `packageName: String`, `windowId: Int`, `elements: List<AccessibleUiElement>`; structural equality; no timestamp/version or element ID |
| `ScreenObservationState` (internal) | Nullable `snapshot`, `revision: Long` starting at zero; private setters; `update`, `invalidateSelection`, `clear`, `canSelect` |

`update` increments revision on changed snapshots. Navigation/target scroll/window invalidation
increments it even if the next hierarchy is identical. `clear` removes the snapshot and
increments revision. `canSelect` checks revision/snapshot equality, membership, enabled/clickable
state and positive bounds. Overlay checks also require display intersection and current picker
identity, with a refresh before highlighting. A→B→A cannot restore an old picker selection
within that state instance.

Manual selection uses the existing revision counter. AI snapshots add a process-local,
monotonically allocated session ID for each overlay owner. The controller additionally tracks
goal versions and request IDs. A reconnect cannot revive work owned by an earlier overlay.
Observation still requires an interactive display and unlocked keyguard.

## Shared API work still proposed

The single-module prototype now has a controller and local candidate adapter under the user's
explicit integration authorization. It has not implemented the generic shared Planner,
MockPlanner, LlmPlanner or RulePlanner API proposed below, nor merged those contracts into main.
The service still schedules observations with Handler; coroutine jobs manage local planning.
AI proposes candidates; Android owns identity, freshness, coordinates, lifecycle and drawing.

## M0 contract proposal — requires joint agreement and implementation

Reuse existing fields instead of introducing incompatible duplicates. **ScreenElement** is
the shared conceptual name for `AccessibleUiElement`; agree whether to retain its name or add
an alias (none exists). **ScreenSnapshot** would wrap a copied observation with a version key.
This generic Planner API remains a documentation proposal. The current prototype uses
`GuidanceSnapshot` and the implemented `SnapshotKey(sessionId, revision)` instead:

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

Formal shared-contract agreement remains open. The authorized prototype implements its
session/request lifecycle and candidate adapter on this branch; it does not merge these
contracts into main. Review the implemented names, schema, limits, completion policy and
fallback rules before shared-contract adoption.

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

LocalAI: Unloaded → Loading → Ready or Failed, with explicit resource close. Provision a
compatible artifact locally before inference; current APK has none. Choose import/bundling
and format after testing runtime/device compatibility; do not assume any Gemma export works.

Initialize/infer off the main thread, bound/serialize requests and handle cancellation without
leaving old callbacks eligible to draw. Reuse the loaded model within the agreed session;
close on owner teardown. Test actual runtime cancellation/close behavior. Record model/backend/
device, load/inference timing and memory before setting budgets. Missing/corrupt-model handling
and fallback stay local. Developer downloads are separate from offline acceptance: the
installed, provisioned app must restart and infer without a connection.

## Local AI backend merged from feat/local-ai

The isolated `ai/LocalInference` native inference component and `ai/navigation` experiment/evaluation helpers
are now present alongside the existing guidance UI. Consult [LOCAL_AI.md](LOCAL_AI.md),
[NAVIGATION_BACKEND.md](NAVIGATION_BACKEND.md), and [M4_PLAN.md](M4_PLAN.md).
Guide Me now binds the runtime lazily to candidate generation. Candidate YES/NO judgments are
attached to trusted original indices; the legacy TAP/NONE evaluator remains available for
comparison. English Settings routes are conservative and documented in
[GUIDANCE_INTEGRATION.md](GUIDANCE_INTEGRATION.md).
