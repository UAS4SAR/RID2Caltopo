package org.ncssar.rid2caltopo.video

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow

internal data class CrosshairConfiguration(
    val style: String = "Simple",
    val widthPx: Float = 1f,
    val sizePercent: Float = 5f,
    val mainColor: Long = 0xFFFFFFFF,
    val borderColor: Long = 0xFF000000,
    val showCoordinates: Boolean = true,
)

internal object CrosshairPreferences {
    val configuration = MutableStateFlow(CrosshairConfiguration())
    private var loaded = false
    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences("crosshair", Context.MODE_PRIVATE)
        configuration.value = CrosshairConfiguration(
            prefs.getString("style", "Simple").takeIf { it in listOf("None", "Simple", "Bordered") } ?: "Simple",
            prefs.getFloat("widthPx", 1f).takeIf { it.isFinite() && it > 0f } ?: 1f,
            prefs.getFloat("sizePercent", 5f).takeIf { it.isFinite() }?.coerceIn(2f, 25f) ?: 5f,
            prefs.getLong("mainColor", 0xFFFFFFFF), prefs.getLong("borderColor", 0xFF000000),
            prefs.getBoolean("showCoordinates", true),
        )
        loaded = true
    }
    fun save(context: Context, value: CrosshairConfiguration) {
        configuration.value = value
        context.getSharedPreferences("crosshair", Context.MODE_PRIVATE).edit()
            .putString("style", value.style).putFloat("widthPx", value.widthPx)
            .putFloat("sizePercent", value.sizePercent).putLong("mainColor", value.mainColor)
            .putLong("borderColor", value.borderColor).putBoolean("showCoordinates", value.showCoordinates).apply()
    }
}

@Composable
internal fun CrosshairConfigurationSection() {
    val context = LocalContext.current
    CrosshairPreferences.load(context)
    val config by CrosshairPreferences.configuration.collectAsState()
    fun update(value: CrosshairConfiguration) = CrosshairPreferences.save(context, value)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Crosshair Configuration:", style = MaterialTheme.typography.titleMedium)
        Row {
            listOf("None", "Simple", "Bordered").forEach { style ->
                TextButton(onClick = { update(config.copy(style = style)) }) {
                    Text(if (config.style == style) "✓ $style" else style)
                }
            }
        }
        var width by remember { mutableStateOf(config.widthPx.toString()) }
        OutlinedTextField(value = width, onValueChange = {
            width = it
            it.toFloatOrNull()?.takeIf { px -> px.isFinite() && px > 0 }?.let { px -> update(config.copy(widthPx = px)) }
        }, label = { Text("Width in px") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Size: ${config.sizePercent.toInt()}% of the narrower video dimension")
        Slider(value = config.sizePercent, onValueChange = { update(config.copy(sizePercent = it)) }, valueRange = 2f..25f, steps = 22)
        CrosshairColorField("Main color", config.mainColor) { update(config.copy(mainColor = it)) }
        CrosshairColorField("Border color", config.borderColor) { update(config.copy(borderColor = it)) }
        Row {
            Checkbox(checked = config.showCoordinates, onCheckedChange = { update(config.copy(showCoordinates = it)) })
            Text("Coordinates in MSL and REF")
        }
        Text("Use #RRGGBB colors. Center taps cycle MSL → REF → crosshair only. Settings apply to all streams.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CrosshairColorField(label: String, color: Long, onChange: (Long) -> Unit) {
    var text by remember(color) { mutableStateOf("#%06X".format(color and 0xFFFFFF)) }
    OutlinedTextField(value = text, onValueChange = {
        text = it
        if (it.matches(Regex("#[0-9a-fA-F]{6}"))) onChange(0xFF000000 or it.drop(1).toLong(16))
    }, label = { Text(label) }, leadingIcon = { Text("●", color = Color(color)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

internal fun stampClueCrosshair(source: android.graphics.Bitmap, coordinates: String?): android.graphics.Bitmap {
    val config = CrosshairPreferences.configuration.value
    if (config.style == "None") return source
    val image = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: return source
    val canvas = android.graphics.Canvas(image)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val cx = image.width / 2f; val cy = image.height / 2f
    val arm = minOf(image.width, image.height) * config.sizePercent / 200f
    fun lines(color: Long, width: Float) {
        paint.color = color.toInt(); paint.strokeWidth = width
        canvas.drawLine(cx-arm,cy,cx+arm,cy,paint)
        canvas.drawLine(cx,cy-arm,cx,cy+arm,paint)
    }
    if (config.style == "Bordered") lines(config.borderColor,config.widthPx+2f)
    lines(config.mainColor,config.widthPx)
    if (coordinates != null && config.showCoordinates) {
        paint.textSize = maxOf(14f, minOf(image.width,image.height)*0.018f)
        paint.typeface = android.graphics.Typeface.MONOSPACE
        val width = paint.measureText(coordinates)
        val metrics = paint.fontMetrics
        val x = cx-width/2f; val top = cy+arm+8f
        paint.color = android.graphics.Color.argb(192,0,0,0)
        canvas.drawRect(x-4f,top-3f,x+width+4f,top+metrics.descent-metrics.ascent+3f,paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawText(coordinates,x,top-metrics.ascent,paint)
    }
    return image
}
