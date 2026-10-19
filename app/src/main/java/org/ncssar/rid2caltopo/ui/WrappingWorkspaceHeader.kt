package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight

/** Keep the action strip at the trailing edge, including when it needs its own line. */
@Composable
internal fun WrappingWorkspaceHeader(modifier:Modifier=Modifier,title:@Composable ()->Unit,actions:@Composable ()->Unit) {
    Layout(modifier=modifier,content={Box{title()};Box{actions()}}) { measurables,constraints ->
        val loose=constraints.copy(minWidth=0,minHeight=0)
        val heading=measurables[0].measure(loose)
        val controls=measurables[1].measure(loose)
        val width=if(constraints.hasBoundedWidth)constraints.maxWidth else constraints.constrainWidth(heading.width+controls.width)
        val sameLine=heading.width+controls.width<=width
        val height=constraints.constrainHeight(if(sameLine)maxOf(heading.height,controls.height) else heading.height+controls.height)
        layout(width,height) {
            heading.placeRelative(0,if(sameLine)(height-heading.height)/2 else 0)
            controls.placeRelative(width-controls.width,if(sameLine)(height-controls.height)/2 else heading.height)
        }
    }
}
