package org.ncssar.rid2caltopo.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.core.content.ContextCompat
import org.ncssar.rid2caltopo.data.CaltopoClient

/** Observe input, never consume it. Browser input crosses its private process boundary. */
object UserInteractionTracker {
    private const val ACTION = "org.ncssar.rid2caltopo.USER_INTERACTION"

    fun record(context: Context) {
        if (Application.getProcessName().endsWith(":caltopo_probe")) {
            context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
        } else {
            CaltopoClient.NoteUserInteraction()
        }
    }

    fun install(application: Application) {
        if (!Application.getProcessName().endsWith(":caltopo_probe")) {
            ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == ACTION) CaltopoClient.NoteUserInteraction()
                }
            }, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { observe(activity.window) }
            override fun onActivityResumed(activity: Activity) { observe(activity.window) }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun observe(window: Window): () -> Unit {
        val original = window.callback ?: return {}
        if (original is InputCallback) return {}
        val wrapped = InputCallback(original, window.context.applicationContext)
        window.callback = wrapped
        val removeAccessibility = observeAccessibilityRoot(window.decorView)
        return {
            if (window.callback === wrapped) window.callback = original
            removeAccessibility()
        }
    }

    fun observeAccessibilityRoot(root: android.view.View): () -> Unit {
        val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { observeAccessibility(root) }
        root.viewTreeObserver.addOnGlobalLayoutListener(layout)
        observeAccessibility(root)
        return { root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layout) }
    }

    private val accessibilityObservers = java.util.WeakHashMap<android.view.View, java.lang.ref.WeakReference<android.view.View.AccessibilityDelegate>>()

    private fun observeAccessibility(view: android.view.View) {
        val original = view.accessibilityDelegate
        if (accessibilityObservers[view]?.get() !== original || original == null) {
            androidx.core.view.ViewCompat.setAccessibilityDelegate(view,
                InputAccessibilityDelegate(original ?: android.view.View.AccessibilityDelegate(), view.context.applicationContext))
            view.accessibilityDelegate?.let { accessibilityObservers[view] = java.lang.ref.WeakReference(it) }
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) observeAccessibility(view.getChildAt(index))
        }
    }

    private class InputAccessibilityDelegate(original: android.view.View.AccessibilityDelegate, private val context: Context) :
        androidx.core.view.AccessibilityDelegateCompat(original) {
        // Native controls and Compose/WebView virtual nodes use different entry points.
        override fun performAccessibilityAction(host: android.view.View, action: Int, args: Bundle?): Boolean {
            record(context)
            return super.performAccessibilityAction(host, action, args)
        }
        override fun getAccessibilityNodeProvider(host: android.view.View): androidx.core.view.accessibility.AccessibilityNodeProviderCompat? {
            val delegate = super.getAccessibilityNodeProvider(host) ?: return null
            return object : androidx.core.view.accessibility.AccessibilityNodeProviderCompat() {
                override fun createAccessibilityNodeInfo(id: Int) = delegate.createAccessibilityNodeInfo(id)
                override fun findAccessibilityNodeInfosByText(text: String, id: Int) = delegate.findAccessibilityNodeInfosByText(text, id)
                override fun findFocus(focus: Int) = delegate.findFocus(focus)
                override fun addExtraDataToAccessibilityNodeInfo(id: Int, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat, key: String, args: Bundle?) {
                    delegate.addExtraDataToAccessibilityNodeInfo(id, info, key, args)
                }
                override fun performAction(id: Int, action: Int, args: Bundle?): Boolean {
                    record(context)
                    return delegate.performAction(id, action, args)
                }
            }
        }
    }

    private class InputCallback(
        private val delegate: Window.Callback,
        private val context: Context
    ) : Window.Callback by delegate {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            record(context)
            return delegate.dispatchTouchEvent(event)
        }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            record(context)
            return delegate.dispatchGenericMotionEvent(event)
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            record(context)
            return delegate.dispatchKeyEvent(event)
        }
        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
            record(context)
            return delegate.dispatchKeyShortcutEvent(event)
        }
    }

    /** IMEs can commit text without dispatching a key event to the window. */
    fun inputConnection(connection: InputConnection, context: Context): InputConnection =
        object : InputConnectionWrapper(connection, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                record(context)
                return super.commitText(text, newCursorPosition)
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                record(context)
                return super.setComposingText(text, newCursorPosition)
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                record(context)
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                record(context)
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                record(context)
                return super.sendKeyEvent(event)
            }
            @androidx.annotation.RequiresApi(33)
            override fun commitText(text: CharSequence, newCursorPosition: Int, attributes: android.view.inputmethod.TextAttribute?): Boolean {
                record(context)
                return super.commitText(text, newCursorPosition, attributes)
            }
            @androidx.annotation.RequiresApi(33)
            override fun setComposingText(text: CharSequence, newCursorPosition: Int, attributes: android.view.inputmethod.TextAttribute?): Boolean {
                record(context)
                return super.setComposingText(text, newCursorPosition, attributes)
            }
            override fun performContextMenuAction(id: Int): Boolean {
                record(context)
                return super.performContextMenuAction(id)
            }
            override fun performEditorAction(actionCode: Int): Boolean {
                record(context)
                return super.performEditorAction(actionCode)
            }
        }
}
