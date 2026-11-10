package dev.shoko.remote.shoko

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Stable Shoko 5.3.3 API. Commands are deliberately not retried or redirected. */
class ShokoRepository(
    baseUrl: String,
    private val apiKey: String,
    client: OkHttpClient? = null,
) {
    val serverUrl: HttpUrl = normalizeServerUrl(baseUrl)
    private val client = originBoundClient(serverUrl, client)

    suspend fun version(): JsonObject = decode(request("api/v3/Init/Version", authenticate = false))

    suspend fun unrecognized(query: String = "", page: Int = 1, pageSize: Int = 100): FilePage {
        require(page > 0 && pageSize in 1..1000)
        val path = if (query.isBlank()) "api/v3/File" else "api/v3/File/Search"
        val url = endpoint(path).newBuilder().apply {
            if (query.isNotBlank()) addPathSegment(query)
            addQueryParameter("include_only", "Unrecognized")
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", pageSize.toString())
            addQueryParameter("sortOrder", "FileName")
        }.build()
        return decode(execute(url))
    }

    suspend fun file(id: Int): ShokoFile {
        require(id > 0)
        return decode(execute(endpoint("api/v3/File/$id").newBuilder()
            .addQueryParameter("include", "XRefs")
            .addQueryParameter("includeDataFrom", "AniDB").build()))
    }

    suspend fun animeSearch(query: String, page: Int = 1, pageSize: Int = 50): AnimePage {
        require(page > 0 && pageSize in 1..100)
        val url = endpoint("api/v3/Series/AniDB/Search").newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("searchById", "true")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", pageSize.toString()).build()
        return decode(execute(url))
    }

    suspend fun dump(fileIds: Collection<Int>) {
        val ids = fileIds.distinct()
        require(ids.isNotEmpty() && ids.all { it > 0 }) { "Select at least one valid file." }
        val body = buildJsonObject { putJsonArray("FileIDs") { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
        request("api/v3/AVDump/DumpFiles", body = body.toString())
    }

    suspend fun rescan(id: Int) {
        require(id > 0)
        execute(endpoint("api/v3/File/$id/Rescan").newBuilder()
            .addQueryParameter("priority", "true").build(), body = "")
    }

    suspend fun avDumpStatus(): AVDumpStatus = decode(request("api/v3/AVDump/Status"))

    private fun endpoint(path: String): HttpUrl = serverUrl.newBuilder().addPathSegments(path).build()

    private suspend fun request(path: String, body: String? = null, authenticate: Boolean = true): String =
        execute(endpoint(path), body, authenticate)

    private suspend fun execute(url: HttpUrl, body: String? = null, authenticate: Boolean = true): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (authenticate) {
                require(apiKey.isNotBlank()) { "Sign in to Shoko Server first." }
                header("apikey", apiKey)
            }
            if (body != null) post(oneShotJson(body))
        }.build()
        return executeRequest(client, request, body != null, apiKey)
    }

    private inline fun <reified T> decode(value: String): T = try {
        json.decodeFromString<T>(value)
    } catch (_: SerializationException) {
        throw ShokoException(0, "Shoko returned an unexpected response. This app targets Server 5.3.3.")
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun oneShotJson(value: String): RequestBody {
            val delegate = value.toRequestBody(JSON_MEDIA_TYPE)
            return object : RequestBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
                // Also prevents OkHttp follow-up replay after a 503 with Retry-After: 0.
                override fun isOneShot() = true
            }
        }

        suspend fun login(
            baseUrl: String,
            user: String,
            password: String,
            device: String,
            client: OkHttpClient? = null,
        ): String {
            require(user.isNotBlank() && device.isNotBlank()) { "Enter your Shoko username." }
            val base = normalizeServerUrl(baseUrl)
            val body = buildJsonObject { put("user", user); put("pass", password); put("device", device) }
            val request = Request.Builder().url(base.newBuilder().addPathSegments("api/auth").build())
                .header("Accept", "application/json")
                .post(oneShotJson(body.toString())).build()
            val value = executeRequest(originBoundClient(base, client), request, true, password)
            val key = runCatching { json.parseToJsonElement(value).jsonObject["apikey"]?.jsonPrimitive?.content }.getOrNull()
            return key?.takeIf { it.isNotBlank() }
                ?: throw ShokoException(0, "Shoko did not return an API key.")
        }

        fun normalizeServerUrl(value: String): HttpUrl {
            val url = try { (value.trim().trimEnd('/') + "/").toHttpUrl() }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Enter a complete http:// or https:// server address.") }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "The server address must not contain credentials, a query, or a fragment."
            }
            return url
        }

        internal fun originBoundClient(base: HttpUrl, template: OkHttpClient? = null): OkHttpClient =
            (template ?: OkHttpClient()).newBuilder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val target = chain.request().url
                    if (!sameOrigin(base, target)) throw IOException("Request outside the selected Shoko server was blocked.")
                    chain.proceed(chain.request())
                }.build()

        internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
            a.scheme == b.scheme && a.host == b.host && a.port == b.port

        private suspend fun executeRequest(client: OkHttpClient, request: Request, command: Boolean, secret: String): String =
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(ShokoException(0,
                            if (command) "Connection interrupted. The command may have reached Shoko; refresh its status before retrying."
                            else "Cannot reach Shoko Server. Check its address and your network or VPN.", command))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                val body = it.body?.string().orEmpty()
                                if (!continuation.isActive) return
                                if (it.isSuccessful) continuation.resume(body)
                                else continuation.resumeWithException(ShokoException(it.code, errorMessage(it.code, body, secret), command && it.code >= 500))
                            }
                        } catch (_: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(ShokoException(0,
                                "The response was interrupted. Refresh status before retrying.", command))
                        }
                    }
                })
            }

        private fun errorMessage(code: Int, body: String, secret: String): String {
            if (code == 401) return "Your Shoko sign-in expired or the credentials were incorrect. Sign in again."
            if (code == 403) return "Your Shoko account does not have permission for this action."
            if (code in 300..399) return "Shoko redirected the request. Enter the final server address and sign in there."
            val detail = runCatching {
                val obj = json.parseToJsonElement(body).jsonObject
                val errors = obj["errors"] as? JsonObject
                errors?.values?.joinToString(" ") { item ->
                    (item as? kotlinx.serialization.json.JsonArray)?.joinToString(" ") { it.jsonPrimitive.content }.orEmpty()
                }?.takeIf { it.isNotBlank() }
                    ?: obj["detail"]?.jsonPrimitive?.content
                    ?: obj["title"]?.jsonPrimitive?.content
            }.getOrNull()
            return (detail?.take(500) ?: "Shoko could not complete the request (HTTP $code).")
                .let { if (secret.isNotEmpty()) it.replace(secret, "[redacted]") else it }
        }
    }
}
