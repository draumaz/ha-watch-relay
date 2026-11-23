package xyz.draumaz.wristbridge.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.draumaz.wristbridge.mobile.data.ConfigRepository
import xyz.draumaz.wristbridge.mobile.data.HaCredentials
import xyz.draumaz.wristbridge.mobile.ha.CameraImageProcessor
import xyz.draumaz.wristbridge.mobile.ha.HaApiClient
import xyz.draumaz.wristbridge.mobile.wear.ConnectedWatchInfo
import xyz.draumaz.wristbridge.mobile.wear.WearSyncManager
import xyz.draumaz.wristbridge.shared.CameraFrameMeta
import xyz.draumaz.wristbridge.shared.CameraQualityPreset
import xyz.draumaz.wristbridge.shared.EntityStatesSnapshot
import xyz.draumaz.wristbridge.shared.HaEntityConfig
import xyz.draumaz.wristbridge.shared.HaEntityState
import xyz.draumaz.wristbridge.shared.WatchConfig

data class MobileUiState(
    val baseUrl: String = "",
    val accessToken: String = "",
    val connectionStatus: String? = null,
    val isTestingConnection: Boolean = false,
    val isLoadingEntities: Boolean = false,
    val isSyncing: Boolean = false,
    val syncStatus: String? = null,
    val connectedWatches: List<ConnectedWatchInfo> = emptyList(),
    val allControllableEntities: List<HaEntityState> = emptyList(),
    val allCameraEntities: List<HaEntityState> = emptyList(),
    val entitySearchQuery: String = "",
    val watchConfig: WatchConfig = WatchConfig(),
    val cameraPreviewBytes: ByteArray? = null,
    val isLoadingCameraPreview: Boolean = false,
)

class MobileViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepo = ConfigRepository(application)
    private val haClient = HaApiClient()
    private val syncManager = WearSyncManager(application)

    private val _uiState = MutableStateFlow(MobileUiState())
    val uiState: StateFlow<MobileUiState> = _uiState.asStateFlow()

    init {
        val creds = configRepo.getCredentials()
        val config = configRepo.getWatchConfig()
        _uiState.update {
            it.copy(
                baseUrl = creds.baseUrl,
                accessToken = creds.accessToken,
                watchConfig = config,
            )
        }
        refreshConnectedWatches()
        if (creds.isValid) {
            fetchEntitiesFromHa()
        }
    }

    fun onBaseUrlChanged(value: String) {
        _uiState.update { it.copy(baseUrl = value) }
    }

    fun onAccessTokenChanged(value: String) {
        _uiState.update { it.copy(accessToken = value) }
    }

    fun onEntitySearchChanged(value: String) {
        _uiState.update { it.copy(entitySearchQuery = value) }
    }

    fun saveAndTestConnection() {
        val state = _uiState.value
        configRepo.saveCredentials(state.baseUrl, state.accessToken)
        val creds = configRepo.getCredentials()
        val updatedConfig = configRepo.saveWatchConfig(state.watchConfig)
        _uiState.update {
            it.copy(
                watchConfig = updatedConfig,
                isTestingConnection = true,
                connectionStatus = null,
            )
        }

        viewModelScope.launch {
            val result = haClient.testConnection(creds)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionStatus = "Connected to Home Assistant!",
                        )
                    }
                    fetchEntitiesFromHa()
                    syncEverythingToWatch()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionStatus = "Connection failed: ${err.message}",
                        )
                    }
                },
            )
        }
    }

    fun fetchEntitiesFromHa() {
        val creds = configRepo.getCredentials()
        if (!creds.isValid) return

        _uiState.update { it.copy(isLoadingEntities = true) }
        viewModelScope.launch {
            haClient.fetchAllStates(creds).fold(
                onSuccess = { allStates ->
                    val controllable = allStates
                        .filter { it.domain in HaEntityConfig.CONTROLLABLE_DOMAINS }
                        .sortedBy { it.friendlyName.lowercase() }
                    val cameras = allStates
                        .filter { it.domain == "camera" }
                        .sortedBy { it.friendlyName.lowercase() }
                    _uiState.update {
                        it.copy(
                            isLoadingEntities = false,
                            allControllableEntities = controllable,
                            allCameraEntities = cameras,
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingEntities = false,
                            connectionStatus = "Failed to load entities: ${err.message}",
                        )
                    }
                },
            )
        }
    }

    fun toggleEntitySelection(entity: HaEntityState) {
        val current = _uiState.value.watchConfig.selectedEntities.toMutableList()
        val existingIndex = current.indexOfFirst { it.entityId == entity.entityId }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(
                HaEntityConfig(
                    entityId = entity.entityId,
                    label = entity.friendlyName,
                    domain = entity.domain,
                ),
            )
        }
        val updated = configRepo.updateSelectedEntities(current)
        _uiState.update { it.copy(watchConfig = updated) }
        syncEverythingToWatch()
    }

    fun moveSelectedEntity(index: Int, delta: Int) {
        val current = _uiState.value.watchConfig.selectedEntities.toMutableList()
        val target = index + delta
        if (index !in current.indices || target !in current.indices) return
        val item = current.removeAt(index)
        current.add(target, item)
        val updated = configRepo.updateSelectedEntities(current)
        _uiState.update { it.copy(watchConfig = updated) }
        syncEverythingToWatch()
    }

    fun selectCamera(camera: HaEntityState) {
        val cfg = _uiState.value.watchConfig
        val updated = configRepo.updateCameraConfig(
            cameraEntityId = camera.entityId,
            cameraName = camera.friendlyName,
            refreshIntervalSec = cfg.cameraRefreshIntervalSec,
            quality = cfg.cameraQuality,
        )
        _uiState.update { it.copy(watchConfig = updated) }
        syncEverythingToWatch()
        loadCameraPreview()
    }

    fun setCameraRefreshInterval(seconds: Int) {
        val cfg = _uiState.value.watchConfig
        val updated = configRepo.updateCameraConfig(
            cameraEntityId = cfg.cameraEntityId,
            cameraName = cfg.cameraName,
            refreshIntervalSec = seconds,
            quality = cfg.cameraQuality,
        )
        _uiState.update { it.copy(watchConfig = updated) }
        syncEverythingToWatch()
    }

    fun setCameraQuality(preset: CameraQualityPreset) {
        val cfg = _uiState.value.watchConfig
        val updated = configRepo.updateCameraConfig(
            cameraEntityId = cfg.cameraEntityId,
            cameraName = cfg.cameraName,
            refreshIntervalSec = cfg.cameraRefreshIntervalSec,
            quality = preset,
        )
        _uiState.update { it.copy(watchConfig = updated) }
        syncEverythingToWatch()
    }

    fun loadCameraPreview() {
        val creds = configRepo.getCredentials()
        val cfg = _uiState.value.watchConfig
        val cameraId = cfg.cameraEntityId ?: return
        if (!creds.isValid) return

        _uiState.update { it.copy(isLoadingCameraPreview = true) }
        viewModelScope.launch {
            haClient.fetchCameraSnapshot(creds, cameraId).fold(
                onSuccess = { raw ->
                    // Bitmap decode/scale/encode is CPU-bound; keep it off the main thread.
                    val processed = withContext(Dispatchers.Default) {
                        CameraImageProcessor.processForWatch(
                            rawBytes = raw,
                            maxWidthPx = cfg.cameraQuality.maxWidthPx,
                            jpegQuality = cfg.cameraQuality.jpegQuality,
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingCameraPreview = false,
                            cameraPreviewBytes = processed.jpegBytes,
                        )
                    }
                    syncManager.pushCameraFrame(
                        jpegBytes = processed.jpegBytes,
                        meta = CameraFrameMeta(
                            entityId = cameraId,
                            timestampEpochMs = System.currentTimeMillis(),
                            width = processed.width,
                            height = processed.height,
                            sizeBytes = processed.jpegBytes.size,
                        ),
                    )
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingCameraPreview = false,
                            syncStatus = "Camera preview error: ${err.message}",
                        )
                    }
                },
            )
        }
    }

    fun refreshConnectedWatches() {
        viewModelScope.launch {
            val watches = syncManager.getConnectedWatches()
            _uiState.update { it.copy(connectedWatches = watches) }
        }
    }

    fun syncEverythingToWatch() {
        val creds = configRepo.getCredentials()
        val cfg = configRepo.getWatchConfig()
        _uiState.update { it.copy(isSyncing = true, syncStatus = null) }

        viewModelScope.launch {
            val watches = syncManager.getConnectedWatches()
            val configPush = syncManager.pushConfig(cfg)
            var statePushError: String? = null

            if (creds.isValid && cfg.selectedEntities.isNotEmpty()) {
                val ids = cfg.selectedEntities.map { it.entityId }
                haClient.fetchEntityStates(creds, ids).fold(
                    onSuccess = { states ->
                        syncManager.pushEntityStates(
                            EntityStatesSnapshot(
                                states = states,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            ),
                        )
                    },
                    onFailure = { statePushError = it.message },
                )
            }

            val statusMsg = when {
                configPush.isFailure -> "Sync failed: ${configPush.exceptionOrNull()?.message}"
                statePushError != null -> "Config synced (HA state error: $statePushError)"
                watches.isEmpty() -> "Synced to Data Layer (no watch currently reachable)"
                else -> "Synced to ${watches.joinToString { it.displayName }}"
            }
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    connectedWatches = watches,
                    syncStatus = statusMsg,
                )
            }
        }
    }
}
