/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import android.content.Context
import org.ncssar.rid2caltopo.app.CaltopoPersonalAuthActivity

/**
 * Opens CalTopo account activation in an Auth Tab. Google sign-in stays in that
 * Chrome surface, not in this app's embedded WebView.
 */
object CaltopoPersonalAccountBrowser {
    fun open(context: Context, activationUrl: String) {
        context.startActivity(CaltopoPersonalAuthActivity.intent(context, activationUrl))
    }
}
