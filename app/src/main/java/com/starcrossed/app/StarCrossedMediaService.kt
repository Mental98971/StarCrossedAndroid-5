package com.starcrossed.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * StarCrossedMediaService
 * ─────────────────────────────────────────────────────────────────────────
 * See StarCrossedMediaSession.kt for the full architecture write-up. Short
 * version: this service owns the ONE WebView instance for the app's entire
 * process lifetime. MainActivity borrows that WebView's View (attaches it
 * into its layout) whenever it's in the foreground, and detaches — but
 * never destroys — it otherwise. Because the WebView itself is never
 * destroyed, its JS keeps running (and therefore the Web Audio graph keeps
 * producing sound) regardless of Activity lifecycle, exactly as long as
 * this Service is alive. `android:stopWithTask="false"` (set in the
 * manifest) plus becoming a foreground service the moment playback starts
 * is what keeps this Service — and therefore the WebView — alive when the
 * user swipes STAR CROSSED away from Recents.
 *
 * Extending MediaSessionService (rather than a plain Service) means Media3
 * automatically manages the media-style notification and its foreground
 * lifecycle for us, driven purely by the player state changes we report via
 * WebViewProxyPlayer.updateFromJs()/invalidateState() — we deliberately do
 * NOT hand-call startForeground()/stopForeground() ourselves anywhere in
 * this file, trusting the framework's own well-tested state machine instead
 * of re-implementing it.
 */
@OptIn(UnstableApi::class)
class StarCrossedMediaService : MediaSessionService() {

    lateinit var webView: WebView
        private set

    private lateinit var player: WebViewProxyPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: AudioManager

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    private var audioFocusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false
    private var duckedByFocusLoss = false
    private var wasPlaying = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var becomingNoisyRegistered = false

    /** The file chooser currently awaiting a result, if any — set in
     *  onShowFileChooser (setupWebView), resolved via completeFileChooser().
     *  Main-thread only: both onShowFileChooser and completeFileChooser run
     *  on the UI thread (WebView callbacks and MainActivity's
     *  ActivityResultLauncher callback both do), so no synchronization is
     *  needed here beyond that. */
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): StarCrossedMediaService = this@StarCrossedMediaService
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Fires for BOTH wired-headphone unplug and Bluetooth A2DP
            // disconnect — this single OS broadcast is the documented,
            // correct signal for "audio is about to become audible on the
            // device's own speaker unexpectedly" and covers the headset/
            // Bluetooth-disconnect requirement without any custom Bluetooth
            // state tracking.
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (::player.isInitialized && player.isPlaying) player.pause()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "audio focus change: ${focusChangeName(change)}")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                abandonAudioFocus()
                if (::player.isInitialized && player.isPlaying) {
                    pausedByFocusLoss = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (::player.isInitialized && player.isPlaying) {
                    pausedByFocusLoss = true
                    player.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (::player.isInitialized && player.isPlaying) {
                    duckedByFocusLoss = true
                    // Capture the user's real target volume into a JS global
                    // BEFORE lowering it, so we restore to the correct value
                    // later rather than the ducked one (ae.setVolume also
                    // overwrites the "remembered" volume field, not just the
                    // live gain — see AudioEngine.setVolume in the HTML).
                    evalJs(
                        "(function(){try{var v=(window.app&&window.app.ae)?window.app.ae.volume:0.75;" +
                            "window.__scPreDuckVol=v;if(window.app&&window.app.ae)window.app.ae.setVolume(Math.min(v,0.25));}catch(e){}})();"
                    )
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (duckedByFocusLoss) {
                    duckedByFocusLoss = false
                    evalJs(
                        "(function(){try{if(window.app&&window.app.ae&&typeof window.__scPreDuckVol==='number')" +
                            "window.app.ae.setVolume(window.__scPreDuckVol);}catch(e){}})();"
                    )
                }
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    if (::player.isInitialized) player.play()
                }
            }
        }
    }

    private fun focusChangeName(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
        else -> "unknown($change)"
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun onCreate() {
        Log.d(TAG, "onCreate() — service instance ${System.identityHashCode(this)}")
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupWebView()
        player = WebViewProxyPlayer(Looper.getMainLooper(), ::dispatchToJs)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = buildStarCrossedSession(this, player, sessionActivity)

        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        becomingNoisyRegistered = true

        observeState()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        Log.d(TAG, "onGetSession() — controller pkg=${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_LOCAL_BIND) binder else super.onBind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            StarCrossedWidget.ACTION_PLAY_PAUSE, StarCrossedWidget.ACTION_NEXT, StarCrossedWidget.ACTION_PREVIOUS -> {
                if (PlaybackStateManager.appReady.value) {
                    executeWidgetAction(intent.action!!)
                } else {
                    // App/WebView not initialized yet (cold widget tap). Queue the
                    // single requested action for when `ready()` fires, and bring
                    // the app forward so the user sees it booting rather than a
                    // widget tap that appears to silently do nothing.
                    PlaybackStateManager.pendingColdStartAction.value = intent.action
                    if (!PlaybackStateManager.status.value.hasTrack) {
                        startActivity(
                            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved()")
        // No manual teardown here — android:stopWithTask="false" already
        // prevents automatic destruction, and MediaSessionService's base
        // implementation independently decides whether to stop itself based
        // on whether the player is idle. We deliberately don't second-guess
        // that logic.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() — service instance ${System.identityHashCode(this)}")
        if (becomingNoisyRegistered) { unregisterReceiver(becomingNoisyReceiver); becomingNoisyRegistered = false }
        abandonAudioFocus()
        releaseWakeLock()
        serviceJob.cancel()
        if (::mediaSession.isInitialized) mediaSession.release()
        // Explicit, not merely defensive: WebViewProxyPlayer.availableCommands now
        // includes COMMAND_RELEASE specifically so this actually reaches
        // handleRelease() (see StarCrossedMediaSession.kt) rather than being a
        // silent no-op — MediaSession.release() above does not call this for us.
        if (::player.isInitialized) player.release()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    // ── Widget action execution (also reused by the cold-start queue) ───

    private fun executeWidgetAction(action: String) {
        if (!::player.isInitialized) return
        when (action) {
            StarCrossedWidget.ACTION_PLAY_PAUSE -> if (player.isPlaying) player.pause() else player.play()
            StarCrossedWidget.ACTION_NEXT -> player.seekToNext()
            StarCrossedWidget.ACTION_PREVIOUS -> player.seekToPrevious()
        }
    }

    // ── State fan-out: JS-reported truth -> Player -> notification, + widget ─

    private fun observeState() {
        serviceScope.launch {
            combine(
                PlaybackStateManager.metadata,
                PlaybackStateManager.status,
                PlaybackStateManager.artwork
            ) { meta, status, art -> Triple(meta, status, art) }
                .collect { (meta, status, art) ->
                    player.updateFromJs(
                        title = meta.title,
                        artist = meta.artist,
                        album = meta.album,
                        durationMs = meta.durationMs,
                        positionMs = status.positionMs,
                        isPlaying = status.isPlaying,
                        hasTrack = status.hasTrack,
                        artwork = art
                    )
                    StarCrossedWidget.pushUpdate(this@StarCrossedMediaService, meta, status, art)
                    handlePlayingTransition(status.isPlaying)
                }
        }
        serviceScope.launch {
            // Keeps the notification's heart button (see buildFavoriteCommandButton in
            // StarCrossedMediaSession.kt) in sync with JS-reported favorite status —
            // covers both the initial state and every subsequent change, from either an
            // in-page star tap or a system-UI toggle round-tripping back through JS.
            PlaybackStateManager.status.map { it.isFavorite }.distinctUntilChanged()
                .collect { isFavorite ->
                    if (::mediaSession.isInitialized) {
                        mediaSession.setCustomLayout(ImmutableList.of(buildFavoriteCommandButton(isFavorite)))
                    }
                }
        }
        serviceScope.launch {
            PlaybackStateManager.appReady.collect { ready ->
                if (ready) {
                    PlaybackStateManager.pendingColdStartAction.value?.let { action ->
                        executeWidgetAction(action)
                        PlaybackStateManager.pendingColdStartAction.value = null
                    }
                }
            }
        }
        // Best-effort native artwork fetch for online tracks whose art the JS
        // canvas export couldn't read back (CORS-tainted canvas). Runs in
        // parallel with the base64 path; whichever resolves first wins — art
        // freshness doesn't need strict ordering, only eventual correctness.
        serviceScope.launch {
            PlaybackStateManager.metadata.collect { meta ->
                val url = meta.artworkUrl
                if (url.isNotBlank()) {
                    launch(Dispatchers.IO) {
                        val bmp = fetchBitmapSafely(url)
                        if (bmp != null) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                if (PlaybackStateManager.artwork.value == null) PlaybackStateManager.artwork.value = bmp
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handlePlayingTransition(isPlaying: Boolean) {
        if (isPlaying && !wasPlaying) {
            Log.d(TAG, "playback started (JS-reported) -> requesting audio focus")
            requestAudioFocus()
            acquireWakeLock()
        } else if (!isPlaying && wasPlaying) {
            Log.d(TAG, "playback stopped (JS-reported)")
            releaseWakeLock()
            // Deliberately NOT abandoning audio focus on a mere pause (e.g. a
            // brief gap between tracks) — only on true AUDIOFOCUS_LOSS from
            // the system, or service destruction. Abandoning on every pause
            // would cause needless focus flapping with other apps.
        }
        wasPlaying = isPlaying
    }

    // ── Audio focus ───────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .setWillPauseWhenDucked(false)
            .build()
        val result = audioManager.requestAudioFocus(request)
        Log.d(
            TAG,
            "requestAudioFocus() -> " +
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "NOT granted ($result)"
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            Log.d(TAG, "abandonAudioFocus()")
            audioManager.abandonAudioFocusRequest(it)
        }
        audioFocusRequest = null
    }

    // ── Wake lock (defensive, bounded — reinforces the foreground-service
    //    Doze exemption rather than being the sole mechanism relied upon) ──

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarCrossed:Playback").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_SAFETY_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── JS command dispatch (native -> WebView) ──────────────────────────

    private fun dispatchToJs(command: WebViewProxyPlayer.JsCommand) {
        val script = when (command) {
            is WebViewProxyPlayer.JsCommand.SetPlayWhenReady ->
                if (command.playWhenReady) "window.AndroidBridgeReceiver&&window.AndroidBridgeReceiver.onAndroidPlay();"
                else "window.AndroidBridgeReceiver&&window.AndroidBridgeReceiver.onAndroidPause();"
            WebViewProxyPlayer.JsCommand.Next -> "window.AndroidBridgeReceiver&&window.AndroidBridgeReceiver.onAndroidNext();"
            WebViewProxyPlayer.JsCommand.Previous -> "window.AndroidBridgeReceiver&&window.AndroidBridgeReceiver.onAndroidPrevious();"
            is WebViewProxyPlayer.JsCommand.SeekTo -> "window.AndroidBridgeReceiver&&window.AndroidBridgeReceiver.onAndroidSeekTo(${command.positionMs});"
            is WebViewProxyPlayer.JsCommand.SetFavorite -> "window.AndroidBridgeReceiver&&window.AndroidBridgeReceiver.onAndroidToggleFavorite();"
            WebViewProxyPlayer.JsCommand.Stop -> "window.AndroidBridgeReceiver&&window.AndroidBridgeReceiver.onAndroidPause();"
        }
        evalJs(script)
    }

    private fun evalJs(script: String) {
        if (::webView.isInitialized) webView.evaluateJavascript(script, null)
    }

    // ── WebView (the real audio engine) ──────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(applicationContext))
            .build()

        webView = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The page has its own robust "tap to enable audio" resume flow
            // (see #ctxBar in the HTML), designed around the standard
            // browser autoplay-gesture policy. But commands arriving via
            // evaluateJavascript() from a lockscreen/Bluetooth/widget tap are
            // NOT recognized by Chromium as a user gesture (only real
            // touch/click events on the page are) — without this flag, a
            // perfectly legitimate hardware "play" press could be silently
            // rejected by WebView's own autoplay gate. Safe to disable
            // entirely here because this WebView only ever loads STAR
            // CROSSED itself, a page we fully control — the autoplay-gesture
            // policy exists to stop untrusted sites from abusing autoplay,
            // which doesn't apply to a first-party app wrapper.
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // A bare WebChromeClient() (as this was) leaves onShowFileChooser()
            // at its default `return false` — silently ignoring every tap on
            // JS's <input type="file">. That one mechanism backs "Import
            // Media"/"Browse Files" (audio) and the playlist/config JSON
            // pickers alike. Only an Activity can launch the actual system
            // picker, so this hands the request off through
            // PlaybackStateManager to MainActivity and holds the pending
            // callback here (see pendingFileChooserCallback) until
            // completeFileChooser() reports back.
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    // Resolve any still-pending previous request first — WebView's
                    // contract expects exactly one onReceiveValue call per
                    // onShowFileChooser, and dropping an old callback would leave
                    // that earlier <input> permanently unresponsive.
                    pendingFileChooserCallback?.onReceiveValue(null)
                    pendingFileChooserCallback = filePathCallback
                    PlaybackStateManager.fileChooserRequests.tryEmit(fileChooserParams)
                    return true
                }
            }
            // Ask Chromium to keep this WebView's renderer at high priority
            // even while it has no visible window (Activity backgrounded or
            // destroyed) — the officially documented mechanism for keeping a
            // background WebView's JS/audio pipeline running at full speed.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, /* waivedWhenNotVisible = */ false)

            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    PlaybackStateManager.pageParsed.value = true
                }
            }

            addJavascriptInterface(
                StarCrossedWebBridge(onRequestNotificationPermission = {
                    PlaybackStateManager.notificationPermissionRequests.tryEmit(Unit)
                }),
                "AndroidMedia"
            )

            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

            loadUrl("https://$ASSET_DOMAIN/assets/STAR_CROSSED_v47.html")
        }
    }

    /** MainActivity calls this once its file-picker ActivityResultLauncher
     *  returns, completing whichever <input type="file"> is currently
     *  waiting (see [pendingFileChooserCallback]). `null` correctly signals
     *  "cancelled" to the JS side — WebView's own contract for this
     *  callback, same as a real browser reports a dismissed file dialog. */
    fun completeFileChooser(uris: Array<Uri>?) {
        pendingFileChooserCallback?.onReceiveValue(uris)
        pendingFileChooserCallback = null
    }

    private fun fetchBitmapSafely(urlStr: String): Bitmap? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            doInput = true
            instanceFollowRedirects = true
            connect()
        }
        val bmp = if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            BitmapFactory.decodeStream(conn.inputStream)
        } else null
        conn.disconnect()
        bmp
    } catch (e: Exception) {
        null // artwork is always optional — never let a failed fetch surface anywhere
    }

    companion object {
        private const val TAG = "SCMediaService"
        const val ACTION_LOCAL_BIND = "com.starcrossed.app.ACTION_LOCAL_BIND"
        val ASSET_DOMAIN: String = WebViewAssetLoader.DEFAULT_DOMAIN
        private const val WAKE_LOCK_SAFETY_TIMEOUT_MS = 10L * 60L * 60L * 1000L // 10h safety cap
    }
}
