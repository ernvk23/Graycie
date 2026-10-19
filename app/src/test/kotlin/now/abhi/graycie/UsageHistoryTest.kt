package now.abhi.graycie

import android.app.usage.UsageEvents
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageHistoryTest {
    private val today = Instant.parse("2026-10-18T00:00:00Z").toEpochMilli()

    private fun foreground(seconds: Long, packageName: String = "app", className: String = "Main") =
        UsageStamp(today + seconds * 1_000L, packageName, className, UsageStampType.FOREGROUND)

    private fun background(seconds: Long, packageName: String = "app", className: String = "Main") =
        UsageStamp(today + seconds * 1_000L, packageName, className, UsageStampType.BACKGROUND)

    private fun screenOn(seconds: Long) =
        UsageStamp(today + seconds * 1_000L, null, null, UsageStampType.SCREEN_ON)

    private fun screenOff(seconds: Long) =
        UsageStamp(today + seconds * 1_000L, null, null, UsageStampType.SCREEN_OFF)

    private fun keyguard(seconds: Long, eventType: Int) =
        UsageStamp(today + seconds * 1_000L, null, null, usageStampType(eventType, 28)!!)

    private fun shutdown(seconds: Long) =
        UsageStamp(today + seconds * 1_000L, null, null, UsageStampType.SHUTDOWN)

    private fun startup(seconds: Long) =
        UsageStamp(today + seconds * 1_000L, null, null, UsageStampType.STARTUP)

    private fun window(nowSeconds: Long, bootMillis: Long = 0L) =
        UsageHistoryWindow(
            todayStartMillis = today,
            nowMillis = today + nowSeconds * 1_000L,
            bootMillis = bootMillis,
        )

    @Test fun separateSessionsAreSummed() {
        val events = listOf(
            foreground(0, packageName = "other"),
            foreground(60),
            background(120),
            background(150, packageName = "other"),
            foreground(180),
            background(240),
        )
        assertEquals(120L, foregroundSecondsToday(events, "app", window(300)))
    }

    @Test fun ongoingSessionRunsThroughTheSampleTime() {
        assertEquals(180L, foregroundSecondsToday(listOf(foreground(60)), "app", window(240)))
    }

    @Test fun sessionCrossingMidnightIsClippedToToday() {
        val events = listOf(foreground(-60), background(120))
        assertEquals(120L, foregroundSecondsToday(events, "app", window(180)))
    }

    @Test fun activityTransitionsWithinOnePackageStayForeground() {
        val events = listOf(
            foreground(60, className = "A"),
            foreground(70, className = "B"),
            background(80, className = "A"),
            background(100, className = "B"),
        )
        assertEquals(40L, foregroundSecondsToday(events, "app", window(120)))
    }

    @Test fun duplicateEventsDoNotDoubleCount() {
        val events = listOf(
            foreground(60),
            foreground(61),
            background(120),
            background(121),
        )
        assertEquals(60L, foregroundSecondsToday(events, "app", window(180)))
    }

    @Test fun screenOffTimeIsExcludedWhileTheActivityWaitsForScreenOn() {
        val events = listOf(
            foreground(60),
            screenOff(90),
            foreground(100),
            screenOn(150),
            foreground(160),
            background(200),
        )
        assertEquals(80L, foregroundSecondsToday(events, "app", window(220)))
    }

    @Test fun rebootPreservesCompletedUsageEarlierToday() {
        val events = listOf(
            foreground(0),
            background(50),
            foreground(120),
            background(180),
        )
        assertEquals(
            110L,
            foregroundSecondsToday(events, "app", window(200, bootMillis = today + 100 * 1_000L)),
        )
    }

    @Test fun startupDiscardsAnUnclosedSessionWithoutChargingDowntime() {
        val events = listOf(
            foreground(0),
            background(50),
            foreground(60),
            startup(120),
            foreground(140),
            background(200),
        )
        assertEquals(110L, foregroundSecondsToday(events, "app", window(240)))
    }

    @Test fun turningOnTheScreenDoesNotCountUsageBeforeUnlock() {
        val events = listOf(
            foreground(0),
            screenOff(30),
            keyguard(30, UsageEvents.Event.KEYGUARD_SHOWN),
            screenOn(60),
            foreground(70),
            keyguard(120, UsageEvents.Event.KEYGUARD_HIDDEN),
            background(180),
        )
        assertEquals(90L, foregroundSecondsToday(events, "app", window(200)))
    }

    @Test fun screenOnResumesAnActivityWithoutANewForegroundEvent() {
        val events = listOf(foreground(0), screenOff(30), screenOn(90), background(150))
        assertEquals(90L, foregroundSecondsToday(events, "app", window(180)))
    }

    @Test fun shutdownClosesTheSessionUntilStartup() {
        val events = listOf(
            foreground(60),
            shutdown(120),
            startup(180),
            foreground(200),
            background(250),
        )
        assertEquals(110L, foregroundSecondsToday(events, "app", window(300)))
    }

    @Test fun orphanBackgroundAfterTodayCannotEstablishABoundary() {
        assertNull(foregroundSecondsToday(listOf(background(60)), "app", window(120)))
    }

    @Test fun todayStartUsesTheLocalCalendar() {
        val zone = ZoneId.of("America/New_York")
        val noon = Instant.parse("2026-03-08T12:00:00Z").toEpochMilli()
        assertEquals(
            Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(),
            startOfTodayMillis(noon, zone),
        )
    }

    @Suppress("DEPRECATION")
    @Test fun usageEventTypesRespectThePlatformLevel() {
        assertEquals(
            UsageStampType.FOREGROUND,
            usageStampType(UsageEvents.Event.MOVE_TO_FOREGROUND, 26),
        )
        assertEquals(
            UsageStampType.BACKGROUND,
            usageStampType(UsageEvents.Event.MOVE_TO_BACKGROUND, 26),
        )
        assertEquals(
            UsageStampType.FOREGROUND,
            usageStampType(UsageEvents.Event.ACTIVITY_RESUMED, 29),
        )
        assertEquals(
            UsageStampType.STARTUP,
            usageStampType(UsageEvents.Event.DEVICE_STARTUP, 29),
        )
        assertNull(usageStampType(UsageEvents.Event.DEVICE_STARTUP, 28))
        assertNull(usageStampType(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 27))
        assertEquals(
            UsageStampType.SCREEN_OFF,
            usageStampType(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 28),
        )
        assertEquals(
            UsageStampType.LOCKED,
            usageStampType(UsageEvents.Event.KEYGUARD_SHOWN, 28),
        )
        assertNull(usageStampType(999, 35))
    }
}
