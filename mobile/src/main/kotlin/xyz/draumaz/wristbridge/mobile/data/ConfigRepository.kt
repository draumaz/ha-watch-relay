package xyz.draumaz.wristbridge.mobile.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import xyz.draumaz.wristbridge.shared.CameraQualityPreset
import xyz.draumaz.wristbridge.shared.HaEntityConfig
import xyz.draumaz.wristbridge.shared.HaJson
import xyz.draumaz.wristbridge.shared.WatchConfig

data class HaCredentials(
    val baseUrl: String,
    val accessToken: String,
) {
    val isValid: Boolean
        get() = baseUrl.isNotBlank() && accessToken.isNotBlank()
}

class ConfigRepository(context: Context) {

    private val appContext = context.applicationContext

    private val securePrefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "wristbridge_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            appContext.getSharedPreferences("wristbridge_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    private val configPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("wristbridge_config", Context.MODE_PRIVATE)
    }

    fun getCredentials(): HaCredentials = HaCredentials(
        baseUrl = securePrefs.getString(KEY_BASE_URL, "") ?: "",
        accessToken = securePrefs.getString(KEY_ACCESS_TOKEN, "") ?: "",
    )

    fun saveCredentials(baseUrl: String, accessToken: String) {
        securePrefs.edit {
            putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            putString(KEY_ACCESS_TOKEN, accessToken.trim())
        }
    }

    fun getWatchConfig(): WatchConfig {
        val raw = configPrefs.getString(KEY_WATCH_CONFIG_JSON, null)
        val parsed = if (raw != null) {
            runCatching { HaJson.decodeFromString<WatchConfig>(raw) }.getOrNull()
        } else {
            null
        }
        val creds = getCredentials()
        return (parsed ?: WatchConfig()).copy(isHaConfigured = creds.isValid)
    }

    fun saveWatchConfig(config: WatchConfig): WatchConfig {
        val creds = getCredentials()
        val stamped = config.copy(
            isHaConfigured = creds.isValid,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        configPrefs.edit {
            putString(KEY_WATCH_CONFIG_JSON, HaJson.encodeToString(stamped))
        }
        return stamped
    }

    fun updateSelectedEntities(entities: List<HaEntityConfig>): WatchConfig {
        return saveWatchConfig(getWatchConfig().copy(selectedEntities = entities))
    }

    fun updateCameraConfig(
        cameraEntityId: String?,
        cameraName: String,
        refreshIntervalSec: Int,
        quality: CameraQualityPreset,
    ): WatchConfig {
        return saveWatchConfig(
            getWatchConfig().copy(
                cameraEntityId = cameraEntityId,
                cameraName = cameraName,
                cameraRefreshIntervalSec = refreshIntervalSec.coerceIn(2, 60),
                cameraQuality = quality,
            ),
        )
    }

    companion object {
        private const val KEY_BASE_URL = "ha_base_url"
        private const val KEY_ACCESS_TOKEN = "ha_access_token"
        private const val KEY_WATCH_CONFIG_JSON = "watch_config_json"
    }
}
