package com.asnidev.sysreadoutmonitor.term

/** Width of the key column; the longest key ("compass") plus a space. */
const val KEY_WIDTH = 8

/** A `key     value` row; the value continues under itself when it wraps. */
fun row(key: String, value: List<Span>): Line =
    Line(listOf(Span(key.padEnd(KEY_WIDTH), Tone.KEY)) + value, indent = KEY_WIDTH)

fun row(key: String, value: String): Line = row(key, listOf(Span(value)))

/** A dim `# section` comment. */
fun comment(text: String): Line = Line(listOf(Span("# $text", Tone.DIM)), indent = 2)

/** A tappable `! needs …, tap to …` line. */
fun needs(text: String, tap: Tap): Line = Line(listOf(Span("! $text", Tone.LINK, tap)), indent = 2)

/** The same, as the value of a row. */
fun needsRow(key: String, text: String, tap: Tap): Line = row(key, listOf(Span("! $text", Tone.LINK, tap)))

/** Fedora's default prompt, `[user@host ~]$ `, and the host name it shows. */
object Prompt {
    fun text(host: String) = "[user@$host ~]$ "

    /**
     * The phone's name as a host name: lowercased, spaces as hyphens, only
     * letters, digits and hyphens ("Xperia 10 IV" → "xperia-10-iv").
     */
    fun host(deviceName: String?, model: String): String =
        listOfNotNull(deviceName, model).map(::sanitize).firstOrNull { it.isNotEmpty() } ?: "localhost"

    private fun sanitize(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^\\p{L}\\p{N}-]"), "")
            .replace(Regex("-{2,}"), "-").trim('-')
}
