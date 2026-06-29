package com.mandro.core.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import com.mandro.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import javax.inject.Singleton

// EMG 센서 BLE 서비스/캐릭터리스틱 UUID
// TODO: 실제 펌웨어 UUID로 교체 필요
private const val EMG_SERVICE_UUID        = "0000xxxx-0000-1000-8000-00805f9b34fb"
private const val EMG_CHARACTERISTIC_UUID = "0000yyyy-0000-1000-8000-00805f9b34fb"
private const val RECONNECT_MAX_ATTEMPTS  = 3

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

    private var gatt: BluetoothGatt? = null
    private var reconnectAttempts = 0

    // ── 스캔 ──────────────────────────────────────────────────

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        _bleState.value = BleState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // 필요시 EMG 서비스 UUID로 필터링
        scanner.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val foundDevices = mutableListOf<BleDevice>()
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            val device = BleDevice(
                name = name,
                address = result.device.address,
                rssi = result.rssi,
            )
            if (foundDevices.none { it.address == device.address }) {
                foundDevices.add(device)
                _bleState.value = BleState.DevicesFound(foundDevices.toList())
            }
        }
    }

    // ── 연결 ──────────────────────────────────────────────────

    fun connect(device: BleDevice) {
        stopScan()
        _bleState.value = BleState.Connecting(device)
        reconnectAttempts = 0

        val btDevice = adapter?.getRemoteDevice(device.address) ?: return
        gatt = btDevice.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _bleState.value = BleState.Disconnected
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (reconnectAttempts < RECONNECT_MAX_ATTEMPTS) {
                        reconnectAttempts++
                        gatt.connect()  // 자동 재연결 시도
                    } else {
                        _bleState.value = BleState.Disconnected
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt
                .getService(java.util.UUID.fromString(EMG_SERVICE_UUID))
                ?.getCharacteristic(java.util.UUID.fromString(EMG_CHARACTERISTIC_UUID))
                ?: run {
                    _bleState.value = BleState.Error("EMG 서비스를 찾을 수 없어요")
                    return
                }

            // Notification 활성화
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.descriptors.firstOrNull()
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }

            val connectedDevice = BleDevice(
                name = gatt.device.name ?: "Unknown",
                address = gatt.device.address,
                rssi = 0,
            )
            _bleState.value = BleState.Connected(connectedDevice)
            reconnectAttempts = 0
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // 펌웨어 패킷 파싱 — 20 샘플 × 8채널 × 2bytes(int16)
            // TODO: 실제 펌웨어 패킷 포맷에 맞게 수정 필요
            val bytes = characteristic.value ?: return
            parseEmgPacket(bytes)
        }
    }

    // ── 패킷 파싱 ─────────────────────────────────────────────

    /**
     * BLE 패킷 → EmgSample 변환
     * 패킷 포맷: [int16 × 8채널] × N샘플
     * 1200 Hz / 60 pkt/s = 20 samples/packet
     */
    private fun parseEmgPacket(bytes: ByteArray) {
        val samplesPerPacket = bytes.size / (EMG_CHANNELS * 2)
        for (s in 0 until samplesPerPacket) {
            val channels = FloatArray(EMG_CHANNELS) { ch ->
                val offset = (s * EMG_CHANNELS + ch) * 2
                val raw = (bytes[offset].toInt() and 0xFF) or
                          ((bytes[offset + 1].toInt() and 0xFF) shl 8)
                // int16 → float (-32768 ~ 32767) → normalized
                raw.toShort().toFloat()
            }
            _emgStream.tryEmit(EmgSample(channels = channels))
        }
    }
}
