package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ncssar.rid2caltopo.btrx.BtrxButtonConfirm
import org.ncssar.rid2caltopo.btrx.BtrxDeskPodText
import org.ncssar.rid2caltopo.btrx.BtrxManager

/**
 * Desk Pod button confirmation (firmware 0x0108) over the map: a small Confirm/Cancel card at
 * the top, never a dialog, so the map, the stream tiles and the alert dialogs stay usable.
 * Composed at the app root (outside the BTRX pane). While this host is composed and the app
 * is at least STARTED it tells the manager the panel can be seen: only then does the app send
 * the keepalive and answer "shown", so with the app in the background the Desk Pod button
 * acts on its own. The same card shows inside the Desk Pod pane (a dialog window above this).
 */
@Composable
fun BtrxButtonConfirmHost() {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        fun update() = BtrxManager.setButtonPanelAvailable(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer)
        update()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            BtrxManager.setButtonPanelAvailable(false)
        }
    }
    val s by BtrxManager.snapshot.collectAsState()
    val v = s.buttonConfirm
    val notice = s.buttonNotice
    if (v == null && notice == null) return
    // Fills the screen only for alignment: no pointer handling, so touches outside the card reach the map.
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        if (v != null) BtrxButtonConfirmCard(v, Modifier.widthIn(max = 460.dp).padding(horizontal = 12.dp))
        else if (notice != null) BtrxButtonNoticeChip(notice)
    }
}

/** The Confirm/Cancel card (map host and Desk Pod pane). Factory reset is red and says it cannot be undone. */
@Composable
fun BtrxButtonConfirmCard(v: BtrxButtonConfirm.View, modifier: Modifier = Modifier) {
    val danger = v.factoryReset
    Surface(
        modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                BtrxDeskPodText.confirmTitle(v),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(BtrxDeskPodText.confirmBody(v), style = MaterialTheme.typography.bodySmall)
            Text(BtrxDeskPodText.confirmCountdown(v), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            if (!v.confirmSent) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { BtrxManager.answerButtonConfirm(false) }) { Text(BtrxDeskPodText.CONFIRM_CANCEL) }
                Button(
                    onClick = { BtrxManager.answerButtonConfirm(true) },
                    colors = if (danger) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ) else ButtonDefaults.buttonColors(),
                ) { Text(if (danger) BtrxDeskPodText.CONFIRM_RESET_OK else BtrxDeskPodText.CONFIRM_OK) }
            }
        }
    }
}

@Composable
private fun BtrxButtonNoticeChip(n: BtrxButtonConfirm.Notice) {
    Text(
        n.text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .widthIn(max = 460.dp)
            .padding(horizontal = 12.dp)
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
