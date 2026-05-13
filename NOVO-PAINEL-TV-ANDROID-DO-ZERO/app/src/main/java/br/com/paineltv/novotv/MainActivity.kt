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

        val header = LinearLayout(this)
        nativeHeaderCard = header
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(28), dp(24), dp(28), dp(24))
        header.background = createGradientCardDrawable(
            startColor = 0xFFFFFEFC.toInt(),
            endColor = 0xFFFBF6F0.toInt(),
            strokeColor = 0xFFE7DDD1.toInt(),
            strokeWidth = dp(1),
            radiusDp = 34
        )
        header.elevation = dp(9).toFloat()
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val headerTop = LinearLayout(this)
        headerTop.orientation = LinearLayout.HORIZONTAL
        headerTop.gravity = Gravity.CENTER_VERTICAL
        header.addView(headerTop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val brandWrap = LinearLayout(this)
        brandWrap.orientation = LinearLayout.HORIZONTAL
        brandWrap.gravity = Gravity.CENTER_VERTICAL
        headerTop.addView(brandWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        nativeUnitLogoView = ImageView(this)
        nativeUnitLogoView.scaleType = ImageView.ScaleType.CENTER_CROP
        nativeUnitLogoView.background = createGradientCardDrawable(
            startColor = 0xFFFFFEFC.toInt(),
            endColor = 0xFFF6EFE8.toInt(),
            strokeColor = 0xFFE8DDD1.toInt(),
            strokeWidth = dp(1),
            radiusDp = 24
        )
        nativeUnitLogoView.setPadding(dp(10), dp(10), dp(10), dp(10))
        nativeUnitLogoView.isVisible = false
        brandWrap.addView(nativeUnitLogoView, LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            marginEnd = dp(20)
        })

        val titleBlock = LinearLayout(this)
        titleBlock.orientation = LinearLayout.VERTICAL
        brandWrap.addView(titleBlock, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val eyebrow = TextView(this)
        eyebrow.text = "PAINEL DE ATENDIMENTO DIGITAL"
        eyebrow.setTextColor(0xFF2A2E31.toInt())
        eyebrow.textSize = 11f
        eyebrow.typeface = Typeface.DEFAULT_BOLD
        eyebrow.letterSpacing = 0.08f
        eyebrow.isVisible = false
        titleBlock.addView(eyebrow)

        nativeUnitNameText = TextView(this)
        nativeUnitNameText.text = "Painel de chamados"
        nativeUnitNameText.setTextColor(0xFF1E1F20.toInt())
        nativeUnitNameText.textSize = 30f
        nativeUnitNameText.typeface = Typeface.DEFAULT_BOLD
        nativeUnitNameText.maxLines = 2
        nativeUnitNameText.setLineSpacing(0f, 0.98f)
        titleBlock.addView(nativeUnitNameText)

        nativeUnitSubtitleText = TextView(this)
        nativeUnitSubtitleText.text = "Fluxo de paciente inteligente focado na prioridade e clareza visual"
        nativeUnitSubtitleText.setTextColor(0xFF5B5751.toInt())
        nativeUnitSubtitleText.textSize = 14f
        nativeUnitSubtitleText.setLineSpacing(0f, 1.1f)
        nativeUnitSubtitleText.isVisible = false
        titleBlock.addView(nativeUnitSubtitleText)

        nativeModeText = TextView(this)
        nativeModeText.text = "Aguardando configuracao do painel"
        nativeModeText.setTextColor(0xFF7A756E.toInt())
        nativeModeText.textSize = 0f
        nativeModeText.setLineSpacing(0f, 1.1f)
        nativeModeText.setPadding(0, dp(10), 0, 0)
        nativeModeText.isVisible = false
        titleBlock.addView(nativeModeText)

        val statusWrap = LinearLayout(this)
        statusWrap.orientation = LinearLayout.VERTICAL
        statusWrap.gravity = Gravity.END
        statusWrap.background = createGradientCardDrawable(
            startColor = 0xFFFFFDFC.toInt(),
            endColor = 0xFFF8F4EE.toInt(),
            strokeColor = 0xFFE9E0D6.toInt(),
            strokeWidth = dp(1),
            radiusDp = 26
        )
        statusWrap.setPadding(dp(18), dp(16), dp(18), dp(16))
        statusWrap.isVisible = false
        headerTop.addView(statusWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(18)
        })

        val statusTitle = TextView(this)
        statusTitle.text = "STATUS DO PAINEL"
        statusTitle.setTextColor(0xFF8A8178.toInt())
        statusTitle.textSize = 11f
        statusTitle.typeface = Typeface.DEFAULT_BOLD
        statusTitle.letterSpacing = 0.18f
        statusTitle.gravity = Gravity.END
        statusTitle.isVisible = false
        statusWrap.addView(statusTitle)

        nativeStatusText = TextView(this)
        nativeStatusText.text = "Aguardando dados"
        nativeStatusText.setTextColor(0xFF1B587D.toInt())
        nativeStatusText.textSize = 14f
        nativeStatusText.typeface = Typeface.DEFAULT_BOLD
        nativeStatusText.gravity = Gravity.CENTER
        nativeStatusText.setPadding(dp(18), dp(12), dp(18), dp(12))
        nativeStatusText.minWidth = dp(220)
        statusWrap.addView(nativeStatusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        })
        setNativeStatus("Aguardando dados", 0xFF205A7A.toInt(), 0xFFE6F4FA.toInt())

        nativeBodyRow = LinearLayout(this)
        nativeBodyRow.orientation = LinearLayout.HORIZONTAL
        nativeBodyRow.setPadding(0, dp(20), 0, 0)
        root.addView(nativeBodyRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        nativeCurrentCard = LinearLayout(this)
        nativeCurrentCard.orientation = LinearLayout.VERTICAL
        nativeCurrentCard.setPadding(dp(34), dp(30), dp(34), dp(30))
        nativeCurrentCard.background = createGradientCardDrawable(
            startColor = 0xFFFFFEFC.toInt(),
            endColor = 0xFFFBF5EE.toInt(),
            strokeColor = 0xFFD0B38D.toInt(),
            strokeWidth = dp(1),
            radiusDp = 36
        )
        nativeCurrentCard.elevation = dp(10).toFloat()
        nativeBodyRow.addView(nativeCurrentCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.28f).apply {
            marginEnd = dp(20)
        })

        val currentHeader = LinearLayout(this)
        currentHeader.orientation = LinearLayout.HORIZONTAL
        currentHeader.gravity = Gravity.CENTER_VERTICAL
        nativeCurrentCard.addView(currentHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        nativeCurrentLabelText = TextView(this)
        nativeCurrentLabelText.text = "CHAMADA ATIVA"
        nativeCurrentLabelText.setTextColor(0xFF7A4A08.toInt())
        nativeCurrentLabelText.textSize = 13f
        nativeCurrentLabelText.typeface = Typeface.DEFAULT_BOLD
        nativeCurrentLabelText.letterSpacing = 0.16f
        nativeCurrentLabelText.setPadding(dp(18), dp(11), dp(18), dp(11))
        nativeCurrentLabelText.background = createPillDrawable(0xFFFFE1B8.toInt(), 0xFFF2C784.toInt())
        currentHeader.addView(nativeCurrentLabelText)

        val currentHeaderHint = TextView(this)
        nativeCurrentHintText = currentHeaderHint
        currentHeaderHint.text = "Fluxo ativo em tempo real"
        currentHeaderHint.setTextColor(0xFF6B6761.toInt())
        currentHeaderHint.textSize = 14f
        currentHeaderHint.gravity = Gravity.END
        currentHeader.addView(currentHeaderHint, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        nativePatientText = TextView(this)
        nativePatientText.text = "--"
        nativePatientText.setTextColor(0xFF1E1F20.toInt())
        nativePatientText.textSize = 56f
        nativePatientText.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        nativePatientText.gravity = Gravity.CENTER_VERTICAL
        nativePatientText.maxLines = 3
        nativePatientText.setLineSpacing(0f, 0.95f)
        nativeCurrentCard.addView(nativePatientText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply {
            topMargin = dp(26)
            bottomMargin = dp(10)
        })

        val summaryBand = LinearLayout(this)
        summaryBand.orientation = LinearLayout.VERTICAL
        summaryBand.setPadding(dp(22), dp(20), dp(22), dp(20))
        summaryBand.background = createGradientCardDrawable(
            startColor = 0xFFFFFEFC.toInt(),
            endColor = 0xFFF8F4EE.toInt(),
            strokeColor = 0xFFE5DCD2.toInt(),
            strokeWidth = dp(1),
            radiusDp = 28
        )
        nativeCurrentCard.addView(summaryBand, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        nativeRoomText = TextView(this)
        nativeRoomText.text = "Sala: --"
        nativeRoomText.setTextColor(0xFF222222.toInt())
        nativeRoomText.textSize = 30f
        nativeRoomText.gravity = Gravity.START
        nativeRoomText.typeface = Typeface.DEFAULT_BOLD
        summaryBand.addView(nativeRoomText)

        nativeProfessionalText = TextView(this)
        nativeProfessionalText.text = "Profissional: --"
        nativeProfessionalText.setTextColor(0xFF3B3A39.toInt())
        nativeProfessionalText.textSize = 19f
        nativeProfessionalText.gravity = Gravity.START
        nativeProfessionalText.setLineSpacing(0f, 1.08f)
        summaryBand.addView(nativeProfessionalText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
        })

        nativePriorityText = TextView(this)
        nativePriorityText.text = ""
        nativePriorityText.setTextColor(0xFF4F5F70.toInt())
        nativePriorityText.textSize = 18f
        nativePriorityText.typeface = Typeface.DEFAULT_BOLD
        nativePriorityText.gravity = Gravity.CENTER
        nativePriorityText.setPadding(dp(18), dp(12), dp(18), dp(12))
        nativeCurrentCard.addView(nativePriorityText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START
            topMargin = dp(18)
        })

        nativeUpdatedText = TextView(this)
        nativeUpdatedText.text = ""
        nativeUpdatedText.setTextColor(0xFF69645E.toInt())
        nativeUpdatedText.textSize = 15f
        nativeUpdatedText.gravity = Gravity.START
        nativeCurrentCard.addView(nativeUpdatedText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(14)
        })

        nativeSideColumn = LinearLayout(this)
        nativeSideColumn.orientation = LinearLayout.VERTICAL
        nativeBodyRow.addView(nativeSideColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.02f))

        nativeHistoryCard = LinearLayout(this)
        nativeHistoryCard.orientation = LinearLayout.VERTICAL
        nativeHistoryCard.setPadding(dp(26), dp(24), dp(26), dp(24))
        nativeHistoryCard.background = createGradientCardDrawable(
            startColor = 0xFFFFFEFC.toInt(),
            endColor = 0xFFFBF6F0.toInt(),
            strokeColor = 0xFFE4D8CA.toInt(),
            strokeWidth = dp(1),
            radiusDp = 32
        )
        nativeHistoryCard.elevation = dp(8).toFloat()
        nativeSideColumn.addView(nativeHistoryCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            0.42f
        ))

        val lastLabel = TextView(this)
        nativeHistoryTitleText = lastLabel
        lastLabel.text = "ULTIMAS CHAMADAS"
        lastLabel.setTextColor(0xFF202020.toInt())
        lastLabel.textSize = 24f
        lastLabel.typeface = Typeface.DEFAULT_BOLD
        nativeHistoryCard.addView(lastLabel)

        nativeHistoryHintText = TextView(this)
        nativeHistoryHintText.text = "Historico recente com leitura rapida para equipe, recepcao e pacientes em espera."
        nativeHistoryHintText.setTextColor(0xFF625F5A.toInt())
        nativeHistoryHintText.textSize = 14f
        nativeHistoryHintText.setLineSpacing(0f, 1.08f)
        nativeHistoryCard.addView(nativeHistoryHintText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            bottomMargin = dp(10)
        })

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        nativeList = LinearLayout(this)
        nativeList.orientation = LinearLayout.VERTICAL
        scroll.addView(nativeList)
        nativeHistoryCard.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        nativeMediaCard = LinearLayout(this)
        nativeMediaCard.orientation = LinearLayout.VERTICAL
        nativeMediaCard.setPadding(dp(24), dp(24), dp(24), dp(24))
        nativeMediaCard.background = createGradientCardDrawable(
            startColor = 0xFFFFFEFC.toInt(),
            endColor = 0xFFFAF4EC.toInt(),
            strokeColor = 0xFFE3D8CB.toInt(),
            strokeWidth = dp(1),
            radiusDp = 32
        )
        nativeMediaCard.elevation = dp(8).toFloat()
        nativeMediaCard.isVisible = false
        nativeSideColumn.addView(nativeMediaCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            0.58f
        ).apply {
            topMargin = dp(18)
        })

        val mediaLabel = TextView(this)
        nativeMediaTitleText = mediaLabel
        mediaLabel.text = "MIDIA DA UNIDADE"
        mediaLabel.setTextColor(0xFF202020.toInt())
        mediaLabel.textSize = 24f
        mediaLabel.typeface = Typeface.DEFAULT_BOLD
        nativeMediaCard.addView(mediaLabel)

        nativeMediaHintText = TextView(this)
        nativeMediaHintText.text = "Video, orientacao institucional ou comunicados visuais para a sala."
        nativeMediaHintText.setTextColor(0xFF625F5A.toInt())
        nativeMediaHintText.textSize = 14f
        nativeMediaHintText.setLineSpacing(0f, 1.08f)
        nativeMediaCard.addView(nativeMediaHintText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            bottomMargin = dp(10)
        })

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
        nativeMediaCard.addView(created, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply {
            topMargin = dp(14)
        })
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
        calls.take(maxHistoryItems()).forEach { call ->
            nativeList.addView(createNativeCallRow(call))
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
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(
            dp(visualStyle.historyRowPadHDp),
            dp(visualStyle.historyRowPadVDp),
            dp(visualStyle.historyRowPadHDp),
            dp(visualStyle.historyRowPadVDp)
        )
        row.background = createGradientCardDrawable(
            startColor = priorityStyle.rowBackground,
            endColor = priorityStyle.rowEndBackground,
            strokeColor = priorityStyle.strokeColor,
            strokeWidth = dp(visualStyle.sideStrokeWidthDp),
            radiusDp = visualStyle.rowRadiusDp
        )
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(visualStyle.historyRowSpacingDp)
        }

        val patient = TextView(this)
        patient.text = themedDisplayText(call.patient.ifBlank { "--" }, visualStyle.historyPatientUppercase)
        patient.setTextColor(theme.titleColor)
        patient.textSize = visualStyle.historyPatientSize
        patient.typeface = if (theme.patientSerif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        patient.maxLines = 2
        patient.ellipsize = TextUtils.TruncateAt.END
        row.addView(patient)

        if (priorityStyle.badge.isNotBlank()) {
            val badge = TextView(this)
            badge.text = priorityStyle.badge
            badge.setTextColor(priorityStyle.accentColor)
            badge.textSize = 11f
            badge.typeface = Typeface.DEFAULT_BOLD
            badge.setPadding(dp(10), dp(6), dp(10), dp(6))
            badge.background = createPillDrawable(priorityStyle.badgeBackground, priorityStyle.strokeColor)
            row.addView(badge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            })
        }

        val detail = TextView(this)
        detail.text = buildString {
            append(call.room.ifBlank { "--" })
            if (call.professional.isNotBlank()) append(" | ").append(call.professional)
            if (call.updatedAt.isNotBlank()) append("\n").append(formatSigssTime(call.updatedAt))
        }
        detail.setTextColor(theme.mutedColor)
        detail.textSize = visualStyle.historyDetailSize
        detail.setLineSpacing(0f, 1.08f)
        detail.maxLines = 3
        detail.ellipsize = TextUtils.TruncateAt.END
        row.addView(detail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })

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
            "sus_institucional" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFB43A4A.toInt(), 0xFFFFF4F6.toInt(), 0xFFFFE7EB.toInt(), 0xFFFFFAFB.toInt(), 0xFFFFEFF2.toInt(), 0xFFFFD9E0.toInt(), 0xFFF0B9C4.toInt(), 1.05f)
                "urgent" -> PriorityStyle("URGENTE", 0xFF9A6800.toInt(), 0xFFFFFAEE.toInt(), 0xFFFFEFD1.toInt(), 0xFFFFFCF5.toInt(), 0xFFFFF4E1.toInt(), 0xFFFFE6B3.toInt(), 0xFFEBCB84.toInt(), 1.04f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF007F6D.toInt(), 0xFFF1FBF8.toInt(), 0xFFE0F5EF.toInt(), 0xFFF7FCFA.toInt(), 0xFFEBF8F3.toInt(), 0xFFD3EEE5.toInt(), 0xFFB5DED0.toInt(), 1.024f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF005A8D.toInt(), 0xFFFFFFFF.toInt(), 0xFFEFF7FF.toInt(), 0xFFF8FBFE.toInt(), 0xFFEAF4FB.toInt(), 0xFFD5E7F5.toInt(), 0xFFBDD6E9.toInt(), 1.024f)
            }
            "tech_light" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFB52E3C.toInt(), 0xFFFFF3F5.toInt(), 0xFFFFE5EA.toInt(), 0xFFFFF7F9.toInt(), 0xFFFFEEF2.toInt(), 0xFFFFDDE5.toInt(), 0xFFF1C5CE.toInt(), 1.04f)
                "urgent" -> PriorityStyle("URGENTE", 0xFF9D6500.toInt(), 0xFFFFF9ED.toInt(), 0xFFFFEFD7.toInt(), 0xFFFFFCF4.toInt(), 0xFFFFF4E4.toInt(), 0xFFFFE7BF.toInt(), 0xFFF0D5A0.toInt(), 1.03f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF18727C.toInt(), 0xFFF2FBFD.toInt(), 0xFFE6F5FA.toInt(), 0xFFF7FCFE.toInt(), 0xFFEEF8FB.toInt(), 0xFFDDF0F5.toInt(), 0xFFC8E0EA.toInt(), 1.016f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF15648B.toInt(), 0xFFFFFFFF.toInt(), 0xFFF1F7FF.toInt(), 0xFFF8FBFF.toInt(), 0xFFEFF5FF.toInt(), 0xFFE1EFFB.toInt(), 0xFFD2E2F4.toInt(), 1.016f)
            }
            "hospital_blue" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFB34C3F.toInt(), 0xFFFFF7F4.toInt(), 0xFFFFECE7.toInt(), 0xFFFFFAF8.toInt(), 0xFFFFF1EC.toInt(), 0xFFFFE3DB.toInt(), 0xFFE6C6BC.toInt(), 1.04f)
                "urgent" -> PriorityStyle("URGENTE", 0xFF89621A.toInt(), 0xFFFFFAF1.toInt(), 0xFFFFF0DC.toInt(), 0xFFFFFCF6.toInt(), 0xFFFFF6E7.toInt(), 0xFFFFE9CD.toInt(), 0xFFE5D2AB.toInt(), 1.03f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF386B7F.toInt(), 0xFFF4FBFD.toInt(), 0xFFEAF5F9.toInt(), 0xFFF8FCFD.toInt(), 0xFFF0F7FA.toInt(), 0xFFE2EDF1.toInt(), 0xFFCDDEE6.toInt(), 1.016f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF205D7F.toInt(), 0xFFFFFFFF.toInt(), 0xFFF3FAFF.toInt(), 0xFFF8FCFE.toInt(), 0xFFF0F7FB.toInt(), 0xFFE3EFF5.toInt(), 0xFFD1E2EC.toInt(), 1.016f)
            }
            "contrast_gold" -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFAA4636.toInt(), 0xFFFFF6F2.toInt(), 0xFFFFE9E0.toInt(), 0xFFFFFAF7.toInt(), 0xFFFFF0E9.toInt(), 0xFFFFE0D3.toInt(), 0xFFE5BFAF.toInt(), 1.04f)
                "urgent" -> PriorityStyle("URGENTE", 0xFF7A530C.toInt(), 0xFFFFFAEF.toInt(), 0xFFFFEFD5.toInt(), 0xFFFFFCF5.toInt(), 0xFFFFF3E1.toInt(), 0xFFFFE5BB.toInt(), 0xFFE2C98F.toInt(), 1.03f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF665A46.toInt(), 0xFFFFFCF9.toInt(), 0xFFF6EFE4.toInt(), 0xFFFFFDFA.toInt(), 0xFFF9F2E8.toInt(), 0xFFECE1D1.toInt(), 0xFFDACCB7.toInt(), 1.016f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF744705.toInt(), 0xFFFFFEFB.toInt(), 0xFFFBF2E2.toInt(), 0xFFFFFDF8.toInt(), 0xFFF8F1E4.toInt(), 0xFFFFE2AF.toInt(), 0xFFE3C178.toInt(), 1.016f)
            }
            else -> when (level) {
                "emergency" -> PriorityStyle("EMERGENCIA", 0xFFB44734.toInt(), 0xFFFFF7F3.toInt(), 0xFFFFECE5.toInt(), 0xFFFFFAF7.toInt(), 0xFFFFF1EA.toInt(), 0xFFFFE4D8.toInt(), 0xFFEBC7B8.toInt(), 1.04f)
                "urgent" -> PriorityStyle("URGENTE", 0xFF8A5A11.toInt(), 0xFFFFFAF0.toInt(), 0xFFFFF0D9.toInt(), 0xFFFFFCF6.toInt(), 0xFFFFF5E5.toInt(), 0xFFFFE9C7.toInt(), 0xFFE8D0A5.toInt(), 1.03f)
                "normal" -> PriorityStyle("ATENDIMENTO NORMAL", 0xFF4A6D64.toInt(), 0xFFFFFCF9.toInt(), 0xFFF4EFE8.toInt(), 0xFFFFFDFA.toInt(), 0xFFF8F2EB.toInt(), 0xFFECE4D9.toInt(), 0xFFDED1C1.toInt(), 1.016f)
                else -> PriorityStyle("CHAMADO EM PAINEL", 0xFF7A4A08.toInt(), 0xFFFFFEFC.toInt(), 0xFFFBF5EE.toInt(), 0xFFFFFCFA.toInt(), 0xFFF8F2EB.toInt(), 0xFFFFE5C6.toInt(), 0xFFE7D5BC.toInt(), 1.016f)
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
            "sus_institucional" -> NativeTheme(
                backgroundStart = 0xFFF4FAFF.toInt(),
                backgroundEnd = 0xFFE6F3FF.toInt(),
                headerStart = 0xFFFFFFFF.toInt(),
                headerEnd = 0xFFF0F8FF.toInt(),
                headerStroke = 0xFFCBDFF0.toInt(),
                logoStart = 0xFFF8FDFF.toInt(),
                logoEnd = 0xFFE9F5FF.toInt(),
                logoStroke = 0xFFCFE2F2.toInt(),
                currentStart = 0xFFFFFFFF.toInt(),
                currentEnd = 0xFFF1F9FF.toInt(),
                currentStroke = 0xFFBFD9EB.toInt(),
                historyStart = 0xFFFFFFFF.toInt(),
                historyEnd = 0xFFF1F8FD.toInt(),
                historyStroke = 0xFFCFE0EB.toInt(),
                mediaStart = 0xFFFFFFFF.toInt(),
                mediaEnd = 0xFFEFF7FC.toInt(),
                mediaStroke = 0xFFCDE0EA.toInt(),
                surfaceStart = 0xFFF8FCFF.toInt(),
                surfaceEnd = 0xFFEDF7FC.toInt(),
                surfaceStroke = 0xFFD4E3EB.toInt(),
                titleColor = 0xFF07395C.toInt(),
                bodyColor = 0xFF25536B.toInt(),
                mutedColor = 0xFF5F7D8F.toInt(),
                patientColor = 0xFF072F4A.toInt(),
                patientSerif = false,
                chipTextColor = 0xFF005A8D.toInt(),
                chipBackground = 0xFFE4F4FF.toInt(),
                chipStroke = 0xFFC5E0F3.toInt()
            )
            "tech_light" -> NativeTheme(
                backgroundStart = 0xFFF5FAFF.toInt(),
                backgroundEnd = 0xFFE8F1FF.toInt(),
                headerStart = 0xFFFFFFFF.toInt(),
                headerEnd = 0xFFF0F6FF.toInt(),
                headerStroke = 0xFFD2E0F4.toInt(),
                logoStart = 0xFFF8FBFF.toInt(),
                logoEnd = 0xFFEAF2FF.toInt(),
                logoStroke = 0xFFD7E3F5.toInt(),
                currentStart = 0xFFFFFFFF.toInt(),
                currentEnd = 0xFFF2F7FF.toInt(),
                currentStroke = 0xFFCCDCF1.toInt(),
                historyStart = 0xFFFFFFFF.toInt(),
                historyEnd = 0xFFF3F8FF.toInt(),
                historyStroke = 0xFFD5E2F2.toInt(),
                mediaStart = 0xFFFFFFFF.toInt(),
                mediaEnd = 0xFFF0F6FF.toInt(),
                mediaStroke = 0xFFD5E2F2.toInt(),
                surfaceStart = 0xFFF8FBFF.toInt(),
                surfaceEnd = 0xFFEEF4FF.toInt(),
                surfaceStroke = 0xFFD8E3F2.toInt(),
                titleColor = 0xFF17283C.toInt(),
                bodyColor = 0xFF274560.toInt(),
                mutedColor = 0xFF6C8096.toInt(),
                patientColor = 0xFF13273B.toInt(),
                patientSerif = false,
                chipTextColor = 0xFF145F89.toInt(),
                chipBackground = 0xFFE4F3FF.toInt(),
                chipStroke = 0xFFCFE3F8.toInt()
            )
            "hospital_blue" -> NativeTheme(
                backgroundStart = 0xFFF4FBFF.toInt(),
                backgroundEnd = 0xFFEAF5FC.toInt(),
                headerStart = 0xFFFFFFFF.toInt(),
                headerEnd = 0xFFF3FAFF.toInt(),
                headerStroke = 0xFFD4E4EF.toInt(),
                logoStart = 0xFFF8FDFF.toInt(),
                logoEnd = 0xFFEEF7FB.toInt(),
                logoStroke = 0xFFD8E6EE.toInt(),
                currentStart = 0xFFFFFFFF.toInt(),
                currentEnd = 0xFFF4FAFF.toInt(),
                currentStroke = 0xFFC9DCE8.toInt(),
                historyStart = 0xFFFFFFFF.toInt(),
                historyEnd = 0xFFF5FAFD.toInt(),
                historyStroke = 0xFFD6E3EA.toInt(),
                mediaStart = 0xFFFFFFFF.toInt(),
                mediaEnd = 0xFFF2F8FC.toInt(),
                mediaStroke = 0xFFD6E3EA.toInt(),
                surfaceStart = 0xFFF9FDFF.toInt(),
                surfaceEnd = 0xFFF0F7FA.toInt(),
                surfaceStroke = 0xFFD8E3E9.toInt(),
                titleColor = 0xFF18303F.toInt(),
                bodyColor = 0xFF274B62.toInt(),
                mutedColor = 0xFF6C8191.toInt(),
                patientColor = 0xFF183345.toInt(),
                patientSerif = false,
                chipTextColor = 0xFF1A698B.toInt(),
                chipBackground = 0xFFE2F4FB.toInt(),
                chipStroke = 0xFFCBE5F1.toInt()
            )
            "contrast_gold" -> NativeTheme(
                backgroundStart = 0xFFFFFCF6.toInt(),
                backgroundEnd = 0xFFF7F0E0.toInt(),
                headerStart = 0xFFFFFEFB.toInt(),
                headerEnd = 0xFFFBF3E6.toInt(),
                headerStroke = 0xFFE0CCAB.toInt(),
                logoStart = 0xFFFFFEFB.toInt(),
                logoEnd = 0xFFF7EEDB.toInt(),
                logoStroke = 0xFFE2D0AE.toInt(),
                currentStart = 0xFFFFFEFB.toInt(),
                currentEnd = 0xFFFBF2E2.toInt(),
                currentStroke = 0xFFD3B783.toInt(),
                historyStart = 0xFFFFFEFB.toInt(),
                historyEnd = 0xFFF8F1E3.toInt(),
                historyStroke = 0xFFDBC7A0.toInt(),
                mediaStart = 0xFFFFFEFB.toInt(),
                mediaEnd = 0xFFF7EFDF.toInt(),
                mediaStroke = 0xFFDBC7A0.toInt(),
                surfaceStart = 0xFFFFFDF8.toInt(),
                surfaceEnd = 0xFFF8F1E4.toInt(),
                surfaceStroke = 0xFFE0D1B6.toInt(),
                titleColor = 0xFF211B13.toInt(),
                bodyColor = 0xFF423429.toInt(),
                mutedColor = 0xFF786B5B.toInt(),
                patientColor = 0xFF241C12.toInt(),
                patientSerif = true,
                chipTextColor = 0xFF744705.toInt(),
                chipBackground = 0xFFFFDFAB.toInt(),
                chipStroke = 0xFFEAC070.toInt()
            )
            else -> NativeTheme(
                backgroundStart = 0xFFFFFCF8.toInt(),
                backgroundEnd = 0xFFF8F2EA.toInt(),
                headerStart = 0xFFFFFEFC.toInt(),
                headerEnd = 0xFFFBF6F0.toInt(),
                headerStroke = 0xFFE7DDD1.toInt(),
                logoStart = 0xFFFFFEFC.toInt(),
                logoEnd = 0xFFF6EFE8.toInt(),
                logoStroke = 0xFFE8DDD1.toInt(),
                currentStart = 0xFFFFFEFC.toInt(),
                currentEnd = 0xFFFBF5EE.toInt(),
                currentStroke = 0xFFD0B38D.toInt(),
                historyStart = 0xFFFFFEFC.toInt(),
                historyEnd = 0xFFFBF6F0.toInt(),
                historyStroke = 0xFFE4D8CA.toInt(),
                mediaStart = 0xFFFFFEFC.toInt(),
                mediaEnd = 0xFFFAF4EC.toInt(),
                mediaStroke = 0xFFE3D8CB.toInt(),
                surfaceStart = 0xFFFFFEFC.toInt(),
                surfaceEnd = 0xFFF8F4EE.toInt(),
                surfaceStroke = 0xFFE5DCD2.toInt(),
                titleColor = 0xFF202020.toInt(),
                bodyColor = 0xFF3B3A39.toInt(),
                mutedColor = 0xFF69645E.toInt(),
                patientColor = 0xFF1E1F20.toInt(),
                patientSerif = true,
                chipTextColor = 0xFF7A4A08.toInt(),
                chipBackground = 0xFFFFE1B8.toInt(),
                chipStroke = 0xFFF2C784.toInt()
            )
        }
    }

    private fun currentThemeStyle(): NativeThemeStyle {
        return when (currentThemeId) {
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
            "tech_light" -> NativeThemeStyle(
                rootPaddingDp = 16,
                headerPadHDp = 24,
                headerPadVDp = 20,
                currentPadHDp = 30,
                currentPadVDp = 26,
                historyPadHDp = 20,
                historyPadVDp = 20,
                mediaPadHDp = 20,
                mediaPadVDp = 18,
                headerRadiusDp = 24,
                currentRadiusDp = 28,
                sideRadiusDp = 22,
                rowRadiusDp = 18,
                logoRadiusDp = 18,
                headerStrokeWidthDp = 1,
                currentStrokeWidthDp = 1,
                sideStrokeWidthDp = 1,
                headerElevationDp = 8,
                currentElevationDp = 10,
                sideElevationDp = 7,
                patientSize = 50f,
                roomSize = 28f,
                professionalSize = 18f,
                prioritySize = 18f,
                updatedSize = 14f,
                historyTitleSize = 23f,
                historyPatientSize = 18f,
                historyDetailSize = 12.5f,
                mediaTitleSize = 23f,
                chipTextSize = 12f,
                chipLetterSpacing = 0.24f,
                chipPadHDp = 15,
                chipPadVDp = 10,
                currentNoMediaWeight = 1.36f,
                currentWithMediaWeight = 0.98f,
                sideNoMediaWeight = 0.94f,
                sideWithMediaWeight = 1.24f,
                historyWithMediaWeight = 0.34f,
                mediaWithMediaWeight = 0.82f,
                idleHistoryWeight = 0.72f,
                idleMediaWeight = 1.3f,
                historyRowPadHDp = 14,
                historyRowPadVDp = 12,
                historyRowSpacingDp = 8,
                historyPatientUppercase = true,
                patientUppercase = false,
                useCurrentHint = true,
                priorityFullWidth = true,
                mediaTopMarginDp = 12,
                animationBlinkAlpha = 0.78f,
                animationBlinkScale = 1.03f,
                animationPriorityScale = 1.14f,
                animationLabelScale = 1.07f,
                animationIntervalMs = 250L,
                animationPulseBoost = 0.03f
            )
            "hospital_blue" -> NativeThemeStyle(
                rootPaddingDp = 18,
                headerPadHDp = 26,
                headerPadVDp = 22,
                currentPadHDp = 30,
                currentPadVDp = 28,
                historyPadHDp = 22,
                historyPadVDp = 22,
                mediaPadHDp = 22,
                mediaPadVDp = 22,
                headerRadiusDp = 28,
                currentRadiusDp = 30,
                sideRadiusDp = 28,
                rowRadiusDp = 22,
                logoRadiusDp = 20,
                headerStrokeWidthDp = 1,
                currentStrokeWidthDp = 1,
                sideStrokeWidthDp = 1,
                headerElevationDp = 7,
                currentElevationDp = 9,
                sideElevationDp = 7,
                patientSize = 54f,
                roomSize = 29f,
                professionalSize = 18f,
                prioritySize = 18f,
                updatedSize = 15f,
                historyTitleSize = 22f,
                historyPatientSize = 18f,
                historyDetailSize = 13f,
                mediaTitleSize = 22f,
                chipTextSize = 12f,
                chipLetterSpacing = 0.18f,
                chipPadHDp = 16,
                chipPadVDp = 10,
                currentNoMediaWeight = 1.48f,
                currentWithMediaWeight = 1.04f,
                sideNoMediaWeight = 0.86f,
                sideWithMediaWeight = 1.18f,
                historyWithMediaWeight = 0.36f,
                mediaWithMediaWeight = 0.76f,
                idleHistoryWeight = 0.74f,
                idleMediaWeight = 1.26f,
                historyRowPadHDp = 15,
                historyRowPadVDp = 13,
                historyRowSpacingDp = 9,
                historyPatientUppercase = false,
                patientUppercase = false,
                useCurrentHint = true,
                priorityFullWidth = false,
                mediaTopMarginDp = 14,
                animationBlinkAlpha = 0.76f,
                animationBlinkScale = 1.026f,
                animationPriorityScale = 1.1f,
                animationLabelScale = 1.05f,
                animationIntervalMs = 280L,
                animationPulseBoost = 0.024f
            )
            "contrast_gold" -> NativeThemeStyle(
                rootPaddingDp = 24,
                headerPadHDp = 30,
                headerPadVDp = 26,
                currentPadHDp = 38,
                currentPadVDp = 34,
                historyPadHDp = 26,
                historyPadVDp = 24,
                mediaPadHDp = 24,
                mediaPadVDp = 24,
                headerRadiusDp = 38,
                currentRadiusDp = 42,
                sideRadiusDp = 34,
                rowRadiusDp = 24,
                logoRadiusDp = 24,
                headerStrokeWidthDp = 1,
                currentStrokeWidthDp = 1,
                sideStrokeWidthDp = 1,
                headerElevationDp = 10,
                currentElevationDp = 12,
                sideElevationDp = 8,
                patientSize = 60f,
                roomSize = 31f,
                professionalSize = 19f,
                prioritySize = 19f,
                updatedSize = 15f,
                historyTitleSize = 24f,
                historyPatientSize = 19f,
                historyDetailSize = 13f,
                mediaTitleSize = 24f,
                chipTextSize = 13f,
                chipLetterSpacing = 0.2f,
                chipPadHDp = 18,
                chipPadVDp = 11,
                currentNoMediaWeight = 1.56f,
                currentWithMediaWeight = 1.12f,
                sideNoMediaWeight = 0.8f,
                sideWithMediaWeight = 1.08f,
                historyWithMediaWeight = 0.4f,
                mediaWithMediaWeight = 0.68f,
                idleHistoryWeight = 0.84f,
                idleMediaWeight = 1.18f,
                historyRowPadHDp = 16,
                historyRowPadVDp = 14,
                historyRowSpacingDp = 10,
                historyPatientUppercase = false,
                patientUppercase = false,
                useCurrentHint = false,
                priorityFullWidth = false,
                mediaTopMarginDp = 16,
                animationBlinkAlpha = 0.74f,
                animationBlinkScale = 1.03f,
                animationPriorityScale = 1.13f,
                animationLabelScale = 1.06f,
                animationIntervalMs = 300L,
                animationPulseBoost = 0.032f
            )
            else -> NativeThemeStyle(
                rootPaddingDp = 22,
                headerPadHDp = 28,
                headerPadVDp = 24,
                currentPadHDp = 34,
                currentPadVDp = 30,
                historyPadHDp = 26,
                historyPadVDp = 24,
                mediaPadHDp = 24,
                mediaPadVDp = 24,
                headerRadiusDp = 34,
                currentRadiusDp = 36,
                sideRadiusDp = 32,
                rowRadiusDp = 24,
                logoRadiusDp = 24,
                headerStrokeWidthDp = 1,
                currentStrokeWidthDp = 1,
                sideStrokeWidthDp = 1,
                headerElevationDp = 9,
                currentElevationDp = 10,
                sideElevationDp = 8,
                patientSize = 58f,
                roomSize = 30f,
                professionalSize = 19f,
                prioritySize = 18f,
                updatedSize = 15f,
                historyTitleSize = 24f,
                historyPatientSize = 19f,
                historyDetailSize = 13f,
                mediaTitleSize = 24f,
                chipTextSize = 13f,
                chipLetterSpacing = 0.18f,
                chipPadHDp = 18,
                chipPadVDp = 11,
                currentNoMediaWeight = 1.52f,
                currentWithMediaWeight = 1.08f,
                sideNoMediaWeight = 0.82f,
                sideWithMediaWeight = 1.16f,
                historyWithMediaWeight = 0.38f,
                mediaWithMediaWeight = 0.72f,
                idleHistoryWeight = 0.78f,
                idleMediaWeight = 1.22f,
                historyRowPadHDp = 16,
                historyRowPadVDp = 14,
                historyRowSpacingDp = 10,
                historyPatientUppercase = false,
                patientUppercase = false,
                useCurrentHint = false,
                priorityFullWidth = false,
                mediaTopMarginDp = 16,
                animationBlinkAlpha = 0.72f,
                animationBlinkScale = 1.024f,
                animationPriorityScale = 1.09f,
                animationLabelScale = 1.05f,
                animationIntervalMs = 290L,
                animationPulseBoost = 0.018f
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
            val size = if (currentThemeId == "sus_institucional") dp(78) else if (currentThemeId == "tech_light") dp(72) else dp(84)
            params.width = size
            params.height = size
            nativeUnitLogoView.layoutParams = params
        }
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
        nativeMediaCard.setPadding(
            dp(visualStyle.mediaPadHDp),
            dp(visualStyle.mediaPadVDp),
            dp(visualStyle.mediaPadHDp),
            dp(visualStyle.mediaPadVDp)
        )
        nativeMediaCard.background = createGradientCardDrawable(
            startColor = theme.mediaStart,
            endColor = theme.mediaEnd,
            strokeColor = theme.mediaStroke,
            strokeWidth = dp(visualStyle.sideStrokeWidthDp),
            radiusDp = visualStyle.sideRadiusDp
        )
        nativeMediaCard.elevation = dp(visualStyle.sideElevationDp).toFloat()
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
        nativeUnitNameText.setTextColor(theme.titleColor)
        nativeUnitNameText.text = themedDisplayText(currentUnitName.ifBlank { "Painel de chamados" }, visualStyle.patientUppercase)
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
        empty.text = "Nenhum chamado recente para exibir neste momento."
        empty.setTextColor(0xFF6D6862.toInt())
        empty.textSize = 18f
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(14), dp(34), dp(14), dp(34))
        empty.background = createGradientCardDrawable(
            startColor = 0xFFFFFDFA.toInt(),
            endColor = 0xFFF7F1EA.toInt(),
            strokeColor = 0xFFE5DACE.toInt(),
            strokeWidth = dp(1),
            radiusDp = 24
        )
        nativeList.addView(empty, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        })
    }

    private fun animateCurrentCallCard(style: PriorityStyle, isNewCall: Boolean) {
        val visualStyle = currentThemeStyle()
        nativeCurrentCard.animate().cancel()
        nativePriorityText.animate().cancel()
        if (!isNewCall) return

        val baseAlpha = 1f
        val blinkAlpha = visualStyle.animationBlinkAlpha
        val basePriorityAlpha = 1f
        val blinkPriorityAlpha = (visualStyle.animationBlinkAlpha - 0.06f).coerceAtLeast(0.58f)
        val blinkSteps = 6
        repeat(blinkSteps) { index ->
            mainHandler.postDelayed({
                val highlighted = index % 2 == 0
                nativeCurrentCard.alpha = if (highlighted) baseAlpha else blinkAlpha
                nativeCurrentCard.scaleX = if (highlighted) visualStyle.animationBlinkScale else 0.994f
                nativeCurrentCard.scaleY = if (highlighted) visualStyle.animationBlinkScale else 0.994f
                nativePriorityText.alpha = if (highlighted) basePriorityAlpha else blinkPriorityAlpha
                nativePriorityText.scaleX = if (highlighted) visualStyle.animationPriorityScale else 1f
                nativePriorityText.scaleY = if (highlighted) visualStyle.animationPriorityScale else 1f
                nativeCurrentLabelText.alpha = if (highlighted) 1f else 0.78f
                nativeCurrentLabelText.scaleX = if (highlighted) visualStyle.animationLabelScale else 1f
                nativeCurrentLabelText.scaleY = if (highlighted) visualStyle.animationLabelScale else 1f
            }, index * visualStyle.animationIntervalMs)
        }
        mainHandler.postDelayed({
            nativeCurrentCard.alpha = 1f
            nativePriorityText.alpha = 1f
            nativePriorityText.scaleX = 1f
            nativePriorityText.scaleY = 1f
            nativeCurrentLabelText.alpha = 1f
            nativeCurrentLabelText.scaleX = 1f
            nativeCurrentLabelText.scaleY = 1f
            nativeCurrentCard.scaleX = 0.99f
            nativeCurrentCard.scaleY = 0.99f
            nativeCurrentCard.animate()
                .scaleX(style.pulseScale + visualStyle.animationPulseBoost)
                .scaleY(style.pulseScale + visualStyle.animationPulseBoost)
                .setDuration(280L)
                .withEndAction {
                    nativeCurrentCard.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(320L)
                        .start()
                }
                .start()
        }, visualStyle.animationIntervalMs * blinkSteps + 80L)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
