package com.example.engine

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object VideoAudioConverter {
    private const val TAG = "VideoAudioConverter"
    private const val TIMEOUT_US = 10000L

    fun getOutputDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "VideoToAudio")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getVideoMetadata(context: Context, uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        var resolution = "N/A"
        var sampleRate = 44100
        var channelCount = 2
        var audioMime: String? = null
        var title = "Video"
        var fileSize = 0L

        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (width != null && height != null) {
                resolution = "${width}x${height}"
            }
            audioMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read metadata with retriever: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // Query file name and size from content resolver
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        title = cursor.getString(nameIndex) ?: title
                    }
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Content resolver query error: ${e.message}")
        }

        // Inspect audio track using extractor
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioMime = mime
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Extractor probe error: ${e.message}")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        val cleanTitle = title.substringBeforeLast(".")
        return VideoMetadata(
            uriString = uri.toString(),
            title = cleanTitle,
            durationMs = durationMs,
            fileSizeBytes = fileSize,
            resolution = resolution,
            audioMime = audioMime,
            sampleRate = sampleRate,
            channelCount = channelCount
        )
    }

    suspend fun convert(
        context: Context,
        uri: Uri,
        config: ConversionConfig,
        onProgress: (Int, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val meta = getVideoMetadata(context, uri)
        val outDir = getOutputDirectory(context)
        val safeBaseName = meta.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val timestamp = System.currentTimeMillis() % 100000
        val ext = config.format.extension
        val targetFile = File(outDir, "${safeBaseName}_audio_$timestamp.$ext")

        val trimStartUs = config.trimStartMs * 1000L
        val trimEndUs = if (config.trimEndMs > config.trimStartMs) config.trimEndMs * 1000L else Long.MAX_VALUE
        val totalUs = if (trimEndUs != Long.MAX_VALUE) (trimEndUs - trimStartUs) else (meta.durationMs * 1000L)

        // Path 1: Direct Lossless Stream Copy (if M4A and ORIGINAL bitrate and no trim)
        if (config.format == AudioFormat.M4A && config.bitrate == AudioBitrate.ORIGINAL && config.trimStartMs == 0L && config.trimEndMs == 0L) {
            try {
                onProgress(10, "Direct extraction...")
                val success = extractDirectAudioStream(context, uri, targetFile, onProgress)
                if (success) {
                    onProgress(100, "Done")
                    scanAndRegisterFile(context, targetFile, config.format.mimeType)
                    return@withContext targetFile
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct copy failed, falling back to decoding: ${e.message}")
            }
        }

        // Path 2: WAV Lossless PCM extraction
        if (config.format == AudioFormat.WAV) {
            onProgress(15, "Decoding audio...")
            extractToWav(context, uri, targetFile, trimStartUs, trimEndUs, totalUs, onProgress)
            onProgress(100, "Done")
            scanAndRegisterFile(context, targetFile, config.format.mimeType)
            return@withContext targetFile
        }

        // Path 3: Transcode to AAC / M4A / MP3 with chosen bitrate
        onProgress(15, "Transcoding audio...")
        transcodeAudio(context, uri, targetFile, config, trimStartUs, trimEndUs, totalUs, onProgress)
        onProgress(100, "Done")
        scanAndRegisterFile(context, targetFile, config.format.mimeType)
        targetFile
    }

    private fun extractDirectAudioStream(
        context: Context,
        uri: Uri,
        outFile: File,
        onProgress: (Int, String) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
            extractor.setDataSource(pfd.fileDescriptor)
            pfd.close()

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return false
            }

            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 512
            }
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            var samplesCopied = 0
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                samplesCopied++
                if (samplesCopied % 100 == 0) {
                    onProgress(40 + (samplesCopied % 50), "Extracting...")
                }
                extractor.advance()
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Direct stream extract error", e)
            return false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private suspend fun extractToWav(
        context: Context,
        uri: Uri,
        outFile: File,
        trimStartUs: Long,
        trimEndUs: Long,
        totalUs: Long,
        onProgress: (Int, String) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var fos: FileOutputStream? = null

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open video file")
            extractor.setDataSource(pfd.fileDescriptor)
            pfd.close()

            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = fmt
                    break
                }
            }

            if (trackIndex == -1 || trackFormat == null) {
                throw IllegalStateException("No audio track found in video")
            }

            extractor.selectTrack(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            var sampleRate = 44100
            var channels = 2
            if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }

            fos = FileOutputStream(outFile)
            // Write placeholder header (44 bytes)
            fos.write(WavWriter.createWavHeader(sampleRate, channels, 16, 0))

            val info = MediaCodec.BufferInfo()
            var isEos = false
            var totalPcmBytes = 0L

            if (trimStartUs > 0) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            while (!isEos && coroutineContext.isActive) {
                // Feed decoder
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = decoder.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        val sampleTime = extractor.sampleTime

                        if (sampleSize < 0 || sampleTime > trimEndUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read decoder
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0 && info.presentationTimeUs >= trimStartUs) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        outBuf.get(bytes)
                        fos.write(bytes)
                        totalPcmBytes += info.size

                        val elapsed = (info.presentationTimeUs - trimStartUs).coerceAtLeast(0L)
                        val progress = if (totalUs > 0) ((elapsed.toFloat() / totalUs) * 85).toInt().coerceIn(10, 95) else 50
                        onProgress(progress, "Converting to WAV ($progress%)")
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }

            fos.flush()
            fos.close()
            fos = null

            // Update WAV header with accurate totalAudioLen
            WavWriter.updateWavHeaderLengths(outFile, totalPcmBytes)
        } finally {
            try { fos?.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {}
        }
    }

    private suspend fun transcodeAudio(
        context: Context,
        uri: Uri,
        outFile: File,
        config: ConversionConfig,
        trimStartUs: Long,
        trimEndUs: Long,
        totalUs: Long,
        onProgress: (Int, String) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open video file")
            extractor.setDataSource(pfd.fileDescriptor)
            pfd.close()

            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = fmt
                    break
                }
            }

            if (trackIndex == -1 || trackFormat == null) {
                throw IllegalStateException("No audio track found in video")
            }

            extractor.selectTrack(trackIndex)
            val inMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
            decoder = MediaCodec.createDecoderByType(inMime)
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            var sampleRate = 44100
            var channels = 2
            if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            if (config.channels == AudioChannels.MONO) channels = 1
            if (config.channels == AudioChannels.STEREO) channels = 2

            val targetBitrate = if (config.bitrate.kbps > 0) config.bitrate.kbps * 1000 else 192000

            val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 64)

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            if (trimStartUs > 0) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val decoderInfo = MediaCodec.BufferInfo()
            val encoderInfo = MediaCodec.BufferInfo()
            var extractorEos = false
            var decoderEos = false
            var encoderEos = false

            while (!encoderEos && coroutineContext.isActive) {
                // 1. Feed Extractor to Decoder
                if (!extractorEos) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            val sampleTime = extractor.sampleTime
                            if (sampleSize < 0 || sampleTime > trimEndUs) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorEos = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Read PCM from Decoder & Feed to Encoder
                if (!decoderEos) {
                    val decOutIdx = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)
                    if (decOutIdx >= 0) {
                        val pcmBuf = decoder.getOutputBuffer(decOutIdx)
                        if ((decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderEos = true
                        }
                        if (pcmBuf != null && decoderInfo.size > 0 && decoderInfo.presentationTimeUs >= trimStartUs) {
                            pcmBuf.position(decoderInfo.offset)
                            pcmBuf.limit(decoderInfo.offset + decoderInfo.size)

                            // Queue into encoder
                            var encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            while (encInIdx < 0 && coroutineContext.isActive) {
                                drainEncoder(encoder, encoderInfo, muxer, muxerStarted, onProgress, trimStartUs, totalUs) { started ->
                                    muxerStarted = started
                                }
                                encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            }
                            if (encInIdx >= 0) {
                                val encInBuf = encoder.getInputBuffer(encInIdx)
                                if (encInBuf != null) {
                                    encInBuf.clear()
                                    encInBuf.put(pcmBuf)
                                    val flags = if (decoderEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                    encoder.queueInputBuffer(
                                        encInIdx,
                                        0,
                                        decoderInfo.size,
                                        decoderInfo.presentationTimeUs - trimStartUs,
                                        flags
                                    )
                                }
                            }
                        } else if (decoderEos) {
                            val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIdx >= 0) {
                                encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)
                    }
                }

                // 3. Drain Encoder to Muxer
                val encOutIdx = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)
                if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw IllegalStateException("Encoder format changed twice")
                    }
                    val newFmt = encoder.outputFormat
                    muxerTrackIndex = muxer.addTrack(newFmt)
                    muxer.start()
                    muxerStarted = true
                } else if (encOutIdx >= 0) {
                    val encOutBuf = encoder.getOutputBuffer(encOutIdx)
                    if (encOutBuf != null && encoderInfo.size > 0 && muxerStarted) {
                        encOutBuf.position(encoderInfo.offset)
                        encOutBuf.limit(encoderInfo.offset + encoderInfo.size)
                        muxer.writeSampleData(muxerTrackIndex, encOutBuf, encoderInfo)

                        val elapsed = encoderInfo.presentationTimeUs.coerceAtLeast(0L)
                        val progress = if (totalUs > 0) ((elapsed.toFloat() / totalUs) * 85).toInt().coerceIn(10, 95) else 50
                        onProgress(progress, "Encoding (${config.bitrate.label})... $progress%")
                    }
                    if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderEos = true
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                }
            }
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {}
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        muxerStarted: Boolean,
        onProgress: (Int, String) -> Unit,
        trimStartUs: Long,
        totalUs: Long,
        onMuxerStarted: (Boolean) -> Unit
    ) {
        var started = muxerStarted
        var trackIdx = 0
        while (true) {
            val idx = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIdx = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                started = true
                onMuxerStarted(true)
            } else if (idx >= 0) {
                val outBuf = encoder.getOutputBuffer(idx)
                if (outBuf != null && bufferInfo.size > 0 && started) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIdx, outBuf, bufferInfo)
                }
                encoder.releaseOutputBuffer(idx, false)
            } else {
                break
            }
        }
    }

    private fun scanAndRegisterFile(context: Context, file: File, mimeType: String) {
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType)
        ) { path, uri ->
            Log.d(TAG, "Scanned $path: uri=$uri")
        }

        // Also add to MediaStore Audio collection if possible
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VideoToAudio")
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore insert notification note: ${e.message}")
        }
    }
}
