package com.treko.core.common.model

/**
 * Lightweight UI indicator describing background complementary capture while Treko is speaking.
 */
enum class FullDuplexIndicator {
    INACTIVE,
    CAPTURING_COMPLEMENTARY,
    QUEUED_COMPLEMENTARY,
    FLUSHING_COMPLEMENTARY
}
