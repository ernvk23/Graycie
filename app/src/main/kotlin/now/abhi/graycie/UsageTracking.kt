package now.abhi.graycie

import android.os.Handler
import android.os.SystemClock
import java.time.Instant
import java.time.ZoneId

const val DEFAULT_GRAY_AFTER_MINUTES = 0
const val MAX_GRAY_AFTER_MINUTES = 24 * 60
const val USAGE_EXHAUSTED_MARGIN_SECONDS = 30L
const val USAGE_RETRY_DELAY_MILLIS = 30_000L

/** Schedules one main-thread usage callback after [delayMillis]. */
interface UsageCheckScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit)
    fun cancel()
}

object NoOpUsageCheckScheduler : UsageCheckScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit) = Unit
    override fun cancel() = Unit
}

class HandlerUsageCheckScheduler(private val handler: Handler) : UsageCheckScheduler {
    private val token = Any()

    override fun schedule(delayMillis: Long, action: () -> Unit) {
        handler.removeCallbacksAndMessages(token)
        handler.postAtTime(action, token, SystemClock.uptimeMillis() + delayMillis)
    }

    override fun cancel() = handler.removeCallbacksAndMessages(token)
}

internal fun startOfTodayMillis(nowMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowMillis)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

