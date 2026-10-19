package org.ncssar.rid2caltopo.video

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Only this HUD observes scale changes; a gesture must not rebuild the map's overlays. */
@Composable
internal fun MapScaleBarHud(state:State<MapScaleBarState?>,modifier:Modifier=Modifier) {
    val scale=state.value?:return
    val density=LocalDensity.current
    val width=with(density){scale.widthPx.toFloat().toDp()}
    val paint=remember(density){Paint(Paint.ANTI_ALIAS_FLAG).apply{
        textSize=with(density){11.sp.toPx()};typeface=Typeface.DEFAULT_BOLD
    }}
    Canvas(modifier.width(width+8.dp).height(30.dp)) {
        val x=2.dp.toPx();val end=x+scale.widthPx.toFloat();val y=size.height-2.dp.toPx()
        for((color,stroke) in listOf(Color.Black to 8.dp.toPx(),Color.White to 4.dp.toPx())) {
            drawLine(color,androidx.compose.ui.geometry.Offset(x,y),androidx.compose.ui.geometry.Offset(end,y),stroke,StrokeCap.Round)
            drawLine(color,androidx.compose.ui.geometry.Offset(x,y-5.dp.toPx()),androidx.compose.ui.geometry.Offset(x,y),stroke,StrokeCap.Round)
            drawLine(color,androidx.compose.ui.geometry.Offset(end,y-5.dp.toPx()),androidx.compose.ui.geometry.Offset(end,y),stroke,StrokeCap.Round)
        }
        drawIntoCanvas { canvas->
            paint.color=android.graphics.Color.BLACK;paint.style=Paint.Style.STROKE;paint.strokeWidth=3.dp.toPx()
            canvas.nativeCanvas.drawText(scale.label,x,y-9.dp.toPx(),paint)
            paint.style=Paint.Style.FILL;paint.color=android.graphics.Color.WHITE
            canvas.nativeCanvas.drawText(scale.label,x,y-9.dp.toPx(),paint)
        }
    }
}
