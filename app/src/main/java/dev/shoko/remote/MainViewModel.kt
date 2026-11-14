package dev.shoko.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shoko.remote.anidb.*
import dev.shoko.remote.data.*
import dev.shoko.remote.shoko.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AppState(
    val workspace: Workspace = Workspace(), val ready: Boolean = false, val connected: Boolean = false,
    val serverUrl: String = "", val username: String = "", val files: List<ShokoFile> = emptyList(),
    val total: Int = 0, val pageNumber: Int = 1, val query: String = "", val animeResults: List<Anime> = emptyList(),
    val animeSearching: Boolean = false, val animeSearchCompleted: Boolean = false, val animeSearchError: String? = null,
    val templates: List<TemplateEntity> = emptyList(), val busy: Boolean = false, val live: Boolean = false,
    val notice: String? = null, val filesLoading: Boolean = false, val filesReset: Long = 0, val editorBusy: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dao = (application as ShokoApplication).database.workspace()
    private val credentials = CredentialStore(application)
    val aniDb = AniDbWebController(application)
    private val mutable = MutableStateFlow(AppState(serverUrl = credentials.serverUrl, username = credentials.username))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private var repository: ShokoRepository? = null
    private var monitor: AVDumpMonitor? = null
    private var monitorEvents: Job? = null
    private var monitorState: Job? = null
    private var poll: Job? = null
    private var actionJob: Job? = null
    private val saveMutex = Mutex()
    private val reconcileMutex = Mutex()
    private var foreground = false
    private var storageWritable = false
    private var storageErrorReported = false
    private var connectionEpoch = 0L
    private var batchEpoch = 0L
    private var editorEpoch = 0L
    private var filesRequest = 0L
    private var filesJob: Job? = null
    private var editorAction = false
        set(value) { field = value; mutable.update { it.copy(editorBusy = value) } }
    private var manualOfficial = false
    private val dumpRevisions = mutableMapOf<Int, Long>()
    private var pendingTemplate: String? = null
    private var restoreDefaultTemplate = false
    private var restoreSavedPage = false
    private var restoreSavedReview = false
    private val animeSearch = AnimeSearchController(viewModelScope, { query -> connectedRepository().animeSearch(query).list })

    init {
        viewModelScope.launch {
            val loaded = runCatching { withContext(Dispatchers.IO) { dao.load() } }
            val row = loaded.getOrNull()
            val decoded = row?.let { runCatching { json.decodeFromString<Workspace>(it.payload) } }
            storageWritable = loaded.isSuccess && (decoded == null || decoded.isSuccess)
            val workspace = (decoded?.getOrNull() ?: Workspace(anidbSelection = emptyList())).recoverSelection()
            val recovered = workspace.copy(page = if (workspace.page == "official") "add" else workspace.page,
                draft = DraftSync.normalizeReleaseDates(if (workspace.draft.submission == "Submitting to AniDB") workspace.draft.copy(submission = "Outcome unknown; check AniDB before retrying") else workspace.draft),
                selected = workspace.selected.map { file ->
                    if (file.status in activeDumpStates) file.copy(status = "Outcome unknown", message = "Reconnect to check this dump. It will not be repeated automatically.") else file
                })
            mutable.update { it.copy(workspace = recovered, ready = true) }
            if (!storageWritable) notice("The saved draft could not be read. It has been left untouched. New edits stay in memory; Start new batch resets the saved workspace.")
            val token = runCatching { credentials.apiKey() }.getOrElse { notice("The saved server sign-in could not be read. Sign in again."); null }
            if (token != null && credentials.serverUrl.isNotBlank()) connect(credentials.serverUrl, token)
        }
        viewModelScope.launch {
            dao.templates().catch { notice("Saved templates could not be read. Your current draft remains available."); emit(emptyList()) }
                .collect { templates -> mutable.update { it.copy(templates = templates) } }
        }
        viewModelScope.launch { aniDb.snapshot.collect { page -> if (state.value.ready) acceptSnapshot(page) } }
        viewModelScope.launch {
            animeSearch.state.collect { search ->
                mutable.update { it.copy(animeResults = search.results, animeSearching = search.loading,
                    animeSearchCompleted = search.completed, animeSearchError = search.error?.message) }
                if (search.error is ShokoException && search.error.statusCode == 401) disconnect(clearSaved = false)
            }
        }
    }

    private fun persist() {
        if (!storageWritable) return
        viewModelScope.launch {
            saveMutex.withLock {
                try {
                    dao.save(WorkspaceEntity(payload = json.encodeToString(state.value.workspace)))
                    storageErrorReported = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (!storageErrorReported) notice("Your latest edits could not be saved to the phone. Keep the app open and check available storage.")
                    storageErrorReported = true
                }
            }
        }
    }
    private fun edit(block: (Workspace) -> Workspace) {
        val next = block(state.value.workspace)
        if (next == state.value.workspace) return
        mutable.update { it.copy(workspace = next) }
        if (state.value.ready) persist()
    }
    private fun history(message: String) = edit { it.copy(history = (listOf(HistoryEntry(System.currentTimeMillis(), message)) + it.history).take(200)) }
    fun navigate(page: String) {
        if (page in setOf("add", "official") && !ensureDumpReady()) return
        edit { it.copy(page = page) }
        if (page == "add") {
            prepareAnimeSearch()
            val draft = state.value.workspace.draft
            if (draft.animeId > 0 && aniDb.snapshot.value.animeId != draft.animeId &&
                !DraftSync.belongsToAnime(aniDb.snapshot.value, draft.animeId)) {
                manualOfficial = false
                restoreSavedPage = draft.initialized
                restoreSavedReview = draft.review.isNotEmpty()
                aniDb.loadAnime(draft.animeId)
            }
        }
        if (page == "files" && state.value.connected) refreshFiles(state.value.pageNumber)
        if (page == "dump" && state.value.connected) refreshDump()
    }
    fun dismissNotice() { mutable.update { it.copy(notice = null) } }
    fun notice(message: String) { mutable.update { it.copy(notice = message) } }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        actionJob = viewModelScope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(error.message ?: "The request could not be completed.")
                if (error is ShokoException && error.statusCode == 401) disconnect(clearSaved = false)
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun connectedRepository(): ShokoRepository =
        repository?.takeIf { state.value.connected } ?: error("Connect to Shoko Server first.")

    fun login(url: String, username: String, password: String) = action {
        val base = ShokoRepository.normalizeServerUrl(url.trim()).toString().trimEnd('/')
        val token = ShokoRepository.login(base, username, password, "Shoko Remote Android")
        withContext(Dispatchers.IO) { credentials.save(base, username, token) }
        mutable.update { it.copy(serverUrl = base, username = username) }
        if (state.value.workspace.serverUrl.isNotBlank() && state.value.workspace.serverUrl != base) {
            batchEpoch++
            edit { Workspace(serverUrl = base, history = it.history) }
        } else edit { it.copy(serverUrl = base) }
        if (connect(base, token)) history("Connected to Shoko Server.")
    }
    private suspend fun connect(url: String, key: String): Boolean {
        val normalizedUrl = ShokoRepository.normalizeServerUrl(url).toString().trimEnd('/')
        if (state.value.workspace.serverUrl != normalizedUrl) {
            batchEpoch++
            dumpRevisions.clear()
            pendingTemplate = null
            restoreDefaultTemplate = false
            restoreSavedPage = false
            restoreSavedReview = false
            edit { it.boundToServer(normalizedUrl) }
        }
        disconnect(clearSaved = false)
        val epoch = connectionEpoch
        val nextRepository = ShokoRepository(normalizedUrl, key)
        repository = nextRepository
        try {
            nextRepository.version()
            val files = nextRepository.unrecognized(state.value.query, 1, 100)
            if (epoch != connectionEpoch || repository !== nextRepository) return false
            mutable.update { it.copy(connected = true, files = files.list, total = files.total, pageNumber = 1) }
            val nextMonitor = AVDumpMonitor(normalizedUrl, key)
            monitor = nextMonitor
            monitorEvents = viewModelScope.launch { nextMonitor.events.collect { event -> if (monitor === nextMonitor) acceptDumpEvent(event) } }
            monitorState = viewModelScope.launch { nextMonitor.connected.collect { live -> if (monitor === nextMonitor) mutable.update { it.copy(live = live) } } }
            if (foreground) startPolling()
            reconcile()
            prepareAnimeSearch()
            val draft = state.value.workspace.draft
            if (draft.animeId > 0 && state.value.workspace.page == "add") {
                restoreSavedPage = draft.initialized
                restoreSavedReview = draft.review.isNotEmpty()
                aniDb.loadAnime(draft.animeId)
            }
            return true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (repository === nextRepository) {
                disconnect(clearSaved = false)
                notice(error.message ?: "Saved connection is unavailable. Check the address and network.")
            }
            return false
        }
    }
    private fun disconnect(clearSaved: Boolean) {
        connectionEpoch++
        invalidateEditor()
        filesRequest++; filesJob?.cancel(); filesJob = null
        animeSearch.reset()
        val oldMonitor = monitor
        monitor = null; repository = null
        poll?.cancel(); poll = null
        monitorEvents?.cancel(); monitorEvents = null
        monitorState?.cancel(); monitorState = null
        if (oldMonitor != null) viewModelScope.launch { runCatching { oldMonitor.stop() } }
        if (clearSaved) credentials.clear()
        mutable.update { it.copy(connected = false, files = emptyList(), live = false, filesLoading = false) }
    }
    fun logout() {
        actionJob?.cancel(); actionJob = null
        disconnect(clearSaved = true)
        mutable.update { it.copy(busy = false) }
    }
    fun onForeground(active: Boolean) {
        foreground = active
        aniDb.setForeground(active)
        poll?.cancel(); poll = null
        if (!active) {
            val current = monitor
            viewModelScope.launch { runCatching { current?.stop() } }
        } else {
            startPolling()
            if (state.value.connected && state.value.workspace.page == "files") refreshFiles(state.value.pageNumber)
        }
    }
    private fun startPolling() {
        poll?.cancel()
        val currentMonitor = monitor ?: return
        val currentRepository = repository ?: return
        if (!foreground || !state.value.connected) return
        poll = viewModelScope.launch {
            runCatching { currentMonitor.start() }
            while (isActive && repository === currentRepository && monitor === currentMonitor && foreground) {
                runCatching { reconcile() }.onFailure { if (it is ShokoException && it.statusCode == 401) disconnect(clearSaved = false) }
                delay(5_000)
            }
        }
    }
    fun searchFiles(query: String) { mutable.update { it.copy(query = query) }; refreshFiles() }
    fun refreshFiles(page: Int = 1) {
        val repo = repository?.takeIf { state.value.connected } ?: return
        val request = ++filesRequest
        val epoch = batchEpoch
        val query = state.value.query
        filesJob?.cancel()
        mutable.update { it.copy(filesLoading = true) }
        filesJob = viewModelScope.launch {
            try {
                var target = page.coerceAtLeast(1)
                var result = repo.unrecognized(query, target, 100)
                val last = ((result.total + 99) / 100).coerceAtLeast(1)
                if (target > last) { target = last; result = repo.unrecognized(query, target, 100) }
                if (repository === repo && request == filesRequest && epoch == batchEpoch && state.value.query == query)
                    mutable.update { it.copy(files = result.list, total = result.total, pageNumber = target) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (repository === repo && request == filesRequest) {
                    notice(error.message ?: "Files could not refresh.")
                    if (error is ShokoException && error.statusCode == 401) disconnect(clearSaved = false)
                }
            } finally { if (request == filesRequest) mutable.update { it.copy(filesLoading = false) } }
        }
    }
    fun toggleFile(file: ShokoFile) {
        if (state.value.workspace.draft.animeId > 0) { notice("A draft is open. Start a new batch to change the file selection."); return }
        if (file.id in state.value.workspace.pendingFileIds) { notice("This file is waiting for Shoko recognition."); return }
        batchEpoch++
        animeSearch.reset()
        edit { workspace -> workspace.copy(animeSearchQuery = null, anidbSelection = workspace.anidbFiles.map { it.id }.filterNot { it == file.id },
            selected = if (workspace.selected.any { it.id == file.id }) workspace.selected.filterNot { it.id == file.id } else workspace.selected + selectedFile(file)) }
    }
    private fun selectedFile(file: ShokoFile) = SelectedFile(file.id, file.fileName, file.size, file.hashes.ed2k, file.avDump.lastDumpedAt,
        status = file.avDump.status ?: if (file.avDump.lastDumpedAt != null) "Dumped" else "Not dumped", progress = file.avDump.progress, recognized = file.recognized)
    fun selectVisible() { state.value.files.filterNot { file -> state.value.workspace.selected.any { it.id == file.id } }.forEach(::toggleFile) }
    fun newBatch() {
        batchEpoch++; dumpRevisions.clear(); pendingTemplate = null; restoreDefaultTemplate = false; restoreSavedPage = false; restoreSavedReview = false
        storageWritable = true
        animeSearch.reset()
        invalidateEditor()
        edit { it.startNewBatch() }
        mutable.update { it.copy(query = "", pageNumber = 1, filesReset = it.filesReset + 1) }
        refreshFiles()
        persist()
    }
    private fun invalidateEditor() {
        editorEpoch++; editorAction = false; manualOfficial = false
        pendingTemplate = null; restoreDefaultTemplate = false; restoreSavedPage = false; restoreSavedReview = false
        aniDb.reset()
    }
    fun toggleAniDbFile(id: Int) {
        val workspace = state.value.workspace
        if (workspace.draft.animeId > 0) { notice("Change selected files to discard the open draft first."); return }
        if (workspace.selected.none { it.id == id && !it.recognized } || id in workspace.pendingFileIds) return
        invalidateEditor()
        animeSearch.reset()
        val ids = workspace.anidbFiles.map { it.id }
        edit { it.copy(anidbSelection = if (id in ids) ids - id else ids + id, animeSearchQuery = null, draft = Draft()) }
    }
    fun changeSelectedFiles() {
        invalidateEditor(); animeSearch.reset()
        edit { it.clearGroup().copy(page = "dump") }
        refreshDump()
    }
    private fun refreshDump() {
        viewModelScope.launch {
            try { reconcile() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                notice(error.message ?: "Status could not refresh.")
                if (error is ShokoException && error.statusCode == 401) disconnect(clearSaved = false)
            }
        }
    }
    fun startDump() = action {
        val repo = connectedRepository()
        val files = state.value.workspace.selected.filter { it.status in setOf("Not dumped", "Failed") && !it.recognized }
        require(files.isNotEmpty()) { "Select files that still need AVDump. Unknown outcomes must be checked before retrying." }
        require(files.all { it.hash.matches(Regex("[a-fA-F0-9]{32}")) }) { "Some selected files do not have a complete ED2K hash." }
        runCatching { monitor?.start() }
        val ids = files.map { it.id }.toSet()
        val epoch = batchEpoch
        edit { it.copy(page = "dump", selected = it.selected.map { file -> if (file.id in ids) file.copy(status = "Submitting dump", message = "", progress = null) else file }) }
        try {
            repo.dump(ids)
            if (repository !== repo || epoch != batchEpoch) return@action
            edit { it.copy(selected = it.selected.map { file -> if (file.id in ids && file.status == "Submitting dump") file.copy(status = "Queued") else file }) }
            history("Requested AVDump for ${ids.size} file(s).")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (repository === repo && epoch == batchEpoch) {
                val unknown = error !is ShokoException || error.outcomeUnknown
                edit { it.copy(selected = it.selected.map { file -> if (file.id in ids && file.status !in setOf("Dumped", "Failed")) file.copy(status = if (unknown) "Outcome unknown" else "Failed", message = error.message.orEmpty()) else file }) }
            }
            throw error
        }
    }
    fun allowDumpRetry(id: Int) {
        val file = state.value.workspace.selected.find { it.id == id } ?: return
        if (file.status !in setOf("Outcome unknown", "Failed")) return
        dumpRevisions[id] = (dumpRevisions[id] ?: 0L) + 1L
        edit { it.copy(selected = it.selected.map { selected -> if (selected.id == id) selected.copy(status = "Not dumped", message = "Explicit retry requested after checking server status.") else selected }) }
    }
    private fun acceptDumpEvent(event: AVDumpEvent) {
        val affected = event.videoIds.toSet().intersect(state.value.workspace.selected.map { it.id }.toSet())
        if (affected.isEmpty()) return
        affected.forEach { id -> dumpRevisions[id] = (dumpRevisions[id] ?: 0L) + 1L }
        val status = when (event.type) {
            "Restore" -> if (event.progress != null || event.startedAt?.let { !it.startsWith("0001") } == true) "Running" else "Queued"
            "Started", "Progress", "CreqUpdate", "ED2KLink" -> "Running"
            "Success" -> "Dumped"
            "Failure", "Error", "MissingApiKey", "InvalidCredentials", "Timeout", "GenericException", "InstallException" -> "Failed"
            else -> null
        }
        edit { it.copy(selected = it.selected.map { file -> if (file.id in affected) file.copy(status = status ?: file.status,
            progress = if (status == "Dumped") 100.0 else event.progress ?: file.progress,
            message = event.errorMessage ?: event.message ?: file.message) else file }) }
        if (status in setOf("Dumped", "Failed")) history("AVDump ${if (status == "Dumped") "completed" else "reported a problem"} for ${affected.size} file(s).")
        prepareAnimeSearch()
    }
    fun recheckDumpStatus() = action { connectedRepository(); reconcile() }
    private suspend fun reconcile() = reconcileMutex.withLock {
        val repo = repository ?: return@withLock
        val epoch = batchEpoch
        val selected = state.value.workspace.selected
        if (selected.isEmpty()) return@withLock
        val revisions = selected.associate { it.id to (dumpRevisions[it.id] ?: 0L) }
        val updates = mutableMapOf<Int, ShokoFile>()
        for (file in selected) {
            try { updates[file.id] = repo.file(file.id) }
            catch (error: Exception) {
                if (error is CancellationException || error is ShokoException && error.statusCode == 401) throw error
            }
        }
        if (repository !== repo || epoch != batchEpoch) return@withLock
        edit { it.copy(selected = it.selected.map { file ->
            val fresh = updates[file.id] ?: return@map file
            if ((dumpRevisions[file.id] ?: 0L) != revisions[file.id]) return@map file.copy(recognized = file.recognized || fresh.recognized)
            val succeeded = fresh.avDump.lastDumpedAt != null && (file.baseline == null || fresh.avDump.lastDumpedAt != file.baseline || file.status == "Dumped")
            val status = fresh.avDump.status ?: when {
                succeeded -> "Dumped"
                file.status in activeDumpStates -> "Outcome unknown"
                else -> file.status
            }
            file.copy(status = status, progress = if (status == "Dumped") 100.0 else fresh.avDump.progress ?: file.progress,
                recognized = file.recognized || fresh.recognized)
        }) }
        edit { it.removeCompletedGroups() }
        if (updates.size == selected.size && state.value.workspace.poolRecognized) {
            history("Shoko recognized all files in the pool. Started a new batch."); newBatch()
        } else {
            val group = state.value.workspace.anidbFiles
            if (group.isNotEmpty() && group.all { it.recognized && updates.containsKey(it.id) }) {
                invalidateEditor(); animeSearch.reset()
                edit { it.clearGroup().copy(page = "dump") }
            }
            prepareAnimeSearch()
        }
    }
    private fun prepareAnimeSearch() {
        val workspace = state.value.workspace
        if (!state.value.connected || workspace.page != "add" || workspace.draft.animeId > 0 ||
            !workspace.subsetReady) return
        edit { it.prepareAnimeSearch() }
        animeSearch.submit(state.value.workspace.animeSearchQuery.orEmpty())
    }
    fun setAnimeQuery(query: String) {
        edit { it.copy(animeSearchQuery = query) }
        animeSearch.submit(query)
    }
    fun searchAnime(query: String) {
        if (query.isBlank()) { notice("Enter an anime title or AniDB ID."); return }
        edit { it.copy(animeSearchQuery = query) }
        animeSearch.submit(query, force = true, immediate = true)
    }
    private fun ensureDumpReady(): Boolean {
        if (!state.value.workspace.subsetReady) {
            notice("Finish AVDump for the selected files before adding them to AniDB.")
            edit { it.copy(page = "dump") }
            return false
        }
        return true
    }
    fun chooseAnime(id: Int, title: String) {
        if (id <= 0) { notice("Enter a positive AniDB anime ID."); return }
        if (!ensureDumpReady()) return
        animeSearch.reset()
        val links = state.value.workspace.draft.values["addfilem_ed2k"].orEmpty().ifBlank { state.value.workspace.anidbFiles.joinToString("\n") { it.ed2k } }
        invalidateEditor()
        pendingTemplate = null; restoreSavedPage = false; restoreSavedReview = false
        edit { it.copy(page = "add", draft = Draft(animeId = id, animeTitle = title, values = mapOf("addfilem_ed2k" to links))) }
        restoreDefaultTemplate = true
        aniDb.loadAnime(id)
    }
    fun changeAnime() {
        if (!ensureDumpReady()) return
        val links = state.value.workspace.draft.values["addfilem_ed2k"].orEmpty().ifBlank { state.value.workspace.anidbFiles.joinToString("\n") { it.ed2k } }
        invalidateEditor()
        pendingTemplate = null; restoreDefaultTemplate = false; restoreSavedPage = false; restoreSavedReview = false
        edit { it.copy(page = "add", draft = Draft(values = mapOf("addfilem_ed2k" to links))) }
        prepareAnimeSearch()
    }
    fun openAniDb() {
        if (!ensureDumpReady()) return
        val draft = state.value.workspace.draft
        if (draft.animeId <= 0) { navigate("add"); return }
        manualOfficial = true
        navigate("official")
        if (!DraftSync.belongsToAnime(aniDb.snapshot.value, draft.animeId)) {
            restoreSavedPage = draft.initialized
            restoreSavedReview = draft.review.isNotEmpty()
            aniDb.loadAnime(draft.animeId)
        } else aniDb.refresh()
    }
    fun readOfficialPage() {
        manualOfficial = false
        aniDb.refresh { page ->
            if (page.status in setOf("form", "review")) acceptSnapshot(page)
            else notice(page.message)
        }
        navigate("add")
    }
    fun snapshot(): AniDbFormSnapshot? = DraftSync.decodeSchema(state.value.workspace.draft)
    private fun acceptSnapshot(page: AniDbFormSnapshot) {
        val old = state.value.workspace.draft
        if (old.animeId <= 0) return
        val editing = state.value.workspace.page in setOf("add", "official")
        val associated = DraftSync.belongsToAnime(page, old.animeId) || page.animeId == old.animeId
        if (!associated) return
        if (page.status in setOf("success", "error", "unknown")) {
            val submission = DraftSync.submissionFeedback(old, page, aniDb.actionBusy.value)
            edit { it.copy(draft = it.draft.copy(submission = submission), page = if (editing && !manualOfficial) "add" else it.page) }
            return
        }
        if (page.status in setOf("login", "challenge", "unsupported")) {
            if (editing) edit { it.copy(page = "official") }
            return
        }
        if (page.status !in setOf("form", "review")) return
        if (!DraftSync.belongsToAnime(page, old.animeId)) { notice("This AniDB form belongs to another anime. Choose that anime in the app before editing it."); return }
        val templateValue = page.fields.find { it.id == "addfilem_template" }?.value
        val adoptTemplate = pendingTemplate != null && pendingTemplate == templateValue
        if (adoptTemplate) pendingTemplate = null
        val restore = restoreSavedPage || page.status == "review" && restoreSavedReview
        val next = DraftSync.merge(old, page, preserveSaved = restore, adoptTemplate = adoptTemplate)
        edit { it.copy(draft = next.copy(submission = DraftSync.submissionFeedback(old, page, aniDb.actionBusy.value)),
            page = if (editing && !manualOfficial) "add" else it.page) }
        val populateRestored = restoreSavedPage && page.status == "form"
        restoreSavedPage = false
        if (page.status == "review") restoreSavedReview = false
        if (restoreDefaultTemplate) {
            restoreDefaultTemplate = false
            val default = state.value.templates.firstOrNull { it.isDefault }
            if (default != null) applySavedTemplate(default)
            else if (!old.initialized) aniDb.apply(edits())
        } else if (populateRestored) aniDb.apply(edits())
    }
    fun setField(id: String, value: String) = edit {
        val changedLinks = id == "addfilem_ed2k" && it.draft.values[id] != value
        it.copy(draft = it.draft.copy(values = it.draft.values + (id to value), dirty = true,
            review = if (changedLinks) emptyList() else it.draft.review,
            reviewIdentity = if (changedLinks) emptyList() else it.draft.reviewIdentity,
            reviewBaseline = if (changedLinks) emptyList() else it.draft.reviewBaseline))
    }
    fun setCheck(id: String, value: Boolean) = edit { it.copy(draft = it.draft.copy(checks = it.draft.checks + (id to value), dirty = true)) }
    private fun edits(): AniDbEdits = DraftSync.transfer(state.value.workspace.draft, aniDb.snapshot.value)
    private fun requireLiveForm(): Boolean {
        val live = aniDb.snapshot.value
        val valid = live.status in setOf("form", "review") && DraftSync.belongsToAnime(live, state.value.workspace.draft.animeId)
        if (!valid) notice("Open AniDB and sign in to restore this draft before transferring changes.")
        return valid
    }
    fun groupSearch(query: String) {
        if (editorAction || aniDb.actionBusy.value || !requireLiveForm()) return
        val current = editorGuard()
        aniDb.apply(edits()) { ok -> if (current() && ok) aniDb.searchGroups(query) }
    }
    fun selectBuiltinTemplate(value: String) {
        if (editorAction || aniDb.actionBusy.value || !requireLiveForm()) return
        val current = editorGuard()
        aniDb.apply(edits()) { ok -> if (current() && ok) { pendingTemplate = value; aniDb.selectTemplate(value) } }
    }
    fun changeTrackCount(audio: Boolean, count: Int) {
        if (editorAction || aniDb.actionBusy.value || !requireLiveForm()) return
        val old = state.value.workspace.draft
        val current = editorGuard()
        edit { it.copy(draft = DraftSync.withTrackCount(it.draft, audio, count)) }
        aniDb.apply(edits()) { ok -> if (current() && !ok) { edit { it.copy(draft = old) }; aniDb.refresh() } }
    }
    private fun editorGuard(): () -> Boolean {
        val epoch = editorEpoch
        val connection = connectionEpoch
        val anime = state.value.workspace.draft.animeId
        return { epoch == editorEpoch && connection == connectionEpoch && anime == state.value.workspace.draft.animeId }
    }
    fun continueOnAniDb() {
        if (editorAction || aniDb.actionBusy.value || !ensureDumpReady() || !requireLiveForm()) return
        if (aniDb.snapshot.value.status != "form") return
        runCatching { Ed2kValidator.parse(state.value.workspace.draft.values["addfilem_ed2k"].orEmpty()) }
            .onFailure { notice(it.message.orEmpty()); return }
        val current = editorGuard()
        editorAction = true
        aniDb.apply(edits()) { ok ->
            if (current()) {
                if (ok) aniDb.next { if (current()) editorAction = false }
                else editorAction = false
            }
        }
    }
    private fun transferReview(onComplete: (Boolean) -> Unit) {
        val current = editorGuard()
        val reviews = state.value.workspace.draft.review.map { AniDbReviewEdit(it.index, it.episode, it.version, AniDbReleaseDate.normalize(it.releaseDate)) }
        aniDb.apply(edits()) { ok -> if (current()) {
            if (ok && reviews.isNotEmpty()) aniDb.applyReview(reviews) { applied -> if (current()) onComplete(applied) }
            else onComplete(false)
        } }
    }
    fun addFiles() {
        if (editorAction || aniDb.actionBusy.value || !ensureDumpReady() || !requireLiveForm()) return
        if (aniDb.snapshot.value.status != "review") return
        val submission = state.value.workspace.draft.submission
        if (submission != "Not submitted" && !submission.startsWith("AniDB error:")) {
            notice("Check the previous submission on AniDB before trying again."); return
        }
        val current = editorGuard()
        editorAction = true
        transferReview { applied ->
            if (!applied) { editorAction = false; return@transferReview }
            // Persist the intent before invoking AniDB, so process death cannot enable replay.
            edit { it.copy(draft = it.draft.copy(submission = "Submitting to AniDB")) }
            viewModelScope.launch {
                saveMutex.withLock {
                    if (!current()) return@withLock
                    try {
                        check(storageWritable) { "Save the workspace before submitting files." }
                        dao.save(WorkspaceEntity(payload = json.encodeToString(state.value.workspace)))
                        if (!current()) return@withLock
                        aniDb.submitFiles { activated ->
                            if (current()) {
                                editorAction = false
                                if (!activated && state.value.workspace.draft.submission == "Submitting to AniDB")
                                    edit { it.copy(draft = it.draft.copy(submission = "AniDB error: ${aniDb.pageError.value ?: "AniDB could not activate Add Files. Check its form."}")) }
                            }
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (current()) {
                            editorAction = false
                            edit { it.copy(draft = it.draft.copy(submission = "Not submitted")) }
                            notice(error.message ?: "The submission intent could not be saved.")
                        }
                    }
                }
            }
        }
    }
    fun reviewOnAniDb() {
        if (editorAction || aniDb.actionBusy.value || !requireLiveForm()) return
        editorAction = true
        transferReview { ok ->
            editorAction = false
            if (ok) { manualOfficial = true; edit { it.copy(page = "official") } }
        }
    }
    fun setReview(index: Int, episode: String? = null, version: String? = null, date: String? = null) = edit {
        it.copy(draft = it.draft.copy(dirty = true, review = it.draft.review.map { row -> if (row.index == index) row.copy(episode = episode ?: row.episode,
            version = version ?: row.version, releaseDate = date ?: row.releaseDate) else row }))
    }
    fun applyDate(date: String) = edit { it.copy(draft = it.draft.copy(dirty = true, review = it.draft.review.map { row -> row.copy(releaseDate = date) })) }
    private fun storageAction(block: suspend () -> Unit) {
        viewModelScope.launch { try { block() } catch (error: Exception) { if (error is CancellationException) throw error; notice("The template change could not be saved. ${error.message.orEmpty()}") } }
    }
    fun saveTemplate(name: String, replaceId: String? = null) {
        if (name.isBlank()) return
        val draft = state.value.workspace.draft
        if (!draft.initialized) { notice("Open an AniDB form before saving a template."); return }
        val saved = DraftSync.template(draft)
        storageAction { dao.saveTemplate(TemplateEntity(replaceId ?: UUID.randomUUID().toString(), name.trim(), json.encodeToString(saved),
            isDefault = state.value.templates.find { it.id == replaceId }?.isDefault ?: false)) }
    }
    fun applySavedTemplate(template: TemplateEntity) {
        if (editorAction || aniDb.actionBusy.value || !requireLiveForm()) return
        val saved = runCatching { json.decodeFromString<Draft>(template.payload) }.getOrElse { notice("This template could not be read."); return }
        val manual = aniDb.snapshot.value.fields.find { it.id == "addfilem_template" }?.options
            ?.find { it.label.equals("manual", ignoreCase = true) && !it.disabled }?.value
        if (manual == null) { notice("The current AniDB form has no manual template option. Use its official page."); return }
        val groupValues = state.value.workspace.draft.values.filterKeys { it in setOf("addfilem_ed2k", "addfilem_group", "addfilem_group_input") }
        edit { it.copy(draft = it.draft.copy(values = groupValues + saved.values + ("addfilem_template" to manual), checks = saved.checks,
            audioTracks = saved.audioTracks, subtitleTracks = saved.subtitleTracks, dirty = true)) }
        aniDb.apply(edits())
    }
    fun templateDefault(id: String) = storageAction { dao.setDefault(id) }
    fun deleteTemplate(id: String) = storageAction { dao.deleteTemplate(id) }
    fun resetTemplates() = storageAction { dao.resetTemplates() }
    fun submittedAndRecheck() = action {
        val repo = connectedRepository()
        if (editorAction || aniDb.actionBusy.value) return@action
        val links = Ed2kValidator.parse(state.value.workspace.draft.values["addfilem_ed2k"].orEmpty())
        val known = state.value.workspace.anidbFiles.filter { file -> links.any { it.size == file.size && it.hash.equals(file.hash, ignoreCase = true) } }
        require(known.isNotEmpty()) { "None of these links correspond to selected Shoko files. Their recognition cannot be checked here." }
        invalidateEditor(); animeSearch.reset()
        val groupId = UUID.randomUUID().toString()
        edit { it.reportSubmitted(groupId, known.map { file -> file.id }) }
        history("User reported AniDB submission. Requested Shoko recognition checks.")
        queueRecognition(repo, known.filterNot { it.recognized }.map { it.id }, groupId)
    }
    fun recheckPendingGroup(id: String) = action {
        val group = state.value.workspace.pendingGroups.find { it.id == id } ?: return@action
        val ids = group.fileIds.filter { fileId -> state.value.workspace.selected.any { it.id == fileId && !it.recognized } }
        queueRecognition(connectedRepository(), ids, group.id)
    }
    private suspend fun queueRecognition(repo: ShokoRepository, ids: List<Int>, groupId: String) {
        fun current() = repository === repo && state.value.workspace.pendingGroups.any { it.id == groupId }
        var failure: String? = null
        try {
            for (id in ids) {
                if (!current()) return
                try { repo.rescan(id) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failure = error.message ?: "A recognition check could not be queued."
                    if (error is ShokoException && error.statusCode == 401) { disconnect(clearSaved = false); return }
                }
            }
        } finally {
            if (current()) reconcile()
        }
        if (current())
            notice(failure ?: "Rechecks queued. AniDB may take time to make the new records available.")
    }
    override fun onCleared() {
        aniDb.dispose()
        val oldMonitor = monitor
        monitor = null; repository = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { oldMonitor?.stop() } }
        super.onCleared()
    }
    companion object { private val activeDumpStates = setOf("Queued", "Running", "Submitting dump") }
}
