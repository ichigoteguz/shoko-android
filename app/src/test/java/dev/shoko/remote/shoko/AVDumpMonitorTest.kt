package dev.shoko.remote.shoko

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class AVDumpMonitorTest {
    @Test fun `SignalR authenticates both negotiate and WebSocket with apikey header`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{"negotiateVersion":1,"connectionId":"connection","connectionToken":"token","availableTransports":[{"transport":"WebSockets","transferFormats":["Text","Binary"]}]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            // The SignalR Java client sends its UTF-8 JSON handshake in a binary frame.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"protocol\"")) {
                    webSocket.send("{}\u001e")
                    webSocket.send("{\"type\":1,\"target\":\"AVDump:Event\",\"arguments\":[{\"Type\":\"Progress\",\"SessionID\":7,\"VideoIDs\":[42],\"Progress\":50}]}\u001e")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val monitor = AVDumpMonitor(server.url("/").toString(), "private-key")
        try {
            withTimeout(10_000) {
                val incoming = async(start = CoroutineStart.UNDISPATCHED) { monitor.events.first() }
                monitor.start()
                val event = incoming.await()
                assertEquals(listOf(42), event.videoIds)
                assertEquals(50.0, event.progress!!, 0.0)
                val negotiate = server.takeRequest()
                val websocket = server.takeRequest()
                assertTrue(negotiate.path!!.contains("/signalr/aggregate/negotiate"))
                for (request in listOf(negotiate, websocket)) {
                    assertEquals("private-key", request.getHeader("apikey"))
                    assertNull(request.getHeader("Authorization"))
                    assertNull(request.requestUrl?.queryParameter("access_token"))
                    assertNull(request.requestUrl?.queryParameter("apikey"))
                }
            }
        } finally {
            monitor.stop()
            server.shutdown()
        }
    }

    @Test fun `SignalR untyped number payload restores integer IDs`() {
        val payload = AVDumpMonitor.signalRJson(mapOf("Type" to "Progress", "SessionID" to 4.0,
            "VideoIDs" to listOf(11.0), "Progress" to 12.5))
        val event = AVDumpMonitor.decodeEvents(payload.toString()).single()
        assertEquals(4, event.sessionId)
        assertEquals(listOf(11), event.videoIds)
        assertEquals(12.5, event.progress!!, 0.0)
    }

    @Test fun `stable progress event preserves lowercase ed2ks and video correlation`() {
        val event = AVDumpMonitor.decodeEvents("""{"Type":"Progress","SessionID":4,"VideoIDs":[11,12],"Progress":25,"ed2ks":["ed2k://|file|example.mkv|1|0123456789ABCDEF0123456789ABCDEF|/"],"UnknownField":true}""").single()
        assertEquals("Progress", event.type)
        assertEquals(listOf(11, 12), event.videoIds)
        assertEquals(25.0, event.progress!!, 0.0)
        assertEquals(1, event.ed2ks.size)
        assertFalse(event.isTerminal)
    }

    @Test fun `reconnect restore payload is an array of active sessions`() {
        val events = AVDumpMonitor.decodeEvents("""[{"Type":"Restore","SessionID":1,"VideoIDs":[3]},{"Type":"Restore","SessionID":2,"VideoIDs":[4]}]""")
        assertEquals(listOf(1, 2), events.map { it.sessionId })
        assertFalse(events.any { it.isTerminal })
    }

    @Test fun `failures remain distinct from successful dump completion`() {
        assertTrue(AVDumpEvent("Success").isTerminal)
        assertFalse(AVDumpEvent("Success").failed)
        assertTrue(AVDumpEvent("MissingApiKey").failed)
        assertTrue(AVDumpEvent("Timeout").failed)
        assertFalse(AVDumpEvent("InstalledAVDump").isTerminal)
    }

    @Test fun `a hash without a successful dump timestamp does not imply dump success`() {
        val file = ShokoFile(1, 100, FileHashes("0123456789ABCDEF0123456789ABCDEF"))
        assertTrue(file.ed2kLink.isNotBlank())
        assertNull(file.avDump.lastDumpedAt)
        assertFalse(file.recognized)
    }
}
