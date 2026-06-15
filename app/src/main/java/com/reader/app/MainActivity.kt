package com.reader.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.reader.app.di.SharedYouTubeUrlBus
import com.reader.app.di.extractYouTubeUrlFromSharedText
import com.reader.app.ui.nav.ReaderNavGraph
import com.reader.app.ui.theme.ReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Main activity initialized. This triggers change tracking.
        // Cold-start from a share: pull the URL out BEFORE setContent
        // so by the time the nav graph runs its first composition, the
        // SharedYouTubeUrlBus already has the URL and the navigation
        // collector immediately routes us to the Upload screen.
        handleSharedIntent(intent)
        handleNotificationDeepLink(intent)
        setContent {
            ReaderTheme {
                ReaderNavGraph()
            }
        }
    }

    /**
     * Handles "share another time while we're already running."
     *
     * `android:launchMode="singleTask"` in the manifest guarantees that
     * the second share goes through this entry point instead of
     * spinning up a new activity instance — which would otherwise mean
     * a new ServiceLocator init / new ViewModelStore / lost chat
     * history.
     *
     * `setIntent(intent)` is the standard pattern: it overwrites the
     * Activity's stored intent so any later `getIntent()` call (or
     * config-change replays) sees the share, not the original
     * launcher intent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
        handleNotificationDeepLink(intent)
    }

    private fun handleNotificationDeepLink(intent: Intent?) {
        if (intent == null) return
        val isDeepLink = intent.getBooleanExtra("EXTRA_NOTIF_DEEP_LINK", false)
        if (isDeepLink) {
            val docId = intent.getLongExtra("EXTRA_NOTIF_DOC_ID", -1L)
            val type = intent.getStringExtra("EXTRA_NOTIF_TYPE").orEmpty()
            if (docId != -1L && type.isNotEmpty()) {
                com.reader.app.di.DeepLinkBus.post(docId, type)
            }
        }
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        // We registered for text/plain only — but be defensive in case
        // some clients send no MIME or a slightly different one.
        val type = intent.type
        if (type != null && !type.startsWith("text/")) return
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractYouTubeUrlFromSharedText(raw)
        if (!url.isNullOrBlank()) {
            SharedYouTubeUrlBus.post(url)
        }
        // We deliberately leave the intent in place. The nav graph's
        // collector consumes the bus value (via UploadViewModel.init).
    }
}
