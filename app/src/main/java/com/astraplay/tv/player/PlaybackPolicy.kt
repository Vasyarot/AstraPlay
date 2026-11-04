package com.astraplay.tv.player

/** Conservative defaults for 1 GB TVs; the byte target covers compressed data, not decoder RAM. */
data class PlaybackPolicy(val bufferBytes: Int, val minBufferMs: Int, val maxBufferMs: Int,
                          val maxWidth: Int, val maxHeight: Int, val maxBitrate: Int) {
    companion object {
        fun forDevice(lowMemory: Boolean, requestedSeconds: Int): PlaybackPolicy {
            val seconds = requestedSeconds.coerceIn(5, if (lowMemory) 15 else 30)
            return if (lowMemory) PlaybackPolicy(12 * 1024 * 1024, seconds * 1000,
                (seconds + 5) * 1000, 1280, 720, 3_000_000)
            else PlaybackPolicy(24 * 1024 * 1024, seconds * 1000,
                (seconds + 10) * 1000, 1920, 1080, 8_000_000)
        }
    }
}
