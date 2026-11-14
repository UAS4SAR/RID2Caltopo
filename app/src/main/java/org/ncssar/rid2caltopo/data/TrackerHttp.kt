/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ncssar.rid2caltopo.data

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * HTTP client for r2c-tracker organization requests.
 *
 * It has its own dispatcher and connection pool, so
 * [CaltopoSession.cancelInFlightRequestsForConnectivityRestore] cannot cancel a Tracker
 * check-in. The timeouts allow a scale-to-zero Tracker several seconds to start.
 */
object TrackerHttp {
    const val CONNECT_TIMEOUT_SECONDS = 20L
    const val READ_TIMEOUT_SECONDS = 40L
    const val CALL_TIMEOUT_SECONDS = 60L

    @JvmField
    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher())
        .connectionPool(ConnectionPool())
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .dns(Dns.SYSTEM)
        .build()
}
