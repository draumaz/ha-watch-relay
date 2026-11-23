package xyz.draumaz.wristbridge.mobile.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.draumaz.wristbridge.mobile.data.HaCredentials
import xyz.draumaz.wristbridge.shared.EntityActionRequest
import xyz.draumaz.wristbridge.shared.HaEntityState
import xyz.draumaz.wristbridge.shared.HaJson
import xyz.draumaz.wristbridge.shared.HaServiceMapper
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
internal data class RawHaState(
    @SerialName("entity_id") val entityId: String,
    val state: String,
    val attributes: JsonObject? = null,
)

class HaApiClient(
    private val httpClient: OkHttpClient = defaultOkHttpClient(),
) {

    suspend fun testConnection(credentials: HaCredentials): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(credentials.baseUrl, "/api/")
            val request = authedRequest(url, credentials.accessToken).get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }
                body.ifBlank { "Connected" }
            }
        }
    }

    suspend fun fetchAllStates(credentials: HaCredentials): Result<List<HaEntityState>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = buildUrl(credentials.baseUrl, "/api/states")
                val request = authedRequest(url, credentials.accessToken).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }
                    val body = response.body.string()
                    parseStatesList(body, System.currentTimeMillis())
                }
            }
        }

    suspend fun fetchEntityStates(
        credentials: HaCredentials,
        entityIds: List<String>,
    ): Result<List<HaEntityState>> = withContext(Dispatchers.IO) {
        if (entityIds.isEmpty()) return@withContext Result.success(emptyList())
        runCatching {
            val now = System.currentTimeMillis()
            val results = mutableListOf<HaEntityState>()
            for (entityId in entityIds) {
                val url = buildUrl(credentials.baseUrl, "/api/states/$entityId")
                val request = authedRequest(url, credentials.accessToken).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body.string()
                        results += parseSingleState(body, now)
                    } else if (response.code == 404) {
                        results += HaEntityState(
                            entityId = entityId,
                            state = "unavailable",
                            friendlyName = entityId,
                            lastUpdatedEpochMs = now,
                        )
                    } else {
                        throw IOException("HTTP ${response.code} for $entityId")
                    }
                }
            }
            results
        }
    }

    suspend fun executeEntityAction(
        credentials: HaCredentials,
        action: EntityActionRequest,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val serviceCall = HaServiceMapper.resolveAction(action)
            val url = buildUrl(
                credentials.baseUrl,
                "/api/services/${serviceCall.domain}/${serviceCall.service}",
            )
            val payload = """{"entity_id":"${action.entityId}"}"""
            val request = authedRequest(url, credentials.accessToken)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Service call failed: HTTP ${response.code}")
                }
            }
        }
    }

    suspend fun fetchCameraSnapshot(
        credentials: HaCredentials,
        entityId: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = buildUrl(credentials.baseUrl, "/api/camera_proxy/$entityId")
            val request = authedRequest(url, credentials.accessToken).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Camera proxy HTTP ${response.code}: ${response.message}")
                }
                response.body.bytes()
            }
        }
    }

    private fun authedRequest(url: String, token: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        fun buildUrl(baseUrl: String, path: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "Home Assistant URL must start with http:// or https://"
            }
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            return "$trimmed$cleanPath"
        }

        fun parseStatesList(jsonBody: String, nowEpochMs: Long): List<HaEntityState> {
            val rawList = HaJson.decodeFromString<List<RawHaState>>(jsonBody)
            return rawList.map { it.toEntityState(nowEpochMs) }
        }

        fun parseSingleState(jsonBody: String, nowEpochMs: Long): HaEntityState {
            val raw = HaJson.decodeFromString<RawHaState>(jsonBody)
            return raw.toEntityState(nowEpochMs)
        }

        private fun RawHaState.toEntityState(nowEpochMs: Long): HaEntityState {
            val friendlyName = attributes?.get("friendly_name")?.asStringOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: entityId.substringAfter('.').replace('_', ' ')
            return HaEntityState(
                entityId = entityId,
                state = state,
                friendlyName = friendlyName,
                domain = entityId.substringBefore('.', ""),
                lastUpdatedEpochMs = nowEpochMs,
            )
        }

        private fun JsonElement.asStringOrNull(): String? =
            runCatching { jsonPrimitive.contentOrNull }.getOrNull()
    }
}
