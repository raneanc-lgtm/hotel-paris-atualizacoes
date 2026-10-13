package com.slimcreative.sinucamobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mesa via Bluetooth: um celular abre a mesa (servidor RFCOMM) e o outro procura e conecta.
 * As mensagens do jogo são linhas de texto (JSON), as mesmas do jogo pela internet.
 */
@SuppressLint("MissingPermission")
class BluetoothMesa {
    static final UUID ID = UUID.fromString("5c1d3a10-8b7e-4f2a-9c3d-51a0e7b1c0d3");
    static final String SERVICO = "SinucaMobile";
    static final int PEDIDO_PERM = 41, PEDIDO_LIGAR = 42, PEDIDO_VISIVEL = 43;

    private final Activity act;
    private final WebView web;
    private final BluetoothAdapter bt;
    private final ExecutorService escrita = Executors.newSingleThreadExecutor();
    private final Set<String> vistos = new HashSet<>();
    private String pendente = null;              // "hospedar" ou "procurar" esperando permissão / Bluetooth ligar
    private BluetoothServerSocket servidor;
    private BluetoothSocket sock;
    private OutputStream saida;
    private BroadcastReceiver receptor;
    private volatile int geracao = 0;            // cada abrir/procurar/conectar novo invalida o anterior

    BluetoothMesa(Activity a, WebView w) {
        act = a;
        web = w;
        BluetoothManager m = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        bt = m != null ? m.getAdapter() : null;
    }

    boolean disponivel() { return bt != null; }

    // ---------- avisos para o jogo ----------
    private void evento(String tipo, String dados) {
        final String js = "window.__btEvento && window.__btEvento(" + JSONObject.quote(tipo) + "," + JSONObject.quote(dados == null ? "" : dados) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void recebido(String linha) {
        final String js = "window.__btRecv && window.__btRecv(" + JSONObject.quote(linha) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    // ---------- permissões e Bluetooth ligado ----------
    private String[] permissoes() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};   // Android antigo: procurar aparelhos exige localização
    }

    private boolean temPermissoes() {
        for (String p : permissoes())
            if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    /** roda na thread da tela */
    void iniciar(String acao) {
        if (bt == null) { evento("erro", "Este celular não tem Bluetooth"); return; }
        pendente = acao;
        if (!temPermissoes()) { act.requestPermissions(permissoes(), PEDIDO_PERM); return; }
        if (!bt.isEnabled()) {
            try { act.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), PEDIDO_LIGAR); }
            catch (Exception e) { pendente = null; evento("erro", "Ligue o Bluetooth nas configurações do celular"); }
            return;
        }
        seguir();
    }

    void resultadoPermissao(int[] res) {
        boolean ok = res.length > 0;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok && pendente != null) iniciar(pendente);
        else { pendente = null; evento("erro", "Sem a permissão de Bluetooth não dá para jogar assim · libere em Configurações › Apps › Sinuca Mobile"); }
    }

    void resultadoAtividade(int pedido) {
        if (pedido == PEDIDO_LIGAR) {
            if (bt.isEnabled() && pendente != null) seguir();
            else { pendente = null; evento("erro", "O Bluetooth precisa estar ligado"); }
        } else if (pedido == PEDIDO_VISIVEL) {
            pendente = null;
            hospedarAgora();                     // mesmo se recusar ficar visível: celulares já pareados acham a mesa
        }
    }

    private void seguir() {
        String a = pendente;
        if ("hospedar".equals(a)) {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            try { act.startActivityForResult(i, PEDIDO_VISIVEL); }
            catch (Exception e) { pendente = null; hospedarAgora(); }
        } else if ("procurar".equals(a)) {
            pendente = null;
            procurar();
        }
    }

    // ---------- abrir a mesa (quem convida) ----------
    private void hospedarAgora() {
        fechar();
        final int g = geracao;
        String nome = "";
        try { nome = bt.getName(); } catch (Exception e) { /* sem nome */ }
        evento("visivel", nome);
        new Thread(() -> {
            try {
                BluetoothServerSocket s = bt.listenUsingInsecureRfcommWithServiceRecord(SERVICO, ID);
                synchronized (this) {
                    if (g != geracao) { s.close(); return; }
                    servidor = s;
                }
                BluetoothSocket c = s.accept();
                try { s.close(); } catch (IOException e) { /* ok */ }
                if (g != geracao) { c.close(); return; }
                ligar(c, g, "host");
            } catch (IOException e) {
                if (g == geracao) evento("erro", "A mesa Bluetooth fechou · tente de novo");
            }
        }).start();
    }

    // ---------- procurar mesas (quem entra) ----------
    private void procurar() {
        fechar();
        vistos.clear();
        try {
            for (BluetoothDevice d : bt.getBondedDevices()) dispositivo(d, true);
        } catch (Exception e) { /* sem pareados */ }
        receptor = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null) dispositivo(d, false);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())) {
                    evento("busca-fim", "");
                }
            }
        };
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(receptor, f, Context.RECEIVER_EXPORTED);
        else act.registerReceiver(receptor, f);
        boolean ok;
        try {
            if (bt.isDiscovering()) bt.cancelDiscovery();
            ok = bt.startDiscovery();
        } catch (Exception e) { ok = false; }
        if (ok) evento("buscando", "");
        else evento("erro", Build.VERSION.SDK_INT < 31 ? "Não deu para procurar · ligue a Localização do celular" : "Não deu para procurar celulares");
    }

    private void dispositivo(BluetoothDevice d, boolean pareado) {
        String end = d.getAddress();
        if (end == null || !vistos.add(end)) return;
        String nome = null;
        try { nome = d.getName(); } catch (Exception e) { /* sem nome */ }
        if (nome == null && !pareado) { vistos.remove(end); return; }   // sem nome ainda: espera aparecer com nome
        try {
            JSONObject o = new JSONObject();
            o.put("n", nome == null ? end : nome);
            o.put("a", end);
            o.put("p", pareado);
            evento("aparelho", o.toString());
        } catch (Exception e) { /* ignora */ }
    }

    void conectar(String endereco) {
        if (bt == null) return;
        fechar();
        final int g = geracao;
        final BluetoothDevice d;
        try { d = bt.getRemoteDevice(endereco); }
        catch (Exception e) { evento("erro", "Aparelho inválido"); return; }
        evento("conectando", "");
        new Thread(() -> {
            BluetoothSocket c = null;
            try {
                c = d.createInsecureRfcommSocketToServiceRecord(ID);
                c.connect();
            } catch (IOException e) {
                try { if (c != null) c.close(); } catch (IOException x) { /* ok */ }
                c = null;
                try {
                    c = d.createRfcommSocketToServiceRecord(ID);   // segunda tentativa: conexão pareada
                    c.connect();
                } catch (IOException e2) {
                    try { if (c != null) c.close(); } catch (IOException x) { /* ok */ }
                    if (g == geracao) evento("erro", "Não conectou · confira se o outro celular está com a mesa aberta");
                    return;
                }
            }
            if (g != geracao) { try { c.close(); } catch (IOException x) { /* ok */ } return; }
            ligar(c, g, "guest");
        }).start();
    }

    // ---------- conversa entre os dois ----------
    private void ligar(BluetoothSocket c, int g, String papel) {
        try {
            synchronized (this) {
                if (g != geracao) { c.close(); return; }
                sock = c;
                saida = c.getOutputStream();
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String nome = "";
            try { nome = c.getRemoteDevice().getName(); } catch (Exception e) { /* sem nome */ }
            evento(papel, nome);
            String linha;
            while ((linha = r.readLine()) != null) {
                if (g != geracao) break;
                if (!linha.isEmpty()) recebido(linha);
            }
        } catch (IOException e) { /* caiu */ }
        if (g == geracao) evento("fechado", "");
        synchronized (this) {
            if (sock == c) { sock = null; saida = null; }
        }
        try { c.close(); } catch (IOException x) { /* ok */ }
    }

    void enviar(String s) {
        final OutputStream o;
        synchronized (this) { o = saida; }
        if (o == null || s == null) return;
        final byte[] b = (s.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8);
        escrita.execute(() -> {
            try { o.write(b); o.flush(); } catch (IOException e) { /* a leitura avisa que caiu */ }
        });
    }

    synchronized void fechar() {
        geracao++;
        try { if (servidor != null) servidor.close(); } catch (IOException e) { /* ok */ }
        servidor = null;
        try { if (sock != null) sock.close(); } catch (IOException e) { /* ok */ }
        sock = null;
        saida = null;
        if (receptor != null) {
            try { act.unregisterReceiver(receptor); } catch (Exception e) { /* ok */ }
            receptor = null;
        }
        try { if (bt != null && bt.isDiscovering()) bt.cancelDiscovery(); } catch (Exception e) { /* sem permissão */ }
    }
}
