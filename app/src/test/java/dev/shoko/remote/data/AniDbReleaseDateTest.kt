package dev.shoko.remote.data

import dev.shoko.remote.anidb.AniDbField
import dev.shoko.remote.anidb.AniDbFormSnapshot
import dev.shoko.remote.anidb.AniDbReviewRow
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AniDbReleaseDateTest {
    @Test fun formatterUsesDayMonthYearWithLeadingZeroes() {
        assertEquals("02.01.2026", AniDbReleaseDate.format(LocalDate.of(2026, 1, 2)))
        assertEquals("29.02.2024", AniDbReleaseDate.format(LocalDate.of(2024, 2, 29)))
    }

    @Test fun legacyIsoConversionPreservesTheDateAndOnlyChangesValidCompleteIso() {
        assertEquals("14.11.2026", AniDbReleaseDate.normalize("2026-11-14"))
        for (unchanged in listOf("", "14.11.2026", "2.1.2026", "14.11.", "2026-11-", "2026-02-30", "custom text"))
            assertEquals(unchanged, AniDbReleaseDate.normalize(unchanged))
    }

    @Test fun oldSavedReviewDatesAndBaselinesMigrateWithoutChangingOtherEdits() {
        val date = "2026-11-14"
        val field = AniDbField("date", "text", "Release date", date)
        val page = AniDbFormSnapshot(status = "review", dateFormat = "yyyy-MM-dd", rows = listOf(
            AniDbReviewRow(0, "one.mkv", "abcd", "mkv", field.copy(value = "2"), field.copy(value = "3"), field)))
        val saved = Draft(animeId = 42, schema = Json.encodeToString(page),
            review = listOf(ReviewValue(0, "2", "3", date), ReviewValue(1, "4", "1", "")),
            reviewBaseline = listOf(ReviewValue(0, "1", "1", "2020-02-29")), dirty = true)
        val migrated = DraftSync.normalizeReleaseDates(Json.decodeFromString<Draft>(Json.encodeToString(saved)))
        assertEquals(saved.copy(schema = migrated.schema, review = listOf(ReviewValue(0, "2", "3", "14.11.2026"),
            ReviewValue(1, "4", "1", "")), reviewBaseline = listOf(ReviewValue(0, "1", "1", "29.02.2020"))), migrated)
        assertEquals("dd.MM.yyyy", DraftSync.decodeSchema(migrated)?.dateFormat)
        assertEquals("14.11.2026", DraftSync.decodeSchema(migrated)?.rows?.single()?.releaseDate?.value)
        assertEquals(migrated, DraftSync.normalizeReleaseDates(migrated))
    }

    @Test fun restoredDateSurvivesTheFirstCorrectlyFormattedOfficialReview() {
        val field = AniDbField("date", "text", "Release date")
        val row = AniDbReviewRow(0, "one.mkv", "abcd", "mkv", field.copy(value = "1"), field.copy(value = "2"), field)
        val page = AniDbFormSnapshot(status = "review", rows = listOf(row))
        val draft = DraftSync.merge(Draft(), page, today = "2026-11-14")
        val restored = DraftSync.merge(draft, page, preserveSaved = true, today = "15.11.2026")
        assertEquals("14.11.2026", restored.review.single().releaseDate)
        assertEquals("1", restored.review.single().episode)
        assertEquals("2", restored.review.single().version)
    }
}
