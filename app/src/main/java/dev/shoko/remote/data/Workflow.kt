package dev.shoko.remote.data

import kotlinx.serialization.Serializable

@Serializable
data class SelectedFile(
    val id: Int,
    val name: String,
    val size: Long,
    val hash: String,
    val baseline: String? = null,
    val status: String = "Not dumped",
    val progress: Double? = null,
    val message: String = "",
    val recognized: Boolean = false,
) {
    val ed2k: String get() = "ed2k://|file|${name.replace("|", "%7C").replace("\r", "%0D").replace("\n", "%0A")}|$size|$hash|/"
}

@Serializable
data class Draft(
    val animeId: Int = 0,
    val animeTitle: String = "",
    val schema: String = "",
    val values: Map<String, String> = emptyMap(),
    val checks: Map<String, Boolean> = emptyMap(),
    val audioTracks: Int? = null,
    val subtitleTracks: Int? = null,
    val review: List<ReviewValue> = emptyList(),
    val reviewIdentity: List<String> = emptyList(),
    val reviewBaseline: List<ReviewValue> = emptyList(),
    val submission: String = "Not submitted",
    val dirty: Boolean = false,
    val initialized: Boolean = false,
)

@Serializable
data class ReviewValue(val index: Int, val episode: String, val version: String, val releaseDate: String)

@Serializable
data class HistoryEntry(val time: Long, val message: String)

@Serializable
data class PendingGroup(val id: String, val animeId: Int, val animeTitle: String, val fileIds: List<Int>)

@Serializable
data class Workspace(
    val serverUrl: String = "",
    val selected: List<SelectedFile> = emptyList(),
    val draft: Draft = Draft(),
    val history: List<HistoryEntry> = emptyList(),
    val page: String = "files",
    val animeSearchQuery: String? = null,
    // Null distinguishes pre-subset workspaces from a deliberately empty selection.
    val anidbSelection: List<Int>? = null,
    val pendingGroups: List<PendingGroup> = emptyList(),
) {
    val pendingFileIds: Set<Int> get() = pendingGroups.flatMap { it.fileIds }.toSet()
    val anidbFiles: List<SelectedFile> get() {
        val ids = anidbSelection ?: legacySubset()
        return selected.filter { it.id in ids && it.id !in pendingFileIds }
    }
    val subsetReady: Boolean get() = anidbFiles.isNotEmpty() && anidbFiles.all { it.recognized || it.status == "Dumped" }
    val poolRecognized: Boolean get() = selected.isNotEmpty() && selected.all { it.recognized }

    private fun legacySubset(): List<Int> {
        val links = draft.values["addfilem_ed2k"].orEmpty()
        if (links.isBlank()) return selected.map { it.id }
        val parsed = runCatching { Ed2kValidator.parse(links) }.getOrDefault(emptyList())
        return selected.filter { file -> parsed.any { it.size == file.size && it.hash.equals(file.hash, true) } }.map { it.id }
    }

    fun recoverSelection(): Workspace = copy(anidbSelection = anidbSelection ?: legacySubset())

    fun clearGroup(): Workspace = copy(draft = Draft(), anidbSelection = emptyList(), animeSearchQuery = null)

    fun startNewBatch(): Workspace = clearGroup().copy(selected = emptyList(), pendingGroups = emptyList(), page = "files")

    fun reportSubmitted(id: String, fileIds: List<Int>): Workspace = clearGroup().copy(page = "dump",
        pendingGroups = pendingGroups + PendingGroup(id, draft.animeId, draft.animeTitle, fileIds.distinct()))

    fun removeCompletedGroups(): Workspace = copy(pendingGroups = pendingGroups.filterNot { group ->
        group.fileIds.isNotEmpty() && group.fileIds.all { id -> selected.any { it.id == id && it.recognized } }
    })

    fun boundToServer(url: String): Workspace = if (serverUrl == url) this
        else Workspace(serverUrl = url, history = history)

    fun prepareAnimeSearch(
        suggestTitle: (List<String>) -> String? = { AnimeSearchTitle.fromFileNames(it) },
    ): Workspace {
        if (animeSearchQuery != null) return this
        // A filename suggestion is optional; even a parser initialization failure
        // must leave the saved batch usable with manual anime search.
        val suggestion = try {
            suggestTitle(anidbFiles.map { it.name })
        } catch (_: LinkageError) {
            null
        } catch (_: Exception) {
            null
        }
        return copy(animeSearchQuery = suggestion.orEmpty())
    }
}

data class Ed2kEntry(val name: String, val size: Long, val hash: String)

object Ed2kValidator {
    fun parse(text: String): List<Ed2kEntry> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.isNotEmpty()) { "Add at least one ED2K link." }
        val seen = mutableSetOf<String>()
        return lines.mapIndexed { index, line ->
            val match = Regex("^ed2k://\\|file\\|([^|]+)\\|([0-9]+)\\|([a-fA-F0-9]{32})\\|(?:[^|]*\\|)*/$").matchEntire(line)
            require(match != null) { "Line ${index + 1} is not a valid ED2K file link." }
            val size = match.groupValues[2].toLongOrNull()
            require(size != null && size > 0) { "Line ${index + 1} has an invalid file size." }
            val hash = match.groupValues[3].uppercase()
            require(seen.add("$size:$hash")) { "Line ${index + 1} duplicates an earlier file." }
            Ed2kEntry(match.groupValues[1], size, hash)
        }
    }
}
