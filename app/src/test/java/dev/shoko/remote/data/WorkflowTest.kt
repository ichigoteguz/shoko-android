package dev.shoko.remote.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkflowTest {
    private val first = SelectedFile(1, "[Group] Frieren - 01.mkv", 10, "0123456789ABCDEF0123456789ABCDEF", status = "Dumped")
    private val second = SelectedFile(2, "[Group] Another Anime - 01.mkv", 20, "FEDCBA9876543210FEDCBA9876543210")
    private val history = listOf(HistoryEntry(100, "Requested AVDump"))

    @Test fun subsetReadinessDoesNotDependOnOtherAnimeInThePool() {
        val pool = Workspace(selected = listOf(first, second), anidbSelection = listOf(first.id))
        assertEquals(listOf(first), pool.anidbFiles)
        assertTrue(pool.subsetReady)
        assertFalse(pool.copy(anidbSelection = listOf(first.id, second.id)).subsetReady)
        assertFalse(pool.copy(anidbSelection = emptyList()).subsetReady)
        assertFalse(pool.copy(anidbSelection = listOf(999)).subsetReady)
    }

    @Test fun animeSuggestionReceivesOnlyTheAniDbSubset() {
        val pool = Workspace(selected = listOf(first, second), anidbSelection = listOf(second.id))
        val suggested = pool.prepareAnimeSearch { names ->
            assertEquals(listOf(second.name), names)
            "Another Anime"
        }
        assertEquals("Another Anime", suggested.animeSearchQuery)
    }

    @Test fun oldDraftLinksRestoreOnlyTheirMatchingFilesCaseInsensitively() {
        val legacy = Workspace(selected = listOf(first, second), draft = Draft(animeId = 10,
            values = mapOf("addfilem_ed2k" to second.ed2k.lowercase())))
        val recovered = legacy.recoverSelection()
        assertEquals(listOf(second.id), recovered.anidbSelection)
        assertEquals(listOf(second), recovered.anidbFiles)
        assertEquals(legacy.draft, recovered.draft)
    }

    @Test fun oldWorkspacesWithoutLinksRetainTheirSelectionButExplicitEmptyDoesNot() {
        val legacy = Workspace(selected = listOf(first, second))
        assertEquals(listOf(first.id, second.id), legacy.recoverSelection().anidbSelection)
        assertEquals(emptyList<Int>(), legacy.copy(anidbSelection = emptyList()).recoverSelection().anidbSelection)
        assertTrue(legacy.copy(anidbSelection = emptyList()).anidbFiles.isEmpty())
    }

    @Test fun unrelatedOrInvalidDraftLinksCannotSilentlySelectTheWholePool() {
        for (links in listOf("invalid saved draft", first.copy(size = 999).ed2k)) {
            val recovered = Workspace(selected = listOf(first, second), draft = Draft(values = mapOf("addfilem_ed2k" to links))).recoverSelection()
            assertTrue(recovered.anidbFiles.isEmpty())
        }
    }

    @Test fun submittedGroupClearsEditorAndPreservesPoolForTheNextAnime() {
        val original = Workspace(serverUrl = "http://server:8111", selected = listOf(first, second),
            anidbSelection = listOf(first.id), draft = Draft(animeId = 10, animeTitle = "Frieren"),
            animeSearchQuery = "Frieren", history = history, page = "add")
        val waiting = original.reportSubmitted("group-one", listOf(first.id, first.id))
        assertEquals(original.selected, waiting.selected)
        assertEquals(Draft(), waiting.draft)
        assertEquals(emptyList<Int>(), waiting.anidbSelection)
        assertNull(waiting.animeSearchQuery)
        assertEquals("dump", waiting.page)
        assertEquals(PendingGroup("group-one", 10, "Frieren", listOf(first.id)), waiting.pendingGroups.single())
        // Even a stale/restored subset cannot submit a pending file again.
        assertTrue(waiting.copy(anidbSelection = listOf(first.id)).anidbFiles.isEmpty())
        assertEquals(listOf(second), waiting.copy(anidbSelection = listOf(second.id)).anidbFiles)
    }

    @Test fun pendingGroupsWaitForEveryFileAndMissingStatusIsNeverCompletion() {
        val group = PendingGroup("one", 10, "Anime", listOf(first.id, second.id))
        val partial = Workspace(selected = listOf(first.copy(recognized = true), second), pendingGroups = listOf(group))
        assertEquals(listOf(group), partial.removeCompletedGroups().pendingGroups)
        assertEquals(listOf(group), partial.copy(selected = listOf(first.copy(recognized = true))).removeCompletedGroups().pendingGroups)
        assertTrue(partial.copy(selected = partial.selected.map { it.copy(recognized = true) }).removeCompletedGroups().pendingGroups.isEmpty())
    }

    @Test fun emptyOrPartiallyRecognizedPoolDoesNotTriggerNewBatch() {
        assertFalse(Workspace().poolRecognized)
        assertFalse(Workspace(selected = listOf(first.copy(recognized = true), second)).poolRecognized)
        assertTrue(Workspace(selected = listOf(first.copy(recognized = true), second.copy(recognized = true))).poolRecognized)
    }

    @Test fun newBatchClearsAllWorkflowStateButPreservesConnectionAndHistory() {
        val old = Workspace(serverUrl = "http://server:8111", selected = listOf(first), anidbSelection = listOf(first.id),
            draft = Draft(animeId = 10), page = "add", animeSearchQuery = "Frieren", history = history,
            pendingGroups = listOf(PendingGroup("one", 10, "Frieren", listOf(first.id))))
        val reset = old.startNewBatch()
        assertEquals(old.serverUrl, reset.serverUrl)
        assertEquals(history, reset.history)
        assertTrue(reset.selected.isEmpty())
        assertTrue(reset.pendingGroups.isEmpty())
        assertEquals(emptyList<Int>(), reset.anidbSelection)
        assertEquals(Draft(), reset.draft)
        assertEquals("files", reset.page)
        assertNull(reset.animeSearchQuery)
    }

    @Test fun activeSubsetAndPendingGroupsSurviveSerialization() {
        val saved = Workspace(selected = listOf(first, second), anidbSelection = listOf(second.id),
            pendingGroups = listOf(PendingGroup("one", 10, "Frieren", listOf(first.id))))
        val restored = Json.decodeFromString<Workspace>(Json.encodeToString(saved)).recoverSelection()
        assertEquals(saved, restored)
        assertEquals(listOf(second), restored.anidbFiles)
        val rebound = restored.boundToServer("http://another-server:8111")
        assertTrue(rebound.selected.isEmpty())
        assertTrue(rebound.pendingGroups.isEmpty())
        assertTrue(rebound.anidbFiles.isEmpty())
    }
}
