package com.slimcreative.sinucamobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
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
        web.setWebChromeClient(new WebChromeClient());
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
