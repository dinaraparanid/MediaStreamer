package com.paranid5.mediastreamer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.compose.rememberNavController
import com.paranid5.mediastreamer.composition_locals.LocalStreamState
import com.paranid5.mediastreamer.composition_locals.StreamState
import com.paranid5.mediastreamer.composition_locals.StreamStates
import com.paranid5.mediastreamer.ui.App
import com.paranid5.mediastreamer.ui.screens.LocalNavController
import com.paranid5.mediastreamer.ui.screens.NavHostController
import com.paranid5.mediastreamer.ui.screens.Screens
import com.paranid5.mediastreamer.ui.theme.MediaStreamerTheme
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MediaStreamerTheme {
                val mainNavController = NavHostController(
                    value = rememberNavController(),
                    currentRouteState = Screens.StreamScreen.Searching.title
                )

                CompositionLocalProvider(
                    LocalNavController provides mainNavController,
                    LocalStreamState provides StreamState(StreamStates.SEARCHING)
                ) {
                    App()
                }
            }
        }
    }
}