package xyz.draumaz.wristbridge.mobile.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.draumaz.wristbridge.shared.HaJson
import java.net.URI
import java.net.URLDecoder

data class ParsedTokenQr(
    val token: String,
    val baseUrl: String? = null,
)

/**
 * Extracts a Home Assistant Long-Lived Access Token (and optional base URL)
 * from a scanned QR code payload.
 *
 * Home Assistant's built-in Long-Lived Access Token QR code contains the raw
 * JWT token string directly; this parser also handles JSON payloads or URLs
 * carrying a `token` / `access_token` query parameter.
 */
object TokenQrParser {

    fun parse(rawValue: String): ParsedTokenQr? {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) return null

        // 1. JSON payload: {"token": "...", "url": "..."} or {"access_token": "..."}
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val obj = runCatching { HaJson.decodeFromString<JsonObject>(trimmed) }.getOrNull()
            if (obj != null) {
                val token = (obj["token"] ?: obj["access_token"])
                    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?.trim()
                val url = (obj["url"] ?: obj["base_url"] ?: obj["server"])
                    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    ?.trim()
                    ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (!token.isNullOrBlank()) {
                    return ParsedTokenQr(token = token, baseUrl = url)
                }
            }
        }

        // 2. URI with ?token=... or ?access_token=...
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.contains("://")) {
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val query = uri?.rawQuery
            if (!query.isNullOrBlank()) {
                val params = query.split("&").associate { pair ->
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        k to v
                    } else {
                        pair to ""
                    }
                }
                val token = (params["token"] ?: params["access_token"])?.trim()
                if (!token.isNullOrBlank()) {
                    val base = if (uri.scheme == "http" || uri.scheme == "https") {
                        val portPart = if (uri.port != -1) ":${uri.port}" else ""
                        "${uri.scheme}://${uri.host}$portPart"
                    } else {
                        null
                    }
                    return ParsedTokenQr(token = token, baseUrl = base)
                }
            }
        }

        // 3. Default: Home Assistant's native Long-Lived Access Token QR (raw JWT / token string)
        val cleaned = trimmed.removePrefix("Bearer ").trim()
        return if (cleaned.isNotEmpty()) ParsedTokenQr(token = cleaned) else null
    }
}
