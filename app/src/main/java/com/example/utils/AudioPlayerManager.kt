package com.example.utils

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import com.example.data.db.ConvertedAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val isLooping: Boolean = false,
    val activeAudio: ConvertedAudio? = null,
    val previewFileTitle: String? = null
)

class AudioPlayerManager(private val context: Context, private val scope: CoroutineScope) {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    fun playAudio(audio: ConvertedAudio) {
        val file = File(audio.filePath)
        if (!file.exists()) return

        stop()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                isLooping = _playerState.value.isLooping
                applySpeed(_playerState.value.speed)
                start()
                setOnCompletionListener {
                    _playerState.value = _playerState.value.copy(isPlaying = false, currentPositionMs = 0)
                    stopProgressTracking()
                }
            }

            _playerState.value = _playerState.value.copy(
                isPlaying = true,
                durationMs = mediaPlayer?.duration?.toLong() ?: audio.durationMs,
                currentPositionMs = 0L,
                activeAudio = audio,
                previewFileTitle = audio.title
            )

            startProgressTracking()
        } catch (_: Exception) {
            stop()
        }
    }

    fun playPreviewFile(file: File, title: String) {
        if (!file.exists()) return
        stop()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                isLooping = _playerState.value.isLooping
                applySpeed(_playerState.value.speed)
                start()
                setOnCompletionListener {
                    _playerState.value = _playerState.value.copy(isPlaying = false, currentPositionMs = 0)
                    stopProgressTracking()
                }
            }

            _playerState.value = _playerState.value.copy(
                isPlaying = true,
                durationMs = mediaPlayer?.duration?.toLong() ?: 0L,
                currentPositionMs = 0L,
                activeAudio = null,
                previewFileTitle = title
            )

            startProgressTracking()
        } catch (_: Exception) {
            stop()
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            _playerState.value = _playerState.value.copy(isPlaying = false)
            stopProgressTracking()
        } else {
            player.start()
            _playerState.value = _playerState.value.copy(isPlaying = true)
            startProgressTracking()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        _playerState.value = _playerState.value.copy(currentPositionMs = positionMs)
    }

    fun setSpeed(speed: Float) {
        _playerState.value = _playerState.value.copy(speed = speed)
        applySpeed(speed)
    }

    fun toggleLoop() {
        val next = !_playerState.value.isLooping
        _playerState.value = _playerState.value.copy(isLooping = next)
        mediaPlayer?.isLooping = next
    }

    fun stop() {
        stopProgressTracking()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        _playerState.value = _playerState.value.copy(isPlaying = false, currentPositionMs = 0L)
    }

    private fun applySpeed(speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.let { player ->
                    val params = player.playbackParams ?: PlaybackParams()
                    params.speed = speed
                    player.playbackParams = params
                }
            } catch (_: Exception) {}
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive && mediaPlayer != null && _playerState.value.isPlaying) {
                val current = mediaPlayer?.currentPosition?.toLong() ?: 0L
                _playerState.value = _playerState.value.copy(currentPositionMs = current)
                delay(100)
            }
        }
    }

    private fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stop()
    }
}
