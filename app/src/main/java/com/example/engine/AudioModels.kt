package com.example.engine

enum class AudioFormat(val extension: String, val mimeType: String, val displayName: String) {
    M4A("m4a", "audio/mp4a-latm", "M4A (AAC)"),
    MP3("mp3", "audio/mpeg", "MP3"),
    WAV("wav", "audio/wav", "WAV (Lossless)"),
    FLAC("flac", "audio/flac", "FLAC")
}

enum class AudioBitrate(val kbps: Int, val label: String) {
    ORIGINAL(0, "Original (Lossless Copy)"),
    KBPS_320(320, "320 kbps (Ultra)"),
    KBPS_256(256, "256 kbps (High)"),
    KBPS_192(192, "192 kbps (Standard)"),
    KBPS_128(128, "128 kbps (Good)"),
    KBPS_64(64, "64 kbps (Eco/Voice)")
}

enum class AudioChannels(val channels: Int, val label: String) {
    ORIGINAL(0, "Auto"),
    STEREO(2, "Stereo (2ch)"),
    MONO(1, "Mono (1ch)")
}

data class ConversionConfig(
    val format: AudioFormat = AudioFormat.M4A,
    val bitrate: AudioBitrate = AudioBitrate.ORIGINAL,
    val channels: AudioChannels = AudioChannels.ORIGINAL,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L // 0 means entire video
)

data class VideoMetadata(
    val uriString: String,
    val title: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val resolution: String,
    val audioMime: String?,
    val sampleRate: Int,
    val channelCount: Int
)

sealed class ConversionState {
    object Idle : ConversionState()
    data class Progress(val percentage: Int, val stage: String, val speed: String = "") : ConversionState()
    data class Success(val outputPath: String, val outputUri: String, val durationMs: Long, val fileSize: Long) : ConversionState()
    data class Error(val message: String) : ConversionState()
}
