package xyz.draumaz.hawidget.wear.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import xyz.draumaz.hawidget.wear.data.WearLocalStore
import xyz.draumaz.hawidget.wear.relay.PhoneRelayClient

/**
 * Fullscreen live security camera viewer on Wear OS.
 * Polls the phone relay at the user's configured refresh interval (e.g. every 2-5s)
 * ONLY while STARTED/visible, immediately stopping when backgrounded to save battery.
 */
class CameraLiveActivity : ComponentActivity() {

    private val relay by lazy { PhoneRelayClient(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WearLocalStore.ensureLoaded(applicationContext)

        setContent {
            MaterialTheme {
                CameraLiveScreen(
                    lifecycle = lifecycle,
                    relay = relay,
                )
            }
        }
    }
}

@Composable
private fun CameraLiveScreen(
    lifecycle: Lifecycle,
    relay: PhoneRelayClient,
) {
    val config by WearLocalStore.configFlow.collectAsStateWithLifecycle()
    val cachedFrame by WearLocalStore.cameraFlow.collectAsStateWithLifecycle()
    var phoneOffline by remember { mutableStateOf(false) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var manualTrigger by remember { mutableLongStateOf(0L) }

    // Ticker for smooth "Xs ago" display
    LaunchedEffect(Unit) {
        while (isActive) {
            nowTick = System.currentTimeMillis()
            delay(1000L)
        }
    }

    // Active refresh loop bound to STARTED lifecycle state
    LaunchedEffect(config.cameraEntityId, config.cameraRefreshIntervalSec, config.cameraQuality, manualTrigger) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            relay.hydrateFromDataClient()
            val cameraId = config.cameraEntityId
            if (cameraId.isNullOrBlank()) return@repeatOnLifecycle

            val intervalMs = config.cameraRefreshIntervalSec.coerceIn(2, 60) * 1000L
            while (isActive) {
                val sent = relay.requestCameraFrame(
                    entityId = cameraId,
                    maxWidthPx = config.cameraQuality.maxWidthPx,
                    jpegQuality = config.cameraQuality.jpegQuality,
                    preferFastMessage = true,
                )
                phoneOffline = !sent
                delay(intervalMs)
            }
        }
    }

    val bitmap = remember(cachedFrame?.meta?.timestampEpochMs, cachedFrame?.jpegBytes?.size) {
        cachedFrame?.jpegBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { manualTrigger++ },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = config.cameraName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val msg = when {
                !config.isHaConfigured -> "Configure Home Assistant\nin the phone app"
                config.cameraEntityId == null -> "Select a camera\nin the phone app"
                phoneOffline -> "Phone unreachable"
                cachedFrame?.meta?.error != null -> cachedFrame?.meta?.error ?: "Camera error"
                else -> "Loading camera\nvia phone..."
            }
            Text(
                text = msg,
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }

        // Top camera name badge
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                text = config.cameraName.ifBlank { "HA Camera" },
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }

        // Bottom live status badge
        val ageText = remember(nowTick, cachedFrame?.meta?.timestampEpochMs, phoneOffline) {
            val err = cachedFrame?.meta?.error
            when {
                phoneOffline -> "Phone offline"
                err != null -> err.take(24)
                cachedFrame != null -> "LIVE (${config.cameraRefreshIntervalSec}s) • ${WearLocalStore.formatAge(cachedFrame!!.meta.timestampEpochMs)}"
                else -> "Requesting (${config.cameraRefreshIntervalSec}s)..."
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xAA000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = ageText,
                    color = if (phoneOffline || cachedFrame?.meta?.error != null) Color(0xFFFF8A80) else Color(0xFF81D4FA),
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Tap to refresh",
                color = Color(0xAAFFFFFF),
                fontSize = 9.sp,
            )
        }
    }
}
