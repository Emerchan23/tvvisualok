package br.com.paineltv.gestor;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String PREFS = "painel_gestor";
    private static final String DEFAULT_SERVER = "http://192.168.68.112:9090";
    private static final String DEFAULT_ADMIN_TOKEN = "admin123";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private SharedPreferences prefs;
    private FrameLayout root;
    private SurfaceView cameraPreview;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private boolean scanning = false;
    private boolean decoding = false;

    private String serverUrl;
    private String adminToken;
    private String pairingId = "";

    private EditText serverInput;
    private EditText tokenInput;
    private TextView status;
    private LinearLayout pairingList;
    private LinearLayout deviceList;
    private JSONArray lastPairings = new JSONArray();
    private JSONArray lastDevices = new JSONArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = prefs.getString("serverUrl", DEFAULT_SERVER);
        adminToken = prefs.getString("adminToken", DEFAULT_ADMIN_TOKEN);
        root = new FrameLayout(this);
        setContentView(root);
        showHome(true);
    }

    @Override
    protected void onPause() {
        stopCamera();
        super.onPause();
    }

    private void showHome(boolean autoLoadDashboard) {
        stopCamera();

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox();
        scroll.addView(box);

        TextView title = title("Gestor Painel TV");
        TextView help = paragraph("Use este app para aprovar QR Code e controlar as TVs cadastradas.");

        serverInput = input("Servidor, exemplo http://192.168.0.10:9090", serverUrl);
        tokenInput = input("Token admin", adminToken);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout row1 = row();
        Button save = button("Salvar");
        save.setOnClickListener(view -> {
            savePrefs();
            statusMessage("Configuracao salva.");
        });
        Button refresh = button("Atualizar TVs");
        refresh.setOnClickListener(view -> {
            savePrefs();
            loadDashboard();
        });
        row1.addView(save, rowParam());
        row1.addView(refresh, rowParam());

        LinearLayout row2 = row();
        Button scan = button("Ler QR da TV");
        scan.setOnClickListener(view -> {
            savePrefs();
            showScanner();
        });
        Button manual = button("Codigo manual");
        manual.setOnClickListener(view -> {
            savePrefs();
            showManualPairing();
        });
        row2.addView(scan, rowParam());
        row2.addView(manual, rowParam());

        status = paragraph("");
        pairingList = new LinearLayout(this);
        pairingList.setOrientation(LinearLayout.VERTICAL);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);

        box.addView(title, matchWrap());
        box.addView(help, matchWrap());
        box.addView(serverInput, matchWrap());
        box.addView(tokenInput, matchWrap());
        box.addView(row1, matchWrap());
        box.addView(row2, matchWrap());
        box.addView(status, matchWrap());
        box.addView(sectionTitle("Pareamentos pendentes"), matchWrap());
        box.addView(pairingList, matchWrap());
        box.addView(sectionTitle("TVs cadastradas"), matchWrap());
        box.addView(deviceList, matchWrap());

        root.removeAllViews();
        root.addView(scroll);

        renderPairings();
        renderDevices();
        if (autoLoadDashboard) loadDashboard();
    }

    private void loadDashboard() {
        statusMessage("Buscando pareamentos e TVs...");
        loadPairings();
        loadDevices();
    }

    private void loadPairings() {
        Request request = new Request.Builder()
                .url(apiUrl("/api/pairing?token=" + Uri.encode(adminToken)))
                .get()
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                main.post(() -> statusMessage("Nao consegui carregar os pareamentos pendentes."));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                if (!response.isSuccessful()) {
                    main.post(() -> statusMessage(response.code() == 401
                            ? "Token admin incorreto."
                            : "Servidor recusou a consulta dos pareamentos: " + response.code()));
                    return;
                }
                try {
                    JSONArray pairings = new JSONObject(text).optJSONArray("pairingSessions");
                    lastPairings = pairings == null ? new JSONArray() : pairings;
                    main.post(() -> {
                        renderPairings();
                        statusMessage(String.format(Locale.ROOT, "%d pareamento(s) pendente(s).", lastPairings.length()));
                    });
                } catch (JSONException e) {
                    main.post(() -> statusMessage("Servidor respondeu pareamentos em formato invalido."));
                }
            }
        });
    }

    private void loadDevices() {
        Request request = new Request.Builder()
                .url(apiUrl("/api/devices?token=" + Uri.encode(adminToken)))
                .get()
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                main.post(() -> statusMessage("Nao consegui conectar no servidor. Confira IP e porta."));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                if (!response.isSuccessful()) {
                    main.post(() -> statusMessage(response.code() == 401
                            ? "Token admin incorreto."
                            : "Servidor recusou a consulta: " + response.code()));
                    return;
                }
                try {
                    JSONArray devices = new JSONObject(text).optJSONArray("devices");
                    lastDevices = devices == null ? new JSONArray() : devices;
                    main.post(() -> {
                        renderDevices();
                        statusMessage(String.format(Locale.ROOT,
                                "%d pareamento(s) pendente(s) e %d TV(s) carregada(s).",
                                lastPairings.length(),
                                lastDevices.length()));
                    });
                } catch (JSONException e) {
                    main.post(() -> statusMessage("Servidor respondeu TVs em formato invalido."));
                }
            }
        });
    }

    private void renderPairings() {
        if (pairingList == null) return;
        pairingList.removeAllViews();
        if (lastPairings.length() == 0) {
            pairingList.addView(paragraph("Nenhuma TV aguardando aprovacao agora."), matchWrap());
            return;
        }
        for (int i = 0; i < lastPairings.length(); i++) {
            JSONObject pairing = lastPairings.optJSONObject(i);
            if (pairing != null) pairingList.addView(pairingCard(pairing), matchWrap());
        }
    }

    private View pairingCard(JSONObject pairing) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(18, 16, 18, 16);
        card.setBackgroundColor(0xff16323a);

        String id = pairing.optString("id", "");
        String code = pairing.optString("code", "-");
        String name = pairing.optString("suggestedName", "TV aguardando aprovacao");
        String ip = pairing.optString("ipAddress", "nao informado");
        String appVersion = pairing.optString("appVersion", "-");

        TextView nameText = sectionTitle(name);
        TextView info = paragraphLeft(
                "Codigo: " + code +
                        " | IP: " + ip +
                        "\nApp: " + appVersion +
                        "\nAbra a aprovacao para informar nome e link da TV."
        );

        LinearLayout actions = row();
        Button approve = button("Aprovar");
        approve.setOnClickListener(v -> {
            pairingId = id;
            showApprovalForm(pairing);
        });
        actions.addView(approve, rowParam());

        card.addView(nameText, matchWrap());
        card.addView(info, matchWrap());
        card.addView(actions, matchWrap());
        return card;
    }

    private void renderDevices() {
        if (deviceList == null) return;
        deviceList.removeAllViews();
        if (lastDevices.length() == 0) {
            deviceList.addView(paragraph("Nenhuma TV carregada. Toque em Atualizar TVs."), matchWrap());
            return;
        }
        for (int i = 0; i < lastDevices.length(); i++) {
            JSONObject device = lastDevices.optJSONObject(i);
            if (device != null) deviceList.addView(deviceCard(device), matchWrap());
        }
    }

    private View deviceCard(JSONObject device) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(18, 16, 18, 16);
        card.setBackgroundColor(0xff102027);

        String id = device.optString("id", "");
        String name = device.optString("name", "TV sem nome");
        boolean online = device.optBoolean("online", false);
        boolean audioEnabled = device.optBoolean("audioEnabled", true);
        String ip = device.optString("ipAddress", "nao informado");
        String url = device.optString("currentUrl", "about:blank");
        String heartbeat = device.optString("lastHeartbeatAt", "");

        TextView nameText = sectionTitle(name);
        TextView info = paragraphLeft(
                (online ? "Online" : "Offline") +
                        " | IP: " + ip +
                        " | Audio: " + (audioEnabled ? "ativo" : "mutado") +
                        "\nUltimo sinal: " + friendlyDate(heartbeat) +
                        "\nURL: " + url
        );

        LinearLayout rowA = row();
        Button edit = button("Editar");
        edit.setOnClickListener(v -> showEditDeviceForm(device));
        Button reload = button("Recarregar");
        reload.setOnClickListener(v -> sendCommand(id, "reload_page", "Recarregando painel..."));
        Button webview = button("Reiniciar tela");
        webview.setOnClickListener(v -> sendCommand(id, "recreate_webview", "Reiniciando tela WebView..."));
        rowA.addView(edit, rowParam());
        rowA.addView(reload, rowParam());
        rowA.addView(webview, rowParam());

        LinearLayout rowB = row();
        Button restart = button("Reiniciar app");
        restart.setOnClickListener(v -> sendCommand(id, "restart_app", "Reiniciando aplicativo da TV..."));
        Button cache = button("Limpar cache");
        cache.setOnClickListener(v -> sendCommand(id, "clear_cache", "Limpando cache da TV..."));
        rowB.addView(restart, rowParam());
        rowB.addView(cache, rowParam());

        LinearLayout rowC = row();
        Button audio = button(audioEnabled ? "Mutar audio" : "Ativar audio");
        audio.setOnClickListener(v -> setAudio(id, !audioEnabled));
        Button openPanel = button("Abrir painel");
        openPanel.setOnClickListener(v -> {
            serverUrl = normalizeServerUrl(serverUrl);
            statusMessage("Painel desktop: " + serverUrl);
        });
        rowC.addView(audio, rowParam());
        rowC.addView(openPanel, rowParam());

        card.addView(nameText, matchWrap());
        card.addView(info, matchWrap());
        card.addView(rowA, matchWrap());
        card.addView(rowB, matchWrap());
        card.addView(rowC, matchWrap());
        return card;
    }

    private void sendCommand(String deviceId, String command, String message) {
        savePrefs();
        statusMessage(message);
        try {
            JSONObject body = new JSONObject();
            body.put("command", command);
            body.put("payload", new JSONObject());
            Request request = new Request.Builder()
                    .url(apiUrl("/api/devices/" + Uri.encode(deviceId) + "/commands?token=" + Uri.encode(adminToken)))
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            http.newCall(request).enqueue(new SimpleCallback("Comando enviado.", true));
        } catch (JSONException e) {
            statusMessage("Erro preparando comando.");
        }
    }

    private void setAudio(String deviceId, boolean enabled) {
        savePrefs();
        statusMessage(enabled ? "Ativando audio..." : "Mutando audio...");
        try {
            JSONObject body = new JSONObject();
            body.put("audioEnabled", enabled);
            Request request = new Request.Builder()
                    .url(apiUrl("/api/devices/" + Uri.encode(deviceId) + "?token=" + Uri.encode(adminToken)))
                    .patch(RequestBody.create(body.toString(), JSON))
                    .build();
            http.newCall(request).enqueue(new SimpleCallback(enabled ? "Audio ativado." : "Audio mutado.", true));
        } catch (JSONException e) {
            statusMessage("Erro preparando alteracao de audio.");
        }
    }

    private class SimpleCallback implements Callback {
        private final String successMessage;
        private final boolean refreshAfter;

        SimpleCallback(String successMessage, boolean refreshAfter) {
            this.successMessage = successMessage;
            this.refreshAfter = refreshAfter;
        }

        @Override
        public void onFailure(Call call, IOException e) {
            main.post(() -> statusMessage("Falha de comunicacao. Confira se a TV e o servidor estao online."));
        }

        @Override
        public void onResponse(Call call, Response response) {
            response.close();
            main.post(() -> {
                if (response.isSuccessful()) {
                    statusMessage(successMessage);
                    if (refreshAfter) loadDevices();
                } else if (response.code() == 401) {
                    statusMessage("Token admin incorreto.");
                } else {
                    statusMessage("Servidor recusou a acao: " + response.code());
                }
            });
        }
    }

    private void showScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 41);
            return;
        }

        scanning = true;
        FrameLayout layout = new FrameLayout(this);
        cameraPreview = new SurfaceView(this);
        surfaceHolder = cameraPreview.getHolder();
        surfaceHolder.addCallback(this);
        layout.addView(cameraPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        overlay.setPadding(24, 24, 24, 36);
        overlay.setBackgroundColor(0x77000000);

        TextView tip = paragraph("Aponte para o QR Code na tela da TV");
        Button cancel = button("Cancelar");
        cancel.setOnClickListener(view -> showHome(false));

        overlay.addView(tip, matchWrap());
        overlay.addView(cancel, matchWrap());
        layout.addView(overlay);

        root.removeAllViews();
        root.addView(layout);
    }

    private void showManualPairing() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox();
        scroll.addView(box);
        TextView title = title("Aprovar por codigo");
        EditText idInput = input("ID do pareamento ou URL do QR", "");
        Button open = button("Continuar");
        status = paragraph("");
        open.setOnClickListener(view -> {
            savePrefsFromMemory();
            String value = idInput.getText().toString().trim();
            if (value.contains("pair.html") || value.contains("/pair")) {
                handleQrValue(value);
            } else if (!value.isEmpty()) {
                pairingId = value;
                loadPairingAndShowForm();
            }
        });
        Button back = button("Voltar");
        back.setOnClickListener(view -> showHome(false));
        box.addView(title, matchWrap());
        box.addView(idInput, matchWrap());
        box.addView(open, matchWrap());
        box.addView(back, matchWrap());
        box.addView(status, matchWrap());
        root.removeAllViews();
        root.addView(scroll);
    }

    private void handleQrValue(String value) {
        try {
            Uri uri = Uri.parse(value);
            String id = uri.getQueryParameter("id");
            if (id == null || id.trim().isEmpty()) {
                showHomeWithStatus("QR invalido: nao encontrei o ID do pareamento.");
                return;
            }
            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            if (scheme != null && authority != null) {
                serverUrl = scheme + "://" + authority;
                prefs.edit().putString("serverUrl", serverUrl).apply();
            }
            pairingId = id;
            loadPairingAndShowForm();
        } catch (Exception error) {
            showHomeWithStatus("QR invalido: " + error.getMessage());
        }
    }

    private void loadPairingAndShowForm() {
        statusMessage("Carregando pareamento...");
        Request request = new Request.Builder()
                .url(apiUrl("/api/pairing/" + Uri.encode(pairingId) + "?token=" + Uri.encode(adminToken)))
                .get()
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                main.post(() -> showHomeWithStatus("Falha ao consultar servidor. Confira IP e porta."));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String text = response.body() == null ? "{}" : response.body().string();
                if (!response.isSuccessful()) {
                    main.post(() -> showHomeWithStatus(response.code() == 401
                            ? "Token admin incorreto."
                            : "Servidor recusou: " + response.code()));
                    return;
                }
                try {
                    JSONObject pairing = new JSONObject(text).getJSONObject("pairing");
                    main.post(() -> showApprovalForm(pairing));
                } catch (JSONException e) {
                    main.post(() -> showHomeWithStatus("Resposta invalida do servidor."));
                }
            }
        });
    }

    private void showApprovalForm(JSONObject pairing) {
        stopCamera();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox();
        scroll.addView(box);
        String code = pairing.optString("code", "");

        TextView title = title("Aprovar TV");
        TextView help = paragraphLeft(String.format(Locale.ROOT,
                "Codigo: %s\nStatus: %s\nIP: %s\nApp: %s",
                code,
                pairing.optString("status", "-"),
                pairing.optString("ipAddress", "nao informado"),
                pairing.optString("appVersion", "-")));

        EditText name = input("Nome da TV", pairing.optString("suggestedName", "TV " + code));
        EditText location = input("Local", "");
        EditText group = input("Grupo", "");
        EditText url = input("Link do painel", "about:blank");
        EditText fallback = input("Link fallback", "about:blank");
        Button approve = button("Aprovar e conectar");
        Button back = button("Voltar");
        status = paragraph("");

        approve.setOnClickListener(view -> approvePairing(
                name.getText().toString(),
                location.getText().toString(),
                group.getText().toString(),
                url.getText().toString(),
                fallback.getText().toString()
        ));
        back.setOnClickListener(view -> showHome(true));

        box.addView(title, matchWrap());
        box.addView(help, matchWrap());
        box.addView(name, matchWrap());
        box.addView(location, matchWrap());
        box.addView(group, matchWrap());
        box.addView(url, matchWrap());
        box.addView(fallback, matchWrap());
        box.addView(approve, matchWrap());
        box.addView(back, matchWrap());
        box.addView(status, matchWrap());

        root.removeAllViews();
        root.addView(scroll);
    }

    private void showEditDeviceForm(JSONObject device) {
        stopCamera();
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = baseBox();
        scroll.addView(box);

        String deviceId = device.optString("id", "");
        TextView title = title("Editar TV");
        TextView help = paragraphLeft(String.format(Locale.ROOT,
                "ID: %s\nIP: %s\nAqui voce pode alterar nome e links da TV cadastrada.",
                deviceId,
                device.optString("ipAddress", "nao informado")));

        EditText name = input("Nome da TV", device.optString("name", "TV sem nome"));
        EditText location = input("Local", device.optString("location", ""));
        EditText group = input("Grupo", device.optString("group", ""));
        EditText url = input("Link do painel", device.optString("currentUrl", "about:blank"));
        EditText fallback = input("Link fallback", device.optString("fallbackUrl", "about:blank"));
        Button save = button("Salvar alteracoes");
        Button back = button("Voltar");
        status = paragraph("");

        save.setOnClickListener(view -> updateDevice(
                deviceId,
                name.getText().toString(),
                location.getText().toString(),
                group.getText().toString(),
                url.getText().toString(),
                fallback.getText().toString()
        ));
        back.setOnClickListener(view -> showHome(true));

        box.addView(title, matchWrap());
        box.addView(help, matchWrap());
        box.addView(name, matchWrap());
        box.addView(location, matchWrap());
        box.addView(group, matchWrap());
        box.addView(url, matchWrap());
        box.addView(fallback, matchWrap());
        box.addView(save, matchWrap());
        box.addView(back, matchWrap());
        box.addView(status, matchWrap());

        root.removeAllViews();
        root.addView(scroll);
    }

    private void approvePairing(String name, String location, String group, String url, String fallback) {
        statusMessage("Aprovando TV...");
        try {
            JSONObject body = new JSONObject();
            body.put("name", name == null || name.trim().isEmpty() ? "TV sem nome" : name.trim());
            body.put("location", location);
            body.put("group", group);
            body.put("currentUrl", url == null || url.trim().isEmpty() ? "about:blank" : url);
            body.put("fallbackUrl", fallback == null || fallback.trim().isEmpty() ? "about:blank" : fallback);
            body.put("audioEnabled", true);

            Request request = new Request.Builder()
                    .url(apiUrl("/api/pairing/" + Uri.encode(pairingId) + "/approve?token=" + Uri.encode(adminToken)))
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    main.post(() -> statusMessage("Falha ao aprovar. Confira servidor e internet."));
                }

                @Override
                public void onResponse(Call call, Response response) {
                    response.close();
                    main.post(() -> {
                        if (response.isSuccessful()) {
                            statusMessage("TV aprovada. Ela vai conectar automaticamente.");
                            showHome(true);
                        } else if (response.code() == 401) {
                            statusMessage("Token admin incorreto.");
                        } else {
                            statusMessage("Erro ao aprovar: " + response.code());
                        }
                    });
                }
            });
        } catch (JSONException e) {
            statusMessage("Erro preparando aprovacao.");
        }
    }

    private void updateDevice(String deviceId, String name, String location, String group, String url, String fallback) {
        savePrefs();
        statusMessage("Salvando alteracoes da TV...");
        try {
            JSONObject body = new JSONObject();
            body.put("name", name == null || name.trim().isEmpty() ? "TV sem nome" : name.trim());
            body.put("location", location == null ? "" : location.trim());
            body.put("group", group == null ? "" : group.trim());
            body.put("currentUrl", url == null || url.trim().isEmpty() ? "about:blank" : url.trim());
            body.put("fallbackUrl", fallback == null || fallback.trim().isEmpty() ? "about:blank" : fallback.trim());

            Request request = new Request.Builder()
                    .url(apiUrl("/api/devices/" + Uri.encode(deviceId) + "?token=" + Uri.encode(adminToken)))
                    .patch(RequestBody.create(body.toString(), JSON))
                    .build();

            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    main.post(() -> statusMessage("Falha ao salvar alteracoes da TV."));
                }

                @Override
                public void onResponse(Call call, Response response) {
                    response.close();
                    main.post(() -> {
                        if (response.isSuccessful()) {
                            statusMessage("TV atualizada com sucesso.");
                            showHome(true);
                        } else if (response.code() == 401) {
                            statusMessage("Token admin incorreto.");
                        } else {
                            statusMessage("Erro ao atualizar TV: " + response.code());
                        }
                    });
                }
            });
        } catch (JSONException e) {
            statusMessage("Erro preparando atualizacao da TV.");
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startCamera(holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        stopCamera();
        startCamera(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopCamera();
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!scanning || decoding || camera == null) return;
        decoding = true;
        try {
            Camera.Size size = camera.getParameters().getPreviewSize();
            YuvImage yuv = new YuvImage(data, ImageFormat.NV21, size.width, size.height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, size.width, size.height), 70, out);
            byte[] jpeg = out.toByteArray();
            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bitmap != null) {
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                RGBLuminanceSource source = new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels);
                BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));
                Result result = new MultiFormatReader().decode(binary);
                scanning = false;
                main.post(() -> handleQrValue(result.getText()));
                return;
            }
        } catch (NotFoundException ignored) {
            // Continua lendo ate encontrar um QR valido.
        } catch (Exception error) {
            main.post(() -> showHomeWithStatus("Erro lendo camera: " + error.getMessage()));
        } finally {
            decoding = false;
        }
    }

    private void startCamera(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            camera.setPreviewDisplay(holder);
            camera.setDisplayOrientation(90);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception error) {
            showHomeWithStatus("Nao consegui abrir a camera: " + error.getMessage());
        }
    }

    private void stopCamera() {
        scanning = false;
        decoding = false;
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 41 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showScanner();
        } else {
            showHomeWithStatus("Permissao de camera negada.");
        }
    }

    private String apiUrl(String path) {
        String base = normalizeServerUrl(serverUrl);
        if (path.startsWith("/")) return base + path;
        return base + "/" + path;
    }

    private String normalizeServerUrl(String value) {
        String clean = value == null || value.trim().isEmpty() ? DEFAULT_SERVER : value.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) clean = "http://" + clean;
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean;
    }

    private void savePrefs() {
        serverUrl = serverInput == null ? serverUrl : serverInput.getText().toString().trim();
        adminToken = tokenInput == null ? adminToken : tokenInput.getText().toString().trim();
        savePrefsFromMemory();
    }

    private void savePrefsFromMemory() {
        serverUrl = normalizeServerUrl(serverUrl);
        if (adminToken == null || adminToken.trim().isEmpty()) adminToken = DEFAULT_ADMIN_TOKEN;
        prefs.edit()
                .putString("serverUrl", serverUrl)
                .putString("adminToken", adminToken)
                .apply();
    }

    private void showHomeWithStatus(String message) {
        showHome(false);
        statusMessage(message);
    }

    private void statusMessage(String message) {
        if (status != null) status.setText(message);
    }

    private String friendlyDate(String iso) {
        if (iso == null || iso.trim().isEmpty() || "null".equals(iso)) return "nunca";
        return iso.replace("T", " ").replace("Z", "");
    }

    private LinearLayout baseBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 34, 32, 34);
        box.setBackgroundColor(0xff061014);
        return box;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private TextView title(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(0xffffffff);
        text.setTextSize(28);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private TextView sectionTitle(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(0xffffffff);
        text.setTextSize(18);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.LEFT);
        return text;
    }

    private TextView paragraph(String value) {
        TextView text = paragraphLeft(value);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private TextView paragraphLeft(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(0xffbed3d9);
        text.setTextSize(15);
        text.setGravity(Gravity.LEFT);
        text.setPadding(0, 8, 0, 8);
        return text;
    }

    private EditText input(String hint, String value) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value);
        edit.setSingleLine(true);
        edit.setTextColor(0xffffffff);
        edit.setHintTextColor(0xff96aaaf);
        edit.setTextSize(16);
        edit.setPadding(16, 12, 16, 12);
        edit.setBackgroundColor(0xff102027);
        return edit;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 7, 0, 7);
        return params;
    }

    private LinearLayout.LayoutParams rowParam() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(4, 4, 4, 4);
        return params;
    }
}
