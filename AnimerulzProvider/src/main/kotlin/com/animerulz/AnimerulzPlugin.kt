package com.animerulz

import android.content.Context
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimerulzPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(AnimerulzProvider())
    }

    companion object {
        @Volatile
        private var cfCookies: String = ""

        @Volatile
        private var cfCookieHost: String = ""

        @Volatile
        private var cfUserAgent: String = ""

        fun getCfCookies(): String = cfCookies
        fun setCfCookies(cookies: String) { cfCookies = cookies }

        fun getCfCookieHost(): String = cfCookieHost
        fun setCfCookieHost(host: String) { cfCookieHost = host }

        fun getCfUserAgent(): String = cfUserAgent
        fun setCfUserAgent(ua: String) { cfUserAgent = ua }
    }
}
