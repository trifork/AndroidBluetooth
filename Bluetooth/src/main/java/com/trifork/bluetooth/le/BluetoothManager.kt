@file:Suppress("unused")

package com.trifork.bluetooth.le

import android.annotation.TargetApi
import android.bluetooth.*
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import com.trifork.bluetooth.le.BluetoothManager.Failure.Reason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import kotlin.concurrent.schedule
import android.bluetooth.BluetoothManager as AndroidBluetoothManager

class BluetoothManager(private val context: Context, private val configuration: Configuration) {
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private val manager: AndroidBluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager
    }

    private var connectedGatt = mutableMapOf<Connection, BluetoothGatt>()
    private var communicationCallbacks = mutableMapOf<Connection, BluetoothGattCallback>()

    private var listeners =
        mutableMapOf<Connection, MutableList<Connection.CommunicationCallback>>()

    private var scanCallback: ScanCallback? = null

    val isScanning get() = scanCallback != null

    val isEnabled get() = manager.adapter?.isEnabled == true

    fun startScan(
        filters: List<ScanFilter>? = null,
        settings: ScanSettings? = null,
        callback: ScanCallback
    ) = mainScope.launch {
        if (isScanning) {
            d("Cannot start scanning when already scanning.")
            return@launch
        }

        if (manager.adapter?.isEnabled != true) {
            w("Cannot start scanning when bluetooth is disabled.")
            scanCallback?.on(Failure.Bluetooth(Reason.BluetoothDisabled))
            return@launch
        }

        d("Starting scan.")
        scanCallback = callback
        if (filters != null && settings != null) {
            manager.adapter.bluetoothLeScanner.startScan(filters, settings, scanDelegate)
        } else {
            if (filters != null || settings != null) {
                w("Attempted to use either filter or settings when both are required. Using none of them to scan.")
            }
            manager.adapter.bluetoothLeScanner.startScan(scanDelegate)
        }
    }

    fun stopScan() = mainScope.launch {
        if (!isScanning) {
            d("Cannot stop scanning when not scanning.")
            return@launch
        }

        scanCallback = null
        manager.adapter.bluetoothLeScanner.stopScan(scanDelegate)
        d("Stopping scan.")
    }

    fun connect(device: BluetoothDevice, callback: ConnectCallback) = mainScope.launch {
        val connection = Connection(device)
        val delegate = createCommunicationDelegate(device, connection, callback)

        d("Creating connection $connection to ${device.address}...")
        val gatt = device.connectGatt(context, false, delegate)
        if (gatt != null) {
            communicationCallbacks[connection] = delegate
            connectedGatt[connection] = gatt
        } else {
            callback.on(Failure.Bluetooth(Reason.OperationFailed))
        }
    }

    fun add(connection: Connection, listener: Connection.CommunicationCallback) = mainScope.launch {
        i("Adding listener: $listener.")
        listeners.getOrPut(connection) { mutableListOf() }.add(listener)
    }

    fun remove(connection: Connection, listener: Connection.CommunicationCallback) =
        mainScope.launch {
            i("Removing listener: $listener.")
            listeners.getOrPut(connection) { mutableListOf() }.remove(listener)
        }

    fun set(priority: Connection.Priority, connection: Connection) = mainScope.launch {
        val text = when (priority) {
            Connection.Priority.High -> "high"
            Connection.Priority.Balanced -> "balanced"
            Connection.Priority.Low -> "low"
        }
        val gatt = connectedGatt[connection] ?: run {
            w("Cannot set priority $text for device: ${connection.device.address} which is not connected.")
            return@launch
        }
        d("Setting priority $text for device: ${connection.device.address}.")

        if (!gatt.requestConnectionPriority(priority.value)) {
            on(Failure.Bluetooth(Reason.OperationFailed), connection)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Timer().schedule(100L) {
                onIntervalUpdated(-1, connection)
            }
        }
    }

    fun read(characteristic: BluetoothGattCharacteristic, connection: Connection) =
        mainScope.launch {
            val gatt = connectedGatt[connection] ?: run {
                on(Failure.Read(Reason.NotConnected), connection)
                return@launch
            }
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                on(Failure.Read(Reason.NotSupported), connection)
                return@launch
            }
            v("Reading characteristic: ${characteristic.uuid}.")
            if (!gatt.readCharacteristic(characteristic)) {
                on(Failure.Read(Reason.OperationFailed), connection)
            }
        }

    fun write(
        data: ByteArray,
        characteristic: BluetoothGattCharacteristic,
        connection: Connection
    ) = mainScope.launch {
        val gatt = connectedGatt[connection] ?: run {
            on(Failure.Write(Reason.NotConnected), connection)
            return@launch
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            on(Failure.Write(Reason.NotSupported), connection)
            return@launch
        }

        v(
            "Write to characteristic: ${characteristic.uuid}, data: ${
                configuration.hexFormatter(
                    data
                )
            }."
        )
        characteristic.value = data
        if (!gatt.writeCharacteristic(characteristic)) {
            on(Failure.Write(Reason.OperationFailed), connection)
        }
    }

    fun setNotification(
        enabled: Boolean,
        characteristic: BluetoothGattCharacteristic,
        connection: Connection
    ) = mainScope.launch {
        val gatt = connectedGatt[connection] ?: run {
            on(Failure.Notification(Reason.NotConnected), connection)
            return@launch
        }

        val descriptor =
            characteristic.getDescriptor(ClientCharacteristicConfigurationDescriptor)
        if (descriptor == null || characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            on(Failure.Notification(Reason.NotSupported), connection)
            return@launch
        }

        descriptor.value =
            if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

        d("Set notification state: $enabled for characteristic: ${characteristic.uuid}.")
        if (!gatt.setCharacteristicNotification(characteristic, enabled)) {
            on(Failure.Notification(Reason.OperationFailed), connection)
            return@launch
        }
        if (!gatt.writeDescriptor(descriptor)) {
            on(Failure.Notification(Reason.OperationFailed), connection)
        }
    }

    fun rssi(connection: Connection) = mainScope.launch {
        val gatt = connectedGatt[connection] ?: run {
            on(Failure.Rssi(Reason.NotConnected), connection)
            return@launch
        }
        v("Requesting RSSI.")
        if (!gatt.readRemoteRssi()) {
            on(Failure.Rssi(Reason.OperationFailed), connection)
        }
    }

    fun disconnect(connection: Connection) = mainScope.launch {
        val gatt = connectedGatt[connection] ?: run {
            w("Attempted to disconnect connection: $connection which is not connected.")
            on(Failure.Bluetooth(Reason.NotConnected), connection)
            return@launch
        }

        d("Disconnect connection: $connection")
        gatt.disconnect()
    }

    private val scanDelegate = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val scanResult = result ?: return
            val callback = scanCallback ?: return

            mainScope.launch {
                v("Found device: ${scanResult.device.address}")
                callback.onDiscover(scanResult)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            val list = results ?: return
            val callback = scanCallback ?: return

            mainScope.launch {
                for (item in list) {
                    v("Found device: ${item.device.address}")
                    callback.onDiscover(item)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val callback = scanCallback ?: return

            mainScope.launch {
                callback.on(Failure.Bluetooth(Reason.SystemError(errorCode)))
            }
        }
    }

    private fun createCommunicationDelegate(
        device: BluetoothDevice,
        connection: Connection,
        connectCallback: ConnectCallback
    ): BluetoothGattCallback {
        var retries = 0
        var shouldConnect = true
        val setupComplete = { gatt: BluetoothGatt ->
            d("Device: ${connection.device.address} is ready.")
            connectCallback.onConnected(connection, gatt.services)
        }

        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                d("BluetoothGattCallback.onConnectionStateChange: $status -> $newState")
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && shouldConnect -> {
                        shouldConnect = false
                        retries = 0
                        gatt ?: run {
                            on(Failure.GattCallback(), connection)
                            return
                        }

                        mainScope.launch {
                            i("Connected to device: ${connection.device.address}. Discovering services...")
                            gatt.discoverServices()
                        }
                    }
                    (status != GATT_ERROR && newState == BluetoothProfile.STATE_DISCONNECTED) || retries > 2 -> {
                        onDisconnected(connection, newState)
                        mainScope.launch {
                            connectedGatt.remove(connection)?.close()
                            communicationCallbacks.remove(connection)
                            listeners.remove(connection)
                        }
                    }
                    status == GATT_ERROR || newState == GATT_ERROR -> {
                        connectedGatt.remove(connection)?.close()
                        Thread.sleep(200L)
                        retries += 1
                        w("Changed connection state from: $status to $newState for device: ${connection.device.address}. Retry attempt #$retries...")
                        val retryGatt = device.connectGatt(context, false, this)
                        if (retryGatt != null) {
                            communicationCallbacks[connection] = this
                            connectedGatt[connection] = retryGatt
                        } else {
                            connectCallback.on(Failure.Bluetooth(Reason.OperationFailed))
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                mainScope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        gatt ?: run {
                            on(Failure.GattCallback(), connection)
                            return@launch
                        }

                        configuration.connectionMtuSize?.let {
                            i("Requesting connection to device: ${connection.device.address} uses MTU size: $it...")
                            gatt.requestMtu(it)
                        } ?: run {
                            setupComplete(gatt)
                        }
                    } else {
                        e("Failed to discover services for device: ${connection.device.address} with status: $status.")
                        connectCallback.on(Failure.Bluetooth(Reason.SystemError(status)))
                    }
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    characteristic ?: run {
                        on(Failure.GattCallback(), connection)
                        return
                    }
                    onRead(characteristic, characteristic.value, connection)
                } else {
                    on(Failure.Read(Reason.SystemError(status)), connection)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    characteristic ?: run {
                        on(Failure.GattCallback(), connection)
                        return
                    }
                    onWrite(characteristic, connection)
                } else {
                    on(Failure.Write(Reason.SystemError(status)), connection)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?
            ) {
                characteristic ?: run {
                    on(Failure.GattCallback(), connection)
                    return
                }
                onChanged(characteristic, characteristic.value, connection)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    descriptor ?: run {
                        on(Failure.GattCallback(), connection)
                        return
                    }
                    onNotificationChanged(descriptor.characteristic, connection)
                } else {
                    on(Failure.Notification(Reason.SystemError(status)), connection)
                }
            }

            override fun onReliableWriteCompleted(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onReliableWriteCompleted(connection)
                } else {
                    on(Failure.Write(Reason.SystemError(status)), connection)
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onRead(rssi, connection)
                } else {
                    on(Failure.Rssi(Reason.SystemError(status)), connection)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS || status == MTU_DID_NOT_NEED_TO_CHANGE) {
                    mainScope.launch {
                        gatt ?: run {
                            on(Failure.GattCallback(), connection)
                            return@launch
                        }

                        i("Connection to device: ${connection.device.address} configured to use MTU: $mtu")
                        setupComplete(gatt)
                    }
                } else {
                    on(Failure.Mtu(Reason.SystemError(status)), connection)
                }
            }

            @TargetApi(Build.VERSION_CODES.O)
            @Suppress("UNUSED_PARAMETER")
            fun onConnectionUpdated(
                gatt: BluetoothGatt?,
                interval: Int,
                latency: Int,
                timeout: Int,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onIntervalUpdated(interval, connection)
                } else {
                    on(Failure.Bluetooth(Reason.SystemError(status)), connection)
                }
            }
        }
    }

    private fun onRead(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        connection: Connection
    ) {
        v(
            "Did read from characteristic: ${characteristic.uuid} for device: ${connection.device.address}, data: ${
                configuration.hexFormatter(
                    data
                )
            }."
        )
        inform(connection) { it.onRead(characteristic, data) }
    }

    private fun onChanged(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        connection: Connection
    ) {
        v(
            "Notified about characteristic: ${characteristic.uuid} for device: ${connection.device.address}, data: ${
                configuration.hexFormatter(
                    data
                )
            }."
        )
        inform(connection) { it.onChanged(characteristic, data) }
    }

    private fun onWrite(characteristic: BluetoothGattCharacteristic, connection: Connection) {
        v("Did write to characteristic: ${characteristic.uuid} for device: ${connection.device.address}.")
        inform(connection) { it.onWrite(characteristic) }
    }

    private fun onReliableWriteCompleted(connection: Connection) {
        i("Completed reliable writes for device: ${connection.device.address}.")
        inform(connection) { it.onReliableWriteCompleted() }
    }

    private fun onNotificationChanged(
        characteristic: BluetoothGattCharacteristic,
        connection: Connection
    ) {
        v("Changed notification state for characteristic: ${characteristic.uuid} for device: ${connection.device.address}.")
        inform(connection) { it.onNotificationChanged(characteristic) }
    }

    private fun onRead(rssi: Int, connection: Connection) {
        v("RSSi value: $rssi for device: ${connection.device.address}.")
        inform(connection) { it.onRead(rssi) }
    }

    private fun onDisconnected(connection: Connection, status: Int) {
        i("Device: ${connection.device.address} disconnected with status: $status.")
        inform(connection) { it.onDisconnected(connection, status) }
    }

    private fun on(failure: Failure, connection: Connection) {
        e("Experienced a $failure with on device: ${connection.device.address}.")
        inform(connection) { it.on(failure) }
    }

    private fun onIntervalUpdated(interval: Int, connection: Connection) {
        i("Updated connection interval to $interval with on device: ${connection.device.address}.")
        inform(connection) { it.onUpdated(interval) }
    }

    private inline fun inform(
        connection: Connection,
        crossinline update: (listener: Connection.CommunicationCallback) -> Unit
    ) = mainScope.launch {
        val listeners = listeners[connection] ?: emptyList()
        for (listener in listeners) {
            update(listener)
        }
    }

    private fun v(message: String) = configuration.logger?.v(message)
    private fun d(message: String) = configuration.logger?.d(message)
    private fun i(message: String) = configuration.logger?.i(message)
    private fun w(message: String) = configuration.logger?.w(message)
    private fun e(message: String) = configuration.logger?.e(message)

    interface ScanCallback {
        fun onDiscover(scanResult: ScanResult)
        fun on(failure: Failure)
    }

    interface ConnectCallback {
        fun onConnected(
            connection: Connection,
            services: List<BluetoothGattService>
        )

        fun on(failure: Failure)
    }

    interface Logger {
        fun v(message: String)
        fun d(message: String)
        fun i(message: String)
        fun w(message: String)
        fun e(message: String)
    }

    data class Configuration(
        val connectionMtuSize: Int? = null,
        val logger: Logger? = null,
        val hexFormatter: (ByteArray) -> String =
            { bytes -> bytes.joinToString("-") { "%02x".format(it) } }
    )

    companion object {
        private val ClientCharacteristicConfigurationDescriptor =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GATT_ERROR =
            133 // https://android.googlesource.com/platform/external/bluetooth/bluedroid/+/adc9f28ad418356cb81640059b59eee4d862e6b4/stack/include/gatt_api.h#54
        private const val MTU_DID_NOT_NEED_TO_CHANGE = 4 // This is an assumption
    }

    sealed class Failure {
        sealed class Reason {
            sealed interface Bluetooth
            sealed interface Communication
            sealed interface Rssi

            object NotConnected : Reason(), Bluetooth, Communication, Rssi
            object OperationFailed : Reason(), Bluetooth, Communication, Rssi
            object Missing : Reason(), Bluetooth
            object BluetoothDisabled : Reason(), Bluetooth, Communication
            object NotSupported : Reason(), Communication
            data class SystemError internal constructor(val status: Int) : Reason(), Bluetooth,
                Communication, Rssi
        }

        data class Bluetooth internal constructor(val reason: Reason.Bluetooth) : Failure()
        data class Mtu internal constructor(val reason: Reason.SystemError) : Failure()
        data class Read internal constructor(val reason: Reason.Communication) : Failure()
        data class Write internal constructor(val reason: Reason.Communication) : Failure()
        data class Notification internal constructor(val reason: Reason.Communication) : Failure()
        data class Rssi internal constructor(val reason: Reason.Rssi) : Failure()
        class GattCallback internal constructor() : Failure() {
            val reason = Reason.Missing
        }
    }
}

