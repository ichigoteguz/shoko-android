package dev.shoko.remote.shoko

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.TransportEnum
import io.reactivex.rxjava3.core.Completable
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Observes server-side jobs; it never starts or replays a dump. */
class AVDumpMonitor(baseUrl: String, private val apiKey: String) {
    private val base = ShokoRepository.normalizeServerUrl(baseUrl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var connection: HubConnection? = null
    private var reconnectJob: Job? = null
    @Volatile private var stopped = true
    private val _events = MutableSharedFlow<AVDumpEvent>(extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AVDumpEvent> = _events.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    suspend fun start() {
        mutex.withLock {
            if (_connected.value) return@withLock
            stopped = false
            reconnectJob?.cancel()
            reconnectJob = null
            connect()
        }
    }

    suspend fun stop() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        mutex.withLock {
            try {
                withTimeoutOrNull(5_000) { connection?.stop()?.onErrorComplete()?.awaitCompletion() }
            } finally {
                connection = null
                _connected.value = false
            }
        }
    }

    private suspend fun connect() {
        val hub = connection ?: createConnection().also { connection = it }
        try {
            hub.start().awaitCompletion()
            _connected.value = true
        } catch (error: Exception) {
            _connected.value = false
            if (error is CancellationException) throw error
            throw ShokoException(0, "Live progress is unavailable. File status can still be refreshed from Shoko.")
        }
    }

    private fun createConnection(): HubConnection {
        val url = base.newBuilder().addPathSegments("signalr/aggregate")
            .addQueryParameter("feeds", "avdump,shoko").build()
        return HubConnectionBuilder.create(url.toString())
            .withTransport(TransportEnum.WEBSOCKETS)
            // Stable 5.3.3 authenticates the apikey header; it does not consume Bearer tokens.
            .withHeader("apikey", apiKey)
            .withHandshakeResponseTimeout(15_000)
            .setHttpClientBuilderCallback { builder ->
                builder.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                builder.addInterceptor { chain ->
                    if (!ShokoRepository.sameOrigin(base, chain.request().url))
                        throw IOException("SignalR request outside the selected Shoko server was blocked.")
                    chain.proceed(chain.request())
                }
            }.build().also { hub ->
                // Object payloads avoid exposing the SignalR library's private Gson dependency.
                hub.on("AVDump:Event", { value: Any -> emitPayload(signalRJson(value).toString()) }, Any::class.java)
                hub.on("AVDump:OnConnected", { value: Any -> emitPayload(signalRJson(value).toString()) }, Any::class.java)
                hub.onClosed {
                    _connected.value = false
                    if (!stopped && reconnectJob?.isActive != true) {
                        reconnectJob = scope.launch {
                            var attempt = 0
                            while (!stopped) {
                                delay(longArrayOf(5_000, 15_000, 30_000, 60_000)[attempt.coerceAtMost(3)])
                                val succeeded = mutex.withLock { runCatching { connect() }.isSuccess }
                                if (succeeded) break
                                attempt++
                            }
                        }
                    }
                }
            }
    }

    private fun emitPayload(value: String) {
        // Unknown or malformed event payloads must not terminate the observation connection.
        runCatching { decodeEvents(value) }.getOrDefault(emptyList()).forEach { _events.tryEmit(it) }
    }

    companion object {
        internal fun signalRJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> {
                // Gson's untyped object decoder uses doubles for integer IDs as well.
                val number = value.toDouble()
                if (number.isFinite() && number.toLong().toDouble() == number) JsonPrimitive(number.toLong())
                else JsonPrimitive(value)
            }
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to signalRJson(it.value) })
            is Iterable<*> -> JsonArray(value.map(::signalRJson))
            is Array<*> -> JsonArray(value.map(::signalRJson))
            else -> JsonNull
        }

        internal fun decodeEvents(value: String): List<AVDumpEvent> {
            val root = ShokoRepository.json.parseToJsonElement(value)
            val values: List<JsonElement> = if (root is JsonArray) root else listOf(root)
            return values.map { ShokoRepository.json.decodeFromJsonElement(AVDumpEvent.serializer(), it) }
        }

        private suspend fun Completable.awaitCompletion(): Unit = suspendCancellableCoroutine { continuation ->
            val disposable = subscribe(
                { if (continuation.isActive) continuation.resume(Unit) },
                { if (continuation.isActive) continuation.resumeWithException(it) },
            )
            continuation.invokeOnCancellation { disposable.dispose() }
        }
    }
}
