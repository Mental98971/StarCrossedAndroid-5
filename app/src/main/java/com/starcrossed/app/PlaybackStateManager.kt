package com.starcrossed.app

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * PlaybackStateManager
 * ─────────────────────────────────────────────────────────────────────────
 * Single source of truth shared between:
 *   - StarCrossedWebBridge   (writes: JS → here, on any thread — WebView's
 *                              @JavascriptInterface callbacks run on a
 *                              dedicated background thread, never the UI
 *                              thread, so every field here MUST be safe to
 *                              write from a non-main thread. MutableStateFlow
 *                              assignment is atomic/thread-safe by design.)
 *   - StarCrossedMediaService (reads: collects these flows on Dispatchers.Main
 *                              to drive the proxy Player's invalidateState(),
 *                              the notification, and the widget.)
 *   - MainActivity            (reads: notification-permission priming, and
 *                              file-chooser requests, since only an Activity
 *                              can launch either; also to know when it's
 *                              safe to attach the WebView.)
 *
 * Deliberately a plain singleton `object`, not injected — this app has one
 * process, one playback session, and one WebView. A DI graph would add
 * ceremony without adding correctness here.
 */
object PlaybackStateManager {

    data class TrackMetadata(
        val title: String = "STAR CROSSED",
        val artist: String = "",
        val album: String = "STAR CROSSED",
        val durationMs: Long = 0L,
        val isLocalTrack: Boolean = false,
        val trackKey: String = "",
        val artworkUrl: String = ""
    )

    data class PlaybackStatus(
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val isFavorite: Boolean = false,
        val hasTrack: Boolean = false
    )

    /** Current track metadata as last reported by the JS layer. */
    val metadata = MutableStateFlow(TrackMetadata())

    /** Current transport status as last reported by the JS layer. */
    val status = MutableStateFlow(PlaybackStatus())

    /** Decoded artwork bitmap, once available (base64 from JS canvas export,
     *  or a native fallback fetch of [TrackMetadata.artworkUrl]). Null means
     *  "use the default STAR CROSSED mark", not "loading" — no spinner state
     *  is needed for a notification icon. */
    val artwork = MutableStateFlow<Bitmap?>(null)

    /** True once the JS App instance has finished booting (DB opened,
     *  library synced, splash dismissed) — see the `_androidCall('ready')`
     *  hook added to _init(). Used to safely defer any command that arrived
     *  before the page was ready to actually receive it (cold widget tap). */
    val appReady = MutableStateFlow(false)

    /** A single queued command (e.g. "next") received before [appReady] was
     *  true. Drained by the service the moment appReady flips. Deliberately
     *  scoped to at most ONE pending action — see StarCrossedMediaService
     *  README notes on why this app does not attempt full cold-start
     *  "resume the last track" behavior. */
    val pendingColdStartAction = MutableStateFlow<String?>(null)

    /** JS asked (or the service inferred, on first play) that we prompt for
     *  POST_NOTIFICATIONS. Collected by MainActivity, which is the only
     *  component that can actually launch the permission request. */
    val notificationPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once when the WebView finishes an HTML/DOM parse (WebViewClient
     *  onPageFinished) — NOT the same as [appReady]; a page can finish
     *  parsing well before the async App._init() (DB open, library sync)
     *  completes. Kept mainly for diagnostics / optional future use. */
    val pageParsed = MutableStateFlow(false)

    /** JS's <input type="file"> was tapped — covers "Import Media"/"Browse
     *  Files" (audio) and the playlist/config JSON pickers, which all share
     *  this one WebView mechanism — and needs a native picker launched.
     *  Collected by MainActivity, since only an Activity can launch one;
     *  StarCrossedMediaService's WebChromeClient holds the pending
     *  ValueCallback itself and completes it via completeFileChooser() once
     *  MainActivity reports back. Carries the platform FileChooserParams
     *  object directly (rather than a hand-rolled equivalent) so
     *  MainActivity can use FileChooserParams.createIntent()/parseResult()
     *  — Android's own matched pair for this exact handoff, more robust
     *  than reimplementing that pairing by hand. */
    val fileChooserRequests = MutableSharedFlow<WebChromeClient.FileChooserParams>(extraBufferCapacity = 1)

    /**
     * Called when the JS layer reports the track/queue has gone idle
     * (App._stopPlayback -> `_androidCall('clearTrack')`), and also used
     * defensively on WebView reload so a stale notification never lingers.
     */
    fun reset() {
        metadata.value = TrackMetadata()
        status.value = PlaybackStatus()
        artwork.value = null
    }
}
