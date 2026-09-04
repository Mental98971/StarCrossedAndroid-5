package com.starcrossed.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/**
 * StarCrossedWidget
 * ─────────────────────────────────────────────────────────────────────────
 * A home-screen widget showing the current track + transport controls.
 * Deliberately talks ONLY to StarCrossedMediaService (via broadcast intents
 * it sends to itself, handled below in onReceive) — never touches the
 * WebView or JS directly. The service is the single source of truth; this
 * class is a thin, disposable render target for that truth.
 *
 * Updates arrive two ways:
 *   1. Pushed proactively — StarCrossedMediaService calls
 *      StarCrossedWidget.pushUpdate(context, ...) every time
 *      PlaybackStateManager's flows change. This is the primary path and
 *      is what keeps the widget in near-real-time sync while the app is
 *      actively used.
 *   2. The system's own periodic onUpdate() (see widget_info.xml's
 *      updatePeriodMillis) — a slow, infrequent safety-net refresh in case
 *      a push was ever missed (e.g. widget added while nothing was
 *      playing). Not relied upon for responsiveness.
 */
class StarCrossedWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, PlaybackStateManager.metadata.value,
                PlaybackStateManager.status.value, PlaybackStateManager.artwork.value))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // handles the standard APPWIDGET_UPDATE etc. actions first
        when (intent.action) {
            ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREVIOUS -> {
                // Ensure the service exists (cold-widget-tap case), then hand it
                // the requested action. StarCrossedMediaService decides whether
                // to execute immediately or queue it until the app reports ready.
                val svcIntent = Intent(context, StarCrossedMediaService::class.java).apply {
                    action = intent.action
                }
                ContextCompat.startForegroundService(context, svcIntent)
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.starcrossed.app.widget.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.starcrossed.app.widget.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.starcrossed.app.widget.ACTION_PREVIOUS"

        fun pushUpdate(
            context: Context,
            metadata: PlaybackStateManager.TrackMetadata,
            status: PlaybackStateManager.PlaybackStatus,
            artwork: Bitmap?
        ) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, StarCrossedWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, metadata, status, artwork)
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        private fun buildViews(
            context: Context,
            metadata: PlaybackStateManager.TrackMetadata,
            status: PlaybackStateManager.PlaybackStatus,
            artwork: Bitmap?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_star_crossed)

            views.setTextViewText(
                R.id.widget_title,
                if (status.hasTrack) metadata.title else context.getString(R.string.widget_idle_title)
            )
            views.setTextViewText(
                R.id.widget_artist,
                if (status.hasTrack) metadata.artist else context.getString(R.string.widget_idle_subtitle)
            )
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (status.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            if (artwork != null) {
                views.setImageViewBitmap(R.id.widget_art, artwork)
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_notification)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                PendingIntent.getBroadcast(
                    context, 100,
                    Intent(context, StarCrossedWidget::class.java).setAction(ACTION_PLAY_PAUSE),
                    flags
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                PendingIntent.getBroadcast(
                    context, 101,
                    Intent(context, StarCrossedWidget::class.java).setAction(ACTION_NEXT),
                    flags
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                PendingIntent.getBroadcast(
                    context, 102,
                    Intent(context, StarCrossedWidget::class.java).setAction(ACTION_PREVIOUS),
                    flags
                )
            )
            // Tapping the artwork/title area opens the app.
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 103, openAppIntent, flags)
            )

            return views
        }
    }
}
