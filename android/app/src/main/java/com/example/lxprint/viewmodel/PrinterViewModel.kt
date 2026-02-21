package com.example.lxprint.viewmodel

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lxprint.ble.BleEvent
import com.example.lxprint.ble.BleManager
import com.example.lxprint.ble.BleState
import com.example.lxprint.ble.LxProtocol
import com.example.lxprint.util.BitmapConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PrinterUiState(
    val bleState: BleState = BleState.DISCONNECTED,
    val text: String = "",
    val fontSize: Int = 190,
    val padding: Int = 4,
    val fullWidth: Boolean = true,
    val error: String? = null,
    val printerStatus: LxProtocol.PrinterStatus? = null,
)

class PrinterViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application.applicationContext)

    private val _uiState = MutableStateFlow(PrinterUiState())
    val uiState: StateFlow<PrinterUiState> = _uiState

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap

    init {
        viewModelScope.launch {
            bleManager.events.collect { event ->
                when (event) {
                    is BleEvent.StateChanged -> {
                        _uiState.update { it.copy(bleState = event.state, error = null) }
                    }
                    is BleEvent.Error -> {
                        _uiState.update { it.copy(error = event.message) }
                    }
                    is BleEvent.StatusUpdate -> {
                        _uiState.update { it.copy(printerStatus = event.status) }
                    }
                    is BleEvent.PrintComplete -> {
                        _uiState.update { it.copy(error = null) }
                    }
                }
            }
        }
    }

    fun onTextChanged(text: String) {
        _uiState.update { it.copy(text = text) }
        updatePreview()
    }

    fun onFontSizeChanged(size: Int) {
        _uiState.update { it.copy(fontSize = size, fullWidth = false) }
        updatePreview()
    }

    fun onPaddingChanged(padding: Int) {
        _uiState.update { it.copy(padding = padding) }
        updatePreview()
    }

    fun onFullWidthChanged(enabled: Boolean) {
        if (enabled) {
            val text = _uiState.value.text
            val computed = if (text.isNotBlank()) {
                BitmapConverter.computeFullWidthFontSize(text).toInt().coerceIn(16, 384)
            } else {
                _uiState.value.fontSize
            }
            _uiState.update { it.copy(fullWidth = true, fontSize = computed) }
        } else {
            _uiState.update { it.copy(fullWidth = false) }
        }
        updatePreview()
    }

    private fun updatePreview() {
        val state = _uiState.value
        if (state.text.isBlank() || state.fontSize <= 0) {
            _previewBitmap.value = null
            return
        }
        val fontSize = if (state.fullWidth) {
            val computed = BitmapConverter.computeFullWidthFontSize(state.text).toInt().coerceIn(16, 384)
            if (computed != state.fontSize) {
                _uiState.update { it.copy(fontSize = computed) }
            }
            computed
        } else {
            state.fontSize
        }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                BitmapConverter.textToBitmap(state.text, fontSize.toFloat(), state.padding)
            }
            _previewBitmap.value = bmp
        }
    }

    fun connect() {
        _uiState.update { it.copy(error = null) }
        bleManager.connect()
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    fun print() {
        val text = _uiState.value.text
        if (text.isBlank()) return
        if (_uiState.value.bleState != BleState.CONNECTED) return

        viewModelScope.launch {
            val bitmapData = withContext(Dispatchers.Default) {
                BitmapConverter.textToBitmapData(text, _uiState.value.fontSize.toFloat(), _uiState.value.padding)
            }
            bleManager.print(bitmapData)
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.destroy()
    }
}
