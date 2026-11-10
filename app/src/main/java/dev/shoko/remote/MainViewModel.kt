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
    val notice: String? = null,
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
            val workspace = decoded?.getOrNull() ?: Workspace()
            val recovered = workspace.copy(page = if (workspace.page == "official") "add" else workspace.page,
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
        if (page == "add") prepareAnimeSearch()
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
        animeSearch.reset()
        val oldMonitor = monitor
        monitor = null; repository = null
        poll?.cancel(); poll = null
        monitorEvents?.cancel(); monitorEvents = null
        monitorState?.cancel(); monitorState = null
        if (oldMonitor != null) viewModelScope.launch { runCatching { oldMonitor.stop() } }
        if (clearSaved) credentials.clear()
        mutable.update { it.copy(connected = false, files = emptyList(), live = false) }
    }
    fun logout() {
        actionJob?.cancel(); actionJob = null
        disconnect(clearSaved = true)
        mutable.update { it.copy(busy = false) }
    }
    fun onForeground(active: Boolean) {
        foreground = active
        poll?.cancel(); poll = null
        if (!active) {
            val current = monitor
            viewModelScope.launch { runCatching { current?.stop() } }
        } else startPolling()
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
    fun refreshFiles(page: Int = 1) = action { loadFiles(page) }
    private suspend fun loadFiles(page: Int = 1) {
        val repo = connectedRepository()
        val query = state.value.query
        val result = repo.unrecognized(query, page, 100)
        if (repository === repo && state.value.query == query) mutable.update { it.copy(files = result.list, total = result.total, pageNumber = page) }
    }
    fun toggleFile(file: ShokoFile) {
        if (state.value.workspace.draft.animeId > 0) { notice("A draft is open. Start a new batch to change the file selection."); return }
        batchEpoch++
        animeSearch.reset()
        edit { workspace -> workspace.copy(animeSearchQuery = null,
            selected = if (workspace.selected.any { it.id == file.id }) workspace.selected.filterNot { it.id == file.id } else workspace.selected + selectedFile(file)) }
    }
    private fun selectedFile(file: ShokoFile) = SelectedFile(file.id, file.fileName, file.size, file.hashes.ed2k, file.avDump.lastDumpedAt,
        status = file.avDump.status ?: if (file.avDump.lastDumpedAt != null) "Dumped" else "Not dumped", progress = file.avDump.progress, recognized = file.recognized)
    fun selectVisible() { state.value.files.filterNot { file -> state.value.workspace.selected.any { it.id == file.id } }.forEach(::toggleFile) }
    fun newBatch() {
        batchEpoch++; dumpRevisions.clear(); pendingTemplate = null; restoreDefaultTemplate = false; restoreSavedPage = false; restoreSavedReview = false
        storageWritable = true
        animeSearch.reset()
        edit { it.copy(selected = emptyList(), draft = Draft(), page = "files", animeSearchQuery = null) }
        persist()
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
        prepareAnimeSearch()
    }
    private fun prepareAnimeSearch() {
        val workspace = state.value.workspace
        if (!state.value.connected || workspace.page != "add" || workspace.draft.animeId > 0 ||
            workspace.selected.any { !it.recognized && it.status != "Dumped" }) return
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
        if (state.value.workspace.selected.any { !it.recognized && it.status != "Dumped" }) {
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
        val links = state.value.workspace.draft.values["addfilem_ed2k"].orEmpty().ifBlank { state.value.workspace.selected.joinToString("\n") { it.ed2k } }
        pendingTemplate = null; restoreSavedPage = false; restoreSavedReview = false
        edit { it.copy(page = "official", draft = Draft(animeId = id, animeTitle = title, values = mapOf("addfilem_ed2k" to links))) }
        restoreDefaultTemplate = true
        aniDb.loadAnime(id)
    }
    fun changeAnime() {
        if (!ensureDumpReady()) return
        val links = state.value.workspace.draft.values["addfilem_ed2k"].orEmpty().ifBlank { state.value.workspace.selected.joinToString("\n") { it.ed2k } }
        pendingTemplate = null; restoreDefaultTemplate = false; restoreSavedPage = false; restoreSavedReview = false
        edit { it.copy(page = "add", draft = Draft(values = mapOf("addfilem_ed2k" to links))) }
        prepareAnimeSearch()
    }
    fun openAniDb() {
        if (!ensureDumpReady()) return
        val draft = state.value.workspace.draft
        if (draft.animeId <= 0) { navigate("add"); return }
        navigate("official")
        if (!DraftSync.belongsToAnime(aniDb.snapshot.value, draft.animeId)) {
            restoreSavedPage = draft.initialized
            restoreSavedReview = draft.review.isNotEmpty()
            aniDb.loadAnime(draft.animeId)
        } else aniDb.refresh()
    }
    fun readOfficialPage() {
        aniDb.refresh { page ->
            if (page.status in setOf("form", "review")) acceptSnapshot(page)
            else notice(page.message)
        }
        navigate("add")
    }
    fun snapshot(): AniDbFormSnapshot? = DraftSync.decodeSchema(state.value.workspace.draft)
    private fun acceptSnapshot(page: AniDbFormSnapshot) {
        val old = state.value.workspace.draft
        if (page.status !in setOf("form", "review") || old.animeId <= 0) return
        if (!DraftSync.belongsToAnime(page, old.animeId)) { notice("This AniDB form belongs to another anime. Choose that anime in the app before editing it."); return }
        val templateValue = page.fields.find { it.id == "addfilem_template" }?.value
        val adoptTemplate = pendingTemplate != null && pendingTemplate == templateValue
        if (adoptTemplate) pendingTemplate = null
        val restore = restoreSavedPage || page.status == "review" && restoreSavedReview
        val next = DraftSync.merge(old, page, preserveSaved = restore, adoptTemplate = adoptTemplate)
        edit { it.copy(draft = next) }
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
    fun groupSearch(query: String) { if (requireLiveForm()) aniDb.apply(edits()) { ok -> if (ok) aniDb.searchGroups(query) } }
    fun selectBuiltinTemplate(value: String) {
        if (!requireLiveForm()) return
        aniDb.apply(edits()) { ok -> if (ok) { pendingTemplate = value; aniDb.selectTemplate(value) } }
    }
    fun changeTrackCount(audio: Boolean, count: Int) {
        if (!requireLiveForm()) return
        val old = state.value.workspace.draft
        edit { it.copy(draft = DraftSync.withTrackCount(it.draft, audio, count)) }
        aniDb.apply(edits()) { ok -> if (!ok) { edit { it.copy(draft = old) }; aniDb.refresh() } }
    }
    fun continueOnAniDb() {
        if (!ensureDumpReady() || !requireLiveForm()) return
        runCatching { Ed2kValidator.parse(state.value.workspace.draft.values["addfilem_ed2k"].orEmpty()) }
            .onFailure { notice(it.message.orEmpty()); return }
        aniDb.apply(edits()) { ok -> if (ok) {
            val reviews = state.value.workspace.draft.review.map { AniDbReviewEdit(it.index, it.episode, it.version, it.releaseDate) }
            if (aniDb.snapshot.value.status == "review" && reviews.isNotEmpty()) {
                aniDb.applyReview(reviews) { applied -> if (applied) { edit { it.copy(draft = it.draft.copy(dirty = false)) }; navigate("official") } }
            } else { edit { it.copy(draft = it.draft.copy(dirty = false)) }; navigate("official") }
        } }
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
        if (!requireLiveForm()) return
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
        val links = Ed2kValidator.parse(state.value.workspace.draft.values["addfilem_ed2k"].orEmpty())
        val known = state.value.workspace.selected.filter { file -> links.any { it.size == file.size && it.hash.equals(file.hash, ignoreCase = true) } }
        require(known.isNotEmpty()) { "None of these links correspond to selected Shoko files. Their recognition cannot be checked here." }
        edit { it.copy(draft = it.draft.copy(submission = "Submitted by user; waiting for recognition"), page = "dump") }
        history("User reported AniDB submission. Requested Shoko recognition checks.")
        for (file in known.filterNot { it.recognized }) {
            if (repository !== repo) error("The server connection changed. Reconnect before checking recognition.")
            repo.rescan(file.id)
        }
        reconcile()
        notice("Rechecks queued. AniDB may take time to make the new records available.")
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
