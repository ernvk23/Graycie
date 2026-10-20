package now.abhi.graycie

/** Provider seam for testing write ordering and failures without Android. */
interface SecureSettingsAccess {
    fun hasPermission(): Boolean
    fun readInt(key: String): Int?
    fun writeInt(key: String, value: Int): Boolean
    fun clear(key: String): Boolean
}

data class CorrectionValues(val mode: Int?, val enabled: Int?)

/** Pending records a single write that may have completed before a process interruption. */
data class CorrectionSession(
    val original: CorrectionValues,
    val expected: CorrectionValues,
    val pending: CorrectionValues? = null,
    val completed: Boolean = false,
)

interface CorrectionSessionStore {
    fun read(): CorrectionSession?
    /** Must save durably before returning true. */
    fun write(session: CorrectionSession): Boolean
}

class SecureGrayscaleSettings(
    private val access: SecureSettingsAccess,
    private val sessions: CorrectionSessionStore,
) : GrayscaleSettings {
    override fun hasPermission() = access.hasPermission()

    override fun isApplied(enabled: Boolean): Boolean {
        val current = readValues()
        if (enabled) return current == CorrectionValues(0, 1)
        val original = sessions.read()?.takeIf { !it.completed && it.matches(current) }?.original ?: current
        val color = if (original.mode == 0) original.copy(enabled = 0) else original
        return current == color
    }

    override fun setGrayscale(enabled: Boolean): Boolean {
        requirePermission()
        val current = readValues()
        val session = sessions.read()?.takeIf { !it.completed && it.matches(current) }
        // A manual correction becomes the baseline if management later takes over again.
        if (session == null) save(CorrectionSession(current, current))
        // Color apps keep the saved correction; a grayscale baseline must stay disabled.
        return if (enabled) {
            putIfChanged(MODE, 0) && putIfChanged(ENABLED, 1)
        } else {
            val original = session?.original ?: current
            val color = if (original.mode == 0) original.copy(enabled = 0) else original
            applyValues(color)
        }
    }

    override fun release(): Boolean {
        requirePermission()
        val session = sessions.read()
        if (session == null) {
            // Legacy sessions have no backup; only disable recognizable monochromacy.
            val current = readValues()
            val fallback = if (current.mode == 0) current.copy(enabled = 0) else current
            save(CorrectionSession(fallback, current))
            if (current.mode == 0 && !putIfChanged(ENABLED, 0, restoring = true)) return false
        } else {
            if (session.completed) return true
            if (!session.matches(readValues())) {
                // The user changed correction after our last write. Preserve that choice.
                save(session.copy(completed = true, pending = null))
                return true
            }
            if (!applyValues(session.original, restoring = true)) return false
        }
        save(checkNotNull(sessions.read()).copy(completed = true, pending = null))
        return true
    }

    private fun applyValues(values: CorrectionValues, restoring: Boolean = false): Boolean {
        // Disable before switching modes; the journal keeps ownership through the transition.
        if (readValues().mode != values.mode &&
            !putIfChanged(ENABLED, 0, restoring)) return false
        return putIfChanged(MODE, values.mode, restoring) &&
            putIfChanged(ENABLED, values.enabled, restoring)
    }

    private fun requirePermission() {
        if (!hasPermission()) throw SecurityException("Secure settings permission missing")
    }

    private fun readValues() = CorrectionValues(access.readInt(MODE), access.readInt(ENABLED))

    private fun CorrectionSession.matches(values: CorrectionValues) =
        values == expected || values == pending

    private fun save(session: CorrectionSession) {
        check(sessions.write(session)) { "Could not save Color correction recovery settings" }
    }

    private fun putIfChanged(key: String, value: Int?, restoring: Boolean = false): Boolean {
        val current = readValues()
        val session = checkNotNull(sessions.read())
        if (restoring) {
            if (session.completed) return true
            // Recheck between restore steps in case the user changed settings meanwhile.
            if (!session.matches(current)) {
                save(session.copy(completed = true, pending = null))
                return true
            }
        }
        if ((if (key == MODE) current.mode else current.enabled) == value) return true
        val next = if (key == MODE) current.copy(mode = value) else current.copy(enabled = value)
        save(session.copy(expected = current, pending = next))
        val accepted = if (value == null) access.clear(key) else access.writeInt(key, value)
        if (accepted) save(session.copy(expected = next, pending = null))
        // Retain pending on failure: providers can fail after applying the write.
        return accepted
    }

    companion object {
        const val MODE = "accessibility_display_daltonizer"
        const val ENABLED = "accessibility_display_daltonizer_enabled"
    }
}
