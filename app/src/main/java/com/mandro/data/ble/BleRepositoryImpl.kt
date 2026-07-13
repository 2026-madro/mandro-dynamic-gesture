package com.mandro.data.ble

import com.mandro.core.ble.BleManager
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EmgSample
import com.mandro.domain.model.InferenceResult
import com.mandro.domain.model.WeightTransferState
import com.mandro.domain.repository.BleRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class BleRepositoryImpl @Inject constructor(
    private val bleManager: BleManager,
) : BleRepository {

    override val bleState: Flow<BleState> = bleManager.bleState
    override val emgStream: Flow<EmgSample> = bleManager.emgStream
    override val inferenceStream: Flow<InferenceResult> = bleManager.inferenceStream
    override val weightTransferState: Flow<WeightTransferState> = bleManager.weightTransferState

    override suspend fun startScan() = bleManager.startScan()
    override suspend fun stopScan() = bleManager.stopScan()
    override suspend fun connect(device: BleDevice) = bleManager.connect(device)
    override suspend fun disconnect() = bleManager.disconnect()
    override fun setEmgEnabled(enabled: Boolean) { bleManager.emgEnabled = enabled }
    override suspend fun sendWeights(weightsBytes: ByteArray): Result<Unit> =
        bleManager.sendWeights(weightsBytes)
}
