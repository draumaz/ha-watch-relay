package xyz.draumaz.hawidget.mobile

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import xyz.draumaz.hawidget.mobile.ha.TokenQrParser
import xyz.draumaz.hawidget.mobile.ui.MobileUiState
import xyz.draumaz.hawidget.mobile.ui.MobileViewModel
import xyz.draumaz.hawidget.shared.CameraQualityPreset

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                HaRelayApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HaRelayApp(viewModel: MobileViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HA Watch Relay") },
                actions = {
                    IconButton(onClick = { viewModel.syncEverythingToWatch() }) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync to Watch")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Connect") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.ToggleOn, contentDescription = null) },
                    label = { Text("Controls") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label = { Text("Camera") },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (selectedTab) {
                0 -> ConnectionTab(state, viewModel)
                1 -> ControlsTab(state, viewModel)
                2 -> CameraTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun ConnectionTab(state: MobileUiState, viewModel: MobileViewModel) {
    val context = LocalContext.current
    var showToken by remember { mutableStateOf(false) }
    var qrStatus by remember { mutableStateOf<String?>(null) }

    val startQrScan: () -> Unit = {
        qrStatus = null
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue.orEmpty()
                val parsed = TokenQrParser.parse(raw)
                if (parsed != null) {
                    viewModel.onAccessTokenChanged(parsed.token)
                    if (parsed.baseUrl != null && state.baseUrl.isBlank()) {
                        viewModel.onBaseUrlChanged(parsed.baseUrl)
                    }
                    qrStatus = "Token scanned from QR code"
                } else {
                    qrStatus = "QR code did not contain a valid token"
                }
            }
            .addOnFailureListener { err ->
                qrStatus = "QR scan failed: ${err.message}"
            }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Watch, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Watch Relay Status", style = MaterialTheme.typography.titleSmall)
                        val watchSummary = if (state.connectedWatches.isEmpty()) {
                            "No watch detected yet (ensure Wear OS app is installed)"
                        } else {
                            "Connected: " + state.connectedWatches.joinToString { it.displayName }
                        }
                        Text(watchSummary, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { viewModel.refreshConnectedWatches() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh watches")
                    }
                }
            }
        }

        item {
            Text("Home Assistant Server", style = MaterialTheme.typography.titleMedium)
            Text(
                "All watch requests are proxied through this phone (works over Tailscale/VPN).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChanged,
                label = { Text("Home Assistant URL") },
                placeholder = { Text("http://homeassistant.tailnet.ts.net:8123") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            OutlinedTextField(
                value = state.accessToken,
                onValueChange = viewModel::onAccessTokenChanged,
                label = { Text("Long-Lived Access Token") },
                singleLine = true,
                visualTransformation = if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = startQrScan) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = "Scan Home Assistant token QR code",
                            )
                        }
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle token visibility",
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = startQrScan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan HA Token QR Code")
            }
            qrStatus?.let { msg ->
                Spacer(Modifier.height(4.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { viewModel.saveAndTestConnection() },
                    enabled = !state.isTestingConnection,
                ) {
                    if (state.isTestingConnection) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save & Test")
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.syncEverythingToWatch() },
                    enabled = !state.isSyncing,
                ) {
                    Text("Sync to Watch")
                }
            }
        }

        state.connectionStatus?.let { msg ->
            item {
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        state.syncStatus?.let { msg ->
            item {
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ControlsTab(state: MobileUiState, viewModel: MobileViewModel) {
    val selectedIds = remember(state.watchConfig.selectedEntities) {
        state.watchConfig.selectedEntities.map { it.entityId }.toSet()
    }
    val filtered = remember(state.allControllableEntities, state.entitySearchQuery) {
        val q = state.entitySearchQuery.trim().lowercase()
        if (q.isEmpty()) {
            state.allControllableEntities
        } else {
            state.allControllableEntities.filter {
                it.friendlyName.lowercase().contains(q) || it.entityId.lowercase().contains(q)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Selected for Watch Tile (${state.watchConfig.selectedEntities.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = { viewModel.fetchEntitiesFromHa() }) {
                    Text("Reload")
                }
            }
            Text(
                "First 6 entities appear on the Wear OS Controls Tile; all appear in the watch app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        itemsIndexed(state.watchConfig.selectedEntities, key = { _, item -> "sel_${item.entityId}" }) { idx, entity ->
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entity.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            entity.entityId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { viewModel.moveSelectedEntity(idx, -1) },
                        enabled = idx > 0,
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                    }
                    IconButton(
                        onClick = { viewModel.moveSelectedEntity(idx, +1) },
                        enabled = idx < state.watchConfig.selectedEntities.lastIndex,
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                    }
                    IconButton(
                        onClick = {
                            viewModel.toggleEntitySelection(
                                xyz.draumaz.hawidget.shared.HaEntityState(
                                    entityId = entity.entityId,
                                    state = "",
                                    friendlyName = entity.label,
                                    domain = entity.domain,
                                ),
                            )
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                }
            }
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(
                value = state.entitySearchQuery,
                onValueChange = viewModel::onEntitySearchChanged,
                label = { Text("Search Home Assistant entities") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.isLoadingEntities) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (filtered.isEmpty()) {
            item {
                Text(
                    "No controllable entities loaded. Connect to Home Assistant first.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        } else {
            items(filtered, key = { it.entityId }) { entity ->
                val isSelected = entity.entityId in selectedIds
                Card(
                    onClick = { viewModel.toggleEntitySelection(entity) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entity.friendlyName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${entity.entityId} • ${entity.state}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraTab(state: MobileUiState, viewModel: MobileViewModel) {
    val refreshOptions = listOf(2, 3, 5, 10, 30)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Security Camera Tile", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tile refreshes on view; tapping the tile opens the live polling viewer at the interval below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Text("Live Refresh Rate", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                refreshOptions.forEach { sec ->
                    FilterChip(
                        selected = state.watchConfig.cameraRefreshIntervalSec == sec,
                        onClick = { viewModel.setCameraRefreshInterval(sec) },
                        label = { Text("${sec}s") },
                    )
                }
            }
        }

        item {
            Text("Watch Transfer Quality", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CameraQualityPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = state.watchConfig.cameraQuality == preset,
                        onClick = { viewModel.setCameraQuality(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }
        }

        if (state.watchConfig.cameraEntityId != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Preview: ${state.watchConfig.cameraName}", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { viewModel.loadCameraPreview() }) {
                        Text("Test Snapshot")
                    }
                }
                val bytes = state.cameraPreviewBytes
                val bitmap = remember(bytes) {
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
                if (state.isLoadingCameraPreview) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Camera snapshot preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                    Text(
                        "Watch payload size: ${(bytes?.size ?: 0) / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            HorizontalDivider()
            Text("Available Cameras", style = MaterialTheme.typography.titleSmall)
        }

        if (state.allCameraEntities.isEmpty()) {
            item {
                Text(
                    "No camera.* entities found. Connect to Home Assistant and reload.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            items(state.allCameraEntities, key = { it.entityId }) { camera ->
                val isSelected = state.watchConfig.cameraEntityId == camera.entityId
                Card(
                    onClick = { viewModel.selectCamera(camera) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(camera.friendlyName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${camera.entityId} • ${camera.state}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        }
                    }
                }
            }
        }
    }
}
