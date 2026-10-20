# Verification

## Automated checks

Use JDK 17 and run from the repository root:

```sh
./gradlew lint testDebugUnitTest assembleDebug assembleRelease
```

With an emulator or device available:

```sh
./gradlew connectedDebugAndroidTest
```

JVM tests cover selected-first app ordering with custom times prioritized within each group, snooze transitions, duplicate events/disconnects, notification gating, cleanup failure, safe-launch ordering, reconnect-only recovery, enabled-accessibility-list parsing/preservation, and usage-history reconstruction including ongoing and midnight-crossing sessions, screen-off and reboot boundaries, and DST day boundaries. Policy tests verify the entry sample, the deadline re-sample, query-delivery delay accounting, the final 30-second allowance threshold, deadline cancellation on lock or uncertain foreground, lock matching the launcher without inheriting the app, fresh reads after app return and day/clock/allocation changes, unavailable-query recovery on the next evaluation, and cancellation/stale-result protection. Compose coverage includes the minimal Home hierarchy and rainbow/desaturated ON/OFF wordmark, feature navigation/back, disabled Night Light, Setup routing and information block, the Home-only author link, policy mapping, direct duration wheels with zero defaults, draft cancellation, and the 24-hour/global-override boundary, quick duration choices, optional Usage Access in Setup, the Usage Access banner for positive delays, Auto-snooze snackbars, safe launch, inline recovery UI, search, and narrow 200% text scrollability. Notification instrumentation verifies that Resume is an explicit receiver action rather than an activity launch and routes to direct recovery.

## Device setup

Disable and uninstall any old `dev.grayscale.manager` build, then install Graycie fresh. Grant secure settings, enable the Graycie accessibility component, and choose two selected apps plus one unselected app.

```sh
adb shell pm grant now.abhi.graycie android.permission.WRITE_SECURE_SETTINGS
adb shell settings get secure accessibility_display_daltonizer
adb shell settings get secure accessibility_display_daltonizer_enabled
```

For delayed grayscale, grant Usage Access either through **Settings → Special app access → Usage access** or:

```sh
adb shell appops set now.abhi.graycie android:get_usage_stats allow
```

Grayscale must report mode `0` and enabled `1`. Color destinations must use the saved non-grayscale mode and enabled state; if the saved mode is grayscale, color must report enabled `0`. Master-off, service cleanup, and snooze must restore the saved mode and enabled state when Graycie previously took control, unless a newer manual correction exists. With management off and no pending cleanup, existing Color correction must remain unchanged.

| Scenario | Expected |
| --- | --- |
| Install beside/over the old identity | Not an upgrade: old preferences, grant, and accessibility enablement do not transfer |
| Existing Color correction enabled, Graycie master off | Enabling, reconnecting, or disabling Graycie's accessibility service preserves the original mode and enabled state |
| Existing Color correction enabled, auto-snooze while master off | Automatic snooze, Open safely, and recovery preserve the original correction |
| Correction manually enabled after Graycie is turned off | Subsequent service disconnect/reconnect leaves it unchanged |
| Non-grayscale correction enabled before management | Color apps and Graycie itself use the saved correction in both policies; grayscale apps and Home still use monochromacy |
| Grayscale enabled before management | Color apps disable correction; stopping or snoozing restores the original grayscale |
| Non-grayscale correction selected but disabled before management | Color apps restore its mode with correction still disabled |
| Existing enabled or disabled correction, Graycie stops or snoozes | Original mode and on/off state return, including after process restart |
| User changes correction during management | Cleanup keeps the newer manual correction |
| Graycie cleanup fails, then process restarts | Cleanup responsibility survives; reconnect retries cleanup once permission/provider access returns |
| Selected ↔ unselected | Color follows both policy modes without repeated duplicate writes |
| Manager | Always color and non-selectable |
| Completed Home gesture | Home follows the policy after the launcher is confirmed |
| Cancelled navigation-pill gesture | Keeps the current app state without flicker |
| Launcher-hosted Overview | Home follows the policy; selecting an app applies its policy |
| System UI-hosted Overview | Retains the current state when no portable destination can be identified |
| Launcher catalog row | Active Home is selectable and follows the policy |
| Cold or resumed app | Destination applies after settlement |
| Accessibility reconnect or process restart | Reclassifies the current windows and restores the correct color without switching apps |
| Rapid app cancellation | Only the newest event burst can apply |
| Search overlay / keyboard | Retains current state unless a launchable destination settles |
| Notification shade | Retains current color and suspends the pending deadline; returning to the same app reads fresh usage |
| Persistent app-layer overlay | Retains current state after the retry window |
| Split screen | Focused app applies despite the divider |
| PiP over Home or another app | Unfocused PiP does not override the focused app; a focused PiP app follows its own policy |
| Lock/unlock | Lock matches the launcher's policy state (including its allowance), cancels the deadline, and forgets the app; unlock reclassifies the current windows |
| Activity removed from Recents | Accessibility service continues independently |
| Policy or selection changes | Applies immediately; selections survive policy switches |
| Delayed app, Usage Access missing | Stays color; Setup provides optional step **03: Allow Usage Access**; Immediate apps still gray |
| Delayed app, Usage Access granted | Uses Android's usage today, including use before Graycie started and while gray; grays at the allowance |
| App left continuously open | Reads history once on entry and applies gray at the scheduled deadline with no intermediate queries; stops once gray; returning to the app or a date-change event reads fresh history |
| Usage Access revoked while delayed | Next evaluation stays color and refreshes permission state after resume or unlock; the action remains in Setup |
| Permission revoked | Management disables and setup/error state appears without a crash |
| Large text / narrow screen | All content remains scrollable and controls remain usable |
| First snooze selection on API 33+ | Permission prompt appears; denial does not save the selection |
| Notifications/channel disabled | Snooze refuses to disable accessibility and shows a warning |
| Automatic configured-app open while manager is on | Recovery posts with **Grant & Re-enable**, previous correction restores, then accessibility disables best-effort |
| Automatic configured-app open while manager is off | Recovery still posts with **Grant Permission**, then accessibility disables best-effort |
| Duplicate window/lifecycle events | One notification/cleanup; master remains enabled and snoozed |
| Open safely | Accessibility is confirmed absent before the banking activity starts |
| Safe disable refusal | Target does not launch; recovery/error remains visible |
| Notification body tap | Graycie opens but recovery does not begin |
| Notification recovery while Graycie is closed | Private receiver grants access directly with one tap; activity is not required; the prior master state is preserved |
| In-app recovery callout | Appears only for a pending snooze and starts the same direct recovery flow |
| Recovery refusal / permission loss / timeout | Snooze and notification remain; Accessibility settings is available |
| Manual accessibility re-enable | Service reconnect clears persisted snooze and notification |
| Master switch while snoozed | Shows the pre-snooze state and stays locked until permission recovery completes |
| Reboot / package replacement while snoozed | Recovery notification is reposted |
| Another accessibility service enabled | TalkBack/reader entry and ordering remain unchanged |

Revoke permission for the failure test, then regrant it:

```sh
adb shell pm revoke now.abhi.graycie android.permission.WRITE_SECURE_SETTINGS
```

Force-stop is distinct from dismissing the activity and Android may skip service cleanup. If needed, restore color manually:

```sh
adb shell settings put secure accessibility_display_daltonizer_enabled 0
```

Run device coverage on API 26, API 33, and the current target API. Press each notification recovery variant while Graycie is closed and verify the activity is not required, service reconnection completes, the previous master state is preserved, and the notification disappears only after connection. Exercise refusal and the eight-second timeout and confirm recovery remains. Validate at least one affected banking app with the manager both on and off, and record whether automatic detection succeeds before its compatibility check or requires retry. Acceptance always requires Open safely to remove this service before the banking activity becomes foreground. Also revoke notification permission, disable the recovery channel, reboot while snoozed, and replace the Graycie package while recovery is pending.
