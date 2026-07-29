package com.derpy.earmarks.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's single OkHttp client, shared by the player screen and the
 * background sync worker.
 *
 * One instance rather than one per caller: each client owns its own connection
 * pool and dispatcher threads, and the two paths talk to the same handful of
 * relays and Blossom servers, so sharing keeps connections warm instead of
 * duplicating the machinery.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
