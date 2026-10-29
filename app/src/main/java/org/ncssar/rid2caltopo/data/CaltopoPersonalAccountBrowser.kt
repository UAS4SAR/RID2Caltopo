/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import android.content.Intent
import android.net.Uri

/**
 * Opens CalTopo account activation in the system browser.
 * Google sign-in is not allowed inside this app's embedded WebView.
 * ACTION_VIEW resolves to the user's browser, where Custom Tabs or the
 * browser's own window handles the CalTopo and identity-provider pages.
 */
object CaltopoPersonalAccountBrowser {
    fun systemBrowserIntent(activationUrl: String): Intent {
        require(CaltopoPersonalAccountConnection.isActivationUrl(activationUrl)) {
            "Refusing to open a page that is not CalTopo account activation."
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse(activationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
