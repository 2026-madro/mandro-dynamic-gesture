package com.mandro.data.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.mandro.domain.model.UsbState
import com.mandro.domain.repository.UsbRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "UsbRepositoryImpl"
private const val ACTION_USB_PERMISSION = "com.mandro.USB_PERMISSION"

/**
 * 전송 프로토콜 (ESP32 펌웨어와 반드시 일치해야 함):
 *
 * | 필드        | 크기   | 설명                        |
 * |------------|--------|-----------------------------|
 * | MAGIC      | 4 B    | 0x4D 0x44 0x52 0x4F ("MDRO") |
 * | VERSION    | 1 B    | 0x01                        |
 * | MODEL_SIZE | 4 B LE | model.tflite 바이트 수        |
 * | SCAL_SIZE  | 4 B LE | scaler.bin 바이트 수          |
 * | MODEL_DATA | N B    | TFLite 모델 바이너리           |
 * | SCAL_DATA  | M B    | scaler 바이너리               |
 * | CRC32      | 4 B LE | MODEL_DATA + SCAL_DATA 체크섬 |
 *
 * ESP32는 수신 완료 후 ACK 1바이트(0xAC)를 반환합니다.
 */
@Singleton
class UsbRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsbRepository {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _usbState = MutableStateFlow<UsbState>(UsbState.Disconnected)
    override val usbState: Flow<UsbState> = _usbState

    // Espressif ESP32-S3 기본 VID/PID
    private val ESPRESSIF_VID = 0x303A
    private val ESP32S3_PID   = 0x1001

    private val MAGIC   = byteArrayOf(0x4D, 0x44, 0x52, 0x4F.toByte()) // "MDRO"
    private val VERSION = byteArrayOf(0x01)
    private val ACK     = 0xAC.toByte()

    private val BULK_TIMEOUT_MS = 5_000
    private val CHUNK_SIZE      = 16_384  // 16 KB 단위 전송

    init {
        registerUsbReceiver()
    }

    // ── USB 상태 감지 ───────────────────────────────────────────

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0
        context.registerReceiver(usbReceiver, filter, flags)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && isEsp32(device)) {
                        Log.i(TAG, "ESP32-S3 연결됨: ${device.deviceName}")
                        _usbState.value = UsbState.DeviceDetected
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB 기기 분리됨")
                    _usbState.value = UsbState.Disconnected
                }
            }
        }
    }

    private fun isEsp32(device: UsbDevice) =
        device.vendorId == ESPRESSIF_VID && device.productId == ESP32S3_PID

    // ── 플래시 진입점 ───────────────────────────────────────────

    override suspend fun flash(modelBytes: ByteArray, scalerBytes: ByteArray): Result<Unit> {
        val device = findEsp32Device()
            ?: return Result.failure(IllegalStateException("ESP32-S3 기기를 찾을 수 없어요. USB 케이블을 확인해 주세요."))

        // 권한 요청 (이미 있으면 즉시 진행)
        if (!usbManager.hasPermission(device)) {
            val granted = requestPermission(device)
            if (!granted) return Result.failure(SecurityException("USB 권한이 거부됐어요."))
        }

        return runCatching {
            val connection = usbManager.openDevice(device)
                ?: error("기기를 열 수 없어요.")

            try {
                val (bulkOut, bulkIn) = findBulkEndpoints(device, connection)
                sendPayload(connection, bulkOut, bulkIn, modelBytes, scalerBytes)
            } finally {
                connection.close()
            }
        }.onSuccess {
            _usbState.value = UsbState.Done
        }.onFailure { e ->
            Log.e(TAG, "플래시 실패", e)
            _usbState.value = UsbState.Error(e.message ?: "알 수 없는 오류")
        }
    }

    // ── 기기 탐색 ────────────────────────────────────────────────

    private fun findEsp32Device(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { isEsp32(it) }

    // ── USB 권한 요청 (suspend) ──────────────────────────────────

    private suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val permissionReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED else 0
            context.registerReceiver(permissionReceiver, filter, flags)

            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), pendingFlags,
            )
            usbManager.requestPermission(device, permissionIntent)

            cont.invokeOnCancellation { context.unregisterReceiver(permissionReceiver) }
        }

    // ── 엔드포인트 탐색 (CDC Bulk Out / In) ─────────────────────

    private fun findBulkEndpoints(
        device: UsbDevice,
        connection: UsbDeviceConnection,
    ): Pair<UsbEndpoint, UsbEndpoint> {
        var bulkOut: UsbEndpoint? = null
        var bulkIn: UsbEndpoint? = null

        for (ifIdx in 0 until device.interfaceCount) {
            val intf = device.getInterface(ifIdx)
            if (!connection.claimInterface(intf, true)) continue

            for (epIdx in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(epIdx)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT && bulkOut == null) bulkOut = ep
                if (ep.direction == UsbConstants.USB_DIR_IN  && bulkIn  == null) bulkIn  = ep
            }
            if (bulkOut != null && bulkIn != null) break
        }

        return (bulkOut ?: error("Bulk OUT 엔드포인트를 찾을 수 없어요")) to
               (bulkIn  ?: error("Bulk IN 엔드포인트를 찾을 수 없어요"))
    }

    // ── 실제 전송 ────────────────────────────────────────────────

    private fun sendPayload(
        conn: UsbDeviceConnection,
        bulkOut: UsbEndpoint,
        bulkIn: UsbEndpoint,
        modelBytes: ByteArray,
        scalerBytes: ByteArray,
    ) {
        val totalPayload = modelBytes.size + scalerBytes.size

        // ── 헤더 전송 ─────────────────────────────────────────────
        val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC)                         // 4B
            put(VERSION)                       // 1B
            putInt(modelBytes.size)            // 4B
            putInt(scalerBytes.size)           // 4B
        }.array()

        bulkWrite(conn, bulkOut, header)
        Log.d(TAG, "헤더 전송 완료: model=${modelBytes.size}B scaler=${scalerBytes.size}B")

        // ── 데이터 청크 전송 ─────────────────────────────────────
        var sent = 0
        for (data in listOf(modelBytes, scalerBytes)) {
            var offset = 0
            while (offset < data.size) {
                val chunkEnd = minOf(offset + CHUNK_SIZE, data.size)
                bulkWrite(conn, bulkOut, data.copyOfRange(offset, chunkEnd))
                sent += chunkEnd - offset
                offset = chunkEnd

                val progress = (sent * 100 / totalPayload)
                _usbState.value = UsbState.Flashing(progress)
                Log.d(TAG, "전송 중: $sent / $totalPayload bytes ($progress%)")
            }
        }

        // ── CRC32 체크섬 전송 ─────────────────────────────────────
        val crc = CRC32().apply { update(modelBytes); update(scalerBytes) }
        val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(crc.value.toInt()).array()
        bulkWrite(conn, bulkOut, crcBytes)
        Log.d(TAG, "CRC32 전송: 0x${crc.value.toString(16)}")

        // ── ACK 수신 ──────────────────────────────────────────────
        val ackBuf = ByteArray(1)
        val read = conn.bulkTransfer(bulkIn, ackBuf, 1, BULK_TIMEOUT_MS)
        if (read < 1 || ackBuf[0] != ACK) {
            error("ACK 수신 실패 (received: ${ackBuf.getOrNull(0)?.toString(16) ?: "없음"})")
        }
        Log.i(TAG, "플래시 완료 — ACK 수신")
    }

    private fun bulkWrite(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(offset + ep.maxPacketSize, data.size))
            val sent = conn.bulkTransfer(ep, chunk, chunk.size, BULK_TIMEOUT_MS)
            if (sent < 0) error("USB 전송 오류 (offset=$offset)")
            offset += sent
        }
    }
}
