# Screenly — Milestone 2

An offline Android accessibility prototype, using one Kotlin/Compose application module
with minimum SDK 30. Native View accessibility overlays provide manual visual highlighting.
There is no AI, screenshot capture, or automatic interaction.

## Build and enable

1. Run `./gradlew :app:assembleDebug`.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on an Android device, for example
   with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Open Screenly and tap **Open Accessibility Settings**.
4. Under **Downloaded apps** or **Installed services** (device wording varies), select
   **Screenly**, turn on **Use Screenly**, and accept Android's accessibility prompt.
5. Return to Screenly to see **Accessibility service: Enabled**, then open Android Settings.

If Android blocks enabling a sideloaded app with **Restricted setting**, open Screenly's
system **App info**, use the menu to **Allow restricted settings**, then try again.

## Manually test the floating assistant

1. Open Android Settings with the service enabled. A round **S** bubble should appear.
2. Tap the bubble to open the native View element picker. It lists the current screen's
   enabled, clickable elements with valid bounds, labelled using their text, description,
   contained label, view ID, or class. Choose a labelled Settings row or button. Choosing an
   element only highlights it; it never activates the control.
3. The picker closes and a blue rectangle should outline the selected control at its actual
   screen position. Tap inside it: Android Settings should receive the tap normally.
4. Scroll, navigate to another Settings page, or switch apps. The previous highlight and
   picker should disappear when Screenly receives the navigation/scroll event or the next
   changed observation. Android notifications have a 300 ms timeout; event-driven captures
   are also coalesced with a 250 ms minimum interval. Tap the bubble to choose a fresh element.
5. Drag the bubble if it covers a control. Dragging moves only the bubble; it does not tap the
   app. Tap the bubble and choose **Clear highlight** to remove a rectangle manually, or
   **Close picker** or tap the bubble again to dismiss the picker while retaining an
   unchanged-screen highlight.
   Touches outside the picker pass to the app and dismiss it.
6. Rotate the device and test alignment again, including a control near the status bar.
   Rotation clears the selection and repositions the bubble within safe screen bounds.
7. Return to Screenly: its own activity should have no bubble or highlight. Switch back to
   Settings to see the bubble again. Disable the service in Accessibility Settings: all
   Screenly overlay windows should disappear. Re-enable it and confirm a clean restart.

The bubble, picker, and transparent highlight use `TYPE_ACCESSIBILITY_OVERLAY` and plain
Android Views. No **Display over other apps** permission is required. Bubble/picker windows
are touchable and non-focusable; the full-screen highlight also has `FLAG_NOT_TOUCHABLE`.
The drawing subtracts the overlay's actual screen origin from accessibility screen bounds
to avoid status-bar offset errors. Only active/focused application windows are inspected;
Screenly overlay windows are excluded. The picker refreshes observations before opening and
again before highlighting. A changed package, app window, element snapshot, scroll, or app
window-state event clears the selection. Revisions reject callbacks from old picker screens,
even after navigating back to an identical hierarchy. Off-screen or empty bounds are rejected.
Only the latest sanitized snapshot is held in memory; unchanged snapshots are not logged again.

Overlay windows and snapshots are removed on service interruption, unbinding, destruction,
rotation, screen-off, an unavailable app window, or entry into Screenly's own activity.
Observation is suspended while the display is off or Android reports a locked keyguard.
This manual
checklist requires a device; a successful build does not verify overlay alignment or touch
pass-through on a phone.

## Verification status — 2026-10-09

See [VERIFICATION.md](VERIFICATION.md) for the audit, fixes, automated results, emulator
coverage, remaining risks, and physical-phone checklist. Build, 15 unit tests, and lint passed.
The API 35 Pixel Tablet emulator passed alignment/tap-through, repeated picker toggling,
navigation/scroll clearing, rotation, screen-off, and lifecycle checks. No physical phone
was connected, so Milestone 2 is **CONDITIONALLY READY**, pending physical-device validation.

## Inspect Logcat

In Android Studio, select the connected device and use the filter
`tag:ScreenlyAccessibility`. Keep Debug-level messages visible. Alternatively:

```sh
adb logcat -s ScreenlyAccessibility:D '*:S'
```

Navigate between Android Settings screens and scroll a list. Each observation starts with
the active root's package name and element count, followed by one line per visible element:

```text
Screen package=com.android.settings elements=42
element[0] AccessibleUiElement(text=Network & internet, contentDescription=null, className=android.widget.TextView, viewId=android:id/title, clickable=false, enabled=true, checked=false, scrollable=false, left=48, top=320, right=620, bottom=384)
```

This is an illustrative example, not captured device output. Bounds are `(left, top, right,
bottom)` in screen pixels. IDs and labels may be null; a clickable parent can contain a
non-clickable text label. Screenly's own interface is ignored. Events cover window state,
window content, window changes, and scrolling, with a 300 ms notification timeout. Traversal
is limited to 500 nodes and depth 40 to bound work on large hierarchies. A missing active
window or child is safely skipped. IDs are requested, but depend on what the target app exposes.

Password nodes and their descendants are skipped before reading labels. On API 34+, nodes
marked accessibility-data-sensitive are also skipped. Editable nodes retain their state and
bounds but omit text/descriptions and descendants. Other labels have control/format characters
removed, whitespace normalized, and length limited to 160 characters. Privacy filtering
relies on the target app's accessibility metadata; ordinary non-editable labels still appear
in debug Logcat. The app retains no observation history, writes no screen contents to files,
and requests no network permission. Observation logging is disabled in release builds.

The status indicates that Android has enabled the service and refreshes when Screenly
resumes; it does not prove that another app exposes a usable hierarchy. Device validation
requires installing the APK and performing the steps above.

## Automated checks

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```
