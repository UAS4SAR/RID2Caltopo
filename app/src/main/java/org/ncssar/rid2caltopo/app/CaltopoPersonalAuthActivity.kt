/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import org.ncssar.rid2caltopo.data.CaltopoPersonalAccountConnection

/**
 * In-app CalTopo sign-in. Google stays in Chrome's auth surface, not this app's WebView.
 * The Auth Tab closes when the loopback success page redirects to [CaltopoPersonalAccountConnection.RETURN_SCHEME].
 */
class CaltopoPersonalAuthActivity : ComponentActivity() {
    private var launched = false
    private var usedCustomTab = false
    private var leftForBrowser = false
    private var ownsSession = false
    private val launcher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        android.util.Log.i("CaltopoPersonalAuth", "auth tab result=${result.resultCode}")
        when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> finish()
            AuthTabIntent.RESULT_UNKNOWN_CODE -> if (!usedCustomTab) openCustomTab() else abandon()
            else -> abandon()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_CLOSE) {
            abandon()
            return
        }
        if (intent?.data?.scheme == CaltopoPersonalAccountConnection.RETURN_SCHEME) {
            android.util.Log.i("CaltopoPersonalAuth", "return scheme delivered")
            finish()
            return
        }
        val app = applicationContext
        CaltopoPersonalAccountConnection.onEnrollmentAbandoned = {
            app.startActivity(closeIntent(app))
        }
        ownsSession = true
        android.util.Log.i("CaltopoPersonalAuth", "auth surface process=${android.app.Application.getProcessName()}")
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, CaltopoPersonalAuthService::class.java))
        }.onFailure {
            android.util.Log.i("CaltopoPersonalAuth", "foreground service start failed")
        }
    }

    override fun onPause() {
        if (launched) leftForBrowser = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (launched) {
            if (usedCustomTab && leftForBrowser) abandon()
            return
        }
        val url = intent?.getStringExtra(EXTRA_URL) ?: return finish()
        if (!CaltopoPersonalAccountConnection.isActivationUrl(url)) return finish()
        launched = true
        val uri = Uri.parse(url)
        try {
            AuthTabIntent.Builder().build().launch(launcher, uri, CaltopoPersonalAccountConnection.RETURN_SCHEME)
        } catch (error: ActivityNotFoundException) {
            openCustomTab()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_CLOSE) {
            abandon()
            return
        }
        if (intent.data?.scheme == CaltopoPersonalAccountConnection.RETURN_SCHEME) {
            android.util.Log.i("CaltopoPersonalAuth", "return scheme delivered")
            finish()
        }
    }

    override fun onDestroy() {
        if (ownsSession) {
            CaltopoPersonalAccountConnection.onEnrollmentAbandoned = null
            stopService(Intent(this, CaltopoPersonalAuthService::class.java))
        }
        super.onDestroy()
    }

    private fun openCustomTab() {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return abandon()
        usedCustomTab = true
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
                .launchUrl(this, uri)
        } catch (error: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (nested: ActivityNotFoundException) {
                abandon()
            }
        }
    }

    private fun abandon() {
        CaltopoPersonalAccountConnection.cancelEnrollment()
        finish()
    }

    companion object {
        private const val EXTRA_URL = "activationUrl"
        const val ACTION_CLOSE = "org.ncssar.rid2caltopo.caltopo.CLOSE_AUTH"

        fun intent(context: Context, activationUrl: String): Intent {
            require(CaltopoPersonalAccountConnection.isActivationUrl(activationUrl)) {
                "Refusing to open a page that is not CalTopo account activation."
            }
            return Intent(context, CaltopoPersonalAuthActivity::class.java)
                .putExtra(EXTRA_URL, activationUrl)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun closeIntent(context: Context): Intent =
            Intent(context, CaltopoPersonalAuthActivity::class.java)
                .setAction(ACTION_CLOSE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
