# Foreground detection

## What is detected

Graycie uses its Accessibility service to determine the focused launchable app from interactive-window metadata. Only window type, focus/active state, picture-in-picture state, and root package name are copied into the classifier.

It does not read window titles, screen text, view content, or other node data. It also does not use Usage Access for foreground classification, screenshots, gestures, overlays, a polling loop, or a foreground service. Delayed grayscale reads Android usage history separately, off the main thread, when a delayed destination becomes active and again at the single cancellable deadline; it never polls history or foreground identity. When the classifier cannot confirm the settled app, it retains the current color and suspends the deadline until the app is confirmed again.

## Stable transitions

Window events are handled with a short trailing delay, then a destination must appear in two matching samples before its policy is applied. Newer events invalidate checks started by older events.

This settling step prevents transient windows during app switches and navigation gestures from flipping Color correction. When multiple non-PiP applications remain layered, Graycie retains the current color and rechecks briefly. A layer that persists through the retry window is treated as an overlay and causes no setting change.

Missing roots, non-launchable transient packages, and opaque System UI surfaces are also treated as indeterminate. In each case the existing color state is retained rather than applying a speculative result.

## Multi-window behavior

- A split-screen divider allows the focused application to settle even when multiple apps are visible.
- Unfocused picture-in-picture windows do not block the full-size focused application from settling.
- A focused picture-in-picture app follows its own policy like any other app. Android Color correction is system-wide, so the focused destination decides the single active correction.
- Home settles only when the resolved Home package owns focus or activity without another non-PiP app window.
- Overview is grayscale when it is hosted by the resolved Home package.

Home is resolved from Android's current default Home role/intent, with API-appropriate behavior from Android 8 onward. No launcher, assistant, System UI, or OEM package name is hard-coded.

Debug builds log package names and classification reasons only; they do not log content from the screen.
