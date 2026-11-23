package xyz.draumaz.wristbridge.wear.tiles

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.TypeBuilders
import androidx.wear.protolayout.expression.AnimationParameterBuilders
import androidx.wear.protolayout.expression.DynamicBuilders
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
import xyz.draumaz.wristbridge.shared.EntityActionRequest
import xyz.draumaz.wristbridge.shared.EntityStatesSnapshot
import xyz.draumaz.wristbridge.shared.HaEntityConfig
import xyz.draumaz.wristbridge.shared.WatchConfig
import xyz.draumaz.wristbridge.wear.R
import xyz.draumaz.wristbridge.wear.data.WearLocalStore
import xyz.draumaz.wristbridge.wear.relay.PhoneRelayClient
import xyz.draumaz.wristbridge.wear.ui.WearHaptics

/**
 * Wear OS Tile 1: Minimalist glyph-only Material 3 Expressive scallop buttons.
 * No header, no ON/OFF text — just tactile scalloped glyph buttons that
 * fill in when active and empty out (outlined scallop) when inactive,
 * with instant non-blocking tap response, springy kinetic bounce, and haptics.
 */
class ControlsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relay by lazy { PhoneRelayClient(applicationContext) }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        scope.launch {
            relay.hydrateFromDataClient()
            relay.requestRefreshStates()
        }
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        return CallbackToFutureAdapter.getFuture { completer ->
            val clickId = requestParams.currentState.lastClickableId
            var justToggledEntityId: String? = null

            if (clickId.startsWith(CLICK_TOGGLE_PREFIX)) {
                val entityId = clickId.removePrefix(CLICK_TOGGLE_PREFIX)
                justToggledEntityId = entityId

                val cfg = WearLocalStore.readConfig(applicationContext)
                val entityCfg = cfg.selectedEntities.firstOrNull { it.entityId == entityId }
                val prevState = WearLocalStore.readStates(applicationContext).stateFor(entityId)
                val wasOn = prevState?.isOn == true
                val isMomentary = entityCfg?.isMomentary == true

                // Immediate expressive haptic feedback on tap
                WearHaptics.playToggle(
                    context = applicationContext,
                    turningOn = !wasOn,
                    isMomentary = isMomentary,
                )

                if (entityCfg != null && !isMomentary) {
                    WearLocalStore.applyOptimisticToggle(applicationContext, entityId)
                } else {
                    WearLocalStore.markOptimisticToggle()
                }

                // Fire phone relay asynchronously so onTileRequest returns instantaneously (<5ms)
                // and the kinetic animation is never stalled or dropped waiting on Bluetooth/HA.
                val actionReq = EntityActionRequest(
                    entityId = entityId,
                    domain = entityCfg?.domain ?: entityId.substringBefore('.', ""),
                    currentState = prevState?.state,
                )
                scope.launch {
                    val delivered = relay.sendEntityAction(actionReq)
                    if (!delivered && WearLocalStore.revertOptimisticToggle(applicationContext, PHONE_UNREACHABLE)) {
                        runCatching { getUpdater(applicationContext).requestUpdate(ControlsTileService::class.java) }
                    }
                }
            } else {
                // Hydrate asynchronously in background so tile rendering is never blocked
                scope.launch {
                    relay.hydrateFromDataClient()
                }
            }

            val config = WearLocalStore.readConfig(applicationContext)
            val snapshot = WearLocalStore.readStates(applicationContext)

            val layout = buildMinimalistLayout(config, snapshot, justToggledEntityId)
            val tile = TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(60_000L)
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
            "ControlsTileService.onTileRequest"
        }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        return CallbackToFutureAdapter.getFuture { completer ->
            val builder = ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)

            RESOURCE_MAP.forEach { (id, drawableRes) ->
                builder.addIdToImageMapping(
                    id,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(drawableRes)
                                .build(),
                        )
                        .build(),
                )
            }

            completer.set(builder.build())
            "ControlsTileService.onTileResourcesRequest"
        }
    }

    private fun buildMinimalistLayout(
        config: WatchConfig,
        snapshot: EntityStatesSnapshot,
        justToggledEntityId: String?,
    ): LayoutElementBuilders.LayoutElement {
        val entities = config.selectedEntities.take(6)

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(COLOR_BG))
                            .build(),
                    )
                    .build(),
            )

        if (!config.isHaConfigured || entities.isEmpty()) {
            root.addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(if (!config.isHaConfigured) "Connect on phone" else "Pick buttons on phone")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12f))
                            .setColor(argb(COLOR_MUTED))
                            .build(),
                    )
                    .build(),
            )
            return root.build()
        }

        // Cozy Material 3 Expressive diagonal stagger when there are exactly 2 buttons:
        // bottom-left lower, top-right higher, chunkier scallops.
        if (entities.size == 2) {
            val buttonSizeDp = 86f
            val iconSizeDp = 32f
            val staggerOffsetDp = 28f

            val bottomLeft = LayoutElementBuilders.Box.Builder()
                .setWidth(wrap())
                .setHeight(wrap())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(
                            ModifiersBuilders.Padding.Builder()
                                .setTop(dp(staggerOffsetDp))
                                .build(),
                        )
                        .build(),
                )
                .addContent(
                    buildScallopButton(
                        entity = entities[0],
                        variantIndex = 0,
                        snapshot = snapshot,
                        sizeDp = buttonSizeDp,
                        iconDp = iconSizeDp,
                        animateKinetic = entities[0].entityId == justToggledEntityId,
                    ),
                )
                .build()

            val topRight = LayoutElementBuilders.Box.Builder()
                .setWidth(wrap())
                .setHeight(wrap())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(
                            ModifiersBuilders.Padding.Builder()
                                .setBottom(dp(staggerOffsetDp))
                                .build(),
                        )
                        .build(),
                )
                .addContent(
                    buildScallopButton(
                        entity = entities[1],
                        variantIndex = 1,
                        snapshot = snapshot,
                        sizeDp = buttonSizeDp,
                        iconDp = iconSizeDp,
                        animateKinetic = entities[1].entityId == justToggledEntityId,
                    ),
                )
                .build()

            val diagonalRow = LayoutElementBuilders.Row.Builder()
                .setWidth(wrap())
                .setHeight(wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(bottomLeft)
                .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(2f)).build())
                .addContent(topRight)
                .build()

            root.addContent(diagonalRow)
            return root.build()
        }

        // Balanced scallop grid for 1 or 3..6 buttons:
        // 1 or 3 -> 1 row; 4 -> 2x2; 5-6 -> 3 + (2 or 3)
        val rows: List<List<HaEntityConfig>> = when (entities.size) {
            1, 3 -> listOf(entities)
            4 -> entities.chunked(2)
            else -> listOf(entities.take(3), entities.drop(3))
        }

        val buttonSizeDp = when (entities.size) {
            1 -> 96f
            in 3..4 -> 68f
            else -> 56f
        }
        val iconSizeDp = when (entities.size) {
            1 -> 36f
            in 3..4 -> 26f
            else -> 22f
        }
        val spacingDp = if (entities.size <= 4) 10f else 6f

        val grid = LayoutElementBuilders.Column.Builder()
            .setWidth(wrap())
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

        var slotIndex = 0
        rows.forEachIndexed { rowIdx, rowItems ->
            val row = LayoutElementBuilders.Row.Builder()
                .setWidth(wrap())
                .setHeight(wrap())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)

            rowItems.forEachIndexed { colIdx, entity ->
                if (colIdx > 0) {
                    row.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(spacingDp)).build())
                }
                val currentSlot = slotIndex++
                row.addContent(
                    buildScallopButton(
                        entity = entity,
                        variantIndex = currentSlot,
                        snapshot = snapshot,
                        sizeDp = buttonSizeDp,
                        iconDp = iconSizeDp,
                        animateKinetic = entity.entityId == justToggledEntityId,
                    ),
                )
            }

            grid.addContent(row.build())
            if (rowIdx < rows.lastIndex) {
                grid.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(spacingDp)).build())
            }
        }

        root.addContent(grid.build())
        return root.build()
    }

    private fun buildScallopButton(
        entity: HaEntityConfig,
        variantIndex: Int,
        snapshot: EntityStatesSnapshot,
        sizeDp: Float,
        iconDp: Float,
        animateKinetic: Boolean,
    ): LayoutElementBuilders.LayoutElement {
        val state = snapshot.stateFor(entity.entityId)
        val isFilled = !entity.isMomentary && state?.isOn == true
        val isUnavailable = state?.isUnavailable == true

        val variant = SCALLOP_VARIANTS[variantIndex % SCALLOP_VARIANTS.size]
        val scallopResId = if (isFilled) variant.filledId else variant.outlinedId
        val scallopTint = when {
            isFilled -> COLOR_FILLED
            isUnavailable -> COLOR_UNAVAILABLE
            else -> COLOR_OUTLINE_EMPTY
        }
        val prevScallopTint = if (isFilled) COLOR_OUTLINE_EMPTY else COLOR_FILLED

        val glyphTint = when {
            isFilled -> COLOR_GLYPH_FILLED
            isUnavailable -> COLOR_UNAVAILABLE
            else -> COLOR_GLYPH_EMPTY
        }
        val prevGlyphTint = if (isFilled) COLOR_GLYPH_EMPTY else COLOR_GLYPH_FILLED

        val scallopTintProp = if (animateKinetic && !entity.isMomentary) {
            ColorBuilders.ColorProp.Builder(scallopTint)
                .setDynamicValue(DynamicBuilders.DynamicColor.animate(prevScallopTint, scallopTint, COLOR_ANIM_SPEC))
                .build()
        } else {
            argb(scallopTint)
        }

        val glyphTintProp = if (animateKinetic && !entity.isMomentary) {
            ColorBuilders.ColorProp.Builder(glyphTint)
                .setDynamicValue(DynamicBuilders.DynamicColor.animate(prevGlyphTint, glyphTint, COLOR_ANIM_SPEC))
                .build()
        } else {
            argb(glyphTint)
        }

        val modifiersBuilder = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("$CLICK_TOGGLE_PREFIX${entity.entityId}")
                    .setOnClick(ActionBuilders.LoadAction.Builder().build())
                    .build(),
            )

        // Springy kinetic overshoot pop on the tapped scallop (0.76 -> ~1.10 -> 1.00)
        if (animateKinetic) {
            val scaleAnim = DynamicBuilders.DynamicFloat.animate(0.76f, 1.0f, SPRING_SCALE_SPEC)
            val scaleProp = TypeBuilders.FloatProp.Builder(1.0f)
                .setDynamicValue(scaleAnim)
                .build()
            modifiersBuilder.setTransformation(
                ModifiersBuilders.Transformation.Builder()
                    .setScaleX(scaleProp)
                    .setScaleY(scaleProp)
                    .build(),
            )
        }

        val scallopImageBuilder = @Suppress("DEPRECATION") LayoutElementBuilders.Image.Builder()
            .setResourceId(scallopResId)
            .setWidth(dp(sizeDp))
            .setHeight(dp(sizeDp))
            .setColorFilter(
                LayoutElementBuilders.ColorFilter.Builder()
                    .setTint(scallopTintProp)
                    .build(),
            )

        // Rev-up gear spin on the scallop outer ring only (glyph stays upright!).
        // Spin angle is an exact multiple of 360/lobes so it lands seamlessly on its symmetry.
        if (animateKinetic) {
            val targetDegrees = if (isFilled || entity.isMomentary) variant.spinDegrees else -variant.spinDegrees
            val spinAnim = DynamicBuilders.DynamicFloat.animate(0f, targetDegrees, GEAR_SPIN_SPEC)
            val rotationProp = DimensionBuilders.DegreesProp.Builder(targetDegrees)
                .setDynamicValue(spinAnim)
                .build()
            scallopImageBuilder.setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setTransformation(
                        ModifiersBuilders.Transformation.Builder()
                            .setRotation(rotationProp)
                            .build(),
                    )
                    .build(),
            )
        }
        val scallopLayer = scallopImageBuilder.build()

        val iconId = resolveIconId(entity, isFilled)

        @Suppress("DEPRECATION")
        val glyphLayer = LayoutElementBuilders.Image.Builder()
            .setResourceId(iconId)
            .setWidth(dp(iconDp))
            .setHeight(dp(iconDp))
            .setColorFilter(
                LayoutElementBuilders.ColorFilter.Builder()
                    .setTint(glyphTintProp)
                    .build(),
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(sizeDp))
            .setHeight(dp(sizeDp))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(modifiersBuilder.build())
            .addContent(scallopLayer)
            .addContent(glyphLayer)
            .build()
    }

    private fun resolveIconId(entity: HaEntityConfig, isOn: Boolean): String {
        return when (entity.domain.lowercase()) {
            "light" -> ICON_LIGHT
            "fan" -> ICON_FAN
            "lock" -> if (isOn) ICON_LOCK_OPEN else ICON_LOCK_CLOSED
            "cover" -> ICON_COVER
            "script" -> ICON_SCRIPT
            "scene" -> ICON_SCENE
            "button", "input_button" -> ICON_BUTTON
            else -> ICON_SWITCH
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val RESOURCES_VERSION = "controls_v4_scallops_varied"
        private const val PHONE_UNREACHABLE = "Phone unreachable"
        private const val CLICK_TOGGLE_PREFIX = "toggle:"

        private data class ScallopVariant(
            val filledId: String,
            val outlinedId: String,
            val filledRes: Int,
            val outlinedRes: Int,
            val spinDegrees: Float,
        )

        private val SCALLOP_VARIANTS = listOf(
            ScallopVariant("bg_scallop_0_f", "bg_scallop_0_o", R.drawable.bg_scallop_0_filled, R.drawable.bg_scallop_0_outlined, 2f * 360f / 7f),
            ScallopVariant("bg_scallop_1_f", "bg_scallop_1_o", R.drawable.bg_scallop_1_filled, R.drawable.bg_scallop_1_outlined, 2f * 360f / 9f),
            ScallopVariant("bg_scallop_2_f", "bg_scallop_2_o", R.drawable.bg_scallop_2_filled, R.drawable.bg_scallop_2_outlined, 3f * 360f / 12f),
            ScallopVariant("bg_scallop_3_f", "bg_scallop_3_o", R.drawable.bg_scallop_3_filled, R.drawable.bg_scallop_3_outlined, 2f * 360f / 6f),
            ScallopVariant("bg_scallop_4_f", "bg_scallop_4_o", R.drawable.bg_scallop_4_filled, R.drawable.bg_scallop_4_outlined, 2f * 360f / 10f),
            ScallopVariant("bg_scallop_5_f", "bg_scallop_5_o", R.drawable.bg_scallop_5_filled, R.drawable.bg_scallop_5_outlined, 2f * 360f / 8f),
        )

        // Material 3 Expressive spring-overshoot cubic bezier (snappy pop + elastic settle)
        private val SPRING_OVERSHOOT_EASING =
            AnimationParameterBuilders.Easing.cubicBezier(0.34f, 1.56f, 0.64f, 1.0f)

        private val SPRING_SCALE_SPEC = AnimationParameterBuilders.AnimationSpec.Builder()
            .setAnimationParameters(
                AnimationParameterBuilders.AnimationParameters.Builder()
                    .setDurationMillis(400L)
                    .setEasing(SPRING_OVERSHOOT_EASING)
                    .build(),
            )
            .build()

        // Gear rev-up curve: accelerates like a revving gear and settles with a slight kinetic overshoot
        private val GEAR_REV_EASING =
            AnimationParameterBuilders.Easing.cubicBezier(0.45f, 0.0f, 0.20f, 1.18f)

        private val GEAR_SPIN_SPEC = AnimationParameterBuilders.AnimationSpec.Builder()
            .setAnimationParameters(
                AnimationParameterBuilders.AnimationParameters.Builder()
                    .setDurationMillis(520L)
                    .setEasing(GEAR_REV_EASING)
                    .build(),
            )
            .build()

        private val COLOR_ANIM_SPEC = AnimationParameterBuilders.AnimationSpec.Builder()
            .setAnimationParameters(
                AnimationParameterBuilders.AnimationParameters.Builder()
                    .setDurationMillis(240L)
                    .build(),
            )
            .build()

        private const val ICON_LIGHT = "ic_ha_light"
        private const val ICON_SWITCH = "ic_ha_switch"
        private const val ICON_FAN = "ic_ha_fan"
        private const val ICON_LOCK_CLOSED = "ic_ha_lock_closed"
        private const val ICON_LOCK_OPEN = "ic_ha_lock_open"
        private const val ICON_COVER = "ic_ha_cover"
        private const val ICON_SCRIPT = "ic_ha_script"
        private const val ICON_SCENE = "ic_ha_scene"
        private const val ICON_BUTTON = "ic_ha_button"

        private val RESOURCE_MAP: Map<String, Int> = buildMap {
            SCALLOP_VARIANTS.forEach { v ->
                put(v.filledId, v.filledRes)
                put(v.outlinedId, v.outlinedRes)
            }
            put(ICON_LIGHT, R.drawable.ic_ha_light)
            put(ICON_SWITCH, R.drawable.ic_ha_switch)
            put(ICON_FAN, R.drawable.ic_ha_fan)
            put(ICON_LOCK_CLOSED, R.drawable.ic_ha_lock_closed)
            put(ICON_LOCK_OPEN, R.drawable.ic_ha_lock_open)
            put(ICON_COVER, R.drawable.ic_ha_cover)
            put(ICON_SCRIPT, R.drawable.ic_ha_script)
            put(ICON_SCENE, R.drawable.ic_ha_scene)
            put(ICON_BUTTON, R.drawable.ic_ha_button)
        }

        private const val COLOR_BG = 0xFF000000.toInt()
        private const val COLOR_FILLED = 0xFFFFFFFF.toInt()
        private const val COLOR_OUTLINE_EMPTY = 0xFF8A949E.toInt()
        private const val COLOR_UNAVAILABLE = 0xFF3A3F45.toInt()
        private const val COLOR_GLYPH_FILLED = 0xFF000000.toInt()
        private const val COLOR_GLYPH_EMPTY = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFF90A4AE.toInt()
    }
}
