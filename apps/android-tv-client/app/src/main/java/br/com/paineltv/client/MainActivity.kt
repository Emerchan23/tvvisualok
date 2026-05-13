package br.com.paineltv.client

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var currentUrl: String = "about:blank"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        root = FrameLayout(this)
        setContentView(root)
        createWebView()

        // V1 Android real: carregar token salvo, conectar WebSocket, receber config e aplicar URL.
        applyUrl(currentUrl)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        webView?.destroy()
        webView = WebView(this).also { view ->
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            view.webViewClient = WebViewClient()
            view.webChromeClient = WebChromeClient()
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.mediaPlaybackRequiresUserGesture = false
            view.settings.cacheMode = WebSettings.LOAD_DEFAULT
            root.removeAllViews()
            root.addView(view)
        }
    }

    private fun applyUrl(url: String) {
        currentUrl = url.ifBlank { "about:blank" }
        webView?.loadUrl(currentUrl)
    }

    private fun reloadPage() {
        webView?.reload()
    }

    private fun recreateWebView() {
        createWebView()
        applyUrl(currentUrl)
    }

    private fun clearCache() {
        webView?.clearCache(true)
    }
}
