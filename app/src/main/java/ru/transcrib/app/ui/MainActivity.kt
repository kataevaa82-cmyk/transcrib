package ru.transcrib.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import ru.transcrib.app.TranscribApp

class MainActivity : ComponentActivity() {
    private val app get() = application as TranscribApp

    /** Navigation requests coming from intents (notifications, "share", "open with"). */
    private val navRequests = MutableStateFlow<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        askNotificationPermission()

        setContent {
            TranscribTheme {
                var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
                val request by navRequests.collectAsState()
                LaunchedEffect(request) {
                    request?.let {
                        route = it
                        navRequests.value = null
                    }
                }
                BackHandler(enabled = route != ROUTE_HOME) { route = ROUTE_HOME }

                AnimatedContent(
                    targetState = route,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                ) { r ->
                    when {
                        r == ROUTE_RECORDER -> RecorderScreen(
                            app = app,
                            onBack = { route = ROUTE_HOME },
                        )

                        r == ROUTE_ABOUT -> AboutScreen(onBack = { route = ROUTE_HOME })

                        r.startsWith(ROUTE_TRANSCRIPT) -> TranscriptScreen(
                            app = app,
                            id = r.removePrefix(ROUTE_TRANSCRIPT),
                            onBack = { route = ROUTE_HOME },
                        )

                        r.startsWith(ROUTE_SHARE) -> TranscriptScreen(
                            app = app,
                            id = r.removePrefix(ROUTE_SHARE),
                            openExport = true,
                            onBack = { route = ROUTE_HOME },
                        )

                        else -> HomeScreen(
                            app = app,
                            onOpen = { id -> route = ROUTE_TRANSCRIPT + id },
                            onShare = { id -> route = ROUTE_SHARE + id },
                            onRecord = { route = ROUTE_RECORDER },
                            onAbout = { route = ROUTE_ABOUT },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.uiVisible = true
    }

    override fun onStop() {
        app.uiVisible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_OPEN_ID)?.let {
            val share = intent.getBooleanExtra(EXTRA_SHARE, false)
            navRequests.value = (if (share) ROUTE_SHARE else ROUTE_TRANSCRIPT) + it
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_RECORDER, false)) {
            navRequests.value = ROUTE_RECORDER
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            )

            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java,
            ).orEmpty()

            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        uris.forEach { app.jobs.enqueue(it) }
        Toast.makeText(
            this,
            if (uris.size == 1) "Файл добавлен в очередь расшифровки" else "Файлов в очереди: ${uris.size}",
            Toast.LENGTH_SHORT,
        ).show()
        navRequests.value = ROUTE_HOME
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_ID = "open_id"
        const val EXTRA_OPEN_RECORDER = "open_recorder"
        const val EXTRA_SHARE = "share"
        private const val ROUTE_HOME = "home"
        private const val ROUTE_RECORDER = "recorder"
        private const val ROUTE_ABOUT = "about"
        private const val ROUTE_TRANSCRIPT = "t:"
        private const val ROUTE_SHARE = "s:"
    }
}
