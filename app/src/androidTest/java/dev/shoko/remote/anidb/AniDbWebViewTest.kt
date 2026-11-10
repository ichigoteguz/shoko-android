package dev.shoko.remote.anidb

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the actual asset in Android WebView, without requests to AniDB or a submission. */
@RunWith(AndroidJUnit4::class)
class AniDbWebViewTest {
    @Test
    fun nativeTransferUsesBrowserEventsAndDoesNotSubmit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val html = instrumentation.context.assets.open("anidb-fixture.html").bufferedReader().use { it.readText() }
        lateinit var controller: AniDbWebController
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            controller = AniDbWebController(instrumentation.targetContext.applicationContext)
            controller.attachToHost(activity)
            activity.setContentView(controller.webView)
            val fixtureUrl = "https://anidb.net/anime/1/release/add"
            val productionClient = controller.webView.webViewClient
            controller.webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    if (request.url.toString() == fixtureUrl) {
                        WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                    } else {
                        // No test URL, redirect, subresource, or submitted form may use a real network.
                        WebResourceResponse("text/plain", "UTF-8", 403, "Fixture only", emptyMap(), "".byteInputStream())
                    }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) =
                    productionClient.onPageStarted(view, url, favicon)

                override fun onPageFinished(view: WebView, url: String?) =
                    productionClient.onPageFinished(view, url)
            }
            controller.webView.loadUrl(fixtureUrl)
        }
        try {
            val first = runBlocking { withTimeoutOrNull(20_000) { controller.snapshot.first { it.status == "form" } } }
            assertNotNull("Fixture did not load: ${controller.snapshot.value}; ${controller.pageError.value}", first)
            assertEquals("unknown", first!!.fields.first { it.id == "addfilem_type" }.value)
            val done = CountDownLatch(1)
            var applied = false
            instrumentation.runOnMainSync {
                controller.apply(AniDbEdits(
                    values = mapOf("addfilem_group" to "42", "addfilem_type" to "www", "addstrm_aud_2_type" to "commentary", "addfilem_ed2k" to "ed2k://|file|fixture.mkv|10|0123456789abcdef0123456789abcdef|/"),
                    checks = mapOf("addstrm_vid_flag_FLAG_CLEAN" to true, "addstrm_sub_2_flag_FLAG_HEARINGIMP" to true),
                    audioTracks = 2, subtitleTracks = 2,
                )) { applied = it; done.countDown() }
            }
            assertTrue("WebView did not answer", done.await(20, TimeUnit.SECONDS))
            assertTrue(controller.pageError.value, applied)
            val after = controller.snapshot.value
            assertEquals(2, after.audioTracks)
            assertEquals(2, after.subtitleTracks)
            assertTrue(after.fields.first { it.id == "addstrm_sub_2_flag_FLAG_HEARINGIMP" }.checked)
            assertEquals("commentary", after.fields.first { it.id == "addstrm_aud_2_type" }.value)
            val inspect = CountDownLatch(1)
            var counters = ""
            instrumentation.runOnMainSync {
                controller.webView.evaluateJavascript("JSON.stringify(Object.assign({}, window.fixtureCounts, { metadataEpisodes: document.getElementById('addfilem_episodes').value, metadataTitle: document.getElementById('addfilem_anime_title').value }))") { counters = it; inspect.countDown() }
            }
            assertTrue(inspect.await(10, TimeUnit.SECONDS))
            assertTrue("No form submission is permitted in this test: $counters", counters.contains("\\\"submits\\\":0"))
            assertTrue("No automatic Next is permitted: $counters", counters.contains("\\\"next\\\":0"))
            assertTrue("Hidden episode metadata must be preserved: $counters", counters.contains("\\\"metadataEpisodes\\\":\\\"1\\\""))
            assertTrue("Hidden anime metadata must be preserved: $counters", counters.contains("\\\"metadataTitle\\\":\\\"Fixture anime\\\""))
        } finally {
            scenario.onActivity { activity ->
                activity.setContentView(android.widget.FrameLayout(activity))
                controller.detachFromHost()
                controller.dispose()
            }
            scenario.close()
        }
    }
}
