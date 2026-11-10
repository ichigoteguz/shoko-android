package dev.shoko.remote.shoko

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AnimeSearchStatus(
    val query: String = "",
    val results: List<Anime> = emptyList(),
    val loading: Boolean = false,
    val error: Throwable? = null,
) {
    val completed: Boolean get() = query.isNotBlank() && !loading && error == null
}

/** Debounces read-only searches and prevents an older batch or query from publishing results. */
class AnimeSearchController(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> List<Anime>,
    private val debounceMillis: Long = 200,
) {
    private val mutable = MutableStateFlow(AnimeSearchStatus())
    val state: StateFlow<AnimeSearchStatus> = mutable.asStateFlow()
    private var request: Job? = null
    private var revision = 0L
    private var lastQuery: String? = null

    fun submit(query: String, force: Boolean = false, immediate: Boolean = false) {
        val normalized = query.trim()
        if (!force && normalized == lastQuery) return
        val current = ++revision
        request?.cancel()
        lastQuery = normalized
        if (normalized.isEmpty()) {
            mutable.value = AnimeSearchStatus()
            return
        }
        mutable.value = AnimeSearchStatus(query = normalized, loading = true)
        request = scope.launch {
            if (!immediate) delay(debounceMillis)
            try {
                val results = search(normalized)
                if (current == revision) mutable.value = AnimeSearchStatus(query = normalized, results = results)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current == revision) mutable.value = AnimeSearchStatus(query = normalized, error = error)
            }
        }
    }

    fun reset() {
        revision++
        request?.cancel()
        request = null
        lastQuery = null
        mutable.value = AnimeSearchStatus()
    }
}
