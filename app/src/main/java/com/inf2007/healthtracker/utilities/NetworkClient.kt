package com.inf2007.healthtracker.utilities

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkClient {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}