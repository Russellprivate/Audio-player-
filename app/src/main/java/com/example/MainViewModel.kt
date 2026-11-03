package com.example

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AudioDatabase
import com.example.data.db.ConvertedAudio
import com.example.data.localization.AppLanguage
import com.example.data.localization.LocalizationRepository
import com.example.data.localization.LocalizedStrings
import com.example.engine.AudioBitrate
import com.example.engine.AudioChannels
import com.example.engine.AudioFormat
import com.example.engine.BatchQueueManager
import com.example.engine.ConversionConfig
import com.example.engine.ConversionState
import com.example.engine.VideoAudioConverter
import com.example.engine.VideoMetadata
import com.example.ui.theme.ThemeMode
import com.example.utils.AudioPlayerManager
import com.example.utils.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val db = AudioDatabase.getDatabase(context)
    private val audioDao = db.audioDao()

    val batchManager = BatchQueueManager(context, viewModelScope)
    val audioPlayer = AudioPlayerManager(context, viewModelScope)

    // Language & Theme State
    private val _currentLanguage = MutableStateFlow(AppLanguage.ENGLISH)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    val strings: StateFlow<LocalizedStrings> = combine(_currentLanguage) { lang ->
        LocalizationRepository.getStrings(lang[0])
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalizationRepository.getStrings(AppLanguage.ENGLISH))

    // Single Conversion State
    private val _selectedVideoUri = MutableStateFlow<Uri?>(null)
    val selectedVideoUri: StateFlow<Uri?> = _selectedVideoUri.asStateFlow()

    private val _videoMetadata = MutableStateFlow<VideoMetadata?>(null)
    val videoMetadata: StateFlow<VideoMetadata?> = _videoMetadata.asStateFlow()

    private val _videoThumbnail = MutableStateFlow<Bitmap?>(null)
    val videoThumbnail: StateFlow<Bitmap?> = _videoThumbnail.asStateFlow()

    private val _selectedFormat = MutableStateFlow(AudioFormat.M4A)
    val selectedFormat: StateFlow<AudioFormat> = _selectedFormat.asStateFlow()

    private val _selectedBitrate = MutableStateFlow(AudioBitrate.ORIGINAL)
    val selectedBitrate: StateFlow<AudioBitrate> = _selectedBitrate.asStateFlow()

    private val _selectedChannels = MutableStateFlow(AudioChannels.ORIGINAL)
    val selectedChannels: StateFlow<AudioChannels> = _selectedChannels.asStateFlow()

    private val _trimStartMs = MutableStateFlow(0L)
    val trimStartMs: StateFlow<Long> = _trimStartMs.asStateFlow()

    private val _trimEndMs = MutableStateFlow(0L)
    val trimEndMs: StateFlow<Long> = _trimEndMs.asStateFlow()

    private val _conversionState = MutableStateFlow<ConversionState>(ConversionState.Idle)
    val conversionState: StateFlow<ConversionState> = _conversionState.asStateFlow()

    // Library Filtering & Search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _formatFilter = MutableStateFlow("ALL")
    val formatFilter: StateFlow<String> = _formatFilter.asStateFlow()

    val convertedAudios: StateFlow<List<ConvertedAudio>> = combine(
        audioDao.getAllAudios(),
        _searchQuery,
        _formatFilter
    ) { list, query, filter ->
        list.filter { audio ->
            val matchesQuery = query.isBlank() || audio.title.contains(query, ignoreCase = true)
            val matchesFilter = filter == "ALL" || audio.format.equals(filter, ignoreCase = true)
            matchesQuery && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        NotificationHelper.initNotificationChannel(context)
    }

    fun setLanguage(language: AppLanguage) {
        _currentLanguage.value = language
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    fun selectVideo(uri: Uri) {
        _selectedVideoUri.value = uri
        _conversionState.value = ConversionState.Idle
        viewModelScope.launch(Dispatchers.IO) {
            val meta = VideoAudioConverter.getVideoMetadata(context, uri)
            _videoMetadata.value = meta
            _trimStartMs.value = 0L
            _trimEndMs.value = meta.durationMs

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                _videoThumbnail.value = retriever.getFrameAtTime(1000000)
                retriever.release()
            } catch (_: Exception) {
                _videoThumbnail.value = null
            }
        }
    }

    fun setFormat(format: AudioFormat) {
        _selectedFormat.value = format
    }

    fun setBitrate(bitrate: AudioBitrate) {
        _selectedBitrate.value = bitrate
    }

    fun setChannels(channels: AudioChannels) {
        _selectedChannels.value = channels
    }

    fun setTrimRange(startMs: Long, endMs: Long) {
        _trimStartMs.value = startMs
        _trimEndMs.value = endMs
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFormatFilter(filter: String) {
        _formatFilter.value = filter
    }

    fun startSingleConversion() {
        val uri = _selectedVideoUri.value ?: return
        val meta = _videoMetadata.value ?: return
        if (_conversionState.value is ConversionState.Progress) return

        val config = ConversionConfig(
            format = _selectedFormat.value,
            bitrate = _selectedBitrate.value,
            channels = _selectedChannels.value,
            trimStartMs = _trimStartMs.value,
            trimEndMs = _trimEndMs.value
        )

        viewModelScope.launch(Dispatchers.IO) {
            _conversionState.value = ConversionState.Progress(0, "Starting...", "")
            NotificationHelper.showProgressNotification(
                context,
                "Video to Audio - Russell Private Limited",
                "Converting: ${meta.title}",
                0
            )

            try {
                val convertedFile = VideoAudioConverter.convert(
                    context = context,
                    uri = uri,
                    config = config
                ) { pct, stage ->
                    _conversionState.value = ConversionState.Progress(pct, stage)
                    NotificationHelper.showProgressNotification(
                        context,
                        "Video to Audio - Russell Private Limited",
                        "$stage ($pct%)",
                        pct
                    )
                }

                val convertedDuration = if (config.trimEndMs > config.trimStartMs) {
                    config.trimEndMs - config.trimStartMs
                } else meta.durationMs

                // Insert into Room DB
                val entry = ConvertedAudio(
                    title = convertedFile.nameWithoutExtension,
                    filePath = convertedFile.absolutePath,
                    fileUri = Uri.fromFile(convertedFile).toString(),
                    originalVideoName = meta.title,
                    format = config.format.extension.uppercase(),
                    bitrateKbps = if (config.bitrate.kbps > 0) config.bitrate.kbps else 256,
                    durationMs = convertedDuration,
                    fileSizeBytes = convertedFile.length()
                )
                audioDao.insertAudio(entry)

                _conversionState.value = ConversionState.Success(
                    outputPath = convertedFile.absolutePath,
                    outputUri = entry.fileUri,
                    durationMs = convertedDuration,
                    fileSize = convertedFile.length()
                )

                // Show completion notification
                NotificationHelper.showCompleteNotification(
                    context,
                    "Conversion Finished!",
                    "Saved ${convertedFile.name} to Music/VideoToAudio",
                    "Russell Private Limited"
                )
            } catch (e: Exception) {
                _conversionState.value = ConversionState.Error(e.localizedMessage ?: "Conversion failed")
            }
        }
    }

    fun startBatchConversion() {
        val stringsVal = strings.value
        batchManager.startBatch(
            onItemComplete = { item, file ->
                val entry = ConvertedAudio(
                    title = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    fileUri = Uri.fromFile(file).toString(),
                    originalVideoName = item.title,
                    format = item.config.format.extension.uppercase(),
                    bitrateKbps = if (item.config.bitrate.kbps > 0) item.config.bitrate.kbps else 256,
                    durationMs = item.durationMs,
                    fileSizeBytes = file.length()
                )
                audioDao.insertAudio(entry)
            },
            onAllComplete = { success, _ ->
                NotificationHelper.showCompleteNotification(
                    context,
                    "Batch Conversion Complete!",
                    "Successfully converted $success audio files to device.",
                    "Russell Private Limited"
                )
            }
        )
    }

    fun toggleFavorite(audioId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            audioDao.toggleFavorite(audioId)
        }
    }

    fun deleteAudio(audio: ConvertedAudio) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(audio.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {}
            audioDao.deleteAudio(audio)
            if (audioPlayer.playerState.value.activeAudio?.id == audio.id) {
                audioPlayer.stop()
            }
        }
    }

    fun renameAudio(audioId: Long, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            audioDao.updateTitle(audioId, newTitle.trim())
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
