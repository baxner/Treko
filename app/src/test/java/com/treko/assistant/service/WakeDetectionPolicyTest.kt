package com.treko.assistant.service

import com.treko.core.common.model.TrekoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WakeDetectionPolicyTest {

    @Test
    fun `strong DTW detection wakes Treko from idle`() {
        assertNull(WakeDetectionPolicy.rejectionReason(TrekoState.IDLE, "DTW", snrDb = 30f, sinceTimeoutMs = null))
    }

    @Test
    fun `DTW alone cannot interrupt while thinking`() {
        // Real case: background conversation re-triggered Treko twice while it was thinking.
        assertNotNull(WakeDetectionPolicy.rejectionReason(TrekoState.THINKING, "DTW", snrDb = 23f, sinceTimeoutMs = null))
        assertNotNull(WakeDetectionPolicy.rejectionReason(TrekoState.THINKING, "DTW", snrDb = 12f, sinceTimeoutMs = null))
    }

    @Test
    fun `keyword model can interrupt while thinking`() {
        assertNull(WakeDetectionPolicy.rejectionReason(TrekoState.THINKING, "KWS", snrDb = 20f, sinceTimeoutMs = null))
    }

    @Test
    fun `DTW filters for low SNR and right after a timeout still apply`() {
        assertEquals("low SNR", WakeDetectionPolicy.rejectionReason(TrekoState.IDLE, "DTW", snrDb = 3f, sinceTimeoutMs = null))
        assertNotNull(WakeDetectionPolicy.rejectionReason(TrekoState.IDLE, "DTW", snrDb = 30f, sinceTimeoutMs = 200L))
        assertNull(WakeDetectionPolicy.rejectionReason(TrekoState.IDLE, "DTW", snrDb = 30f, sinceTimeoutMs = 5_000L))
    }
}
