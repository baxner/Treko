package com.treko.core.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HeadsetAudioRouterTest {

    @Test
    fun `returns null when only phone mic and A2DP output are present`() {
        val chosen = HeadsetAudioRouter.selectBestHeadsetTypes(
            inputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC),
            commTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        )
        assertNull(chosen)
    }

    @Test
    fun `selects BLE headset over phone mic`() {
        val chosen = HeadsetAudioRouter.selectBestHeadsetTypes(
            inputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BLE_HEADSET),
            commTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLE_HEADSET)
        )
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, chosen)
    }

    @Test
    fun `prefers wired or USB headset over wireless when both are connected`() {
        val chosen = HeadsetAudioRouter.selectBestHeadsetTypes(
            inputTypes = listOf(
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET
            ),
            commTypes = listOf(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
        )
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, chosen)
    }

    @Test
    fun `selects Bluetooth SCO when classic HFP headset is connected`() {
        val chosen = HeadsetAudioRouter.selectBestHeadsetTypes(
            inputTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC),
            commTypes = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        )
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, chosen)
    }

    @Test
    fun `watches and bands are not taken as headsets by name`() {
        assertTrue(HeadsetAudioRouter.looksLikeWearable("HUAWEI WATCH Ultimate-1B2"))
        assertTrue(HeadsetAudioRouter.looksLikeWearable("Galaxy Watch6 (A1B2)"))
        assertTrue(HeadsetAudioRouter.looksLikeWearable("Xiaomi Smart Band 8"))
        assertTrue(HeadsetAudioRouter.looksLikeWearable("Amazfit GTR 4"))
        assertFalse(HeadsetAudioRouter.looksLikeWearable("RB Meta 01D4"))
        assertFalse(HeadsetAudioRouter.looksLikeWearable("Galaxy Buds2 Pro"))
        assertFalse(HeadsetAudioRouter.looksLikeWearable("JBL Tune 510BT"))
        assertFalse(HeadsetAudioRouter.looksLikeWearable(null))
    }
}
