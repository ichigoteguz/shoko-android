package dev.shoko.remote.shoko

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class FilePage(
    @SerialName("Total") val total: Int = 0,
    @SerialName("List") val list: List<ShokoFile> = emptyList(),
)

@Serializable
data class ShokoFile(
    @SerialName("ID") val id: Int,
    @SerialName("Size") val size: Long = 0,
    @SerialName("Hashes") val hashes: FileHashes = FileHashes(),
    @SerialName("Locations") val locations: List<FileLocation> = emptyList(),
    @SerialName("AVDump") val avDump: FileAVDump = FileAVDump(),
    @SerialName("AniDB") val aniDB: JsonObject? = null,
    @SerialName("SeriesIDs") val seriesIDs: JsonArray = JsonArray(emptyList()),
    @SerialName("IsIgnored") val isIgnored: Boolean = false,
) {
    val fileName: String
        get() = locations.firstOrNull()?.relativePath?.replace('\\', '/')?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() } ?: "File $id"

    val recognized: Boolean
        get() = (aniDB?.get("ID")?.jsonPrimitive?.intOrNull ?: 0) > 0

    val ed2kLink: String
        get() {
            if (size <= 0 || !hashes.ed2k.matches(Regex("[a-fA-F0-9]{32}"))) return ""
            val safeName = fileName.replace("|", "%7C").replace("\r", "%0D").replace("\n", "%0A")
            return "ed2k://|file|$safeName|$size|${hashes.ed2k}|/"
        }
}

@Serializable
data class FileHashes(
    @SerialName("ED2K") val ed2k: String = "",
    @SerialName("CRC32") val crc32: String? = null,
    @SerialName("MD5") val md5: String? = null,
    @SerialName("SHA1") val sha1: String? = null,
)

@Serializable
data class FileLocation(
    @SerialName("RelativePath") val relativePath: String = "",
)

@Serializable
data class FileAVDump(
    @SerialName("Status") val status: String? = null,
    /** Server percentage, in the range 0..100. */
    @SerialName("Progress") val progress: Double? = null,
    @SerialName("LastDumpedAt") val lastDumpedAt: String? = null,
    @SerialName("LastVersion") val lastVersion: String? = null,
    @SerialName("StartedAt") val startedAt: String? = null,
    @SerialName("SucceededCreqCount") val succeededCreqCount: Int? = null,
    @SerialName("FailedCreqCount") val failedCreqCount: Int? = null,
    @SerialName("PendingCreqCount") val pendingCreqCount: Int? = null,
)

@Serializable
data class AnimePage(
    @SerialName("Total") val total: Int = 0,
    @SerialName("List") val list: List<Anime> = emptyList(),
)

@Serializable
data class Anime(
    @SerialName("ID") val id: Int,
    @SerialName("Title") val title: String = "",
    @SerialName("ShokoID") val shokoId: Int? = null,
    @SerialName("EpisodeCount") val episodeCount: Int? = null,
)

@Serializable
data class AVDumpStatus(
    @SerialName("Installed") val installed: Boolean = false,
    @SerialName("InstalledVersion") val installedVersion: String? = null,
    @SerialName("ExpectedVersion") val expectedVersion: String? = null,
)

@Serializable
data class AVDumpEvent(
    @SerialName("Type") val type: String,
    @SerialName("SessionID") val sessionId: Int? = null,
    @SerialName("VideoIDs") val videoIds: List<Int> = emptyList(),
    /** Server percentage, in the range 0..100. */
    @SerialName("Progress") val progress: Double? = null,
    @SerialName("ed2ks") val ed2ks: List<String> = emptyList(),
    @SerialName("SucceededCreqCount") val succeededCreqCount: Int? = null,
    @SerialName("FailedCreqCount") val failedCreqCount: Int? = null,
    @SerialName("PendingCreqCount") val pendingCreqCount: Int? = null,
    @SerialName("Message") val message: String? = null,
    @SerialName("ErrorMessage") val errorMessage: String? = null,
    @SerialName("StartedAt") val startedAt: String? = null,
    @SerialName("EndedAt") val endedAt: String? = null,
) {
    val isTerminal: Boolean get() = type in terminalTypes
    val failed: Boolean get() = isTerminal && type != "Success"

    companion object {
        val terminalTypes = setOf(
            "Success", "Failure", "Error", "MissingApiKey", "InvalidCredentials", "Timeout",
            "GenericException", "InstallException",
        )
    }
}

class ShokoException(
    val statusCode: Int,
    message: String,
    val outcomeUnknown: Boolean = false,
) : Exception(message)
