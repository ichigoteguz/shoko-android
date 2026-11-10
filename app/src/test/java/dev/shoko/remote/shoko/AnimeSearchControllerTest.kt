package dev.shoko.remote.shoko

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnimeSearchControllerTest {
    @Test fun submittedTitleSearchesAutomaticallyAfterTheDebounce() = runTest {
        val queries = mutableListOf<String>()
        val controller = AnimeSearchController(this, { queries += it; listOf(Anime(42, it)) })
        controller.submit("Sousou no Frieren")
        assertTrue(controller.state.value.loading)
        advanceTimeBy(199); runCurrent()
        assertTrue(queries.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("Sousou no Frieren"), queries)
        assertEquals("Sousou no Frieren", controller.state.value.results.single().title)
        assertTrue(controller.state.value.completed)
    }

    @Test fun rapidTypingOnlySearchesTheLatestText() = runTest {
        val queries = mutableListOf<String>()
        val controller = AnimeSearchController(this, { queries += it; emptyList() })
        controller.submit("Fri")
        advanceTimeBy(100)
        controller.submit("Frieren")
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf("Frieren"), queries)
    }

    @Test fun repeatedPageEntryAndPollingDoNotRepeatTheSameSearch() = runTest {
        var calls = 0
        val controller = AnimeSearchController(this, { calls++; listOf(Anime(42, it)) })
        controller.submit("Frieren", immediate = true)
        runCurrent()
        repeat(5) { controller.submit("Frieren") }
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, calls)
    }

    @Test fun anOldResponseCannotReplaceANewerQuery() = runTest {
        val oldResponse = CompletableDeferred<List<Anime>>()
        val controller = AnimeSearchController(this, { query ->
            if (query == "old") withContext(NonCancellable) { oldResponse.await() } else listOf(Anime(2, query))
        })
        controller.submit("old", immediate = true); runCurrent()
        controller.submit("new", immediate = true); runCurrent()
        assertEquals(2, controller.state.value.results.single().id)
        oldResponse.complete(listOf(Anime(1, "old"))); runCurrent()
        assertEquals("new", controller.state.value.query)
        assertEquals(2, controller.state.value.results.single().id)
    }

    @Test fun resettingTheBatchOrConnectionDiscardsAnInFlightResponse() = runTest {
        val response = CompletableDeferred<List<Anime>>()
        val controller = AnimeSearchController(this, { withContext(NonCancellable) { response.await() } })
        controller.submit("old batch", immediate = true); runCurrent()
        controller.reset()
        response.complete(listOf(Anime(1, "old batch"))); runCurrent()
        assertEquals(AnimeSearchStatus(), controller.state.value)
    }

    @Test fun manualBlankCancelsTheSearchAndClearsResults() = runTest {
        var calls = 0
        val controller = AnimeSearchController(this, { calls++; listOf(Anime(1, it)) })
        controller.submit("Frieren")
        controller.submit("")
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, calls)
        assertEquals(AnimeSearchStatus(), controller.state.value)
    }

    @Test fun failureWaitsForAnExplicitRetryRatherThanRetryingOnEveryRefresh() = runTest {
        var calls = 0
        val controller = AnimeSearchController(this, {
            calls++
            if (calls == 1) throw ShokoException(503, "Server unavailable")
            listOf(Anime(42, it))
        })
        controller.submit("Frieren", immediate = true); runCurrent()
        assertEquals("Server unavailable", controller.state.value.error?.message)
        controller.submit("Frieren"); advanceTimeBy(1000); runCurrent()
        assertEquals(1, calls)
        controller.submit("Frieren", force = true, immediate = true); runCurrent()
        assertEquals(2, calls)
        assertTrue(controller.state.value.completed)
    }

    @Test fun anExplicitNumericQueryStillReachesTheExistingSearchApi() = runTest {
        val queries = mutableListOf<String>()
        val controller = AnimeSearchController(this, { queries += it; emptyList() })
        controller.submit(" 19754 ", immediate = true); runCurrent()
        assertEquals(listOf("19754"), queries)
        assertTrue(controller.state.value.completed)
    }
}
