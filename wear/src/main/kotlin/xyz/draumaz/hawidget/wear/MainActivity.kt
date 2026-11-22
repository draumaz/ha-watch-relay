package xyz.draumaz.hawidget.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch
import xyz.draumaz.hawidget.shared.EntityActionRequest
import xyz.draumaz.hawidget.shared.HaEntityConfig
import xyz.draumaz.hawidget.wear.data.WearLocalStore
import xyz.draumaz.hawidget.wear.relay.PhoneRelayClient
import xyz.draumaz.hawidget.wear.ui.CameraLiveActivity
import xyz.draumaz.hawidget.wear.ui.WearHaptics

class MainActivity : ComponentActivity() {

    private val relay by lazy { PhoneRelayClient(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearLocalStore.ensureLoaded(applicationContext)

        setContent {
            MaterialTheme {
                WearHomeScreen(
                    relay = relay,
                    onOpenCamera = {
                        startActivity(Intent(this, CameraLiveActivity::class.java))
                    },
                )
            }
        }
    }
}

@Composable
private fun WearHomeScreen(
    relay: PhoneRelayClient,
    onOpenCamera: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by WearLocalStore.configFlow.collectAsStateWithLifecycle()
    val snapshot by WearLocalStore.statesFlow.collectAsStateWithLifecycle()
    var phoneReachable by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        relay.hydrateFromDataClient()
        phoneReachable = relay.isPhoneReachable()
        if (phoneReachable == true) {
            relay.pingPhone()
            relay.requestRefreshStates()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "HA Controls",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                val status = when {
                    phoneReachable == false -> "Phone offline"
                    !config.isHaConfigured -> "Set up on phone app"
                    snapshot.error != null -> snapshot.error!!
                    else -> "Via phone • ${WearLocalStore.formatAge(snapshot.updatedAtEpochMs)}"
                }
                Text(
                    text = status,
                    fontSize = 11.sp,
                    color = if (phoneReachable == false || snapshot.error != null) Color(0xFFFF8A80) else Color(0xFF90A4AE),
                    textAlign = TextAlign.Center,
                )
            }

            if (config.cameraEntityId != null) {
                item {
                    Button(
                        onClick = onOpenCamera,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Camera: ${config.cameraName.ifBlank { "Live" }}",
                            maxLines = 1,
                        )
                    }
                }
            }

            if (config.selectedEntities.isEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No entities selected yet.\nOpen HA Watch Relay on your phone to pick switches & camera.",
                        fontSize = 12.sp,
                        color = Color(0xFFB0BEC5),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            } else {
                items(config.selectedEntities, key = { it.entityId }) { entity ->
                    val state = snapshot.stateFor(entity.entityId)
                    val isOn = state?.isOn == true && !entity.isMomentary
                    ExpressiveTogglePill(
                        entity = entity,
                        isOn = isOn,
                        onTap = {
                            WearHaptics.playToggle(
                                context = context,
                                turningOn = !isOn,
                                isMomentary = entity.isMomentary,
                            )
                            scope.launch {
                                if (!entity.isMomentary) {
                                    WearLocalStore.applyOptimisticToggle(context, entity.entityId)
                                }
                                phoneReachable = relay.sendEntityAction(
                                    EntityActionRequest(entity.entityId, entity.domain, state?.state),
                                )
                            }
                        },
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(
                    onClick = {
                        WearHaptics.playToggle(context, turningOn = true, isMomentary = true)
                        scope.launch {
                            phoneReachable = relay.requestRefreshStates()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Refresh via Phone")
                }
            }
        }
    }
}

@Composable
private fun ExpressiveTogglePill(
    entity: HaEntityConfig,
    isOn: Boolean,
    onTap: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "toggle_scale",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isOn) 14.dp else 26.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "toggle_corner",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isOn) Color.White else Color.Black,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "toggle_bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isOn) Color.Black else Color.White,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "toggle_fg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isOn) Color.White else Color(0xFF8A949E),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "toggle_border",
    )

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(containerColor)
            .border(BorderStroke(2.dp, borderColor), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = entity.label,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
