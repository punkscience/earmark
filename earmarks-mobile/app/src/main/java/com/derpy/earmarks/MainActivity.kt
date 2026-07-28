package com.derpy.earmarks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.derpy.earmarks.ui.AppState
import com.derpy.earmarks.ui.KeyEntryScreen
import com.derpy.earmarks.ui.MainViewModel
import com.derpy.earmarks.ui.PlayerScreen
import com.derpy.earmarks.ui.SplashScreen
import com.derpy.earmarks.ui.theme.EarmarksTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called BEFORE super.onCreate so the SplashScreen API can hook
        // the activity's launch-time theme and swap it for postSplashScreenTheme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EarmarksTheme {
                Surface(Modifier.fillMaxSize()) {
                    // Branded splash: build dots + "earmarks" wordmark on the ink
                    // ground. Hands off to the app once the brief beat elapses.
                    var showSplash by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        delay(1400)
                        showSplash = false
                    }

                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val vm: MainViewModel = viewModel()
                        val appState by vm.state.collectAsState()
                        val playerState by vm.playerState.collectAsState()
                        val stats by vm.stats.collectAsState()
                        val notice by vm.notice.collectAsState()
                        val channelState by vm.channelState.collectAsState()

                        // Channel state lives on the relays and changes from
                        // other devices while this process sits cached in the
                        // background. Re-sync (throttled) every time the user
                        // comes back, or a channel created on the desktop never
                        // shows up until Android kills the process.
                        val lifecycleOwner = LocalLifecycleOwner.current
                        DisposableEffect(lifecycleOwner) {
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) vm.syncChannelsIfStale()
                            }
                            lifecycleOwner.lifecycle.addObserver(observer)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                        }

                        if (appState is AppState.KeyMissing) {
                            KeyEntryScreen(onSaveKey = { vm.saveKey(it) })
                        } else {
                            PlayerScreen(
                                appState = appState,
                                playerState = playerState,
                                stats = stats,
                                notice = notice,
                                channelState = channelState,
                                onDismissNotice = { vm.dismissNotice() },
                                onPlayPause = { vm.player.playPause() },
                                onSkipNext = { vm.player.skipNext() },
                                onSkipPrevious = { vm.player.skipPrevious() },
                                onDeleteCurrent = { vm.deleteCurrent() },
                                onClearKey = { vm.clearKey() },
                                onSelectSource = { vm.selectSource(it) },
                                onAcceptInvite = { vm.acceptInvite(it) },
                                onDeclineInvite = { vm.declineInvite(it) },
                                onKeepCurrent = { vm.keepCurrentChannelTrack() },
                                onShareCurrent = { vm.shareCurrentTrack(it) }
                            )
                        }
                    }

                    AnimatedVisibility(visible = showSplash, exit = fadeOut()) {
                        Surface(Modifier.fillMaxSize()) { SplashScreen() }
                    }
                }
            }
        }
    }
}
