package br.com.paineltv.androidqr;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.CookieManager;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends Activity {
    private static final String PREFS = "painel_tv";
    private static final String DEFAULT_SERVER = "http://192.168.68.112:9090";
    private static final String APP_VERSION = "0.5.22-qr-main-patient-sync";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

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
    private String pairingId = "";
    private String pairingSecret = "";
    private TextView pairingStatus;
    private LinearLayout nativeCallOverlay;
    private TextView nativeCallName;
    private TextView nativeCallLocal;

    private String serverUrl;
    private String activationToken;
    private String deviceId = "";
    private String deviceName = "Painel TV";
    private String clientId = "";
    private String suggestedName = "";
    private String primaryUrl = "about:blank";
    private String currentUrl = "about:blank";
    private String fallbackUrl = "about:blank";
    private boolean audioEnabled = true;
    private int heartbeatSeconds = 15;
    private int autoRefreshSeconds = 0;
    private int memoryLimitMb = 650;
        private int preventiveReloadHours = 12;
    private int pageOfflineTimeoutSeconds = 60;
    private int reloads = 0;
    private int reconnects = 0;
    private int consecutiveErrors = 0;
    private int recoveryCycles = 0;
    private int webViewRecreates = 0;
    private int appRestarts = 0;
    private long startedAt;
    private long lastSuccessfulPageLoadAt = 0L;
    private long lastAutoRefreshAt = 0L;
    private long lastPreventiveReloadAt = 0L;
    private long lastWebViewRecreateAt = 0L;
    private long lastFullRestartAt = 0L;
    private long lastClientErrorSentAt = 0L;
    private long pageLoadStartedAt = 0L;
    private long lastSpeechRequestAt = 0L;
    private long lastSpeechStartedAt = 0L;
    private long lastSpeechCompletedAt = 0L;
    private long lastNativeSpeechAt = 0L;
    private long lastPanelNetworkActivityAt = 0L;
    private long lastPanelNetworkErrorAt = 0L;
    private long lastPanelAutoReloadAt = 0L;
    private int speechRequests = 0;
    private int speechCompletions = 0;
    private int consecutiveSpeechStalls = 0;
    private int panelNetworkEvents = 0;
    private int consecutivePanelNetworkErrors = 0;
    private int panelAutoReloads = 0;
    private boolean pageLoading = false;
    private String ttsVoicePreference = "default";
    private String ttsSelectedVoiceName = "";
    private int ttsAvailableVoiceCount = 0;
    private String activeSpeechToken = "";
    private String lastNativeSpeechKey = "";
    private String lastNativeSpeechCallSignature = "";
    private String lastError = "";
    private final ArrayDeque<PendingSpeech> pendingSpeech = new ArrayDeque<>();
    private static final long DEAD_PANEL_IDLE_MS = 10L * 60L * 1000L;
    private static final long DEAD_PANEL_RELOAD_COOLDOWN_MS = 30L * 60L * 1000L;
    private static final long STALE_PAGE_RECREATE_MS = 90L * 60L * 1000L;
    private static final long RENDER_MISMATCH_RECOVERY_COOLDOWN_MS = 6_000L;
    private static final long PENDING_VISUAL_SYNC_MAX_AGE_MS = 45_000L;
    private static final int RENDER_MISMATCH_MIN_STREAK = 2;
    private static final long NATIVE_AUDIO_FALLBACK_DELAY_MS = 4_500L;
    private static final long PANEL_POLL_INTERVAL_MS = 2_000L;
    private int consecutiveRenderMismatches = 0;
    private long lastRenderRecoveryAt = 0L;
    private String lastVisualReloadCallId = "";
    private String lastVisualRecreateCallId = "";
    private String pendingVisualSyncCallId = "";
    private long pendingVisualSyncAt = 0L;

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
                runRecovery("Memoria acima do limite: " + memory + " MB");
            }

            long now = System.currentTimeMillis();
                if (autoRefreshSeconds > 0
                    && (panelId == null || panelId.isEmpty())
                    && webView != null
                    && !pageLoading
                    && !"about:blank".equals(currentUrl)
                    && now - lastAutoRefreshAt >= autoRefreshSeconds * 1000L) {
                lastAutoRefreshAt = now;
                lastError = "Atualizacao automatica da pagina executada";
                reloadPage();
            }

                if (preventiveReloadHours > 0
                    && (panelId == null || panelId.isEmpty())
                    && now - lastPreventiveReloadAt >= preventiveReloadHours * 60L * 60L * 1000L
                    && webView != null
                    && !"about:blank".equals(currentUrl)) {
                lastPreventiveReloadAt = now;
                lastError = "Reload preventivo 24/7 executado";
                reloadPage();
            }

            if (pageLoading
                    && pageLoadStartedAt > 0L
                    && pageOfflineTimeoutSeconds > 0
                    && now - pageLoadStartedAt > pageOfflineTimeoutSeconds * 1000L
                    && !"about:blank".equals(currentUrl)) {
                pageLoading = false;
                runRecovery("Pagina travou durante o carregamento por mais de " + pageOfflineTimeoutSeconds + "s");
            }

            if (isSpeechStalled(now)) {
                recoverSpeechBridge("Narrador travou e nao concluiu a ultima chamada");
            }
            if (shouldRecoverDeadPanel(now)) {
                panelAutoReloads++;
                lastPanelAutoReloadAt = now;
                lastError = "Painel ficou sem receber atualizacoes. Recarregando automaticamente.";
                reloadPage();
            }
            main.postDelayed(this, 30_000L);
        }
    };

    private final Runnable speechBridgeRunnable = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                applyChromeKeepAlive(webView);
                injectPanelNetworkMonitor(webView);
                injectPanelErrorGuard(webView);
                injectPanelNextObserver(webView);
                injectSpeechBridge(webView);
                applyAudioSetting();
            }
            main.postDelayed(this, PANEL_POLL_INTERVAL_MS);
        }
    };

    // Native panel polling — bypasses WebView JS timer throttling
    private String panelApiHost = "";
    private String panelId = "";
    private String lastCallId = "";
    private String lastCallSignature = "";
    private String lastCallName = "";
    private String lastCallLocal = "";
    private String lastCallProfessional = "";
    private String lastRenderCheckCallId = "";
    private long lastPanelApiSuccessAt = 0L;
    private int panelApiFailures = 0;
    private String lastPanelApiError = "";
    private String pendingFallbackCallId = "";
    private String pendingFallbackSignature = "";
    private String pendingFallbackName = "";
    private String pendingFallbackText = "";
    private String pendingFallbackLocal = "";
    private String pendingFallbackProfessional = "";
    private int pendingFallbackSpeechRequests = 0;
    private long pendingFallbackAt = 0L;
    private long suppressPageSpeechUntil = 0L;
    private String pendingNativeCallId = "";
    private String pendingNativeCallName = "";

    private final Runnable panelPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollPanelApi();
            main.postDelayed(this, PANEL_POLL_INTERVAL_MS);
        }
    };

    private final Runnable nativeAudioFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            String callId = pendingFallbackCallId == null ? "" : pendingFallbackCallId;
            String signature = pendingFallbackSignature == null ? "" : pendingFallbackSignature;
            String name = pendingFallbackName == null ? "" : pendingFallbackName;
            String local = pendingFallbackLocal == null ? "" : pendingFallbackLocal;
            String professional = pendingFallbackProfessional == null ? "" : pendingFallbackProfessional;
            if (callId.isEmpty() || signature.isEmpty() || name.trim().isEmpty()) {
                clearPendingFallbackAnnouncement();
                return;
            }
            if (!signature.equals(lastCallSignature)) {
                deviceLog("FALLBACK", "Fallback cancelado: assinatura mudou (era " + signature + " agora " + lastCallSignature + ")");
                clearPendingFallbackAnnouncement();
                return;
            }
            if (!audioEnabled) {
                deviceLog("FALLBACK", "Fallback cancelado: audio desativado");
                clearPendingFallbackAnnouncement();
                return;
            }
            if (!activeSpeechToken.isEmpty() || !pendingSpeech.isEmpty()) {
                if (System.currentTimeMillis() - pendingFallbackAt < PENDING_VISUAL_SYNC_MAX_AGE_MS) {
                    main.removeCallbacks(nativeAudioFallbackRunnable);
                    main.postDelayed(nativeAudioFallbackRunnable, 1_000L);
                    return;
                }
                deviceLog("FALLBACK", "Fallback cancelado: TTS continuou ocupado por muito tempo");
                clearPendingFallbackAnnouncement();
                return;
            }
            boolean pageSpokeAfterBaseline = speechRequests > pendingFallbackSpeechRequests;
            String expectedName = normalizeMatchText(name);
            String script = "(function(){try{"
                    + "function norm(v){v=String(v||'').toLowerCase();try{v=v.normalize('NFD').replace(/[\\u0300-\\u036f]/g,'');}catch(e){}return v.replace(/\\s+/g,' ').trim();}"
                    + "var expected=" + JSONObject.quote(expectedName) + ";"
                    + "var local=" + JSONObject.quote(normalizeMatchText(local)) + ";"
                    + "var professional=" + JSONObject.quote(normalizeMatchText(professional)) + ";"
                    + "var lines=String((document.body&&document.body.innerText)||'').split(/\\n+/).map(function(x){return x.trim();}).filter(Boolean);"
                    + "var patient='';"
                    + "for(var i=0;i<lines.length-1;i++){if(norm(lines[i])==='paciente'){patient=lines[i+1]||'';break;}}"
                    + "var current=norm(patient);"
                    + "var visual=!!current&&(current.indexOf(expected)>=0||expected.indexOf(current)>=0);"
                    + "var last=String(window.__painelTvLastSpokenKey||'');"
                    + "var pending=String(window.__painelTvPendingSpeechKey||'');"
                    + "var spoke=last.indexOf(expected)>=0||pending.indexOf(expected)>=0;"
                    + "var spokeLocal=!local||last.indexOf(local)>=0||pending.indexOf(local)>=0;"
                    + "var spokeProf=!professional||last.indexOf(professional)>=0||pending.indexOf(professional)>=0;"
                    + "return (visual?'1':'0')+','+(spoke?'1':'0')+','+(spokeLocal?'1':'0')+','+(spokeProf?'1':'0');"
                    + "}catch(e){return '0,0,0,0';}})();";
            webView.evaluateJavascript(script, value -> {
                String result = String.valueOf(value == null ? "" : value).replace("\"", "");
                String[] parts = result.split(",");
                boolean visualOk = parts.length > 0 && "1".equals(parts[0]);
                boolean pageAlreadySpokePatient = parts.length > 1 && "1".equals(parts[1]);
                boolean pageAlreadySpokeLocal = parts.length > 2 && "1".equals(parts[2]);
                boolean pageAlreadySpokeProfessional = parts.length > 3 && "1".equals(parts[3]);
                long age = System.currentTimeMillis() - pendingFallbackAt;
                if (!visualOk && age < 7_000L) {
                    main.removeCallbacks(nativeAudioFallbackRunnable);
                    main.postDelayed(nativeAudioFallbackRunnable, 300L);
                    return;
                }
                String message = buildCallAnnouncementForFallback(
                        name,
                        local,
                        professional,
                        pageSpokeAfterBaseline,
                        pageAlreadySpokePatient,
                        pageAlreadySpokeLocal,
                        pageAlreadySpokeProfessional
                );
                if (message.trim().isEmpty()) {
                    clearPendingFallbackAnnouncement();
                    return;
                }
                deviceLog("FALLBACK", "Pagina sem nome apos espera. Disparando TTS nativo: " + message);
                announceNativeCall(message);
                clearPendingFallbackAnnouncement();
            });
        }
    };

    private final Runnable pendingNativeCallRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingNativeCallName.isEmpty()) return;
            if (!pendingNativeCallId.equals(lastCallId)) {
                pendingNativeCallName = "";
                pendingNativeCallId = "";
                return;
            }
            String nameToSpeak = pendingNativeCallName;
            pendingNativeCallName = "";
            pendingNativeCallId = "";
            announceNativeCall(nameToSpeak);
        }
    };

    private final Runnable pairingPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollPairingStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        startedAt = System.currentTimeMillis();
        WebView.setWebContentsDebuggingEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        acquireWakeLock();
        initTextToSpeech();

        root = new FrameLayout(this);
        setContentView(root);

        serverUrl = prefs.getString("serverUrl", DEFAULT_SERVER);
        clientId = prefs.getString("clientId", "");
        if (clientId == null || clientId.trim().isEmpty()) {
            clientId = UUID.randomUUID().toString();
            prefs.edit().putString("clientId", clientId).apply();
        }
        suggestedName = prefs.getString("suggestedName", "Painel hospital");
        activationToken = prefs.getString("activationToken", "");
        primaryUrl = prefs.getString("primaryUrl", "about:blank");
        currentUrl = prefs.getString("currentUrl", primaryUrl);
        fallbackUrl = prefs.getString("fallbackUrl", "about:blank");
        audioEnabled = prefs.getBoolean("audioEnabled", true);
        ttsVoicePreference = prefs.getString("ttsVoicePreference", "default");

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
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
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
                    "PainelTVQR::ScreenWakeLock"
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
        main.removeCallbacks(speechBridgeRunnable);
        main.removeCallbacks(panelPollRunnable);
        main.removeCallbacks(nativeAudioFallbackRunnable);
        main.removeCallbacks(pendingNativeCallRunnable);
        clearPendingFallbackAnnouncement();
        pendingNativeCallId = "";
        pendingNativeCallName = "";
        main.removeCallbacks(pairingPollRunnable);
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
        help.setText("Informe o servidor uma vez. Depois gere o QR Code e aprove pelo celular.");
        help.setTextColor(Color.rgb(190, 211, 217));
        help.setTextSize(16);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, 12, 0, 24);

        EditText serverInput = input("Servidor", serverUrl);
        EditText nameInput = input("Nome sugerido da TV", suggestedName);
        EditText tokenInput = input("Token manual (opcional)", activationToken == null ? "" : activationToken);

        Button save = new Button(this);
        save.setText("Gerar QR Code de pareamento");
        save.setAllCaps(false);
        save.setOnClickListener(view -> {
            serverUrl = serverInput.getText().toString().trim();
            suggestedName = nameInput.getText().toString().trim();
            if (suggestedName.isEmpty()) suggestedName = "Painel hospital";
            prefs.edit()
                    .putString("serverUrl", serverUrl)
                    .putString("suggestedName", suggestedName)
                    .apply();
            requestPairing();
        });

        Button manual = new Button(this);
        manual.setText("Conectar com token manual");
        manual.setAllCaps(false);
        manual.setOnClickListener(view -> {
            serverUrl = serverInput.getText().toString().trim();
            activationToken = tokenInput.getText().toString().trim();
            prefs.edit()
                    .putString("serverUrl", serverUrl)
                    .putString("activationToken", activationToken)
                    .putString("suggestedName", nameInput.getText().toString().trim())
                    .apply();
            if (!activationToken.isEmpty()) startPlayer();
        });

        Button clear = new Button(this);
        clear.setText("Limpar configuracao");
        clear.setAllCaps(false);
        clear.setOnClickListener(view -> {
            prefs.edit().clear().apply();
            clientId = UUID.randomUUID().toString();
            prefs.edit().putString("clientId", clientId).apply();
            serverInput.setText(DEFAULT_SERVER);
            nameInput.setText("Painel hospital");
            tokenInput.setText("");
        });

        pairingStatus = new TextView(this);
        pairingStatus.setText("Dica: use o IP do computador, por exemplo " + DEFAULT_SERVER);
        pairingStatus.setTextColor(Color.rgb(190, 211, 217));
        pairingStatus.setTextSize(15);
        pairingStatus.setGravity(Gravity.CENTER);
        pairingStatus.setPadding(0, 8, 0, 8);

        box.addView(title, matchWrap());
        box.addView(help, matchWrap());
        box.addView(serverInput, matchWrap());
        box.addView(nameInput, matchWrap());
        box.addView(save, matchWrap());
        box.addView(tokenInput, matchWrap());
        box.addView(manual, matchWrap());
        box.addView(clear, matchWrap());
        box.addView(pairingStatus, matchWrap());

        root.removeAllViews();
        root.addView(box);
    }

    private void requestPairing() {
        pairingStatus.setText("Pedindo pareamento ao servidor...");
        try {
            JSONObject body = new JSONObject();
            body.put("suggestedName", suggestedName == null || suggestedName.trim().isEmpty() ? "Painel hospital" : suggestedName);
            body.put("clientId", clientId);
            body.put("appVersion", APP_VERSION);
            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("clientId", clientId);
            deviceInfo.put("model", android.os.Build.MODEL);
            deviceInfo.put("manufacturer", android.os.Build.MANUFACTURER);
            deviceInfo.put("localIp", getLocalIpAddress());
            body.put("deviceInfo", deviceInfo);
            body.put("localIpAddress", getLocalIpAddress());

            Request request = new Request.Builder()
                    .url(apiUrl("/api/pairing/request"))
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    main.post(() -> pairingStatus.setText("Falha ao gerar QR: " + e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String text = response.body() == null ? "{}" : response.body().string();
                    if (!response.isSuccessful()) {
                        main.post(() -> pairingStatus.setText("Servidor recusou pareamento: " + response.code()));
                        return;
                    }
                    try {
                        JSONObject pairing = new JSONObject(text).getJSONObject("pairing");
                        pairingId = pairing.getString("id");
                        pairingSecret = pairing.getString("secret");
                        String approveUrl = pairing.getString("approveUrl");
                        String code = pairing.getString("code");
                        main.post(() -> showQrScreen(approveUrl, code));
                    } catch (JSONException e) {
                        main.post(() -> pairingStatus.setText("Resposta invalida do servidor."));
                    }
                }
            });
        } catch (JSONException e) {
            pairingStatus.setText("Erro preparando pareamento.");
        }
    }

    private void showQrScreen(String approveUrl, String code) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(42, 42, 42, 42);
        box.setBackgroundColor(Color.rgb(6, 16, 20));

        TextView title = new TextView(this);
        title.setText("Aponte a camera do celular");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);

        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(approveUrl, 540));
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(18, 18, 18, 18);

        TextView codeView = new TextView(this);
        codeView.setText(String.format(Locale.ROOT, "Codigo: %s", code));
        codeView.setTextColor(Color.WHITE);
        codeView.setTextSize(24);
        codeView.setGravity(Gravity.CENTER);

        pairingStatus = new TextView(this);
        pairingStatus.setText("Aguardando aprovacao do gestor...");
        pairingStatus.setTextColor(Color.rgb(190, 211, 217));
        pairingStatus.setTextSize(16);
        pairingStatus.setGravity(Gravity.CENTER);

        TextView urlView = new TextView(this);
        urlView.setText(approveUrl);
        urlView.setTextColor(Color.rgb(159, 215, 209));
        urlView.setTextSize(13);
        urlView.setGravity(Gravity.CENTER);

        Button back = new Button(this);
        back.setText("Alterar servidor");
        back.setAllCaps(false);
        back.setOnClickListener(view -> showPairingScreen());

        box.addView(title, matchWrap());
        box.addView(qr, matchWrap());
        box.addView(codeView, matchWrap());
        box.addView(pairingStatus, matchWrap());
        box.addView(urlView, matchWrap());
        box.addView(back, matchWrap());

        root.removeAllViews();
        root.addView(box);
        main.removeCallbacks(pairingPollRunnable);
        main.postDelayed(pairingPollRunnable, 1500L);
    }

    private Bitmap qrBitmap(String content, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
        } catch (WriterException ignored) {
            bitmap.eraseColor(Color.WHITE);
        }
        return bitmap;
    }

    private void pollPairingStatus() {
        if (pairingId.isEmpty() || pairingSecret.isEmpty()) return;
        String path = "/api/pairing/" + Uri.encode(pairingId) + "/status?secret=" + Uri.encode(pairingSecret);
        Request request = new Request.Builder().url(apiUrl(path)).get().build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                main.post(() -> {
                    pairingStatus.setText("Aguardando servidor...");
                    main.postDelayed(pairingPollRunnable, 2500L);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                try {
                    JSONObject pairing = new JSONObject(text).getJSONObject("pairing");
                    String status = pairing.optString("status");
                    if ("approved".equals(status)) {
                        activationToken = pairing.getString("activationToken");
                        prefs.edit()
                                .putString("serverUrl", serverUrl)
                                .putString("activationToken", activationToken)
                                .apply();
                        main.post(() -> {
                            pairingStatus.setText("Aprovado. Conectando...");
                            startPlayer();
                        });
                        return;
                    }
                    if ("expired".equals(status)) {
                        main.post(() -> pairingStatus.setText("QR expirou. Gere outro QR Code."));
                        return;
                    }
                    main.post(() -> {
                        pairingStatus.setText("Aguardando aprovacao do gestor...");
                        main.postDelayed(pairingPollRunnable, 2500L);
                    });
                } catch (JSONException e) {
                    main.post(() -> {
                        pairingStatus.setText("Resposta invalida. Tentando novamente...");
                        main.postDelayed(pairingPollRunnable, 2500L);
                    });
                }
            }
        });
    }

    private String apiUrl(String path) {
        String base = normalizeServerUrl(serverUrl);
        if (path.startsWith("/")) return base + path;
        return base + "/" + path;
    }

    private String normalizeServerUrl(String value) {
        String clean = value == null || value.trim().isEmpty() ? DEFAULT_SERVER : value.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://" + clean;
        }
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean;
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
        lastSuccessfulPageLoadAt = System.currentTimeMillis();
        lastPreventiveReloadAt = System.currentTimeMillis();
        pageLoading = false;
        pageLoadStartedAt = 0L;
        createWebView();
        loadUrl(currentUrl, false);
        connectSocket();
        main.removeCallbacks(heartbeatRunnable);
        main.removeCallbacks(watchdogRunnable);
        main.removeCallbacks(speechBridgeRunnable);
        main.postDelayed(heartbeatRunnable, 1000L);
        main.postDelayed(watchdogRunnable, 30_000L);
        main.postDelayed(speechBridgeRunnable, 3000L);
        tryStartKiosk();
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                textToSpeech.setLanguage(new Locale("pt", "BR"));
                textToSpeech.setSpeechRate(1.0f);
                textToSpeech.setPitch(1.0f);
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        lastSpeechStartedAt = System.currentTimeMillis();
                        deviceLog("TTS_OK", "TTS iniciou fala: token=" + utteranceId);
                        main.post(() -> notifyJsSpeechStart(utteranceId));
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        lastSpeechCompletedAt = System.currentTimeMillis();
                        speechCompletions++;
                        consecutiveSpeechStalls = 0;
                        activeSpeechToken = "";
                        deviceLog("TTS_OK", "TTS concluiu fala: token=" + utteranceId);
                        main.post(() -> {
                            notifyJsSpeechDone(utteranceId);
                            executePendingVisualSync(false);
                            flushPendingSpeech();
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        activeSpeechToken = "";
                        deviceLog("TTS_ERR", "TTS erro na fala: token=" + utteranceId);
                        main.post(() -> {
                            notifyJsSpeechError(utteranceId, "Erro no narrador nativo");
                            executePendingVisualSync(false);
                            flushPendingSpeech();
                        });
                    }
                });
                flushPendingSpeech();
            } else {
                lastError = "TextToSpeech indisponivel no dispositivo";
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.addJavascriptInterface(new NativeSpeechBridge(), "AndroidSpeechBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                boolean mainFrame = request != null && request.isForMainFrame();
                boolean getRequest = request == null || request.getMethod() == null || "GET".equalsIgnoreCase(request.getMethod());
                if (mainFrame && getRequest && shouldInjectPanelBootstrap(uri)) {
                    WebResourceResponse injected = buildInjectedPanelResponse(uri.toString());
                    if (injected != null) {
                        deviceLog("BOOTSTRAP", "Ponte antecipada aplicada ao painel: " + uri);
                        return injected;
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoading = true;
                pageLoadStartedAt = System.currentTimeMillis();
                lastPanelNetworkActivityAt = pageLoadStartedAt;
                applyChromeKeepAlive(view);
                injectPanelNetworkMonitor(view);
                injectPanelNextObserver(view);
                injectSpeechBridge(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url == null ? currentUrl : url;
                pageLoading = false;
                pageLoadStartedAt = 0L;
                consecutiveErrors = 0;
                recoveryCycles = 0;
                lastSuccessfulPageLoadAt = System.currentTimeMillis();
                lastPanelNetworkActivityAt = lastSuccessfulPageLoadAt;
                lastError = "";
                prefs.edit().putString("currentUrl", currentUrl).apply();
                applyChromeKeepAlive(view);
                injectPanelNetworkMonitor(view);
                injectPanelNextObserver(view);
                injectSpeechBridge(view);
                applyAudioSetting();
                startPanelPollingForUrl(currentUrl);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    pageLoading = false;
                    lastError = error == null ? "Erro de carregamento" : String.valueOf(error.getDescription());
                    runRecovery(lastError);
                }
            }

        });
        webView.setWebChromeClient(new WebChromeClient());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setOffscreenPreRaster(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setBlockNetworkLoads(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        configureServiceWorker();
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        webView.onResume();
        webView.resumeTimers();

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        installNativeCallOverlay();
    }

    private boolean shouldInjectPanelBootstrap(Uri uri) {
        if (uri == null) return false;
        String path = uri.getPath();
        return path != null && path.startsWith("/unique-panel/panel-screen/");
    }

    private WebResourceResponse buildInjectedPanelResponse(String url) {
        okhttp3.Response response = null;
        try {
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Cache-Control", "no-cache, no-store");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.trim().isEmpty()) builder.header("Cookie", cookie);
            response = http.newCall(builder.build()).execute();
            if (!response.isSuccessful() || response.body() == null) return null;
            String html = response.body().string();
            String bootstrap = "<script>"
                    + chromeKeepAliveScript()
                    + panelNetworkMonitorScript()
                    + panelNextObserverScript()
                    + speechBridgeScript()
                    + "</script>";
            String lower = html.toLowerCase(Locale.ROOT);
            int head = lower.indexOf("<head");
            if (head >= 0) {
                int close = html.indexOf(">", head);
                if (close >= 0) {
                    html = html.substring(0, close + 1) + bootstrap + html.substring(close + 1);
                } else {
                    html = bootstrap + html;
                }
            } else {
                int body = lower.indexOf("<body");
                if (body >= 0) {
                    int close = html.indexOf(">", body);
                    html = close >= 0 ? html.substring(0, close + 1) + bootstrap + html.substring(close + 1) : bootstrap + html;
                } else {
                    html = bootstrap + html;
                }
            }
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            WebResourceResponse webResponse = new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(bytes));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
            headers.put("Pragma", "no-cache");
            headers.put("Access-Control-Allow-Origin", "*");
            webResponse.setResponseHeaders(headers);
            return webResponse;
        } catch (Exception error) {
            deviceLog("BOOTSTRAP", "Nao consegui injetar ponte antes do painel: " + error.getMessage());
            return null;
        } finally {
            if (response != null) response.close();
        }
    }

    private void installNativeCallOverlay() {
        // Desativado por produto: a TV deve exibir somente o painel real, sem camada extra.
    }

    private void showNativeCallOverlay(String name, String local) {
        // Desativado por produto: nada de sobrepor nome por fora do site.
    }

    private void configureServiceWorker() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
            ServiceWorkerWebSettings swSettings = ServiceWorkerController.getInstance().getServiceWorkerWebSettings();
            swSettings.setAllowContentAccess(true);
            swSettings.setAllowFileAccess(false);
            swSettings.setBlockNetworkLoads(false);
        } catch (Exception ignored) {
        }
    }

    private void applyChromeKeepAlive(WebView target) {
        if (target == null) return;
        target.resumeTimers();
        target.evaluateJavascript(chromeKeepAliveScript(), null);
    }

    private void injectPanelNetworkMonitor(WebView target) {
        if (target == null) return;
        target.evaluateJavascript(panelNetworkMonitorScript(), null);
    }

    private void injectPanelNextObserver(WebView target) {
        if (target == null) return;
        target.evaluateJavascript(panelNextObserverScript(), null);
    }

    private void injectPanelErrorGuard(WebView target) {
        if (target == null) return;
        target.evaluateJavascript(panelErrorGuardScript(), null);
    }

    private String panelNextObserverScript() {
        return "(function(){try{"
                + "if(window.__painelTvNextObserverInstalled)return true;"
                + "window.__painelTvNextObserverInstalled=true;"
                + "function norm(v){v=String(v||'').toLowerCase();try{v=v.normalize('NFD').replace(/[\\u0300-\\u036f]/g,'');}catch(e){}return v.replace(/\\s+/g,' ').trim();}"
                + "function visible(e){try{var s=getComputedStyle(e);var r=e.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>5&&r.height>5;}catch(x){return false;}}"
                + "function extractCall(payload){if(!payload)return null;var c=payload;if(payload.data&&payload.data.length)c=payload.data[0];if(payload.data&&!payload.data.length)return null;if(!c||!c.id||!c.personal)return null;return {id:String(c.id),personal:String(c.personal||'').trim(),local:String(c.local||'').trim(),professional:String(c.professional||'').trim()};}"
                + "function mainPatient(){var lines=String(document.body&&document.body.innerText||'').split(/\\n+/).map(function(x){return x.trim();}).filter(Boolean);for(var i=0;i<lines.length-1;i++){if(norm(lines[i])==='paciente'){return lines[i+1]||'';}}return '';}"
                + "function syncCall(call){call=extractCall(call);if(!call||!call.personal)return false;window.__painelTvLastNextCall={id:call.id,personal:call.personal,local:call.local,at:Date.now()};try{if(window.__painelTvWake)window.__painelTvWake();}catch(e){}window.__painelTvLastDomSync={name:mainPatient(),expected:call.personal,local:call.local,source:'call-next-observed',at:Date.now()};return true;}"
                + "function maybeSync(url,text){try{if(String(url||'').indexOf('/api/call/next')<0)return;var payload=JSON.parse(text||'null');syncCall(payload);}catch(e){}}"
                + "if(window.fetch){var nativeFetch=window.fetch;window.fetch=function(){var req=arguments[0];var url=(typeof req==='string')?req:(req&&req.url)||'';return nativeFetch.apply(this,arguments).then(function(res){try{if(String(url).indexOf('/api/call/next')>=0){res.clone().text().then(function(t){maybeSync(url,t);}).catch(function(){});}}catch(e){}return res;}).catch(function(err){try{if(String(url).indexOf('/api/call/next')>=0&&window.AndroidSpeechBridge&&AndroidSpeechBridge.reportPanelActivity){AndroidSpeechBridge.reportPanelActivity('call-next-erro');}}catch(e){}throw err;});};}"
                + "var nativeOpen=XMLHttpRequest.prototype.open;var nativeSend=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){this.__painelTvUrl=u;return nativeOpen.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){try{this.addEventListener('load',function(){maybeSync(this.__painelTvUrl,this.responseText);});}catch(e){}return nativeSend.apply(this,arguments);};"
                + "setInterval(function(){try{var c=window.__painelTvLastNextCall;if(c&&Date.now()-c.at<15000)syncCall(c);}catch(e){}},1000);"
                + "return true;"
                + "}catch(e){return false;}})();";
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
                + "setInterval(function(){try{if(!hasOops())return;var now=Date.now();if(now-last<8000)return;last=now;try{if(window.AndroidSpeechBridge&&AndroidSpeechBridge.reportPanelActivity){AndroidSpeechBridge.reportPanelActivity('ui-erro');}}catch(e){}if(tryClose())return;if(now-Number(window.__painelTvLastErrorReloadAt||0)>30000){window.__painelTvLastErrorReloadAt=now;try{location.reload();}catch(e){}}}catch(e){}},2000);"
                + "}catch(e){}})();";
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

    private String panelNetworkMonitorScript() {
        return "(function(){"
                + "try{"
                + "if(window.__painelTvNetworkMonitor){return;}"
                + "window.__painelTvNetworkMonitor=true;"
                + "function report(kind){try{AndroidSpeechBridge.reportPanelActivity(String(kind||'rede'));}catch(e){}}"
                + "report('monitor-iniciado');"
                + "var oldFetch=window.fetch;"
                + "if(oldFetch){window.fetch=function(){report('fetch');var p=oldFetch.apply(this,arguments);try{p.then(function(){report('fetch-ok');},function(){report('fetch-erro');});}catch(e){}return p;};}"
                + "var OldXhr=window.XMLHttpRequest;"
                + "if(OldXhr){window.XMLHttpRequest=function(){var xhr=new OldXhr();try{xhr.addEventListener('loadend',function(){report('xhr');});xhr.addEventListener('error',function(){report('xhr-erro');});}catch(e){}return xhr;};window.XMLHttpRequest.prototype=OldXhr.prototype;}"
                + "var OldWs=window.WebSocket;"
                + "if(OldWs){window.WebSocket=function(){report('websocket');var ws=arguments.length>1?new OldWs(arguments[0],arguments[1]):new OldWs(arguments[0]);try{ws.addEventListener('open',function(){report('websocket-aberto');});ws.addEventListener('message',function(){report('websocket-msg');});ws.addEventListener('close',function(){report('websocket-fechado');});ws.addEventListener('error',function(){report('websocket-erro');});}catch(e){}return ws;};window.WebSocket.prototype=OldWs.prototype;}"
                + "var OldEs=window.EventSource;"
                + "if(OldEs){window.EventSource=function(){report('eventsource');var es=arguments.length>1?new OldEs(arguments[0],arguments[1]):new OldEs(arguments[0]);try{es.addEventListener('message',function(){report('eventsource-msg');});es.addEventListener('error',function(){report('eventsource-erro');});}catch(e){}return es;};window.EventSource.prototype=OldEs.prototype;}"
                + "}catch(e){}"
                + "})();";
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

    private void injectSpeechBridge(WebView target) {
        if (target == null) return;
        target.evaluateJavascript(speechBridgeScript(), null);
    }

    private String speechBridgeScript() {
        return "(function(){"
                + "try{if(!window.__painelTvSpeechOriginalSaved){window.__painelTvSpeechOriginalSaved=true;window.__painelTvOriginalSpeechSynthesis=window.speechSynthesis;window.__painelTvOriginalUtterance=window.SpeechSynthesisUtterance;}}catch(e){}"
                + "var __ptv_ttsOk=true;"
                + "try{if(window.AndroidSpeechBridge&&AndroidSpeechBridge.isTtsEngineReady){__ptv_ttsOk=!!AndroidSpeechBridge.isTtsEngineReady();}}catch(e){}"
                + "if(!__ptv_ttsOk){"
                + "try{if(window.__painelTvOriginalSpeechSynthesis){Object.defineProperty(window,'speechSynthesis',{value:window.__painelTvOriginalSpeechSynthesis,writable:true,configurable:true});}}catch(e){try{window.speechSynthesis=window.__painelTvOriginalSpeechSynthesis;}catch(ee){}}"
                + "try{if(window.__painelTvOriginalUtterance){Object.defineProperty(window,'SpeechSynthesisUtterance',{value:window.__painelTvOriginalUtterance,writable:true,configurable:true});}}catch(e){try{window.SpeechSynthesisUtterance=window.__painelTvOriginalUtterance;}catch(ee){}}"
                + "try{window.__painelTvNativeTts=null;}catch(e){}"
                + "return;}"
                + "if(window.__painelTvNativeTts&&window.__painelTvNativeTts.version===8){return;}"
                + "window.__painelTvNativeTtsInstalled=true;"
                + "var voices=["
                + "{name:'Google portugu\\u00eas do Brasil',voiceURI:'google-pt-br',lang:'pt-BR',localService:false,default:true},"
                + "{name:'Google US English Female',voiceURI:'google-us-en-female',lang:'en-US',localService:false,default:false},"
                + "{name:'Android TTS',voiceURI:'android-tts',lang:'pt-BR',localService:true,default:false}"
                + "];"
                + "var pendingQueue=[];"
                + "var callbacks={};"
                + "var seq=0;"
                + "function norm(v){v=String(v||'').toLowerCase();try{v=v.normalize('NFD').replace(/[\\u0300-\\u036f]/g,'');}catch(e){}return v.replace(/\\s+/g,' ').trim();}"
                + "function NativeUtterance(text){"
                + "this.text=text||'';this.lang='pt-BR';this.rate=1;this.pitch=1;this.volume=1;"
                + "this.voice=voices[0];this.onstart=null;this.onend=null;this.onerror=null;"
                + "}"
                + "function nextToken(){seq+=1;return 'utt-'+Date.now()+'-'+seq;}"
                + "function safeCall(fn,arg){try{if(typeof fn==='function'){fn(arg);}}catch(e){}}"
                + "function mainPatient(){var lines=String(document.body&&document.body.innerText||'').split(/\\n+/).map(function(x){return x.trim();}).filter(Boolean);for(var i=0;i<lines.length-1;i++){if(norm(lines[i])==='paciente'){return lines[i+1]||'';}}return '';}"
                + "function callSig(){try{var s=String(window.__painelTvLastCallSignature||'');if(s)return s;var c=window.__painelTvLastNextCall||null;if(c&&c.id){return String(c.id||'')+'|'+String(c.personal||'')+'|'+String(c.local||'');}var d=window.__painelTvLastDomSync||null;if(d&&(d.signature||d.id||d.expected)){return String(d.signature||'')||String(d.id||'')+'|'+String(d.expected||'')+'|'+String(d.local||'');}}catch(e){}return '';}"
                + "function bestPatient(){try{var c=window.__painelTvLastNextCall||null;if(c&&c.personal&&Date.now()-Number(c.at||0)<30000)return String(c.personal||'').trim();}catch(e){}try{var d=window.__painelTvLastDomSync||null;if(d&&d.expected&&Date.now()-Number(d.at||0)<30000)return String(d.expected||'').trim();}catch(e){}try{var p=String(mainPatient()||'').trim();if(p)return p;}catch(e){}return '';}"
                + "function fixCallText(text){try{var raw=String(text||'');var key=norm(raw);if(!key)return raw;if(key.indexOf('por favor comparecer')===0&&key.indexOf('chamando o paciente')<0){var p=bestPatient();var pn=norm(p);if(p&&pn&&key.indexOf(pn)<0){var rest=raw.replace(/^\\s*[\\.,\\-]+\\s*/,'').trim();return 'Chamando o paciente '+p+'. '+rest;}}}catch(e){}return String(text||'');}"
                + "function visualReady(text){var wanted=norm(text);if(!wanted)return true;if(wanted==='chamando'||wanted.indexOf('chamando')===0)return true;if(wanted.indexOf('consultorio')===0||wanted.indexOf('sala')===0||wanted.indexOf('classificacao')===0||wanted.indexOf('medicacao')===0||wanted.indexOf('raio x')===0||wanted.indexOf('triagem')===0)return true;if(wanted.indexOf('por favor comparecer')===0)return true;var shown=norm(mainPatient());if(!shown)return false;return wanted.indexOf(shown)>=0||shown.indexOf(wanted)>=0;}"
                + "function enqueueItem(item){pendingQueue.push(item);synth.pending=pendingQueue.length>0||synth.speaking;playNext();}"
                + "function waitVisualThenSpeak(item,startedAt){if(visualReady(item.text)){window.__painelTvPendingSpeechKey='';window.__painelTvPendingSpeechCallSig='';enqueueItem(item);return;}if(Date.now()-startedAt<2500){setTimeout(function(){waitVisualThenSpeak(item,startedAt);},200);return;}window.__painelTvPendingSpeechKey='';window.__painelTvPendingSpeechCallSig='';enqueueItem(item);}"
                + "function restorePendingSpeech(){try{var raw=sessionStorage.getItem('__painelTvPendingSpeech');if(!raw)return;var p=JSON.parse(raw);if(!p||!p.text||Date.now()-Number(p.at||0)>20000){sessionStorage.removeItem('__painelTvPendingSpeech');return;}if(!visualReady(p.text)){setTimeout(restorePendingSpeech,500);return;}sessionStorage.removeItem('__painelTvPendingSpeech');var key=norm(p.text);var sig=norm(callSig());if(window.__painelTvLastSpokenKey===key&&window.__painelTvLastSpokenCallSig===sig&&Date.now()-Number(window.__painelTvLastSpokenAt||0)<90000)return;enqueueItem({token:nextToken(),text:p.text,lang:p.lang||'pt-BR',rate:Number(p.rate||1),pitch:Number(p.pitch||1),onstart:null,onend:null,onerror:null});}catch(e){}}"
                + "function finishToken(token,type,message){"
                + "var entry=callbacks[token];"
                + "if(!entry){synth.speaking=false;synth.pending=pendingQueue.length>0;if(type!=='start'){playNext();}return;}"
                + "if(type==='start'){synth.speaking=true;safeCall(entry.onstart,{type:'start'});return;}"
                + "delete callbacks[token];"
                + "synth.speaking=false;synth.pending=pendingQueue.length>0;"
                + "if(type==='error'){safeCall(entry.onerror,{type:'error',error:message||'Erro nativo'});}else{safeCall(entry.onend,{type:'end'});}"
                + "playNext();"
                + "}"
                + "function playNext(){"
                + "if(synth.speaking||pendingQueue.length===0){synth.pending=pendingQueue.length>0;return;}"
                + "var item=pendingQueue.shift();"
                + "callbacks[item.token]={text:item.text,onstart:item.onstart,onend:item.onend,onerror:item.onerror};"
                + "synth.pending=pendingQueue.length>0;"
                + "try{AndroidSpeechBridge.speak(String(item.token),String(item.text),String(item.lang),Number(item.rate),Number(item.pitch));}"
                + "catch(err){finishToken(item.token,'error',err&&err.message?err.message:'Falha ao enviar fala');}"
                + "}"
                + "try{Object.defineProperty(window,'SpeechSynthesisUtterance',{value:NativeUtterance,writable:true,configurable:true});}catch(e){window.SpeechSynthesisUtterance=NativeUtterance;}"
                + "var synth={"
                + "speaking:false,pending:false,paused:false,"
                + "getVoices:function(){return voices;},"
                + "speak:function(u){"
                + "var text=(typeof u==='string')?u:((u&&u.text)||'');"
                + "var lang=(u&&u.lang)||'pt-BR';"
                + "var rate=(u&&u.rate)||1;"
                + "var pitch=(u&&u.pitch)||1;"
                + "if(!text){return;}"
                + "text=fixCallText(text);"
                + "var key=norm(text);var now=Date.now();var sig=norm(callSig());"
                + "var generic=(key==='chamando'||key.indexOf('chamando ')===0);"
                + "var lastWin=generic?1500:90000;"
                + "var pendingWin=generic?700:8000;"
                + "if((window.__painelTvLastSpokenKey===key&&window.__painelTvLastSpokenCallSig===sig&&now-Number(window.__painelTvLastSpokenAt||0)<lastWin&&(!generic||!!sig))||(window.__painelTvPendingSpeechKey===key&&window.__painelTvPendingSpeechCallSig===sig&&now-Number(window.__painelTvPendingSpeechAt||0)<pendingWin&&(!generic||!!sig))){safeCall(u&&u.onend,{type:'end'});return;}"
                + "window.__painelTvPendingSpeechKey=key;window.__painelTvPendingSpeechAt=now;window.__painelTvPendingSpeechCallSig=sig;"
                + "this.paused=false;"
                + "waitVisualThenSpeak({token:nextToken(),text:text,lang:lang,rate:rate,pitch:pitch,onstart:u&&u.onstart,onend:u&&u.onend,onerror:u&&u.onerror},Date.now());"
                + "},"
                + "cancel:function(){pendingQueue=[];callbacks={};this.speaking=false;this.pending=false;AndroidSpeechBridge.cancel();},"
                + "pause:function(){this.paused=true;AndroidSpeechBridge.cancel();this.speaking=false;},"
                + "resume:function(){this.paused=false;playNext();},"
                + "addEventListener:function(name,fn){if(name==='voiceschanged'&&typeof fn==='function'){setTimeout(fn,0);}},"
                + "removeEventListener:function(){}"
                + "};"
                + "synth.__painelTvNative=true;"
                + "window.__painelTvNativeTts={"
                + "version:8,"
                + "onStart:function(token){finishToken(String(token),'start');},"
                + "onDone:function(token){var entry=callbacks[String(token)];if(entry){window.__painelTvLastSpokenKey=norm(entry.text||'');window.__painelTvLastSpokenAt=Date.now();window.__painelTvLastSpokenCallSig=norm(callSig());window.__painelTvPendingSpeechKey='';window.__painelTvPendingSpeechCallSig='';}finishToken(String(token),'done');},"
                + "onError:function(token,message){finishToken(String(token),'error',message);},"
                + "onCancel:function(){callbacks={};synth.speaking=false;synth.pending=pendingQueue.length>0;}"
                + "};"
                + "try{Object.defineProperty(window,'speechSynthesis',{value:synth,writable:true,configurable:true});}catch(e){window.speechSynthesis=synth;}"
                + "setTimeout(restorePendingSpeech,1200);"
                + "try{window.dispatchEvent(new Event('voiceschanged'));}catch(e){}"
                + "})();";
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
        String base = normalizeServerUrl(serverUrl);
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
            lastError = "Falha temporaria na comunicacao com o servidor";
        }
    }

    private void applyConfig(JSONObject config) {
        deviceId = config.optString("deviceId", deviceId);
        deviceName = config.optString("name", deviceName);
        fallbackUrl = config.optString("fallbackUrl", fallbackUrl);
        audioEnabled = config.optBoolean("audioEnabled", audioEnabled);
        ttsVoicePreference = config.optString("ttsVoicePreference", ttsVoicePreference);
        heartbeatSeconds = config.optInt("heartbeatIntervalSeconds", heartbeatSeconds);
        autoRefreshSeconds = config.optInt("autoRefreshSeconds", autoRefreshSeconds);
        memoryLimitMb = config.optInt("memoryLimitMb", memoryLimitMb);
        preventiveReloadHours = config.optInt("preventiveReloadHours", preventiveReloadHours);
        pageOfflineTimeoutSeconds = config.optInt("pageOfflineTimeoutSeconds", pageOfflineTimeoutSeconds);
        prefs.edit()
                .putString("fallbackUrl", fallbackUrl)
                .putBoolean("audioEnabled", audioEnabled)
                .putString("ttsVoicePreference", ttsVoicePreference)
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
        forceFreshLoad(false);
    }

    private void recreateWebView() {
        reloads++;
        webViewRecreates++;
        lastWebViewRecreateAt = System.currentTimeMillis();
        createWebView();
        loadUrl(currentUrl, false);
    }

    private void clearCache() {
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            forceFreshLoad(true);
        }
    }

    private void forceFreshLoad(boolean resetPanelCursor) {
        if (webView == null || "about:blank".equals(currentUrl)) return;
        if (resetPanelCursor) lastCallId = "";
        webView.loadUrl(freshReloadUrl(currentUrl));
    }

    private String freshReloadUrl(String url) {
        if (url == null || url.trim().isEmpty() || "about:blank".equals(url)) return "about:blank";
        try {
            Uri source = Uri.parse(url);
            Uri.Builder builder = source.buildUpon().clearQuery();
            for (String name : source.getQueryParameterNames()) {
                if ("_ptvts".equals(name)) continue;
                for (String value : source.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, value);
                }
            }
            builder.appendQueryParameter("_ptvts", String.valueOf(System.currentTimeMillis()));
            return builder.build().toString();
        } catch (Exception ignored) {
            String separator = url.contains("?") ? "&" : "?";
            return url + separator + "_ptvts=" + System.currentTimeMillis();
        }
    }

    private void restartApp() {
        appRestarts++;
        lastFullRestartAt = System.currentTimeMillis();
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
        Runtime.getRuntime().exit(0);
    }

    private void runRecovery(String reason) {
        consecutiveErrors++;
        recoveryCycles++;
        lastError = reason;
        sendClientError(reason + " | ciclo " + recoveryCycles);

        if (recoveryCycles == 1) {
            reloadPage();
        } else if (recoveryCycles == 2) {
            recreateWebView();
        } else if (recoveryCycles == 3) {
            clearCache();
            recreateWebView();
        } else {
            sendClientError("Falha repetida. Entrando no fallback e protegendo contra loop infinito.");
            if (fallbackUrl != null && !fallbackUrl.trim().isEmpty()) {
                loadUrl(fallbackUrl, false);
            }
            if (canRestartAppNow()) {
                main.postDelayed(() -> restartApp(), 3000L);
            }
        }
    }

    private boolean canRestartAppNow() {
        return System.currentTimeMillis() - lastFullRestartAt > 10L * 60L * 1000L;
    }

    private boolean shouldRecoverDeadPanel(long now) {
        if (webView == null || pageLoading || "about:blank".equals(currentUrl)) return false;
        if (activeSpeechToken != null && !activeSpeechToken.isEmpty()) return false;
        if (lastSuccessfulPageLoadAt == 0L || now - lastSuccessfulPageLoadAt < DEAD_PANEL_IDLE_MS) return false;
        if (now - lastPanelAutoReloadAt < DEAD_PANEL_RELOAD_COOLDOWN_MS) return false;
        long lastActivity = Math.max(Math.max(lastPanelNetworkActivityAt, lastSuccessfulPageLoadAt), lastPanelApiSuccessAt);
        return now - lastActivity > DEAD_PANEL_IDLE_MS;
    }

    private void applyAudioSetting() {
        if (webView == null) return;
        if (!audioEnabled && textToSpeech != null) {
            textToSpeech.stop();
            activeSpeechToken = "";
        }
        if (audioEnabled) flushPendingSpeech();
        String muted = audioEnabled ? "false" : "true";
        String script = "(function(){"
                + "var m=document.querySelectorAll('video,audio');"
                + "for(var i=0;i<m.length;i++){m[i].muted=" + muted + ";}"
                + "try{if(" + muted + " && window.speechSynthesis){window.speechSynthesis.cancel();}}catch(e){}"
                + "})();";
        webView.evaluateJavascript(script, null);
    }

    private class NativeSpeechBridge {
        @JavascriptInterface
        public void speak(String token, String text, String lang, double rate, double pitch) {
            if (text == null || text.trim().isEmpty()) return;
            main.post(() -> speakNative(token, text, lang, (float) rate, (float) pitch));
        }

        @JavascriptInterface
        public boolean isTtsEngineReady() {
            return ttsReady && textToSpeech != null;
        }

        @JavascriptInterface
        public void cancel() {
            main.post(() -> {
                pendingSpeech.clear();
                activeSpeechToken = "";
                if (textToSpeech != null) textToSpeech.stop();
                notifyJsSpeechCancel();
                executePendingVisualSync(false);
            });
        }

        @JavascriptInterface
        public void reportPanelActivity(String kind) {
            main.post(() -> {
                String k = kind == null ? "" : kind.toLowerCase(java.util.Locale.ROOT);
                long now = System.currentTimeMillis();
                panelNetworkEvents++;
                if (k.contains("erro")) {
                    if (now - lastPanelNetworkErrorAt < 30_000L) {
                        consecutivePanelNetworkErrors++;
                    } else {
                        consecutivePanelNetworkErrors = 1;
                    }
                    lastPanelNetworkErrorAt = now;
                    if (consecutivePanelNetworkErrors >= 3
                            && webView != null
                            && !pageLoading
                            && !"about:blank".equals(currentUrl)
                            && now - lastPanelAutoReloadAt >= 8_000L
                            && (activeSpeechToken == null || activeSpeechToken.isEmpty())) {
                        consecutivePanelNetworkErrors = 0;
                        panelAutoReloads++;
                        lastPanelAutoReloadAt = now;
                        lastError = "Painel falhou ao buscar dados. Recarregando automaticamente.";
                        reloadPage();
                    }
                    return;
                }
                consecutivePanelNetworkErrors = 0;
                lastPanelNetworkActivityAt = now;
            });
        }
    }

    private static class PendingSpeech {
        final String token;
        final String text;
        final String lang;
        final float rate;
        final float pitch;

        PendingSpeech(String token, String text, String lang, float rate, float pitch) {
            this.token = token;
            this.text = text;
            this.lang = lang;
            this.rate = rate;
            this.pitch = pitch;
        }
    }

    private void speakNative(String token, String text, String lang, float rate, float pitch) {
        if (!audioEnabled) return;
        String speechKey = normalizeMatchText(text);
        String callSignature = lastCallSignature == null ? "" : lastCallSignature;
        long now = System.currentTimeMillis();
        if (!speechKey.isEmpty()
                && speechKey.equals(lastNativeSpeechKey)
                && callSignature.equals(lastNativeSpeechCallSignature)
                && now - lastNativeSpeechAt < 90_000L) {
            deviceLog("TTS_SKIP", "Narracao repetida bloqueada: " + text);
            String doneToken = token == null ? "" : token;
            if (!doneToken.isEmpty()) {
                notifyJsSpeechStart(doneToken);
                main.postDelayed(() -> notifyJsSpeechDone(doneToken), 120L);
            }
            return;
        }
        if (textToSpeech == null || !ttsReady) {
            lastError = "Narrador nativo ainda nao esta pronto";
            queuePendingSpeech(token, text, lang, rate, pitch);
            return;
        }
        activeSpeechToken = token == null ? "" : token;
        lastSpeechRequestAt = now;
        lastNativeSpeechKey = speechKey;
        lastNativeSpeechCallSignature = callSignature;
        lastNativeSpeechAt = now;
        speechRequests++;
        Locale locale = localeFromWebLang(lang);
        textToSpeech.setLanguage(locale);
        boolean voiceMatched = applyVoicePreference(locale);
        textToSpeech.setSpeechRate(clamp(rate, 0.6f, 1.4f));
        float basePitch = clamp(pitch, 0.7f, 1.3f);
        textToSpeech.setPitch(clamp(voiceMatched ? basePitch : applyPreferencePitch(basePitch), 0.7f, 1.3f));
        int result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeSpeechToken);
        if (result != TextToSpeech.SUCCESS) {
            lastError = "Falha ao iniciar narracao nativa";
            queuePendingSpeech(activeSpeechToken, text, lang, rate, pitch);
            notifyJsSpeechError(activeSpeechToken, lastError);
            activeSpeechToken = "";
        }
    }

    private void queuePendingSpeech(String token, String text, String lang, float rate, float pitch) {
        if (text == null || text.trim().isEmpty()) return;
        pendingSpeech.addLast(new PendingSpeech(token, text, lang, rate, pitch));
        while (pendingSpeech.size() > 10) pendingSpeech.removeFirst();
    }

    private void flushPendingSpeech() {
        if (!audioEnabled || textToSpeech == null || !ttsReady || !activeSpeechToken.isEmpty()) return;
        if (!pendingSpeech.isEmpty()) {
            PendingSpeech pending = pendingSpeech.removeFirst();
            speakNative(pending.token, pending.text, pending.lang, pending.rate, pending.pitch);
        }
    }

    private Locale localeFromWebLang(String lang) {
        if (lang == null || lang.trim().isEmpty()) return new Locale("pt", "BR");
        String clean = lang.replace('_', '-').toLowerCase(Locale.ROOT);
        if (clean.startsWith("pt")) return new Locale("pt", "BR");
        if (clean.startsWith("en")) return Locale.US;
        if (clean.startsWith("es")) return new Locale("es", "ES");
        return new Locale("pt", "BR");
    }

    private float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) return 1.0f;
        return Math.max(min, Math.min(max, value));
    }

    private boolean applyVoicePreference(Locale locale) {
        ttsSelectedVoiceName = "";
        ttsAvailableVoiceCount = 0;
        if (textToSpeech == null || !ttsReady) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        String pref = ttsVoicePreference == null ? "default" : ttsVoicePreference.trim().toLowerCase(Locale.ROOT);
        if (!pref.equals("male") && !pref.equals("female")) return false;
        try {
            java.util.Set<android.speech.tts.Voice> voices = textToSpeech.getVoices();
            if (voices == null || voices.isEmpty()) return false;
            ttsAvailableVoiceCount = voices.size();
            android.speech.tts.Voice matched = null;
            String langCode = locale == null ? "" : String.valueOf(locale.getLanguage());
            for (android.speech.tts.Voice voice : voices) {
                if (voice == null || voice.getLocale() == null) continue;
                String vLang = String.valueOf(voice.getLocale().getLanguage());
                if (!vLang.equalsIgnoreCase(langCode)) continue;
                if (voice.isNetworkConnectionRequired()) continue;
                String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
                boolean male = name.contains("male") || name.contains("masc") || name.contains("homem") || name.contains("man");
                boolean female = name.contains("female") || name.contains("fem") || name.contains("mulher") || name.contains("woman");
                if (pref.equals("male") && male) {
                    matched = voice;
                    break;
                }
                if (pref.equals("female") && female) {
                    matched = voice;
                    break;
                }
            }
            if (matched == null) {
                deviceLog("TTS_VOICE", "Nenhuma voz " + pref + " encontrada no dispositivo. Usando ajuste de pitch.");
                return false;
            }
            textToSpeech.setVoice(matched);
            ttsSelectedVoiceName = matched.getName() == null ? "" : matched.getName();
            deviceLog("TTS_VOICE", "Voz aplicada: pref=" + pref + " | voice=" + ttsSelectedVoiceName + " | total=" + ttsAvailableVoiceCount);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private float applyPreferencePitch(float pitch) {
        String pref = ttsVoicePreference == null ? "default" : ttsVoicePreference.trim().toLowerCase(Locale.ROOT);
        if ("male".equals(pref)) return pitch * 0.85f;
        if ("female".equals(pref)) return pitch * 1.10f;
        return pitch;
    }

    private boolean isSpeechStalled(long now) {
        if (activeSpeechToken == null || activeSpeechToken.isEmpty()) return false;
        long base = Math.max(lastSpeechStartedAt, lastSpeechRequestAt);
        return base > 0L && now - base > 15_000L;
    }

    private void recoverSpeechBridge(String reason) {
        consecutiveSpeechStalls++;
        lastError = reason;
        sendClientError(reason + " | tentativa " + consecutiveSpeechStalls);
        String stalledToken = activeSpeechToken;
        activeSpeechToken = "";
        try {
            if (textToSpeech != null) {
                textToSpeech.stop();
                textToSpeech.shutdown();
            }
        } catch (Exception ignored) {
            // Best effort while resetting the TTS engine.
        }
        ttsReady = false;
        initTextToSpeech();
        notifyJsSpeechError(stalledToken, reason);
        if (webView != null) {
            injectSpeechBridge(webView);
            applyAudioSetting();
        }
        if (consecutiveSpeechStalls >= 3) {
            consecutiveSpeechStalls = 0;
            recreateWebView();
        }
    }

    private void notifyJsSpeechStart(String token) {
        notifyJsSpeech("onStart", token, null);
    }

    private void notifyJsSpeechDone(String token) {
        notifyJsSpeech("onDone", token, null);
    }

    private void notifyJsSpeechError(String token, String message) {
        notifyJsSpeech("onError", token, message);
    }

    private void notifyJsSpeechCancel() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){try{if(window.__painelTvNativeTts&&window.__painelTvNativeTts.onCancel){window.__painelTvNativeTts.onCancel();}}catch(e){}})();",
                null
        );
    }

    private void notifyJsSpeech(String method, String token, String message) {
        if (webView == null || token == null || token.trim().isEmpty()) return;
        StringBuilder script = new StringBuilder("(function(){try{if(window.__painelTvNativeTts&&window.__painelTvNativeTts.")
                .append(method)
                .append("){window.__painelTvNativeTts.")
                .append(method)
                .append("(")
                .append(JSONObject.quote(token));
        if (message != null) {
            script.append(",").append(JSONObject.quote(message));
        }
        script.append(");}}catch(e){}})();");
        webView.evaluateJavascript(script.toString(), null);
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null && wifiManager.getConnectionInfo() != null) {
                int ip = wifiManager.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format(Locale.ROOT, "%d.%d.%d.%d",
                            ip & 0xff,
                            (ip >> 8) & 0xff,
                            (ip >> 16) & 0xff,
                            (ip >> 24) & 0xff);
                }
            }
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // IP is informational only.
        }
        return "";
    }

    private void sendHeartbeat() {
        if (socket == null) return;
        try {
            JSONObject telemetry = new JSONObject();
            telemetry.put("status", "online");
            telemetry.put("currentUrl", currentUrl);
            telemetry.put("playerStatus", consecutiveErrors > 0 ? "recovering" : "playing");
            telemetry.put("audioEnabled", audioEnabled);
            telemetry.put("ttsVoicePreference", ttsVoicePreference == null ? "default" : ttsVoicePreference);
            telemetry.put("ttsSelectedVoiceName", ttsSelectedVoiceName == null ? "" : ttsSelectedVoiceName);
            telemetry.put("ttsAvailableVoiceCount", ttsAvailableVoiceCount);
            telemetry.put("memoryMb", currentMemoryMb());
            telemetry.put("reloads", reloads);
            telemetry.put("reconnects", reconnects);
            telemetry.put("recoveryCycles", recoveryCycles);
            telemetry.put("webViewRecreates", webViewRecreates);
            telemetry.put("appRestarts", appRestarts);
            telemetry.put("speechRequests", speechRequests);
            telemetry.put("speechCompletions", speechCompletions);
            telemetry.put("speechActive", !activeSpeechToken.isEmpty());
            telemetry.put("panelNetworkEvents", panelNetworkEvents);
            telemetry.put("panelAutoReloads", panelAutoReloads);
            telemetry.put("panelPollingActive", !panelId.isEmpty());
            telemetry.put("panelApiFailures", panelApiFailures);
            telemetry.put("pendingSpeechCount", pendingSpeech.size());
            telemetry.put("pageLoading", pageLoading);
            telemetry.put("lastPanelNetworkActivitySecondsAgo", lastPanelNetworkActivityAt == 0L ? -1 : (System.currentTimeMillis() - lastPanelNetworkActivityAt) / 1000L);
            telemetry.put("lastPanelApiSuccessSecondsAgo", lastPanelApiSuccessAt == 0L ? -1 : (System.currentTimeMillis() - lastPanelApiSuccessAt) / 1000L);
            telemetry.put("lastSuccessfulPageLoadSecondsAgo", lastSuccessfulPageLoadAt == 0L ? -1 : (System.currentTimeMillis() - lastSuccessfulPageLoadAt) / 1000L);
            telemetry.put("lastWebViewRecreateSecondsAgo", lastWebViewRecreateAt == 0L ? -1 : (System.currentTimeMillis() - lastWebViewRecreateAt) / 1000L);
            telemetry.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000L);
            telemetry.put("lastError", lastError == null ? "" : lastError);
            telemetry.put("lastPanelApiError", lastPanelApiError == null ? "" : lastPanelApiError);
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
        long now = System.currentTimeMillis();
        if (now - lastClientErrorSentAt < 60_000L) return;
        lastClientErrorSentAt = now;
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

    private void deviceLog(String tag, String msg) {
        Log.d("PAINEL_TV", "[" + tag + "] " + msg);
        if (socket == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "device_log");
            obj.put("tag", tag);
            obj.put("msg", msg);
            socket.send(obj.toString());
        } catch (JSONException ignored) {}
    }

    private void tryStartKiosk() {
        try {
            startLockTask();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Full lock task requires device-owner provisioning. Immersive mode still protects normal use.
        }
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
            String newId   = m.group(2);
            if (!newId.equals(panelId) || !newHost.equals(panelApiHost)) {
                panelApiHost = newHost;
                panelId      = newId;
                lastCallId   = "";
                main.removeCallbacks(panelPollRunnable);
                main.postDelayed(panelPollRunnable, 1_000L);
            }
        } else {
            if (!panelId.isEmpty()) {
                panelId      = "";
                panelApiHost = "";
                main.removeCallbacks(panelPollRunnable);
            }
        }
    }

    private void pollPanelApi() {
        if (panelId.isEmpty() || panelApiHost.isEmpty()) return;
        String apiUrl = panelApiHost + "/unique-panel/api/call/history"
                + "?panelId=" + panelId
                + "&limit=3&sort=updatedAt:desc&select=id,personal,local,professional,attempts,updatedAt";
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Android TV) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Cache-Control", "no-cache, no-store")
                .build();
        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                main.post(() -> {
                    panelApiFailures++;
                    lastPanelApiError = e == null ? "Falha ao consultar API do painel" : String.valueOf(e.getMessage());
                });
            }
            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (!response.isSuccessful()) {
                    int statusCode = response.code();
                    response.close();
                    main.post(() -> {
                        panelApiFailures++;
                        lastPanelApiError = "API painel HTTP " + statusCode;
                    });
                    return;
                }
                String body = response.body().string();
                response.close();
                String[] values = firstPanelCall(body);
                String firstId = values[0];
                String name    = values[1];
                String local   = values[2];
                String professional = values[3];
                String attempts = values[4];
                String updatedAt = values[5];
                long successAt = System.currentTimeMillis();
                main.post(() -> {
                    lastPanelApiSuccessAt = successAt;
                    panelApiFailures = 0;
                    lastPanelApiError = "";
                    lastPanelNetworkActivityAt = successAt;
                    panelNetworkEvents++;
                });
                main.post(() -> {
                    lastPanelApiError = "";
                    if (firstId != null && !firstId.trim().isEmpty()) {
                        onPanelApiResult(
                                firstId.trim(),
                                name == null ? "" : name.trim(),
                                local == null ? "" : local.trim(),
                                professional == null ? "" : professional.trim(),
                                attempts == null ? "" : attempts.trim(),
                                updatedAt == null ? "" : updatedAt.trim()
                        );
                    }
                });
            }
        });
    }

    private void onPanelApiResult(String firstId, String name, String local, String professional, String attempts, String updatedAt) {
        String signature = buildCallSignature(firstId, name, local, professional, attempts, updatedAt);
        if (signature.equals(lastCallSignature)) {
            return;
        }
        boolean isFirst = lastCallSignature.isEmpty();
        lastCallSignature = signature;
        lastCallId = firstId;
        lastCallName = name == null ? "" : name;
        lastCallLocal = local == null ? "" : local;
        lastCallProfessional = professional == null ? "" : professional;
        publishCallSignatureToPanel(signature, firstId, lastCallName, lastCallLocal);
        consecutiveRenderMismatches = 0;
        lastRenderCheckCallId = "";
        lastVisualReloadCallId = "";
        lastVisualRecreateCallId = "";
        if (isFirst) {
            deviceLog("CALL", "Primeira chamada registrada: " + name + " | " + local + " | id=" + firstId);
            schedulePanelRenderChecks(firstId, name, local);
            return;
        }
        deviceLog("CALL", "Nova chamada detectada: " + name + " | " + local + " | id=" + firstId);
        scheduleNativeFallbackAnnouncement(firstId, signature, name, local, professional);
        schedulePanelRenderChecks(firstId, name, local);
    }

    private void syncWebViewForNewCall(String callId) {
        if (callId == null || callId.isEmpty()) return;
        if (webView == null || "about:blank".equals(currentUrl)) {
            deviceLog("VISUAL_SYNC", "Sincronizacao ignorada: WebView indisponivel para id=" + callId);
            return;
        }
        if ((activeSpeechToken != null && !activeSpeechToken.isEmpty())
                || (textToSpeech != null && textToSpeech.isSpeaking())) {
            requestVisualSyncWhenIdle(callId);
            return;
        }
        if (callId.equals(lastVisualReloadCallId)) return;
        lastVisualReloadCallId = callId;
        lastRenderRecoveryAt = System.currentTimeMillis();
        panelAutoReloads++;
        lastError = "Nova chamada detectada. Sincronizando tela automaticamente.";
        if (pageLoading) {
            try {
                webView.stopLoading();
            } catch (Exception ignored) {
            }
            pageLoading = false;
        }
        deviceLog("VISUAL_SYNC", "Recarregando painel real para alinhar chamada id=" + callId);
        applyChromeKeepAlive(webView);
        injectPanelNetworkMonitor(webView);
        injectPanelNextObserver(webView);
        injectSpeechBridge(webView);
        webView.loadUrl(freshReloadUrl(currentUrl));
    }

    private void requestVisualSyncWhenIdle(String callId) {
        if (callId == null || callId.isEmpty()) return;
        pendingVisualSyncCallId = callId;
        pendingVisualSyncAt = System.currentTimeMillis();
        lastError = "Chamada nova detectada. A tela sera sincronizada assim que a fala terminar.";
        deviceLog("VISUAL_SYNC", "Sincronizacao pendente ate a fala terminar: id=" + callId);
        main.removeCallbacks(pendingVisualSyncRunnable);
        main.postDelayed(pendingVisualSyncRunnable, 1_000L);
    }

    private final Runnable pendingVisualSyncRunnable = new Runnable() {
        @Override
        public void run() {
            executePendingVisualSync(false);
        }
    };

    private void executePendingVisualSync(boolean force) {
        String callId = pendingVisualSyncCallId == null ? "" : pendingVisualSyncCallId;
        if (callId.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!callId.equals(lastCallId) || now - pendingVisualSyncAt > PENDING_VISUAL_SYNC_MAX_AGE_MS) {
            pendingVisualSyncCallId = "";
            pendingVisualSyncAt = 0L;
            return;
        }
        if (!force && ((activeSpeechToken != null && !activeSpeechToken.isEmpty())
                || (textToSpeech != null && textToSpeech.isSpeaking()))) {
            main.removeCallbacks(pendingVisualSyncRunnable);
            main.postDelayed(pendingVisualSyncRunnable, 1_000L);
            return;
        }
        pendingVisualSyncCallId = "";
        pendingVisualSyncAt = 0L;
        syncWebViewForNewCall(callId);
    }

    private void schedulePanelRenderChecks(String firstId, String name, String local) {
        main.postDelayed(() -> verifyPanelRendering(firstId, name, local), 1_500L);
        main.postDelayed(() -> verifyPanelRendering(firstId, name, local), 3_500L);
        main.postDelayed(() -> verifyPanelRendering(firstId, name, local), 6_500L);
        main.postDelayed(() -> verifyPanelRendering(firstId, name, local), 12_000L);
    }

    private void scheduleNativeFallbackAnnouncement(String callId, String signature, String name, String local, String professional) {
        pendingFallbackCallId = callId == null ? "" : callId;
        pendingFallbackSignature = signature == null ? "" : signature;
        pendingFallbackName = name == null ? "" : name;
        pendingFallbackLocal = local == null ? "" : local;
        pendingFallbackProfessional = professional == null ? "" : professional;
        pendingFallbackText = buildFullCallAnnouncement(name, local, professional);
        pendingFallbackSpeechRequests = speechRequests;
        pendingFallbackAt = System.currentTimeMillis();
        main.removeCallbacks(nativeAudioFallbackRunnable);
        main.postDelayed(nativeAudioFallbackRunnable, NATIVE_AUDIO_FALLBACK_DELAY_MS);
        deviceLog("FALLBACK", "Fallback nativo agendado para garantir o nome do paciente se a pagina falhar.");
    }

    private void clearPendingFallbackAnnouncement() {
        pendingFallbackCallId = "";
        pendingFallbackSignature = "";
        pendingFallbackName = "";
        pendingFallbackText = "";
        pendingFallbackLocal = "";
        pendingFallbackProfessional = "";
        pendingFallbackSpeechRequests = speechRequests;
        pendingFallbackAt = 0L;
    }

    private void announceNativeCall(String text) {
        if (!audioEnabled || text == null || text.trim().isEmpty()) return;
        String token = "nativecall_" + System.currentTimeMillis();
        if (textToSpeech == null || !ttsReady || (activeSpeechToken != null && !activeSpeechToken.isEmpty())) {
            queuePendingSpeech(token, text, "pt-BR", 1.0f, 1.0f);
            return;
        }
        speakNative(token, text, "pt-BR", 1.0f, 1.0f);
    }

    private void refreshPanelForNewCall() {
        long now = System.currentTimeMillis();
        long pageAge = lastSuccessfulPageLoadAt == 0L ? Long.MAX_VALUE : now - lastSuccessfulPageLoadAt;
        if (pageAge >= STALE_PAGE_RECREATE_MS) {
            lastError = "Nova chamada detectada com pagina antiga. Recriando WebView.";
            recreateWebView();
            return;
        }
        reloadPage();
    }

    private void verifyPanelRendering(String firstId, String name, String local) {
        if (webView == null || pageLoading || "about:blank".equals(currentUrl)) return;
        String expectedName = normalizeMatchText(name);
        if (expectedName.isEmpty()) return;
        String script = "(function(){try{"
                + "function norm(v){v=String(v||'').toLowerCase();try{v=v.normalize('NFD').replace(/[\\u0300-\\u036f]/g,'');}catch(e){}return v.replace(/\\s+/g,' ').trim();}"
                + "var lines=String((document.body&&document.body.innerText)||'').split(/\\n+/).map(function(x){return x.trim();}).filter(Boolean);"
                + "var patient='';"
                + "for(var i=0;i<lines.length-1;i++){if(norm(lines[i])==='paciente'){patient=lines[i+1]||'';break;}}"
                + "var current=norm(patient);"
                + "var expected=" + JSONObject.quote(expectedName) + ";"
                + "return !!current&&(current.indexOf(expected)>=0||expected.indexOf(current)>=0);"
                + "}catch(e){return false;}})();";
        webView.evaluateJavascript(script, value -> handlePanelRenderCheck(firstId, "true".equalsIgnoreCase(String.valueOf(value))));
    }

    private void handlePanelRenderCheck(String firstId, boolean rendered) {
        if (rendered) {
            consecutiveRenderMismatches = 0;
            lastRenderCheckCallId = firstId == null ? "" : firstId;
            if (firstId != null && firstId.equals(lastCallId)) {
                lastVisualReloadCallId = "";
                lastVisualRecreateCallId = "";
            }
            return;
        }
        if (firstId == null || firstId.isEmpty()) return;
        if (!firstId.equals(lastCallId)) return;
        if (!firstId.equals(lastRenderCheckCallId)) {
            lastRenderCheckCallId = firstId;
            consecutiveRenderMismatches = 1;
            return;
        }
        consecutiveRenderMismatches++;
        if (panelId != null && !panelId.isEmpty()) {
            long now = System.currentTimeMillis();
            if (consecutiveRenderMismatches >= RENDER_MISMATCH_MIN_STREAK
                    && now - lastRenderRecoveryAt >= RENDER_MISMATCH_RECOVERY_COOLDOWN_MS) {
                if ((activeSpeechToken != null && !activeSpeechToken.isEmpty())
                        || (textToSpeech != null && textToSpeech.isSpeaking())) {
                    requestVisualSyncWhenIdle(firstId);
                    return;
                }
                syncWebViewForNewCall(firstId);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (consecutiveRenderMismatches >= RENDER_MISMATCH_MIN_STREAK + 1
            && !firstId.equals(lastVisualRecreateCallId)
            && now - lastRenderRecoveryAt >= RENDER_MISMATCH_RECOVERY_COOLDOWN_MS) {
            if ((activeSpeechToken != null && !activeSpeechToken.isEmpty())
                    || (textToSpeech != null && textToSpeech.isSpeaking())) {
                requestVisualSyncWhenIdle(firstId);
                return;
            }
            lastVisualRecreateCallId = firstId;
            lastRenderRecoveryAt = now;
            lastError = "Tela continuou congelada apos reload. Recriando WebView.";
            recreateWebView();
        }
    }

    private String normalizeMatchText(String value) {
        if (value == null) return "";
        String clean = java.text.Normalizer.normalize(value.trim().toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return clean;
    }

    private String buildCallSignature(String id, String name, String local, String professional, String attempts, String updatedAt) {
        return normalizeMatchText(id)
                + "|" + normalizeMatchText(name)
                + "|" + normalizeMatchText(local)
                + "|" + normalizeMatchText(professional)
                + "|" + normalizeMatchText(attempts)
                + "|" + normalizeMatchText(updatedAt);
    }

    private void publishCallSignatureToPanel(String signature, String callId, String name, String local) {
        if (webView == null || "about:blank".equals(currentUrl)) return;
        String cleanSig = signature == null ? "" : signature.trim();
        if (cleanSig.isEmpty()) return;
        String cleanId = callId == null ? "" : callId.trim();
        String cleanName = name == null ? "" : name.trim();
        String cleanLocal = local == null ? "" : local.trim();
        String script = "(function(){try{"
                + "window.__painelTvLastCallSignature=" + JSONObject.quote(cleanSig) + ";"
                + "window.__painelTvLastDomSync=window.__painelTvLastDomSync||{};"
                + "window.__painelTvLastDomSync.signature=" + JSONObject.quote(cleanSig) + ";"
                + "window.__painelTvLastDomSync.id=" + JSONObject.quote(cleanId) + ";"
                + "window.__painelTvLastDomSync.expected=" + JSONObject.quote(cleanName) + ";"
                + "window.__painelTvLastDomSync.local=" + JSONObject.quote(cleanLocal) + ";"
                + "window.__painelTvLastDomSync.at=Date.now();"
                + "}catch(e){}})();";
        webView.evaluateJavascript(script, null);
    }

    private String buildPatientOnlyAnnouncement(String name) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) return "";
        return "Chamando o paciente " + cleanName + ".";
    }

    private String buildFullCallAnnouncement(String name, String local, String professional) {
        String cleanName = name == null ? "" : name.trim();
        String cleanLocal = local == null ? "" : local.trim();
        String cleanProfessional = professional == null ? "" : professional.trim();
        if (cleanName.isEmpty() && cleanLocal.isEmpty()) return "";
        StringBuilder msg = new StringBuilder();
        if (!cleanName.isEmpty()) {
            msg.append("Chamando o paciente ").append(cleanName).append(".");
        } else {
            msg.append("Chamando.");
        }
        if (!cleanLocal.isEmpty()) {
            msg.append(" Por favor comparecer a sala ").append(cleanLocal).append(".");
        }
        if (!cleanProfessional.isEmpty()) {
            msg.append(" Com profissional ").append(cleanProfessional).append(".");
        }
        return msg.toString();
    }

    private String[] firstPanelCall(String body) {
        if (body == null || body.trim().isEmpty()) return new String[]{"", "", "", "", "", ""};
        try {
            JSONObject root = new JSONObject(body);
            org.json.JSONArray data = root.optJSONArray("data");
            if (data != null && data.length() > 0) {
                JSONObject first = data.optJSONObject(0);
                if (first != null) {
                    return new String[]{
                            first.optString("id", "").trim(),
                            first.optString("personal", "").trim(),
                            first.optString("local", "").trim(),
                            first.optString("professional", "").trim(),
                            String.valueOf(first.opt("attempts") == null ? "" : first.opt("attempts")).trim(),
                            first.optString("updatedAt", "").trim()
                    };
                }
            }
        } catch (Exception ignored) {
            // Fallback for environments that still return XML.
        }
        return new String[]{
                firstXmlTag(body, "id"),
                firstXmlTag(body, "personal"),
                firstXmlTag(body, "local"),
                firstXmlTag(body, "professional"),
                firstXmlTag(body, "attempts"),
                firstXmlTag(body, "updatedAt")
        };
    }

    private String buildCallAnnouncementForFallback(
            String name,
            String local,
            String professional,
            boolean pageSpokeAfterBaseline,
            boolean pageAlreadySpokePatient,
            boolean pageAlreadySpokeLocal,
            boolean pageAlreadySpokeProfessional
    ) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) return "";
        if (pageAlreadySpokePatient) return "";
        return buildFullCallAnnouncement(name, local, professional);
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
