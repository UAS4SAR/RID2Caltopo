package org.ncssar.rid2caltopo.ui

import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.ncssar.rid2caltopo.app.UserInteractionTracker

/** Dialogs have their own window; activity input callbacks do not see their events. */
@Composable
private fun ObserveDialogInput() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val remove = window?.let(UserInteractionTracker::observe)
            ?: UserInteractionTracker.observeAccessibilityRoot(view.rootView)
        onDispose { remove?.invoke() }
    }
}

@Composable
fun Dialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
        ObserveDialogInput()
        content()
    }
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { ObserveDialogInput(); confirmButton() },
        modifier = modifier, dismissButton = dismissButton, icon = icon, title = title, text = text,
        shape = shape, containerColor = containerColor, iconContentColor = iconContentColor,
        titleContentColor = titleContentColor, textContentColor = textContentColor,
        tonalElevation = tonalElevation, properties = properties
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ObserveTextInput(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.ui.platform.InterceptPlatformTextInput(
        interceptor = androidx.compose.runtime.remember(context) {
            androidx.compose.ui.platform.PlatformTextInputInterceptor { request, next ->
                next.startInputMethod(object : androidx.compose.ui.platform.PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: android.view.inputmethod.EditorInfo) =
                        UserInteractionTracker.inputConnection(request.createInputConnection(outAttributes), context)
                })
            }
        }, content = content
    )
}

/** Popups own a separate input root rather than an Activity window. */
@Composable
fun Modifier.observeUserInput(): Modifier {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    return this.onPreviewKeyEvent { UserInteractionTracker.record(context); false }
        .pointerInput(context) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    UserInteractionTracker.record(context)
                }
            }
        }
}

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset(0.dp, 0.dp),
    scrollState: androidx.compose.foundation.ScrollState = androidx.compose.foundation.rememberScrollState(),
    properties: androidx.compose.ui.window.PopupProperties = androidx.compose.ui.window.PopupProperties(focusable = true),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = { UserInteractionTracker.record(context); onDismissRequest() },
        modifier = modifier.observeUserInput(), offset = offset, scrollState = scrollState, properties = properties
    ) {
        ObserveDialogInput()
        content()
    }
}
