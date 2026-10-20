package now.abhi.graycie

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val EVENT_LOOKBACK_MILLIS = 24L * 60L * 60L * 1_000L

sealed interface UsageSample {
    /** [sampledAtElapsedMillis] is the monotonic instant that matches [usedSeconds]. */
    data class Available(
        val usedSeconds: Long,
        val dayStartMillis: Long,
        val sampledAtElapsedMillis: Long,
    ) : UsageSample

    data object Unavailable : UsageSample
}

/** Samples today's foreground usage for one package; the callback runs on the main thread. */
interface UsageSampler {
    fun sample(packageName: String, onResult: (UsageSample) -> Unit)
}

object UnavailableUsageSampler : UsageSampler {
    override fun sample(packageName: String, onResult: (UsageSample) -> Unit) =
        onResult(UsageSample.Unavailable)
}

enum class UsageStampType {
    FOREGROUND,
    BACKGROUND,
    SCREEN_ON,
    SCREEN_OFF,
    UNLOCKED,
    LOCKED,
    SHUTDOWN,
    STARTUP,
}

data class UsageStamp(
    val timestampMillis: Long,
    val packageName: String?,
    val className: String?,
    val type: UsageStampType,
)

data class UsageHistoryWindow(
    val todayStartMillis: Long,
    val nowMillis: Long,
    val bootMillis: Long,
    val lookbackMillis: Long = EVENT_LOOKBACK_MILLIS,
)

/** Reconstructs today's foreground seconds for one package; null when history is ambiguous. */
fun foregroundSecondsToday(
    events: List<UsageStamp>,
    packageName: String,
    window: UsageHistoryWindow,
): Long? {
    val scanStart = window.todayStartMillis - window.lookbackMillis
    // Boot bounds unfinished sessions; completed sessions before it still count today.
    val relevant = sequenceOf(UsageStamp(window.bootMillis, null, null, UsageStampType.STARTUP))
        .plus(events.asSequence())
        .filter { it.timestampMillis in scanStart..window.nowMillis }
        .sortedBy { it.timestampMillis }

    val resumedClasses = mutableSetOf<String>()
    var screenOn = true
    var unlocked = true
    var activeStart: Long? = null
    var totalMillis = 0L
    var sawTargetForeground = false
    var ambiguous = false

    fun closeActive(atMillis: Long) {
        val start = activeStart ?: return
        val from = maxOf(start, window.todayStartMillis)
        val to = minOf(atMillis, window.nowMillis)
        if (to > from) totalMillis += to - from
        activeStart = null
    }

    for (event in relevant) {
        when (event.type) {
            UsageStampType.SCREEN_OFF -> {
                screenOn = false
            }
            UsageStampType.SCREEN_ON -> {
                screenOn = true
            }
            UsageStampType.LOCKED -> unlocked = false
            UsageStampType.UNLOCKED -> unlocked = true
            UsageStampType.SHUTDOWN -> {
                closeActive(event.timestampMillis)
                screenOn = false
                resumedClasses.clear()
            }
            UsageStampType.STARTUP -> {
                // An unfinished session's closing time before a reboot is unknown.
                activeStart = null
                screenOn = true
                unlocked = true
                resumedClasses.clear()
            }
            UsageStampType.FOREGROUND -> {
                if (event.packageName != packageName) continue
                sawTargetForeground = true
                resumedClasses += event.className ?: ""
            }
            UsageStampType.BACKGROUND -> {
                if (event.packageName != packageName) continue
                if (activeStart == null) {
                    // A background without a visible start can be a duplicate or a pre-history session.
                    if (!sawTargetForeground && event.timestampMillis >= window.todayStartMillis) {
                        ambiguous = true
                    }
                }
                resumedClasses -= event.className ?: ""
            }
        }
        if (screenOn && unlocked && resumedClasses.isNotEmpty()) {
            if (activeStart == null) activeStart = event.timestampMillis
        } else {
            closeActive(event.timestampMillis)
        }
    }
    closeActive(window.nowMillis)
    return if (ambiguous) null else totalMillis / 1_000L
}

class AndroidUsageHistory(
    private val context: Context,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) : UsageSampler {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isGranted(): Boolean {
        val mode = try {
            appOps?.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } catch (_: Exception) {
            null
        }
        return mode == AppOpsManager.MODE_ALLOWED ||
            (mode == AppOpsManager.MODE_DEFAULT &&
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED)
    }

    override fun sample(packageName: String, onResult: (UsageSample) -> Unit) {
        executor.execute {
            val result = query(packageName)
            mainHandler.post { onResult(result) }
        }
    }

    private fun query(packageName: String): UsageSample {
        if (!isGranted()) return UsageSample.Unavailable
        if (userManager?.isUserUnlocked == false) return UsageSample.Unavailable
        val usage = usageStats ?: return UsageSample.Unavailable
        val nowMillis = System.currentTimeMillis()
        val sampledAtElapsedMillis = SystemClock.elapsedRealtime()
        val zone = ZoneId.systemDefault()
        val todayStartMillis = startOfTodayMillis(nowMillis, zone)
        val bootMillis = nowMillis - sampledAtElapsedMillis
        val scanStartMillis = todayStartMillis - EVENT_LOOKBACK_MILLIS

        val stamps = try {
            readEvents(usage, packageName, scanStartMillis, nowMillis) ?: return UsageSample.Unavailable
        } catch (_: Exception) {
            return UsageSample.Unavailable
        }

        val hasTargetActivity = stamps.any {
            it.packageName == packageName &&
                (it.type == UsageStampType.FOREGROUND || it.type == UsageStampType.BACKGROUND)
        }
        if (!hasTargetActivity) {
            // No activity events can mean no usage, or pruned events with a nonzero aggregate.
            val aggregateSeconds = try {
                dayAggregateSeconds(usage, packageName, todayStartMillis, nowMillis)
            } catch (_: Exception) {
                null
            } ?: return UsageSample.Unavailable
            if (aggregateSeconds > 0L) return UsageSample.Unavailable
        }

        val seconds = foregroundSecondsToday(
            events = stamps,
            packageName = packageName,
            window = UsageHistoryWindow(todayStartMillis, nowMillis, bootMillis),
        ) ?: return UsageSample.Unavailable
        return UsageSample.Available(seconds, todayStartMillis, sampledAtElapsedMillis)
    }

    private fun readEvents(
        usage: UsageStatsManager,
        packageName: String,
        startMillis: Long,
        endMillis: Long,
    ): List<UsageStamp>? {
        if (startMillis >= endMillis) return emptyList()
        val usageEvents = if (Build.VERSION.SDK_INT >= 35) {
            usage.queryEvents(UsageEventsQuery.Builder(startMillis, endMillis).setEventTypes(
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.SCREEN_INTERACTIVE,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_HIDDEN,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN,
                UsageEvents.Event.DEVICE_STARTUP,
            ).build())
        } else {
            usage.queryEvents(startMillis, endMillis)
        } ?: return null
        val event = UsageEvents.Event()
        val stamps = mutableListOf<UsageStamp>()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val type = usageStampType(event.eventType, Build.VERSION.SDK_INT) ?: continue
            if ((type == UsageStampType.FOREGROUND || type == UsageStampType.BACKGROUND) &&
                event.packageName != packageName
            ) continue
            stamps += UsageStamp(
                timestampMillis = event.timeStamp,
                packageName = event.packageName,
                className = event.className,
                type = type,
            )
        }
        return stamps
    }

    private fun dayAggregateSeconds(
        usage: UsageStatsManager,
        packageName: String,
        startMillis: Long,
        endMillis: Long,
    ): Long? {
        val stats = usage.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startMillis,
            endMillis,
        ) ?: return null
        val total = stats.sumOf { if (it.packageName == packageName) it.totalTimeInForeground else 0L }
        return total / 1_000L
    }
}

@Suppress("DEPRECATION")
internal fun usageStampType(eventType: Int, sdkInt: Int): UsageStampType? {
    if (sdkInt >= 29) {
        when (eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> return UsageStampType.FOREGROUND
            UsageEvents.Event.ACTIVITY_PAUSED -> return UsageStampType.BACKGROUND
            UsageEvents.Event.DEVICE_SHUTDOWN -> return UsageStampType.SHUTDOWN
            UsageEvents.Event.DEVICE_STARTUP -> return UsageStampType.STARTUP
        }
    } else {
        when (eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> return UsageStampType.FOREGROUND
            UsageEvents.Event.MOVE_TO_BACKGROUND -> return UsageStampType.BACKGROUND
        }
    }
    if (sdkInt >= 28) {
        when (eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE -> return UsageStampType.SCREEN_ON
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> return UsageStampType.SCREEN_OFF
            UsageEvents.Event.KEYGUARD_HIDDEN -> return UsageStampType.UNLOCKED
            UsageEvents.Event.KEYGUARD_SHOWN -> return UsageStampType.LOCKED
        }
    }
    return null
}
