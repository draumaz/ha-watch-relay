package xyz.draumaz.hawidget.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val HaJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
enum class CameraQualityPreset(
    val label: String,
    val maxWidthPx: Int,
    val jpegQuality: Int,
) {
    FAST("Fast (320px)", 320, 60),
    BALANCED("Balanced (400px)", 400, 72),
    SHARP("Sharp (480px)", 480, 82),
}

@Serializable
data class HaEntityConfig(
    val entityId: String,
    val label: String,
    val domain: String = entityId.substringBefore('.', ""),
) {
    val isMomentary: Boolean
        get() = domain in MOMENTARY_DOMAINS

    companion object {
        val CONTROLLABLE_DOMAINS: Set<String> = setOf(
            "switch",
            "light",
            "input_boolean",
            "fan",
            "lock",
            "cover",
            "button",
            "input_button",
            "script",
            "scene",
        )

        val MOMENTARY_DOMAINS: Set<String> = setOf(
            "button",
            "input_button",
            "script",
            "scene",
        )
    }
}

@Serializable
data class HaEntityState(
    val entityId: String,
    val state: String,
    val friendlyName: String = entityId,
    val domain: String = entityId.substringBefore('.', ""),
    val lastUpdatedEpochMs: Long = 0L,
) {
    val isOn: Boolean
        get() = state.equals("on", ignoreCase = true) ||
            state.equals("open", ignoreCase = true) ||
            state.equals("unlocked", ignoreCase = true)

    val isUnavailable: Boolean
        get() = state.equals("unavailable", ignoreCase = true) ||
            state.equals("unknown", ignoreCase = true)
}

@Serializable
data class WatchConfig(
    val isHaConfigured: Boolean = false,
    val selectedEntities: List<HaEntityConfig> = emptyList(),
    val cameraEntityId: String? = null,
    val cameraName: String = "",
    val cameraRefreshIntervalSec: Int = 5,
    val cameraQuality: CameraQualityPreset = CameraQualityPreset.BALANCED,
    val updatedAtEpochMs: Long = 0L,
)

@Serializable
data class EntityStatesSnapshot(
    val states: List<HaEntityState> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
    val error: String? = null,
) {
    fun stateFor(entityId: String): HaEntityState? =
        states.firstOrNull { it.entityId == entityId }
}

@Serializable
data class EntityActionRequest(
    val entityId: String,
    val domain: String = entityId.substringBefore('.', ""),
    val currentState: String? = null,
)

@Serializable
data class CameraFrameRequest(
    val entityId: String,
    val maxWidthPx: Int = CameraQualityPreset.BALANCED.maxWidthPx,
    val jpegQuality: Int = CameraQualityPreset.BALANCED.jpegQuality,
    val preferFastMessage: Boolean = false,
)

@Serializable
data class CameraFrameMeta(
    val entityId: String,
    val timestampEpochMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Int = 0,
    val error: String? = null,
)

/**
 * Maps a Home Assistant entity domain + current state to the (serviceDomain, serviceName)
 * pair to invoke via `POST /api/services/{serviceDomain}/{serviceName}`.
 */
object HaServiceMapper {
    data class ServiceCall(val domain: String, val service: String)

    fun resolveAction(request: EntityActionRequest): ServiceCall {
        val domain = request.domain.lowercase()
        val state = request.currentState?.lowercase()
        return when (domain) {
            "button" -> ServiceCall("button", "press")
            "input_button" -> ServiceCall("input_button", "press")
            "script" -> ServiceCall("script", "turn_on")
            "scene" -> ServiceCall("scene", "turn_on")
            "lock" -> if (state == "locked") {
                ServiceCall("lock", "unlock")
            } else {
                ServiceCall("lock", "lock")
            }
            "cover" -> if (state == "open" || state == "opening") {
                ServiceCall("cover", "close_cover")
            } else {
                ServiceCall("cover", "open_cover")
            }
            "switch", "light", "input_boolean", "fan" -> ServiceCall("homeassistant", "toggle")
            else -> ServiceCall("homeassistant", "toggle")
        }
    }
}
