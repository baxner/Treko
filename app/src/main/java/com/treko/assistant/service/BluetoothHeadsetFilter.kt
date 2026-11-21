package com.treko.assistant.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import com.treko.core.audio.HeadsetAudioRouter

/**
 * Only real audio devices (headsets, hands-free, earbuds, glasses, car kits) carry Treko's
 * voice. Watches and bands also offer a Bluetooth call profile, so the phone lists them as
 * headsets; Bluetooth's device class tells them apart. Without the "nearby devices"
 * permission the class is unknown and names that look like wearables are skipped instead.
 */
class BluetoothHeadsetFilter(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    fun allows(device: AudioDeviceInfo): Boolean {
        val isBluetooth = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        if (!isBluetooth) return true
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else null
        majorClass(address)?.let { return isAudioDeviceClass(it) }
        return !HeadsetAudioRouter.looksLikeWearable(device.productName?.toString())
    }

    private fun majorClass(address: String?): Int? {
        if (address.isNullOrBlank() || !BluetoothAdapter.checkBluetoothAddress(address)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return try {
            adapter?.getRemoteDevice(address)?.bluetoothClass?.majorDeviceClass
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /** Bluetooth major class AUDIO_VIDEO: headsets, hands-free, headphones, car audio. */
        fun isAudioDeviceClass(majorClass: Int): Boolean =
            majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                // Some earbuds report no class at all.
                majorClass == BluetoothClass.Device.Major.UNCATEGORIZED
    }
}
