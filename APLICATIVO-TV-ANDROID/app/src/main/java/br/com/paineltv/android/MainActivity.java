package br.com.paineltv.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends Activity {
    private static final String PREFS = "painel_tv";
    private static final String DEFAULT_SERVER = "http://192.168.0.100:8787";
    private static final String APP_VERSION = "0.1.0";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView webView;
    private WebSocket socket;
    private TextToSpeech textToSpeech;
    private PowerManager.WakeLock wakeLock;
    private boolean ttsReady = false;

    private String serverUrl;
    private String activationToken;
    private String deviceId = "";
    private String deviceName = "Painel TV";
    private String primaryUrl = "about:blank";
    private String currentUrl = "about:blank";
    private String fallbackUrl = "about:blank";
    private boolean audioEnabled = true;
    private int heartbeatSeconds = 15;
    private int autoRefreshSeconds = 0;
    private int memoryLimitMb = 650;
    private int reloads = 0;
    private int reconnects = 0;
    private int consecutiveErrors = 0;
    private long startedAt;
    private long lastAutoRefreshAt = 0L;
    private long lastSuccessfulPageLoadAt = 0L;
    private String lastError = "";

    // Native panel polling — bypasses WebView JS timer throttling
    private String panelApiHost = "";
    private String panelId = "";
    private String lastCallId = "";

    private final Runnable panelPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollPanelApi();
            main.postDelayed(this, 15_000L);
        }
    };

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            sendHeartbeat();
            main.postDelayed(this, Math.max(5, heartbeatSeconds) * 1000L);
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            int memory = currentMemoryMb();
            if (memory > memoryLimitMb) {
                lastError = "Memoria acima do limite: " + memory + " MB";
                recreateWebView();
                sendClientError(lastError);
            }
            long now = System.currentTimeMillis();
            if (autoRefreshSeconds > 0
                    && webView != null
                    && !"about:blank".equals(currentUrl)
                    && now - lastAutoRefreshAt >= autoRefreshSeconds * 1000L) {
                lastAutoRefreshAt = now;
                lastError = "Atualizacao automatica da pagina executada";
                reloadPage();
            }
            if (webView != null
                    && !"about:blank".equals(currentUrl)
                    && lastSuccessfulPageLoadAt > 0L
                    && now - lastSuccessfulPageLoadAt > 90_000L) {
                applyChromeKeepAlive(webView);
            }
            main.postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        startedAt = System.currentTimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        acquireWakeLock();
        initTextToSpeech();

        root = new FrameLayout(this);
        setContentView(root);

        serverUrl = prefs.getString("serverUrl", DEFAULT_SERVER);
        activationToken = prefs.getString("activationToken", "");
        primaryUrl = prefs.getString("primaryUrl", "about:blank");
        currentUrl = prefs.getString("currentUrl", primaryUrl);
        fallbackUrl = prefs.getString("fallbackUrl", "about:blank");
        audioEnabled = prefs.getBoolean("audioEnabled", true);

        if (activationToken == null || activationToken.trim().isEmpty()) {
            showPairingScreen();
        } else {
            startPlayer();
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (socket != null) socket.close(1000, "activity destroyed");
        if (webView != null) webView.destroy();
        releaseWakeLock();
        if (textToSpeech != null) { textToSpeech.shutdown(); textToSpeech = null; }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        acquireWakeLock();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager == null) return;
            wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                    "PainelTV::ScreenWakeLock"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            showPairingScreen();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void showPairingScreen() {
        main.removeCallbacks(heartbeatRunnable);
        main.removeCallbacks(watchdogRunnable);
        if (socket != null) socket.close(1000, "setup");
        if (webView != null) {
            webView.destroy();
            webView = null;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(48, 48, 48, 48);
        box.setBackgroundColor(Color.rgb(6, 16, 20));

        TextView title = new TextView(this);
        title.setText("Painel TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);

        TextView help = new TextView(this);
        help.setText("Informe o endereco do servidor e o token gerado no painel.");
        help.setTextColor(Color.rgb(190, 211, 217));
        help.setTextSize(16);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, 12, 0, 24);

        EditText serverInput = input("Servidor", serverUrl);
        EditText tokenInput = input("Token de ativacao", activationToken == null ? "" : activationToken);

        Button save = new Button(this);
        save.setText("Conectar");
        save.setAllCaps(false);
        save.setOnClickListener(view -> {
            serverUrl = serverInput.getText().toString().trim();
            activationToken = tokenInput.getText().toString().trim();
            prefs.edit()
                    .putString("serverUrl", serverUrl)
                    .putString("activationToken", activationToken)
                    .apply();
            startPlayer();
        });

        Button clear = new Button(this);
        clear.setText("Limpar configuracao");
        clear.setAllCaps(false);
        clear.setOnClickListener(view -> {
            prefs.edit().clear().apply();
            serverInput.setText(DEFAULT_SERVER);
            tokenInput.setText("");
        });

        box.addView(title, matchWrap());
        box.addView(help, matchWrap());
        box.addView(serverInput, matchWrap());
        box.addView(tokenInput, matchWrap());
        box.addView(save, matchWrap());
        box.addView(clear, matchWrap());

        root.removeAllViews();
        root.addView(box);
    }

    private EditText input(String hint, String value) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value);
        edit.setSingleLine(true);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(Color.rgb(150, 170, 175));
        edit.setTextSize(18);
        edit.setPadding(16, 12, 16, 12);
        return edit;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 8, 0, 8);
        return params;
    }

    private void startPlayer() {
        root.removeAllViews();
        createWebView();
        loadUrl(currentUrl, false);
        connectSocket();
        main.removeCallbacks(heartbeatRunnable);
        main.removeCallbacks(watchdogRunnable);
        main.postDelayed(heartbeatRunnable, 1000L);
        main.postDelayed(watchdogRunnable, 30_000L);
        tryStartKiosk();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                applyChromeKeepAlive(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url == null ? currentUrl : url;
                consecutiveErrors = 0;
                lastError = "";
                lastSuccessfulPageLoadAt = System.currentTimeMillis();
                prefs.edit().putString("currentUrl", currentUrl).apply();
                applyChromeKeepAlive(view);
                applyAudioSetting();
                startPanelPollingForUrl(currentUrl);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    consecutiveErrors++;
                    lastError = error == null ? "Erro de carregamento" : String.valueOf(error.getDescription());
                    recoverFromPageError();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDatabaseEnabled(true);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        webView.onResume();
        webView.resumeTimers();

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    private void applyChromeKeepAlive(WebView target) {
        if (target == null) return;
        target.resumeTimers();
        target.evaluateJavascript(chromeKeepAliveScript(), null);
        target.evaluateJavascript(panelErrorGuardScript(), null);
    }

    private String chromeKeepAliveScript() {
        return "(function(){"
                + "try{"
                + "if(!window.__painelTvChromeKeepAlive){"
                + "window.__painelTvChromeKeepAlive=true;"
                + "var visible=function(){return false;};"
                + "var state=function(){return 'visible';};"
                + "try{Object.defineProperty(document,'hidden',{get:visible,configurable:true});}catch(e){}"
                + "try{Object.defineProperty(document,'visibilityState',{get:state,configurable:true});}catch(e){}"
                + "try{Object.defineProperty(Document.prototype,'hidden',{get:visible,configurable:true});}catch(e){}"
                + "try{Object.defineProperty(Document.prototype,'visibilityState',{get:state,configurable:true});}catch(e){}"
                + "try{document.hasFocus=function(){return true;};}catch(e){}"
                + "window.__painelTvWake=function(){"
                + "try{window.dispatchEvent(new Event('focus'));}catch(e){}"
                + "try{document.dispatchEvent(new Event('visibilitychange'));}catch(e){}"
                + "try{document.dispatchEvent(new Event('resume'));}catch(e){}"
                + "};"
                + "setInterval(window.__painelTvWake,15000);"
                + "}"
                + "if(window.__painelTvWake){window.__painelTvWake();}"
                + "}catch(e){}"
                + "})();";
    }

    private String panelErrorGuardScript() {
        return "(function(){try{"
                + "if(window.__painelTvErrorGuard)return;"
                + "window.__painelTvErrorGuard=true;"
                + "function norm(v){v=String(v||'').toLowerCase();try{v=v.normalize('NFD').replace(/[\\u0300-\\u036f]/g,'');}catch(e){}return v.replace(/\\s+/g,' ').trim();}"
                + "function visible(e){try{var s=getComputedStyle(e);var r=e.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>5&&r.height>5;}catch(x){return false;}}"
                + "function hasOops(){try{var txt=norm(document.body&&(document.body.innerText||''));return txt.indexOf('oops')>=0&&txt.indexOf('algo deu errado')>=0;}catch(e){return false;}}"
                + "function tryClose(){try{var els=[].slice.call(document.querySelectorAll('button,[role=\"button\"],a,input[type=\"button\"],input[type=\"submit\"]')).filter(visible);var btn=els.find(function(e){var t=norm(e.innerText||e.textContent||e.value||'');return t==='fechar'||t==='ok'||t==='close';});if(btn){btn.click();return true;}}catch(e){}return false;}"
                + "var last=0;"
                + "setInterval(function(){try{if(!hasOops())return;var now=Date.now();if(now-last<8000)return;last=now;if(tryClose())return;if(now-Number(window.__painelTvLastErrorReloadAt||0)>30000){window.__painelTvLastErrorReloadAt=now;try{location.reload();}catch(e){}}}catch(e){}},2000);"
                + "}catch(e){}})();";
    }

    private void loadUrl(String url) {
        loadUrl(url, true);
    }

    private void loadUrl(String url, boolean updatePrimary) {
        currentUrl = normalizeContentUrl(url);
        SharedPreferences.Editor editor = prefs.edit().putString("currentUrl", currentUrl);
        if (updatePrimary && !"about:blank".equals(currentUrl)) {
            primaryUrl = currentUrl;
            editor.putString("primaryUrl", primaryUrl);
        }
        editor.apply();
        if (webView != null) webView.loadUrl(currentUrl);
    }

    private String normalizeContentUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "about:blank";
        String clean = url.trim();
        if ("about:blank".equals(clean)) return clean;
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            return "https://" + clean;
        }
        return clean;
    }

    private void connectSocket() {
        if (socket != null) socket.close(1000, "reconnect");
        Request request = new Request.Builder()
                .url(deviceWsUrl())
                .build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                lastError = "";
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                main.post(() -> handleServerMessage(text));
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                reconnects++;
                lastError = t.getMessage() == null ? "Falha WebSocket" : t.getMessage();
                main.postDelayed(() -> connectSocket(), 3000L);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                reconnects++;
                main.postDelayed(() -> connectSocket(), 3000L);
            }
        });
    }

    private String deviceWsUrl() {
        String base = serverUrl == null || serverUrl.isEmpty() ? DEFAULT_SERVER : serverUrl;
        if (base.startsWith("http://")) base = "ws://" + base.substring("http://".length());
        if (base.startsWith("https://")) base = "wss://" + base.substring("https://".length());
        if (!base.startsWith("ws://") && !base.startsWith("wss://")) base = "ws://" + base;
        Uri uri = Uri.parse(base);
        return uri.buildUpon()
                .path("/ws/device")
                .appendQueryParameter("token", activationToken)
                .build()
                .toString();
    }

    private void handleServerMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String type = json.optString("type");
            if ("registered".equals(type) || "config".equals(type)) {
                applyConfig(json.getJSONObject("config"));
            } else if ("command".equals(type)) {
                handleCommand(json.getJSONObject("command"));
            } else if ("error".equals(type)) {
                lastError = json.optString("error", "Erro do servidor");
            }
        } catch (JSONException error) {
            lastError = error.getMessage();
        }
    }

    private void applyConfig(JSONObject config) {
        deviceId = config.optString("deviceId", deviceId);
        deviceName = config.optString("name", deviceName);
        fallbackUrl = config.optString("fallbackUrl", fallbackUrl);
        audioEnabled = config.optBoolean("audioEnabled", audioEnabled);
        heartbeatSeconds = config.optInt("heartbeatIntervalSeconds", heartbeatSeconds);
        autoRefreshSeconds = config.optInt("autoRefreshSeconds", autoRefreshSeconds);
        memoryLimitMb = config.optInt("memoryLimitMb", memoryLimitMb);
        prefs.edit()
                .putString("fallbackUrl", fallbackUrl)
                .putBoolean("audioEnabled", audioEnabled)
                .apply();

        String newUrl = config.optString("currentUrl", currentUrl);
        primaryUrl = normalizeContentUrl(newUrl);
        prefs.edit().putString("primaryUrl", primaryUrl).apply();
        if (!newUrl.equals(currentUrl)) loadUrl(newUrl, true);
        applyAudioSetting();
    }

    private void handleCommand(JSONObject command) {
        String commandId = command.optString("id");
        String name = command.optString("command");
        JSONObject payload = command.optJSONObject("payload");

        switch (name) {
            case "reload_page":
                reloadPage();
                break;
            case "recreate_webview":
                recreateWebView();
                break;
            case "restart_app":
                restartApp();
                break;
            case "clear_cache":
                clearCache();
                break;
            case "set_audio":
                audioEnabled = payload == null || payload.optBoolean("audioEnabled", true);
                prefs.edit().putBoolean("audioEnabled", audioEnabled).apply();
                applyAudioSetting();
                break;
            case "set_url":
                if (payload != null) loadUrl(payload.optString("currentUrl", currentUrl), true);
                break;
            case "apply_config":
                connectSocket();
                break;
            case "exit_fallback":
                loadUrl(prefs.getString("primaryUrl", primaryUrl), false);
                break;
            default:
                lastError = "Comando desconhecido: " + name;
        }
        sendCommandAck(commandId);
    }

    private void reloadPage() {
        reloads++;
        if (webView != null) webView.reload();
    }

    private void recreateWebView() {
        reloads++;
        createWebView();
        loadUrl(currentUrl);
    }

    private void clearCache() {
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
        }
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
        Runtime.getRuntime().exit(0);
    }

    private void recoverFromPageError() {
        if (consecutiveErrors == 1) {
            reloadPage();
        } else if (consecutiveErrors == 2) {
            recreateWebView();
        } else {
            sendClientError("Falha repetida na URL principal. Entrando no fallback.");
            loadUrl(fallbackUrl, false);
        }
    }

    private void applyAudioSetting() {
        if (webView == null) return;
        String muted = audioEnabled ? "false" : "true";
        String script = "(function(){"
                + "var m=document.querySelectorAll('video,audio');"
                + "for(var i=0;i<m.length;i++){m[i].muted=" + muted + ";}"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    private void sendHeartbeat() {
        if (socket == null) return;
        try {
            JSONObject telemetry = new JSONObject();
            telemetry.put("status", "online");
            telemetry.put("currentUrl", currentUrl);
            telemetry.put("playerStatus", consecutiveErrors > 0 ? "recovering" : "playing");
            telemetry.put("audioEnabled", audioEnabled);
            telemetry.put("memoryMb", currentMemoryMb());
            telemetry.put("reloads", reloads);
            telemetry.put("reconnects", reconnects);
            telemetry.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000L);
            telemetry.put("lastError", lastError == null ? "" : lastError);
            telemetry.put("appVersion", APP_VERSION);

            JSONObject message = new JSONObject();
            message.put("type", "heartbeat");
            message.put("telemetry", telemetry);
            socket.send(message.toString());
        } catch (JSONException ignored) {
            // Telemetry is best effort.
        }
    }

    private void sendCommandAck(String commandId) {
        if (socket == null || commandId == null || commandId.isEmpty()) return;
        try {
            JSONObject ack = new JSONObject();
            ack.put("type", "command_ack");
            ack.put("commandId", commandId);
            ack.put("status", "ok");
            ack.put("handledAt", System.currentTimeMillis());
            socket.send(ack.toString());
        } catch (JSONException ignored) {
            // Ack is best effort.
        }
    }

    private void sendClientError(String message) {
        if (socket == null) return;
        try {
            JSONObject error = new JSONObject();
            error.put("type", "client_error");
            error.put("error", message);
            socket.send(error.toString());
        } catch (JSONException ignored) {
            // Error reporting is best effort.
        }
    }

    private int currentMemoryMb() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return (int) (used / 1024L / 1024L);
    }

    private void tryStartKiosk() {
        try {
            startLockTask();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Full lock task requires device-owner provisioning. Immersive mode still protects normal use.
        }
    }

    // -------------------------------------------------------------------------
    // TTS
    // -------------------------------------------------------------------------

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(new Locale("pt", "BR"));
                ttsReady = (result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED);
            }
        });
    }

    private void announceCall(String name, String local) {
        if (!ttsReady || textToSpeech == null || name == null || name.isEmpty()) return;
        String text = name;
        if (local != null && !local.isEmpty()) {
            text += ", dirija-se a " + local;
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "call_" + System.currentTimeMillis());
    }

    // -------------------------------------------------------------------------
    // Native panel polling — works regardless of WebView JS timer state
    // -------------------------------------------------------------------------

    private void startPanelPollingForUrl(String url) {
        if (url == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(https?://[^/]+)/unique-panel/panel-screen/([a-f0-9-]{36})")
                .matcher(url);
        if (m.find()) {
            String newHost = m.group(1);
            String newId = m.group(2);
            if (!newId.equals(panelId) || !newHost.equals(panelApiHost)) {
                panelApiHost = newHost;
                panelId = newId;
                lastCallId = "";
                main.removeCallbacks(panelPollRunnable);
                main.postDelayed(panelPollRunnable, 5_000L);
            }
        } else {
            if (!panelId.isEmpty()) {
                panelId = "";
                panelApiHost = "";
                main.removeCallbacks(panelPollRunnable);
            }
        }
    }

    private void pollPanelApi() {
        if (panelId.isEmpty() || panelApiHost.isEmpty()) return;
        String apiUrl = panelApiHost + "/unique-panel/api/call/history"
                + "?panelId=" + panelId
                + "&limit=3&sort=updatedAt:desc&select=id,personal,local";
        Request request = new Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Android TV) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Cache-Control", "no-cache, no-store")
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Will retry on next cycle.
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) { response.close(); return; }
                String body = response.body().string();
                response.close();
                String firstId = firstXmlTag(body, "id");
                String name    = firstXmlTag(body, "personal");
                String local   = firstXmlTag(body, "local");
                if (!firstId.isEmpty()) {
                    main.post(() -> onPanelApiResult(firstId, name, local));
                }
            }
        });
    }

    private void onPanelApiResult(String firstId, String name, String local) {
        if (firstId.equals(lastCallId)) return; // Nothing changed.
        lastCallId = firstId;
        // Reload the WebView so Angular renders the fresh queue.
        if (webView != null && !"about:blank".equals(currentUrl)) {
            webView.reload();
        }
        // Announce the new call via native TTS.
        announceCall(name, local);
    }

    private String firstXmlTag(String xml, String tag) {
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        if (start < 0) return "";
        start += open.length();
        int end = xml.indexOf(close, start);
        if (end < 0) return "";
        return xml.substring(start, end).trim();
    }
}
