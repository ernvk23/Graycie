package now.abhi.graycie

import android.os.SystemClock
import java.time.ZoneId

enum class Policy { ONLY_SELECTED, EXCEPT_SELECTED }

private const val PERMISSION_ERROR = "Secure settings permission is missing. Complete the ADB setup."
private const val USAGE_UNAVAILABLE_ERROR =
    "Android usage history is unavailable. Check Usage Access and try again."
internal const val USAGE_ACCESS_ERROR =
    "Usage Access is required for delayed grayscale. Open Setup to grant it."

data class ManagerState(
    val managerEnabled: Boolean = false,
    val policy: Policy = Policy.ONLY_SELECTED,
    val selectedPackages: Set<String> = emptySet(),
    val lastRecognisedPackage: String? = null,
    val lastAppliedGrayscale: Boolean? = null,
    val error: String? = null,
    val snoozePackages: Set<String> = emptySet(),
    val snoozedForPackage: String? = null,
    val ownsColorCorrection: Boolean = false,
    val defaultGrayAfterMinutes: Int = DEFAULT_GRAY_AFTER_MINUTES,
    val grayAfterMinutes: Map<String, Int> = emptyMap(),
    val usageError: String? = null,
)

/** A configured app can trigger an automatic snooze only while none is pending. */
internal fun ManagerState.isAutoSnoozeCandidate(packageName: String): Boolean =
    snoozedForPackage == null && packageName in snoozePackages

interface StateStore {
    fun read(): ManagerState
    fun write(state: ManagerState)
}

interface GrayscaleSettings {
    fun hasPermission(): Boolean
    /** Return false when the provider refuses a write; may throw SecurityException. */
    fun setGrayscale(enabled: Boolean): Boolean
    /** Stop management and restore saved correction, preserving a newer manual choice. */
    fun release(): Boolean
}

/** All entry points run on Android's main thread, including secure-setting writes. */
class PolicyEngine(
    private val ownPackage: String,
    private val store: StateStore,
    private val settings: GrayscaleSettings,
    private val serviceEnabled: () -> Boolean,
    private val changed: () -> Unit = {},
    private val scheduler: UsageCheckScheduler = NoOpUsageCheckScheduler,
    private val usageSampler: UsageSampler = UnavailableUsageSampler,
    private val usageAccess: () -> Boolean = { true },
    private val nowWallMillis: () -> Long = { System.currentTimeMillis() },
    private val nowElapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    var state = store.read()
        private set
    private var foregroundTarget: String? = null
    private var screenOn = true
    private var locked = false
    private var evaluationGeneration = 0L

    private fun save(next: ManagerState) {
        if (next == state) return
        state = next
        store.write(next)
        changed()
    }

    fun checkPrerequisites() {
        if (!settings.hasPermission()) {
            cancelEvaluation()
            save(state.copy(managerEnabled = state.managerEnabled && state.snoozedForPackage != null,
                lastAppliedGrayscale = null,
                error = PERMISSION_ERROR))
        } else if (!serviceEnabled() && state.managerEnabled && state.snoozedForPackage == null) {
            setEnabled(false)
        } else if (state.error == PERMISSION_ERROR) {
            save(state.copy(error = null))
        }
    }

    fun refreshPrerequisites() {
        checkPrerequisites()
        evaluate()
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            cancelEvaluation()
            save(state.copy(managerEnabled = false, snoozedForPackage = null))
            releaseGrayscale()
            return
        }
        checkPrerequisites()
        if (!settings.hasPermission() || !serviceEnabled()) return
        save(state.copy(managerEnabled = true, error = null,
            lastAppliedGrayscale = if (state.managerEnabled) state.lastAppliedGrayscale else null))
        evaluate()
    }

    fun setPolicy(policy: Policy) {
        save(state.copy(policy = policy))
        checkPrerequisites()
        evaluate()
    }

    fun setSelected(packages: Set<String>) {
        save(state.copy(selectedPackages = packages - ownPackage))
        checkPrerequisites()
        evaluate()
    }

    fun setDefaultGrayAfterMinutes(minutes: Int, clearOverrides: Boolean = false) {
        save(state.copy(
            defaultGrayAfterMinutes = minutes.coerceIn(0, MAX_GRAY_AFTER_MINUTES),
            grayAfterMinutes = if (clearOverrides) emptyMap() else state.grayAfterMinutes,
        ))
        evaluate()
    }

    fun setGrayAfterMinutes(packages: Map<String, Int>) {
        val sanitized = packages
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, minutes) -> minutes.coerceIn(0, MAX_GRAY_AFTER_MINUTES) }
        save(state.copy(grayAfterMinutes = sanitized))
        evaluate()
    }

    fun setSnoozePackages(packages: Set<String>) {
        save(state.copy(snoozePackages = packages - ownPackage))
    }

    fun prepareSnooze(packageName: String): Boolean {
        if (state.snoozedForPackage != null || packageName !in state.snoozePackages) {
            return false
        }
        cancelEvaluation()
        save(state.copy(snoozedForPackage = packageName, error = null))
        return true
    }

    /** Release Graycie's correction without changing the user's master-enabled preference. */
    fun restoreColorForSnooze(): Boolean {
        if (!state.ownsColorCorrection) return true
        return try {
            if (!settings.hasPermission()) throw SecurityException("Permission missing")
            if (!settings.release()) {
                save(state.copy(lastAppliedGrayscale = null,
                    error = "Previous Color correction could not be restored before snoozing. Check Android Color correction settings."))
                false
            } else {
                save(state.copy(lastAppliedGrayscale = null, ownsColorCorrection = false, error = null))
                true
            }
        } catch (_: SecurityException) {
            save(state.copy(lastAppliedGrayscale = null, error = PERMISSION_ERROR))
            false
        } catch (_: RuntimeException) {
            save(state.copy(lastAppliedGrayscale = null,
                error = "Previous Color correction could not be restored before snoozing. Check Android Color correction settings."))
            false
        }
    }

    fun abortSnooze(message: String) {
        save(state.copy(snoozedForPackage = null, error = message))
        evaluate()
        if (state.error == null) reportError(message)
    }

    fun reportError(message: String) = save(state.copy(error = message))

    fun completeResume() {
        save(state.copy(snoozedForPackage = null, lastRecognisedPackage = null,
            lastAppliedGrayscale = null, error = null))
    }

    fun onConnected() {
        cancelEvaluation()
        screenOn = true
        locked = false
        // Persisted foreground and applied state cannot be trusted after reconnect/restart.
        foregroundTarget = null
        save(state.copy(lastRecognisedPackage = null, lastAppliedGrayscale = null))
        checkPrerequisites()
        if (!state.managerEnabled && settings.hasPermission()) releaseGrayscale()
    }

    fun onForeground(packageName: String, isLaunchable: Boolean) {
        // Check before deduplication so revocation is caught even for repeated events.
        checkPrerequisites()
        if (packageName != ownPackage && !isLaunchable) return
        if (packageName == foregroundTarget) return
        foregroundTarget = packageName
        if (packageName != state.lastRecognisedPackage) {
            save(state.copy(lastRecognisedPackage = packageName))
        }
        evaluate()
    }

    /** Keep current color, drop the deadline, and resample when the app is confirmed again. */
    fun onForegroundUncertain() {
        cancelEvaluation()
        foregroundTarget = null
    }

    fun onDisconnected() {
        cancelEvaluation()
        foregroundTarget = null
        locked = false
        if (state.snoozedForPackage != null) {
            save(state.copy(lastRecognisedPackage = null))
            return
        }
        save(state.copy(managerEnabled = false, lastRecognisedPackage = null))
        releaseGrayscale()
    }

    /** Locking shows the launcher's policy state and forgets the foreground app. */
    fun onScreenOff(homePackage: String?) {
        screenOn = false
        locked = true
        cancelEvaluation()
        foregroundTarget = homePackage
        if (!state.managerEnabled || state.snoozedForPackage != null || homePackage == null) {
            releaseGrayscale()
            return
        }
        evaluate()
    }

    /** Unlock starts from a clean slate; the service reclassifies the current windows. */
    fun onUserPresent() {
        screenOn = true
        locked = false
        foregroundTarget = null
    }

    /** Clock, timezone, or date changes replace the deadline with a fresh sample. */
    fun onTimeChanged() {
        if (state.managerEnabled && state.snoozedForPackage == null) evaluate()
    }

    private fun evaluate() {
        cancelEvaluation()
        if (!state.managerEnabled || state.snoozedForPackage != null || (!screenOn && !locked)) return
        if (usageAccess() && state.usageError == USAGE_ACCESS_ERROR) {
            setUsageError(null)
        }
        val target = foregroundTarget ?: return
        val selected = target in state.selectedPackages
        val grayscale = target != ownPackage && when (state.policy) {
            Policy.ONLY_SELECTED -> selected
            Policy.EXCEPT_SELECTED -> !selected
        }
        if (!grayscale) {
            applyGrayscale(false)
            return
        }
        val minutes = state.grayAfterMinutes[target] ?: state.defaultGrayAfterMinutes
        val allowanceSeconds = minutes.coerceAtLeast(0) * 60L
        if (allowanceSeconds <= 0L) {
            applyGrayscale(true)
            return
        }
        if (!usageAccess()) {
            setUsageError(USAGE_ACCESS_ERROR)
            applyGrayscale(false)
            return
        }
        val generation = evaluationGeneration
        usageSampler.sample(target) { sample ->
            onSample(generation, target, allowanceSeconds, sample)
        }
    }

    private fun onSample(
        generation: Long,
        packageName: String,
        allowanceSeconds: Long,
        sample: UsageSample,
    ) {
        if (generation != evaluationGeneration || !isActive(packageName)) return
        when (sample) {
            UsageSample.Unavailable -> {
                setUsageError(USAGE_UNAVAILABLE_ERROR)
                applyGrayscale(false, clearError = false)
            }
            is UsageSample.Available -> {
                if (sample.dayStartMillis != startOfTodayMillis(nowWallMillis(), zone())) {
                    // The local day changed while the query was in flight; sample again.
                    evaluate()
                    return
                }
                setUsageError(null)
                val remainingSeconds = allowanceSeconds - sample.usedSeconds
                val deadlineElapsedMillis = sample.sampledAtElapsedMillis +
                    (remainingSeconds - USAGE_EXHAUSTED_MARGIN_SECONDS) * 1_000L
                val delayMillis = deadlineElapsedMillis - nowElapsedMillis()
                if (remainingSeconds <= USAGE_EXHAUSTED_MARGIN_SECONDS || delayMillis <= 0L) {
                    applyGrayscale(true, clearError = false)
                    return
                }
                applyGrayscale(false, clearError = false)
                if (locked) return
                if (generation != evaluationGeneration || !isActive(packageName)) return
                scheduler.schedule(delayMillis) {
                    onDeadline(generation, packageName)
                }
            }
        }
    }

    private fun onDeadline(generation: Long, packageName: String) {
        if (generation != evaluationGeneration || !isActive(packageName)) return
        evaluate()
    }

    private fun isActive(packageName: String): Boolean =
        state.managerEnabled && state.snoozedForPackage == null && (screenOn || locked) &&
            foregroundTarget == packageName

    private fun setUsageError(message: String?) {
        if (state.usageError == message) return
        save(state.copy(usageError = message))
    }

    private fun cancelEvaluation() {
        evaluationGeneration++
        scheduler.cancel()
    }

    private fun applyGrayscale(enabled: Boolean, clearError: Boolean = true) {
        if (state.lastAppliedGrayscale == enabled) return
        try {
            if (!settings.hasPermission()) throw SecurityException("Permission missing")
            // Persist ownership before writing so partial failures and restarts can
            // clean up, without touching corrections when Graycie has never managed them.
            save(state.copy(ownsColorCorrection = true))
            if (!settings.setGrayscale(enabled)) {
                failChange("Android refused the color correction change. Check setup and try again.")
                return
            }
            save(state.copy(lastAppliedGrayscale = enabled, error = if (clearError) null else state.error))
        } catch (_: SecurityException) {
            failChange(PERMISSION_ERROR)
        } catch (_: RuntimeException) {
            failChange("Could not change Android Color correction. Check setup and try again.")
        }
    }

    private fun releaseGrayscale() {
        if (!state.ownsColorCorrection) return
        try {
            if (!settings.hasPermission()) throw SecurityException("Permission missing")
            if (!settings.release()) {
                failChange("Android refused to restore previous Color correction. Check Android Color correction settings.",
                    cleanup = false)
                return
            }
            save(state.copy(lastAppliedGrayscale = null, ownsColorCorrection = false, error = null))
        } catch (_: SecurityException) {
            failChange(PERMISSION_ERROR, cleanup = false)
        } catch (_: RuntimeException) {
            failChange("Could not restore previous Color correction. Check Android Color correction settings.",
                cleanup = false)
        }
    }

    private fun failChange(message: String, cleanup: Boolean = true) {
        cancelEvaluation()
        var error = message
        var ownsColorCorrection = state.ownsColorCorrection
        if (cleanup && ownsColorCorrection && settings.hasPermission()) {
            val restored = try { settings.release() } catch (_: RuntimeException) { false }
            if (restored) ownsColorCorrection = false
            if (!restored) error += " Previous Color correction could not be restored; check Android Color correction settings."
        }
        save(state.copy(managerEnabled = false, lastAppliedGrayscale = null,
            ownsColorCorrection = ownsColorCorrection, error = error))
    }
}
