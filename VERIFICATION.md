# Milestone 2 verification — 2026-10-09

## A. Build Results

| Check | Result |
| --- | --- |
| `./gradlew :app:assembleDebug` | PASS |
| `./gradlew :app:testDebugUnitTest` | PASS — 15 tests, no failures/errors/skips |
| `./gradlew :app:lintDebug` | PASS — 0 errors; 10 existing dependency-version notices |

These tasks were run together after the final application-code fix. The updated debug APK
was installed on the running emulator. No dependencies, Gradle settings, manifest permissions,
or accessibility capabilities were added in this audit.

## B. Accessibility Tests

`adb devices -l` was checked at the beginning and during testing. Only `emulator-5554`, a
Pixel Tablet running API 35, was available. **No physical-device tests were performed.**

Verified on the emulator: active Settings application/window identification; extraction of
text, content descriptions, classes, IDs, enabled/clickable/scrollable flags and screen bounds;
missing labels and IDs; navigation detection; exclusion of Screenly's activity and overlay
windows; and safe clearing when a Settings page returned no usable root. Live observations
included non-editable labels/descriptions, clickable containers and scrollable lists.

Inspected in code: password/sensitive-subtree filtering before label reads, editable-value
omission, null-root/child handling, node/window recycling on API 30–32, and no retained event
or node references. API 33+ pooling is disabled and deprecated recycling is avoided.
Traversal stops at depth 40 and a budget of 500 real nodes/null child slots. Capture scheduling
coalesces event types without starvation; event-driven captures have a 250 ms minimum interval.
User-requested picker refreshes deliberately bypass that interval. Duplicate snapshots do not
repeat element logging. Timing and duplicate/invalidation policies have unit coverage; a
formal CPU/ANR performance benchmark was not run.

Not verified: live `checked=true` or disabled controls, injected stale-node failures,
physical devices, API 30–32 node pooling, or arbitrary third-party application hierarchies.
False checked values were observed; checked-state extraction was inspected. Do not infer
that every app or every Settings control exposes usable accessibility data.

## C. Overlay Tests

Verified on the updated emulator APK:

- One floating bubble, a separate picker, and a separate transparent highlight window.
- Five repeated bubble open/close cycles without reopening or duplicate windows.
- Accurate rectangle alignment in both landscape and portrait, including the Settings
  detail pane's nonzero screen origin.
- Tapping inside the portrait rectangle opened the underlying Internet control.
- Native window diagnostics showed `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_TOUCHABLE`
  on the full-screen highlight, and a touchable/non-focusable bubble.
- Navigation and eight rapid alternating Settings launches removed the old rectangle and
  finished with one bubble. Scrolling removed a selected highlight.
- Rotation removed the selection and positioned one bubble in the new display bounds;
  the original free-rotation setting was restored.
- Screen-off removed all overlay windows; waking the emulator restored one bubble with no
  stale rectangle. This emulator did not require a PIN: **secure-keyguard behavior is untested**.
- Entering Screenly removed overlays; returning to Settings recreated one bubble.
- Force-stopping Screenly removed all windows. Android also disabled its service; explicit
  re-enabling restored one bubble. Automatic restart after a user force-stop is not expected.
- Normal service disable/re-enable removed picker/bubble windows, unbound the service, and
  created a clean bubble on reconnection. No app-process fatal exception was logged in that check.

Bubble dragging was verified in the preceding emulator smoke test. The drag path is preserved
with an added guard for an already-detached window. Physical touch behavior, cutouts, OEM
display/window policies, and multiple displays remain untested.

## D. Issues Found

| Issue | Severity / evidence | Root cause | Applied fix |
| --- | --- | --- | --- |
| Bubble tap reopened the picker instead of closing it | Medium; reproduced on emulator | Picker's outside-touch dismissal arrived before the bubble click | Remember the originating touch's down time and whether that click should close the picker; five-cycle regression passed |
| Null child slots escaped the traversal budget | Medium; confirmed in source | Only real nodes incremented the counter | Count null child attempts toward the 500-slot budget |
| Every callback traversed/logged the tree, including duplicates | Medium; confirmed in source | Android's notification timeout did not provide an app-wide capture deadline or log deduplication | Coalesce captures with a bounded 250 ms interval; log only changed snapshots; cache the normalization regex |
| Old picker callbacks could match a screen revisited later | Medium; source race risk | Snapshot equality alone could accept an A→B→A transition or a dismissed picker | Require a current revision and the still-current picker instance; unit tests cover navigation, clearing and reconnect-like transitions |
| No explicit screen-off/keyguard observation policy | Medium; source safety gap | Observation depended only on accessibility window events | Guard interactive/unlocked state, receive screen-off/unlock broadcasts, and cancel captures/clear snapshots on screen-off |
| Detachment could throw during cleanup | Medium; source crash risk | Cleanup assumed WindowManager still owned each view | Clear references first; tolerate already-detached views; dispose old controllers and guard stale clicks/drag updates |
| Off-screen coordinates remained selectable | Low; source validation gap | Positive width/height did not prove display intersection | Reject inverted/empty/outside-display bounds; allow partially visible controls; unit tested |
| Truncation could split an emoji | Low; deterministic defect | UTF-16 `take(160)` could leave a dangling high surrogate | Drop a truncated high surrogate and normalize Unicode separators; unit tested |
| Internet page supplied no usable root on this emulator | Runtime limitation; root cause not established | Framework/target hierarchy unavailable to this service | Preserve safe clearing and empty-root handling; investigate on a physical device rather than bypass privacy filtering |

The audit-risk entries describe safeguards added for code paths that were unsafe or unguarded;
they are not claims that those races or privacy failures were observed on a physical device.
Lint's four custom-view advisories were resolved with normal Canvas KTX usage and documented
suppressions for programmatic construction, absolute screen gravity, and outside-touch handling.
Existing dependency versions were preserved.

## E. Files Modified

- `ScreenlyAccessibilityService.kt`: coalesced capture scheduling, changed-snapshot logging,
  lock/screen-off guards, receiver/callback cleanup, and bounded null-child traversal.
- `ScreenlyOverlay.kt`: picker toggle fix, selection revisions, stale-instance/disposal guards,
  coordinate checks, idempotent detachment, and custom-view lint handling.
- `ScreenObservation.kt`: Android-independent snapshot/revision policy and capture-delay logic.
- `AccessibleUiElement.kt`: screen-intersection validation and cached, Unicode-safe normalization.
- `ScreenObservationStateTest.kt`: eight focused tests for transitions, stale selection,
  coordinates and capture deadlines.
- `ObservationSanitizerTest.kt`: two additional Unicode boundary/whitespace regression tests.
- `README.md` and `VERIFICATION.md`: updated behavior, evidence and physical validation steps.

## F. Remaining Risks and Physical Checklist

Physical-device behavior is the readiness gate. Only API 35 was exercised; older supported
Android versions, secure lock screens, cutouts, OEM service restrictions, and other apps need
validation. Hierarchies can be incomplete, sensitive, empty, or temporarily unavailable.
Filtering depends on the target app's accessibility metadata; ordinary non-editable labels
still appear in debug Logcat. Accessibility delivery and main-thread/platform load determine
actual clearing latency. Content changes clear on the next changed snapshot; navigation and
target scrolling invalidate selection as soon as their events reach Screenly. Future work
must respect snapshot revisions and must not assume that bounds or hierarchy data remain current.

Perform this checklist on a phone running API 30 or newer:

1. Enable Developer options and USB debugging, connect a data-capable USB cable, unlock the
   phone, and authorize the computer. Identify its serial, model and Android API:

   ```sh
   adb devices -l
   adb -s SERIAL shell getprop ro.product.model
   adb -s SERIAL shell getprop ro.build.version.sdk
   adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. Open Screenly → **Open Accessibility Settings** → **Downloaded apps/Installed services**
   → **Screenly** → **Use Screenly**. Accept Android's prompt. If sideloading is restricted,
   use Screenly's system **App info** menu → **Allow restricted settings**, then retry.
   Return to Screenly and verify **Enabled**.
3. Open Settings and navigate between two pages while observing:

   ```sh
   adb -s SERIAL logcat -s ScreenlyAccessibility:D AndroidRuntime:E '*:S'
   ```

   Confirm package/class/label/description/state fields and pixel bounds. Look for an enabled
   clickable row, a scrollable list, and a checked control where metadata permits. Unchanged
   snapshots are intentionally not logged repeatedly. Missing labels/IDs must not crash.
4. Tap **S**, choose an enabled row/button, and compare the rectangle with its actual edges.
   Tap inside the rectangle and confirm the underlying control opens. Select again, scroll,
   navigate rapidly, or switch apps; the old rectangle must clear. Never use old coordinates
   from Logcat to actuate another screen.
5. Repeat bubble open/close at least five times, use **Clear highlight**, and drag the bubble
   away from a control. Confirm app touches outside the picker work and no duplicate bubble
   appears. Inspect `adb -s SERIAL shell dumpsys window windows` if needed: the highlight must
   include `NOT_TOUCHABLE`; the bubble must not.
6. Rotate portrait↔landscape with a highlight selected; expect clearing, correct bubble bounds,
   and fresh alignment after selecting again. Test a control near a status bar/cutout.
7. Lock a phone that already has a PIN/password, wake it while still locked, then unlock
   normally. Screenly must not display target overlays or observe behind the locked screen;
   unlocking must restore a fresh bubble without an old rectangle. Unlock normally on the
   phone; never paste the PIN/password into Codex or diagnostic logs.
8. Open Screenly, background it, and return to Settings. Disable the service with a picker
   open; all windows must disappear. Re-enable and check exactly one fresh bubble. Repeat.
   If force-stop is tested, expect Android to disable the service and re-enable it manually.
9. Visit a screen with insufficient accessibility data; expect no stale rectangle and safe
   recovery after returning to a readable page. Record failures with model/API, target page,
   steps and the relevant sanitized diagnostics.

## G. Milestone 2 Readiness

**CONDITIONALLY READY.** Automated checks and the stated emulator checks passed, and no critical
app crash was observed. Required physical-device validation is incomplete. Do not treat the
emulator or Gradle results as physical-device proof. Milestone 3 was not implemented.
