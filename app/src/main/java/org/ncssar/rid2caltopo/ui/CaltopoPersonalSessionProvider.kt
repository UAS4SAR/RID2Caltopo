package org.ncssar.rid2caltopo.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** Not exported: only this application's main process can request scoped cookies. */
class CaltopoPersonalSessionProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle().apply {
        if (method == "catalog") runCatching { CaltopoPersonalProbeActivity.prepareCatalog(requireNotNull(context)); CaltopoPersonalProbeActivity.catalog() }.onSuccess { putString("catalog", it) }.onFailure { putBoolean("loginRequired", it is org.ncssar.rid2caltopo.data.PersonalCaltopoLoginRequired); putString("error", "Personal maps could not load.") }
        if (method == "authorizeMedia") putBoolean("authorized", CaltopoPersonalProbeActivity.authorizeMedia(arg, extras?.getString("mediaID")))
        if (method == "valid") putBoolean("valid", CaltopoPersonalProbeActivity.validSession(arg, extras?.getString("url")))
        if (method == "cookie") putString("cookie", CaltopoPersonalProbeActivity.cookieForSession(arg, extras?.getString("url")))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
