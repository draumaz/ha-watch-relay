package xyz.draumaz.hawidget.mobile.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.draumaz.hawidget.mobile.data.ConfigRepository
import xyz.draumaz.hawidget.mobile.ha.CameraImageProcessor
import xyz.draumaz.hawidget.mobile.ha.HaApiClient
import xyz.draumaz.hawidget.shared.CameraFrameMeta
import xyz.draumaz.hawidget.shared.CameraFrameRequest
import xyz.draumaz.hawidget.shared.DataLayerPaths
import xyz.draumaz.hawidget.shared.EntityActionRequest
import xyz.draumaz.hawidget.shared.EntityStatesSnapshot
import xyz.draumaz.hawidget.shared.HaJson

/**
 * Background relay running on the phone. Receives watch requests over the Wearable
 * Data Layer API and proxies them to Home Assistant using the phone's network stack
 * (including any active VPN such as Tailscale).
 */
class WatchRelayService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configRepo by lazy { ConfigRepository(applicationContext) }
    private val haClient by lazy { HaApiClient() }
    private val syncManager by lazy { WearSyncManager(applicationContext) }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        val sourceNodeId = messageEvent.sourceNodeId
        Log.d(TAG, "Received watch message: $path from $sourceNodeId")

        when (path) {
            DataLayerPaths.CMD_PING -> {
                serviceScope.launch {
                    syncManager.sendPong(sourceNodeId)
                    syncManager.pushConfig(configRepo.getWatchConfig())
                }
            }

            DataLayerPaths.CMD_REFRESH_STATES -> {
                serviceScope.launch { handleRefreshStates() }
            }

            DataLayerPaths.CMD_ENTITY_ACTION -> {
                val payload = messageEvent.data.toString(Charsets.UTF_8)
                serviceScope.launch { handleEntityAction(payload) }
            }

            DataLayerPaths.CMD_REQUEST_CAMERA_FRAME -> {
                val payload = messageEvent.data.toString(Charsets.UTF_8)
                serviceScope.launch { handleCameraRequest(payload, sourceNodeId) }
            }
        }
    }

    private suspend fun handleRefreshStates() {
        val creds = configRepo.getCredentials()
        val config = configRepo.getWatchConfig()
        if (!creds.isValid) {
            syncManager.pushEntityStates(
                EntityStatesSnapshot(
                    updatedAtEpochMs = System.currentTimeMillis(),
                    error = "Home Assistant not configured on phone",
                ),
            )
            return
        }

        val entityIds = config.selectedEntities.map { it.entityId }
        val result = haClient.fetchEntityStates(creds, entityIds)
        val snapshot = result.fold(
            onSuccess = { states ->
                EntityStatesSnapshot(
                    states = states,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            },
            onFailure = { err ->
                Log.w(TAG, "Failed to fetch HA states", err)
                EntityStatesSnapshot(
                    updatedAtEpochMs = System.currentTimeMillis(),
                    error = err.message ?: "Unable to reach Home Assistant",
                )
            },
        )
        syncManager.pushEntityStates(snapshot)
    }

    private suspend fun handleEntityAction(payloadJson: String) {
        val creds = configRepo.getCredentials()
        if (!creds.isValid) {
            syncManager.pushEntityStates(
                EntityStatesSnapshot(
                    updatedAtEpochMs = System.currentTimeMillis(),
                    error = "Home Assistant not configured on phone",
                ),
            )
            return
        }

        val action = runCatching {
            HaJson.decodeFromString<EntityActionRequest>(payloadJson)
        }.getOrNull() ?: return

        val callResult = haClient.executeEntityAction(creds, action)
        if (callResult.isFailure) {
            Log.w(TAG, "HA service call failed for ${action.entityId}", callResult.exceptionOrNull())
            syncManager.pushEntityStates(
                EntityStatesSnapshot(
                    updatedAtEpochMs = System.currentTimeMillis(),
                    error = callResult.exceptionOrNull()?.message ?: "Action failed",
                ),
            )
            return
        }

        // Allow HA state machine & device integration to settle before re-querying
        delay(550)
        handleRefreshStates()
    }

    private suspend fun handleCameraRequest(payloadJson: String, sourceNodeId: String) {
        val creds = configRepo.getCredentials()
        val config = configRepo.getWatchConfig()
        val req = runCatching {
            if (payloadJson.isNotBlank()) {
                HaJson.decodeFromString<CameraFrameRequest>(payloadJson)
            } else {
                null
            }
        }.getOrNull()

        val entityId = req?.entityId?.takeIf { it.isNotBlank() } ?: config.cameraEntityId
        val maxWidth = req?.maxWidthPx ?: config.cameraQuality.maxWidthPx
        val quality = req?.jpegQuality ?: config.cameraQuality.jpegQuality
        val preferFast = req?.preferFastMessage ?: false

        if (!creds.isValid || entityId.isNullOrBlank()) {
            val meta = CameraFrameMeta(
                entityId = entityId.orEmpty(),
                timestampEpochMs = System.currentTimeMillis(),
                error = if (!creds.isValid) "HA not configured" else "No camera selected",
            )
            syncManager.pushCameraFrame(null, meta, sourceNodeId, preferFast)
            return
        }

        val result = haClient.fetchCameraSnapshot(creds, entityId)
        result.fold(
            onSuccess = { rawBytes ->
                val processed = CameraImageProcessor.processForWatch(rawBytes, maxWidth, quality)
                val meta = CameraFrameMeta(
                    entityId = entityId,
                    timestampEpochMs = System.currentTimeMillis(),
                    width = processed.width,
                    height = processed.height,
                    sizeBytes = processed.jpegBytes.size,
                )
                syncManager.pushCameraFrame(processed.jpegBytes, meta, sourceNodeId, preferFast)
            },
            onFailure = { err ->
                Log.w(TAG, "Camera snapshot failed for $entityId", err)
                val meta = CameraFrameMeta(
                    entityId = entityId,
                    timestampEpochMs = System.currentTimeMillis(),
                    error = err.message ?: "Camera unreachable",
                )
                syncManager.pushCameraFrame(null, meta, sourceNodeId, preferFast)
            },
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "WatchRelayService"
    }
}
