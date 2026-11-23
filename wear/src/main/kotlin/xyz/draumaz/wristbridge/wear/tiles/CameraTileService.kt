package xyz.draumaz.wristbridge.wear.tiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.graphics.scale
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.draumaz.wristbridge.shared.CameraFrameMeta
import xyz.draumaz.wristbridge.shared.WatchConfig
import xyz.draumaz.wristbridge.wear.data.WearLocalStore
import xyz.draumaz.wristbridge.wear.relay.PhoneRelayClient
import xyz.draumaz.wristbridge.wear.ui.CameraLiveActivity
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Wear OS Tile 2: Security camera viewer.
 * - Requests a fresh snapshot via the phone whenever the tile is entered or Refresh is tapped.
 * - Tapping the camera image launches [CameraLiveActivity] for active polling at the configured refresh rate.
 */
class CameraTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relay by lazy { PhoneRelayClient(applicationContext) }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        scope.launch {
            relay.hydrateFromDataClient()
            requestFreshFrameFromPhone()
        }
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        return CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                try {
                    val clickId = requestParams.currentState.lastClickableId
                    var phoneOffline = false

                    if (clickId == CLICK_REFRESH_CAMERA) {
                        phoneOffline = !requestFreshFrameFromPhone()
                    } else {
                        relay.hydrateFromDataClient()
                        phoneOffline = !relay.isPhoneReachable()
                        // Also trigger a background refresh if cached frame is stale (> configured interval)
                        val cfg = WearLocalStore.readConfig(applicationContext)
                        val meta = WearLocalStore.readCameraMeta(applicationContext)
                        val ageMs = if (meta != null) System.currentTimeMillis() - meta.timestampEpochMs else Long.MAX_VALUE
                        if (!phoneOffline && cfg.cameraEntityId != null && ageMs > cfg.cameraRefreshIntervalSec * 1000L) {
                            requestFreshFrameFromPhone()
                        }
                    }

                    val cfg = WearLocalStore.readConfig(applicationContext)
                    val meta = WearLocalStore.readCameraMeta(applicationContext)
                    val hasImage = WearLocalStore.readCameraBytes(applicationContext) != null
                    val resVersion = "cam_${meta?.timestampEpochMs ?: 0L}_${meta?.sizeBytes ?: 0}"

                    val layout = buildCameraLayout(cfg, meta, hasImage, phoneOffline)
                    // Respect Wear OS minimum tile freshness while keeping it as responsive as allowed
                    val freshnessMs = (cfg.cameraRefreshIntervalSec.coerceAtLeast(20)) * 1000L

                    val tile = TileBuilders.Tile.Builder()
                        .setResourcesVersion(resVersion)
                        .setFreshnessIntervalMillis(freshnessMs)
                        .setTileTimeline(
                            TimelineBuilders.Timeline.Builder()
                                .addTimelineEntry(
                                    TimelineBuilders.TimelineEntry.Builder()
                                        .setLayout(
                                            LayoutElementBuilders.Layout.Builder()
                                                .setRoot(layout)
                                                .build(),
                                        )
                                        .build(),
                                )
                                .build(),
                        )
                        .build()

                    completer.set(tile)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to build camera tile", e)
                    completer.setException(e)
                }
            }
            "CameraTileService.onTileRequest"
        }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        return CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                val builder = ResourceBuilders.Resources.Builder()
                    .setVersion(requestParams.version)

                val jpegBytes = WearLocalStore.readCameraBytes(applicationContext)
                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    // A corrupt/partial frame must not leave the tile without resources.
                    runCatching { decodeToInlineImage(jpegBytes, MAX_TILE_IMAGE_DIM) }
                        .onFailure { Log.w(TAG, "Failed to decode camera frame", it) }
                        .getOrNull()
                        ?.let { inlineImg ->
                            builder.addIdToImageMapping(
                                RES_CAMERA_SNAPSHOT,
                                ResourceBuilders.ImageResource.Builder()
                                    .setInlineResource(inlineImg)
                                    .build(),
                            )
                        }
                }

                completer.set(builder.build())
            }
            "CameraTileService.onTileResourcesRequest"
        }
    }

    private suspend fun requestFreshFrameFromPhone(): Boolean {
        val cfg = WearLocalStore.readConfig(applicationContext)
        val cameraId = cfg.cameraEntityId ?: return relay.isPhoneReachable()
        return relay.requestCameraFrame(
            entityId = cameraId,
            maxWidthPx = cfg.cameraQuality.maxWidthPx,
            jpegQuality = cfg.cameraQuality.jpegQuality,
            preferFastMessage = false,
        )
    }

    private fun buildCameraLayout(
        config: WatchConfig,
        meta: CameraFrameMeta?,
        hasImage: Boolean,
        phoneOffline: Boolean,
    ): LayoutElementBuilders.LayoutElement {
        val launchLiveAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(CameraLiveActivity::class.java.name)
                    .build(),
            )
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF000000.toInt()))
                            .build(),
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(CLICK_OPEN_LIVE)
                            .setOnClick(launchLiveAction)
                            .build(),
                    )
                    .build(),
            )

        if (hasImage && config.cameraEntityId != null) {
            root.addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(RES_CAMERA_SNAPSHOT)
                    .setWidth(expand())
                    .setHeight(expand())
                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_CROP)
                    .build(),
            )
        } else {
            val emptyMsg = when {
                !config.isHaConfigured -> "Set up HA on phone"
                config.cameraEntityId == null -> "Select a camera\nin the phone app"
                phoneOffline -> "Phone offline"
                meta?.error != null -> meta.error!!
                else -> "Tap Refresh for snapshot"
            }
            root.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(emptyMsg)
                    .setMaxLines(3)
                    .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(0xFFB0BEC5.toInt()))
                            .build(),
                    )
                    .build(),
            )
        }

        // Top & bottom overlay HUD
        val overlay = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(dp(16f))
                            .setBottom(dp(14f))
                            .setStart(dp(12f))
                            .setEnd(dp(12f))
                            .build(),
                    )
                    .build(),
            )

        // Top badge: camera name
        val title = config.cameraName.ifBlank { "HA Camera" }
        overlay.addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(wrap())
                .setHeight(wrap())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(argb(0xAA000000.toInt()))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(10f)).build())
                                .build(),
                        )
                        .setPadding(
                            ModifiersBuilders.Padding.Builder()
                                .setStart(dp(8f))
                                .setEnd(dp(8f))
                                .setTop(dp(2f))
                                .setBottom(dp(2f))
                                .build(),
                        )
                        .build(),
                )
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(title)
                        .setMaxLines(1)
                        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(11f))
                                .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                .setColor(argb(0xFFFFFFFF.toInt()))
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )

        // Middle spacer pushes controls to the bottom
        overlay.addContent(
            LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .build(),
        )

        // Bottom controls: Live & Refresh pills + age
        val ageLabel = when {
            phoneOffline -> "Phone offline"
            meta?.error != null -> meta.error!!.take(20)
            meta != null -> "${WearLocalStore.formatAge(meta.timestampEpochMs)} • ${config.cameraRefreshIntervalSec}s live"
            else -> "Tap Live"
        }

        val btnRow = LayoutElementBuilders.Row.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .addContent(
                buildHudPill("Live", CLICK_OPEN_LIVE, launchLiveAction, 0xCC0288D1.toInt()),
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
            .addContent(
                buildHudPill("Refresh", CLICK_REFRESH_CAMERA, ActionBuilders.LoadAction.Builder().build(), 0xCC263238.toInt()),
            )
            .build()

        overlay.addContent(btnRow)
        overlay.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
        overlay.addContent(
            LayoutElementBuilders.Box.Builder()
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(argb(0xAA000000.toInt()))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(8f)).build())
                                .build(),
                        )
                        .setPadding(
                            ModifiersBuilders.Padding.Builder()
                                .setStart(dp(6f))
                                .setEnd(dp(6f))
                                .setTop(dp(1f))
                                .setBottom(dp(1f))
                                .build(),
                        )
                        .build(),
                )
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(ageLabel)
                        .setMaxLines(1)
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(9f))
                                .setColor(argb(if (phoneOffline || meta?.error != null) 0xFFFF8A80.toInt() else 0xFFE0E0E0.toInt()))
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )

        root.addContent(overlay.build())
        return root.build()
    }

    private fun buildHudPill(
        text: String,
        clickableId: String,
        action: ActionBuilders.Action,
        bgColor: Int,
    ): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Box.Builder()
            .setWidth(wrap())
            .setHeight(dp(24f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(12f)).build())
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(10f))
                            .setEnd(dp(10f))
                            .build(),
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(clickableId)
                            .setOnClick(action)
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(text)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(10f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun decodeToInlineImage(
        jpegBytes: ByteArray,
        maxDim: Int,
    ): ResourceBuilders.InlineImageResource? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > maxDim) {
            val ratio = maxDim.toFloat() / longest.toFloat()
            val w = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
            val h = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
            decoded.scale(w, h).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        val rgb565 = if (scaled.config == Bitmap.Config.RGB_565) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.RGB_565, false).also { scaled.recycle() }
        }
        val buf = ByteBuffer.allocate(rgb565.byteCount)
        rgb565.copyPixelsToBuffer(buf)
        val width = rgb565.width
        val height = rgb565.height
        rgb565.recycle()

        return ResourceBuilders.InlineImageResource.Builder()
            .setData(buf.array())
            .setWidthPx(width)
            .setHeightPx(height)
            .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "CameraTileService"
        private const val RES_CAMERA_SNAPSHOT = "ha_camera_snapshot"
        private const val CLICK_REFRESH_CAMERA = "refresh_camera"
        private const val CLICK_OPEN_LIVE = "open_live_camera"
        private const val MAX_TILE_IMAGE_DIM = 360
    }
}
