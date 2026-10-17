package com.slimcreative.sinucamobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.content.ClipData;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Sinuca Mobile — Slim Creative. O jogo roda dentro do app, em tela cheia, deitado e com a tela sempre acesa. */
public class MainActivity extends Activity {
    private WebView web;
    private BluetoothMesa mesa;
    private Atualizador atual;
    private ValueCallback<Uri[]> escolha;          // escolher músicas do celular
    static final int PEDIDO_ARQUIVO = 51;
    private volatile String assistir = null;   // código de partida vindo de um link

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // fichas, placar e configurações ficam salvos
        s.setMediaPlaybackRequiresUserGesture(false); // sons do jogo
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);

        mesa = new BluetoothMesa(this, web);
        assistir = codigoDoLink(getIntent());
        web.addJavascriptInterface(new Ponte(), "SinucaApp");   // jogo online: mandar o código da sala pelo WhatsApp etc.
        atual = new Atualizador(this, web);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse x = atual.interceptar(r.getUrl().toString());   // versão nova do jogo baixada da internet
                return x != null ? x : super.shouldInterceptRequest(v, r);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // "Escolher músicas": abre o seletor de arquivos do Android
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (escolha != null) escolha.onReceiveValue(null);
                escolha = cb;
                try {
                    Intent i = p.createIntent();
                    if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, PEDIDO_ARQUIVO);
                    return true;
                } catch (Exception e) {
                    escolha = null;
                    return false;
                }
            }
        });
        setContentView(web);
        telaCheia();

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    /** esconde barra de status e de navegação (volta escondida depois de um toque na borda) */
    private void telaCheia() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) telaCheia();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); telaCheia(); if (atual != null) atual.verificar(false); }

    /** ponte com o jogo: abre o "compartilhar" do Android com o convite da sala */
    private class Ponte {
        @JavascriptInterface
        public void compartilhar(String texto) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, texto);
                startActivity(Intent.createChooser(i, "Convidar para a sala"));
            });
        }
        // ---- abrir app de música (Spotify, Deezer, YouTube Music...) e voltar pro jogo ----
        @JavascriptInterface public String appsMusica() { return listarApps(false); }
        @JavascriptInterface public String appsTodos() { return listarApps(true); }
        @JavascriptInterface public boolean abrirApp(String pacote) {
            final Intent i = getPackageManager().getLaunchIntentForPackage(pacote);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(() -> { try { startActivity(i); } catch (Exception e) { /* app sumiu */ } });
            return true;
        }
        @JavascriptInterface public int versaoWeb() { return atual.versaoWeb(); }
        @JavascriptInterface public void instalarApk() { runOnUiThread(() -> atual.instalar()); }
        @JavascriptInterface public void procurarAtualizacao() { atual.verificar(true); }
        @JavascriptInterface public String codigoAssistir() { String c = assistir; assistir = null; return c; }
        // ---- jogo via Bluetooth ----
        @JavascriptInterface public boolean btDisponivel() { return mesa.disponivel(); }
        @JavascriptInterface public void btHospedar() { runOnUiThread(() -> mesa.iniciar("hospedar")); }
        @JavascriptInterface public void btProcurar() { runOnUiThread(() -> mesa.iniciar("procurar")); }
        @JavascriptInterface public void btConectar(String endereco) { mesa.conectar(endereco); }
        @JavascriptInterface public void btEnviar(String texto) { mesa.enviar(texto); }
        @JavascriptInterface public void btFechar() { mesa.fechar(); }
    }

    // apps de música mais usados (aparecem primeiro, se estiverem instalados)
    private static final String[] CONHECIDOS = {
        "com.spotify.music", "deezer.android.app", "com.google.android.apps.youtube.music", "com.google.android.youtube",
        "com.amazon.mp3", "com.apple.android.music", "com.soundcloud.android", "com.studiosol.palcomp3", "com.aspiro.tidal",
        "org.schabi.newpipe", "com.sec.android.app.music", "org.videolan.vlc", "com.maxmpz.audioplayer"
    };

    private String rotulo(PackageManager pm, String p) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString(); } catch (Exception e) { return p; }
    }

    /** lista apps instalados: os de música (todos=false) ou todos que abrem pela tela inicial (todos=true) */
    private String listarApps(boolean todos) {
        PackageManager pm = getPackageManager();
        JSONArray out = new JSONArray();
        Set<String> vistos = new HashSet<>();
        vistos.add(getPackageName());
        try {
            if (!todos) {
                for (String p : CONHECIDOS)
                    if (pm.getLaunchIntentForPackage(p) != null && vistos.add(p)) out.put(new JSONObject().put("p", p).put("n", rotulo(pm, p)));
                Intent m = new Intent(Intent.ACTION_MAIN);
                m.addCategory(Intent.CATEGORY_APP_MUSIC);
                for (ResolveInfo r : pm.queryIntentActivities(m, 0)) {
                    String p = r.activityInfo.packageName;
                    if (pm.getLaunchIntentForPackage(p) != null && vistos.add(p)) out.put(new JSONObject().put("p", p).put("n", rotulo(pm, p)));
                }
            } else {
                Intent l = new Intent(Intent.ACTION_MAIN);
                l.addCategory(Intent.CATEGORY_LAUNCHER);
                List<String[]> lista = new ArrayList<>();
                for (ResolveInfo r : pm.queryIntentActivities(l, 0)) {
                    String p = r.activityInfo.packageName;
                    if (vistos.add(p)) lista.add(new String[]{p, r.loadLabel(pm).toString()});
                }
                lista.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
                for (String[] a : lista) out.put(new JSONObject().put("p", a[0]).put("n", a[1]));
            }
        } catch (Exception e) { /* devolve o que deu */ }
        return out.toString();
    }

    /** sinucamobile://assistir/ABCD  ou  https://...?assistir=ABCD */
    private static String codigoDoLink(Intent i) {
        if (i == null || i.getData() == null) return null;
        Uri u = i.getData();
        String c = u.getQueryParameter("assistir");
        if (c == null && "sinucamobile".equals(u.getScheme())) c = u.getLastPathSegment();
        if (c == null) return null;
        c = c.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return c.length() == 4 ? c : null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String c = codigoDoLink(intent);
        if (c != null && web != null) web.evaluateJavascript("window.__assistir && window.__assistir('" + c + "')", null);
    }

    @Override
    public void onRequestPermissionsResult(int pedido, String[] permissoes, int[] resultado) {
        super.onRequestPermissionsResult(pedido, permissoes, resultado);
        if (pedido == BluetoothMesa.PEDIDO_PERM) mesa.resultadoPermissao(resultado);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int pedido, int resultado, Intent dados) {
        super.onActivityResult(pedido, resultado, dados);
        if (pedido == PEDIDO_ARQUIVO) {
            if (escolha == null) return;
            Uri[] r = null;
            if (resultado == RESULT_OK && dados != null) {
                ClipData clip = dados.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    r = new Uri[clip.getItemCount()];
                    for (int k = 0; k < r.length; k++) r[k] = clip.getItemAt(k).getUri();
                } else if (dados.getData() != null) r = new Uri[]{dados.getData()};
            }
            escolha.onReceiveValue(r);
            escolha = null;
            return;
        }
        mesa.resultadoAtividade(pedido);
    }

    @Override
    protected void onDestroy() {
        if (mesa != null) mesa.fechar();
        super.onDestroy();
    }

    /** botão voltar: abre o menu do jogo (☰); no menu inicial, sai do app */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.__voltar && window.__voltar()) ? '1' : '0'", v -> {
            if (!"\"1\"".equals(v)) MainActivity.super.onBackPressed();
        });
    }
}
