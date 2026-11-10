package dev.shoko.remote.data

import dev.shoko.remote.anidb.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DraftSyncTest {
    private val source = "addfilem_type"
    private val links = "addfilem_ed2k"
    private val clean = "addstrm_vid_flag_FLAG_CLEAN"
    private fun field(id: String, value: String) = AniDbField(id, "select", id, value)
    private fun page(vararg fields: AniDbField, rows: List<AniDbReviewRow> = emptyList(), audio: Int = 1) = AniDbFormSnapshot(
        status = if (rows.isEmpty()) "form" else "review", url = "https://anidb.net/anime/42/release/add",
        fields = fields.toList(), rows = rows, audioTracks = audio, subtitleTracks = 1,
    )
    private fun draft(page: AniDbFormSnapshot) = DraftSync.merge(Draft(animeId = 42), page)
    private fun row(filename: String, episode: String = "1", version: String = "v1", date: String = "") = AniDbReviewRow(
        0, filename, "abcd1234", "mkv", field("episode", episode), field("version", version), field("date", date),
    )

    @Test fun nativeEditsSurviveAnUnchangedPageButOfficialChangesWin() {
        val initial = page(field(source, "unknown"))
        val edited = draft(initial).copy(values = mapOf(source to "www"))
        assertEquals("www", DraftSync.merge(edited, initial).values[source])
        assertEquals("Blu-ray", DraftSync.merge(edited, page(field(source, "Blu-ray"))).values[source])
    }

    @Test fun selectedTemplateAdoptsItsValuesAndFalseCheckboxes() {
        val initial = page(field(source, "unknown"), AniDbField(clean, "checkbox", "clean", checked = true))
        val edited = draft(initial).copy(values = mapOf(source to "www"), checks = mapOf(clean to true))
        val applied = DraftSync.merge(edited, page(field(source, "DVD"), AniDbField(clean, "checkbox", "clean", checked = false)), adoptTemplate = true)
        assertEquals("DVD", applied.values[source])
        assertEquals(false, applied.checks[clean])
    }

    @Test fun reviewKeepsLinksForRecognitionButDoesNotTransferRemovedTextarea() {
        val initial = page(field(source, "www"), field(links, "ed2k link"))
        val review = page(field(source, "www"), rows = listOf(row("one.mkv")))
        val merged = DraftSync.merge(draft(initial), review)
        assertEquals("ed2k link", merged.values[links])
        assertFalse(DraftSync.transfer(merged, review).values.containsKey(links))
    }

    @Test fun removingTracksPrunesBothValuesAndFlags() {
        val initial = page(field("addstrm_aud_1_type", "normal"), field("addstrm_aud_2_type", "commentary"), audio = 2)
        val edited = draft(initial).copy(checks = mapOf("addstrm_sub_2_flag_FLAG_HEARINGIMP" to true), subtitleTracks = 2)
        val shrunk = DraftSync.withTrackCount(DraftSync.withTrackCount(edited, true, 1), false, 1)
        assertFalse(shrunk.values.containsKey("addstrm_aud_2_type"))
        assertFalse(shrunk.checks.containsKey("addstrm_sub_2_flag_FLAG_HEARINGIMP"))
        assertEquals(1, DraftSync.transfer(shrunk, initial).audioTracks)
    }

    @Test fun templateResolvesTrackCountsAndExcludesBatchIdentity() {
        val initial = page(field(source, "www"), field("addfilem_group", "9"), field(links, "batch"), audio = 2)
        val template = DraftSync.template(draft(initial).copy(audioTracks = null))
        assertEquals(2, template.audioTracks)
        assertEquals("www", template.values[source])
        assertFalse(template.values.containsKey(links))
        assertFalse(template.values.containsKey("addfilem_group"))
        assertEquals(0, template.animeId)
    }

    @Test fun reviewThreeWayMergeKeepsNativeVersionAndOfficialDate() {
        val baseline = page(rows = listOf(row("one.mkv")))
        val edited = draft(baseline).copy(review = listOf(ReviewValue(0, "1", "v2", "")))
        val changed = page(rows = listOf(row("one.mkv", date = "2026-11-08")))
        val result = DraftSync.merge(edited, changed).review.single()
        assertEquals("v2", result.version)
        assertEquals("2026-11-08", result.releaseDate)
    }

    @Test fun differentFileIdentityCannotInheritAnotherFilesEdits() {
        val baseline = page(rows = listOf(row("one.mkv")))
        val edited = draft(baseline).copy(review = listOf(ReviewValue(0, "99", "v5", "2020-01-01")))
        val result = DraftSync.merge(edited, page(rows = listOf(row("two.mkv")))).review.single()
        assertEquals("1", result.episode)
        assertEquals("v1", result.version)
        assertEquals("", result.releaseDate)
    }

    @Test fun recoveredExtraTracksCanBeRecreatedBeforeTheirValuesAreTransferred() {
        val initial = page(field("addstrm_aud_1_type", "normal"), field("addstrm_aud_2_type", "commentary"), audio = 2)
        val saved = draft(initial)
        val fresh = page(field("addstrm_aud_1_type", "normal"), audio = 1)
        val recovered = DraftSync.merge(saved, fresh, preserveSaved = true)
        val transfer = DraftSync.transfer(recovered, fresh)
        assertEquals(2, transfer.audioTracks)
        assertEquals("commentary", transfer.values["addstrm_aud_2_type"])
    }

    @Test fun originAndAnimeIdentityAreExact() {
        assertTrue(DraftSync.belongsToAnime(page(), 42))
        assertFalse(DraftSync.belongsToAnime(page().copy(url = "https://anidb.net.evil.test/anime/42/release/add"), 42))
        assertFalse(DraftSync.belongsToAnime(page(), 4))
    }

    @Test fun restoringCredentialsForAnotherServerCannotReuseFileIdsOrDrafts() {
        val saved = Workspace(serverUrl = "http://server-a:8111", selected = listOf(SelectedFile(42, "one.mkv", 10, "hash")), draft = Draft(animeId = 42))
        val rebound = saved.boundToServer("http://server-b:8111")
        assertTrue(rebound.selected.isEmpty())
        assertEquals(0, rebound.draft.animeId)
        assertEquals("http://server-b:8111", rebound.serverUrl)
        assertEquals(saved, saved.boundToServer("http://server-a:8111"))
    }
}
