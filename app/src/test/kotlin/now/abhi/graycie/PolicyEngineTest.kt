package now.abhi.graycie

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PolicyEngineTest {
    private class Store(
        var saved: ManagerState = ManagerState(defaultGrayAfterMinutes = 0),
    ) : StateStore {
        override fun read() = saved
        override fun write(state: ManagerState) { saved = state }
    }
    private class Settings : GrayscaleSettings {
        var permission = true
        var throws = false
        var accepts = true
        var releaseAccepts = true
        var providerThrows = false
        var revokeOnWrite = false
        var releases = 0
        val writes = mutableListOf<Boolean>()
        override fun hasPermission() = permission
        override fun setGrayscale(enabled: Boolean): Boolean {
            if (revokeOnWrite) { permission = false; throw SecurityException() }
            if (providerThrows) throw IllegalStateException("Provider unavailable")
            if (throws) throw SecurityException()
            if (!accepts) return false
            writes.add(enabled)
            return true
        }
        override fun release(): Boolean {
            releases++
            if (throws) throw SecurityException()
            return releaseAccepts
        }
    }
    private val store = Store()
    private val settings = Settings()
    private var serviceEnabled = true

    private class Scheduler : UsageCheckScheduler {
        var action: (() -> Unit)? = null
        var delayMillis: Long? = null
        override fun schedule(delayMillis: Long, action: () -> Unit) {
            this.delayMillis = delayMillis
            this.action = action
        }
        override fun cancel() {
            action = null
            delayMillis = null
        }
        fun fire() {
            val next = action ?: error("No usage check scheduled")
            action = null
            delayMillis = null
            next()
        }
    }

    private inner class UsageSamplerFake : UsageSampler {
        var provider: (String) -> UsageSample = { sample(0L) }
        var autoRespond = true
        var calls = 0
        val pending = mutableListOf<Pair<String, (UsageSample) -> Unit>>()

        override fun sample(packageName: String, onResult: (UsageSample) -> Unit) {
            calls++
            if (autoRespond) onResult(provider(packageName))
            else pending += packageName to onResult
        }
    }

    private var wallMillis = Instant.parse("2026-10-18T00:00:00Z").toEpochMilli()
    private var elapsedMillis = 1_000_000L
    private var zone: ZoneId = ZoneId.of("UTC")
    private var usageAccess = true
    private val scheduler = Scheduler()
    private val sampler = UsageSamplerFake()

    private fun sample(usedSeconds: Long) = UsageSample.Available(
        usedSeconds = usedSeconds,
        dayStartMillis = startOfTodayMillis(wallMillis, zone),
        sampledAtElapsedMillis = elapsedMillis,
    )

    private fun accruingSample(initialUsedSeconds: Long) {
        val initialElapsedMillis = elapsedMillis
        sampler.provider = {
            sample(initialUsedSeconds + (elapsedMillis - initialElapsedMillis) / 1_000L)
        }
    }

    private fun engine() = PolicyEngine("manager", store, settings, { serviceEnabled })
    private val engine = engine()

    private fun timedEngine(defaultMinutes: Int = 10): PolicyEngine {
        store.saved = ManagerState(defaultGrayAfterMinutes = defaultMinutes)
        return PolicyEngine(
            ownPackage = "manager",
            store = store,
            settings = settings,
            serviceEnabled = { serviceEnabled },
            scheduler = scheduler,
            usageSampler = sampler,
            usageAccess = { usageAccess },
            nowWallMillis = { wallMillis },
            nowElapsedMillis = { elapsedMillis },
            zone = { zone },
        )
    }

    private fun start(policy: Policy = Policy.ONLY_SELECTED) = start(engine, policy)

    private fun start(engine: PolicyEngine, policy: Policy = Policy.ONLY_SELECTED) {
        engine.setSelected(setOf("selected", "also.selected"))
        engine.setPolicy(policy)
        engine.setEnabled(true)
    }

    @Test fun onlySelectedTransitionsAndTransientEvents() {
        start()
        engine.onForeground("selected", true)
        engine.onForeground("selected", true)
        engine.onForeground("keyboard", false)
        engine.onForeground("system.ui", false)
        engine.onForeground("also.selected", true)
        assertEquals(listOf(true), settings.writes)
        assertEquals("also.selected", engine.state.lastRecognisedPackage)
        engine.onForeground("other", true)
        engine.onForeground("selected", true)
        assertEquals(listOf(true, false, true), settings.writes)
    }

    @Test fun exceptSelectedAlwaysKeepsManagerInColor() {
        start(Policy.EXCEPT_SELECTED)
        engine.onForeground("other", true)
        engine.onForeground("selected", true)
        engine.onForeground("other", true)
        engine.onForeground("manager", false)
        assertEquals(listOf(true, false, true, false), settings.writes)
        engine.setSelected(setOf("manager"))
        assertFalse("manager" in engine.state.selectedPackages)
    }

    @Test fun configurationChangesApplyImmediately() {
        start()
        engine.onForeground("other", true)
        engine.setSelected(setOf("other"))
        engine.setPolicy(Policy.EXCEPT_SELECTED)
        assertEquals(listOf(false, true, false), settings.writes)
    }

    @Test fun masterOffAlwaysRestoresColor() {
        start()
        engine.onForeground("selected", true)
        engine.setEnabled(false)
        engine.onForeground("other", true)
        engine.onForeground("selected", true)
        assertEquals(listOf(true), settings.writes)
        assertEquals(1, settings.releases)
        assertFalse(engine.state.managerEnabled)
    }

    @Test fun refusesEnableWithoutBothPrerequisites() {
        settings.permission = false
        engine.setEnabled(true)
        assertFalse(engine.state.managerEnabled)
        settings.permission = true
        serviceEnabled = false
        engine.setEnabled(true)
        assertFalse(engine.state.managerEnabled)
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun permissionRevokedOnDuplicateEventDisablesManagement() {
        start()
        engine.onForeground("selected", true)
        settings.permission = false
        engine.onForeground("selected", true)
        assertFalse(engine.state.managerEnabled)
        assertNull(engine.state.lastAppliedGrayscale)
        assertNotNull(engine.state.error)
        assertEquals(listOf(true), settings.writes)
    }

    @Test fun securityExceptionBetweenCheckAndWriteNeverEscapes() {
        start()
        settings.throws = true
        engine.onForeground("selected", true)
        engine.onDisconnected()
        engine.setEnabled(false)
        assertFalse(engine.state.managerEnabled)
        assertNotNull(engine.state.error)
    }

    @Test fun refusedProviderWriteDoesNotCacheSuccess() {
        start()
        settings.accepts = false
        engine.onForeground("selected", true)
        assertFalse(engine.state.managerEnabled)
        assertNull(engine.state.lastAppliedGrayscale)
        assertNotNull(engine.state.error)
        assertEquals(1, settings.releases)
    }

    @Test fun processRestartRetainsPolicyAndSelectionButWaitsForFreshEvent() {
        start(Policy.EXCEPT_SELECTED)
        engine.onForeground("other", true)
        val restarted = engine()
        restarted.onConnected()
        assertTrue(restarted.state.managerEnabled)
        assertEquals(Policy.EXCEPT_SELECTED, restarted.state.policy)
        assertEquals(setOf("selected", "also.selected"), restarted.state.selectedPackages)
        assertNull(restarted.state.lastRecognisedPackage)
        assertEquals(listOf(true), settings.writes)
        restarted.onForeground("selected", true)
        assertEquals(listOf(true, false), settings.writes)
    }

    @Test fun serviceDisconnectRestoresColorAndDisablesManagement() {
        start()
        engine.onForeground("selected", true)
        engine.onDisconnected()
        assertEquals(listOf(true), settings.writes)
        assertEquals(1, settings.releases)
        assertFalse(engine.state.managerEnabled)
    }

    @Test fun rapidTransitionsRemainOrdered() {
        start()
        repeat(100) {
            engine.onForeground("selected", true)
            engine.onForeground("other", true)
        }
        assertEquals(200, settings.writes.size)
        assertEquals(false, settings.writes.last())
    }

    @Test fun disabledConfigurationDoesNotTouchSettings() {
        engine.setSelected(setOf("selected"))
        engine.setPolicy(Policy.EXCEPT_SELECTED)
        engine.onForeground("other", true)
        assertTrue(settings.writes.isEmpty())
    }

    @Test fun regrantClearsSetupErrorWithoutAutomaticallyReenabling() {
        start()
        settings.permission = false
        engine.checkPrerequisites()
        settings.permission = true
        engine.checkPrerequisites()
        assertNull(engine.state.error)
        assertFalse(engine.state.managerEnabled)
    }

    @Test fun colorAppKeepsManagementActiveWithoutReleasing() {
        start()
        engine.onForeground("other", true)
        assertEquals(listOf(false), settings.writes)
        assertEquals(0, settings.releases)
        assertTrue(engine.state.managerEnabled)
    }

    @Test fun reenableInColorAppReacquiresSettingsAfterRelease() {
        start()
        engine.onForeground("other", true)
        engine.setEnabled(false)
        engine.setEnabled(true)
        assertEquals(listOf(false, false), settings.writes)
        assertEquals(1, settings.releases)
    }

    @Test fun disabledStartupDoesNotTouchColorCorrection() {
        engine.onConnected()
        assertEquals(0, settings.releases)
        assertTrue(settings.writes.isEmpty())
        assertNull(engine.state.lastAppliedGrayscale)
        assertFalse(engine.state.ownsColorCorrection)
    }

    @Test fun completedCleanupDoesNotRepeatOnDisconnectOrReconnect() {
        start()
        engine.onForeground("selected", true)
        engine.setEnabled(false)
        engine.onDisconnected()
        engine.onDisconnected()
        engine().onConnected()
        assertEquals(1, settings.releases)
        assertFalse(store.saved.ownsColorCorrection)
    }

    @Test fun failedCleanupRetainsOwnershipAcrossRestartForRetry() {
        start()
        engine.onForeground("selected", true)
        settings.releaseAccepts = false
        engine.setEnabled(false)
        assertTrue(store.saved.ownsColorCorrection)

        settings.releaseAccepts = true
        val restarted = engine()
        restarted.onConnected()
        assertEquals(2, settings.releases)
        assertFalse(restarted.state.ownsColorCorrection)
        assertNull(restarted.state.error)
        restarted.onDisconnected()
        assertEquals(2, settings.releases)
    }

    @Test fun permissionLossRetainsOwnershipUntilCleanupCanRun() {
        start()
        engine.onForeground("selected", true)
        settings.permission = false
        engine.checkPrerequisites()
        assertTrue(store.saved.ownsColorCorrection)

        settings.permission = true
        val restarted = engine()
        restarted.onConnected()
        assertEquals(1, settings.releases)
        assertFalse(restarted.state.ownsColorCorrection)
    }

    @Test fun missingServiceReleasesManagement() {
        start()
        engine.onForeground("selected", true)
        serviceEnabled = false
        engine.checkPrerequisites()
        assertEquals(1, settings.releases)
        assertFalse(engine.state.managerEnabled)
    }

    @Test fun failedCleanupIsReportedWithoutRetrying() {
        start()
        settings.accepts = false
        settings.releaseAccepts = false
        engine.onForeground("selected", true)
        assertEquals(1, settings.releases)
        assertTrue(engine.state.error!!.contains("could not be restored"))
        assertNull(engine.state.lastAppliedGrayscale)
        engine.onForeground("other", true)
        assertEquals(1, settings.releases)
    }

    @Test fun failedMasterOffDoesNotRetryOrCacheColor() {
        start()
        engine.onForeground("selected", true)
        settings.releaseAccepts = false
        engine.setEnabled(false)
        assertEquals(1, settings.releases)
        assertFalse(engine.state.managerEnabled)
        assertNull(engine.state.lastAppliedGrayscale)
        assertNotNull(engine.state.error)
    }

    @Test fun providerExceptionDisablesAndCleansUpOnce() {
        start()
        settings.providerThrows = true
        engine.onForeground("selected", true)
        assertEquals(1, settings.releases)
        assertFalse(engine.state.managerEnabled)
        assertNull(engine.state.lastAppliedGrayscale)
    }

    @Test fun permissionLostDuringWriteSkipsCleanup() {
        start()
        settings.revokeOnWrite = true
        engine.onForeground("selected", true)
        assertEquals(0, settings.releases)
        assertFalse(engine.state.managerEnabled)
        assertNull(engine.state.lastAppliedGrayscale)
        assertTrue(engine.state.error!!.contains("permission"))
    }

    @Test fun deadlineResamplesTheSourceAndGreysWhenExhausted() {
        accruingSample(60)
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(listOf(false), settings.writes)
        assertEquals(1, sampler.calls)
        assertEquals(510_000L, scheduler.delayMillis)

        elapsedMillis += scheduler.delayMillis!!
        wallMillis += scheduler.delayMillis!!
        scheduler.fire()
        assertEquals(listOf(false, true), settings.writes)
        assertEquals(2, sampler.calls)
        assertNull(scheduler.action)
    }

    @Test fun queryDeliveryDelayIsIncludedInTheDeadline() {
        sampler.provider = { sample(60).also { elapsedMillis += 10_000L } }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(1, sampler.calls)
        assertEquals(500_000L, scheduler.delayMillis)
        assertEquals(listOf(false), settings.writes)
    }

    @Test fun lastThirtySecondsAreTreatedAsExhaustedOnEntry() {
        sampler.provider = { sample(570) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(listOf(true), settings.writes)
        assertEquals(1, sampler.calls)
        assertNull(scheduler.action)
    }

    @Test fun thirtyOneSecondsRemainingSchedulesTheFinalSecond() {
        accruingSample(569)
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(listOf(false), settings.writes)
        assertEquals(1_000L, scheduler.delayMillis)

        elapsedMillis += 1_000L
        scheduler.fire()
        assertEquals(listOf(false, true), settings.writes)
        assertEquals(2, sampler.calls)
    }

    @Test fun unavailableUsageWaitsForTheNextEvaluation() {
        sampler.provider = { UsageSample.Unavailable }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertNotNull(timed.state.usageError)
        assertEquals(listOf(false), settings.writes)
        assertTrue(timed.state.managerEnabled)
        assertNull(scheduler.action)

        sampler.provider = { sample(0) }
        timed.onTimeChanged()
        assertNull(timed.state.usageError)
        assertEquals(2, sampler.calls)
        assertEquals(listOf(false), settings.writes)
        assertEquals(570_000L, scheduler.delayMillis)
    }

    @Test fun noConfiguredDelayGreysImmediately() {
        val timed = timedEngine(defaultMinutes = 0)
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(listOf(true), settings.writes)
        assertEquals(0, sampler.calls)
        assertNull(scheduler.action)
    }

    @Test fun perAppOverrideReplacesTheGlobalDelay() {
        val timed = timedEngine()
        start(timed)
        timed.setGrayAfterMinutes(mapOf("selected" to 0))
        timed.onForeground("selected", true)
        assertEquals(listOf(true), settings.writes)
        assertEquals(0, sampler.calls)
        assertNull(scheduler.action)
    }

    @Test fun resettingAllTimesAppliesImmediatelyAndLetsEveryAppInheritAgain() {
        val timed = timedEngine()
        start(timed)
        val overrides = mapOf("selected" to 15, "also.selected" to 30)
        timed.setGrayAfterMinutes(overrides)
        timed.onForeground("selected", true)
        timed.setDefaultGrayAfterMinutes(5)
        assertEquals(overrides, timed.state.grayAfterMinutes)
        assertNotNull(scheduler.action)
        val queriesBeforeReset = sampler.calls

        timed.setDefaultGrayAfterMinutes(0, clearOverrides = true)
        assertEquals(0, timed.state.defaultGrayAfterMinutes)
        assertTrue(store.saved.grayAfterMinutes.isEmpty())
        assertEquals(setOf("selected", "also.selected"), timed.state.selectedPackages)
        assertEquals(true, settings.writes.last())
        assertNull(scheduler.action)
        assertEquals(queriesBeforeReset, sampler.calls)

        timed.setDefaultGrayAfterMinutes(20)
        assertEquals(false, settings.writes.last())
        assertNotNull(scheduler.action)
    }

    @Test fun exceptSelectedChecksUnselectedAppsOnly() {
        val timed = timedEngine()
        start(timed, Policy.EXCEPT_SELECTED)
        timed.onForeground("other", true)
        assertEquals(listOf(false), settings.writes)
        assertNotNull(scheduler.action)

        timed.onForeground("selected", true)
        assertEquals(listOf(false), settings.writes)
        assertNull(scheduler.action)
        assertEquals(1, sampler.calls)
    }

    @Test fun twoAppsHaveIndependentAllowances() {
        sampler.provider = { packageName ->
            val used = if (packageName == "selected") 9 * 60L else 10 * 60L
            sample(used)
        }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertNotNull(scheduler.action)
        timed.onForeground("also.selected", true)
        assertEquals(listOf(false, true), settings.writes)
        assertEquals(2, sampler.calls)
    }

    @Test fun slowQueryForPreviousAppIsDiscarded() {
        sampler.autoRespond = false
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        timed.onForeground("other", true)
        assertEquals(listOf(false), settings.writes)

        val (_, callback) = sampler.pending.single()
        callback(sample(11 * 60))
        assertEquals(listOf(false), settings.writes)
    }

    @Test fun canceledDeadlineCannotReplaceTheNewAppsQuery() {
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        val canceledCheck = scheduler.action!!

        sampler.autoRespond = false
        timed.onForeground("also.selected", true)
        canceledCheck()
        assertEquals(2, sampler.calls)
        val (packageName, callback) = sampler.pending.single()
        assertEquals("also.selected", packageName)
        callback(sample(10 * 60))
        assertEquals(listOf(false, true), settings.writes)
        assertNull(scheduler.action)
    }

    @Test fun lockShowsLauncherColorAndDoesNotInheritTheApp() {
        start()
        engine.onForeground("selected", true)
        assertEquals(listOf(true), settings.writes)

        engine.onScreenOff("launcher")
        assertEquals(listOf(true, false), settings.writes)
        assertNull(scheduler.action)

        engine.onUserPresent()
        engine.onForeground("selected", true)
        assertEquals(listOf(true, false, true), settings.writes)
    }

    @Test fun lockShowsLauncherGrayInExceptSelected() {
        start(Policy.EXCEPT_SELECTED)
        engine.onForeground("selected", true)
        assertEquals(listOf(false), settings.writes)

        engine.onScreenOff("launcher")
        assertEquals(listOf(false, true), settings.writes)
    }

    @Test fun lockWithoutLauncherRestoresColor() {
        start()
        engine.onForeground("selected", true)

        engine.onScreenOff(null)
        assertEquals(1, settings.releases)
        assertNull(engine.state.lastAppliedGrayscale)
    }

    @Test fun lockUsesLauncherDelayWithoutScheduling() {
        sampler.provider = { sample(4 * 60) }
        val timed = timedEngine()
        start(timed)
        timed.setSelected(setOf("selected", "launcher"))

        timed.onScreenOff("launcher")
        assertEquals(1, sampler.calls)
        assertEquals(listOf(false), settings.writes)
        assertNull(scheduler.action)

        timed.onUserPresent()
        timed.onForeground("launcher", true)
        assertEquals(2, sampler.calls)
        assertNotNull(scheduler.action)
    }

    @Test fun lockUsesLauncherExhaustedAllowance() {
        sampler.provider = { sample(10 * 60) }
        val timed = timedEngine()
        start(timed)
        timed.setSelected(setOf("selected", "launcher"))

        timed.onScreenOff("launcher")
        assertEquals(listOf(true), settings.writes)
        assertNull(scheduler.action)
    }

    @Test fun lockCancelsDeadlineAndUnlockSamplesOnlyAfterConfirmation() {
        sampler.provider = { sample(4 * 60) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertNotNull(scheduler.action)
        val callsBeforeLock = sampler.calls

        timed.onScreenOff("launcher")
        assertNull(scheduler.action)
        assertEquals(callsBeforeLock, sampler.calls)

        wallMillis += 60 * 60_000L
        timed.onUserPresent()
        assertNull(scheduler.action)
        assertEquals(callsBeforeLock, sampler.calls)
        assertEquals(listOf(false), settings.writes)

        timed.onForeground("selected", true)
        assertEquals(callsBeforeLock + 1, sampler.calls)
        assertNotNull(scheduler.action)
        assertEquals(listOf(false), settings.writes)
    }

    @Test fun uncertainForegroundSuspendsDeadlineAndReturningToSameAppResamples() {
        sampler.provider = { sample(4 * 60) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(1, sampler.calls)
        assertEquals(330_000L, scheduler.delayMillis)

        timed.onForegroundUncertain()
        assertNull(scheduler.action)
        assertEquals(1, sampler.calls)
        assertEquals(listOf(false), settings.writes)

        timed.onForeground("selected", true)
        assertEquals(2, sampler.calls)
        assertEquals(330_000L, scheduler.delayMillis)
        assertEquals(listOf(false), settings.writes)
    }

    @Test fun queryFailureRecoversWhenPrerequisitesAreRefreshed() {
        sampler.provider = { UsageSample.Unavailable }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertNotNull(timed.state.usageError)

        sampler.provider = { sample(9 * 60) }
        timed.refreshPrerequisites()
        assertNull(timed.state.usageError)
        assertNotNull(scheduler.action)
    }

    @Test fun snoozeCancelsTheDeadline() {
        val timed = timedEngine()
        timed.setSnoozePackages(setOf("bank"))
        start(timed)
        timed.onForeground("selected", true)
        assertNotNull(scheduler.action)

        assertTrue(timed.prepareSnooze("bank"))
        assertNull(scheduler.action)
    }

    @Test fun abortedSnoozeRestoresTheDeadline() {
        accruingSample(9 * 60)
        val timed = timedEngine()
        timed.setSnoozePackages(setOf("bank"))
        start(timed)
        timed.onForeground("selected", true)
        assertTrue(timed.prepareSnooze("bank"))

        timed.abortSnooze("Notification unavailable")
        assertNull(timed.state.snoozedForPackage)
        assertNotNull(scheduler.action)

        elapsedMillis += scheduler.delayMillis!!
        scheduler.fire()
        assertEquals(listOf(false, true), settings.writes)
        assertEquals(3, sampler.calls)
    }

    @Test fun abortedSnoozeKeepsItsFailureAfterAnAsynchronousUsageResult() {
        sampler.provider = { sample(9 * 60) }
        val timed = timedEngine()
        timed.setSnoozePackages(setOf("bank"))
        start(timed)
        timed.onForeground("selected", true)
        assertTrue(timed.prepareSnooze("bank"))
        assertTrue(timed.restoreColorForSnooze())
        sampler.autoRespond = false

        timed.abortSnooze("Accessibility disable failed")
        val (_, callback) = sampler.pending.single()
        callback(sample(9 * 60))
        assertEquals("Accessibility disable failed", timed.state.error)
        assertNotNull(scheduler.action)
    }

    @Test fun failedPolicyRestorationAfterAbortedSnoozeKeepsTheNewSettingsError() {
        val timed = timedEngine()
        timed.setSnoozePackages(setOf("bank"))
        start(timed)
        timed.onForeground("selected", true)
        assertTrue(timed.prepareSnooze("bank"))
        assertTrue(timed.restoreColorForSnooze())
        settings.providerThrows = true

        timed.abortSnooze("Accessibility disable failed")
        assertFalse(timed.state.managerEnabled)
        assertTrue(timed.state.error!!.contains("Could not change Android Color correction"))
        assertNull(scheduler.action)
    }

    @Test fun missingUsageAccessKeepsColorButImmediateStillGreys() {
        usageAccess = false
        sampler.provider = { error("Usage should not be sampled without access") }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(listOf(false), settings.writes)
        assertEquals(0, sampler.calls)
        assertTrue(timed.state.managerEnabled)
        assertTrue(timed.state.usageError!!.contains("Open Setup"))
        assertNull(scheduler.action)

        timed.setGrayAfterMinutes(mapOf("selected" to 0))
        assertEquals(listOf(false, true), settings.writes)
    }

    @Test fun grantingUsageAccessClearsTheRequirementMessage() {
        usageAccess = false
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertTrue(timed.state.usageError!!.contains("Open Setup"))

        usageAccess = true
        sampler.provider = { sample(0) }
        timed.refreshPrerequisites()
        assertNull(timed.state.usageError)
        assertNotNull(scheduler.action)
    }

    @Test fun grayAppKeepsNoDeadlineAndRegainsAllowanceOnTheNewDay() {
        wallMillis = Instant.parse("2026-10-18T23:50:00Z").toEpochMilli()
        sampler.provider = { sample(10 * 60) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(listOf(true), settings.writes)
        assertNull(scheduler.action)
        timed.onForeground("selected", true)
        assertEquals(1, sampler.calls)

        wallMillis += 10 * 60_000L
        sampler.provider = { sample(0) }
        timed.onTimeChanged()
        assertEquals(listOf(true, false), settings.writes)
        assertEquals(2, sampler.calls)
        assertEquals(570_000L, scheduler.delayMillis)
    }

    @Test fun oldDeadlineDoesNotApplyToTheNewDay() {
        wallMillis = Instant.parse("2026-10-18T23:59:00Z").toEpochMilli()
        sampler.provider = { sample(0) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        val oldDeadline = scheduler.action!!
        assertEquals(570_000L, scheduler.delayMillis)
        assertEquals(1, sampler.calls)

        wallMillis += 2 * 60_000L
        elapsedMillis += 2 * 60_000L
        oldDeadline()
        assertEquals(2, sampler.calls)
        assertEquals(listOf(false), settings.writes)
        assertEquals(570_000L, scheduler.delayMillis)
    }

    @Test fun allowanceChangeReplacesThePendingDeadlineAndInvalidatesTheOldCallback() {
        sampler.provider = { sample(60) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        val oldDeadline = scheduler.action!!
        assertEquals(510_000L, scheduler.delayMillis)

        timed.setGrayAfterMinutes(mapOf("selected" to 5))
        assertEquals(2, sampler.calls)
        assertEquals(210_000L, scheduler.delayMillis)
        assertEquals(listOf(false), settings.writes)

        oldDeadline()
        assertEquals(listOf(false), settings.writes)
        assertEquals(210_000L, scheduler.delayMillis)
    }

    @Test fun timeChangeResamplesCurrentDestination() {
        sampler.provider = { sample(0) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertNotNull(scheduler.action)

        sampler.provider = { sample(11 * 60) }
        timed.onTimeChanged()
        assertEquals(listOf(false, true), settings.writes)
        assertEquals(2, sampler.calls)
    }

    @Test fun disablingCancelsTheDeadline() {
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertNotNull(scheduler.action)

        timed.setEnabled(false)
        assertNull(scheduler.action)
        assertFalse(timed.state.managerEnabled)
    }

    @Test fun policyChangeCancelsDelayedDestination() {
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertNotNull(scheduler.action)

        timed.setPolicy(Policy.EXCEPT_SELECTED)
        assertNull(scheduler.action)
        assertEquals(listOf(false), settings.writes)
    }

    @Test fun raisingAllowanceKeepsGrayWhenTotalUsageExceedsIt() {
        sampler.provider = { sample(10 * 60) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(listOf(true), settings.writes)

        sampler.provider = { sample(40 * 60) }
        timed.setGrayAfterMinutes(mapOf("selected" to 30))
        assertEquals(listOf(true), settings.writes)
        assertEquals(2, sampler.calls)
    }

    @Test fun increasingAppOverrideWhileManagerIsForegroundRestoresColorOnReturn() {
        sampler.provider = { sample(10 * 60) }
        val timed = timedEngine()
        start(timed)
        timed.onForeground("selected", true)
        assertEquals(listOf(true), settings.writes)

        timed.onForeground("manager", true)
        assertEquals(listOf(true, false), settings.writes)

        timed.setGrayAfterMinutes(mapOf("selected" to 20))
        assertEquals(listOf(true, false), settings.writes)

        sampler.provider = { sample(10 * 60) }
        timed.onForeground("selected", true)
        assertEquals(listOf(true, false), settings.writes)
        assertEquals(570_000L, scheduler.delayMillis)
    }

    @Test fun autoSnoozeCandidatesAreConfiguredAppsWithoutPendingSnooze() {
        val configured = ManagerState(snoozePackages = setOf("bank"))
        assertTrue(configured.isAutoSnoozeCandidate("bank"))
        assertFalse(configured.isAutoSnoozeCandidate("browser"))
        assertFalse(configured.copy(snoozedForPackage = "bank").isAutoSnoozeCandidate("bank"))
    }
}
