package com.starcrossed.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * MainActivity
 * ─────────────────────────────────────────────────────────────────────────
 * Deliberately thin. This Activity does NOT create, own, or destroy the
 * WebView that plays audio — StarCrossedMediaService does, for its entire
 * process lifetime. All this Activity does is:
 *   1. Ensure the service exists and bind to it.
 *   2. Borrow the service's WebView View and attach it into its own layout
 *      whenever visible; detach (never destroy) it otherwise.
 *   3. Handle the things only an Activity can do: request the
 *      POST_NOTIFICATIONS runtime permission on Android 13+, and launch the
 *      system file picker for JS's <input type="file"> (Import Media /
 *      Browse Files, and the playlist/config JSON pickers).
 *
 * android:configChanges (see AndroidManifest.xml) intentionally prevents
 * this Activity from being recreated on rotation — STAR CROSSED's own CSS
 * already handles responsive/orientation reflow, and recreating the
 * Activity for a rotation would otherwise force a WebView
 * detach/reattach cycle for no reason.
 */
class MainActivity : AppCompatActivity() {

    private var mediaService: StarCrossedMediaService? = null
    private var bound = false
    private lateinit var container: FrameLayout
    private var hasRequestedNotificationPermissionThisSession = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result intentionally unused —
            whether granted or denied, the foreground service still runs correctly per
            StarCrossedMediaService's docs; denial just means the notification is invisible. */ }

    // Handles JS's <input type="file"> — "Import Media"/"Browse Files" and
    // the playlist/config JSON pickers all share this one WebView mechanism
    // (see observeFileChooserRequests / launchFileChooser below, and
    // StarCrossedMediaService's WebChromeClient). No runtime permission is
    // requested for this: FileChooserParams.createIntent() launches the
    // system picker (or whichever app the user has for it), which grants a
    // temporary, scoped read URI permission for just the file(s) selected —
    // exactly what's needed here, since files are read once, immediately,
    // into memory and stored in STAR CROSSED's own library. That covers
    // every supported Android version, including 13+'s READ_MEDIA_AUDIO,
    // which is a different (unneeded) access pattern for direct
    // MediaStore/filesystem-style reads, not for picker-mediated selection.
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            mediaService?.completeFileChooser(FileChooserParams.parseResult(result.resultCode, result.data))
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? StarCrossedMediaService.LocalBinder ?: return
            mediaService = binder.getService()
            bound = true
            attachWebView()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            mediaService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.webview_container)

        // Guarantee the service (and therefore the WebView) exists even if
        // nothing is playing yet — a plain bind is enough for this; the
        // service only promotes itself to a true foreground/notification
        // state once JS reports actual playback (see StarCrossedMediaService).
        bindService(
            Intent(this, StarCrossedMediaService::class.java).apply { action = StarCrossedMediaService.ACTION_LOCAL_BIND },
            connection,
            Context.BIND_AUTO_CREATE
        )

        observeNotificationPermissionRequests()
        observeFirstPlaybackForPermissionPriming()
        observeFileChooserRequests()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = mediaService?.webView
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (bound) attachWebView()
    }

    override fun onStop() {
        super.onStop()
        detachWebView()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    // ── WebView attach/detach ─────────────────────────────────────────

    private fun attachWebView() {
        val webView = mediaService?.webView ?: return
        (webView.parent as? ViewGroup)?.let { parent ->
            if (parent !== container) parent.removeView(webView)
        }
        if (webView.parent == null) {
            container.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun detachWebView() {
        val webView = mediaService?.webView ?: return
        // Detach only — StarCrossedMediaService retains ownership and the
        // WebView keeps running (JS timers, Web Audio playback) regardless
        // of whether any Activity currently displays it.
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    // ── Notification permission (Android 13+) ─────────────────────────

    private fun observeNotificationPermissionRequests() {
        lifecycleScope.launch {
            PlaybackStateManager.notificationPermissionRequests.collect {
                requestNotificationPermissionIfNeeded()
            }
        }
    }

    /**
     * Best-practice timing: prompt in context, the first time the user
     * actually starts playback, rather than nagging on cold app launch
     * before they've done anything. Fires at most once per process
     * lifetime — if denied, we respect that and never re-prompt ourselves
     * (the OS's own "don't ask again" handling takes over from there).
     */
    private fun observeFirstPlaybackForPermissionPriming() {
        lifecycleScope.launch {
            PlaybackStateManager.status.collect { status ->
                if (status.isPlaying && !hasRequestedNotificationPermissionThisSession) {
                    hasRequestedNotificationPermissionThisSession = true
                    requestNotificationPermissionIfNeeded()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return // permission doesn't exist pre-13
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ── File chooser (Import Media / Browse Files, playlist/config JSON) ──

    private fun observeFileChooserRequests() {
        lifecycleScope.launch {
            PlaybackStateManager.fileChooserRequests.collect { params ->
                launchFileChooser(params)
            }
        }
    }

    /**
     * Uses FileChooserParams.createIntent()/parseResult() — Android's own
     * documented matched pair for this handoff (build the intent from it,
     * fire it, parse the result with the same class's static parser) —
     * rather than a hand-built equivalent, since that pairing is guaranteed
     * to agree with whatever the device's specific WebView provider expects.
     * Handles single selection (Intent#getData), multiple selection
     * (Intent#getClipData, when the <input> has the `multiple` attribute —
     * parseResult() already resolves this distinction) and cancellation
     * (RESULT_CANCELED -> parseResult() returns null -> the file-chooser
     * callback's registration below completes with null, which is exactly
     * what WebView expects for "nothing selected").
     */
    private fun launchFileChooser(params: FileChooserParams) {
        try {
            fileChooserLauncher.launch(params.createIntent())
        } catch (e: ActivityNotFoundException) {
            // No app on this device can handle it — realistically never
            // happens (every Android device ships a Files/Downloads
            // provider) but complete the pending callback rather than leave
            // the JS <input> waiting forever if it somehow does.
            mediaService?.completeFileChooser(null)
        }
    }
}
