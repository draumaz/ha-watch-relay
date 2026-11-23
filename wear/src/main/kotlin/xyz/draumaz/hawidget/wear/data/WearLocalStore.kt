package xyz.draumaz.hawidget.wear.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import xyz.draumaz.hawidget.shared.CameraFrameMeta
import xyz.draumaz.hawidget.shared.EntityStatesSnapshot
import xyz.draumaz.hawidget.shared.HaEntityState
import xyz.draumaz.hawidget.shared.HaJson
import xyz.draumaz.hawidget.shared.WatchConfig
import java.io.File

data class CachedCameraFrame(
    val meta: CameraFrameMeta,
    val jpegBytes: ByteArray?,
)

/**
 * Process-wide local cache on the watch backed by SharedPreferences and app-private files.
 * Tiles read synchronously from here for instant rendering while fresh updates arrive via the phone.
 */
object WearLocalStore {

    private const val PREFS_NAME = "ha_wear_cache"
    private const val KEY_CONFIG = "watch_config_json"
    private const val KEY_STATES = "entity_states_json"
    private const val KEY_CAMERA_META = "camera_meta_json"
    private const val CAMERA_FILE_NAME = "camera_latest.jpg"

    private val _configFlow = MutableStateFlow(WatchConfig())
    val configFlow: StateFlow<WatchConfig> = _configFlow.asStateFlow()

    private val _statesFlow = MutableStateFlow(EntityStatesSnapshot())
    val statesFlow: StateFlow<EntityStatesSnapshot> = _statesFlow.asStateFlow()

    private val _cameraFlow = MutableStateFlow<CachedCameraFrame?>(null)
    val cameraFlow: StateFlow<CachedCameraFrame?> = _cameraFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var lastOptimisticToggleEpochMs: Long = 0L

    fun wasRecentlyToggled(withinMs: Long = 1_500L): Boolean =
        (System.currentTimeMillis() - lastOptimisticToggleEpochMs) < withinMs

    fun markOptimisticToggle() {
        lastOptimisticToggleEpochMs = System.currentTimeMillis()
    }

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            _configFlow.value = readConfig(context)
            _statesFlow.value = readStates(context)
            val meta = readCameraMeta(context)
            val bytes = readCameraBytes(context)
            if (meta != null) {
                _cameraFlow.value = CachedCameraFrame(meta, bytes)
            }
            initialized = true
        }
    }

    fun readConfig(context: Context): WatchConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG, null) ?: return WatchConfig()
        return runCatching { HaJson.decodeFromString<WatchConfig>(raw) }.getOrDefault(WatchConfig())
    }

    fun saveConfig(context: Context, config: WatchConfig) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, HaJson.encodeToString(config)).apply()
        _configFlow.value = config
    }

    fun readStates(context: Context): EntityStatesSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_STATES, null) ?: return EntityStatesSnapshot()
        return runCatching { HaJson.decodeFromString<EntityStatesSnapshot>(raw) }
            .getOrDefault(EntityStatesSnapshot())
    }

    fun saveStates(context: Context, snapshot: EntityStatesSnapshot) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATES, HaJson.encodeToString(snapshot)).apply()
        _statesFlow.value = snapshot
    }

    /**
     * Used when hydrating cached DataClient items so an older snapshot never
     * clobbers a newer state or an in-flight optimistic toggle.
     */
    fun saveStatesIfNewer(context: Context, snapshot: EntityStatesSnapshot) {
        if (wasRecentlyToggled(withinMs = 3_000L)) return
        val current = readStates(context)
        if (snapshot.updatedAtEpochMs > current.updatedAtEpochMs) {
            saveStates(context, snapshot)
        }
    }

    fun applyOptimisticToggle(context: Context, entityId: String) {
        markOptimisticToggle()
        val now = System.currentTimeMillis()
        val current = readStates(context)
        val updatedStates = current.states.map { st ->
            if (st.entityId == entityId) {
                val nextState = when (st.domain.lowercase()) {
                    "lock" -> if (st.state.equals("locked", true)) "unlocked" else "locked"
                    "cover" -> if (st.state.equals("open", true)) "closed" else "open"
                    "switch", "light", "input_boolean", "fan" -> if (st.isOn) "off" else "on"
                    else -> st.state
                }
                st.copy(state = nextState, lastUpdatedEpochMs = now)
            } else {
                st
            }
        }
        val finalStates = if (updatedStates.any { it.entityId == entityId }) {
            updatedStates
        } else {
            updatedStates + HaEntityState(
                entityId = entityId,
                state = "on",
                lastUpdatedEpochMs = now,
            )
        }
        saveStates(
            context,
            current.copy(
                states = finalStates,
                updatedAtEpochMs = now,
                error = null,
            ),
        )
    }

    fun readCameraMeta(context: Context): CameraFrameMeta? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CAMERA_META, null) ?: return null
        return runCatching { HaJson.decodeFromString<CameraFrameMeta>(raw) }.getOrNull()
    }

    fun readCameraBytes(context: Context): ByteArray? {
        val file = File(context.applicationContext.filesDir, CAMERA_FILE_NAME)
        return if (file.exists() && file.length() > 0L) {
            runCatching { file.readBytes() }.getOrNull()
        } else {
            null
        }
    }

    fun saveCameraFrame(context: Context, meta: CameraFrameMeta, jpegBytes: ByteArray?) {
        val appCtx = context.applicationContext
        if (jpegBytes != null && jpegBytes.isNotEmpty()) {
            val tmpFile = File(appCtx.filesDir, "$CAMERA_FILE_NAME.tmp")
            val finalFile = File(appCtx.filesDir, CAMERA_FILE_NAME)
            runCatching {
                tmpFile.writeBytes(jpegBytes)
                tmpFile.renameTo(finalFile)
            }
        }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CAMERA_META, HaJson.encodeToString(meta)).apply()
        val effectiveBytes = jpegBytes ?: readCameraBytes(appCtx)
        _cameraFlow.value = CachedCameraFrame(meta, effectiveBytes)
    }

    fun formatAge(epochMs: Long): String {
        if (epochMs <= 0L) return "never"
        val deltaSec = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0L)
        return when {
            deltaSec < 5 -> "just now"
            deltaSec < 60 -> "${deltaSec}s ago"
            deltaSec < 3600 -> "${deltaSec / 60}m ago"
            else -> "${deltaSec / 3600}h ago"
        }
    }
}
