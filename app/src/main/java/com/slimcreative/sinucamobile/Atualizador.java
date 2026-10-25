package com.slimcreative.sinucamobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Atualização automática.
 * 1) Jogo (HTML): quando tem internet, baixa a versão nova do site e passa a usar ela (sem reinstalar).
 * 2) App (APK): se mudou a parte do Android, avisa e instala com um toque (o Android pede confirmação).
 */
class Atualizador {
    static final String BASE = "https://raneanc-lgtm.github.io/hotel-paris-atualizacoes/sinuca/";
    static final String PAGINA = "file:///android_asset/index.html";

    private final Activity act;
    private final WebView web;
    private final File dir;
    private final SharedPreferences prefs;
    private final int webEmbutida, nativoEmbutido;
    private volatile boolean rodando = false;
    private long ultima = 0;
    private volatile String apkUrl = null;

    Atualizador(Activity a, WebView w) {
        act = a;
        web = w;
        dir = new File(a.getFilesDir(), "web");
        prefs = a.getSharedPreferences("atualizacao", Activity.MODE_PRIVATE);
        webEmbutida = lerNumero("versao.txt");
        nativoEmbutido = lerNumero("nativo.txt");
    }

    private int lerNumero(String nome) {
        try (InputStream in = act.getAssets().open(nome)) {
            return Integer.parseInt(new String(lerTudo(in, 64), StandardCharsets.UTF_8).trim());
        } catch (Exception e) { return 0; }
    }

    private static byte[] lerTudo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            if (o.size() > max) throw new IOException("grande demais");
        }
        return o.toByteArray();
    }

    private static byte[] baixar(String url, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SinucaMobile");
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) { return lerTudo(in, max); }
        } finally { c.disconnect(); }
    }

    private File arquivoWeb() { return new File(dir, "index.html"); }

    /** versão do jogo que está valendo (a embutida no APK ou a baixada, a mais nova) */
    int versaoWeb() {
        int c = prefs.getInt("web", 0);
        return (c > webEmbutida && arquivoWeb().exists()) ? c : webEmbutida;
    }

    /** a WebView pede a página do jogo: se tiver versão baixada mais nova, entrega ela */
    WebResourceResponse interceptar(String url) {
        if (url == null || !url.startsWith(PAGINA)) return null;
        int c = prefs.getInt("web", 0);
        File f = arquivoWeb();
        if (c <= webEmbutida || !f.exists()) return null;
        try { return new WebResourceResponse("text/html", "utf-8", new FileInputStream(f)); }
        catch (IOException e) { return null; }
    }

    private void js(String codigo) {
        web.post(() -> web.evaluateJavascript(codigo, null));
    }

    private long espera = 0;

    /** procura atualização: depois de dar certo espera 5 min; se falhar, tenta de novo em 30 s (e vai aumentando até 5 min) */
    void verificar(boolean forcar) {
        long agora = System.currentTimeMillis();
        if (rodando) { if (forcar) js("window.__updEstado && window.__updEstado('ocupado')"); return; }
        if (!forcar && agora - ultima < espera) return;
        ultima = agora;
        rodando = true;
        new Thread(() -> {
            boolean ok = false;
            for (int t = 0; t < 3 && !ok; t++) {                     // até 3 tentativas seguidas (rede do celular oscila)
                try { ok = checar(forcar); } catch (Exception e) { ok = false; }
                if (!ok) try { Thread.sleep(2500L * (t + 1)); } catch (InterruptedException ie) { break; }
            }
            if (ok) espera = 5 * 60 * 1000L;
            else {
                espera = Math.min(5 * 60 * 1000L, Math.max(30 * 1000L, espera * 2));
                if (forcar) js("window.__updEstado && window.__updEstado('erro')");
            }
            rodando = false;
        }).start();
    }

    private boolean checar(boolean avisar) throws Exception {
        JSONObject v = new JSONObject(new String(baixar(BASE + "versao.json?t=" + System.currentTimeMillis(), 64 * 1024), StandardCharsets.UTF_8));
        int web = v.optInt("web", 0);
        boolean nova = web > versaoWeb();
        if (nova) {
            byte[] html = baixar(BASE + "index.html?v=" + web + "&t=" + System.currentTimeMillis(), 16 * 1024 * 1024);
            String s = new String(html, StandardCharsets.UTF_8);
            // só aceita o arquivo certo e inteiro
            if (!(html.length > 150000 && s.contains("WEB_VER = " + web + ";") && s.trim().endsWith("</html>"))) return false;   // veio cortado: tenta de novo
            if (!dir.exists() && !dir.mkdirs()) return false;
            File tmp = new File(dir, "index.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(html); o.getFD().sync(); }
            File f = arquivoWeb();
            if (f.exists() && !f.delete()) return false;
            if (!tmp.renameTo(f)) return false;
            prefs.edit().putInt("web", web).commit();
            js("window.__atualizou && window.__atualizou(" + web + ")");
        }
        int nativo = v.optInt("nativo", 0);
        String url = v.optString("apkUrl", ""), nome = v.optString("apk", "");
        if (nativo > nativoEmbutido && url.startsWith("https://")) {
            apkUrl = url;
            js("window.__apkNovo && window.__apkNovo(" + JSONObject.quote(nome) + ")");
        } else if (!nova && avisar) js("window.__updEstado && window.__updEstado('nada', " + versaoWeb() + ")");
        return true;
    }

    /** baixa o APK novo e abre o instalador do Android */
    void instalar() {
        final String url = apkUrl;
        if (url == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            js("window.__apkEstado && window.__apkEstado('permitir')");
            try {
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            } catch (Exception e) { /* tela não existe nesse aparelho */ }
            return;
        }
        js("window.__apkEstado && window.__apkEstado('baixando')");
        new Thread(() -> {
            try {
                byte[] apk = baixar(url, 80 * 1024 * 1024);
                File f = new File(act.getCacheDir(), ApkProvider.ARQUIVO);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(apk); }
                act.runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(Uri.parse("content://" + act.getPackageName() + ".apk/" + ApkProvider.ARQUIVO), "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(i);
                    } catch (Exception e) { js("window.__apkEstado && window.__apkEstado('erro')"); }
                });
            } catch (Exception e) {
                js("window.__apkEstado && window.__apkEstado('erro')");
            }
        }).start();
    }
}
