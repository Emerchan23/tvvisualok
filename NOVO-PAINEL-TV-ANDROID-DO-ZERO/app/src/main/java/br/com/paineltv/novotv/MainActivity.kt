package br.com.paineltv.novotv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextUtils
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private val tag = "PainelTVNovo"

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var container: FrameLayout
    private lateinit var webView: WebView
    private lateinit var overlayRoot: FrameLayout
    private lateinit var overlayContent: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayQr: ImageView
    private lateinit var overlayText: TextView
    private lateinit var overlayButtonPrimary: MaterialButton
    private lateinit var overlayButtonSecondary: MaterialButton
    private lateinit var callOverlay: TextView
    private lateinit var nativePanelRoot: LinearLayout
    private lateinit var nativeBodyRow: LinearLayout
    private lateinit var nativeSideColumn: LinearLayout
    private lateinit var nativeHeaderCard: LinearLayout
    private lateinit var nativeUnitLogoView: ImageView
    private lateinit var nativeUnitNameText: TextView
    private lateinit var nativeUnitSubtitleText: TextView
    private lateinit var nativeCurrentCard: LinearLayout
    private lateinit var nativeHistoryCard: LinearLayout
    private lateinit var nativeMediaCard: LinearLayout
    private var nativeMediaWebView: WebView? = null
    private lateinit var nativeModeText: TextView
    private lateinit var nativeCurrentLabelText: TextView
    private lateinit var nativeCurrentHintText: TextView
    private lateinit var nativeStatusText: TextView
    private lateinit var nativePatientText: TextView
    private lateinit var nativeRoomText: TextView
    private lateinit var nativeProfessionalText: TextView
    private lateinit var nativePriorityText: TextView
    private lateinit var nativeUpdatedText: TextView
    private lateinit var nativeHistoryTitleText: TextView
    private lateinit var nativeHistoryHintText: TextView
    private lateinit var nativeMediaTitleText: TextView
    private lateinit var nativeMediaHintText: TextView
    private lateinit var nativeList: LinearLayout
    private lateinit var nativeClockText: TextView
    private lateinit var nativeDateText: TextView

    private val prefs by lazy { getSharedPreferences("painel_tv_novo", Context.MODE_PRIVATE) }

    private var networkAvailable: Boolean = false
    private var lastPageOkAt: Long = 0L
    private var lastLoadStartedAt: Long = 0L
    private var lastPanelNetworkActivityAt: Long = 0L
    private var consecutiveErrors: Int = 0
    private var reloadScheduled: Boolean = false
    private var pairingInProgress: Boolean = false
    private var pairingId: String? = null
    private var pairingSecret: String? = null
    private var pairingExpiresAt: String? = null
    private var ws: WebSocket? = null
    private var heartbeatIntervalSeconds: Int = 15
    private var heartbeatReloads: Int = 0
    private var heartbeatReconnects: Int = 0
    private var webViewRecreates: Int = 0
    private var appRestarts: Int = 0
    private var panelNetworkEvents: Int = 0
    private var panelAutoReloads: Int = 0
    private var lastError: String = ""
    private var lastAnnouncement: String = ""
    private var lastAnnouncementAt: Long = 0L
    private var appStartedAtMs: Long = SystemClock.elapsedRealtime()
    private var audioEnabled: Boolean = true
    private var ttsVoicePreference: String = "default"
    private var currentTtsVoice: String = ""
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var pendingTtsText: String = ""
    private var lastTtsStatus: String = "iniciando"
    private var speechPlayer: MediaPlayer? = null
    private var currentContentUrl: String = ""
    private var currentDisplayMode: String = "panel_only"
    private var currentMediaUrl: String = ""
    private var currentMediaPlaybackUrl: String = ""
    private var currentThemeId: String = "classic_warm"
    private var currentUnitName: String = ""
    private var currentUnitLogoUrl: String = ""
    private var nativeModeActive: Boolean = false
    private var nativePanelBaseUrl: String = ""
    private var nativePanelId: String = ""
    private var nativePollFailures: Int = 0
    private var nativeLastSuccessAt: Long = 0L
    private var nativeLastCallDetectedAt: Long = 0L
    private var nativePollInitialized: Boolean = false
    private var nativeLastEventKey: String = ""
    private var nativePolling: Boolean = false
    private var nativeLastRenderedCalls: List<SigssCall> = emptyList()
    private var expectedRestart: Boolean = false
    private var shutdownRequested: Boolean = false

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val alarmManager by lazy {
        getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable = true
            mainHandler.post {
                if (hasActivationToken()) {
                    hideOverlay()
                    ensureWebSocketConnected()
                } else if (!pairingInProgress) {
                    showPairingIdle()
                }
            }
        }

        override fun onLost(network: Network) {
            networkAvailable = hasValidatedNetwork(connectivityManager)
            mainHandler.post {
                if (!networkAvailable) {
                    showStatus(getString(R.string.status_offline))
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            networkAvailable = hasInternet
            mainHandler.post {
                if (hasInternet) {
                    if (hasActivationToken()) {
                        hideOverlay()
                    } else if (!pairingInProgress) {
                        showPairingIdle()
                    }
                    if (!validated) {
                        showStatus(getString(R.string.status_sem_internet))
                    }
                } else {
                    showStatus(getString(R.string.status_offline))
                }
            }
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()

            if (networkAvailable) {
                if (nativeModeActive) {
                    if (nativeLastSuccessAt > 0L && now - nativeLastSuccessAt > 60_000L) {
                        pollNativeSigssPanel()
                    }
                } else {
                    val sinceOk = now - lastPageOkAt
                    val sinceStart = now - lastLoadStartedAt

                    if (lastPageOkAt > 0L && sinceOk > 5 * 60_000L) {
                        scheduleReload(delayMs = 0L)
                    } else if (lastLoadStartedAt > 0L && sinceStart > 90_000L) {
                        scheduleReload(delayMs = 0L)
                    }
                }
            }

            mainHandler.postDelayed(this, 30_000L)
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            mainHandler.postDelayed(this, (heartbeatIntervalSeconds.coerceIn(5, 60) * 1000).toLong())
        }
    }

    private val nativeSigssPollRunnable = object : Runnable {
        override fun run() {
            if (nativeModeActive) {
                pollNativeSigssPanel()
                mainHandler.postDelayed(this, 2_000L)
            }
        }
    }

    private val presenceRunnable = object : Runnable {
        override fun run() {
            markActivityAlive(true)
            mainHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.edit()
            .putBoolean("shutdown_requested", false)
            .putBoolean("maintenance_mode", false)
            .apply()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                lastError = throwable.message ?: throwable.javaClass.simpleName
                scheduleSelfRestart("uncaught")
            } catch (_: Throwable) {
            }
            try {
                exitProcess(10)
            } catch (_: Throwable) {
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        container = FrameLayout(this)
        container.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        overlayRoot = FrameLayout(this)
        overlayRoot.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlayRoot.setBackgroundColor(0xCC000000.toInt())
        overlayRoot.isVisible = false

        overlayContent = LinearLayout(this)
        overlayContent.orientation = LinearLayout.VERTICAL
        overlayContent.isFocusable = true
        overlayContent.isFocusableInTouchMode = true
        overlayContent.setPadding(40, 40, 40, 40)
        overlayContent.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        overlayTitle = TextView(this)
        overlayTitle.setTextColor(0xFFFFFFFF.toInt())
        overlayTitle.textSize = 22f

        overlayQr = ImageView(this)
        overlayQr.adjustViewBounds = true
        overlayQr.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        overlayText = TextView(this)
        overlayText.setTextColor(0xFFFFFFFF.toInt())
        overlayText.textSize = 16f

        overlayButtonPrimary = MaterialButton(this)
        overlayButtonPrimary.isAllCaps = false

        overlayButtonSecondary = MaterialButton(this)
        overlayButtonSecondary.isAllCaps = false

        overlayContent.addView(overlayTitle)
        overlayContent.addView(overlayQr)
        overlayContent.addView(overlayText)
        overlayContent.addView(overlayButtonPrimary)
        overlayContent.addView(overlayButtonSecondary)
        overlayRoot.addView(overlayContent)

        callOverlay = TextView(this)
        callOverlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }
        callOverlay.setBackgroundColor(0xAA000000.toInt())
        callOverlay.setTextColor(Color.WHITE)
        callOverlay.textSize = 20f
        callOverlay.setPadding(32, 24, 32, 24)
        callOverlay.isVisible = false

        nativePanelRoot = createNativePanelView()
        nativePanelRoot.isVisible = false
        applyNativeTheme()

        container.addView(webView)
        container.addView(nativePanelRoot)
        container.addView(callOverlay)
        container.addView(overlayRoot)
        setContentView(container)

        overlayRoot.isClickable = true
        overlayRoot.isFocusable = true
        overlayRoot.isFocusableInTouchMode = true
        overlayRoot.bringToFront()

        setupWebView()
        applyImmersive()

        val base = getServerBaseUrl()
        if (hasActivationToken()) {
            if (base.isBlank()) {
                applyContentUrl(getFallbackPanelUrl())
                showServerMissing()
            } else {
                applyContentUrl(getFallbackPanelUrl())
                showConnecting()
                ensureWebSocketConnected()
            }
        } else {
            applyContentUrl("about:blank")
            showPairingIdle()
        }

        ensureTts()
        startKeepAliveService()
    }

    override fun onStart() {
        super.onStart()
        shutdownRequested = false
        expectedRestart = false
        markActivityAlive(true)
        networkAvailable = hasValidatedNetwork(connectivityManager)
        if (!networkAvailable) {
            showStatus(getString(R.string.status_offline))
        }
        if (hasActivationToken() && networkAvailable) {
            val base = getServerBaseUrl()
            if (base.isBlank()) {
                showServerMissing()
            } else {
                ensureWebSocketConnected()
            }
        }

        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        mainHandler.post(watchdogRunnable)
        mainHandler.post(presenceRunnable)
        if (nativeModeActive) {
            mainHandler.removeCallbacks(nativeSigssPollRunnable)
            mainHandler.post(nativeSigssPollRunnable)
        }
    }

    override fun onResume() {
        super.onResume()
        markActivityAlive(true)
        startKeepAliveService()
    }

    override fun onPause() {
        markActivityAlive(false)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        markActivityAlive(false)
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {
        }
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(nativeSigssPollRunnable)
        mainHandler.removeCallbacks(presenceRunnable)
        disconnectWebSocket()
    }

    override fun onDestroy() {
        super.onDestroy()
        markActivityAlive(false)
        stopClockUpdates()
        mainHandler.removeCallbacksAndMessages(null)
        disconnectWebSocket()
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
        try {
            speechPlayer?.release()
        } catch (_: Throwable) {
        }
        speechPlayer = null
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        try {
            webView.stopLoading()
        } catch (_: Throwable) {
        }
        try {
            webView.destroy()
        } catch (_: Throwable) {
        }
        destroyNativeMediaWebView()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showSettingsDialog()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun setupWebView() {
        configureWebViewBase(webView, installBridge = true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return interceptPanelBootstrap(request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                lastLoadStartedAt = SystemClock.elapsedRealtime()
            }

            override fun onPageFinished(view: WebView, url: String) {
                lastPageOkAt = SystemClock.elapsedRealtime()
                consecutiveErrors = 0
                if (hasActivationToken()) hideOverlay()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                consecutiveErrors += 1
                showStatus(getString(R.string.status_erro_carregar))
                scheduleReload(computeBackoffMs(consecutiveErrors))
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (!request.isForMainFrame) return
                consecutiveErrors += 1
                showStatus(getString(R.string.status_erro_servidor, errorResponse.statusCode))
                scheduleReload(computeBackoffMs(consecutiveErrors))
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                consecutiveErrors += 1
                handler.cancel()
                showStatus(getString(R.string.status_erro_ssl))
                scheduleReload(computeBackoffMs(consecutiveErrors))
            }
        }
    }

    private fun configureWebViewBase(target: WebView, installBridge: Boolean = false) {
        val s = target.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportZoom(false)
        s.userAgentString = s.userAgentString + " PainelTVNovo/1.0"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                s.forceDark = WebSettings.FORCE_DARK_OFF
            } catch (_: Throwable) {
            }
        }

        target.isVerticalScrollBarEnabled = false
        target.isHorizontalScrollBarEnabled = false
        target.overScrollMode = View.OVER_SCROLL_NEVER
        target.setBackgroundColor(Color.BLACK)
        if (installBridge) {
            target.addJavascriptInterface(NativeBridge(), "NativeBridge")
        }
        target.webChromeClient = WebChromeClient()
    }

    private fun interceptPanelBootstrap(request: WebResourceRequest): WebResourceResponse? {
        if (!request.isForMainFrame) return null
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val url = request.url ?: return null
        val path = url.path ?: return null
        if (!path.contains("/unique-panel/panel-screen/")) return null
        val req = Request.Builder().url(url.toString()).get().apply {
            for ((k, v) in request.requestHeaders) {
                if (k.equals("accept-encoding", ignoreCase = true)) continue
                header(k, v)
            }
        }.build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                val contentType = resp.header("content-type") ?: "text/html; charset=utf-8"
                if (!contentType.contains("text/html")) return null
                val html = resp.body?.string().orEmpty()
                val injected = injectBootstrap(html)
                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    resp.code,
                    resp.message.ifBlank { "OK" },
                    mapOf("cache-control" to "no-store"),
                    ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8))
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun injectBootstrap(html: String): String {
        val script = buildString {
            append("<script>")
            append("(function(){")
            append("function safeCall(m,a){try{if(window.NativeBridge&&window.NativeBridge[m])window.NativeBridge[m](a);}catch(e){}}")
            append("function notifyCall(txt){safeCall('onCallJson',txt);}function notifyErr(txt){safeCall('onPanelError',txt);}function notifyLog(tag,msg){try{if(window.NativeBridge&&window.NativeBridge.onLog)window.NativeBridge.onLog(tag,msg);}catch(e){}}")
            append("function maybeCall(txt){try{if(!txt) return; if(txt.length>20000) return; var ok=false; try{var o=JSON.parse(txt); var s=JSON.stringify(o); ok=/(\\\"patient\\\"|\\\"paciente\\\"|\\\"patientName\\\"|\\\"nome\\\"|\\\"room\\\"|\\\"sala\\\"|\\\"guiche\\\"|\\\"professional\\\"|\\\"profissional\\\")/.test(s);}catch(e){ok=/paciente|patient|sala|guiche|consultorio|profissional/i.test(txt);} if(ok) notifyCall(txt);}catch(e){}}")
            append("var origFetch=window.fetch; if(origFetch){window.fetch=function(){var args=arguments; return origFetch.apply(this,args).then(function(res){try{var c=res.clone(); c.text().then(function(t){maybeCall(t);}).catch(function(){});}catch(e){} if(!res||!res.ok){notifyErr('fetch_'+(res?res.status:'fail'));} return res;}).catch(function(err){notifyErr('fetch_fail'); throw err;});};}")
            append("(function(){var X=window.XMLHttpRequest; if(!X) return; var open=X.prototype.open; var send=X.prototype.send; X.prototype.open=function(m,u){this.__u=u; return open.apply(this,arguments);}; X.prototype.send=function(){var self=this; var prev=this.onreadystatechange; this.onreadystatechange=function(){try{if(self.readyState===4){try{maybeCall(String(self.responseText||''));}catch(e){} if(self.status>=400){notifyErr('xhr_'+self.status);} } }catch(e){} if(prev) return prev.apply(this,arguments);}; return send.apply(this,arguments);};})();")
            append("notifyLog('BOOT','ok');")
            append("})();")
            append("</script>")
        }
        val headClose = html.indexOf("</head>", ignoreCase = true)
        if (headClose >= 0) {
            return html.substring(0, headClose) + script + html.substring(headClose)
        }
        return script + html
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun onCallJson(json: String) {
            mainHandler.post { handleCallEvent(json) }
        }

        @JavascriptInterface
        fun onPanelError(message: String) {
            mainHandler.post { handlePanelError(message) }
        }

        @JavascriptInterface
        fun onLog(tag: String, msg: String) {
            mainHandler.post { sendDeviceLog(tag, msg) }
        }
    }

    private fun handlePanelError(message: String) {
        lastError = message
        panelAutoReloads += 1
        scheduleReload(delayMs = 0L)
    }

    private fun handleCallEvent(raw: String) {
        lastPanelNetworkActivityAt = SystemClock.elapsedRealtime()
        panelNetworkEvents += 1
        val data = try {
            val t = raw.trim()
            when {
                t.startsWith("[") -> {
                    val arr = JSONArray(t)
                    if (arr.length() > 0 && arr.opt(0) is JSONObject) arr.optJSONObject(0) else JSONObject()
                }
                t.startsWith("{") -> JSONObject(t)
                else -> JSONObject()
            }
        } catch (_: Throwable) {
            JSONObject()
        }

        val name = pickFirstString(data, listOf("patient", "patientName", "nome", "name", "paciente"))
        val room = pickFirstString(data, listOf("room", "sala", "local", "location", "guiche", "desk"))
        val prof = pickFirstString(data, listOf("professional", "profissional", "doctor", "medico"))

        val phrase = buildAnnouncement(name, room, prof)
        val now = SystemClock.elapsedRealtime()
        if (phrase == lastAnnouncement && now - lastAnnouncementAt < 5_000L) return
        lastAnnouncement = phrase
        lastAnnouncementAt = now
        showCallOverlay(phrase)
        speak(phrase)
        sendDeviceLog("CALL", phrase)
    }

    private fun pickFirstString(obj: JSONObject?, keys: List<String>): String {
        if (obj == null) return ""
        for (k in keys) {
            val v = obj.opt(k)
            if (v is String && v.isNotBlank()) return v
        }
        return ""
    }

    private fun buildAnnouncement(name: String, room: String, prof: String): String {
        val parts = mutableListOf<String>()
        val n = name.trim().ifBlank { "paciente" }
        parts += "Chamando o paciente $n."
        val cleanRoom = room.trim()
        if (cleanRoom.isNotBlank()) {
            parts += "Por favor comparecer a sala $cleanRoom."
        }
        val cleanProf = prof.trim()
        if (cleanProf.isNotBlank()) {
            parts += "Com profissional $cleanProf."
        }
        return parts.joinToString(" ")
    }

    private fun showCallOverlay(text: String) {
        callOverlay.text = text
        callOverlay.isVisible = true
        mainHandler.removeCallbacks(hideCallRunnable)
        mainHandler.postDelayed(hideCallRunnable, 12_000L)
    }

    private val hideCallRunnable = Runnable {
        callOverlay.isVisible = false
    }

    private fun ensureTts() {
        if (tts != null) return
        ttsReady = false
        lastTtsStatus = "iniciando"
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false
                lastTtsStatus = "motor TTS indisponivel"
                lastError = "Narrador TTS indisponivel neste dispositivo."
                sendDeviceLog("TTS_INIT_ERR", "status_$status")
                return@TextToSpeech
            }
            try {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val result = tts?.setLanguage(Locale("pt", "BR")) ?: TextToSpeech.ERROR
                tts?.setSpeechRate(0.92f)
                tts?.setPitch(1.0f)
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                lastTtsStatus = if (ttsReady) "pronto" else "idioma pt-BR indisponivel"
                sendDeviceLog("TTS_READY", lastTtsStatus)
            } catch (t: Throwable) {
                ttsReady = true
                lastTtsStatus = "pronto com configuracao padrao"
                sendDeviceLog("TTS_CFG_WARN", t.message ?: "config")
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    lastTtsStatus = "falando"
                }

                override fun onDone(utteranceId: String) {
                    lastTtsStatus = "pronto"
                }

                override fun onError(utteranceId: String) {
                    lastTtsStatus = "erro ao falar"
                    sendDeviceLog("TTS_PLAY_ERR", utteranceId)
                }
            })
            val queued = pendingTtsText
            pendingTtsText = ""
            if (queued.isNotBlank()) mainHandler.post { speak(queued) }
        }
    }

    private fun speak(text: String) {
        if (!audioEnabled) return
        if (playServerSpeech(text)) return
        ensureTts()
        val engine = tts ?: return
        if (!ttsReady) {
            pendingTtsText = text
            sendDeviceLog("TTS_QUEUED", text)
            return
        }
        try {
            val id = "utt_" + SystemClock.elapsedRealtime()
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.SUCCESS) {
                lastTtsStatus = "enviado para fala"
                sendDeviceLog("TTS_OK", text)
            } else {
                lastTtsStatus = "falha ao enviar fala"
                lastError = "Narrador nao conseguiu iniciar a fala."
                sendDeviceLog("TTS_SPEAK_FAIL", "result_$result")
            }
        } catch (t: Throwable) {
            lastTtsStatus = "erro ao falar"
            lastError = "Erro no narrador TTS."
            sendDeviceLog("TTS_ERR", t.message ?: "erro")
        }
    }

    private fun playServerSpeech(text: String): Boolean {
        val base = getServerBaseUrl().trimEnd('/')
        if (base.isBlank()) return false
        val token = prefs.getString(PREF_ACTIVATION_TOKEN, null)?.trim().orEmpty()
        if (token.isBlank()) return false
        return try {
            try {
                speechPlayer?.stop()
            } catch (_: Throwable) {
            }
            try {
                speechPlayer?.release()
            } catch (_: Throwable) {
            }
            speechPlayer = null
            lastTtsStatus = "gerando audio no gerenciador"
            val body = JSONObject().apply {
                put("text", text)
                put("rate", 1.0)
                put("pitch", 1.0)
                if (currentTtsVoice.isNotBlank()) {
                    put("voice", currentTtsVoice)
                }
            }.toString()
            val request = Request.Builder()
                .url("$base/api/tts/synthesize")
                .header("x-device-token", token)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    mainHandler.post {
                        lastTtsStatus = "falha ao gerar audio no gerenciador"
                        sendDeviceLog("SERVER_TTS_SYNTH_ERR", e.message ?: "network")
                        speakWithAndroidTtsFallback(text)
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    val raw = try {
                        response.body?.string().orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    mainHandler.post {
                        if (!response.isSuccessful) {
                            lastTtsStatus = "gerenciador recusou audio"
                            sendDeviceLog("SERVER_TTS_SYNTH_HTTP", "${response.code}:$raw")
                            speakWithAndroidTtsFallback(text)
                            return@post
                        }
                        try {
                            val audioUrl = JSONObject(raw).optString("audioUrl", "")
                            if (audioUrl.isBlank()) throw IllegalStateException("audioUrl vazio")
                            val fullUrl = if (audioUrl.startsWith("http://") || audioUrl.startsWith("https://")) {
                                audioUrl
                            } else {
                                base + audioUrl
                            }
                            playServerAudioUrl(fullUrl, text)
                        } catch (t: Throwable) {
                            lastTtsStatus = "resposta de audio invalida"
                            sendDeviceLog("SERVER_TTS_SYNTH_PARSE", t.message ?: "parse")
                            speakWithAndroidTtsFallback(text)
                        }
                    }
                }
            })
            true
        } catch (t: Throwable) {
            lastTtsStatus = "erro ao abrir audio do gerenciador"
            sendDeviceLog("SERVER_TTS_OPEN_ERR", t.message ?: "erro")
            false
        }
    }

    private fun playServerAudioUrl(url: String, originalText: String) {
        try {
            val player = MediaPlayer()
            speechPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setOnPreparedListener {
                lastTtsStatus = "tocando audio do gerenciador"
                it.start()
                sendDeviceLog("SERVER_TTS_PLAY", originalText)
            }
            player.setOnCompletionListener {
                lastTtsStatus = "audio finalizado"
                try {
                    it.release()
                } catch (_: Throwable) {
                }
                if (speechPlayer === it) speechPlayer = null
            }
            player.setOnErrorListener { mp, what, extra ->
                lastTtsStatus = "falha no audio do gerenciador"
                lastError = "Nao foi possivel tocar audio TTS do gerenciador."
                sendDeviceLog("SERVER_TTS_ERR", "what=$what extra=$extra")
                try {
                    mp.release()
                } catch (_: Throwable) {
                }
                if (speechPlayer === mp) speechPlayer = null
                speakWithAndroidTtsFallback(originalText)
                true
            }
            player.setDataSource(url)
            player.prepareAsync()
        } catch (t: Throwable) {
            lastTtsStatus = "erro ao tocar audio do gerenciador"
            sendDeviceLog("SERVER_TTS_OPEN_ERR", t.message ?: "erro")
            speakWithAndroidTtsFallback(originalText)
        }
    }

    private fun speakWithAndroidTtsFallback(text: String) {
        ensureTts()
        val engine = tts ?: return
        if (!ttsReady) {
            pendingTtsText = text
            sendDeviceLog("TTS_QUEUED", text)
            return
        }
        try {
            val id = "utt_" + SystemClock.elapsedRealtime()
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result == TextToSpeech.SUCCESS) {
                lastTtsStatus = "enviado para fala local"
                sendDeviceLog("TTS_OK", text)
            } else {
                lastTtsStatus = "falha ao enviar fala local"
                lastError = "Narrador nao conseguiu iniciar a fala."
                sendDeviceLog("TTS_SPEAK_FAIL", "result_$result")
            }
        } catch (t: Throwable) {
            lastTtsStatus = "erro ao falar local"
            lastError = "Erro no narrador TTS."
            sendDeviceLog("TTS_ERR", t.message ?: "erro")
        }
    }

    private fun sendDeviceLog(tag: String, msg: String) {
        val socket = ws ?: return
        val payload = JSONObject().apply {
            put("type", "device_log")
            put("tag", tag)
            put("msg", msg)
        }.toString()
        try {
            socket.send(payload)
        } catch (_: Throwable) {
        }
    }

    private fun showStatus(message: String) {
        overlayTitle.text = getString(R.string.status_titulo)
        overlayQr.setImageDrawable(null)
        overlayQr.isVisible = false
        overlayText.text = message
        overlayButtonPrimary.isVisible = false
        overlayButtonSecondary.isVisible = false
        overlayRoot.isVisible = true
        overlayRoot.bringToFront()
    }

    private fun showConnecting() {
        overlayTitle.text = getString(R.string.status_titulo)
        overlayQr.setImageDrawable(null)
        overlayQr.isVisible = false
        overlayText.text = getString(R.string.status_conectando)
        overlayButtonPrimary.isVisible = false
        overlayButtonSecondary.isVisible = false
        overlayRoot.isVisible = true
        overlayRoot.bringToFront()
    }

    private fun showServerMissing() {
        pairingInProgress = false
        overlayTitle.text = getString(R.string.pair_titulo)
        overlayQr.setImageDrawable(null)
        overlayQr.isVisible = false
        overlayText.text = getString(R.string.status_configurar_servidor)
        overlayButtonPrimary.text = getString(R.string.pair_configurar)
        overlayButtonPrimary.isVisible = true
        overlayButtonPrimary.setOnClickListener { showSettingsDialog() }
        overlayButtonSecondary.text = getString(R.string.desvincular)
        overlayButtonSecondary.isVisible = true
        overlayButtonSecondary.setOnClickListener {
            clearPairing()
            showPairingIdle()
        }
        overlayRoot.isVisible = true
        overlayRoot.bringToFront()
    }

    private fun computeBackoffMs(errors: Int): Long {
        val clamped = errors.coerceIn(1, 6)
        val base = 2_000L
        return base shl (clamped - 1)
    }

    private fun scheduleReload(delayMs: Long) {
        if (!networkAvailable) return
        if (reloadScheduled) return
        if (nativeModeActive) {
            heartbeatReloads += 1
            pollNativeSigssPanel()
            return
        }
        reloadScheduled = true
        mainHandler.postDelayed(
            {
                reloadScheduled = false
                try {
                    webView.stopLoading()
                } catch (_: Throwable) {
                }
                heartbeatReloads += 1
                webView.reload()
            },
            delayMs.coerceAtMost(120_000L)
        )
    }

    private fun createNativePanelView(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = createGradientCardDrawable(
            startColor = 0xFFFFFCF8.toInt(),
            endColor = 0xFFF8F2EA.toInt(),
            strokeColor = 0x00000000,
            strokeWidth = 0,
            radiusDp = 0
        )
        root.setPadding(dp(20), dp(20), dp(20), dp(20))
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // HEADER MODERNO - Design limpo com relogio integrado
        val header = LinearLayout(this)
        nativeHeaderCard = header
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(24), dp(16), dp(24), dp(16))
        header.background = createGradientCardDrawable(
            startColor = 0xFF007A33.toInt(),
            endColor = 0xFF006428.toInt(),
            strokeColor = 0xFF005522.toInt(),
            strokeWidth = 0,
            radiusDp = 20
        )
        header.elevation = dp(8).toFloat()
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Logo da unidade (lado esquerdo)
        nativeUnitLogoView = ImageView(this)
        nativeUnitLogoView.scaleType = ImageView.ScaleType.CENTER_CROP
        nativeUnitLogoView.background = createGradientCardDrawable(
            startColor = 0xFFFFFFFF.toInt(),
            endColor = 0xFFF5FBF8.toInt(),
            strokeColor = 0x00000000,
            strokeWidth = 0,
            radiusDp = 14
        )
        nativeUnitLogoView.setPadding(dp(8), dp(8), dp(8), dp(8))
        nativeUnitLogoView.isVisible = false
        header.addView(nativeUnitLogoView, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            marginEnd = dp(18)
        })

        // Bloco central com nome da unidade
        val titleBlock = LinearLayout(this)
        titleBlock.orientation = LinearLayout.VERTICAL
        titleBlock.gravity = Gravity.CENTER_VERTICAL
        header.addView(titleBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        nativeUnitNameText = TextView(this)
        nativeUnitNameText.text = "PAINEL DE CHAMADOS"
        nativeUnitNameText.setTextColor(0xFFFFFFFF.toInt())
        nativeUnitNameText.textSize = sp(26f)
        nativeUnitNameText.typeface = Typeface.DEFAULT_BOLD
        nativeUnitNameText.letterSpacing = 0.02f
        nativeUnitNameText.maxLines = 1
        titleBlock.addView(nativeUnitNameText)

        nativeUnitSubtitleText = TextView(this)
        nativeUnitSubtitleText.text = "Sistema Unico de Saude"
        nativeUnitSubtitleText.setTextColor(0xCCFFFFFF.toInt())
        nativeUnitSubtitleText.textSize = sp(14f)
        nativeUnitSubtitleText.letterSpacing = 0.04f
        nativeUnitSubtitleText.isVisible = true
        titleBlock.addView(nativeUnitSubtitleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
        })

        // Texto de modo (oculto por padrao)
        nativeModeText = TextView(this)
        nativeModeText.text = ""
        nativeModeText.setTextColor(0x00000000.toInt())
        nativeModeText.textSize = 0f
        nativeModeText.isVisible = false
        titleBlock.addView(nativeModeText)

        // Status text (oculto, usado internamente)
        nativeStatusText = TextView(this)
        nativeStatusText.text = ""
        nativeStatusText.textSize = 0f
        nativeStatusText.isVisible = false
        titleBlock.addView(nativeStatusText)

        // Bloco do relogio (lado direito)
        val clockBlock = LinearLayout(this)
        clockBlock.orientation = LinearLayout.VERTICAL
        clockBlock.gravity = Gravity.CENTER or Gravity.END
        clockBlock.setPadding(dp(20), dp(8), dp(0), dp(8))
        header.addView(clockBlock, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        nativeClockText = TextView(this)
        nativeClockText.text = "00:00"
        nativeClockText.setTextColor(0xFFFFFFFF.toInt())
        nativeClockText.textSize = sp(42f)
        nativeClockText.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        nativeClockText.gravity = Gravity.END
        nativeClockText.letterSpacing = -0.02f
        clockBlock.addView(nativeClockText)

        nativeDateText = TextView(this)
        nativeDateText.text = "Segunda-feira, 01 de Janeiro"
        nativeDateText.setTextColor(0xCCFFFFFF.toInt())
        nativeDateText.textSize = sp(14f)
        nativeDateText.gravity = Gravity.END
        nativeDateText.letterSpacing = 0.02f
        clockBlock.addView(nativeDateText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(-4)
        })

        // Iniciar atualizacao do relogio
        startClockUpdates()
        setNativeStatus("Aguardando dados", 0xFF205A7A.toInt(), 0xFFE6F4FA.toInt())

        nativeBodyRow = LinearLayout(this)
        nativeBodyRow.orientation = LinearLayout.HORIZONTAL
        nativeBodyRow.setPadding(0, dp(20), 0, 0)
        root.addView(nativeBodyRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        // CARD DE CHAMADA ATUAL - Design moderno com destaque visual
        nativeCurrentCard = LinearLayout(this)
        nativeCurrentCard.orientation = LinearLayout.VERTICAL
        nativeCurrentCard.setPadding(dp(28), dp(24), dp(28), dp(24))
        nativeCurrentCard.background = createGradientCardDrawable(
            startColor = 0xFFFFFFFF.toInt(),
            endColor = 0xFFF8FCFA.toInt(),
            strokeColor = 0xFF007A33.toInt(),
            strokeWidth = dp(3),
            radiusDp = 24
        )
        nativeCurrentCard.elevation = dp(12).toFloat()
        nativeBodyRow.addView(nativeCurrentCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.3f).apply {
            marginEnd = dp(16)
        })

        // Header do card com badge de prioridade centralizado
        val currentHeader = LinearLayout(this)
        currentHeader.orientation = LinearLayout.HORIZONTAL
        currentHeader.gravity = Gravity.CENTER_VERTICAL
        nativeCurrentCard.addView(currentHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Badge "CHAMANDO" centralizado
        nativeCurrentLabelText = TextView(this)
        nativeCurrentLabelText.text = "CHAMANDO"
        nativeCurrentLabelText.setTextColor(0xFFFFFFFF.toInt())
        nativeCurrentLabelText.textSize = sp(16f)
        nativeCurrentLabelText.typeface = Typeface.DEFAULT_BOLD
        nativeCurrentLabelText.letterSpacing = 0.12f
        nativeCurrentLabelText.setPadding(dp(24), dp(12), dp(24), dp(12))
        nativeCurrentLabelText.background = createPillDrawable(0xFF007A33.toInt(), 0xFF005522.toInt())
        currentHeader.addView(nativeCurrentLabelText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Hint text (oculto por padrao)
        val currentHeaderHint = TextView(this)
        nativeCurrentHintText = currentHeaderHint
        currentHeaderHint.text = ""
        currentHeaderHint.setTextColor(0xFF6B6761.toInt())
        currentHeaderHint.textSize = 0f
        currentHeaderHint.isVisible = false
        currentHeader.addView(currentHeaderHint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Badge de prioridade no topo direito
        nativePriorityText = TextView(this)
        nativePriorityText.text = ""
        nativePriorityText.setTextColor(0xFFFFFFFF.toInt())
        nativePriorityText.textSize = sp(14f)
        nativePriorityText.typeface = Typeface.DEFAULT_BOLD
        nativePriorityText.gravity = Gravity.CENTER
        nativePriorityText.letterSpacing = 0.08f
        nativePriorityText.setPadding(dp(16), dp(10), dp(16), dp(10))
        currentHeader.addView(nativePriorityText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Nome do paciente - GRANDE e centralizado
        nativePatientText = TextView(this)
        nativePatientText.text = "--"
        nativePatientText.setTextColor(0xFF1A1A1A.toInt())
        nativePatientText.textSize = sp(64f)
        nativePatientText.typeface = Typeface.DEFAULT_BOLD
        nativePatientText.gravity = Gravity.CENTER
        nativePatientText.maxLines = 2
        nativePatientText.setLineSpacing(0f, 0.92f)
        nativeCurrentCard.addView(nativePatientText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply {
            topMargin = dp(20)
            bottomMargin = dp(16)
        })

        // Barra de informacoes - Sala e Profissional lado a lado
        val infoBar = LinearLayout(this)
        infoBar.orientation = LinearLayout.HORIZONTAL
        infoBar.gravity = Gravity.CENTER
        infoBar.setPadding(dp(20), dp(18), dp(20), dp(18))
        infoBar.background = createGradientCardDrawable(
            startColor = 0xFFF5FBF8.toInt(),
            endColor = 0xFFEDF7F2.toInt(),
            strokeColor = 0xFFD0E8DC.toInt(),
            strokeWidth = dp(1),
            radiusDp = 16
        )
        nativeCurrentCard.addView(infoBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Bloco da Sala
        val roomBlock = LinearLayout(this)
        roomBlock.orientation = LinearLayout.VERTICAL
        roomBlock.gravity = Gravity.CENTER
        infoBar.addView(roomBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val roomLabel = TextView(this)
        roomLabel.text = "SALA"
        roomLabel.setTextColor(0xFF5A8A6A.toInt())
        roomLabel.textSize = sp(12f)
        roomLabel.typeface = Typeface.DEFAULT_BOLD
        roomLabel.letterSpacing = 0.1f
        roomLabel.gravity = Gravity.CENTER
        roomBlock.addView(roomLabel)

        nativeRoomText = TextView(this)
        nativeRoomText.text = "--"
        nativeRoomText.setTextColor(0xFF1A5A3A.toInt())
        nativeRoomText.textSize = sp(32f)
        nativeRoomText.gravity = Gravity.CENTER
        nativeRoomText.typeface = Typeface.DEFAULT_BOLD
        roomBlock.addView(nativeRoomText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        })

        // Divisor vertical
        val divider = View(this)
        divider.setBackgroundColor(0xFFD0E8DC.toInt())
        infoBar.addView(divider, LinearLayout.LayoutParams(dp(1), dp(50)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        })

        // Bloco do Profissional
        val profBlock = LinearLayout(this)
        profBlock.orientation = LinearLayout.VERTICAL
        profBlock.gravity = Gravity.CENTER
        infoBar.addView(profBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))

        val profLabel = TextView(this)
        profLabel.text = "PROFISSIONAL"
        profLabel.setTextColor(0xFF5A8A6A.toInt())
        profLabel.textSize = sp(12f)
        profLabel.typeface = Typeface.DEFAULT_BOLD
        profLabel.letterSpacing = 0.1f
        profLabel.gravity = Gravity.CENTER
        profBlock.addView(profLabel)

        nativeProfessionalText = TextView(this)
        nativeProfessionalText.text = "--"
        nativeProfessionalText.setTextColor(0xFF1A5A3A.toInt())
        nativeProfessionalText.textSize = sp(20f)
        nativeProfessionalText.gravity = Gravity.CENTER
        nativeProfessionalText.typeface = Typeface.DEFAULT_BOLD
        nativeProfessionalText.maxLines = 2
        profBlock.addView(nativeProfessionalText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        })

        // Texto de atualizacao (menor, no rodape do card)
        nativeUpdatedText = TextView(this)
        nativeUpdatedText.text = ""
        nativeUpdatedText.setTextColor(0xFF7A9A8A.toInt())
        nativeUpdatedText.textSize = sp(13f)
        nativeUpdatedText.gravity = Gravity.CENTER
        nativeCurrentCard.addView(nativeUpdatedText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        })

        // COLUNA LATERAL - Historico e Video
        nativeSideColumn = LinearLayout(this)
        nativeSideColumn.orientation = LinearLayout.VERTICAL
        nativeBodyRow.addView(nativeSideColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f))

        // CARD DE HISTORICO - Design limpo
        nativeHistoryCard = LinearLayout(this)
        nativeHistoryCard.orientation = LinearLayout.VERTICAL
        nativeHistoryCard.setPadding(dp(20), dp(18), dp(20), dp(18))
        nativeHistoryCard.background = createGradientCardDrawable(
            startColor = 0xFFFFFFFF.toInt(),
            endColor = 0xFFF8FCF9.toInt(),
            strokeColor = 0xFFD0E8DC.toInt(),
            strokeWidth = dp(1),
            radiusDp = 20
        )
        nativeHistoryCard.elevation = dp(6).toFloat()
        nativeSideColumn.addView(nativeHistoryCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            0.45f
        ))

        // Header do historico
        val historyHeader = LinearLayout(this)
        historyHeader.orientation = LinearLayout.HORIZONTAL
        historyHeader.gravity = Gravity.CENTER_VERTICAL
        nativeHistoryCard.addView(historyHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val lastLabel = TextView(this)
        nativeHistoryTitleText = lastLabel
        lastLabel.text = "ULTIMAS CHAMADAS"
        lastLabel.setTextColor(0xFF1A5A3A.toInt())
        lastLabel.textSize = sp(18f)
        lastLabel.typeface = Typeface.DEFAULT_BOLD
        lastLabel.letterSpacing = 0.05f
        historyHeader.addView(lastLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Hint text (oculto)
        nativeHistoryHintText = TextView(this)
        nativeHistoryHintText.text = ""
        nativeHistoryHintText.textSize = 0f
        nativeHistoryHintText.isVisible = false
        nativeHistoryCard.addView(nativeHistoryHintText)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        nativeList = LinearLayout(this)
        nativeList.orientation = LinearLayout.VERTICAL
        scroll.addView(nativeList)
        nativeHistoryCard.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // CARD DE MIDIA - Fullbleed sem bordas, video grande
        nativeMediaCard = LinearLayout(this)
        nativeMediaCard.orientation = LinearLayout.VERTICAL
        nativeMediaCard.setPadding(0, 0, 0, 0) // Sem padding para fullbleed
        nativeMediaCard.setBackgroundColor(0xFF000000.toInt()) // Fundo preto para video
        nativeMediaCard.elevation = dp(4).toFloat()
        nativeMediaCard.isVisible = false
        nativeSideColumn.addView(nativeMediaCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            0.55f // Video ocupa mais espaco
        ).apply {
            topMargin = dp(12)
        })

        // Titulo e hint ocultos (video fullbleed nao precisa)
        val mediaLabel = TextView(this)
        nativeMediaTitleText = mediaLabel
        mediaLabel.text = ""
        mediaLabel.textSize = 0f
        mediaLabel.isVisible = false
        nativeMediaCard.addView(mediaLabel)

        nativeMediaHintText = TextView(this)
        nativeMediaHintText.text = ""
        nativeMediaHintText.textSize = 0f
        nativeMediaHintText.isVisible = false
        nativeMediaCard.addView(nativeMediaHintText)

        showEmptyHistoryState()
        return root
    }

    private fun updateNativeLayoutForMode() {
        val visualStyle = currentThemeStyle()
        val alwaysMedia = currentDisplayMode == "panel_with_media" && currentMediaPlaybackUrl.isNotBlank()
        val idleMedia = currentDisplayMode == "panel_idle_media" &&
            currentMediaPlaybackUrl.isNotBlank() &&
            (nativeLastCallDetectedAt <= 0L || SystemClock.elapsedRealtime() - nativeLastCallDetectedAt >= 20_000L)
        val withMedia = alwaysMedia || idleMedia
        val idleSplitMode = idleMedia && currentDisplayMode == "panel_idle_media"

        nativeMediaCard.isVisible = withMedia
        nativeCurrentCard.isVisible = !idleSplitMode
        nativeSideColumn.orientation = if (idleSplitMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        refreshUnitBranding()
        applyNativeTheme()
        nativeModeText.text = when {
            idleSplitMode -> "Modo de espera visual ativo com midia em destaque e fila recente acessivel."
            alwaysMedia -> "Experiencia de chamada com conteudo lateral ativo para orientar a sala de espera."
            currentDisplayMode == "panel_idle_media" -> "Painel principal ativo com alternancia automatica para midia quando a fila estiver em pausa."
            else -> "Fluxo de paciente inteligente focado na prioridade e clareza visual"
        }
        nativeCurrentLabelText.text = if (idleSplitMode) "MODO ESPERA" else "CHAMADA ATIVA"
        nativeHistoryHintText.text = if (idleSplitMode) {
            "Historico preservado ao lado da midia durante a pausa operacional."
        } else {
            "Historico recente com leitura rapida para equipe, recepcao e pacientes em espera."
        }
        nativeMediaHintText.text = if (idleSplitMode) {
            "A midia assume protagonismo enquanto nao houver nova chamada ativa."
        } else {
            "Conteudo lateral para orientacao, video institucional ou mensagens de apoio ao atendimento."
        }

        (nativeCurrentCard.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.height = LinearLayout.LayoutParams.MATCH_PARENT
            params.weight = if (idleSplitMode) 0f else if (withMedia) visualStyle.currentWithMediaWeight else visualStyle.currentNoMediaWeight
            params.marginEnd = if (idleSplitMode) 0 else dp(18)
            nativeCurrentCard.layoutParams = params
        }
        (nativeSideColumn.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.width = 0
            params.height = LinearLayout.LayoutParams.MATCH_PARENT
            params.weight = if (idleSplitMode) visualStyle.idleHistoryWeight + visualStyle.idleMediaWeight else if (withMedia) visualStyle.sideWithMediaWeight else visualStyle.sideNoMediaWeight
            nativeSideColumn.layoutParams = params
        }
        (nativeHistoryCard.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (idleSplitMode) {
                params.width = 0
                params.height = LinearLayout.LayoutParams.MATCH_PARENT
                params.weight = visualStyle.idleHistoryWeight
                params.topMargin = 0
                params.marginEnd = dp(18)
            } else {
                params.width = LinearLayout.LayoutParams.MATCH_PARENT
                params.height = 0
                params.weight = if (withMedia) visualStyle.historyWithMediaWeight else 1f
                params.topMargin = 0
                params.marginEnd = 0
            }
            nativeHistoryCard.layoutParams = params
        }
        (nativeMediaCard.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (idleSplitMode) {
                params.width = 0
                params.height = LinearLayout.LayoutParams.MATCH_PARENT
                params.weight = visualStyle.idleMediaWeight
                params.topMargin = 0
                params.marginEnd = 0
            } else {
                params.width = LinearLayout.LayoutParams.MATCH_PARENT
                params.height = 0
                params.weight = if (withMedia) visualStyle.mediaWithMediaWeight else 0f
                params.topMargin = if (withMedia) dp(visualStyle.mediaTopMarginDp) else 0
                params.marginEnd = 0
            }
            nativeMediaCard.layoutParams = params
        }

        if (withMedia) {
            val mediaView = ensureNativeMediaWebView()
            val targetUrl = resolveServerUrl(currentMediaPlaybackUrl)
            if (targetUrl.isNotBlank() && mediaView.url != targetUrl) {
                mediaView.loadUrl(targetUrl)
            }
        } else {
            destroyNativeMediaWebView()
        }
    }

    private fun ensureNativeMediaWebView(): WebView {
        nativeMediaWebView?.let { return it }
        val created = WebView(this)
        configureWebViewBase(created)
        created.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }
        // Fullbleed - preenche todo o card sem margens
        nativeMediaCard.addView(created, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        nativeMediaWebView = created
        return created
    }

    private fun destroyNativeMediaWebView() {
        val current = nativeMediaWebView ?: return
        try {
            current.stopLoading()
        } catch (_: Throwable) {
        }
        try {
            current.loadUrl("about:blank")
        } catch (_: Throwable) {
        }
        try {
            nativeMediaCard.removeView(current)
        } catch (_: Throwable) {
        }
        try {
            current.destroy()
        } catch (_: Throwable) {
        }
        nativeMediaWebView = null
    }

    private fun applyContentUrl(url: String) {
        currentContentUrl = url.ifBlank { "about:blank" }
        val sigss = parseSigssPanelUrl(currentContentUrl)
        if (sigss != null) {
            startNativeSigssPanel(sigss.first, sigss.second)
        } else {
            startWebViewMode(currentContentUrl)
        }
    }

    private fun parseSigssPanelUrl(url: String): Pair<String, String>? {
        return try {
            val uri = android.net.Uri.parse(url)
            val segments = uri.pathSegments ?: return null
            val idx = segments.indexOf("panel-screen")
            if (idx < 0 || idx + 1 >= segments.size) return null
            if (!segments.contains("unique-panel")) return null
            val scheme = uri.scheme ?: "https"
            val authority = uri.authority ?: return null
            val panelId = segments[idx + 1].trim()
            if (panelId.isBlank()) return null
            Pair("$scheme://$authority", panelId)
        } catch (_: Throwable) {
            null
        }
    }

    private fun startNativeSigssPanel(baseUrl: String, panelId: String) {
        nativeModeActive = true
        nativePanelBaseUrl = baseUrl.trimEnd('/')
        nativePanelId = panelId
        nativePollFailures = 0
        nativePollInitialized = false
        nativeLastEventKey = ""
        nativeLastCallDetectedAt = 0L
        callOverlay.isVisible = false
        webView.stopLoading()
        webView.isVisible = false
        nativePanelRoot.isVisible = true
        updateNativeLayoutForMode()
        nativePanelRoot.bringToFront()
        overlayRoot.bringToFront()
        lastPageOkAt = SystemClock.elapsedRealtime()
        setNativeStatus("Conectando ao painel...", 0xFF205A7A.toInt(), 0xFFE6F4FA.toInt())
        sendDeviceLog("NATIVE_SIGSS", "start $panelId")
        mainHandler.removeCallbacks(nativeSigssPollRunnable)
        mainHandler.post(nativeSigssPollRunnable)
    }

    private fun isSameNativeSigssPanel(url: String): Boolean {
        val parsed = parseSigssPanelUrl(url) ?: return false
        return nativeModeActive &&
            nativePanelBaseUrl.equals(parsed.first.trimEnd('/'), ignoreCase = true) &&
            nativePanelId.equals(parsed.second, ignoreCase = true)
    }

    private fun startWebViewMode(url: String) {
        if (nativeModeActive) {
            mainHandler.removeCallbacks(nativeSigssPollRunnable)
        }
        nativeModeActive = false
        currentDisplayMode = "panel_only"
        currentMediaPlaybackUrl = ""
        currentMediaUrl = ""
        updateNativeLayoutForMode()
        nativePanelRoot.isVisible = false
        webView.isVisible = true
        webView.loadUrl(url)
    }

    private fun pollNativeSigssPanel() {
        if (!nativeModeActive || nativePolling || nativePanelBaseUrl.isBlank() || nativePanelId.isBlank()) return
        nativePolling = true
        val url = nativePanelBaseUrl +
            "/unique-panel/api/call/history?panelId=$nativePanelId" +
            "&limit=8&sort=updatedAt:desc" +
            "&select=id,professional,personal,password,priority,priorityColor,local,updatedAt,attempts"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "PainelTVNovo/${BuildConfig.VERSION_NAME} NativeSIGSS")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                mainHandler.post {
                    nativePolling = false
                    nativePollFailures += 1
                    lastError = "Falha ao consultar chamados. O app vai tentar de novo."
                    setNativeStatus(
                        "Reconectando... falha ${nativePollFailures}",
                        0xFF9A6700.toInt(),
                        0xFFFFF3D6.toInt()
                    )
                    sendDeviceLog("NATIVE_SIGSS_ERR", e.message ?: "network")
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                val text = try {
                    response.body?.string().orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                mainHandler.post {
                    nativePolling = false
                    if (!response.isSuccessful) {
                        nativePollFailures += 1
                        lastError = "Painel respondeu erro ${response.code}."
                        setNativeStatus(
                            "Painel respondeu erro ${response.code}",
                            0xFFA84444.toInt(),
                            0xFFFDE7EA.toInt()
                        )
                        sendDeviceLog("NATIVE_SIGSS_HTTP", response.code.toString())
                        return@post
                    }
                    val calls = parseSigssHistory(text)
                    renderNativeSigssCalls(calls)
                }
            }
        })
    }

    private fun parseSigssHistory(raw: String): List<SigssCall> {
        return try {
            val trimmed = raw.trim()
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("data")
                        ?: root.optJSONArray("items")
                        ?: root.optJSONArray("results")
                        ?: JSONArray().also { if (root.has("id")) it.put(root) }
                }
                else -> JSONArray()
            }
            val out = mutableListOf<SigssCall>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out += SigssCall(
                    id = jsonText(obj.opt("id")),
                    patient = jsonText(obj.opt("personal")).ifBlank { jsonText(obj.opt("patient")) },
                    room = jsonText(obj.opt("local")).ifBlank { jsonText(obj.opt("room")) },
                    professional = jsonText(obj.opt("professional")),
                    priority = jsonText(obj.opt("priority")),
                    priorityColor = jsonText(obj.opt("priorityColor")),
                    updatedAt = jsonText(obj.opt("updatedAt")),
                    attempts = obj.optInt("attempts", 0)
                )
            }
            out
        } catch (t: Throwable) {
            lastError = "Resposta do painel veio em formato inesperado."
            sendDeviceLog("NATIVE_SIGSS_PARSE", t.message ?: "parse")
            emptyList()
        }
    }

    private fun jsonText(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value.trim()
            is JSONObject -> {
                listOf("name", "nome", "description", "descricao", "label", "value")
                    .firstNotNullOfOrNull { k -> value.optString(k, "").trim().takeIf { it.isNotBlank() } }
                    ?: value.toString()
            }
            else -> value.toString().trim()
        }
    }

    private fun maxHistoryItems(): Int {
        return when (currentDisplayMode) {
            "panel_with_media" -> 3
            "panel_idle_media" -> if (currentMediaPlaybackUrl.isNotBlank()) 3 else 4
            else -> 4
        }
    }

    private fun renderNativeSigssCalls(calls: List<SigssCall>) {
        val now = SystemClock.elapsedRealtime()
        nativeLastSuccessAt = now
        lastPageOkAt = now
        lastPanelNetworkActivityAt = now
        panelNetworkEvents += 1
        nativePollFailures = 0
        lastError = ""

        if (calls.isEmpty()) {
            nativeLastRenderedCalls = emptyList()
            updateNativeLayoutForMode()
            setNativeStatus("Sem chamados recentes", 0xFF2D6E4A.toInt(), 0xFFE7F6EE.toInt())
            nativePatientText.text = themedDisplayText("--", currentThemeStyle().patientUppercase)
            nativeRoomText.text = "Sala: --"
            nativeProfessionalText.text = "Profissional: --"
            nativePriorityText.text = ""
            applyPriorityStyle(nativeCurrentCard, nativePriorityText, resolvePriorityStyle(null))
            nativeUpdatedText.text = ""
            showEmptyHistoryState()
            return
        }

        val current = calls.first()
        nativeLastRenderedCalls = calls
        val eventKey = "${current.id}|${current.attempts}|${current.updatedAt}"
        val isNewCall = !nativePollInitialized || eventKey != nativeLastEventKey
        if (!nativePollInitialized || eventKey != nativeLastEventKey) {
            nativeLastCallDetectedAt = now
        }
        updateNativeLayoutForMode()
        val priorityStyle = resolvePriorityStyle(current)
        setNativeStatus("Online - atualizado agora", 0xFF1F6A57.toInt(), 0xFFE5F5EE.toInt())
        nativePatientText.text = themedDisplayText(current.patient.ifBlank { "--" }, currentThemeStyle().patientUppercase)
        nativeRoomText.text = "Sala: ${current.room.ifBlank { "--" }}"
        nativeProfessionalText.text = "Profissional: ${current.professional.ifBlank { "--" }}"
        nativePriorityText.text = priorityStyle.badge
        applyPriorityStyle(nativeCurrentCard, nativePriorityText, priorityStyle)
        nativeUpdatedText.text = "Ultima atualizacao: ${formatSigssTime(current.updatedAt)}"
        animateCurrentCallCard(priorityStyle, isNewCall)

        nativeList.removeAllViews()
        calls.take(maxHistoryItems()).forEachIndexed { index, call ->
            val row = createNativeCallRow(call)
            nativeList.addView(row)
            // Cascade animation: each row appears 50ms after the previous
            if (isNewCall) {
                animateHistoryRowEntry(row, (index * 50 + 400).toLong())
            }
        }

        val phrase = buildAnnouncement(current.patient, current.room, current.professional)
        if (!nativePollInitialized) {
            nativePollInitialized = true
            nativeLastEventKey = eventKey
            lastAnnouncement = phrase
            lastAnnouncementAt = now
            speak(phrase)
            sendDeviceLog("NATIVE_SIGSS_CALL_FIRST", phrase)
            return
        }
        if (eventKey != nativeLastEventKey) {
            nativeLastEventKey = eventKey
            lastAnnouncement = phrase
            lastAnnouncementAt = now
            speak(phrase)
            sendDeviceLog("NATIVE_SIGSS_CALL", phrase)
        }
    }

    private fun createNativeCallRow(call: SigssCall): LinearLayout {
        val priorityStyle = resolvePriorityStyle(call)
        val theme = currentTheme()
        val visualStyle = currentThemeStyle()
        
        // Row principal - layout horizontal moderno
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.background = createGradientCardDrawable(
            startColor = 0xFFFFFFFF.toInt(),
            endColor = 0xFFFAFDFB.toInt(),
            strokeColor = 0xFFE0F0E8.toInt(),
            strokeWidth = dp(1),
            radiusDp = 12
        )
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        }

        // Indicador de prioridade colorido (barra vertical)
        val indicator = View(this)
        indicator.setBackgroundColor(priorityStyle.accentColor)
        row.addView(indicator, LinearLayout.LayoutParams(dp(4), dp(44)).apply {
            marginEnd = dp(12)
        })

        // Bloco de informacoes
        val infoBlock = LinearLayout(this)
        infoBlock.orientation = LinearLayout.VERTICAL
        row.addView(infoBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Nome do paciente
        val patient = TextView(this)
        patient.text = themedDisplayText(call.patient.ifBlank { "--" }, visualStyle.historyPatientUppercase)
        patient.setTextColor(theme.titleColor)
        patient.textSize = sp(16f)
        patient.typeface = Typeface.DEFAULT_BOLD
        patient.maxLines = 1
        patient.ellipsize = TextUtils.TruncateAt.END
        infoBlock.addView(patient)

        // Detalhes (sala e profissional)
        val detail = TextView(this)
        detail.text = buildString {
            append(call.room.ifBlank { "--" })
            if (call.professional.isNotBlank()) append(" - ").append(call.professional)
        }
        detail.setTextColor(theme.mutedColor)
        detail.textSize = sp(13f)
        detail.maxLines = 1
        detail.ellipsize = TextUtils.TruncateAt.END
        infoBlock.addView(detail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
        })

        // Badge de prioridade (lado direito)
        if (priorityStyle.badge.isNotBlank() && !priorityStyle.badge.contains("PAINEL", ignoreCase = true)) {
            val badge = TextView(this)
            badge.text = priorityStyle.badge.take(3) // Abrevia: EME, URG, NOR
            badge.setTextColor(0xFFFFFFFF.toInt())
            badge.textSize = sp(10f)
            badge.typeface = Typeface.DEFAULT_BOLD
            badge.gravity = Gravity.CENTER
            badge.setPadding(dp(8), dp(4), dp(8), dp(4))
            badge.background = createPillDrawable(priorityStyle.accentColor, priorityStyle.accentColor)
            row.addView(badge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            })
        }

        // Hora (lado direito)
        if (call.updatedAt.isNotBlank()) {
            val time = TextView(this)
            time.text = formatSigssTime(call.updatedAt)
            time.setTextColor(theme.mutedColor)
            time.textSize = sp(12f)
            time.gravity = Gravity.END
            row.addView(time, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
            })
        }

        return row
    }

    private fun formatSigssTime(value: String): String {
        if (value.length < 16) return value
        val date = value.substring(0, 10)
        val time = value.substring(11, 16)
        return "$time - $date"
    }

    private fun applyPriorityStyle(card: LinearLayout, badgeView: TextView, style: PriorityStyle) {
        val visualStyle = currentThemeStyle()
        card.background = createGradientCardDrawable(
            startColor = style.cardBackground,
            endColor = style.cardEndBackground,
            strokeColor = style.strokeColor,
            strokeWidth = dp(visualStyle.currentStrokeWidthDp),
            radiusDp = visualStyle.currentRadiusDp
        )
        badgeView.setTextColor(style.accentColor)
        badgeView.background = createPillDrawable(style.badgeBackground, style.strokeColor)
        badgeView.isVisible = style.badge.isNotBlank()
    }

    private fun themedPriorityStyle(level: String): PriorityStyle {
        return when (currentThemeId) {
            "dark_modern" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFFF4757.toInt(), 0xFF2A1215.toInt(), 0xFF3D1A1E.toInt(), 0xFF1A1A1F.toInt(), 0xFF2A1518.toInt(), 0xFF4A1F24.toInt(), 0xFF5A252B.toInt(), 1.06f)
                "urgent" -> PriorityStyle("URGENTE", 0xFFFFA502.toInt(), 0xFF2A2210.toInt(), 0xFF3D3218.toInt(), 0xFF1A1A1F.toInt(), 0xFF2A2515.toInt(), 0xFF4A3F1F.toInt(), 0xFF5A4C25.toInt(), 1.05f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF2ED573.toInt(), 0xFF152A1A.toInt(), 0xFF1E3D24.toInt(), 0xFF1A1A1F.toInt(), 0xFF182A1C.toInt(), 0xFF254A2F.toInt(), 0xFF2E5A38.toInt(), 1.03f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF60A5FA.toInt(), 0xFF1A1A1F.toInt(), 0xFF1E3A5F.toInt(), 0xFF1A1A1F.toInt(), 0xFF182840.toInt(), 0xFF1E3A5F.toInt(), 0xFF2563EB.toInt(), 1.03f)
            }
            "high_contrast" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFFF0000.toInt(), 0xFF000000.toInt(), 0xFF330000.toInt(), 0xFF000000.toInt(), 0xFF1A0000.toInt(), 0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 1.08f)
                "urgent" -> PriorityStyle("URGENTE", 0xFFFFFF00.toInt(), 0xFF000000.toInt(), 0xFF333300.toInt(), 0xFF000000.toInt(), 0xFF1A1A00.toInt(), 0xFFFFFF00.toInt(), 0xFFFFFF00.toInt(), 1.06f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF00FF00.toInt(), 0xFF000000.toInt(), 0xFF003300.toInt(), 0xFF000000.toInt(), 0xFF001A00.toInt(), 0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 1.04f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFFFFD700.toInt(), 0xFF000000.toInt(), 0xFF1A1500.toInt(), 0xFF000000.toInt(), 0xFF0D0A00.toInt(), 0xFFFFD700.toInt(), 0xFFFFD700.toInt(), 1.04f)
            }
            // PRIORIDADES SUS VERDE - Verde institucional com cores de alerta
            "sus_verde" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFDC2626.toInt(), 0xFFFEF2F2.toInt(), 0xFFFEE2E2.toInt(), 0xFFFEF2F2.toInt(), 0xFFFECACA.toInt(), 0xFFFCA5A5.toInt(), 0xFFDC2626.toInt(), 1.06f)
                "urgent" -> PriorityStyle("URGENTE", 0xFFD97706.toInt(), 0xFFFFFBEB.toInt(), 0xFFFEF3C7.toInt(), 0xFFFFFBEB.toInt(), 0xFFFDE68A.toInt(), 0xFFFCD34D.toInt(), 0xFFD97706.toInt(), 1.05f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF007A33.toInt(), 0xFFF0FDF4.toInt(), 0xFFDCFCE7.toInt(), 0xFFF0FDF4.toInt(), 0xFFBBF7D0.toInt(), 0xFF86EFAC.toInt(), 0xFF007A33.toInt(), 1.03f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF007A33.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0FDF4.toInt(), 0xFFF0FDF4.toInt(), 0xFFDCFCE7.toInt(), 0xFFBBF7D0.toInt(), 0xFF007A33.toInt(), 1.03f)
            }
            // PRIORIDADES HOSPITAL AZUL - Azul hospitalar com alertas
            "hospital_azul" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFDC2626.toInt(), 0xFFFEF2F2.toInt(), 0xFFFEE2E2.toInt(), 0xFFFEF2F2.toInt(), 0xFFFECACA.toInt(), 0xFFFCA5A5.toInt(), 0xFFDC2626.toInt(), 1.06f)
                "urgent" -> PriorityStyle("URGENTE", 0xFFD97706.toInt(), 0xFFFFFBEB.toInt(), 0xFFFEF3C7.toInt(), 0xFFFFFBEB.toInt(), 0xFFFDE68A.toInt(), 0xFFFCD34D.toInt(), 0xFFD97706.toInt(), 1.05f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF0066A1.toInt(), 0xFFF0F9FF.toInt(), 0xFFE0F2FE.toInt(), 0xFFF0F9FF.toInt(), 0xFFBAE6FD.toInt(), 0xFF7DD3FC.toInt(), 0xFF0066A1.toInt(), 1.03f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF0066A1.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0F9FF.toInt(), 0xFFF0F9FF.toInt(), 0xFFE0F2FE.toInt(), 0xFFBAE6FD.toInt(), 0xFF0066A1.toInt(), 1.03f)
            }
            // PRIORIDADES CLINICA MODERNA - Verde-agua com alertas
            "clinica_moderna" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFDC2626.toInt(), 0xFFFEF2F2.toInt(), 0xFFFEE2E2.toInt(), 0xFFFEF2F2.toInt(), 0xFFFECACA.toInt(), 0xFFFCA5A5.toInt(), 0xFFDC2626.toInt(), 1.05f)
                "urgent" -> PriorityStyle("URGENTE", 0xFFD97706.toInt(), 0xFFFFFBEB.toInt(), 0xFFFEF3C7.toInt(), 0xFFFFFBEB.toInt(), 0xFFFDE68A.toInt(), 0xFFFCD34D.toInt(), 0xFFD97706.toInt(), 1.04f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF20B2AA.toInt(), 0xFFF0FDFA.toInt(), 0xFFCCFBF1.toInt(), 0xFFF0FDFA.toInt(), 0xFF99F6E4.toInt(), 0xFF5EEAD4.toInt(), 0xFF20B2AA.toInt(), 1.03f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF20B2AA.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0FDFA.toInt(), 0xFFF0FDFA.toInt(), 0xFFCCFBF1.toInt(), 0xFF99F6E4.toInt(), 0xFF20B2AA.toInt(), 1.03f)
            }
            // PRIORIDADES EMERGENCIA - Vermelho dominante com alertas intensos
            "emergencia" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFFFFFFF.toInt(), 0xFFDC2626.toInt(), 0xFFB91C1C.toInt(), 0xFFDC2626.toInt(), 0xFF991B1B.toInt(), 0xFFDC2626.toInt(), 0xFFFFFFFF.toInt(), 1.08f)
                "urgent" -> PriorityStyle("URGENTE", 0xFFFFFFFF.toInt(), 0xFFD97706.toInt(), 0xFFB45309.toInt(), 0xFFD97706.toInt(), 0xFF92400E.toInt(), 0xFFD97706.toInt(), 0xFFFFFFFF.toInt(), 1.06f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFFFFFFFF.toInt(), 0xFF059669.toInt(), 0xFF047857.toInt(), 0xFF059669.toInt(), 0xFF065F46.toInt(), 0xFF059669.toInt(), 0xFFFFFFFF.toInt(), 1.04f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFFDC2626.toInt(), 0xFFFFFFFF.toInt(), 0xFFFEF2F2.toInt(), 0xFFFEF2F2.toInt(), 0xFFFEE2E2.toInt(), 0xFFFECACA.toInt(), 0xFFDC2626.toInt(), 1.04f)
            }
            // PRIORIDADES PADRAO - SUS Verde como fallback
            else -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFDC2626.toInt(), 0xFFFEF2F2.toInt(), 0xFFFEE2E2.toInt(), 0xFFFEF2F2.toInt(), 0xFFFECACA.toInt(), 0xFFFCA5A5.toInt(), 0xFFDC2626.toInt(), 1.06f)
                "urgent" -> PriorityStyle("URGENTE", 0xFFD97706.toInt(), 0xFFFFFBEB.toInt(), 0xFFFEF3C7.toInt(), 0xFFFFFBEB.toInt(), 0xFFFDE68A.toInt(), 0xFFFCD34D.toInt(), 0xFFD97706.toInt(), 1.05f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF007A33.toInt(), 0xFFF0FDF4.toInt(), 0xFFDCFCE7.toInt(), 0xFFF0FDF4.toInt(), 0xFFBBF7D0.toInt(), 0xFF86EFAC.toInt(), 0xFF007A33.toInt(), 1.03f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF007A33.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0FDF4.toInt(), 0xFFF0FDF4.toInt(), 0xFFDCFCE7.toInt(), 0xFFBBF7D0.toInt(), 0xFF007A33.toInt(), 1.03f)
            }
        }
    }

    private fun resolvePriorityStyle(call: SigssCall?): PriorityStyle {
        val priorityText = call?.priority.orEmpty().trim().lowercase(Locale.ROOT)
        val colorText = call?.priorityColor.orEmpty().trim()

        if (priorityText.contains("emerg")) {
            return themedPriorityStyle("emergency")
        }
        if (priorityText.contains("urg")) {
            return themedPriorityStyle("urgent")
        }
        if (colorText.isNotBlank()) {
            val parsed = safeParseColor(colorText)
            if (parsed != null) {
                val red = Color.red(parsed)
                val green = Color.green(parsed)
                if (red >= 180 && green < 120) {
                    return themedPriorityStyle("emergency")
                }
                if (red >= 200 && green >= 150) {
                    return themedPriorityStyle("urgent").copy(badge = "ATENDIMENTO PRIORITARIO")
                }
                if (green >= 120) {
                    return themedPriorityStyle("normal")
                }
            }
        }

        return themedPriorityStyle("default")
    }

    private fun safeParseColor(value: String): Int? {
        return try {
            Color.parseColor(value)
        } catch (_: Throwable) {
            null
        }
    }

    private fun themedDisplayText(value: String, uppercase: Boolean): String {
        val text = value.ifBlank { "--" }
        return if (uppercase) text.uppercase(Locale("pt", "BR")) else text
    }

    private fun currentTheme(): NativeTheme {
        return when (currentThemeId) {
            "dark_modern" -> NativeTheme(
                backgroundStart = 0xFF0A0A0B.toInt(),
                backgroundEnd = 0xFF151518.toInt(),
                headerStart = 0xFF1A1A1F.toInt(),
                headerEnd = 0xFF141418.toInt(),
                headerStroke = 0xFF2A2A30.toInt(),
                logoStart = 0xFF1E1E24.toInt(),
                logoEnd = 0xFF18181C.toInt(),
                logoStroke = 0xFF2D2D35.toInt(),
                currentStart = 0xFF1A1A1F.toInt(),
                currentEnd = 0xFF121216.toInt(),
                currentStroke = 0xFF2563EB.toInt(),
                historyStart = 0xFF1A1A1F.toInt(),
                historyEnd = 0xFF141418.toInt(),
                historyStroke = 0xFF2A2A30.toInt(),
                mediaStart = 0xFF1A1A1F.toInt(),
                mediaEnd = 0xFF141418.toInt(),
                mediaStroke = 0xFF2A2A30.toInt(),
                surfaceStart = 0xFF1E1E24.toInt(),
                surfaceEnd = 0xFF18181C.toInt(),
                surfaceStroke = 0xFF2D2D35.toInt(),
                titleColor = 0xFFFFFFFF.toInt(),
                bodyColor = 0xFFCCCCCC.toInt(),
                mutedColor = 0xFF999999.toInt(),
                patientColor = 0xFFFFFFFF.toInt(),
                patientSerif = false,
                chipTextColor = 0xFF60A5FA.toInt(),
                chipBackground = 0xFF1E3A5F.toInt(),
                chipStroke = 0xFF2563EB.toInt()
            )
            "high_contrast" -> NativeTheme(
                backgroundStart = 0xFF000000.toInt(),
                backgroundEnd = 0xFF000000.toInt(),
                headerStart = 0xFF000000.toInt(),
                headerEnd = 0xFF000000.toInt(),
                headerStroke = 0xFFFFD700.toInt(),
                logoStart = 0xFF000000.toInt(),
                logoEnd = 0xFF000000.toInt(),
                logoStroke = 0xFFFFD700.toInt(),
                currentStart = 0xFF000000.toInt(),
                currentEnd = 0xFF000000.toInt(),
                currentStroke = 0xFFFFD700.toInt(),
                historyStart = 0xFF000000.toInt(),
                historyEnd = 0xFF000000.toInt(),
                historyStroke = 0xFFFFD700.toInt(),
                mediaStart = 0xFF000000.toInt(),
                mediaEnd = 0xFF000000.toInt(),
                mediaStroke = 0xFFFFD700.toInt(),
                surfaceStart = 0xFF000000.toInt(),
                surfaceEnd = 0xFF000000.toInt(),
                surfaceStroke = 0xFFFFD700.toInt(),
                titleColor = 0xFFFFFFFF.toInt(),
                bodyColor = 0xFFFFFFFF.toInt(),
                mutedColor = 0xFFCCCCCC.toInt(),
                patientColor = 0xFFFFFFFF.toInt(),
                patientSerif = false,
                chipTextColor = 0xFF000000.toInt(),
                chipBackground = 0xFFFFD700.toInt(),
                chipStroke = 0xFFFFD700.toInt()
            )
            // TEMA SUS VERDE - Cores oficiais do SUS com verde institucional
            "sus_verde" -> NativeTheme(
                backgroundStart = 0xFFF0F9F4.toInt(),
                backgroundEnd = 0xFFE3F2E9.toInt(),
                headerStart = 0xFF007A33.toInt(),  // Verde SUS oficial
                headerEnd = 0xFF006428.toInt(),
                headerStroke = 0xFF005522.toInt(),
                logoStart = 0xFFFFFFFF.toInt(),
                logoEnd = 0xFFF5FBF8.toInt(),
                logoStroke = 0xFF007A33.toInt(),
                currentStart = 0xFFFFFFFF.toInt(),
                currentEnd = 0xFFF5FBF8.toInt(),
                currentStroke = 0xFF007A33.toInt(),
                historyStart = 0xFFFFFFFF.toInt(),
                historyEnd = 0xFFF8FCF9.toInt(),
                historyStroke = 0xFFB8D4C4.toInt(),
                mediaStart = 0xFFFFFFFF.toInt(),
                mediaEnd = 0xFFF5FAF7.toInt(),
                mediaStroke = 0xFFB8D4C4.toInt(),
                surfaceStart = 0xFFF8FCF9.toInt(),
                surfaceEnd = 0xFFF0F7F3.toInt(),
                surfaceStroke = 0xFFC5DED0.toInt(),
                titleColor = 0xFF005522.toInt(),
                bodyColor = 0xFF1A5A3A.toInt(),
                mutedColor = 0xFF5A8A6A.toInt(),
                patientColor = 0xFF003D18.toInt(),
                patientSerif = false,
                chipTextColor = 0xFFFFFFFF.toInt(),
                chipBackground = 0xFF007A33.toInt(),
                chipStroke = 0xFF005522.toInt()
            )
            // TEMA HOSPITAL AZUL - Azul calmo hospitalar que transmite confianca
            "hospital_azul" -> NativeTheme(
                backgroundStart = 0xFFF0F7FC.toInt(),
                backgroundEnd = 0xFFE4F0F8.toInt(),
                headerStart = 0xFF0066A1.toInt(),  // Azul hospitalar
                headerEnd = 0xFF005588.toInt(),
                headerStroke = 0xFF004470.toInt(),
                logoStart = 0xFFFFFFFF.toInt(),
                logoEnd = 0xFFF5FAFD.toInt(),
                logoStroke = 0xFF0066A1.toInt(),
                currentStart = 0xFFFFFFFF.toInt(),
                currentEnd = 0xFFF5FAFD.toInt(),
                currentStroke = 0xFF0066A1.toInt(),
                historyStart = 0xFFFFFFFF.toInt(),
                historyEnd = 0xFFF8FBFD.toInt(),
                historyStroke = 0xFFB8D4E8.toInt(),
                mediaStart = 0xFFFFFFFF.toInt(),
                mediaEnd = 0xFFF5F9FC.toInt(),
                mediaStroke = 0xFFB8D4E8.toInt(),
                surfaceStart = 0xFFF8FBFD.toInt(),
                surfaceEnd = 0xFFF0F6FA.toInt(),
                surfaceStroke = 0xFFC5DEF0.toInt(),
                titleColor = 0xFF004470.toInt(),
                bodyColor = 0xFF1A4A6A.toInt(),
                mutedColor = 0xFF5A7A9A.toInt(),
                patientColor = 0xFF003355.toInt(),
                patientSerif = false,
                chipTextColor = 0xFFFFFFFF.toInt(),
                chipBackground = 0xFF0066A1.toInt(),
                chipStroke = 0xFF004470.toInt()
            )
            // TEMA CLINICA MODERNA - Branco clean com verde-agua (estilo centro cirurgico)
            "clinica_moderna" -> NativeTheme(
                backgroundStart = 0xFFF5FAFA.toInt(),
                backgroundEnd = 0xFFECF5F5.toInt(),
                headerStart = 0xFF20B2AA.toInt(),  // Verde-agua/Teal
                headerEnd = 0xFF1A9A92.toInt(),
                headerStroke = 0xFF158A82.toInt(),
                logoStart = 0xFFFFFFFF.toInt(),
                logoEnd = 0xFFF5FCFB.toInt(),
                logoStroke = 0xFF20B2AA.toInt(),
                currentStart = 0xFFFFFFFF.toInt(),
                currentEnd = 0xFFF5FCFB.toInt(),
                currentStroke = 0xFF20B2AA.toInt(),
                historyStart = 0xFFFFFFFF.toInt(),
                historyEnd = 0xFFF8FCFB.toInt(),
                historyStroke = 0xFFB8E0DC.toInt(),
                mediaStart = 0xFFFFFFFF.toInt(),
                mediaEnd = 0xFFF5FAFA.toInt(),
                mediaStroke = 0xFFB8E0DC.toInt(),
                surfaceStart = 0xFFF8FCFB.toInt(),
                surfaceEnd = 0xFFF0F8F7.toInt(),
                surfaceStroke = 0xFFC5E8E4.toInt(),
                titleColor = 0xFF158A82.toInt(),
                bodyColor = 0xFF2A6A65.toInt(),
                mutedColor = 0xFF5A9A95.toInt(),
                patientColor = 0xFF0D6A62.toInt(),
                patientSerif = false,
                chipTextColor = 0xFFFFFFFF.toInt(),
                chipBackground = 0xFF20B2AA.toInt(),
                chipStroke = 0xFF158A82.toInt()
            )
            // TEMA EMERGENCIA - Vermelho para pronto-socorro/UPA
            "emergencia" -> NativeTheme(
                backgroundStart = 0xFFFDF5F5.toInt(),
                backgroundEnd = 0xFFF8ECEC.toInt(),
                headerStart = 0xFFDC2626.toInt(),  // Vermelho emergencia
                headerEnd = 0xFFB91C1C.toInt(),
                headerStroke = 0xFF991B1B.toInt(),
                logoStart = 0xFFFFFFFF.toInt(),
                logoEnd = 0xFFFDF8F8.toInt(),
                logoStroke = 0xFFDC2626.toInt(),
                currentStart = 0xFFFFFFFF.toInt(),
                currentEnd = 0xFFFDF8F8.toInt(),
                currentStroke = 0xFFDC2626.toInt(),
                historyStart = 0xFFFFFFFF.toInt(),
                historyEnd = 0xFFFDFAFA.toInt(),
                historyStroke = 0xFFE8C4C4.toInt(),
                mediaStart = 0xFFFFFFFF.toInt(),
                mediaEnd = 0xFFFDF8F8.toInt(),
                mediaStroke = 0xFFE8C4C4.toInt(),
                surfaceStart = 0xFFFDFAFA.toInt(),
                surfaceEnd = 0xFFF8F2F2.toInt(),
                surfaceStroke = 0xFFF0D0D0.toInt(),
                titleColor = 0xFF991B1B.toInt(),
                bodyColor = 0xFF7F1D1D.toInt(),
                mutedColor = 0xFFAA6A6A.toInt(),
                patientColor = 0xFF7F1D1D.toInt(),
                patientSerif = false,
                chipTextColor = 0xFFFFFFFF.toInt(),
                chipBackground = 0xFFDC2626.toInt(),
                chipStroke = 0xFF991B1B.toInt()
            )
            // TEMA PADRAO - SUS Verde como fallback
            else -> NativeTheme(
                backgroundStart = 0xFFF0F9F4.toInt(),
                backgroundEnd = 0xFFE3F2E9.toInt(),
                headerStart = 0xFF007A33.toInt(),
                headerEnd = 0xFF006428.toInt(),
                headerStroke = 0xFF005522.toInt(),
                logoStart = 0xFFFFFFFF.toInt(),
                logoEnd = 0xFFF5FBF8.toInt(),
                logoStroke = 0xFF007A33.toInt(),
                currentStart = 0xFFFFFFFF.toInt(),
                currentEnd = 0xFFF5FBF8.toInt(),
                currentStroke = 0xFF007A33.toInt(),
                historyStart = 0xFFFFFFFF.toInt(),
                historyEnd = 0xFFF8FCF9.toInt(),
                historyStroke = 0xFFB8D4C4.toInt(),
                mediaStart = 0xFFFFFFFF.toInt(),
                mediaEnd = 0xFFF5FAF7.toInt(),
                mediaStroke = 0xFFB8D4C4.toInt(),
                surfaceStart = 0xFFF8FCF9.toInt(),
                surfaceEnd = 0xFFF0F7F3.toInt(),
                surfaceStroke = 0xFFC5DED0.toInt(),
                titleColor = 0xFF005522.toInt(),
                bodyColor = 0xFF1A5A3A.toInt(),
                mutedColor = 0xFF5A8A6A.toInt(),
                patientColor = 0xFF003D18.toInt(),
                patientSerif = false,
                chipTextColor = 0xFFFFFFFF.toInt(),
                chipBackground = 0xFF007A33.toInt(),
                chipStroke = 0xFF005522.toInt()
            )
        }
    }

    private fun currentThemeStyle(): NativeThemeStyle {
        return when (currentThemeId) {
            "dark_modern" -> NativeThemeStyle(
                rootPaddingDp = 18,
                headerPadHDp = 26,
                headerPadVDp = 22,
                currentPadHDp = 32,
                currentPadVDp = 28,
                historyPadHDp = 22,
                historyPadVDp = 20,
                mediaPadHDp = 20,
                mediaPadVDp = 18,
                headerRadiusDp = 20,
                currentRadiusDp = 24,
                sideRadiusDp = 18,
                rowRadiusDp = 14,
                logoRadiusDp = 16,
                headerStrokeWidthDp = 1,
                currentStrokeWidthDp = 2,
                sideStrokeWidthDp = 1,
                headerElevationDp = 0,
                currentElevationDp = 0,
                sideElevationDp = 0,
                patientSize = 56f,
                roomSize = 30f,
                professionalSize = 19f,
                prioritySize = 18f,
                updatedSize = 15f,
                historyTitleSize = 22f,
                historyPatientSize = 18f,
                historyDetailSize = 13f,
                mediaTitleSize = 22f,
                chipTextSize = 12f,
                chipLetterSpacing = 0.22f,
                chipPadHDp = 16,
                chipPadVDp = 10,
                currentNoMediaWeight = 1.44f,
                currentWithMediaWeight = 0.98f,
                sideNoMediaWeight = 0.88f,
                sideWithMediaWeight = 1.26f,
                historyWithMediaWeight = 0.32f,
                mediaWithMediaWeight = 0.84f,
                idleHistoryWeight = 0.70f,
                idleMediaWeight = 1.30f,
                historyRowPadHDp = 14,
                historyRowPadVDp = 12,
                historyRowSpacingDp = 8,
                historyPatientUppercase = true,
                patientUppercase = true,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 12,
                animationBlinkAlpha = 0.6f,
                animationBlinkScale = 1.04f,
                animationPriorityScale = 1.15f,
                animationLabelScale = 1.08f,
                animationIntervalMs = 220L,
                animationPulseBoost = 0.035f
            )
            "high_contrast" -> NativeThemeStyle(
                rootPaddingDp = 20,
                headerPadHDp = 28,
                headerPadVDp = 24,
                currentPadHDp = 36,
                currentPadVDp = 32,
                historyPadHDp = 24,
                historyPadVDp = 22,
                mediaPadHDp = 22,
                mediaPadVDp = 20,
                headerRadiusDp = 0,
                currentRadiusDp = 0,
                sideRadiusDp = 0,
                rowRadiusDp = 0,
                logoRadiusDp = 0,
                headerStrokeWidthDp = 3,
                currentStrokeWidthDp = 4,
                sideStrokeWidthDp = 3,
                headerElevationDp = 0,
                currentElevationDp = 0,
                sideElevationDp = 0,
                patientSize = 72f,
                roomSize = 38f,
                professionalSize = 24f,
                prioritySize = 24f,
                updatedSize = 18f,
                historyTitleSize = 28f,
                historyPatientSize = 24f,
                historyDetailSize = 16f,
                mediaTitleSize = 28f,
                chipTextSize = 16f,
                chipLetterSpacing = 0.15f,
                chipPadHDp = 20,
                chipPadVDp = 12,
                currentNoMediaWeight = 1.50f,
                currentWithMediaWeight = 1.04f,
                sideNoMediaWeight = 0.84f,
                sideWithMediaWeight = 1.20f,
                historyWithMediaWeight = 0.36f,
                mediaWithMediaWeight = 0.78f,
                idleHistoryWeight = 0.76f,
                idleMediaWeight = 1.24f,
                historyRowPadHDp = 16,
                historyRowPadVDp = 14,
                historyRowSpacingDp = 10,
                historyPatientUppercase = true,
                patientUppercase = true,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 14,
                animationBlinkAlpha = 0.5f,
                animationBlinkScale = 1.05f,
                animationPriorityScale = 1.18f,
                animationLabelScale = 1.10f,
                animationIntervalMs = 350L,
                animationPulseBoost = 0.04f
            )
            "sus_institucional" -> NativeThemeStyle(
                rootPaddingDp = 16,
                headerPadHDp = 24,
                headerPadVDp = 22,
                currentPadHDp = 24,
                currentPadVDp = 24,
                historyPadHDp = 20,
                historyPadVDp = 20,
                mediaPadHDp = 18,
                mediaPadVDp = 18,
                headerRadiusDp = 20,
                currentRadiusDp = 24,
                sideRadiusDp = 20,
                rowRadiusDp = 18,
                logoRadiusDp = 18,
                headerStrokeWidthDp = 2,
                currentStrokeWidthDp = 2,
                sideStrokeWidthDp = 2,
                headerElevationDp = 6,
                currentElevationDp = 8,
                sideElevationDp = 6,
                patientSize = 52f,
                roomSize = 30f,
                professionalSize = 19f,
                prioritySize = 18f,
                updatedSize = 15f,
                historyTitleSize = 22f,
                historyPatientSize = 18f,
                historyDetailSize = 13f,
                mediaTitleSize = 22f,
                chipTextSize = 12f,
                chipLetterSpacing = 0.2f,
                chipPadHDp = 16,
                chipPadVDp = 10,
                currentNoMediaWeight = 1.44f,
                currentWithMediaWeight = 0.96f,
                sideNoMediaWeight = 0.88f,
                sideWithMediaWeight = 1.3f,
                historyWithMediaWeight = 0.30f,
                mediaWithMediaWeight = 0.86f,
                idleHistoryWeight = 0.68f,
                idleMediaWeight = 1.34f,
                historyRowPadHDp = 14,
                historyRowPadVDp = 12,
                historyRowSpacingDp = 8,
                historyPatientUppercase = true,
                patientUppercase = true,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 12,
                animationBlinkAlpha = 0.8f,
                animationBlinkScale = 1.028f,
                animationPriorityScale = 1.12f,
                animationLabelScale = 1.06f,
                animationIntervalMs = 270L,
                animationPulseBoost = 0.028f
            )
            // ESTILO SUS VERDE - Moderno e institucional
            "sus_verde" -> NativeThemeStyle(
                rootPaddingDp = 18,
                headerPadHDp = 26,
                headerPadVDp = 18,
                currentPadHDp = 32,
                currentPadVDp = 28,
                historyPadHDp = 22,
                historyPadVDp = 20,
                mediaPadHDp = 20,
                mediaPadVDp = 18,
                headerRadiusDp = 20,
                currentRadiusDp = 24,
                sideRadiusDp = 20,
                rowRadiusDp = 16,
                logoRadiusDp = 16,
                headerStrokeWidthDp = 0,
                currentStrokeWidthDp = 3,
                sideStrokeWidthDp = 1,
                headerElevationDp = 8,
                currentElevationDp = 12,
                sideElevationDp = 6,
                patientSize = 58f,
                roomSize = 30f,
                professionalSize = 19f,
                prioritySize = 18f,
                updatedSize = 15f,
                historyTitleSize = 22f,
                historyPatientSize = 18f,
                historyDetailSize = 13f,
                mediaTitleSize = 22f,
                chipTextSize = 13f,
                chipLetterSpacing = 0.15f,
                chipPadHDp = 18,
                chipPadVDp = 10,
                currentNoMediaWeight = 1.46f,
                currentWithMediaWeight = 1.0f,
                sideNoMediaWeight = 0.88f,
                sideWithMediaWeight = 1.24f,
                historyWithMediaWeight = 0.34f,
                mediaWithMediaWeight = 0.82f,
                idleHistoryWeight = 0.72f,
                idleMediaWeight = 1.28f,
                historyRowPadHDp = 14,
                historyRowPadVDp = 12,
                historyRowSpacingDp = 8,
                historyPatientUppercase = true,
                patientUppercase = true,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 12,
                animationBlinkAlpha = 0.7f,
                animationBlinkScale = 1.04f,
                animationPriorityScale = 1.15f,
                animationLabelScale = 1.08f,
                animationIntervalMs = 240L,
                animationPulseBoost = 0.03f
            )
            // ESTILO HOSPITAL AZUL - Calmo e profissional
            "hospital_azul" -> NativeThemeStyle(
                rootPaddingDp = 18,
                headerPadHDp = 26,
                headerPadVDp = 18,
                currentPadHDp = 32,
                currentPadVDp = 28,
                historyPadHDp = 22,
                historyPadVDp = 20,
                mediaPadHDp = 20,
                mediaPadVDp = 18,
                headerRadiusDp = 20,
                currentRadiusDp = 24,
                sideRadiusDp = 20,
                rowRadiusDp = 16,
                logoRadiusDp = 16,
                headerStrokeWidthDp = 0,
                currentStrokeWidthDp = 3,
                sideStrokeWidthDp = 1,
                headerElevationDp = 8,
                currentElevationDp = 12,
                sideElevationDp = 6,
                patientSize = 56f,
                roomSize = 30f,
                professionalSize = 19f,
                prioritySize = 18f,
                updatedSize = 15f,
                historyTitleSize = 22f,
                historyPatientSize = 18f,
                historyDetailSize = 13f,
                mediaTitleSize = 22f,
                chipTextSize = 13f,
                chipLetterSpacing = 0.15f,
                chipPadHDp = 18,
                chipPadVDp = 10,
                currentNoMediaWeight = 1.46f,
                currentWithMediaWeight = 1.0f,
                sideNoMediaWeight = 0.88f,
                sideWithMediaWeight = 1.24f,
                historyWithMediaWeight = 0.34f,
                mediaWithMediaWeight = 0.82f,
                idleHistoryWeight = 0.72f,
                idleMediaWeight = 1.28f,
                historyRowPadHDp = 14,
                historyRowPadVDp = 12,
                historyRowSpacingDp = 8,
                historyPatientUppercase = true,
                patientUppercase = true,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 12,
                animationBlinkAlpha = 0.72f,
                animationBlinkScale = 1.035f,
                animationPriorityScale = 1.12f,
                animationLabelScale = 1.06f,
                animationIntervalMs = 260L,
                animationPulseBoost = 0.025f
            )
            // ESTILO CLINICA MODERNA - Clean e sofisticado
            "clinica_moderna" -> NativeThemeStyle(
                rootPaddingDp = 18,
                headerPadHDp = 26,
                headerPadVDp = 18,
                currentPadHDp = 32,
                currentPadVDp = 28,
                historyPadHDp = 22,
                historyPadVDp = 20,
                mediaPadHDp = 20,
                mediaPadVDp = 18,
                headerRadiusDp = 22,
                currentRadiusDp = 26,
                sideRadiusDp = 22,
                rowRadiusDp = 18,
                logoRadiusDp = 18,
                headerStrokeWidthDp = 0,
                currentStrokeWidthDp = 3,
                sideStrokeWidthDp = 1,
                headerElevationDp = 6,
                currentElevationDp = 10,
                sideElevationDp = 5,
                patientSize = 54f,
                roomSize = 28f,
                professionalSize = 18f,
                prioritySize = 17f,
                updatedSize = 14f,
                historyTitleSize = 21f,
                historyPatientSize = 17f,
                historyDetailSize = 12f,
                mediaTitleSize = 21f,
                chipTextSize = 12f,
                chipLetterSpacing = 0.18f,
                chipPadHDp = 16,
                chipPadVDp = 9,
                currentNoMediaWeight = 1.42f,
                currentWithMediaWeight = 0.98f,
                sideNoMediaWeight = 0.90f,
                sideWithMediaWeight = 1.26f,
                historyWithMediaWeight = 0.36f,
                mediaWithMediaWeight = 0.80f,
                idleHistoryWeight = 0.74f,
                idleMediaWeight = 1.26f,
                historyRowPadHDp = 14,
                historyRowPadVDp = 11,
                historyRowSpacingDp = 8,
                historyPatientUppercase = true,
                patientUppercase = true,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 12,
                animationBlinkAlpha = 0.74f,
                animationBlinkScale = 1.032f,
                animationPriorityScale = 1.10f,
                animationLabelScale = 1.05f,
                animationIntervalMs = 270L,
                animationPulseBoost = 0.022f
            )
            // ESTILO EMERGENCIA - Impactante e urgente
            "emergencia" -> NativeThemeStyle(
                rootPaddingDp = 16,
                headerPadHDp = 24,
                headerPadVDp = 16,
                currentPadHDp = 30,
                currentPadVDp = 26,
                historyPadHDp = 20,
                historyPadVDp = 18,
                mediaPadHDp = 18,
                mediaPadVDp = 16,
                headerRadiusDp = 18,
                currentRadiusDp = 22,
                sideRadiusDp = 18,
                rowRadiusDp = 14,
                logoRadiusDp = 14,
                headerStrokeWidthDp = 0,
                currentStrokeWidthDp = 4,
                sideStrokeWidthDp = 1,
                headerElevationDp = 10,
                currentElevationDp = 14,
                sideElevationDp = 6,
                patientSize = 60f,
                roomSize = 32f,
                professionalSize = 20f,
                prioritySize = 20f,
                updatedSize = 16f,
                historyTitleSize = 24f,
                historyPatientSize = 20f,
                historyDetailSize = 14f,
                mediaTitleSize = 24f,
                chipTextSize = 14f,
                chipLetterSpacing = 0.12f,
                chipPadHDp = 20,
                chipPadVDp = 12,
                currentNoMediaWeight = 1.50f,
                currentWithMediaWeight = 1.04f,
                sideNoMediaWeight = 0.84f,
                sideWithMediaWeight = 1.20f,
                historyWithMediaWeight = 0.32f,
                mediaWithMediaWeight = 0.84f,
                idleHistoryWeight = 0.70f,
                idleMediaWeight = 1.30f,
                historyRowPadHDp = 12,
                historyRowPadVDp = 10,
                historyRowSpacingDp = 6,
                historyPatientUppercase = true,
                patientUppercase = true,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 10,
                animationBlinkAlpha = 0.55f,
                animationBlinkScale = 1.06f,
                animationPriorityScale = 1.20f,
                animationLabelScale = 1.12f,
                animationIntervalMs = 180L,
                animationPulseBoost = 0.045f
            )
            // ESTILO PADRAO - SUS Verde como fallback
            else -> NativeThemeStyle(
                rootPaddingDp = 18,
                headerPadHDp = 26,
                headerPadVDp = 18,
                currentPadHDp = 32,
                currentPadVDp = 28,
                historyPadHDp = 22,
                historyPadVDp = 20,
                mediaPadHDp = 20,
                mediaPadVDp = 18,
                headerRadiusDp = 20,
                currentRadiusDp = 24,
                sideRadiusDp = 20,
                rowRadiusDp = 16,
                logoRadiusDp = 16,
                headerStrokeWidthDp = 0,
                currentStrokeWidthDp = 3,
                sideStrokeWidthDp = 1,
                headerElevationDp = 8,
                currentElevationDp = 12,
                sideElevationDp = 6,
                patientSize = 58f,
                roomSize = 30f,
                professionalSize = 19f,
                prioritySize = 18f,
                updatedSize = 15f,
                historyTitleSize = 22f,
                historyPatientSize = 18f,
                historyDetailSize = 13f,
                mediaTitleSize = 22f,
                chipTextSize = 13f,
                chipLetterSpacing = 0.15f,
                chipPadHDp = 18,
                chipPadVDp = 10,
                currentNoMediaWeight = 1.46f,
                currentWithMediaWeight = 1.0f,
                sideNoMediaWeight = 0.88f,
                sideWithMediaWeight = 1.24f,
                historyWithMediaWeight = 0.34f,
                mediaWithMediaWeight = 0.82f,
                idleHistoryWeight = 0.72f,
                idleMediaWeight = 1.28f,
                historyRowPadHDp = 14,
                historyRowPadVDp = 12,
                historyRowSpacingDp = 8,
                historyPatientUppercase = true,
                patientUppercase = true,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 12,
                animationBlinkAlpha = 0.7f,
                animationBlinkScale = 1.04f,
                animationPriorityScale = 1.15f,
                animationLabelScale = 1.08f,
                animationIntervalMs = 240L,
                animationPulseBoost = 0.03f
            )
        }
    }

    private fun applyNativeTheme() {
        val theme = currentTheme()
        val visualStyle = currentThemeStyle()
        nativePanelRoot.setPadding(
            dp(visualStyle.rootPaddingDp),
            dp(visualStyle.rootPaddingDp),
            dp(visualStyle.rootPaddingDp),
            dp(visualStyle.rootPaddingDp)
        )
        nativePanelRoot.background = createGradientCardDrawable(
            startColor = theme.backgroundStart,
            endColor = theme.backgroundEnd,
            strokeColor = 0x00000000,
            strokeWidth = 0,
            radiusDp = 0
        )
        nativeHeaderCard.setPadding(
            dp(visualStyle.headerPadHDp),
            dp(visualStyle.headerPadVDp),
            dp(visualStyle.headerPadHDp),
            dp(visualStyle.headerPadVDp)
        )
        nativeHeaderCard.background = createGradientCardDrawable(
            startColor = theme.headerStart,
            endColor = theme.headerEnd,
            strokeColor = theme.headerStroke,
            strokeWidth = dp(visualStyle.headerStrokeWidthDp),
            radiusDp = visualStyle.headerRadiusDp
        )
        nativeHeaderCard.elevation = dp(visualStyle.headerElevationDp).toFloat()
        nativeUnitLogoView.background = createGradientCardDrawable(
            startColor = theme.logoStart,
            endColor = theme.logoEnd,
            strokeColor = theme.logoStroke,
            strokeWidth = dp(visualStyle.headerStrokeWidthDp),
            radiusDp = visualStyle.logoRadiusDp
        )
        (nativeUnitLogoView.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val size = dp(72)
            params.width = size
            params.height = size
            nativeUnitLogoView.layoutParams = params
        }
        // Header colorido - textos brancos
        val isHeaderDark = currentThemeId in listOf("sus_verde", "hospital_azul", "clinica_moderna", "emergencia")
        val headerTextColor = if (isHeaderDark) 0xFFFFFFFF.toInt() else theme.titleColor
        val headerMutedColor = if (isHeaderDark) 0xCCFFFFFF.toInt() else theme.mutedColor
        nativeUnitNameText.setTextColor(headerTextColor)
        nativeUnitSubtitleText.setTextColor(headerMutedColor)
        nativeClockText.setTextColor(headerTextColor)
        nativeDateText.setTextColor(headerMutedColor)
        nativeCurrentCard.setPadding(
            dp(visualStyle.currentPadHDp),
            dp(visualStyle.currentPadVDp),
            dp(visualStyle.currentPadHDp),
            dp(visualStyle.currentPadVDp)
        )
        nativeCurrentCard.background = createGradientCardDrawable(
            startColor = theme.currentStart,
            endColor = theme.currentEnd,
            strokeColor = theme.currentStroke,
            strokeWidth = dp(visualStyle.currentStrokeWidthDp),
            radiusDp = visualStyle.currentRadiusDp
        )
        nativeCurrentCard.elevation = dp(visualStyle.currentElevationDp).toFloat()
        nativeHistoryCard.setPadding(
            dp(visualStyle.historyPadHDp),
            dp(visualStyle.historyPadVDp),
            dp(visualStyle.historyPadHDp),
            dp(visualStyle.historyPadVDp)
        )
        nativeHistoryCard.background = createGradientCardDrawable(
            startColor = theme.historyStart,
            endColor = theme.historyEnd,
            strokeColor = theme.historyStroke,
            strokeWidth = dp(visualStyle.sideStrokeWidthDp),
            radiusDp = visualStyle.sideRadiusDp
        )
        nativeHistoryCard.elevation = dp(visualStyle.sideElevationDp).toFloat()
        // Media card - fullbleed sem padding nem bordas para video limpo
        nativeMediaCard.setPadding(0, 0, 0, 0)
        nativeMediaCard.setBackgroundColor(0xFF000000.toInt()) // Fundo preto para video
        nativeMediaCard.elevation = dp(4).toFloat()
        nativeCurrentLabelText.setTextColor(theme.chipTextColor)
        nativeCurrentLabelText.background = createPillDrawable(theme.chipBackground, theme.chipStroke)
        nativeCurrentLabelText.textSize = visualStyle.chipTextSize
        nativeCurrentLabelText.letterSpacing = visualStyle.chipLetterSpacing
        nativeCurrentLabelText.setPadding(
            dp(visualStyle.chipPadHDp),
            dp(visualStyle.chipPadVDp),
            dp(visualStyle.chipPadHDp),
            dp(visualStyle.chipPadVDp)
        )
        nativeUnitNameText.text = themedDisplayText(currentUnitName.ifBlank { "PAINEL DE CHAMADOS" }, visualStyle.patientUppercase)
        nativePatientText.setTextColor(theme.patientColor)
        nativePatientText.typeface = if (theme.patientSerif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        nativePatientText.textSize = visualStyle.patientSize
        nativePatientText.text = themedDisplayText(nativePatientText.text.toString(), visualStyle.patientUppercase)
        nativeCurrentHintText.setTextColor(theme.mutedColor)
        nativeCurrentHintText.isVisible = visualStyle.useCurrentHint
        nativeHistoryTitleText.setTextColor(theme.titleColor)
        nativeHistoryTitleText.textSize = visualStyle.historyTitleSize
        nativeHistoryHintText.setTextColor(theme.mutedColor)
        nativeMediaTitleText.setTextColor(theme.titleColor)
        nativeMediaTitleText.textSize = visualStyle.mediaTitleSize
        nativeMediaHintText.setTextColor(theme.mutedColor)
        nativeRoomText.setTextColor(theme.titleColor)
        nativeRoomText.textSize = visualStyle.roomSize
        nativeProfessionalText.setTextColor(theme.bodyColor)
        nativeProfessionalText.textSize = visualStyle.professionalSize
        nativePriorityText.textSize = visualStyle.prioritySize
        nativeUpdatedText.setTextColor(theme.mutedColor)
        nativeUpdatedText.textSize = visualStyle.updatedSize

        (nativePriorityText.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (visualStyle.priorityFullWidth) {
                params.width = LinearLayout.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.FILL_HORIZONTAL
            } else {
                params.width = LinearLayout.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.START
            }
            nativePriorityText.layoutParams = params
        }
        nativePriorityText.gravity = Gravity.CENTER
        (nativeMediaWebView?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            params.topMargin = dp(visualStyle.mediaTopMarginDp)
            nativeMediaWebView?.layoutParams = params
        }
    }

    private fun refreshUnitBranding() {
        nativeUnitNameText.text = currentUnitName.ifBlank { "Painel de chamados" }
        nativeUnitNameText.isVisible = true
        nativeUnitSubtitleText.text = ""
        nativeUnitSubtitleText.isVisible = false
        val logoUrl = resolveServerUrl(currentUnitLogoUrl)
        if (logoUrl.isBlank()) {
            nativeUnitLogoView.setImageDrawable(null)
            nativeUnitLogoView.isVisible = false
            return
        }
        nativeUnitLogoView.isVisible = true
        nativeUnitLogoView.tag = logoUrl
        Thread {
            try {
                val request = Request.Builder()
                    .url(logoUrl)
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("http_${response.code}")
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    mainHandler.post {
                        if (nativeUnitLogoView.tag == logoUrl && bitmap != null) {
                            nativeUnitLogoView.setImageBitmap(bitmap)
                            nativeUnitLogoView.isVisible = true
                        }
                    }
                }
            } catch (_: Throwable) {
                mainHandler.post {
                    if (nativeUnitLogoView.tag == logoUrl) {
                        nativeUnitLogoView.setImageDrawable(null)
                        nativeUnitLogoView.isVisible = false
                    }
                }
            }
        }.start()
    }

    private fun setNativeStatus(text: String, textColor: Int, backgroundColor: Int) {
        nativeStatusText.text = text
        nativeStatusText.setTextColor(textColor)
        nativeStatusText.background = createPillDrawable(backgroundColor, 0x00000000)
    }

    private fun showEmptyHistoryState() {
        nativeList.removeAllViews()
        val empty = TextView(this)
        empty.text = "Aguardando chamadas..."
        empty.setTextColor(0xFF7A9A8A.toInt())
        empty.textSize = sp(15f)
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(14), dp(24), dp(14), dp(24))
        empty.background = createGradientCardDrawable(
            startColor = 0xFFF5FBF8.toInt(),
            endColor = 0xFFEDF7F2.toInt(),
            strokeColor = 0xFFD0E8DC.toInt(),
            strokeWidth = dp(1),
            radiusDp = 12
        )
        nativeList.addView(empty, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        })
    }

    private fun animateCurrentCallCard(style: PriorityStyle, isNewCall: Boolean) {
        val visualStyle = currentThemeStyle()
        nativeCurrentCard.animate().cancel()
        nativePriorityText.animate().cancel()
        nativePatientText.animate().cancel()
        nativeCurrentLabelText.animate().cancel()
        if (!isNewCall) return

        // Determine animation intensity based on priority
        val isEmergency = style.badge.contains("EMERG", ignoreCase = true)
        val isUrgent = style.badge.contains("URGENT", ignoreCase = true)
        val blinkSteps = when {
            isEmergency -> 8
            isUrgent -> 6
            else -> 4
        }
        val intervalMs = when {
            isEmergency -> (visualStyle.animationIntervalMs * 0.7).toLong()
            isUrgent -> (visualStyle.animationIntervalMs * 0.85).toLong()
            else -> visualStyle.animationIntervalMs
        }

        // Initial slide-in animation from left
        nativeCurrentCard.translationX = -dp(80).toFloat()
        nativeCurrentCard.alpha = 0f
        nativeCurrentCard.scaleX = 0.92f
        nativeCurrentCard.scaleY = 0.92f
        
        nativeCurrentCard.animate()
            .translationX(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .withEndAction {
                // After slide-in, start the attention blink animation
                val baseAlpha = 1f
                val blinkAlpha = visualStyle.animationBlinkAlpha
                val basePriorityAlpha = 1f
                val blinkPriorityAlpha = (visualStyle.animationBlinkAlpha - 0.06f).coerceAtLeast(0.58f)
                
                repeat(blinkSteps) { index ->
                    mainHandler.postDelayed({
                        val highlighted = index % 2 == 0
                        
                        // Card glow effect
                        nativeCurrentCard.alpha = if (highlighted) baseAlpha else blinkAlpha
                        nativeCurrentCard.scaleX = if (highlighted) visualStyle.animationBlinkScale else 0.994f
                        nativeCurrentCard.scaleY = if (highlighted) visualStyle.animationBlinkScale else 0.994f
                        
                        // Priority badge animation
                        nativePriorityText.alpha = if (highlighted) basePriorityAlpha else blinkPriorityAlpha
                        nativePriorityText.scaleX = if (highlighted) visualStyle.animationPriorityScale else 1f
                        nativePriorityText.scaleY = if (highlighted) visualStyle.animationPriorityScale else 1f
                        
                        // Label animation
                        nativeCurrentLabelText.alpha = if (highlighted) 1f else 0.78f
                        nativeCurrentLabelText.scaleX = if (highlighted) visualStyle.animationLabelScale else 1f
                        nativeCurrentLabelText.scaleY = if (highlighted) visualStyle.animationLabelScale else 1f
                        
                        // Patient name subtle scale for emphasis
                        nativePatientText.scaleX = if (highlighted) 1.015f else 1f
                        nativePatientText.scaleY = if (highlighted) 1.015f else 1f
                    }, index * intervalMs)
                }
                
                // Final settling pulse animation
                mainHandler.postDelayed({
                    nativeCurrentCard.alpha = 1f
                    nativePriorityText.alpha = 1f
                    nativePriorityText.scaleX = 1f
                    nativePriorityText.scaleY = 1f
                    nativeCurrentLabelText.alpha = 1f
                    nativeCurrentLabelText.scaleX = 1f
                    nativeCurrentLabelText.scaleY = 1f
                    nativePatientText.scaleX = 1f
                    nativePatientText.scaleY = 1f
                    
                    // Smooth breathing pulse at the end
                    nativeCurrentCard.scaleX = 0.99f
                    nativeCurrentCard.scaleY = 0.99f
                    nativeCurrentCard.animate()
                        .scaleX(style.pulseScale + visualStyle.animationPulseBoost)
                        .scaleY(style.pulseScale + visualStyle.animationPulseBoost)
                        .setDuration(320L)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction {
                            nativeCurrentCard.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(380L)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        }
                        .start()
                }, intervalMs * blinkSteps + 100L)
            }
            .start()
    }

    private fun animateHistoryRowEntry(row: View, delayMs: Long) {
        row.alpha = 0f
        row.translationY = dp(20).toFloat()
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(250L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun createCardDrawable(
        backgroundColor: Int,
        strokeColor: Int,
        strokeWidth: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(backgroundColor)
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun createGradientCardDrawable(
        startColor: Int,
        endColor: Int,
        strokeColor: Int,
        strokeWidth: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun createPillDrawable(backgroundColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(backgroundColor)
            if (strokeColor != 0x00000000) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density * screenScale).toInt()

    // Escala responsiva baseada no tamanho da tela
    // TV 32" (720p) = escala menor, TV 55"+ (1080p/4K) = escala normal
    private val screenScale: Float by lazy {
        val metrics = resources.displayMetrics
        val screenWidthDp = metrics.widthPixels / metrics.density
        when {
            screenWidthDp < 960 -> 0.70f   // TV pequena 32" ou menor (720p)
            screenWidthDp < 1280 -> 0.82f  // TV media 40-43" (1080p)
            screenWidthDp < 1600 -> 0.92f  // TV grande 50-55" (1080p)
            else -> 1.0f                    // TV muito grande 60"+ ou 4K
        }
    }

    // Escala de fonte - proporcional mas com minimo legivel
    private fun sp(value: Float): Float {
        val scaled = value * screenScale
        return scaled.coerceAtLeast(value * 0.65f) // Nunca menor que 65% do original
    }

    private val clockUpdateRunnable = object : Runnable {
        override fun run() {
            updateClockDisplay()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun startClockUpdates() {
        mainHandler.removeCallbacks(clockUpdateRunnable)
        mainHandler.post(clockUpdateRunnable)
    }

    private fun stopClockUpdates() {
        mainHandler.removeCallbacks(clockUpdateRunnable)
    }

    private fun updateClockDisplay() {
        try {
            val now = java.util.Calendar.getInstance()
            val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = now.get(java.util.Calendar.MINUTE)
            nativeClockText.text = String.format("%02d:%02d", hour, minute)
            
            val dayOfWeek = when (now.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.SUNDAY -> "Domingo"
                java.util.Calendar.MONDAY -> "Segunda-feira"
                java.util.Calendar.TUESDAY -> "Terca-feira"
                java.util.Calendar.WEDNESDAY -> "Quarta-feira"
                java.util.Calendar.THURSDAY -> "Quinta-feira"
                java.util.Calendar.FRIDAY -> "Sexta-feira"
                java.util.Calendar.SATURDAY -> "Sabado"
                else -> ""
            }
            val day = now.get(java.util.Calendar.DAY_OF_MONTH)
            val month = when (now.get(java.util.Calendar.MONTH)) {
                java.util.Calendar.JANUARY -> "Janeiro"
                java.util.Calendar.FEBRUARY -> "Fevereiro"
                java.util.Calendar.MARCH -> "Marco"
                java.util.Calendar.APRIL -> "Abril"
                java.util.Calendar.MAY -> "Maio"
                java.util.Calendar.JUNE -> "Junho"
                java.util.Calendar.JULY -> "Julho"
                java.util.Calendar.AUGUST -> "Agosto"
                java.util.Calendar.SEPTEMBER -> "Setembro"
                java.util.Calendar.OCTOBER -> "Outubro"
                java.util.Calendar.NOVEMBER -> "Novembro"
                java.util.Calendar.DECEMBER -> "Dezembro"
                else -> ""
            }
            nativeDateText.text = "$dayOfWeek, $day de $month"
        } catch (_: Throwable) { }
    }

    private fun hideOverlay() {
        overlayRoot.isVisible = false
    }

    private fun showSettingsDialog() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 20, 40, 0)

        val inputServer = EditText(this)
        inputServer.hint = getString(R.string.config_servidor_hint)
        inputServer.setText(getServerBaseUrl())

        val inputFallback = EditText(this)
        inputFallback.hint = getString(R.string.config_fallback_hint)
        inputFallback.setText(getFallbackPanelUrl())

        root.addView(inputServer)
        root.addView(inputFallback)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.config_titulo))
            .setView(root)
            .setPositiveButton(getString(R.string.salvar)) { _, _ ->
                val server = inputServer.text?.toString()?.trim().orEmpty()
                val fallback = inputFallback.text?.toString()?.trim().orEmpty()

                prefs.edit()
                    .putString(PREF_SERVER_BASE_URL, normalizeServerBase(server))
                    .putString(PREF_FALLBACK_URL, if (fallback.isBlank()) DEFAULT_FALLBACK_URL else fallback)
                    .apply()

                if (!hasActivationToken()) {
                    showPairingIdle()
                } else {
                    ensureWebSocketConnected()
                }
            }
            .setNeutralButton(getString(R.string.desvincular)) { _, _ ->
                clearPairing()
                showPairingIdle()
            }
            .setNegativeButton(getString(R.string.cancelar), null)
            .create()

        dialog.show()
    }

    private fun showPairingIdle() {
        stopContentForPairing()
        pairingInProgress = false
        pairingId = null
        pairingSecret = null
        pairingExpiresAt = null

        overlayTitle.text = getString(R.string.pair_titulo)
        overlayQr.setImageDrawable(null)
        overlayQr.isVisible = false
        overlayText.text = buildString {
            append(getString(R.string.pair_instrucoes_1))
            append("\n\n")
            append(getString(R.string.pair_instrucoes_2, getServerBaseUrl().ifBlank { "-" }))
        }
        overlayButtonPrimary.text = getString(R.string.pair_conectar)
        overlayButtonPrimary.isVisible = true
        overlayButtonPrimary.setOnClickListener { startPairing() }
        overlayButtonSecondary.text = getString(R.string.pair_configurar)
        overlayButtonSecondary.isVisible = true
        overlayButtonSecondary.setOnClickListener { showSettingsDialog() }
        overlayRoot.isVisible = true
        overlayRoot.bringToFront()
    }

    private fun stopContentForPairing() {
        nativeModeActive = false
        nativePolling = false
        mainHandler.removeCallbacks(nativeSigssPollRunnable)
        nativePanelRoot.isVisible = false
        currentDisplayMode = "panel_only"
        currentMediaPlaybackUrl = ""
        currentMediaUrl = ""
        updateNativeLayoutForMode()
        callOverlay.isVisible = false
        currentContentUrl = "about:blank"
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.isVisible = true
        } catch (_: Throwable) {
        }
    }

    private fun startPairing() {
        if (!networkAvailable) {
            showStatus(getString(R.string.status_offline))
            return
        }
        val base = getServerBaseUrl()
        if (base.isBlank()) {
            showSettingsDialog()
            return
        }

        pairingInProgress = true
        overlayTitle.text = getString(R.string.pair_titulo)
        overlayQr.setImageDrawable(null)
        overlayQr.isVisible = false
        overlayText.text = getString(R.string.pair_iniciando)
        overlayButtonPrimary.isVisible = false
        overlayButtonSecondary.isVisible = false
        overlayRoot.isVisible = true

        Thread {
            try {
                val clientId = ensureClientId()
                val body = JSONObject().apply {
                    put("suggestedName", "TV ${android.os.Build.MODEL}")
                    put("clientId", clientId)
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("localIpAddress", getLocalIpv4Address())
                    put("deviceInfo", JSONObject().apply {
                        put("manufacturer", android.os.Build.MANUFACTURER)
                        put("model", android.os.Build.MODEL)
                        put("sdk", android.os.Build.VERSION.SDK_INT)
                        put("localIp", getLocalIpv4Address())
                    })
                }.toString()

                val request = Request.Builder()
                    .url("${base.trimEnd('/')}/api/pairing/request")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("http_${response.code}")
                    val json = JSONObject(text).getJSONObject("pairing")
                    val id = json.getString("id")
                    val secret = json.getString("secret")
                    val code = json.getString("code")
                    val approveUrl = json.getString("approveUrl")
                    val expiresAt = json.optString("expiresAt", "")
                    Log.i(tag, "PAIR requested id=$id code=$code approveUrl=$approveUrl expiresAt=$expiresAt")

                    mainHandler.post {
                        pairingId = id
                        pairingSecret = secret
                        pairingExpiresAt = expiresAt
                        showPairingQr(code, approveUrl)
                        pollPairingStatus()
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "PAIR request failed", t)
                mainHandler.post {
                    pairingInProgress = false
                    overlayTitle.text = getString(R.string.pair_titulo)
                    overlayQr.setImageDrawable(null)
                    overlayQr.isVisible = false
                    overlayText.text = getString(R.string.pair_erro)
                    overlayButtonPrimary.text = getString(R.string.pair_tentar_novamente)
                    overlayButtonPrimary.isVisible = true
                    overlayButtonPrimary.setOnClickListener { startPairing() }
                    overlayButtonSecondary.text = getString(R.string.pair_configurar)
                    overlayButtonSecondary.isVisible = true
                    overlayButtonSecondary.setOnClickListener { showSettingsDialog() }
                    overlayRoot.isVisible = true
                }
            }
        }.start()
    }

    private fun showPairingQr(code: String, approveUrl: String) {
        overlayTitle.text = getString(R.string.pair_titulo)
        overlayQr.isVisible = true
        overlayQr.setImageBitmap(generateQrBitmap(approveUrl, 720))
        overlayText.text = buildString {
            append(getString(R.string.pair_qr_instrucao))
            append("\n\n")
            append(getString(R.string.pair_codigo, code))
            append("\n")
            append(getString(R.string.pair_ip, getServerBaseUrl()))
        }
        overlayButtonPrimary.isVisible = false
        overlayButtonSecondary.text = getString(R.string.pair_configurar)
        overlayButtonSecondary.isVisible = true
        overlayButtonSecondary.setOnClickListener { showSettingsDialog() }
        overlayRoot.isVisible = true
    }

    private fun pollPairingStatus() {
        val id = pairingId ?: return
        val secret = pairingSecret ?: return
        val base = getServerBaseUrl()
        if (base.isBlank()) return

        Thread {
            try {
                val request = Request.Builder()
                    .url("${base.trimEnd('/')}/api/pairing/${id}/status?secret=${secret}")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("http_${response.code}")
                    val pairing = JSONObject(text).getJSONObject("pairing")
                    val status = pairing.getString("status")
                    Log.i(tag, "PAIR status id=$id status=$status")
                    if (status == "approved") {
                        val activationToken = pairing.getString("activationToken")
                        Log.i(tag, "PAIR approved id=$id token=${activationToken.take(6)}***")
                        prefs.edit().putString(PREF_ACTIVATION_TOKEN, activationToken).apply()
                        pairingInProgress = false
                        mainHandler.post {
                            hideOverlay()
                            ensureWebSocketConnected()
                        }
                        return@use
                    }
                    if (status == "expired") {
                        pairingInProgress = false
                        mainHandler.post { showPairingIdle() }
                        return@use
                    }
                    mainHandler.postDelayed({ pollPairingStatus() }, 2000L)
                }
            } catch (t: Throwable) {
                Log.e(tag, "PAIR status failed", t)
                mainHandler.postDelayed({ pollPairingStatus() }, 3000L)
            }
        }.start()
    }

    private fun ensureWebSocketConnected() {
        if (!networkAvailable) return
        val token = prefs.getString(PREF_ACTIVATION_TOKEN, null)?.trim().orEmpty()
        val base = getServerBaseUrl()
        if (token.isBlank() || base.isBlank()) return
        if (ws != null) return

        val wsUrl = base.trimEnd('/').replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") +
            "/ws/device?token=" + token

        val request = Request.Builder().url(wsUrl).build()
        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "WS connected")
                heartbeatReconnects += 1
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.post(heartbeatRunnable)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWsMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WS failure", t)
                ws = null
                mainHandler.removeCallbacks(heartbeatRunnable)
                if (hasActivationToken() && (webView.url.isNullOrBlank() || webView.url == "about:blank")) {
                    mainHandler.post { showConnecting() }
                }
                mainHandler.postDelayed({ ensureWebSocketConnected() }, 3000L)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "WS closed code=$code reason=$reason")
                ws = null
                mainHandler.removeCallbacks(heartbeatRunnable)
                if (hasActivationToken() && (webView.url.isNullOrBlank() || webView.url == "about:blank")) {
                    mainHandler.post { showConnecting() }
                }
                mainHandler.postDelayed({ ensureWebSocketConnected() }, 3000L)
            }
        })
    }

    private fun disconnectWebSocket() {
        try {
            ws?.close(1000, "bye")
        } catch (_: Throwable) {
        }
        ws = null
    }

    private fun handleWsMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type", "")) {
                "registered" -> {
                    val config = json.optJSONObject("config") ?: return
                    applyConfig(config)
                }
                "config" -> {
                    val config = json.optJSONObject("config") ?: return
                    applyConfig(config)
                }
                "command" -> {
                    val command = json.optJSONObject("command") ?: return
                    val commandId = command.optString("id", "")
                    val cmd = command.optString("command", "")
                    val payload = command.optJSONObject("payload") ?: JSONObject()
                    mainHandler.post { handleCommand(cmd, payload) }
                    if (commandId.isNotBlank()) sendCommandAck(commandId)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyConfig(config: JSONObject) {
        val currentUrl = config.optString("currentUrl", "")
        val fallbackUrl = config.optString("fallbackUrl", getFallbackPanelUrl())
        heartbeatIntervalSeconds = config.optInt("heartbeatIntervalSeconds", 15)
        audioEnabled = config.optBoolean("audioEnabled", audioEnabled)
        ttsVoicePreference = config.optString("ttsVoicePreference", ttsVoicePreference)
        currentTtsVoice = config.optString("ttsVoice", currentTtsVoice).trim()
        currentDisplayMode = config.optString("displayMode", currentDisplayMode).ifBlank { "panel_only" }
        currentMediaUrl = config.optString("mediaUrl", currentMediaUrl)
        currentMediaPlaybackUrl = config.optString("mediaPlaybackUrl", currentMediaPlaybackUrl).ifBlank { currentMediaUrl }
        currentThemeId = config.optString("themeId", currentThemeId).ifBlank { "classic_warm" }
        currentUnitName = config.optString("unitName", currentUnitName).trim()
        currentUnitLogoUrl = config.optString("unitLogoUrl", currentUnitLogoUrl).trim()
        val url = if (currentUrl.isBlank() || currentUrl == "about:blank") fallbackUrl else currentUrl

        mainHandler.post {
            refreshUnitBranding()
            val normalizedIncoming = url.ifBlank { "about:blank" }
            val sameUrl = currentContentUrl.ifBlank { "about:blank" } == normalizedIncoming
            val sameNativePanel = isSameNativeSigssPanel(normalizedIncoming)
            if ((sameUrl || sameNativePanel) && nativeModeActive) {
                currentContentUrl = normalizedIncoming
                nativeModeActive = true
                webView.isVisible = false
                nativePanelRoot.isVisible = true
                callOverlay.isVisible = false
                nativePanelRoot.bringToFront()
                overlayRoot.bringToFront()
                updateNativeLayoutForMode()
                if (nativeLastRenderedCalls.isNotEmpty()) {
                    renderNativeSigssCalls(nativeLastRenderedCalls)
                } else {
                    setNativeStatus("Atualizando visual do painel...", 0xFF205A7A.toInt(), 0xFFE6F4FA.toInt())
                    showEmptyHistoryState()
                    mainHandler.removeCallbacks(nativeSigssPollRunnable)
                    mainHandler.post(nativeSigssPollRunnable)
                }
                if (currentMediaPlaybackUrl.isNotBlank()) {
                    val targetUrl = resolveServerUrl(currentMediaPlaybackUrl)
                    val mediaView = nativeMediaWebView ?: if (currentMediaPlaybackUrl.isNotBlank()) ensureNativeMediaWebView() else null
                    mediaView?.let {
                        if (targetUrl.isNotBlank() && mediaView.url != targetUrl) {
                            mediaView.loadUrl(targetUrl)
                        }
                    }
                }
            } else if (sameUrl && !nativeModeActive) {
                updateNativeLayoutForMode()
            } else if (url.isNotBlank()) {
                applyContentUrl(url)
            }
            hideOverlay()
        }
    }

    private fun handleCommand(command: String, payload: JSONObject) {
        when (command) {
            "reload_page" -> {
                heartbeatReloads += 1
                if (nativeModeActive) {
                    pollNativeSigssPanel()
                } else {
                    webView.reload()
                }
            }
            "recreate_webview" -> {
                recreateWebView()
            }
            "clear_cache" -> {
                clearWebViewData()
            }
            "restart_app" -> {
                restartApp()
            }
            "enter_maintenance" -> {
                enterMaintenanceMode()
            }
            "set_audio" -> {
                val enabled = payload.optBoolean("audioEnabled", true)
                audioEnabled = enabled
                if (!enabled) {
                    try {
                        tts?.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        speechPlayer?.stop()
                        speechPlayer?.release()
                    } catch (_: Throwable) {
                    }
                    speechPlayer = null
                }
            }
            "open_url" -> {
                val url = payload.optString("url", "")
                if (url.isNotBlank()) applyContentUrl(url)
            }
            "unpair" -> {
                clearPairing()
                showPairingIdle()
            }
        }
    }

    private fun recreateWebView() {
        val currentUrl = currentContentUrl.ifBlank { webView.url ?: getFallbackPanelUrl() }
        try {
            container.removeView(webView)
        } catch (_: Throwable) {
        }
        try {
            webView.stopLoading()
        } catch (_: Throwable) {
        }
        try {
            webView.destroy()
        } catch (_: Throwable) {
        }

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        container.addView(webView, 0)
        setupWebView()
        webViewRecreates += 1
        applyContentUrl(currentUrl)
        sendDeviceLog("WEBVIEW", "recreate")
    }

    private fun clearWebViewData() {
        try {
            webView.clearCache(true)
            webView.clearHistory()
        } catch (_: Throwable) {
        }
        try {
            nativeMediaWebView?.clearCache(true)
            nativeMediaWebView?.clearHistory()
        } catch (_: Throwable) {
        }
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Throwable) {
        }
        sendDeviceLog("CACHE", "cleared")
    }

    private fun restartApp() {
        appRestarts += 1
        sendDeviceLog("APP", "restart")
        expectedRestart = true
        scheduleSelfRestart("command_restart", 500L)
        mainHandler.postDelayed(
            {
                shutdownRequested = true
                prefs.edit().putBoolean("shutdown_requested", true).apply()
                try {
                    finish()
                } catch (_: Throwable) {
                }
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {
                }
            },
            150L
        )
    }

    private fun enterMaintenanceMode() {
        sendDeviceLog("APP", "maintenance_mode")
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(nativeSigssPollRunnable)
        pairingInProgress = false
        nativePolling = false
        shutdownRequested = true
        expectedRestart = false
        prefs.edit()
            .putBoolean("maintenance_mode", true)
            .putBoolean("shutdown_requested", true)
            .apply()
        try {
            stopService(Intent(this, AppKeepAliveService::class.java))
        } catch (_: Throwable) {
        }
        try {
            disconnectWebSocket()
        } catch (_: Throwable) {
        }
        try {
            finishAffinity()
        } catch (_: Throwable) {
        }
        mainHandler.postDelayed(
            {
                try {
                    finish()
                } catch (_: Throwable) {
                }
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {
                }
                try {
                    exitProcess(0)
                } catch (_: Throwable) {
                }
            },
            120L
        )
    }

    private fun markActivityAlive(foreground: Boolean) {
        prefs.edit()
            .putLong("activity_last_seen", SystemClock.elapsedRealtime())
            .putBoolean("activity_foreground", foreground)
            .apply()
    }

    private fun startKeepAliveService() {
        try {
            val serviceIntent = Intent(this, AppKeepAliveService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Throwable) {
        }
    }

    private fun scheduleSelfRestart(reason: String, delayMs: Long = 1500L) {
        sendDeviceLog("APP_WATCHDOG", reason)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            4101,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(300L)
        try {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } catch (_: Throwable) {
            try {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } catch (_: Throwable) {
            }
        }
    }

    private fun sendCommandAck(commandId: String) {
        val socket = ws ?: return
        val msg = JSONObject().apply {
            put("type", "command_ack")
            put("commandId", commandId)
        }.toString()
        try {
            socket.send(msg)
        } catch (_: Throwable) {
        }
    }

    private fun sendHeartbeat() {
        val socket = ws ?: return
        val telemetry = JSONObject().apply {
            put("status", "online")
            put("currentUrl", currentContentUrl.ifBlank { webView.url ?: "" })
            put("playerState", if (nativeModeActive) "native_sigss" else "webview")
            put("memoryMb", 0)
            put("reloads", heartbeatReloads)
            put("reconnects", heartbeatReconnects)
            put("webViewRecreates", webViewRecreates)
            put("appRestarts", appRestarts)
            put("nativeMode", nativeModeActive)
            put("nativePanelId", nativePanelId)
            put("nativePollFailures", nativePollFailures)
            put("panelNetworkEvents", panelNetworkEvents)
            put("panelAutoReloads", panelAutoReloads)
            put(
                "lastPanelNetworkActivitySecondsAgo",
                if (lastPanelNetworkActivityAt > 0L) ((SystemClock.elapsedRealtime() - lastPanelNetworkActivityAt) / 1000).toInt() else -1
            )
            put("uptimeSeconds", ((SystemClock.elapsedRealtime() - appStartedAtMs) / 1000).toInt())
            put("appVersion", BuildConfig.VERSION_NAME)
            put("audioEnabled", audioEnabled)
            put("ttsReady", ttsReady)
            put("ttsStatus", lastTtsStatus)
            put("lastSuccessfulPageLoadSecondsAgo", if (lastPageOkAt > 0L) ((SystemClock.elapsedRealtime() - lastPageOkAt) / 1000).toInt() else -1)
            put("lastError", lastError)
        }
        val msg = JSONObject().apply {
            put("type", "heartbeat")
            put("telemetry", telemetry)
        }.toString()
        try {
            socket.send(msg)
        } catch (_: Throwable) {
        }
    }

    private fun applyImmersive() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun hasActivationToken(): Boolean {
        return !prefs.getString(PREF_ACTIVATION_TOKEN, null).isNullOrBlank()
    }

    private fun clearPairing() {
        prefs.edit().remove(PREF_ACTIVATION_TOKEN).apply()
        stopContentForPairing()
        disconnectWebSocket()
    }

    private fun ensureClientId(): String {
        val current = prefs.getString(PREF_CLIENT_ID, null)
        if (!current.isNullOrBlank()) return current
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_CLIENT_ID, created).apply()
        return created
    }

    private fun normalizeServerBase(input: String): String {
        val v = input.trim()
        if (v.isBlank()) return ""
        if (v.startsWith("http://") || v.startsWith("https://")) return v.trimEnd('/')
        return "http://${v.trimEnd('/')}"
    }

    private fun getServerBaseUrl(): String {
        return prefs.getString(PREF_SERVER_BASE_URL, "")?.trim().orEmpty()
    }

    private fun resolveServerUrl(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("/")) return getServerBaseUrl().trimEnd('/') + raw
        return raw
    }

    private fun getFallbackPanelUrl(): String {
        return prefs.getString(PREF_FALLBACK_URL, DEFAULT_FALLBACK_URL) ?: DEFAULT_FALLBACK_URL
    }

    private fun generateQrBitmap(text: String, size: Int): android.graphics.Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bmp
    }

    private fun getLocalIpv4Address(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress ?: ""
                }
            }
        } catch (_: Throwable) {
        }
        return ""
    }

    private data class SigssCall(
        val id: String,
        val patient: String,
        val room: String,
        val professional: String,
        val priority: String,
        val priorityColor: String,
        val updatedAt: String,
        val attempts: Int
    )

    private data class PriorityStyle(
        val badge: String,
        val accentColor: Int,
        val cardBackground: Int,
        val cardEndBackground: Int,
        val rowBackground: Int,
        val rowEndBackground: Int,
        val badgeBackground: Int,
        val strokeColor: Int,
        val pulseScale: Float
    )

    private data class NativeTheme(
        val backgroundStart: Int,
        val backgroundEnd: Int,
        val headerStart: Int,
        val headerEnd: Int,
        val headerStroke: Int,
        val logoStart: Int,
        val logoEnd: Int,
        val logoStroke: Int,
        val currentStart: Int,
        val currentEnd: Int,
        val currentStroke: Int,
        val historyStart: Int,
        val historyEnd: Int,
        val historyStroke: Int,
        val mediaStart: Int,
        val mediaEnd: Int,
        val mediaStroke: Int,
        val surfaceStart: Int,
        val surfaceEnd: Int,
        val surfaceStroke: Int,
        val titleColor: Int,
        val bodyColor: Int,
        val mutedColor: Int,
        val patientColor: Int,
        val patientSerif: Boolean,
        val chipTextColor: Int,
        val chipBackground: Int,
        val chipStroke: Int
    )

    private data class NativeThemeStyle(
        val rootPaddingDp: Int,
        val headerPadHDp: Int,
        val headerPadVDp: Int,
        val currentPadHDp: Int,
        val currentPadVDp: Int,
        val historyPadHDp: Int,
        val historyPadVDp: Int,
        val mediaPadHDp: Int,
        val mediaPadVDp: Int,
        val headerRadiusDp: Int,
        val currentRadiusDp: Int,
        val sideRadiusDp: Int,
        val rowRadiusDp: Int,
        val logoRadiusDp: Int,
        val headerStrokeWidthDp: Int,
        val currentStrokeWidthDp: Int,
        val sideStrokeWidthDp: Int,
        val headerElevationDp: Int,
        val currentElevationDp: Int,
        val sideElevationDp: Int,
        val patientSize: Float,
        val roomSize: Float,
        val professionalSize: Float,
        val prioritySize: Float,
        val updatedSize: Float,
        val historyTitleSize: Float,
        val historyPatientSize: Float,
        val historyDetailSize: Float,
        val mediaTitleSize: Float,
        val chipTextSize: Float,
        val chipLetterSpacing: Float,
        val chipPadHDp: Int,
        val chipPadVDp: Int,
        val currentNoMediaWeight: Float,
        val currentWithMediaWeight: Float,
        val sideNoMediaWeight: Float,
        val sideWithMediaWeight: Float,
        val historyWithMediaWeight: Float,
        val mediaWithMediaWeight: Float,
        val idleHistoryWeight: Float,
        val idleMediaWeight: Float,
        val historyRowPadHDp: Int,
        val historyRowPadVDp: Int,
        val historyRowSpacingDp: Int,
        val historyPatientUppercase: Boolean,
        val patientUppercase: Boolean,
        val useCurrentHint: Boolean,
        val priorityFullWidth: Boolean,
        val mediaTopMarginDp: Int,
        val animationBlinkAlpha: Float,
        val animationBlinkScale: Float,
        val animationPriorityScale: Float,
        val animationLabelScale: Float,
        val animationIntervalMs: Long,
        val animationPulseBoost: Float
    )

    companion object {
        private const val PREF_SERVER_BASE_URL = "pref_server_base_url"
        private const val PREF_FALLBACK_URL = "pref_fallback_url"
        private const val PREF_ACTIVATION_TOKEN = "pref_activation_token"
        private const val PREF_CLIENT_ID = "pref_client_id"

        private const val DEFAULT_FALLBACK_URL =
            "https://sigss.chapadaodoceu.go.gov.br/unique-panel/panel-screen/dedba94c-6ec7-4e60-9028-2167bccdf108"
    }
}

private fun hasValidatedNetwork(cm: ConnectivityManager): Boolean {
    val active = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(active) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
