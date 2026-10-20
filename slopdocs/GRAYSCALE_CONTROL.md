# Grayscale control

## Master control

The Home screen provides one accessible OFF/ON control for grayscale management. Enabling it starts policy-based switching once setup is complete. Disabling it immediately restores color and stops responding to foreground changes.

Graycie itself always remains in color so its controls stay usable. Home follows the same policy as any other app and is selectable.

## App policies

The Grayscale Control screen implements two policies:

- **Only These** makes selected apps grayscale and leaves other settled apps in color.
- **Except These** makes selected apps stay in color and makes other settled apps grayscale.

The selected package set is shared by both policies. Switching policy does not discard selections, and policy or selection changes are evaluated immediately against the current settled destination.

## App selection

The selector shows launchable apps for the current user with their actual icon and localized label. Users can search by app name or package name. Selected apps appear first in both policies. Within the selected and unselected groups, apps with custom time overrides appear first, preserving locale-aware ordering within each subgroup. Search still filters every group.

Graycie and the active Home app are excluded from effective selection. The catalog also includes launcher packages that may not expose a normal app-drawer entry, which allows Home to be identified without hard-coded vendor package names.

## Delayed grayscale

The Grayscale Control screen also sets a daily allowance before a grayscale target is applied. Tapping the global allowance or an app's allowance opens quick choices (**Immediate**, **5**, **10**, **15**, **20**, **30**, and **60 minutes**) plus hour/minute wheels. The default is **0 hours, 0 minutes** (**Immediate**); editing an allowance starts from its current value. Users can choose any whole-minute duration up to 24 hours, tap **Set** to save, or **Cancel** to discard changes. Each eligible app inherits the global value until overridden; **Use global** clears the override. The global dialog also offers **Reset all to Immediate**, which immediately sets the global allowance to zero and removes every per-app override in one update. A later global duration then applies to every policy target. Ordinary global edits preserve per-app overrides; per-app dialogs retain Immediate and Use global.

A positive delay requires Android Usage Access. Graycie reads today's foreground time from Android usage history, including usage before Graycie started and time spent in an already-gray app. When an app becomes active, Graycie reads usage once and either applies grayscale immediately or schedules one cancellable deadline for the remaining allowance. The deadline includes query-delivery time; when it fires, Graycie reads usage again and turns the still-active app gray only if the allowance is exhausted. It stops once gray. Date/clock changes, app returns, reconnect, and configuration changes cancel the old deadline and read fresh usage, including on a new day. Lock applies the launcher's current policy state, including its allowance, and forgets the foreground app; it never schedules a new deadline. Unlock reclassifies and only reapplies grayscale when a matching app is foreground. The deadline pauses while the classifier cannot confirm that the same app is active, and returning to a confirmed app reads fresh usage. Thirty seconds or less remaining is treated as exhausted to avoid a brief color flash. If Usage Access is missing or the history query fails, the delayed app stays in color and Grayscale Control reports the issue. The optional third step in Setup opens Usage Access settings. Immediate rules do not require Usage Access.

Usage history is package-level and can differ from the focused-window policy in multi-window mode; Graycie still chooses the destination from accessibility focus and uses history only for the allowance. Reconstruction looks back at most 24 hours to find a session crossing midnight. On API 26–27, Android does not expose screen/keyguard usage events, so activity background events are the screen boundary. If retained history cannot establish a boundary, the user is locked, or the query fails, the sample is treated as unavailable rather than zero.

## Special destinations

Home follows the same policy as any other app. Launcher-hosted Overview is treated the same way. If an OEM exposes Overview only as an indeterminate System UI surface, Graycie retains the current color rather than guessing.
