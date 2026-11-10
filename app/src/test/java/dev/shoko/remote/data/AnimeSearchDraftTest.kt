package dev.shoko.remote.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AnimeSearchDraftTest {
    private val file = SelectedFile(42, "[SubsPlease] Sousou no Frieren - 01 (1080p) [DEADBEEF].mkv", 10, "hash", status = "Dumped")

    @Test fun aNewSelectionStartsWithItsCleanedAnimeTitle() {
        val workspace = Workspace(selected = listOf(file)).prepareAnimeSearch()
        assertEquals("Sousou no Frieren", workspace.animeSearchQuery)
    }

    @Test fun aManualTitleOrManualBlankIsNotReplacedOnReentry() {
        for (query in listOf("My corrected title", "")) {
            val workspace = Workspace(selected = listOf(file), animeSearchQuery = query)
            assertEquals(query, workspace.prepareAnimeSearch().animeSearchQuery)
        }
    }

    @Test fun theEditedQuerySurvivesProcessStateSerialization() {
        val saved = Workspace(selected = listOf(file), animeSearchQuery = "My corrected title", page = "add")
        val restored = Json.decodeFromString<Workspace>(Json.encodeToString(saved))
        assertEquals("My corrected title", restored.prepareAnimeSearch().animeSearchQuery)
    }

    @Test fun oldSavedWorkspacesCanAcquireTheNewSuggestedQuery() {
        val old = Json.decodeFromString<Workspace>("""{"selected":[{"id":42,"name":"[SubsPlease] Sousou no Frieren - 01 (1080p) [DEADBEEF].mkv","size":10,"hash":"hash","status":"Dumped"}],"page":"add"}""")
        assertNull(old.animeSearchQuery)
        assertEquals("Sousou no Frieren", old.prepareAnimeSearch().animeSearchQuery)
    }

    @Test fun aDifferentServerCannotInheritThePreviousSearch() {
        val old = Workspace(serverUrl = "http://one:8111", selected = listOf(file), animeSearchQuery = "Frieren")
        assertNull(old.boundToServer("http://two:8111").animeSearchQuery)
    }

    @Test fun anOptionalParserFailureCannotPreventSavedBatchRecovery() {
        val saved = Workspace(selected = listOf(file), page = "add")
        for (error in listOf(
            ExceptionInInitializerError("Invalid platform pattern"),
            NoClassDefFoundError("Parser could not initialize"),
            IllegalArgumentException("Invalid filename"),
        )) {
            val restored = saved.prepareAnimeSearch { throw error }
            assertEquals("", restored.animeSearchQuery)
            assertEquals(saved.selected, restored.selected)
            assertEquals("add", restored.page)
        }
    }

    @Test fun anExistingManualQueryDoesNotInvokeTheSuggestionParser() {
        val saved = Workspace(selected = listOf(file), animeSearchQuery = "My title")
        assertEquals("My title", saved.prepareAnimeSearch { error("Must not be called") }.animeSearchQuery)
    }
}
