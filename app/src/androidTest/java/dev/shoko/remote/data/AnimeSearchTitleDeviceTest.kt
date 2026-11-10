package dev.shoko.remote.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's ICU regex implementation using only in-memory filenames and JSON. */
@RunWith(AndroidJUnit4::class)
class AnimeSearchTitleDeviceTest {
    @Test
    fun parsesSquareCurlyAndParenthesizedReleaseGroups() {
        listOf("[Group]", "{Group}", "(Group)").forEach { group ->
            val filename = "$group Sousou no Frieren - 01 (1080p) [DEADBEEF].mkv"
            assertEquals(filename, "Sousou no Frieren", AnimeSearchTitle.fromFileName(filename))
        }
    }

    @Test
    fun stripsSquareCurlyAndParenthesizedDubSuffixes() {
        listOf("[Dub]", "{Dub}", "(Dub)").forEach { suffix ->
            val filename = "[Group] Movie - A Meaningful Subtitle $suffix.mkv"
            assertEquals(filename, "Movie - A Meaningful Subtitle", AnimeSearchTitle.fromFileName(filename))
        }
    }

    @Test
    fun preservesNumericTitlesAndSceneSeasonInformation() {
        assertEquals("86", AnimeSearchTitle.fromFileName("[Group][86][01][1080p].mkv"))
        assertEquals("86 - Eighty Six", AnimeSearchTitle.fromFileName(
            "[Group] 86 - Eighty Six - 01 [1080p].mkv",
        ))
        assertEquals("Attack on Titan S2", AnimeSearchTitle.fromFileName(
            "Attack.on.Titan.S02E01.1080p.WEB-DL.AAC2.0.H.264-GROUP.mkv",
        ))
    }

    @Test
    fun preparesJsonRestoredReadyBatchWithoutChangingSavedDraft() {
        val restored = Json { ignoreUnknownKeys = true }.decodeFromString<Workspace>(
            """
            {
              "serverUrl": "https://shoko.invalid",
              "page": "add",
              "selected": [
                {
                  "id": 42,
                  "name": "[Group] Sousou no Frieren - 01 (1080p) [DEADBEEF].mkv",
                  "size": 1000,
                  "hash": "0123456789ABCDEF0123456789ABCDEF",
                  "status": "Dumped",
                  "baseline": "2026-11-08T12:00:00Z"
                },
                {
                  "id": 43,
                  "name": "{Other Group} Sousou no Frieren - 02 [720p].mkv",
                  "size": 2000,
                  "hash": "FEDCBA9876543210FEDCBA9876543210",
                  "status": "Dumped"
                }
              ],
              "draft": {
                "animeId": 0,
                "values": {"addfilem_ed2k": "saved draft text"},
                "dirty": true
              },
              "history": [{"time": 1, "message": "Saved batch"}]
            }
            """.trimIndent(),
        )
        assertNull(restored.animeSearchQuery)

        val prepared = restored.prepareAnimeSearch()

        assertEquals(restored.copy(animeSearchQuery = "Sousou no Frieren"), prepared)
        assertEquals(prepared, prepared.prepareAnimeSearch())
    }
}
