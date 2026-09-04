package com.starcrossed.app

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONException
import org.json.JSONObject

/**
 * StarCrossedWebBridge
 * ─────────────────────────────────────────────────────────────────────────
 * Installed via `webView.addJavascriptInterface(bridge, "AndroidMedia")`,
 * so from the HTML/JS side this is `window.AndroidMedia`.
 *
 * IMPORTANT THREADING NOTE: every @JavascriptInterface method here is
 * invoked by the WebView on a dedicated background thread it manages
 * internally (never the app's main thread, and never the same thread twice
 * in a row is guaranteed either). Every method below therefore ONLY ever
 * writes into PlaybackStateManager's MutableStateFlow/MutableSharedFlow
 * fields, which are safe to assign from any thread. Nothing here touches a
 * View, the NotificationManager, or AppWidgetManager directly — those all
 * happen in StarCrossedMediaService, which collects these flows on
 * Dispatchers.Main.
 *
 * Every method fails soft: malformed input is logged and dropped, never
 * thrown past this boundary back into WebView's JS-bridge machinery (an
 * uncaught exception here would surface as a JS-side error on the *calling*
 * script, which is the STAR CROSSED page itself — we must never let a bridge
 * hiccup destabilize the actual music player).
 */
class StarCrossedWebBridge(
    private val onRequestNotificationPermission: () -> Unit
) {

    @JavascriptInterface
    fun updatePlaybackState(isPlaying: Boolean, positionMs: Double, isFavorite: Boolean) {
        Log.d(TAG, "[JS-bridge] updatePlaybackState(isPlaying=$isPlaying, posMs=$positionMs)")
        val prev = PlaybackStateManager.status.value
        PlaybackStateManager.status.value = prev.copy(
            isPlaying = isPlaying,
            positionMs = positionMs.toLong().coerceAtLeast(0L),
            isFavorite = isFavorite,
            hasTrack = true
        )
    }

    @JavascriptInterface
    fun setTrack(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val o = JSONObject(json)
            PlaybackStateManager.metadata.value = PlaybackStateManager.TrackMetadata(
                title = o.optString("title", "STAR CROSSED").ifBlank { "STAR CROSSED" },
                artist = o.optString("artist", ""),
                album = o.optString("album", "STAR CROSSED"),
                durationMs = o.optLong("durationMs", 0L).coerceAtLeast(0L),
                isLocalTrack = o.optBoolean("isLocalTrack", false),
                trackKey = o.optString("trackKey", ""),
                artworkUrl = o.optString("artworkUrl", "")
            )
            val prevStatus = PlaybackStateManager.status.value
            PlaybackStateManager.status.value = prevStatus.copy(
                hasTrack = true,
                isFavorite = o.optBoolean("isFavorite", prevStatus.isFavorite)
            )
            // A new track means any previously-decoded artwork is stale until
            // either the base64 canvas export or the native URL-fetch fallback
            // (see StarCrossedMediaService) produces a fresh bitmap.
            PlaybackStateManager.artwork.value = null
        } catch (e: JSONException) {
            Log.w(TAG, "setTrack: malformed JSON payload, ignoring", e)
        }
    }

    @JavascriptInterface
    fun setArtworkBase64(dataUrl: String?) {
        if (dataUrl.isNullOrBlank()) return
        try {
            // Accept both a raw base64 string and a full "data:image/jpeg;base64,..." URL.
            val comma = dataUrl.indexOf(',')
            val clean = if (dataUrl.startsWith("data:") && comma >= 0) dataUrl.substring(comma + 1) else dataUrl
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) PlaybackStateManager.artwork.value = bmp
        } catch (e: Exception) {
            // Never crash on a malformed/partial data URL — the native URL-fetch
            // fallback in the service, or the default STAR CROSSED mark, covers this.
            Log.w(TAG, "setArtworkBase64: decode failed, will rely on artworkUrl fallback", e)
        }
    }

    @JavascriptInterface
    fun setFavorite(isFavorite: Boolean) {
        val prev = PlaybackStateManager.status.value
        PlaybackStateManager.status.value = prev.copy(isFavorite = isFavorite)
    }

    @JavascriptInterface
    fun clearTrack() {
        PlaybackStateManager.reset()
    }

    @JavascriptInterface
    fun ready() {
        PlaybackStateManager.appReady.value = true
    }

    @JavascriptInterface
    fun requestNotificationPermission() {
        onRequestNotificationPermission()
    }

    @JavascriptInterface
    fun log(message: String?) {
        if (!message.isNullOrEmpty()) Log.d(TAG, "[JS] $message")
    }

    companion object {
        private const val TAG = "SCWebBridge"
    }
}
