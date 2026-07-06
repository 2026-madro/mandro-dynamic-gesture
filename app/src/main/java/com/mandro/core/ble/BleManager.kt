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
    @ApplicationContext private val context: Context, // [질문] 이 컨텍스트 클래스가 뭔지? 안드로이드 단에 있는 기능 같긴 한데
    // [답변] 맞음. Context는 안드로이드 프레임워크가 제공하는 "현재 앱/컴포넌트의 실행 환경"에 대한 핸들.
    // 시스템 서비스(블루투스, 위치 등)에 접근하거나, 리소스를 읽거나, 다른 컴포넌트를 실행할 때 항상 필요함.
    // 여기서는 @ApplicationContext로 Hilt가 "Activity가 아니라 앱 전체 생명주기를 따르는 Context"를 주입해줌
    // (BleManager는 Singleton이라 특정 Activity에 묶이면 메모리 누수가 나기 때문).
) {
    private val adapter: BluetoothAdapter? =
        // context라는 클래스에서 getSystemService 함수를 사용 , 그리고 Context 클래스에 선언된 블루투스 서비스라는 변수 주입? adapter는 뭘까..
        // [답변] getSystemService(BLUETOOTH_SERVICE)는 안드로이드 시스템이 관리하는 BluetoothManager 객체를 돌려주는 함수.
        // "블루투스 서비스라는 변수 주입"이라기보다, 시스템에 미리 떠 있는 매니저 인스턴스를 얻어오는 것에 가까움 (as?는 타입 캐스팅 실패 시 null).
        // BluetoothManager.adapter가 바로 이 기기의 블루투스 하드웨어를 제어하는 진입점인 BluetoothAdapter.
        // 즉 스캔 시작/중지, 페어링된 기기 조회, GATT 연결 시작(connectGatt) 등이 전부 이 adapter를 통해 이뤄짐.
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // 뷰모델과 ui 의 역할을 분리하기 위해 이 구조를 많이 사용하는 것 같긴 한데 정확히 MutableStateFlow가 어떤 역할인지?
    // [답변] 맞는 방향. MutableStateFlow는 "현재 값 하나를 항상 들고 있는 관찰 가능한 컨테이너".
    // 값이 바뀌면 구독하고 있는 모든 곳(ViewModel, Compose UI)에 자동으로 새 값을 흘려보냄.
    // private _bleState(쓰기 가능)는 BleManager 내부에서만 값을 바꾸고,
    // 바로 아래 공개된 bleState(읽기 전용 StateFlow)는 밖에서 구독만 하게 해서 캡슐화함
    // (외부에서 상태를 함부로 덮어쓰지 못하게 막는 패턴 — backing property 패턴).
    private val _bleState = MutableStateFlow<BleState>(BleState.Disconnected) // Blestate가 블루투스의 연결 상태를 나타내는 건 알겠는데, 그 원리가 뭔지, 왜 Blestate라는 클래스에서는 disconnected에서 자기 스스로를 또 호출하는 거자ㅣ?
    // [답변] 자기 자신을 호출하는 게 아니라 상속 구조임. BleState는 sealed class이고,
    // Disconnected/Scanning/Connected 등은 그 sealed class를 상속하는 하위 타입들 (domain/model/BleDevice.kt 참고).
    // "BleState()"는 함수 호출이 아니라 부모 클래스의 생성자를 호출하는 상속 문법 (Kotlin의 `: BleState()`).
    // 그래서 BleState 타입 변수 하나로 "연결 안 됨/스캔 중/연결됨/에러" 등 서로 다른 케이스를 안전하게 표현할 수 있고,
    // when문에서 이 케이스들을 강제로 다 분기 처리하게 만들 수 있음 (enum보다 각 상태가 자기만의 데이터를 가질 수 있어 더 강력함).
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    // emg에서 데이터 받아오는 변수?
    private val _emgStream = MutableSharedFlow<EmgSample>(
        replay = 0,
        extraBufferCapacity = 512,
    )
    val emgStream: SharedFlow<EmgSample> = _emgStream.asSharedFlow()

    // 얘는 추론 결과 받아오는 변수일 거고
    private val _inferenceStream = MutableSharedFlow<com.mandro.domain.model.InferenceResult>(
        replay = 1,
        extraBufferCapacity = 64,
    )
    val inferenceStream: SharedFlow<com.mandro.domain.model.InferenceResult> = _inferenceStream.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var packetCount = 0
    // 두 Characteristic 구독을 순차적으로 처리하기 위한 플래그
    private var emgNotifyDone = false

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
            // 무시
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
        if (bytes.size != PACKET_SIZE) {
            Log.w(TAG, "예상치 못한 패킷 크기: ${bytes.size} bytes (기대값: $PACKET_SIZE) — 드롭")
            return
        }
        packetCount++
        // 매 64번째 패킷(약 1초마다)만 로그 — CH0 첫 샘플값 출력
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
