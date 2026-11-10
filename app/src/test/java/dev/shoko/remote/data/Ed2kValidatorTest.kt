package dev.shoko.remote.data

import org.junit.Assert.*
import org.junit.Test

class Ed2kValidatorTest {
    private val hash = "B21C6E5ACCE4605C11F0ABB654ADDCF2"
    @Test fun acceptsLargeFilesAndUnicode() {
        val result = Ed2kValidator.parse("ed2k://|file|日本語 - 01.mkv|7805529997|$hash|/").single()
        assertEquals(7805529997L, result.size)
        assertEquals("日本語 - 01.mkv", result.name)
    }
    @Test fun rejectsDuplicatesRegardlessOfNameOrHashCase() {
        assertThrows(IllegalArgumentException::class.java) {
            Ed2kValidator.parse("ed2k://|file|one.mkv|2|$hash|/\ned2k://|file|two.mkv|2|${hash.lowercase()}|/")
        }
    }
    @Test fun rejectsMalformedEmptyAndOverflowLinks() {
        listOf("", "ed2k://|file|bad|3|abcd|/", "ed2k://|file|bad|0|$hash|/", "ed2k://|file|bad|999999999999999999999|$hash|/").forEach {
            assertThrows(IllegalArgumentException::class.java) { Ed2kValidator.parse(it) }
        }
    }
}
