package com.zjh.akali.playback

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 课与课之间的停顿 */
const val GAP_BETWEEN_LESSONS_MS = 2000L

/** 界面发给服务的自定义命令：循环播放当前这课的 [ARG_START_MS, ARG_END_MS]；END 不大于 START 表示取消循环。 */
const val COMMAND_LOOP_RANGE = "akali.loop_range"
const val ARG_START_MS = "start_ms"
const val ARG_END_MS = "end_ms"

/**
 * 持有播放器的前台服务，独立于界面。退出界面后继续播放，锁屏和通知栏可控制。
 * 每课播完会暂停，停 2 秒后再继续下一课（或重复本课）。
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gapJob: Job? = null
    private var loopMessage: PlayerMessage? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.pauseAtEndOfMediaItems = true
        player.addListener(GapListener(player))
        session = MediaSession.Builder(this, player).setCallback(SessionCallback(player)).build()
    }

    /** 在 endMs 处发消息，播放到那里就跳回 startMs；ExoPlayer 按播放位置触发，比定时轮询准。 */
    private fun setLoop(player: ExoPlayer, startMs: Long, endMs: Long) {
        clearLoop()
        if (endMs <= startMs || player.currentMediaItem == null) return
        loopMessage = player.createMessage { _, _ -> player.seekTo(startMs) }
            .setLooper(Looper.getMainLooper())
            .setPosition(player.currentMediaItemIndex, endMs)
            .setDeleteAfterDelivery(false)
            .send()
    }

    private fun clearLoop() {
        loopMessage?.cancel()
        loopMessage = null
    }

    private inner class SessionCallback(private val player: ExoPlayer) : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_LOOP_RANGE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_LOOP_RANGE) {
                setLoop(player, args.getLong(ARG_START_MS), args.getLong(ARG_END_MS))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    private inner class GapListener(private val player: ExoPlayer) : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                gapJob?.cancel()
                return
            }
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                val continues = player.repeatMode == Player.REPEAT_MODE_ONE || player.hasNextMediaItem()
                if (continues) {
                    gapJob?.cancel()
                    gapJob = scope.launch {
                        delay(GAP_BETWEEN_LESSONS_MS)
                        player.play()
                    }
                }
            } else {
                gapJob?.cancel()
            }
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            clearLoop()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                gapJob?.cancel()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        clearLoop()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
