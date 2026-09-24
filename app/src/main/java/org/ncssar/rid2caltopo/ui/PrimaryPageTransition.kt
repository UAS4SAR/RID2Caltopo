package org.ncssar.rid2caltopo.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Animate only the arriving page, avoiding overlapping Live View consumers during exit. */
@Composable
fun PrimaryPageTransition(screen: ActiveScreen, content: @Composable () -> Unit) {
    var previous by remember { mutableStateOf(screen) }
    val offset = remember(screen) {
        val enteringLive = previous == ActiveScreen.MAIN && screen == ActiveScreen.STREAMS
        val enteringMain = previous == ActiveScreen.STREAMS && screen == ActiveScreen.MAIN
        Animatable(if (enteringLive) 0.12f else if (enteringMain) -0.12f else 0f)
    }
    LaunchedEffect(screen) {
        previous = screen
        offset.animateTo(0f, tween(220))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = size.width * offset.value }) {
        content()
    }
}
