package dev.shoko.remote.anidb

import kotlinx.serialization.Serializable

/** Values and choices are read from the signed-in page, not an obsolete static catalog. */
@Serializable
data class AniDbOption(val value: String, val label: String, val disabled: Boolean = false)

@Serializable
data class AniDbField(
    val id: String,
    val kind: String,
    val label: String,
    val value: String = "",
    val checked: Boolean = false,
    val disabled: Boolean = false,
    val options: List<AniDbOption> = emptyList(),
)

@Serializable
data class AniDbReviewRow(
    val index: Int,
    val filename: String,
    val crc: String,
    val extension: String,
    val episode: AniDbField,
    val version: AniDbField,
    val releaseDate: AniDbField,
)

@Serializable
data class AniDbFormSnapshot(
    val status: String = "page",
    val adapterVersion: Int = 1,
    val url: String = "",
    val title: String = "",
    val fields: List<AniDbField> = emptyList(),
    val rows: List<AniDbReviewRow> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val message: String = "Sign in to AniDB, then open an anime's Mass Add page.",
    val audioTracks: Int = 0,
    val subtitleTracks: Int = 0,
)

@Serializable
data class AniDbEdits(
    val values: Map<String, String> = emptyMap(),
    val checks: Map<String, Boolean> = emptyMap(),
    val audioTracks: Int? = null,
    val subtitleTracks: Int? = null,
)

@Serializable
data class AniDbReviewEdit(
    val index: Int,
    val episode: String,
    val version: String,
    val releaseDate: String,
)

data class AniDbBrowserDialog(val message: String, val kind: String, val defaultValue: String = "")

@Serializable
internal data class AniDbAdapterResult(
    val ok: Boolean = false,
    val error: String = "",
    val snapshot: AniDbFormSnapshot? = null,
)
