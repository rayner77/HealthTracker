package com.inf2007.healthtracker.utilities

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkClient {
    private const val PINNER_HOST = "zining.duckdns.org"
    private const val PINNER_HASH = "sha256/ExSFZAGdghyWD1UU/GND7UmXXRntthAidfMabRfWrq0="

    val instance: OkHttpClient by lazy {
        // Create client with pinning
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .certificatePinner(
                CertificatePinner.Builder()
                    .add(PINNER_HOST, PINNER_HASH)
                    .build()
            )
            .build()
    }
}