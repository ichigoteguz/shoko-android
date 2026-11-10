package dev.shoko.remote.data

import dev.shoko.remote.anidb.AniDbEdits
import dev.shoko.remote.anidb.AniDbFormSnapshot
import dev.shoko.remote.anidb.AniDbReviewRow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Pure three-way reconciliation: a newer official edit wins, otherwise keep native edits. */
object DraftSync {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val trackId = Regex("^addstrm_(aud|sub)_(\\d+)_.*$")
    private val animeUrl = Regex("^https://anidb\\.net(?::443)?/anime/(\\d+)/release/add(?:[?#].*)?$", RegexOption.IGNORE_CASE)

    fun belongsToAnime(snapshot: AniDbFormSnapshot, animeId: Int): Boolean =
        animeUrl.matchEntire(snapshot.url)?.groupValues?.get(1)?.toIntOrNull() == animeId

    fun decodeSchema(draft: Draft): AniDbFormSnapshot? =
        runCatching { json.decodeFromString<AniDbFormSnapshot>(draft.schema) }.getOrNull()

    fun merge(old: Draft, incoming: AniDbFormSnapshot, preserveSaved: Boolean = false, adoptTemplate: Boolean = false): Draft {
        val previous = decodeSchema(old)
        val beforeValues = previous?.fields?.associate { it.id to it.value }.orEmpty()
        val beforeChecks = previous?.fields?.associate { it.id to it.checked }.orEmpty()
        val values = incoming.fields.filter { it.kind != "checkbox" }.associate { field ->
            val local = old.values[field.id]
            val baseline = beforeValues[field.id]
            val value = when {
                field.id == "addfilem_ed2k" && !old.initialized && !local.isNullOrBlank() -> local
                adoptTemplate -> field.value
                preserveSaved && local != null -> local
                local != null && baseline != null && field.value == baseline -> local
                else -> field.value
            }
            field.id to value
        }.toMutableMap()
        // Links remain useful for Shoko recognition when AniDB removes its entry textarea on review.
        if (!values.containsKey("addfilem_ed2k")) old.values["addfilem_ed2k"]?.let { values["addfilem_ed2k"] = it }
        val checks = incoming.fields.filter { it.kind == "checkbox" }.associate { field ->
            val local = old.checks[field.id]
            val baseline = beforeChecks[field.id]
            field.id to when {
                adoptTemplate -> field.checked
                preserveSaved && local != null -> local
                local != null && baseline != null && field.checked == baseline -> local
                else -> field.checked
            }
        }.toMutableMap()
        val audio = if (preserveSaved) old.audioTracks ?: incoming.audioTracks else incoming.audioTracks
        val subtitles = if (preserveSaved) old.subtitleTracks ?: incoming.subtitleTracks else incoming.subtitleTracks
        if (preserveSaved) {
            old.values.filterKeys { allowedTrack(it, audio, subtitles) }.forEach { (id, value) -> values.putIfAbsent(id, value) }
            old.checks.filterKeys { allowedTrack(it, audio, subtitles) }.forEach { (id, value) -> checks.putIfAbsent(id, value) }
        }
        val identities = incoming.rows.map(::identity)
        val pageReview = incoming.rows.map { ReviewValue(it.index, it.episode.value, it.version.value, it.releaseDate.value) }
        val review = when {
            incoming.status != "review" -> old.review
            old.reviewIdentity != identities -> pageReview
            preserveSaved -> pageReview.map { page -> old.review.find { it.index == page.index } ?: page }
            else -> pageReview.map { page ->
                val local = old.review.find { it.index == page.index } ?: return@map page
                val baseline = old.reviewBaseline.find { it.index == page.index } ?: return@map local
                ReviewValue(page.index,
                    if (page.episode == baseline.episode) local.episode else page.episode,
                    if (page.version == baseline.version) local.version else page.version,
                    if (page.releaseDate == baseline.releaseDate) local.releaseDate else page.releaseDate)
            }
        }
        return old.copy(schema = json.encodeToString(incoming), values = values, checks = checks,
            audioTracks = audio, subtitleTracks = subtitles, initialized = true, review = review,
            reviewIdentity = if (incoming.status == "review") identities else old.reviewIdentity,
            reviewBaseline = if (incoming.status == "review") pageReview else old.reviewBaseline)
    }

    fun transfer(draft: Draft, live: AniDbFormSnapshot): AniDbEdits {
        val ids = live.fields.map { it.id }.toSet()
        val audio = draft.audioTracks ?: live.audioTracks
        val subtitles = draft.subtitleTracks ?: live.subtitleTracks
        fun keep(id: String): Boolean {
            if (trackId.matches(id)) return allowedTrack(id, audio, subtitles)
            return id in ids
        }
        return AniDbEdits(draft.values.filterKeys(::keep), draft.checks.filterKeys(::keep), audio, subtitles)
    }

    fun withTrackCount(draft: Draft, audio: Boolean, count: Int): Draft {
        val next = if (audio) draft.copy(audioTracks = count.coerceAtLeast(0), dirty = true)
            else draft.copy(subtitleTracks = count.coerceAtLeast(0), dirty = true)
        val audioCount = next.audioTracks ?: decodeSchema(next)?.audioTracks ?: 0
        val subCount = next.subtitleTracks ?: decodeSchema(next)?.subtitleTracks ?: 0
        fun keep(id: String) = !trackId.matches(id) || allowedTrack(id, audioCount, subCount)
        return next.copy(values = next.values.filterKeys(::keep), checks = next.checks.filterKeys(::keep))
    }

    fun template(draft: Draft): Draft {
        val schema = decodeSchema(draft)
        return draft.copy(schema = "", review = emptyList(), reviewIdentity = emptyList(), reviewBaseline = emptyList(),
            animeId = 0, animeTitle = "", initialized = false, dirty = false, submission = "Not submitted",
            audioTracks = draft.audioTracks ?: schema?.audioTracks ?: 0,
            subtitleTracks = draft.subtitleTracks ?: schema?.subtitleTracks ?: 0,
            values = draft.values.filterKeys { it !in setOf("addfilem_ed2k", "addfilem_group", "addfilem_group_input", "addfilem_template") })
    }

    private fun identity(row: AniDbReviewRow) = "${row.index}|${row.filename}|${row.crc}|${row.extension}"
    private fun allowedTrack(id: String, audio: Int, subtitles: Int): Boolean {
        val match = trackId.matchEntire(id) ?: return false
        val number = match.groupValues[2].toIntOrNull() ?: return false
        return number in 1..(if (match.groupValues[1] == "aud") audio else subtitles)
    }
}
