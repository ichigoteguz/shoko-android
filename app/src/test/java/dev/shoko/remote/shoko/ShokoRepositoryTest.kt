package dev.shoko.remote.shoko

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShokoRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: ShokoRepository

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        repository = ShokoRepository(server.url("/").toString(), "private-key")
    }

    @After fun teardown() { server.shutdown() }

    @Test fun `login sends credentials as JSON body and returns API key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"apikey":"new-key"}"""))
        val key = ShokoRepository.login(server.url("/").toString(), "user", "pass\"word", "android-device")
        val request = server.takeRequest()
        assertEquals("new-key", key)
        assertEquals("/api/auth", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("apikey"))
        val body = ShokoRepository.json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals(JsonPrimitive("pass\"word"), body["pass"])
        assertEquals(JsonPrimitive("android-device"), body["device"])
    }

    @Test fun `unrecognized request decodes long sizes and carries pagination`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Total":101,"List":[{"ID":42,"Size":9000000001,"Hashes":{"ED2K":"0123456789ABCDEF0123456789ABCDEF"},"Locations":[{"RelativePath":"folder\\episode.mkv"}],"AVDump":{"Status":null,"LastDumpedAt":"2026-11-08T00:00:00Z"}}]}"""))
        val result = repository.unrecognized(page = 2)
        val request = server.takeRequest()
        assertEquals("private-key", request.getHeader("apikey"))
        assertEquals("Unrecognized", request.requestUrl?.queryParameter("include_only"))
        assertEquals("2", request.requestUrl?.queryParameter("page"))
        assertEquals(101, result.total)
        assertEquals(9000000001L, result.list.single().size)
        assertEquals("episode.mkv", result.list.single().fileName)
        assertTrue(result.list.single().ed2kLink.contains("|9000000001|"))
    }

    @Test fun `search encodes path query as one segment`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Total":0,"List":[]}"""))
        repository.unrecognized("a/b ?#")
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/api/v3/File/Search/a%2Fb%20%3F%23?"))
        assertEquals("Unrecognized", request.requestUrl?.queryParameter("include_only"))
    }

    @Test fun `anime search uses query endpoint not legacy path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Total":1,"List":[{"ID":19754,"Title":"Example","EpisodeCount":12}]}"""))
        val result = repository.animeSearch("19754")
        val request = server.takeRequest()
        assertEquals("/api/v3/Series/AniDB/Search", request.requestUrl?.encodedPath)
        assertEquals("19754", request.requestUrl?.queryParameter("query"))
        assertEquals(19754, result.list.single().id)
    }

    @Test fun `dump deduplicates IDs and accepts empty successful response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        repository.dump(listOf(3, 3, 9))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v3/AVDump/DumpFiles", request.path)
        val body = ShokoRepository.json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(3), JsonPrimitive(9))), body["FileIDs"])
        assertEquals(1, server.requestCount)
    }

    @Test fun `file details request metadata needed to prove recognition`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ID":3,"AniDB":{"ID":100},"SeriesIDs":[]}"""))
        assertTrue(repository.file(3).recognized)
        val request = server.takeRequest()
        assertEquals("XRefs", request.requestUrl?.queryParameter("include"))
        assertEquals("AniDB", request.requestUrl?.queryParameter("includeDataFrom"))
    }

    @Test fun `rescan queues one command and does not claim recognition`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        repository.rescan(11)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v3/File/11/Rescan?priority=true", request.path)
        assertEquals(1, server.requestCount)
    }

    @Test fun `redirects never forward credentials to another origin`() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/capture")))
            val error = runCatching { repository.avDumpStatus() }.exceptionOrNull()
            assertTrue(error is ShokoException)
            assertEquals(302, (error as ShokoException).statusCode)
            assertNull(other.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { other.shutdown() }
    }

    @Test fun `ambiguous command connection loss is not retried`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val error = runCatching { repository.dump(listOf(1)) }.exceptionOrNull()
        assertTrue(error is ShokoException)
        assertTrue((error as ShokoException).outcomeUnknown)
        assertEquals(1, server.requestCount)
    }

    @Test fun `server retry advice never replays a write`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200))
        val error = runCatching { repository.dump(listOf(1)) }.exceptionOrNull()
        assertTrue(error is ShokoException)
        assertEquals(503, (error as ShokoException).statusCode)
        assertTrue(error.outcomeUnknown)
        assertEquals(1, server.requestCount)
    }

    @Test fun `configuration errors are readable and secrets are redacted`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"errors":{"Settings":["Missing AVDump API key private-key"]}}"""))
        val error = runCatching { repository.dump(listOf(1)) }.exceptionOrNull() as ShokoException
        assertTrue(error.message!!.contains("Missing AVDump API key"))
        assertFalse(error.message!!.contains("private-key"))
        assertFalse(error.outcomeUnknown)
    }

    @Test fun `server address rejects credentials and query parameters`() {
        for (value in listOf("https://user:password@example.com", "https://example.com/?apikey=secret", "ftp://example.com")) {
            assertTrue(runCatching { ShokoRepository.normalizeServerUrl(value) }.isFailure)
        }
    }

    @Test fun `version check needs no API key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Server":{"Version":"5.3.3"}}"""))
        repository.version()
        assertNull(server.takeRequest().getHeader("apikey"))
    }
}
