package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun OperatorMainHeader(modifier: Modifier = Modifier, title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            val availableWidth = maxWidth
            if (maxWidth >= 600.dp * LocalDensity.current.fontScale) {
                Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = availableWidth - 280.dp)) { title() }
                    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { title() }
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        }
    }
}
