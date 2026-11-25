package es.tartamoto.radionoche

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Reproductor en segundo plano (sigue sonando con la pantalla apagada). */
@UnstableApi
class PlayerService : MediaSessionService() {

    companion object {
        @Volatile var instance: PlayerService? = null
        const val LIVE_ID = "directo"

        fun liveItem(url: String): MediaItem = MediaItem.Builder()
            .setMediaId(LIVE_ID)
            .setUri(url)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(url)).build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle("COPE en directo").setArtist("Radio Noche").build()
            )
            .build()
    }

    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Momento en que se apagará (0 = sin temporizador). */
    var sleepAt = 0L
        private set
    private var liveUrlIndex = 0
    private var liveErrors = 0
    private var recErrors = 0

    private val tick = object : Runnable {
        override fun run() {
            savePosition()
            checkSleep()
            handler.postDelayed(this, 1000)
        }
    }
    private var tickCount = 0

    override fun onCreate() {
        super.onCreate()
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SwitchDataSource.Factory(this), extractors))
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId
                if (id == LIVE_ID && liveErrors < 10) {
                    // Probar la siguiente dirección de la emisora
                    liveErrors++
                    liveUrlIndex++
                    val urls = Prefs.urls(this@PlayerService)
                    player.setMediaItem(liveItem(urls[liveUrlIndex % urls.size]))
                    player.prepare()
                    player.play()
                } else if (id != null && id != LIVE_ID && recErrors < 5) {
                    recErrors++
                    // Grabación: reintentar desde donde iba
                    val pos = player.currentPosition
                    player.prepare()
                    player.seekTo(pos)
                    player.play()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { liveErrors = 0; recErrors = 0 }
            }
        })

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val out = mediaItems.map { item ->
                        val u = item.requestMetadata.mediaUri
                        if (u != null) item.buildUpon().setUri(u).build() else item
                    }.toMutableList()
                    return Futures.immediateFuture(out)
                }
            })
            .build()

        instance = this
        handler.post(tick)
    }

    fun playLive() {
        val urls = Prefs.urls(this)
        liveErrors = 0
        player.setMediaItem(liveItem(urls[liveUrlIndex % urls.size]))
        player.prepare()
        player.play()
    }

    fun setSleep(minutes: Int) {
        sleepAt = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        player.volume = 1f
    }

    private fun checkSleep() {
        if (sleepAt == 0L) return
        val left = sleepAt - System.currentTimeMillis()
        if (left <= 0) {
            player.pause()
            player.volume = 1f
            sleepAt = 0L
        } else if (left < 20_000) {
            player.volume = (left / 20_000f).coerceIn(0.05f, 1f) // baja poco a poco
        }
    }

    private fun savePosition() {
        if (++tickCount % 5 != 0) return
        val id = player.currentMediaItem?.mediaId ?: return
        if (id == LIVE_ID) return
        if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
            Prefs.setPosition(this, id, player.currentPosition)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        tickCount = 4
        savePosition()
        handler.removeCallbacks(tick)
        instance = null
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }
}
