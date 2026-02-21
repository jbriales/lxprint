package com.example.lxprint.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
            TopAppBar(title = { Text("LxPrint") })
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

            // Connect / Disconnect button
            val isDisconnected = state.bleState == BleState.DISCONNECTED
            val isConnectedOrPrinting = state.bleState == BleState.CONNECTED ||
                    state.bleState == BleState.PRINTING

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

            // Text input
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Label text") },
                minLines = 3,
                maxLines = 5,
            )

            // Font size input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Size", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = state.fontSize.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { viewModel.onFontSizeChanged(it) }
                    },
                    modifier = Modifier.width(80.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Text("px", style = MaterialTheme.typography.bodyMedium)
            }

            // Bitmap preview
            previewBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Label preview",
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .background(Color.White),
                    contentScale = ContentScale.FillWidth,
                )
            }

            // Print button
            Button(
                onClick = { viewModel.print() },
                enabled = state.bleState == BleState.CONNECTED && state.text.isNotBlank(),
            ) {
                Text("Print")
            }
        }
    }
}
