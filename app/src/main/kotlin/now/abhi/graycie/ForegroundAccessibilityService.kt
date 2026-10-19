package now.abhi.graycie

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

private const val UNLOCK_DEDUPE_MILLIS = 1_000L

class ForegroundAccessibilityService : AccessibilityService() {
    private val controller by lazy { ManagerController.get(this) }
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val classifier = ForegroundWindowClassifier()
    private val stabilizer = TransitionStabilizer()
    private val handler = Handler(Looper.getMainLooper())
    private var launchablePackages = emptySet<String>()
    private var homePackages = emptySet<String>()
    private var callbackRegistered = false
    private var screenReceiverRegistered = false
    private var eventGeneration = 0L
    private var transitionStartedAt = 0L
    private var lastUnlockEvaluationAt = 0L

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> controller.engine.onScreenOff()
                Intent.ACTION_SCREEN_ON -> {
                    // Without a keyguard there may be no later USER_PRESENT.
                    if (!keyguardManager.isKeyguardLocked) evaluateUnlock()
                }
                Intent.ACTION_USER_PRESENT -> evaluateUnlock()
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED -> controller.engine.onTimeChanged()
            }
        }
    }

    private fun evaluateUnlock() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnlockEvaluationAt < UNLOCK_DEDUPE_MILLIS) return
        lastUnlockEvaluationAt = now
        controller.refreshUsageAccess()
        controller.engine.onUserPresent()
    }

    private val packagesChanged = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = refreshPackages()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = refreshPackages()
        override fun onPackageChanged(packageName: String, user: UserHandle) = refreshPackages()
        override fun onPackagesAvailable(packages: Array<out String>, user: UserHandle, replacing: Boolean) = refreshPackages()
        override fun onPackagesUnavailable(packages: Array<out String>, user: UserHandle, replacing: Boolean) = refreshPackages()
    }

    private fun refreshPackages() {
        launchablePackages = AppCatalog(this).packageNames() + packageName
        homePackages = controller.activeHomePackages()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshPackages()
        if (!callbackRegistered) {
            launcherApps.registerCallback(packagesChanged, handler)
            callbackRegistered = true
        }
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_DATE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiverRegistered = true
        }
        controller.onServiceConnected()
        if (powerManager?.isInteractive == false || keyguardManager?.isKeyguardLocked == true) {
            controller.engine.onScreenOff()
        }
        // Reapply the current window state instead of waiting for the next event.
        restartSettleWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val eventPackage = event.packageName?.toString()
            if (eventPackage != null && controller.automaticSnooze(eventPackage) { disableSelf() }) {
                cancelChecks()
                return
            }
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        restartSettleWindow()
    }

    private fun restartSettleWindow() {
        eventGeneration++
        val generation = eventGeneration
        transitionStartedAt = SystemClock.uptimeMillis() + SETTLE_DELAY_MS
        stabilizer.reset()
        handler.removeCallbacksAndMessages(RECHECK_TOKEN)
        schedule(generation, SETTLE_DELAY_MS)
    }

    private fun classifyForeground(generation: Long) {
        if (generation != eventGeneration) return
        // The default Home app can change without its package being added, removed, or updated.
        homePackages = controller.activeHomePackages()
        val elapsed = (SystemClock.uptimeMillis() - transitionStartedAt).coerceAtLeast(0)
        val currentWindows = windows.map { it.toMetadata() }
        if (classifier.isOwnWindowFocused(currentWindows, packageName)) {
            controller.engine.onForeground(packageName, true)
            return
        }
        val classification = classifier.classify(
            windows = currentWindows,
            launchablePackages = launchablePackages,
            homePackages = homePackages,
            retryExpired = stabilizer.retryExpired(elapsed),
        )
        if (BuildConfig.DEBUG) {
            val packages = currentWindows.mapNotNull { it.packageName }.distinct()
            Log.d(TAG, "windows=$packages classification=${classification.javaClass.simpleName} reason=${classification.reason}")
        }
        when (stabilizer.action(classification, elapsed)) {
            TransitionAction.Apply -> {
                when (classification) {
                    is ForegroundClassification.Settled ->
                        controller.engine.onForeground(classification.packageName, true)
                    is ForegroundClassification.HomeOverview ->
                        controller.engine.onHome(classification.packageName)
                    else -> error("Only stable destinations can be applied")
                }
            }
            TransitionAction.Retry -> schedule(generation, stabilizer.nextDelayMs())
            TransitionAction.Ignore -> controller.engine.onForegroundUncertain()
        }
    }

    private fun AccessibilityWindowInfo.toMetadata(): WindowMetadata {
        val rootPackage = root?.packageName?.toString()
        return WindowMetadata(
            packageName = rootPackage,
            focused = isFocused,
            active = isActive,
            type = type,
            pictureInPicture = isInPictureInPictureMode,
            hasRoot = rootPackage != null,
        )
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cancelChecks()
        controller.onServiceDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelChecks()
        controller.onServiceDisconnected()
        if (callbackRegistered) launcherApps.unregisterCallback(packagesChanged)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenStateReceiver)
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun cancelChecks() {
        eventGeneration++
        stabilizer.reset()
        handler.removeCallbacksAndMessages(RECHECK_TOKEN)
    }

    private fun schedule(generation: Long, delayMs: Long) {
        handler.postAtTime(
            { classifyForeground(generation) },
            RECHECK_TOKEN,
            SystemClock.uptimeMillis() + delayMs,
        )
    }

    private companion object {
        const val TAG = "GrayscaleWindows"
        const val SETTLE_DELAY_MS = 250L
        val RECHECK_TOKEN = Any()
    }
}
