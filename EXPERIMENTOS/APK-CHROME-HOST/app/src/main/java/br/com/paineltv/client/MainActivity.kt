package br.com.paineltv.chromehost

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        val targetUrl = resolveTargetUrl()
        if (openInChrome(targetUrl)) {
            finish()
            return
        }
        if (openInDefaultBrowser(targetUrl)) {
            finish()
            return
        }

        // Last resort: if no external browser is available, use internal WebView.
        root = FrameLayout(this)
        setContentView(root)
        createWebView()
        webView?.loadUrl(targetUrl)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun resolveTargetUrl(): String {
        val prefs = getSharedPreferences("chrome_host_prefs", MODE_PRIVATE)
        val defaultUrl = "https://sigss.chapadaodoceu.go.gov.br/unique-panel/"
        val fromIntent = intent?.dataString?.trim().orEmpty()
        val fromExtra = intent?.getStringExtra("panelUrl")?.trim().orEmpty()
        val candidate = when {
            fromExtra.startsWith("http", ignoreCase = true) -> fromExtra
            fromIntent.startsWith("http", ignoreCase = true) -> fromIntent
            else -> prefs.getString("panelUrl", defaultUrl).orEmpty()
        }
        val normalized = if (candidate.startsWith("http", ignoreCase = true)) candidate else defaultUrl
        prefs.edit().putString("panelUrl", normalized).apply()
        return normalized
    }

    private fun openInChrome(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            true
        } else {
            false
        }
    }

    private fun openInDefaultBrowser(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            true
        } else {
            false
        }
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
}
