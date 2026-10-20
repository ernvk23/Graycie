package now.abhi.graycie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWindowClassifierTest {
    private val classifier = ForegroundWindowClassifier()
    private val apps = setOf("app.one", "app.two", "pip")

    private fun app(packageName: String, focused: Boolean = true, pip: Boolean = false) =
        WindowMetadata(packageName, focused, focused, 1, pip)

    @Test fun settledApplicationIsAccepted() {
        val result = classifier.classify(listOf(app("app.one")), apps)
        assertEquals("app.one", (result as ForegroundClassification.Settled).packageName)
    }

    @Test fun focusedOwnWindowIsAuthoritative() {
        val own = "now.abhi.graycie"
        assertTrue(classifier.isOwnWindowFocused(listOf(app(own)), own))
        assertFalse(classifier.isOwnWindowFocused(listOf(app(own, focused = false)), own))
        assertFalse(classifier.isOwnWindowFocused(listOf(app(own, pip = true)), own))
    }

    @Test fun backgroundAppBehindFocusedAppDuringGestureIsTemporary() {
        val gesture = listOf(app("app.one"), app("app.two", focused = false))
        assertTrue(classifier.classify(gesture, apps) is ForegroundClassification.Temporary)
    }

    @Test fun systemUiOverAnAppIsNotMistakenForDestination() {
        val overlay = listOf(
            WindowMetadata("system.ui", true, true, ForegroundWindowClassifier.WINDOW_TYPE_SYSTEM),
            WindowMetadata("app.one", false, false, ForegroundWindowClassifier.WINDOW_TYPE_APPLICATION),
        )
        assertTrue(classifier.classify(overlay, apps) is ForegroundClassification.NonLaunchable)
    }

    @Test fun layeredDestinationRetriesThenBecomesOverlay() {
        val windows = listOf(app("app.two"), app("app.one", focused = false))
        assertTrue(classifier.classify(windows, apps) is ForegroundClassification.Temporary)
        assertTrue(classifier.classify(windows, apps, retryExpired = true) is ForegroundClassification.Overlay)
    }

    @Test fun splitScreenAcceptsFocusedApp() {
        val windows = listOf(
            app("app.two"), app("app.one", focused = false),
            WindowMetadata(null, false, false, ForegroundWindowClassifier.WINDOW_TYPE_SPLIT_SCREEN_DIVIDER),
        )
        assertTrue(classifier.classify(windows, apps) is ForegroundClassification.Settled)
    }

    @Test fun pictureInPictureDoesNotBlockDestination() {
        val result = classifier.classify(listOf(app("app.two"), app("pip", false, true)), apps)
        assertEquals("app.two", (result as ForegroundClassification.Settled).packageName)
    }

    @Test fun focusedPictureInPictureAppIsSettled() {
        val result = classifier.classify(listOf(app("app.one", focused = false), app("pip", pip = true)), apps)
        assertEquals("pip", (result as ForegroundClassification.Settled).packageName)
    }

    @Test fun focusedPictureInPictureAppOverridesLayeredFullScreenApps() {
        val windows = listOf(
            app("app.one", focused = false),
            app("app.two", focused = false),
            app("pip", pip = true),
        )
        val result = classifier.classify(windows, apps)
        assertEquals("pip", (result as ForegroundClassification.Settled).packageName)
    }

    @Test fun transientsAndMissingRootsAreNotApplied() {
        assertTrue(classifier.classify(listOf(app("keyboard")), apps) is ForegroundClassification.NonLaunchable)
        val missing = WindowMetadata(null, true, true, 1, hasRoot = false)
        assertTrue(classifier.classify(listOf(missing), apps) is ForegroundClassification.Temporary)
    }

    @Test fun stabilizerRequiresTwoMatchingDestinationSamples() {
        val stabilizer = TransitionStabilizer()
        val app = ForegroundClassification.Settled("app.one", "settled")
        assertEquals(TransitionAction.Retry, stabilizer.action(app, 0))
        assertEquals(TransitionAction.Apply, stabilizer.action(app, 100))
    }

    @Test fun changedDestinationRestartsConfirmation() {
        val stabilizer = TransitionStabilizer()
        val app = ForegroundClassification.Settled("app.one", "settled")
        val second = ForegroundClassification.Settled("app.two", "settled")
        assertEquals(TransitionAction.Retry, stabilizer.action(app, 0))
        assertEquals(TransitionAction.Retry, stabilizer.action(second, 100))
        assertEquals(TransitionAction.Apply, stabilizer.action(second, 200))
    }

    @Test fun stabilizerStopsRetryingIndeterminateStateAfterOneSecond() {
        val stabilizer = TransitionStabilizer()
        val temporary = ForegroundClassification.Temporary("transition")
        assertEquals(TransitionAction.Retry, stabilizer.action(temporary, 999))
        assertEquals(TransitionAction.Ignore, stabilizer.action(temporary, 1_000))
    }

    @Test fun nonLaunchableSurfaceIsRecheckedDuringSettlementWindow() {
        val stabilizer = TransitionStabilizer()
        val systemUi = ForegroundClassification.NonLaunchable("system.ui", "transient")
        assertEquals(TransitionAction.Retry, stabilizer.action(systemUi, 999))
        assertEquals(TransitionAction.Ignore, stabilizer.action(systemUi, 1_000))
    }

    @Test fun destinationFirstSeenAtDeadlineStillGetsConfirmationSample() {
        val stabilizer = TransitionStabilizer()
        val app = ForegroundClassification.Settled("app.one", "settled")
        assertEquals(TransitionAction.Retry, stabilizer.action(app, 1_000))
        assertEquals(TransitionAction.Apply, stabilizer.action(app, 1_100))
    }

    @Test fun resetInvalidatesPendingDestination() {
        val stabilizer = TransitionStabilizer()
        val app = ForegroundClassification.Settled("app.one", "settled")
        assertEquals(TransitionAction.Retry, stabilizer.action(app, 0))
        stabilizer.reset()
        assertEquals(TransitionAction.Retry, stabilizer.action(app, 100))
    }

    @Test fun newerEventCancelsOldRetryToken() {
        val session = TransitionSession()
        val old = session.newEvent()
        val current = session.newEvent()
        assertFalse(session.isCurrent(old))
        assertTrue(session.isCurrent(current))
        session.cancel()
        assertFalse(session.isCurrent(current))
    }
}
