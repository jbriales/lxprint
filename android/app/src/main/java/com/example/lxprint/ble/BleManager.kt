package com.example.lxprint.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.example.lxprint.util.PrintBitmapData
import com.example.lxprint.util.BitmapConverter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed class BleEvent {
    data class StateChanged(val state: BleState) : BleEvent()
    data class Error(val message: String) : BleEvent()
    data class StatusUpdate(val status: LxProtocol.PrinterStatus) : BleEvent()
    object PrintComplete : BleEvent()
}

enum class BleState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    PRINTING,
}

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val _events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<BleEvent> = _events

    private var state = BleState.DISCONNECTED
    private var gatt: BluetoothGatt? = null
    private var sendChar: BluetoothGattCharacteristic? = null
    private var mac: ByteArray? = null
    private var authCrcs: IntArray? = null
    private var scanCallback: ScanCallback? = null
    private var printCompleteDeferred: CompletableDeferred<Int>? = null
    private var connectTimeoutJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun setState(newState: BleState) {
        state = newState
        _events.tryEmit(BleEvent.StateChanged(newState))
    }

    private fun emitError(msg: String) {
        _events.tryEmit(BleEvent.Error(msg))
    }

    fun connect() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            emitError("Bluetooth is not available or not enabled")
            return
        }

        setState(BleState.SCANNING)
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            emitError("BLE scanner not available")
            setState(BleState.DISCONNECTED)
            return
        }

        connectTimeoutJob = scope.launch {
            delay(15_000)
            emitError("Scan timeout")
            stopScan(scanner)
            if (state == BleState.SCANNING) {
                setState(BleState.DISCONNECTED)
            }
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: return
                if (name.startsWith("LX")) {
                    stopScan(scanner)
                    connectToDevice(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                emitError("Scan failed: $errorCode")
                setState(BleState.DISCONNECTED)
            }
        }

        val filters = listOf(
            ScanFilter.Builder().build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback!!)
    }

    private fun stopScan(scanner: android.bluetooth.le.BluetoothLeScanner) {
        connectTimeoutJob?.cancel()
        scanCallback?.let { scanner.stopScan(it) }
        scanCallback = null
    }

    private fun connectToDevice(device: BluetoothDevice) {
        setState(BleState.CONNECTING)

        connectTimeoutJob = scope.launch {
            delay(30_000)
            emitError("Connection timeout")
            disconnect()
        }

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(128)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emitError("Disconnected")
                cleanupGatt()
                setState(BleState.DISCONNECTED)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitError("Service discovery failed")
                disconnect()
                return
            }

            val service = gatt.getService(LxProtocol.SERVICE_UUID)
            if (service == null) {
                emitError("Printer service not found")
                disconnect()
                return
            }

            sendChar = service.getCharacteristic(LxProtocol.SEND_CHAR_UUID)
            val recvChar = service.getCharacteristic(LxProtocol.RECV_CHAR_UUID)

            if (sendChar == null || recvChar == null) {
                emitError("Printer characteristics not found")
                disconnect()
                return
            }

            // Enable notifications on recv characteristic
            gatt.setCharacteristicNotification(recvChar, true)
            val descriptor = recvChar.getDescriptor(LxProtocol.CCC_DESCRIPTOR_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Notifications enabled, start auth
                setState(BleState.AUTHENTICATING)
                writeCharacteristic(LxProtocol.authHello())
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val msg = characteristic.value ?: return
            if (msg.isEmpty() || msg[0] != LxProtocol.MSG_PREFIX) return

            when (msg[1]) {
                LxProtocol.MSG_AUTH_HELLO -> handleAuthStage1(msg)
                LxProtocol.MSG_AUTH_STAGE2 -> handleAuthStage2(msg)
                LxProtocol.MSG_AUTH_STAGE3 -> handleAuthResult(msg)
                LxProtocol.MSG_STATUS -> handleStatus(msg)
                LxProtocol.MSG_PRINT_COMPLETE -> handlePrintComplete(msg)
            }
        }
    }

    private fun handleAuthStage1(msg: ByteArray) {
        mac = LxProtocol.parseMac(msg)
        val (stage2Msg, crcs) = LxProtocol.authStage2(mac!!)
        authCrcs = crcs
        writeCharacteristic(stage2Msg)
    }

    private fun handleAuthStage2(msg: ByteArray) {
        val crcs = authCrcs ?: return
        writeCharacteristic(LxProtocol.authStage3(crcs))
    }

    private fun handleAuthResult(msg: ByteArray) {
        if (LxProtocol.isAuthSuccess(msg)) {
            connectTimeoutJob?.cancel()
            setState(BleState.CONNECTED)
        } else {
            emitError("Authentication failed")
            disconnect()
        }
    }

    private fun handleStatus(msg: ByteArray) {
        val status = LxProtocol.parseStatus(msg) ?: return
        _events.tryEmit(BleEvent.StatusUpdate(status))
    }

    private fun handlePrintComplete(msg: ByteArray) {
        val printLength = LxProtocol.parsePrintCompleteLength(msg)
        printCompleteDeferred?.complete(printLength)
    }

    private fun writeCharacteristic(data: ByteArray) {
        val char = sendChar ?: return
        val g = gatt ?: return
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value = data
        g.writeCharacteristic(char)
    }

    suspend fun print(bitmapData: PrintBitmapData) {
        if (state != BleState.CONNECTED) return
        setState(BleState.PRINTING)

        printCompleteDeferred = CompletableDeferred()

        // Send print init
        writeCharacteristic(LxProtocol.printInit(bitmapData.printLength + 1))
        delay(50)

        // Stream print lines
        val lines = BitmapConverter.generatePrintLines(bitmapData)
        for (line in lines) {
            writeCharacteristic(line)
            delay(50)
        }

        // Send last line
        writeCharacteristic(BitmapConverter.generateLastLine(bitmapData.printLength))

        // Wait for print complete
        try {
            val printLength = withTimeout(30_000) {
                printCompleteDeferred!!.await()
            }
            // Send print ack
            writeCharacteristic(LxProtocol.printAck(printLength))
            _events.tryEmit(BleEvent.PrintComplete)
        } catch (e: TimeoutCancellationException) {
            emitError("Print timeout")
        }

        setState(BleState.CONNECTED)
    }

    fun disconnect() {
        connectTimeoutJob?.cancel()
        cleanupGatt()
        setState(BleState.DISCONNECTED)
    }

    private fun cleanupGatt() {
        gatt?.let {
            it.close()
        }
        gatt = null
        sendChar = null
        mac = null
        authCrcs = null
        printCompleteDeferred = null
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
