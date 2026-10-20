# Local state and lifecycle

## Persisted configuration

Graycie stores configuration locally in the `grayscale_manager` preferences file. The durable user intent consists of:

- whether grayscale management is enabled;
- the active grayscale policy;
- selected grayscale packages;
- the default daily grayscale allowance and any per-app overrides;
- selected Auto-snooze packages; and
- the package for a pending stable snooze.

The controller also records limited operational state, such as the last recognized package, last applied grayscale value, and a recoverable error. These values help avoid redundant writes and surface failures, but are reset when a service reconnection makes them unreliable.

Accumulated usage totals are not persisted by Graycie. When a delayed destination becomes active, the accessibility service samples Android usage history for the current destination and either applies grayscale or keeps one cancellable deadline callback for the remaining allowance. The sample carries a monotonic timestamp, so the deadline includes time spent waiting for the query. When the callback fires, the service samples usage again and turns the still-active app gray only if the allowance is exhausted. The deadline is canceled and history is read fresh on app changes and returns, reconnect, configuration edits, and date/clock/timezone changes; it is suspended while the classifier cannot confirm the same app is active. Lock or screen-off applies the launcher's current policy state, including its allowance, cancels the deadline, and forgets the foreground app; it never schedules a new lock-screen deadline. Unlock starts from a clean state and reclassifies the current windows, so grayscale returns only when a matching app is foreground again. It stops once gray. Completed sessions earlier in the day remain counted across device reboots; unfinished sessions with an unknown shutdown boundary are discarded rather than charging powered-off time. Android queries run off the main thread. The obsolete `grayscale_daily_usage` preference file is no longer read or written and is left inert. The global allowance and per-app overrides remain in `grayscale_manager`.

A separate persisted flag records whether Graycie took control of Color correction. It is saved before attempting a managed setting change and cleared only after successful cleanup. It survives service reconnections and failed cleanup, so Graycie can clean up its own changes without disabling an unrelated correction while management is off.

There is no database, account, cloud sync, analytics, or network storage.

## State ownership

`ManagerController` is the single state owner. It serializes mutations on Android's main thread and publishes immutable `ManagerSnapshot` values through a read-only `StateFlow`. `ManagerViewModel` combines those snapshots with transient interface state such as search, loading, and mutation feedback.

Policy evaluation and secure-setting access are kept behind small, tested boundaries. This allows policy decisions, foreground classification, safe setting edits, and snooze transitions to be tested without depending on the full Android UI.

## Lifecycle behavior

On activity resume, Graycie marks its own screen as the current color destination, refreshes permissions and Accessibility status, and reloads the installed-app catalog.

An unexpected Accessibility service removal attempts to restore the previous Color correction only if Graycie took control of Color correction, keeps the user's enabled preference, and reports the missing service, so reconnecting resumes management. A disconnect caused by an intentional snooze also preserves the user's enabled preference and pending recovery, including when that preference was already off. When the service reconnects, Graycie clears stale foreground/applied-state assumptions and completes any pending recovery without changing the user's master-switch preference. Off-state service connections, disconnections, and snooze leave existing Color correction alone unless cleanup from earlier management remains pending.

A separate recovery preference file stores the original correction, the last values written, and any pending write, including whether either setting was originally absent. Each entry is saved synchronously before changing secure settings. Restoration disables correction before switching modes, then restores the saved on/off state. A newer manual correction is left alone. Completed recovery is recorded so repeated cleanup cannot undo a restored grayscale preference.

Provider failures are fail-safe: previous Color correction is restored when permission remains available, the error is exposed to the interface, and the master intent is retained for the next evaluation. Unchanged secure-setting values are not rewritten.
