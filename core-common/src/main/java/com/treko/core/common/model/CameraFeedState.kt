package com.treko.core.common.model

data class CameraFeedState(
    val entityId: String,
    val friendlyName: String,
    val snapshotUrl: String,
    val haUrl: String,
    val haToken: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isLive: Boolean = false,
    val isVisible: Boolean = true,
    val liveEntityId: String? = null
)

data class MultiCameraGridState(
    val cameras: List<CameraFeedState>,
    val haUrl: String,
    val haToken: String,
    val isLiveAll: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)
