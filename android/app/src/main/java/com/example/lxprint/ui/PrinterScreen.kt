package com.example.lxprint.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lxprint.ble.BleState
import com.example.lxprint.viewmodel.PrinterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(viewModel: PrinterViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val previewBitmap by viewModel.previewBitmap.collectAsState()
    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        if (permissionsGranted) {
            viewModel.connect()
        }
    }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("LX Label Print") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val statusText = when (state.bleState) {
                    BleState.DISCONNECTED -> "Disconnected"
                    BleState.SCANNING -> "Scanning..."
                    BleState.CONNECTING -> "Connecting..."
                    BleState.AUTHENTICATING -> "Authenticating..."
                    BleState.CONNECTED -> "Connected"
                    BleState.PRINTING -> "Printing..."
                }
                Text(text = statusText, style = MaterialTheme.typography.bodyLarge)

                state.printerStatus?.let { ps ->
                    Text(
                        text = "Battery: ${ps.battery}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Error text
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Controls row: left side has connect + size, right side has Print button
            val isDisconnected = state.bleState == BleState.DISCONNECTED
            val isConnectedOrPrinting = state.bleState == BleState.CONNECTED ||
                    state.bleState == BleState.PRINTING

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Left column: connect/disconnect
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isDisconnected) {
                        Button(onClick = {
                            if (permissionsGranted) {
                                viewModel.connect()
                            } else {
                                permissionLauncher.launch(requiredPermissions)
                            }
                        }) {
                            Text("Connect")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.disconnect() },
                            enabled = !isDisconnected,
                        ) {
                            Text("Disconnect")
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Print button
                Button(
                    onClick = { viewModel.print() },
                    enabled = state.bleState == BleState.CONNECTED && state.text.isNotBlank(),
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Print")
                }
            }

            // Font size controls: full-width toggle + slider
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Full width", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.fullWidth,
                    onCheckedChange = { viewModel.onFullWidthChanged(it) },
                )
                Slider(
                    value = state.fontSize.toFloat(),
                    onValueChange = { viewModel.onFontSizeChanged(it.toInt()) },
                    valueRange = 16f..384f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${state.fontSize}px",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(48.dp),
                )
            }

            // Padding control
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Pad", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.padding.toFloat(),
                    onValueChange = { viewModel.onPaddingChanged(it.toInt()) },
                    valueRange = 0f..50f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${state.padding}px",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(48.dp),
                )
            }

            // Bitmap preview (tap to type)
            BasicTextField(
                value = state.text,
                onValueChange = viewModel::onTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 60.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .background(Color.White),
                textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                cursorBrush = SolidColor(Color.Transparent),
                decorationBox = { innerTextField ->
                    Box {
                        previewBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Label preview",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                        // Hidden but required for keyboard input
                        innerTextField()
                    }
                },
            )

        }
    }
}
