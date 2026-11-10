package dev.shoko.remote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeSearchTitleTest {
    @Test fun stripsReleaseGroupEpisodeVersionAndTechnicalSuffix() {
        assertEquals("Sousou no Frieren", AnimeSearchTitle.fromFileName(
            "[SubsPlease] Sousou no Frieren - 01v2 (1080p) [DEADBEEF].mkv",
        ))
        assertEquals("Dan Da Dan", AnimeSearchTitle.fromFileName(
            "D:\\Anime\\[SubsPlease] Dan Da Dan - 13 (1080p) [4D01F204].mkv",
        ))
        assertEquals("Show Title", AnimeSearchTitle.fromFileName("[Group][Show Title][01][1080p].mp4"))
        assertEquals("Show Title", AnimeSearchTitle.fromFileName("Show_Title_01_[BD_1080p].mkv"))
    }

    @Test fun preservesNumbersAndPunctuationInTitles() {
        mapOf(
            "[Erai-raws] 86 - Eighty Six - 01 [1080p][Multiple Subtitle].mkv" to "86 - Eighty Six",
            "[Group] 86 - 01 [1080p].mkv" to "86",
            "[Group][86][01][1080p].mkv" to "86",
            "[Group] 3-gatsu no Lion - 01 [1080p].mkv" to "3-gatsu no Lion",
            "[Group] 2.5-jigen no Ririsa - 01 [1080p].mkv" to "2.5-jigen no Ririsa",
            "[Group] Re:ZERO - Starting Life in Another World - 01.mkv" to "Re:ZERO - Starting Life in Another World",
            "[Group] ぼっち・ざ・ろっく！ - 01 (1080p).mkv" to "ぼっち・ざ・ろっく！",
            "86.mkv" to "86",
        ).forEach { (filename, expected) -> assertEquals(filename, expected, AnimeSearchTitle.fromFileName(filename)) }
    }

    @Test fun keepsMovieNamesYearsAndMeaningfulSeasonInformation() {
        mapOf(
            "[Group] Suzume no Tojimari (2022) [Dual Audio] [1080p].mkv" to "Suzume no Tojimari (2022)",
            "[Group] Movie - A Meaningful Subtitle (Dub).mkv" to "Movie - A Meaningful Subtitle",
            "[Group] Dragon Ball Z Movie 12 [BD 1080p].mkv" to "Dragon Ball Z Movie 12",
            "Suzume.no.Tojimari.2022.1080p.BluRay.x265-GROUP.mkv" to "Suzume no Tojimari (2022)",
            "Attack.on.Titan.S02E01.1080p.WEB-DL.AAC2.0.H.264-GROUP.mkv" to "Attack on Titan S2",
            "[Group] Show Title II - 01 [1080p].mkv" to "Show Title S2",
            "[Group] Show Title S1 - 01 [1080p].mkv" to "Show Title",
            "[Group] Show Title (2020) - 01 [1080p].mkv" to "Show Title (2020)",
        ).forEach { (filename, expected) -> assertEquals(filename, expected, AnimeSearchTitle.fromFileName(filename)) }
    }

    @Test fun ignoresEmptyMetadataOnlyHashAndTimestampNames() {
        listOf("", "   ", ".mkv", "[Group] [1080p] [DEADBEEF].mkv", "1080p.mkv", "DEADBEEF.mkv",
            "2026-11-10 12-34-56.mkv", "---.mkv", "Episode 01.mkv").forEach { filename ->
            assertNull(filename, AnimeSearchTitle.fromFileName(filename))
        }
        assertNull(AnimeSearchTitle.fromFileNames(emptyList()))
        assertNull(AnimeSearchTitle.fromFileNames(listOf("", "1080p.mkv")))
    }

    @Test fun choosesCommonSeriesAcrossGroupsAndIgnoresUnparseableNames() {
        assertEquals("Sousou no Frieren", AnimeSearchTitle.fromFileNames(listOf(
            "[SubsPlease] Sousou no Frieren - 01 [1080p].mkv",
            "[Erai-raws] Sousou no Frieren - 02 [720p].mkv",
            "[Other] Different Show - 01.mkv",
            "DEADBEEF.mkv",
        )))
        assertEquals("Movie - Part", AnimeSearchTitle.fromFileNames(listOf("Movie - Part One.mkv", "Movie - Part Two.mkv")))
        assertEquals("Bocchan", AnimeSearchTitle.fromFileNames(listOf("Bocchi.mkv", "Bocchan.mkv")))
    }
}
