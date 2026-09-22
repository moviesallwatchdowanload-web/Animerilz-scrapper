package com.animerulz

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import java.util.Locale

class CloudflareWebViewDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null,
    private val startExpanded: Boolean = true
) : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "Animerulz_CFDialog"
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 120000L

        private val CHALLENGE_TITLES = listOf(
            "just a moment",
            "checking your browser",
            "attention required",
            "ddos-guard",
            "one more step"
        )

        fun isChallengeTitle(title: String): Boolean {
            val lower = title.lowercase(Locale.ROOT)
            return CHALLENGE_TITLES.any { lower.contains(it) }
        }
    }

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            targetUrl
        }
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return

            CookieManager.getInstance().flush()
            val cookies = CookieManager.getInstance().getCookie(targetHost) ?: ""
            val title = webView?.title ?: ""
            val isChallenge = isChallengeTitle(title)

            Log.d(TAG, "Poll [${pollElapsedMs}ms] title='$title'")

            val cfClearance = Regex("cf_clearance=[^;]{15,}")
            val ddgCookie = Regex("__ddg[12]_=")

            when {
                cfClearance.containsMatchIn(cookies) && !isChallenge -> {
                    saveCookiesAndDismiss(cookies)
                }
                ddgCookie.containsMatchIn(cookies) && !isChallenge -> {
                    if (pollElapsedMs >= 60000L) {
                        saveCookiesAndDismiss(cookies)
                    } else {
                        scheduleNextPoll()
                    }
                }
                else -> {
                    if (pollElapsedMs >= POLL_TIMEOUT_MS) {
                        updateStatus("⏱️ Timed out. Solve CAPTCHA and tap Bypass again.")
                    } else {
                        scheduleNextPoll()
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext())
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Android TV", "Android")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            loadsImagesAutomatically = true
        }
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.requestFocus()

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!cookiesSaved) updateStatus("Loading… $newProgress%")
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (cookiesSaved) return
                val title = view?.title ?: ""
                Log.d(TAG, "onPageFinished title='$title' url=$url")

                if (isChallengeTitle(title)) {
                    updateStatus("🔄 Challenge active – solve the CAPTCHA above")
                    return
                }

                updateStatus("✏️ Page loaded – checking cookies…")
                CookieManager.getInstance().flush()
                val hostCookie = CookieManager.getInstance().getCookie(targetHost) ?: ""
                val urlCookie = try {
                    if (url != null) {
                        val uri = Uri.parse(url)
                        CookieManager.getInstance().getCookie("${uri.scheme}://${uri.host}") ?: ""
                    } else ""
                } catch (e: Exception) { "" }

                val combined = when {
                    Regex("cf_clearance=[^;]{15,}").containsMatchIn(hostCookie) -> hostCookie
                    Regex("cf_clearance=[^;]{15,}").containsMatchIn(urlCookie) -> urlCookie
                    else -> null
                }

                if (combined != null) {
                    handler.removeCallbacks(cookiePollRunnable)
                    saveCookiesAndDismiss(combined)
                }
            }
        }
        return wv
    }

    private fun saveCookiesAndDismiss(cookies: String) {
        if (cookiesSaved) return
        cookiesSaved = true
        handler.removeCallbacks(cookiePollRunnable)

        AnimerulzPlugin.setCfCookies(cookies)
        AnimerulzPlugin.setCfCookieHost(targetHost)
        AnimerulzPlugin.setCfUserAgent(webView?.settings?.userAgentString ?: "")

        Log.d(TAG, "✅ Saved cookies")
        updateStatus("✅ Done! Cookies saved.")

        webView?.postDelayed({
            if (isAdded) {
                onFinished?.invoke(true)
                dismissAllowingStateLoss()
            }
        }, 1500L)
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Waiting for cookies… (${pollElapsedMs / 1000}s)")
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    private fun updateStatus(msg: String) {
        activity?.runOnUiThread {
            statusText?.text = msg
            if (msg.startsWith("✅")) {
                progressBar?.visibility = View.GONE
                statusText?.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                progressBar?.visibility = View.VISIBLE
                statusText?.setTextColor(Color.parseColor("#A0A0B0"))
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        if (startExpanded) {
            dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setDimAmount(0.5f)
            (dialog as? BottomSheetDialog)?.behavior?.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                peekHeight = -1
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val title = TextView(requireContext()).apply {
            text = "🛡️ Animerulz – Cloudflare Bypass"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }
        root.addView(title)

        statusText = TextView(requireContext()).apply {
            text = "Loading challenge page…"
            textSize = 13f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, 4)
        }
        root.addView(statusText)

        val hint = TextView(requireContext()).apply {
            text = "Solve any CAPTCHA shown below. The dialog will close automatically once done."
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, 12)
        }
        root.addView(hint)

        progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyle).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 }
        }
        root.addView(progressBar)

        val containerFrame = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        webView = buildWebView()
        containerFrame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(containerFrame)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cm = CookieManager.getInstance()
        val host = try {
            Uri.parse(targetHost).host ?: targetHost
        } catch (e: Exception) { targetHost }

        // Clear old CF cookies
        listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog", "__ddg1_", "__ddg2_", "__cfruid").forEach { name ->
            cm.setCookie(targetHost, "$name=; domain=$host; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cm.setCookie(targetHost, "$name=; domain=.$host; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cm.setCookie(targetHost, "$name=; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
        }
        cm.flush()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        cm.flush()

        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(-1, -1)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!cookiesSaved) {
            handler.removeCallbacks(cookiePollRunnable)
            onFinished?.invoke(false)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(cookiePollRunnable)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }
}
