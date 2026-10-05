package com.nisaba.infra.http

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One shared OkHttp instance; per-request timeouts are derived with `newBuilder()` so the pool is reused. */
object NisabaHttpClient {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // the model client retries itself, only before an answer starts
            .build()
    }

    private const val CONNECT_SECONDS = 10L
    private const val READ_SECONDS = 90L
}
