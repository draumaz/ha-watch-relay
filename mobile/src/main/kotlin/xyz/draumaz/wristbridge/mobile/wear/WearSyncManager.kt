package xyz.draumaz.wristbridge.mobile.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import xyz.draumaz.wristbridge.shared.CameraFrameMeta
import xyz.draumaz.wristbridge.shared.DataLayerPaths
import xyz.draumaz.wristbridge.shared.EntityStatesSnapshot
import xyz.draumaz.wristbridge.shared.HaJson
import xyz.draumaz.wristbridge.shared.WatchConfig

data class ConnectedWatchInfo(
    val nodeId: String,
    val displayName: String,
    val isNearby: Boolean,
)

class WearSyncManager(context: Context) {

    private val appContext = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }

    suspend fun getConnectedWatches(): List<ConnectedWatchInfo> {
        return runCatching {
            val capInfo = capabilityClient
                .getCapability(DataLayerPaths.CAPABILITY_WEAR_CLIENT, CapabilityClient.FILTER_REACHABLE)
                .await()
            val nodes = capInfo.nodes.ifEmpty { nodeClient.connectedNodes.await().toSet() }
            nodes.map { ConnectedWatchInfo(it.id, it.displayName, it.isNearby) }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to query connected watches", e)
            emptyList()
        }
    }

    suspend fun pushConfig(config: WatchConfig): Result<Unit> = runCatching {
        val req = PutDataMapRequest.create(DataLayerPaths.DATA_CONFIG).apply {
            dataMap.putString(DataLayerPaths.KEY_JSON, HaJson.encodeToString(config))
            dataMap.putLong(DataLayerPaths.KEY_UPDATED_AT, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(req).await()
    }

    suspend fun pushEntityStates(snapshot: EntityStatesSnapshot): Result<Unit> = runCatching {
        val req = PutDataMapRequest.create(DataLayerPaths.DATA_ENTITY_STATES).apply {
            dataMap.putString(DataLayerPaths.KEY_JSON, HaJson.encodeToString(snapshot))
            dataMap.putLong(DataLayerPaths.KEY_UPDATED_AT, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(req).await()
    }

    suspend fun pushCameraFrame(
        jpegBytes: ByteArray?,
        meta: CameraFrameMeta,
        targetNodeId: String? = null,
        preferFastMessage: Boolean = false,
    ): Result<Unit> = runCatching {
        // Fast-path direct MessageClient delivery for active live-view polling when compact enough
        if (preferFastMessage &&
            targetNodeId != null &&
            jpegBytes != null &&
            jpegBytes.size <= DataLayerPaths.MAX_DIRECT_MESSAGE_FRAME_BYTES
        ) {
            runCatching {
                messageClient.sendMessage(
                    targetNodeId,
                    DataLayerPaths.RESP_CAMERA_FRAME_BYTES,
                    jpegBytes,
                ).await()
            }.onFailure {
                Log.w(TAG, "Fast camera message failed; falling back to DataClient", it)
            }
        } else if (preferFastMessage && targetNodeId != null && meta.error != null) {
            runCatching {
                messageClient.sendMessage(
                    targetNodeId,
                    DataLayerPaths.RESP_CAMERA_ERROR,
                    meta.error.orEmpty().toByteArray(Charsets.UTF_8),
                ).await()
            }
        }

        // Always update DataClient so the Wear OS Camera Tile has the freshest cached frame
        val req = PutDataMapRequest.create(DataLayerPaths.DATA_CAMERA_FRAME).apply {
            dataMap.putString(DataLayerPaths.KEY_CAMERA_META, HaJson.encodeToString(meta))
            dataMap.putLong(DataLayerPaths.KEY_UPDATED_AT, meta.timestampEpochMs)
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                dataMap.putAsset(DataLayerPaths.KEY_CAMERA_ASSET, Asset.createFromBytes(jpegBytes))
            }
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(req).await()
    }

    suspend fun sendPong(nodeId: String) {
        runCatching {
            messageClient.sendMessage(nodeId, DataLayerPaths.RESP_PONG, ByteArray(0)).await()
        }
    }

    companion object {
        private const val TAG = "WearSyncManager"
    }
}
