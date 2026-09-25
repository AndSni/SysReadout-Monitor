package com.asnidev.sysreadoutmonitor.term

/**
 * Every warning and critical level in one place. A value inside its normal
 * range is [Tone.GOOD]; callers that only want to flag trouble (table cells)
 * use [flag], which turns GOOD into plain text.
 */
object Thresholds {
    const val LOAD_WARN = 70.0
    const val LOAD_CRIT = 90.0
    const val BATTERY_WARN = 30
    const val BATTERY_CRIT = 15
    const val TEMP_WARN = 45.0
    const val TEMP_CRIT = 55.0
    const val STORAGE_WARN = 85.0
    const val STORAGE_CRIT = 95.0

    /** CPU % and memory used %. */
    fun load(percent: Double): Tone = when {
        percent >= LOAD_CRIT -> Tone.CRIT
        percent >= LOAD_WARN -> Tone.WARN
        else -> Tone.GOOD
    }

    fun battery(level: Int): Tone = when {
        level < BATTERY_CRIT -> Tone.CRIT
        level < BATTERY_WARN -> Tone.WARN
        else -> Tone.GOOD
    }

    fun temperature(celsius: Double): Tone = when {
        celsius > TEMP_CRIT -> Tone.CRIT
        celsius > TEMP_WARN -> Tone.WARN
        else -> Tone.GOOD
    }

    /** Storage used %. */
    fun storage(percent: Double): Tone = when {
        percent > STORAGE_CRIT -> Tone.CRIT
        percent > STORAGE_WARN -> Tone.WARN
        else -> Tone.GOOD
    }

    /** PowerManager thermal status as ProbeReader words it (none, light, moderate, SEVERE…). */
    fun thermal(status: String): Tone = when (status.lowercase()) {
        "none" -> Tone.GOOD
        "light", "moderate" -> Tone.WARN
        "severe", "critical", "emergency", "shutdown" -> Tone.CRIT
        else -> Tone.FG
    }

    /** Signal strength in words, as ProbeReader spells Android's 0–4 level. */
    fun signal(words: String): Tone = when (words.lowercase()) {
        "very strong", "strong" -> Tone.GOOD
        "medium" -> Tone.WARN
        "weak", "very weak", "no signal" -> Tone.CRIT
        else -> Tone.FG
    }

    /** A radio signal in dBm: the same cut-offs as the words (strong and better, medium, weak). */
    fun dbm(dbm: Int): Tone = signal(Signal.words(dbm))

    /** The low-memory flag is always critical. */
    val lowMemory = Tone.CRIT

    /** For table cells: only warnings and criticals get colour. */
    fun flag(tone: Tone): Tone = if (tone == Tone.GOOD) Tone.FG else tone
}
