package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class BatchItemStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class BatchItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val fileSize: Long,
    val resolution: String,
    val thumbnail: Bitmap? = null,
    val config: ConversionConfig = ConversionConfig(),
    val status: BatchItemStatus = BatchItemStatus.PENDING,
    val progress: Int = 0,
    val outputFile: File? = null,
    val errorMessage: String? = null
)

data class BatchOverallState(
    val isRunning: Boolean = false,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val overallProgress: Float = 0f
)

class BatchQueueManager(private val context: Context, private val scope: CoroutineScope) {
    private val _queue = MutableStateFlow<List<BatchItem>>(emptyList())
    val queue: StateFlow<List<BatchItem>> = _queue.asStateFlow()

    private val _batchState = MutableStateFlow(BatchOverallState())
    val batchState: StateFlow<BatchOverallState> = _batchState.asStateFlow()

    private var batchJob: Job? = null

    fun addVideos(uris: List<Uri>, defaultConfig: ConversionConfig = ConversionConfig()) {
        scope.launch(Dispatchers.IO) {
            val newItems = uris.map { uri ->
                val meta = VideoAudioConverter.getVideoMetadata(context, uri)
                var thumb: Bitmap? = null
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    thumb = retriever.getFrameAtTime(1000000)
                    retriever.release()
                } catch (_: Exception) {}

                BatchItem(
                    uri = uri,
                    title = meta.title,
                    durationMs = meta.durationMs,
                    fileSize = meta.fileSizeBytes,
                    resolution = meta.resolution,
                    thumbnail = thumb,
                    config = defaultConfig
                )
            }
            _queue.value = _queue.value + newItems
            updateBatchStateSummary()
        }
    }

    fun removeItem(id: String) {
        _queue.value = _queue.value.filterNot { it.id == id }
        updateBatchStateSummary()
    }

    fun clearQueue() {
        if (_batchState.value.isRunning) {
            cancelBatch()
        }
        _queue.value = emptyList()
        updateBatchStateSummary()
    }

    fun updateGlobalConfig(format: AudioFormat, bitrate: AudioBitrate) {
        _queue.value = _queue.value.map { item ->
            if (item.status == BatchItemStatus.PENDING) {
                item.copy(config = item.config.copy(format = format, bitrate = bitrate))
            } else item
        }
    }

    fun updateItemConfig(id: String, config: ConversionConfig) {
        _queue.value = _queue.value.map {
            if (it.id == id) it.copy(config = config) else it
        }
    }

    fun startBatch(
        onItemComplete: suspend (BatchItem, File) -> Unit,
        onAllComplete: (Int, Int) -> Unit
    ) {
        if (_batchState.value.isRunning) return
        batchJob?.cancel()

        batchJob = scope.launch(Dispatchers.IO) {
            val pendingItems = _queue.value.filter { it.status == BatchItemStatus.PENDING || it.status == BatchItemStatus.FAILED }
            if (pendingItems.isEmpty()) return@launch

            _batchState.value = _batchState.value.copy(
                isRunning = true,
                totalCount = _queue.value.size
            )

            var successCount = 0
            var failCount = 0

            for ((index, item) in _queue.value.withIndex()) {
                if (item.status != BatchItemStatus.PENDING && item.status != BatchItemStatus.FAILED) {
                    if (item.status == BatchItemStatus.COMPLETED) successCount++
                    continue
                }

                // Mark current item processing
                updateItem(item.id) { it.copy(status = BatchItemStatus.PROCESSING, progress = 0) }
                _batchState.value = _batchState.value.copy(
                    currentIndex = index + 1,
                    overallProgress = index.toFloat() / _queue.value.size.coerceAtLeast(1)
                )

                try {
                    val convertedFile = VideoAudioConverter.convert(
                        context = context,
                        uri = item.uri,
                        config = item.config
                    ) { pct, _ ->
                        updateItem(item.id) { it.copy(progress = pct) }
                    }

                    updateItem(item.id) {
                        it.copy(
                            status = BatchItemStatus.COMPLETED,
                            progress = 100,
                            outputFile = convertedFile
                        )
                    }
                    successCount++
                    onItemComplete(item, convertedFile)
                } catch (e: Exception) {
                    updateItem(item.id) {
                        it.copy(
                            status = BatchItemStatus.FAILED,
                            errorMessage = e.localizedMessage ?: "Failed"
                        )
                    }
                    failCount++
                }

                _batchState.value = _batchState.value.copy(
                    completedCount = successCount,
                    failedCount = failCount,
                    overallProgress = (index + 1).toFloat() / _queue.value.size.coerceAtLeast(1)
                )
            }

            _batchState.value = _batchState.value.copy(isRunning = false)
            onAllComplete(successCount, failCount)
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        _queue.value = _queue.value.map {
            if (it.status == BatchItemStatus.PROCESSING) {
                it.copy(status = BatchItemStatus.CANCELLED)
            } else it
        }
        _batchState.value = _batchState.value.copy(isRunning = false)
    }

    private fun updateItem(id: String, transform: (BatchItem) -> BatchItem) {
        _queue.value = _queue.value.map { if (it.id == id) transform(it) else it }
    }

    private fun updateBatchStateSummary() {
        val total = _queue.value.size
        val completed = _queue.value.count { it.status == BatchItemStatus.COMPLETED }
        val failed = _queue.value.count { it.status == BatchItemStatus.FAILED }
        _batchState.value = _batchState.value.copy(
            totalCount = total,
            completedCount = completed,
            failedCount = failed,
            overallProgress = if (total > 0) completed.toFloat() / total else 0f
        )
    }
}
