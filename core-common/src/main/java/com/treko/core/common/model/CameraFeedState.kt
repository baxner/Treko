package com.treko.core.common.model

data class CameraFeedState(
    val entityId: String,
    val friendlyName: String,
    val snapshotUrl: String,
    val haUrl: String,
    val haToken: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isLive: Boolean = false,
    val isVisible: Boolean = true
)
