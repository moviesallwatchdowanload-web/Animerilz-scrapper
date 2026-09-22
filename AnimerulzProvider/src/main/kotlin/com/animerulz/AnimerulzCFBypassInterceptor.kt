package com.animerulz

import okhttp3.Interceptor
import okhttp3.Response

class AnimerulzCFBypassInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // CF cookies add
        val savedCookies = AnimerulzPlugin.getCfCookies()
        if (savedCookies.isNotEmpty()) {
            val existingCookie = original.header("Cookie") ?: ""
            val existingParts = existingCookie.split(";").map { it.trim() }.filter { it.isNotEmpty() }

            // Remove old cf_clearance from existing
            val existingFiltered = existingParts.filter { !it.startsWith("cf_clearance=") }

            // Saved cookies from webview
            val savedParts = savedCookies.split(";").map { it.trim() }.filter { it.isNotEmpty() }

            val merged = (savedParts + existingFiltered).distinct()
            builder.header("Cookie", merged.joinToString("; "))
        }

        // User agent from CF dialog
        val savedUA = AnimerulzPlugin.getCfUserAgent()
        if (savedUA.isNotEmpty()) {
            builder.header("User-Agent", savedUA)
        }

        // Mobile headers
        builder.header("sec-ch-ua-mobile", "?1")
        builder.header("sec-ch-ua-platform", "\"Android\"")

        return chain.proceed(builder.build())
    }
}
