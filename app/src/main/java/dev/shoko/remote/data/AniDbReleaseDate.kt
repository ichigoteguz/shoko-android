package dev.shoko.remote.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** AniDB's release-date text format, separate from Shoko's ISO timestamps. */
object AniDbReleaseDate {
    private val formatter = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT)
    private val legacyIso = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")

    fun format(date: LocalDate): String = date.format(formatter)
    fun today(): String = format(LocalDate.now())

    /** Convert complete, valid dates saved by 0.1.5; leave edits and blanks intact. */
    fun normalize(value: String): String = if (legacyIso.matches(value))
        runCatching { format(LocalDate.parse(value)) }.getOrDefault(value) else value
}
