package com.trifork.bluetooth.le

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
class Connection internal constructor(internal val device: BluetoothDevice) : Parcelable {
    interface CommunicationCallback {
        fun onRead(characteristic: BluetoothGattCharacteristic, data: ByteArray) {}
        fun onChanged(characteristic: BluetoothGattCharacteristic, data: ByteArray) {}
        fun onWrite(characteristic: BluetoothGattCharacteristic) {}
        fun onReliableWriteCompleted() {}
        fun onNotificationChanged(characteristic: BluetoothGattCharacteristic) {}
        fun onRead(rssi: Int) {}
        fun onUpdated(interval: Int) {}
        fun onDisconnected(connection: Connection, status: Int) {}
        fun on(failure: BluetoothManager.Failure) {}
    }

    enum class Priority(val value: Int) {
        High(BluetoothGatt.CONNECTION_PRIORITY_HIGH),
        Balanced(BluetoothGatt.CONNECTION_PRIORITY_BALANCED),
        Low(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
    }
}