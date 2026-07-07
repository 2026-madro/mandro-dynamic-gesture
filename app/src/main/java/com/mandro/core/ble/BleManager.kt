package com.mandro.core.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.mandro.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BleManager"

private const val EMG_SERVICE_UUID        = "12345678-1234-1234-1234-1234567890ab"
private const val EMG_CHARACTERISTIC_UUID = "abcd1234-5678-1234-5678-abcdef123456"  // raw EMG
private const val INFER_CHARACTERISTIC_UUID = "abcd1234-5678-1234-5678-abcdef123457" // 추론 결과
private const val ARMBAND_NAME            = "ESP32S3_FAST_BLE"
private const val MTU_SIZE                = 247
private const val SAMPLES_PER_PACKET      = 20
private const val PACKET_SIZE             = SAMPLES_PER_PACKET * EMG_CHANNELS  // 160 bytes

@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _bleState = MutableStateFlow<BleState>(BleState.Disconnected)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _emgStream = MutableSharedFlow<EmgSample>(
        replay = 0,
        extraBufferCapacity = 512,
    )
    val emgStream: SharedFlow<EmgSample> = _emgStream.asSharedFlow()
    private val _inferenceStream = MutableSharedFlow<com.mandro.domain.model.InferenceResult>(
        replay = 1,
        extraBufferCapacity = 64,
    )
    val inferenceStream: SharedFlow<com.mandro.domain.model.InferenceResult> = _inferenceStream.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var packetCount = 0
    private var emgNotifyDone = false

    @Volatile var emgEnabled = false

    // ── 스캔 ──────────────────────────────────────────────────

    // 이 앱이 블루투스 스캔을 할 수 있는 권한이 있는지를 시스템에게 확인받는 함수
    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 연결 권한 있나 확인
    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (!hasScanPermission()) {
            _bleState.value = BleState.Error("블루투스 스캔 권한이 필요합니다.")
            return
        }

        foundDevices.clear()
        _bleState.value = BleState.Scanning
        Log.d(TAG, "스캔 시작")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "스캔 시작 실패: ${e.message}")
            _bleState.value = BleState.Error("블루투스 스캔을 시작할 수 없습니다: ${e.message}")
        }
    }

    fun stopScan() {
        if (!hasScanPermission()) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // 무시
        }
    }

    private val foundDevices = mutableListOf<BleDevice>()
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val name = result.device.name ?: return
                if (name != ARMBAND_NAME) return
                val device = BleDevice(
                    name = name,
                    address = result.device.address,
                    rssi = result.rssi,
                )
                if (foundDevices.none { it.address == device.address }) {
                    foundDevices.add(device)
                    Log.d(TAG, "기기 발견: ${device.name} (${device.address}) RSSI=${device.rssi}")
                    _bleState.value = BleState.DevicesFound(foundDevices.toList())
                }
            } catch (e: SecurityException) {
                // 권한이 없으면 스캔 결과 처리 불가
            }
        }
    }

    // ── 연결 ──────────────────────────────────────────────────

    fun connect(device: BleDevice) {
        if (!hasConnectPermission()) {
            _bleState.value = BleState.Error("블루투스 연결 권한이 필요합니다.")
            return
        }
        stopScan()
        _bleState.value = BleState.Connecting(device)

        val btDevice = adapter?.getRemoteDevice(device.address) ?: return
        try {
            gatt = btDevice.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            _bleState.value = BleState.Error("블루투스 연결을 시작할 수 없습니다: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {

        }
        gatt = null
        _bleState.value = BleState.Disconnected
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            try {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "GATT 연결됨 — MTU 요청 ($MTU_SIZE bytes)")
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.requestMtu(MTU_SIZE)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "GATT 연결 끊김 (status=$status)")
                        this@BleManager.gatt?.close()
                        this@BleManager.gatt = null
                        _bleState.value = BleState.Disconnected
                    }
                }
            } catch (e: SecurityException) {
                _bleState.value = BleState.Error("블루투스 통신 중 권한 오류가 발생했습니다.")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU 협상 완료: ${mtu} bytes (status=$status) — 서비스 탐색 시작")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                _bleState.value = BleState.Error("서비스 검색 중 권한 오류가 발생했습니다.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            try {
                Log.d(TAG, "서비스 탐색 완료 (status=$status)")
                val service = gatt.getService(java.util.UUID.fromString(EMG_SERVICE_UUID))
                    ?: run {
                        Log.e(TAG, "EMG 서비스 없음 — UUID 불일치 가능성")
                        _bleState.value = BleState.Error("암밴드를 찾지 못했어요. 전원을 확인하고 다시 시도해 주세요.")
                        return
                    }

                // raw EMG Characteristic (...56) — 먼저 구독
                emgNotifyDone = false
                val emgChar = service.getCharacteristic(java.util.UUID.fromString(EMG_CHARACTERISTIC_UUID))
                if (emgChar != null) {
                    enableNotify(gatt, emgChar)
                } else {
                    Log.w(TAG, "raw EMG Characteristic 없음")
                    subscribeInferChar(gatt, service)  // EMG 없어도 추론 결과는 구독 시도
                }
                // 추론 결과 Characteristic (...57) 구독은 onDescriptorWrite 콜백에서 순차 처리
            } catch (e: SecurityException) {
                _bleState.value = BleState.Error("서비스 설정 중 권한 오류가 발생했습니다.")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val charUuid = descriptor.characteristic.uuid.toString()
            Log.d(TAG, "Descriptor 쓰기 완료: $charUuid (status=$status)")

            if (charUuid.equals(EMG_CHARACTERISTIC_UUID, ignoreCase = true) && !emgNotifyDone) {
                emgNotifyDone = true
                // raw EMG 구독 완료 → 추론 결과 Characteristic 구독
                val service = gatt.getService(java.util.UUID.fromString(EMG_SERVICE_UUID))
                if (service != null) subscribeInferChar(gatt, service)

                // 연결 완료 상태 업데이트
                val deviceName = try { gatt.device.name } catch (e: SecurityException) { null }
                val connectedDevice = BleDevice(
                    name = deviceName ?: ARMBAND_NAME,
                    address = gatt.device.address,
                    rssi = 0,
                )
                Log.i(TAG, "연결 완료: ${connectedDevice.name}")
                _bleState.value = BleState.Connected(connectedDevice)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val bytes = characteristic.value ?: return
            when (characteristic.uuid.toString().lowercase()) {
                EMG_CHARACTERISTIC_UUID.lowercase()   -> parseEmgPacket(bytes)
                INFER_CHARACTERISTIC_UUID.lowercase() -> parseInferencePacket(bytes)
            }
        }
    }

    private fun enableNotify(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(char, true)
            char.descriptors.firstOrNull()?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Notify 등록 실패: ${char.uuid}", e)
        }
    }

    private fun subscribeInferChar(gatt: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        val inferChar = service.getCharacteristic(java.util.UUID.fromString(INFER_CHARACTERISTIC_UUID))
        if (inferChar != null) {
            enableNotify(gatt, inferChar)
            Log.d(TAG, "추론 결과 Characteristic 구독 요청")
        } else {
            Log.w(TAG, "추론 결과 Characteristic 없음 (펌웨어 미지원 가능성)")
        }
    }

    // ── 패킷 파싱 ─────────────────────────────────────────────

    /** 포맷: "classname|l0|l1|l2|l3|l4|l5" */
    private fun parseInferencePacket(bytes: ByteArray) {
        val result = com.mandro.domain.model.InferenceResult.parse(bytes) ?: run {
            Log.w(TAG, "추론 결과 파싱 실패: ${String(bytes, Charsets.UTF_8)}")
            return
        }
        _inferenceStream.tryEmit(result)
    }

    /**
     * 패킷 포맷: uint8 × 8채널 × 20샘플 = 160 bytes (row-major)
     * byte[s*8 + ch] → 채널 ch의 s번째 샘플값 (0~255)
     */
    private fun parseEmgPacket(bytes: ByteArray) {
        if (!emgEnabled) return
        if (bytes.size != PACKET_SIZE) {
            Log.w(TAG, "예상치 못한 패킷 크기: ${bytes.size} bytes (기대값: $PACKET_SIZE) — 드롭")
            return
        }
        packetCount++
        if (packetCount % 64 == 0) {
            val ch0 = bytes[0].toInt() and 0xFF
            Log.d(TAG, "패킷 수신 #$packetCount — CH0[0]=$ch0 (0~255)")
        }
        for (s in 0 until SAMPLES_PER_PACKET) {
            val channels = FloatArray(EMG_CHANNELS) { ch ->
                (bytes[s * EMG_CHANNELS + ch].toInt() and 0xFF).toFloat()
            }
            _emgStream.tryEmit(EmgSample(channels = channels))
        }
    }
}
