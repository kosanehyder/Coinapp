package com.coinfutures.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coinfutures.app.data.settings.AppSettings
import com.coinfutures.app.notifications.Notifier
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.l10n.stringsFor
import com.coinfutures.app.ui.navigation.AppRoot
import com.coinfutures.app.ui.navigation.LocalContainer
import com.coinfutures.app.ui.navigation.PendingDeepLink
import com.coinfutures.app.ui.theme.CoinFuturesTheme
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize

class MainActivity : ComponentActivity() {

    private val deepLink = mutableStateOf<PendingDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CoinFuturesApp).container
        handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: AppSettings()
            val strings = stringsFor(s.language)
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalContainer provides container, LocalStrings provides strings) {
                CoinFuturesTheme(mode = s.theme) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        val link by deepLink
                        AppRoot(deepLink = link, onDeepLinkConsumed = { deepLink.value = null })
                        if (settings != null && !s.disclaimerAccepted) {
                            AlertDialog(
                                onDismissRequest = {},
                                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                                title = { Text(strings.disclaimerTitle) },
                                text = { Column { Text(strings.disclaimerBody) } },
                                confirmButton = {
                                    Button(onClick = { scope.launch { container.settings.acceptDisclaimer() } }) {
                                        Text(strings.disclaimerAccept)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(Notifier.EXTRA_INSTRUMENT) ?: return
        val eventId = intent.getLongExtra(Notifier.EXTRA_EVENT_ID, -1L).takeIf { it > 0 }
        deepLink.value = PendingDeepLink(id, intent.getStringExtra(Notifier.EXTRA_PLAN_KEY), eventId)
        intent.removeExtra(Notifier.EXTRA_INSTRUMENT)
    }
}
