package com.treko.core.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.treko.core.common.util.TrekoLogBuffer

/**
 * Enruta automáticamente la captura de micrófono y la salida de voz hacia audífonos conectados
 * (BLE Audio / Bluetooth SCO / USB-C / Jack 3.5 mm) en lugar del micrófono integrado del teléfono.
 */
class HeadsetAudioRouter(
    private val onRouteChanged: (RouteResult) -> Unit = {}
) {
    data class RouteResult(
        val isHeadsetActive: Boolean,
        val inputDevice: AudioDeviceInfo?,
        val outputDevice: AudioDeviceInfo?,
        val deviceLabel: String?
    )

    @Volatile
    private var audioManager: AudioManager? = null

    @Volatile
    private var activeRecord: AudioRecordHolder? = null

    @Volatile
    var currentRoute: RouteResult = RouteResult(
        isHeadsetActive = false,
        inputDevice = null,
        outputDevice = null,
        deviceLabel = null
    )
        private set

    private var callbackRegistered = false

    /**
     * Decides whether a connected device may carry Treko's voice. Set by the app, which can
     * ask Bluetooth what kind of device it is (a watch or band also exposes a call profile,
     * but it is not a headset). Default: everything except names that look like wearables.
     */
    @Volatile
    var deviceFilter: (AudioDeviceInfo) -> Boolean = { device ->
        !looksLikeWearable(device.productName?.toString())
    }

    private val skippedLogged = HashSet<String>()

    private fun usable(devices: List<AudioDeviceInfo>): List<AudioDeviceInfo> = devices.filter { device ->
        if (!isHeadsetInputType(device.type)) return@filter true
        val ok = try {
            deviceFilter(device)
        } catch (_: Exception) {
            true
        }
        if (!ok) {
            val name = device.productName?.toString().orEmpty()
            if (skippedLogged.add(name)) {
                TrekoLogBuffer.i(TAG, "Ignorando '$name' como audífono: no es un dispositivo de audio (reloj, banda…).")
            }
        }
        ok
    }

    private interface AudioRecordHolder {
        fun setPreferredDevice(device: AudioDeviceInfo?): Boolean
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            applyBestRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            applyBestRoute()
        }
    }

    fun attachAudioManager(manager: AudioManager?) {
        audioManager = manager
    }

    fun startRouting(record: android.media.AudioRecord?): RouteResult {
        activeRecord = record?.let { r ->
            object : AudioRecordHolder {
                override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean {
                    return r.setPreferredDevice(device)
                }
            }
        }
        val am = audioManager
        if (am != null && !callbackRegistered) {
            try {
                am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
                callbackRegistered = true
            } catch (_: Exception) {
            }
        }
        return applyBestRoute()
    }

    fun updateAudioRecord(record: android.media.AudioRecord?) {
        activeRecord = record?.let { r ->
            object : AudioRecordHolder {
                override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean {
                    return r.setPreferredDevice(device)
                }
            }
        }
        activeRecord?.setPreferredDevice(currentRoute.inputDevice)
    }

    fun hasHeadsetConnected(): Boolean {
        val am = audioManager ?: return false
        val inputs = usable(am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList())
        val commDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            usable(am.availableCommunicationDevices)
        } else {
            emptyList()
        }
        return selectBestHeadsetTypes(
            inputTypes = inputs.map { it.type },
            commTypes = commDevices.map { it.type }
        ) != null
    }

    fun stopRouting() {
        activeRecord = null
        val am = audioManager
        if (am != null) {
            if (callbackRegistered) {
                try {
                    am.unregisterAudioDeviceCallback(deviceCallback)
                } catch (_: Exception) {
                }
                callbackRegistered = false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    am.clearCommunicationDevice()
                } catch (_: Exception) {
                }
            }
            try {
                if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    am.mode = AudioManager.MODE_NORMAL
                }
            } catch (_: Exception) {
            }
        }
        currentRoute = RouteResult(
            isHeadsetActive = false,
            inputDevice = null,
            outputDevice = null,
            deviceLabel = null
        )
        onRouteChanged(currentRoute)
    }

    fun applyBestRoute(): RouteResult {
        val am = audioManager
        if (am == null) {
            currentRoute = RouteResult(false, null, null, null)
            return currentRoute
        }

        val inputs = usable(am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList())
        val outputs = usable(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList())
        val commDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            usable(am.availableCommunicationDevices)
        } else {
            emptyList()
        }

        val chosenType = selectBestHeadsetTypes(
            inputTypes = inputs.map { it.type },
            commTypes = commDevices.map { it.type }
        )

        if (chosenType == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    am.clearCommunicationDevice()
                } catch (_: Exception) {
                }
            }
            try {
                if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    am.mode = AudioManager.MODE_NORMAL
                }
            } catch (_: Exception) {
            }
            activeRecord?.setPreferredDevice(null)
            val result = RouteResult(
                isHeadsetActive = false,
                inputDevice = null,
                outputDevice = null,
                deviceLabel = null
            )
            if (currentRoute.isHeadsetActive) {
                TrekoLogBuffer.i(TAG, "Audífono desconectado: regresando a micrófono del teléfono.")
            }
            currentRoute = result
            onRouteChanged(result)
            return result
        }

        val matchedComm = commDevices.firstOrNull { it.type == chosenType }
        val matchedInput = inputs.firstOrNull { it.type == chosenType }
            ?: inputs.firstOrNull { isHeadsetInputType(it.type) }
        val matchedOutput = matchedComm ?: outputs.firstOrNull { it.type == chosenType }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                }
                if (matchedComm != null) {
                    am.setCommunicationDevice(matchedComm)
                }
            } catch (e: Exception) {
                TrekoLogBuffer.w(TAG, "No se pudo fijar communicationDevice ($chosenType): ${e.message}")
            }
        }

        activeRecord?.setPreferredDevice(matchedInput)

        val label = (matchedInput?.productName?.toString()
            ?: matchedComm?.productName?.toString()
            ?: typeName(chosenType)).trim().ifEmpty { typeName(chosenType) }

        val result = RouteResult(
            isHeadsetActive = true,
            inputDevice = matchedInput,
            outputDevice = matchedOutput,
            deviceLabel = label
        )

        if (!currentRoute.isHeadsetActive || currentRoute.deviceLabel != result.deviceLabel) {
            TrekoLogBuffer.i(
                TAG,
                "Ruta de audio cambiada a audífono: '$label' (${typeName(chosenType)}, inputId=${matchedInput?.id}, outputId=${matchedOutput?.id})."
            )
        }
        currentRoute = result
        onRouteChanged(result)
        return result
    }

    companion object {
        private const val TAG = "TrekoAudioRoute"

        private val HEADSET_PRIORITY = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        )

        private val WEARABLE_NAME = Regex(
            "\\b(watch|reloj|smartwatch|band|fitbit|amazfit|garmin|gear fit|galaxy fit|mi band|whoop)\\d*\\b",
            RegexOption.IGNORE_CASE
        )

        /** Fallback when Bluetooth can't say the device class: watches and bands by name. */
        fun looksLikeWearable(productName: String?): Boolean =
            !productName.isNullOrBlank() && WEARABLE_NAME.containsMatchIn(productName)

        internal fun isHeadsetInputType(type: Int): Boolean {
            return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        internal fun selectBestHeadsetTypes(
            inputTypes: List<Int>,
            commTypes: List<Int>
        ): Int? {
            for (candidate in HEADSET_PRIORITY) {
                if (candidate in commTypes || candidate in inputTypes) {
                    return candidate
                }
            }
            return null
        }

        private fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            else -> "TYPE_$type"
        }
    }
}
