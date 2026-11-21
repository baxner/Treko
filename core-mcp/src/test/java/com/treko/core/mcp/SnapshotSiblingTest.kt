package com.treko.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotSiblingTest {

    @Test
    fun trailingLiveSuffixMapsToSnapshot() {
        assertEquals("camera.front_door_snapshot", HomeAssistantClient.snapshotSiblingOf("camera.front_door_live"))
        assertEquals("camera.patio_snapshot", HomeAssistantClient.snapshotSiblingOf("camera.patio_live_view"))
        assertEquals("camera.entrada_snapshot", HomeAssistantClient.snapshotSiblingOf("camera.entrada_vista_en_vivo"))
    }

    @Test
    fun liveInsideTheNameIsLeftAlone() {
        assertNull(HomeAssistantClient.snapshotSiblingOf("camera.delivery_door"))
        assertNull(HomeAssistantClient.snapshotSiblingOf("camera.olive_tree"))
        assertNull(HomeAssistantClient.snapshotSiblingOf("camera.live_room"))
        assertNull(HomeAssistantClient.snapshotSiblingOf("camera.backyardlive"))
        assertNull(HomeAssistantClient.snapshotSiblingOf("camera.garage_snapshot"))
    }

    @Test
    fun malformedIdsGiveNull() {
        assertNull(HomeAssistantClient.snapshotSiblingOf(""))
        assertNull(HomeAssistantClient.snapshotSiblingOf("camera."))
        assertNull(HomeAssistantClient.snapshotSiblingOf("camera._live"))
        assertNull(HomeAssistantClient.snapshotSiblingOf("live"))
    }
}
