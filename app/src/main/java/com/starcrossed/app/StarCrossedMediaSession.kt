package com.starcrossed.app

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
private const val DIAG_TAG = "StarCrossedMedia"
 * StarCrossedMediaSession
 * ─────────────────────────────────────────────────────────────────────────
 * ARCHITECTURE DECISION (read this before touching this file):
 *
 * STAR CROSSED's actual audio engine is a Web Audio API graph living inside
 * the page's JS (dual-slot crossfade gain nodes, a 5-band BiquadFilter EQ,
 * a DynamicsCompressorNode, an AnalyserNode driving the visualizer, an
 * ambient synth oscillator...). None of that is reproducible by handing
 * playback to ExoPlayer — doing so would silently delete most of what this
 * project spent dozens of sessions building. So ExoPlayer is intentionally
 * NOT used here (notice media3-exoplayer isn't even a Gradle dependency).
 *
 * Instead: the WebView is the one and only audio source, full stop — this
 * class exists purely to make that WebView *controllable and visible* to
 * Android's OS-level media plumbing (notification, lockscreen, Bluetooth,
 * widget). It does this via androidx.media3.common.SimpleBasePlayer, which
 * is Media3's own documented mechanism for backing a MediaSession with a
 * player that isn't ExoPlayer (the same technique used for e.g. Cast
 * integrations). We implement the small set of abstract/overridable
 * methods; Media3 fills in the other ~40 Player interface methods for us.
 *
 * Every "handle*" override below does NOT change local player state itself
 * — it forwards the request to JS via `sendCommand`, and JS's own event
 * pipeline (ae.on('play'/'pause'), the RAF loop, etc.) reports the REAL
 * outcome back through the bridge, which flows into PlaybackStateManager,
 * which StarCrossedMediaService uses to call invalidateState(). This keeps
 * a single source of truth (the WebView) rather than two players racing
 * each other, exactly as required.
 *
 * VERIFIED AGAINST REAL SOURCE (androidx/media tags 1.2.1 and 1.4.1):
 *  - SimpleBasePlayer is itself @UnstableApi (RequiresOptIn ERROR level) —
 *    every type/file that subclasses or references it must opt in. This
 *    class is annotated accordingly.
 *  - Player.release() / stop() / prepare() are each gated by
 *    shouldHandleCommand(COMMAND_RELEASE / COMMAND_STOP / COMMAND_PREPARE):
 *    if that exact command isn't in availableCommands, the corresponding
 *    handle*() override is silently never invoked. COMMAND_RELEASE is
 *    included below for this reason.
 *  - BasePlayer.seekToNext()/seekToPrevious() (used by hardware/Bluetooth
 *    media-button routing and by our own widget) dispatch
 *    Player.COMMAND_SEEK_TO_NEXT / COMMAND_SEEK_TO_PREVIOUS (the *bare*
 *    constants), while DefaultMediaNotificationProvider always dispatches
 *    the tapped notification prev/next button as the corresponding
 *    *_MEDIA_ITEM variant, regardless of which pair made the button
 *    visible in the first place. Both pairs are declared below and treated
 *    identically in handleSeek() so every input path — notification tap,
 *    hardware button, widget tap — actually reaches JS.
 */
@OptIn(UnstableApi::class)
class WebViewProxyPlayer(
    applicationLooper: Looper,
    private val sendCommand: (JsCommand) -> Unit
) : SimpleBasePlayer(applicationLooper) {

    sealed class JsCommand {
        data class SetPlayWhenReady(val playWhenReady: Boolean) : JsCommand()
        object Next : JsCommand()
        object Previous : JsCommand()
        data class SeekTo(val positionMs: Long) : JsCommand()
        object SetFavorite : JsCommand()
        object Stop : JsCommand()
    }

    private val availableCommands: Player.Commands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD
        )
        .build()

    // Current known state, updated exclusively via updateFromJs() below and
    // reflected into Player via getState() + invalidateState(). This class
    // never mutates these outside of updateFromJs — the JS layer (via
    // PlaybackStateManager) is the single writer of ground truth.
    @Volatile private var title: String = "STAR CROSSED"
    @Volatile private var artist: String = ""
    @Volatile private var album: String = "STAR CROSSED"
    @Volatile private var durationMs: Long = 0L
    @Volatile private var positionMs: Long = 0L
    @Volatile private var isPlaying: Boolean = false
    @Volatile private var hasTrack: Boolean = false
    @Volatile private var artwork: Bitmap? = null

    /** Called by the service whenever PlaybackStateManager's flows emit. */
    fun updateFromJs(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        positionMs: Long,
        isPlaying: Boolean,
        hasTrack: Boolean,
        artwork: Bitmap?
    ) {
        Log.d(DIAG_TAG, "updateFromJs: isPlaying=$isPlaying hasTrack=$hasTrack posMs=$positionMs (was isPlaying=${this.isPlaying})")
        this.title = title
        this.artist = artist
        this.album = album
        this.durationMs = durationMs
        this.positionMs = positionMs
        this.isPlaying = isPlaying
        this.hasTrack = hasTrack
        this.artwork = artwork
        invalidateState()
    }

    /** Invoked by the session's custom-command handler (see [buildStarCrossedSession])
     *  when the system UI's heart/favorite notification button is tapped. Mirrors the
     *  in-page star tap exactly: JS owns the actual favorite bookkeeping and reports the
     *  real resulting state back through PlaybackStateManager, same as every other
     *  command here — this call never assumes the toggle succeeded. */
    fun requestFavoriteToggle() {
        sendCommand(JsCommand.SetFavorite)
    }

    override fun getState(): State {
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist.ifBlank { null })
            .setAlbumTitle(album)
            .apply { artwork?.let { setArtworkData(bitmapToBytes(it), MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(title.hashCode().toString())
            .setMediaMetadata(mediaMetadata)
            .build()

        val itemData = MediaItemData.Builder(/* uid = */ "star_crossed_current")
            .setMediaItem(mediaItem)
            .setDurationUs(if (durationMs > 0) Util.msToUs(durationMs) else C_TIME_UNSET_US)
            .build()

        val playbackState = when {
            !hasTrack -> Player.STATE_IDLE
            else -> Player.STATE_READY
        }

        return State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaylist(ImmutableList.of(itemData))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setIsLoading(false)
            .setContentPositionMs(positionMs.coerceAtLeast(0L))
            .build()
    }

    // ── Command handlers: forward to JS, let the JS event pipeline report
    //    the real result back through PlaybackStateManager -> updateFromJs.
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Log.d(DIAG_TAG, "handleSetPlayWhenReady($playWhenReady) — forwarding to JS")
        sendCommand(JsCommand.SetPlayWhenReady(playWhenReady))
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        Log.d(DIAG_TAG, "handlePrepare()")
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        Log.d(DIAG_TAG, "handleStop() — forwarding to JS")
        sendCommand(JsCommand.Stop)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        Log.d(DIAG_TAG, "handleSeek(seekCommand=$seekCommand, posMs=$positionMs)")
        // COMMAND_SEEK_TO_NEXT and COMMAND_SEEK_TO_NEXT_MEDIA_ITEM (same for
        // PREVIOUS) are deliberately treated identically here — see the class
        // doc comment above for why both must be declared AND handled alike.
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT ->
                sendCommand(JsCommand.Next)
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS ->
                sendCommand(JsCommand.Previous)
            else -> sendCommand(JsCommand.SeekTo(positionMs.coerceAtLeast(0L)))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        Log.d(DIAG_TAG, "handleRelease()")
        return Futures.immediateVoidFuture()
    }

    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    companion object {
        // Media3/ExoPlayer's C.TIME_UNSET, restated here to avoid pulling in
        // the exoplayer-core artifact just for one constant; the numeric
        // value (Long.MIN_VALUE + 1) is stable/public API in androidx.media3.common.C.
        private const val C_TIME_UNSET_US = Long.MIN_VALUE + 1
        // TEMPORARY DIAGNOSTIC (2026-09-03) — shared with StarCrossedMediaService's
        // own tag so `adb logcat -s SCMediaService SCWebBridge` shows one correlated,
        // chronological stream across native session/service/bridge and (via
        // StarCrossedWebBridge.log) the JS side. Remove alongside the rest of this
        // pass's logging once root cause is confirmed.
        private const val DIAG_TAG = "SCMediaService"
    }
}

/** Custom session command: system UI (notification heart button) asking to toggle the
 *  favorite status of the currently playing track. Not a standard Player.Command — Media3
 *  has no generic "rating" hook on SimpleBasePlayer — so this rides Media3's custom-command
 *  channel instead, dispatched through [MediaSession.Callback.onCustomCommand]. */
const val ACTION_TOGGLE_FAVORITE = "com.starcrossed.app.TOGGLE_FAVORITE"
private val TOGGLE_FAVORITE_COMMAND = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)

/** Builds the heart-icon CommandButton reflecting current favorite status, for both the
 *  session's default custom layout and every subsequent update pushed by the service. */
@OptIn(UnstableApi::class)
fun buildFavoriteCommandButton(isFavorite: Boolean): CommandButton =
    CommandButton.Builder(if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
        .setSessionCommand(TOGGLE_FAVORITE_COMMAND)
        .setDisplayName(if (isFavorite) "Remove favorite" else "Add favorite")
        .setEnabled(true)
        .build()

/**
 * Builds the MediaSession that wraps [WebViewProxyPlayer]. Kept as a small
 * free function (rather than a class) since there is exactly one session
 * for the app's lifetime and it needs no internal state of its own beyond
 * what MediaSession.Builder already manages — a class here would just be a
 * constructor wrapper.
 *
 * The Callback below intentionally does NOT call
 * AcceptedResultBuilder.setAvailablePlayerCommands(...): per Media3's own
 * documented contract that value is INTERSECTED with the player's actual
 * Player.Commands, never unions with it, so leaving it at its permissive
 * default correctly falls through to exactly what WebViewProxyPlayer itself
 * reports — no separate list to accidentally let drift out of sync.
 */
@OptIn(UnstableApi::class)
fun buildStarCrossedSession(
    context: Context,
    player: WebViewProxyPlayer,
    sessionActivity: PendingIntent
): MediaSession {
    val callback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(DIAG_TAG, "onConnect: controller pkg=${controller.packageName} uid=${controller.uid}")
            val sessionCommands = SessionCommands.Builder()
                .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands)
                .add(TOGGLE_FAVORITE_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(ImmutableList.of(buildFavoriteCommandButton(isFavorite = false)))
                .build()
        }

        // TEMPORARY DIAGNOSTIC (2026-09-03): onPlayerCommandRequest is
        // @Deprecated in favor of MediaSession#getControllerForCurrentRequest()
        // called from within the Player itself, but that requires threading a
        // Player -> MediaSession back-reference through the constructor that
        // doesn't otherwise exist — not worth the structural change for a
        // temporary, removable diagnostic. Still fully functional in 1.4.1.
        // Always returns RESULT_SUCCESS — identical to the implicit default —
        // so this changes no behavior; it only reveals which controller
        // (system notification / lockscreen / Bluetooth / this app's own
        // widget-triggered calls, which don't go through a MediaController at
        // all and so won't appear here) is asking for COMMAND_PLAY_PAUSE etc.,
        // and when, relative to the other logs in this stream.
        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            Log.d(DIAG_TAG, "onPlayerCommandRequest: pkg=${controller.packageName} command=$playerCommand")
            return SessionResult.RESULT_SUCCESS
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_FAVORITE) {
                player.requestFavoriteToggle()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    return MediaSession.Builder(context, player)
        .setId("StarCrossedSession")
        .setSessionActivity(sessionActivity)
        .setCallback(callback)
        .build()
}
