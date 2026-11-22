package xyz.draumaz.hawidget.wear.relay

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import xyz.draumaz.hawidget.shared.CameraFrameMeta
import xyz.draumaz.hawidget.shared.CameraFrameRequest
import xyz.draumaz.hawidget.shared.DataLayerPaths
import xyz.draumaz.hawidget.shared.EntityActionRequest
import xyz.draumaz.hawidget.shared.EntityStatesSnapshot
import xyz.draumaz.hawidget.shared.HaJson
import xyz.draumaz.hawidget.shared.WatchConfig
import xyz.draumaz.hawidget.wear.data.WearLocalStore

/**
 * Watch-side Wearable Data Layer client. All Home Assistant interactions are
 * sent as messages to the paired phone, never directly over the watch's network.
 */
class PhoneRelayClient(context: Context) {

    private val appContext = context.applicationContext
    private val capabilityClient by lazy { Wearable.getCapabilityClient(appContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(appContext) }
    private val messageClient by lazy { Wearable.getMessageClient(appContext) }
    private val dataClient by lazy { Wearable.getDataClient(appContext) }

    suspend fun findPhoneNodeId(): String? {
        return runCatching {
            val capInfo = capabilityClient
                .getCapability(DataLayerPaths.CAPABILITY_MOBILE_RELAY, CapabilityClient.FILTER_REACHABLE)
                .await()
            val best = capInfo.nodes.firstOrNull { it.isNearby } ?: capInfo.nodes.firstOrNull()
            if (best != null) return@runCatching best.id

            val fallback = nodeClient.connectedNodes.await()
            (fallback.firstOrNull { it.isNearby } ?: fallback.firstOrNull())?.id
        }.getOrElse { e ->
            Log.w(TAG, "Failed to locate phone node", e)
            null
        }
    }

    suspend fun isPhoneReachable(): Boolean = findPhoneNodeId() != null

    /**
     * Pulls any already-synced DataItems from Google Play Services DataClient
     * (useful on first launch or after reboot).
     */
    suspend fun hydrateFromDataClient() {
        runCatching {
            val buffer = dataClient.getDataItems(Uri.parse("wear://*${DataLayerPaths.DATA_CONFIG}")).await()
            buffer.use { items ->
                for (item in items) {
                    val json = DataMapItem.fromDataItem(item).dataMap.getString(DataLayerPaths.KEY_JSON)
                    if (json != null) {
                        val cfg = HaJson.decodeFromString<WatchConfig>(json)
                        WearLocalStore.saveConfig(appContext, cfg)
                    }
                }
            }
        }
        runCatching {
            val buffer = dataClient.getDataItems(Uri.parse("wear://*${DataLayerPaths.DATA_ENTITY_STATES}")).await()
            buffer.use { items ->
                for (item in items) {
                    val json = DataMapItem.fromDataItem(item).dataMap.getString(DataLayerPaths.KEY_JSON)
                    if (json != null) {
                        val snap = HaJson.decodeFromString<EntityStatesSnapshot>(json)
                        WearLocalStore.saveStates(appContext, snap)
                    }
                }
            }
        }
        runCatching {
            val buffer = dataClient.getDataItems(Uri.parse("wear://*${DataLayerPaths.DATA_CAMERA_FRAME}")).await()
            buffer.use { items ->
                for (item in items) {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val metaJson = dataMap.getString(DataLayerPaths.KEY_CAMERA_META) ?: continue
                    val meta = HaJson.decodeFromString<CameraFrameMeta>(metaJson)
                    val asset = dataMap.getAsset(DataLayerPaths.KEY_CAMERA_ASSET)
                    val bytes = asset?.let {
                        runCatching {
                            dataClient.getFdForAsset(it).await().inputStream.use { stream ->
                                stream.readBytes()
                            }
                        }.getOrNull()
                    }
                    WearLocalStore.saveCameraFrame(appContext, meta, bytes)
                }
            }
        }
    }

    suspend fun pingPhone(): Boolean = sendCommand(DataLayerPaths.CMD_PING, ByteArray(0))

    suspend fun requestRefreshStates(): Boolean =
        sendCommand(DataLayerPaths.CMD_REFRESH_STATES, ByteArray(0))

    suspend fun sendEntityAction(request: EntityActionRequest): Boolean {
        val bytes = HaJson.encodeToString(request).toByteArray(Charsets.UTF_8)
        return sendCommand(DataLayerPaths.CMD_ENTITY_ACTION, bytes)
    }

    suspend fun requestCameraFrame(
        entityId: String,
        maxWidthPx: Int,
        jpegQuality: Int,
        preferFastMessage: Boolean,
    ): Boolean {
        val req = CameraFrameRequest(
            entityId = entityId,
            maxWidthPx = maxWidthPx,
            jpegQuality = jpegQuality,
            preferFastMessage = preferFastMessage,
        )
        val bytes = HaJson.encodeToString(req).toByteArray(Charsets.UTF_8)
        return sendCommand(DataLayerPaths.CMD_REQUEST_CAMERA_FRAME, bytes)
    }

    private suspend fun sendCommand(path: String, payload: ByteArray): Boolean {
        val nodeId = findPhoneNodeId() ?: return false
        return runCatching {
            messageClient.sendMessage(nodeId, path, payload).await()
            true
        }.getOrElse { e ->
            Log.w(TAG, "Failed sending $path to phone node $nodeId", e)
            false
        }
    }

    companion object {
        private const val TAG = "PhoneRelayClient"
    }
}
