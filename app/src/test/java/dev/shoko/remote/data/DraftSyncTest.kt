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
        val changed = page(rows = listOf(row("one.mkv", date = "08.11.2026")))
        val result = DraftSync.merge(edited, changed).review.single()
        assertEquals("v2", result.version)
        assertEquals("08.11.2026", result.releaseDate)
    }

    @Test fun differentFileIdentityCannotInheritAnotherFilesEdits() {
        val baseline = page(rows = listOf(row("one.mkv")))
        val edited = draft(baseline).copy(review = listOf(ReviewValue(0, "99", "v5", "01.01.2020")))
        val result = DraftSync.merge(edited, page(rows = listOf(row("two.mkv"))), today = "14.11.2026").review.single()
        assertEquals("1", result.episode)
        assertEquals("v1", result.version)
        assertEquals("14.11.2026", result.releaseDate)
    }

    @Test fun firstReviewUsesDetectedEpisodeVersionAndDefaultsOnlyBlankDates() {
        val detected = page(rows = listOf(row("one.mkv", episode = "12", version = "v3")))
        val initial = DraftSync.merge(Draft(animeId = 42), detected, today = "14.11.2026").review.single()
        assertEquals("12", initial.episode)
        assertEquals("v3", initial.version)
        assertEquals("14.11.2026", initial.releaseDate)
        val dated = detected.copy(rows = listOf(row("one.mkv", date = "29.02.2020")))
        assertEquals("29.02.2020", DraftSync.merge(Draft(animeId = 42), dated, today = "14.11.2026").review.single().releaseDate)
    }

    @Test fun refreshAndRestartDoNotReplaceDefaultedOrExplicitlyEditedDates() {
        val official = page(rows = listOf(row("one.mkv")))
        val initial = DraftSync.merge(Draft(animeId = 42), official, today = "14.11.2026")
        assertEquals("14.11.2026", DraftSync.merge(initial, official, today = "15.11.2026").review.single().releaseDate)
        for (date in listOf("29.02.2024", "")) {
            val edited = initial.copy(review = initial.review.map { it.copy(releaseDate = date) })
            val restored = Json.decodeFromString<Draft>(Json.encodeToString(edited))
            assertEquals(date, DraftSync.merge(restored, official, preserveSaved = true, today = "15.11.2026").review.single().releaseDate)
            assertEquals(date, DraftSync.merge(edited, official, today = "15.11.2026").review.single().releaseDate)
        }
    }

    @Test fun baselineInlineErrorCannotOverwriteAnInFlightSubmission() {
        val submitting = Draft(submission = "Submitting to AniDB")
        val baselineError = page(rows = listOf(row("one.mkv"))).copy(errorMessage = "Invalid episode")
        assertEquals(submitting.submission, DraftSync.submissionFeedback(submitting, baselineError, actionBusy = true))
        assertEquals(submitting.submission, DraftSync.submissionFeedback(submitting, baselineError, actionBusy = false))
    }

    @Test fun unchangedErrorsAfterUnknownOrReloadCannotEnableAnotherSubmission() {
        val unknown = Draft(submission = "Outcome unknown; check AniDB before retrying")
        val restored = Json.decodeFromString<Draft>(Json.encodeToString(unknown))
        for (status in listOf("form", "review", "error")) {
            val oldError = AniDbFormSnapshot(status = status, errorMessage = "Invalid episode", message = "Invalid episode")
            assertEquals(restored.submission, DraftSync.submissionFeedback(restored, oldError, actionBusy = false))
        }
    }

    @Test fun confirmedNewRejectionMakesAnAmbiguousAttemptRetryableOnlyAfterCompletion() {
        val rejection = page(rows = listOf(row("one.mkv"))).copy(errorMessage = "Invalid episode", submissionRejected = true)
        for (submission in listOf("Submitting to AniDB", "Outcome unknown; check AniDB before retrying")) {
            val pending = Draft(submission = submission)
            assertEquals(submission, DraftSync.submissionFeedback(pending, rejection, actionBusy = true))
            assertEquals("AniDB error: Invalid episode", DraftSync.submissionFeedback(pending, rejection, actionBusy = false))
        }
    }

    @Test fun validationErrorsBeforeAnySubmissionRemainVisibleAndEditable() {
        val validation = page().copy(errorMessage = "Select a group")
        assertEquals("AniDB error: Select a group", DraftSync.submissionFeedback(Draft(), validation, actionBusy = false))
        val correctedAttempt = Draft(submission = "AniDB error: Select a group")
        assertEquals("AniDB error: Invalid episode", DraftSync.submissionFeedback(correctedAttempt,
            validation.copy(errorMessage = "Invalid episode"), actionBusy = false))
    }

    @Test fun onlyExplicitSuccessConfirmsAcceptanceNotNavigationOrMessageText() {
        val submitting = Draft(submission = "Submitting to AniDB")
        val message = "Files successfully added"
        assertEquals("AniDB confirmed: $message", DraftSync.submissionFeedback(submitting,
            AniDbFormSnapshot(status = "success", message = message), actionBusy = false))
        for (status in listOf("page", "form", "review", "login", "challenge")) {
            val navigation = AniDbFormSnapshot(status = status, url = "https://anidb.net/anime/42", message = message)
            assertEquals(submitting.submission, DraftSync.submissionFeedback(submitting, navigation, actionBusy = false))
        }
    }

    @Test fun unknownOutcomeIsDistinctFromARejectedOrAcceptedSubmission() {
        val result = DraftSync.submissionFeedback(Draft(submission = "Submitting to AniDB"),
            AniDbFormSnapshot(status = "unknown", message = "Response interrupted", errorMessage = "Old validation error"), actionBusy = false)
        assertEquals("Outcome unknown; check AniDB before retrying", result)
        assertFalse(result.startsWith("AniDB error:"))
        assertFalse(result.startsWith("AniDB confirmed:"))
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
