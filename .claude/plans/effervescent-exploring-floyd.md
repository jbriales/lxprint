# Android LX Printer App

Minimal Android app (Kotlin + Jetpack Compose) to print text labels to the LX-D02 BLE thermal printer.

## Project Location

Create at `/Users/jesusbriales/lxprint/android/` as a new Android Studio project.

## Project Structure

```
android/app/src/main/
├── AndroidManifest.xml
├── java/com/example/lxprint/
│   ├── MainActivity.kt
│   ├── ui/PrinterScreen.kt
│   ├── viewmodel/PrinterViewModel.kt
│   ├── ble/
│   │   ├── BleManager.kt        # BLE scan, connect, auth, write
│   │   └── LxProtocol.kt        # Protocol constants & message builders
│   └── util/
│       ├── BitmapConverter.kt    # Text → 1-bit bitmap
│       └── Crc16Xmodem.kt       # CRC-16-XMODEM checksum
└── res/values/strings.xml
```

## Files & Responsibilities

### 1. `util/Crc16Xmodem.kt`
- Direct port of `crc16xmodem()` from `src/lib/lxprinter.ts:6-14`
- Pure function: `fun compute(data: ByteArray): Int`
- Polynomial `0x1021`, initial value `0x0000`

### 2. `util/BitmapConverter.kt`
- Replaces the web app's SVG → Canvas → ImageData pipeline
- `textToBitmapData(text: String): PrintBitmapData` — renders text via Android `Canvas.drawText()` on a 384px-wide white bitmap, converts ARGB to 1-bit (luminance < 128 → bit=1), packs 8 pixels/byte MSB-first
- Each row: 48 bytes of image data zero-padded to 96 bytes (printer head is 768px but image is 384px)
- `generatePrintLines(data): List<ByteArray>` — produces 100-byte packets: `[0x55, lineHi, lineLo, ...96 bytes]`
- `generateLastLine(printLength): ByteArray` — final 100-byte marker packet
- Reference: `src/lib/bitmap.ts`

### 3. `ble/LxProtocol.kt`
- Protocol constants: service UUID `0xFFE6`, send char `0xFFE1`, recv char `0xFFE2`, CCC descriptor `0x2902`
- Message builders: `authHello()`, `authStage2(mac)`, `authStage3(crcHighBytes)`, `printInit(len)`, `printAck(len)`
- Parsers: `parseMac(msg)`, `isAuthSuccess(msg)`, `parseStatus(msg)`, `parsePrintComplete(msg)`
- Reference: `src/lib/lxprinter.ts` (auth: lines 140-224, print: lines 247-281)

### 4. `ble/BleManager.kt`
- BLE scan with `BluetoothLeScanner`, filter by name prefix "LX", 15s timeout
- `connectGatt()` → `requestMtu(128)` → `discoverServices()` → enable notifications on `0xFFE2` → auth handshake
- Auth handshake driven by `onCharacteristicChanged` callbacks (stage1 → stage2 → stage3, each response triggers next send)
- `suspend fun print(data)`: sends init, streams lines with `delay(50)` between each write, sends last line, awaits `0x5A 0x06` via `CompletableDeferred`
- Emits events via `SharedFlow<BleEvent>` (state changes, errors, status updates, print complete)
- Uses `WRITE_TYPE_NO_RESPONSE` (ATT Write Command, matching the web app)

### 5. `viewmodel/PrinterViewModel.kt`
- `AndroidViewModel` holding `BleManager` and `MutableStateFlow<PrinterUiState>`
- Collects `BleManager.events` → maps to UI state
- Actions: `connect()`, `disconnect()`, `print()`, `onTextChanged(text)`
- `print()` launches coroutine: converts text → bitmap, calls `bleManager.print()`

### 6. `ui/PrinterScreen.kt`
- Compose UI: status row, error text, connect/disconnect button, text field (3 lines), print button
- Permission handling via `rememberLauncherForActivityResult(RequestMultiplePermissions)`
  - API 31+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
  - API ≤30: `ACCESS_FINE_LOCATION`
- Print button enabled only when `state == CONNECTED && text.isNotBlank()`

### 7. `MainActivity.kt`
- Single activity, `setContent { LxPrintTheme { PrinterScreen(viewModel) } }`

### 8. `AndroidManifest.xml`
- Permissions: `BLUETOOTH` (maxSdk 30), `BLUETOOTH_ADMIN` (maxSdk 30), `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`
- `<uses-feature android:name="android.hardware.bluetooth_le" required="true"/>`

## Build Config
- `minSdk = 24`, `targetSdk = 34`, `compileSdk = 34`
- Compose BOM, Material3, lifecycle-viewmodel-compose, activity-compose

## Implementation Order
1. Generate project with Android Studio Empty Compose Activity template
2. `Crc16Xmodem.kt` — pure, testable
3. `LxProtocol.kt` — pure, testable
4. `BitmapConverter.kt` — needs Android SDK
5. `BleManager.kt` — core BLE logic
6. `PrinterViewModel.kt` — wires BLE + bitmap
7. `PrinterScreen.kt` — UI
8. `MainActivity.kt` — entry point
9. Manifest + build.gradle

## Verification
- Build: `./gradlew assembleDebug`
- Install on physical Android device with BLE
- Enable Bluetooth, tap Connect, verify LX-D02 is found and authenticated
- Type text, tap Print, verify full label prints (not just upper half)
