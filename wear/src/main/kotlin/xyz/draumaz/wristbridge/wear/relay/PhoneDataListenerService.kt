package xyz.draumaz.wristbridge.wear.relay

import android.util.Log
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import xyz.draumaz.wristbridge.shared.CameraFrameMeta
import xyz.draumaz.wristbridge.shared.DataLayerPaths
import xyz.draumaz.wristbridge.shared.EntityStatesSnapshot
import xyz.draumaz.wristbridge.shared.HaJson
import xyz.draumaz.wristbridge.shared.WatchConfig
import xyz.draumaz.wristbridge.wear.data.WearLocalStore
import xyz.draumaz.wristbridge.wear.tiles.CameraTileService
import xyz.draumaz.wristbridge.wear.tiles.ControlsTileService

/**
 * Listens for synced DataItems and fast-path Messages from the phone companion app,
 * persists them to [WearLocalStore], and requests immediate Wear OS Tile refreshes.
 */
class PhoneDataListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val appCtx = applicationContext
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            val path = item.uri.path ?: continue
            val dataMap = DataMapItem.fromDataItem(item).dataMap

            when (path) {
                DataLayerPaths.DATA_CONFIG -> {
                    val json = dataMap.getString(DataLayerPaths.KEY_JSON) ?: continue
                    runCatching { HaJson.decodeFromString<WatchConfig>(json) }
                        .onSuccess { cfg ->
                            WearLocalStore.saveConfig(appCtx, cfg)
                            requestControlsTileUpdate()
                            requestCameraTileUpdate()
                        }
                }

                DataLayerPaths.DATA_ENTITY_STATES -> {
                    val json = dataMap.getString(DataLayerPaths.KEY_JSON) ?: continue
                    runCatching { HaJson.decodeFromString<EntityStatesSnapshot>(json) }
                        .onSuccess { snap ->
                            val prev = WearLocalStore.readStates(appCtx)
                            val statesUnchanged = prev.states.size == snap.states.size &&
                                prev.states.zip(snap.states).all { (a, b) ->
                                    a.entityId == b.entityId && a.state.equals(b.state, ignoreCase = true)
                                }
                            WearLocalStore.saveStates(appCtx, snap)
                            // Authoritative state from the phone: skip tile redraw ONLY if it
                            // matches what we just optimistically animated < 900ms ago.
                            if (!(statesUnchanged && WearLocalStore.wasRecentlyToggled(withinMs = 900L))) {
                                requestControlsTileUpdate()
                            }
                        }
                }

                DataLayerPaths.DATA_CAMERA_FRAME -> {
                    val metaJson = dataMap.getString(DataLayerPaths.KEY_CAMERA_META) ?: continue
                    val meta = runCatching { HaJson.decodeFromString<CameraFrameMeta>(metaJson) }.getOrNull()
                        ?: continue
                    val asset = dataMap.getAsset(DataLayerPaths.KEY_CAMERA_ASSET)
                    scope.launch {
                        val bytes = asset?.let { a ->
                            runCatching {
                                Wearable.getDataClient(appCtx).getFdForAsset(a).await()
                                    .inputStream.use { it.readBytes() }
                            }.getOrNull()
                        }
                        WearLocalStore.saveCameraFrame(appCtx, meta, bytes)
                        requestCameraTileUpdate()
                    }
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val appCtx = applicationContext
        when (messageEvent.path) {
            DataLayerPaths.RESP_CAMERA_FRAME_BYTES -> {
                val bytes = messageEvent.data
                if (bytes.isNotEmpty()) {
                    val cfg = WearLocalStore.readConfig(appCtx)
                    val meta = CameraFrameMeta(
                        entityId = cfg.cameraEntityId.orEmpty(),
                        timestampEpochMs = System.currentTimeMillis(),
                        sizeBytes = bytes.size,
                    )
                    WearLocalStore.saveCameraFrame(appCtx, meta, bytes)
                    requestCameraTileUpdate()
                }
            }

            DataLayerPaths.RESP_CAMERA_ERROR -> {
                val err = messageEvent.data.toString(Charsets.UTF_8)
                val cfg = WearLocalStore.readConfig(appCtx)
                val meta = CameraFrameMeta(
                    entityId = cfg.cameraEntityId.orEmpty(),
                    timestampEpochMs = System.currentTimeMillis(),
                    error = err.ifBlank { "Camera error" },
                )
                WearLocalStore.saveCameraFrame(appCtx, meta, null)
                requestCameraTileUpdate()
            }
        }
    }

    private fun requestControlsTileUpdate() {
        runCatching {
            TileService.getUpdater(applicationContext).requestUpdate(ControlsTileService::class.java)
        }.onFailure { Log.w(TAG, "Controls tile update request failed", it) }
    }

    private fun requestCameraTileUpdate() {
        runCatching {
            TileService.getUpdater(applicationContext).requestUpdate(CameraTileService::class.java)
        }.onFailure { Log.w(TAG, "Camera tile update request failed", it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "PhoneDataListener"
    }
}
