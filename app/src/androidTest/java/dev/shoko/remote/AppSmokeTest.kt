package dev.shoko.remote

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** All network calls in this test go to a server inside the instrumented test process. */
class AppSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val dumped = AtomicBoolean(false)
    private val dumpRequests = AtomicInteger(0)
    private val animeQueries = CopyOnWriteArrayList<String>()
    private val releaseName = "[SubsPlease] Sousou no Frieren - 01 (1080p) [DEADBEEF].mkv"
    private lateinit var server: MockWebServer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // This is isolated emulator/test-app state, never a production Shoko server.
        context.getSharedPreferences("server-session", Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking(Dispatchers.IO) { (context.applicationContext as ShokoApplication).database.clearAllTables() }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/api/auth") return json("""{"apikey":"instrumentation-only"}""")
                    if (path == "/api/v3/Init/Version") return json("""{"Server":{"Version":"5.3.3"}}""")
                    if (path.startsWith("/signalr/")) return MockResponse().setResponseCode(404)
                    if (request.getHeader("apikey") != "instrumentation-only") return MockResponse().setResponseCode(401)
                    return when {
                        path == "/api/v3/File" -> json("""{"Total":1,"List":[${fileJson()}]}""")
                        path == "/api/v3/File/42" -> json(fileJson())
                        path == "/api/v3/Series/AniDB/Search" -> {
                            animeQueries += request.requestUrl!!.queryParameter("query").orEmpty()
                            json("""{"Total":1,"List":[{"ID":99,"Title":"Sousou no Frieren"}]}""")
                        }
                        path == "/api/v3/AVDump/Status" -> json("""{"Installed":true,"InstalledVersion":"3","ExpectedVersion":"3"}""")
                        path == "/api/v3/AVDump/DumpFiles" && request.method == "POST" -> {
                            dumpRequests.incrementAndGet()
                            dumped.set(true)
                            MockResponse().setResponseCode(200)
                        }
                        path == "/api/v3/File/42/Rescan" && request.method == "POST" -> MockResponse().setResponseCode(200)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun teardown() {
        scenario?.close()
        server.shutdown()
    }

    @Test fun passwordlessLoginSelectionDumpAndRecovery() {
        awaitText("Connect to Shoko")
        compose.onNodeWithText("Server address").performTextInput(server.url("/").toString())
        compose.onNodeWithText("Shoko username").performTextInput("Default")
        // Empty passwords are valid in Shoko; this must be an enabled, reachable mobile action.
        compose.onNodeWithText("Connect to Shoko").performScrollTo().assertIsEnabled().performClick()
        awaitText(releaseName)
        compose.onNodeWithText(releaseName).performClick()
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        awaitText("Run AVDump")
        compose.onNodeWithText("Continue to AniDB").assertIsNotEnabled()
        compose.onNodeWithText("Run AVDump").performClick()
        compose.onNodeWithText("Dump selected public releases?").assertExists()
        compose.onAllNodesWithText("Run AVDump").onLast().performClick()
        compose.waitUntil(20_000) { dumpRequests.get() == 1 }
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithText("Refresh").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Refresh").performClick()
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithText("Continue to AniDB").assertIsEnabled() }.isSuccess
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            withTimeout(10_000) {
                while (withContext(Dispatchers.IO) {
                    (context.applicationContext as ShokoApplication).database.workspace().load()?.payload?.contains("Dumped") != true
                }) delay(50)
            }
        }
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitText("Continue to AniDB")
        compose.onNodeWithText("Continue to AniDB").assertIsEnabled()
        // Restoring state must only read status, never repeat an already accepted write.
        assertEquals(1, dumpRequests.get())
        compose.onNodeWithText("Continue to AniDB").performScrollTo().performClick()
        awaitText("AniDB #99")
        compose.onNodeWithText("Anime title or AniDB ID").assertTextContains("Sousou no Frieren")
        // Results appear without pressing Find anime; restarting the Activity must not repeat it.
        assertEquals(listOf("Sousou no Frieren"), animeQueries.toList())
        scenario!!.recreate()
        awaitText("AniDB #99")
        compose.onNodeWithText("Anime title or AniDB ID").assertTextContains("Sousou no Frieren")
        assertEquals(1, animeQueries.size)
    }

    private fun awaitText(text: String) = compose.waitUntil(20_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun fileJson(): String = """{"ID":42,"Size":780552997,"Hashes":{"ED2K":"0123456789ABCDEF0123456789ABCDEF","CRC32":"1234ABCD"},"Locations":[{"RelativePath":"$releaseName"}],"AVDump":{"Status":null,"LastDumpedAt":${if (dumped.get()) "\"2026-11-08T12:00:00Z\"" else "null"},"LastVersion":"3"},"SeriesIDs":[]}"""

    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
}
