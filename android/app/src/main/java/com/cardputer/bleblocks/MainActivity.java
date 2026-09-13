package com.cardputer.bleblocks;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity implements NusClient.Listener {

    private static final int REQ = 42;
    private static final long SCAN_MS = 8000;
    private static final long WAIT_MORE_MS = 4500;
    private static final int MAX_FILE = 400_000;
    /** Idle antes de procesar lo recibido. Debe ser > GAP del Cardputer (400 ms). */
    private static final long RX_IDLE_MS = 2200;

    private TextView status;
    private TextView logView;
    private TextView fileName;
    private Spinner devices;
    private EditText source;
    private EditText blockSize;
    private Button btnSend;
    private Button btnRecv;

    private final List<BluetoothDevice> found = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private BluetoothLeScanner scanner;
    private NusClient nus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();
    private final StringBuilder rxAcc = new StringBuilder();

    private volatile boolean sending;
    private volatile boolean gotMore;
    private volatile boolean gotOk;
    private volatile boolean gotErr;
    private boolean recvOn = true;
    private final StringBuilder inbound = new StringBuilder();
    private final Runnable flushInbound = this::flushInbound;

    /** Ensamblador persistente entre trozos (multi-bloque). */
    private final BlockAssembler assembler = new BlockAssembler();
    private boolean recvStarted;

    private final ActivityResultLauncher<String[]> openDoc =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) loadFromUri(uri, true);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        fileName = findViewById(R.id.fileName);
        devices = findViewById(R.id.devices);
        source = findViewById(R.id.source);
        blockSize = findViewById(R.id.blockSize);
        btnSend = findViewById(R.id.btnSend);
        btnRecv = findViewById(R.id.btnRecv);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        devices.setAdapter(adapter);

        nus = new NusClient(this, this);

        findViewById(R.id.btnScan).setOnClickListener(v -> startScan());
        findViewById(R.id.btnConnect).setOnClickListener(v -> connectSelected());
        findViewById(R.id.btnOpen).setOnClickListener(v -> openPicker());
        btnSend.setOnClickListener(v -> startSend());
        btnRecv.setOnClickListener(v -> {
            recvOn = !recvOn;
            btnRecv.setText(recvOn ? "Recibir ON" : "Recibir OFF");
            log(recvOn ? "recepción activa" : "recepción pausada");
            if (!recvOn) {
                main.removeCallbacks(flushInbound);
            }
        });

        requestPerms();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    private void openPicker() {
        openDoc.launch(new String[]{
                "text/*",
                "application/octet-stream",
                "*/*"
        });
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            CharSequence extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (uri == null && extra != null) {
                source.setText(extra);
                fileName.setText("Texto compartido");
                log("texto recibido por Compartir");
                return;
            }
        } else if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            uri = intent.getData();
        }
        if (uri != null) loadFromUri(uri, false);
    }

    private void loadFromUri(Uri uri, boolean fromPicker) {
        try {
            if (fromPicker) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            }
            String name = queryName(uri);
            String text = readUtf8(uri);
            source.setText(text);
            fileName.setText(name + "  (" + text.length() + " chars)");
            log("cargado: " + name + " (" + text.length() + " chars)");
        } catch (Exception e) {
            toast("No pude leer el archivo");
            log("ERROR archivo: " + e.getMessage());
        }
    }

    private String queryName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {}
        return name != null ? name : "archivo.py";
    }

    private String readUtf8(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("sin acceso");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_FILE) throw new IllegalStateException("archivo demasiado grande");
            }
            return sb.toString();
        }
    }

    private void requestPerms() {
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void startScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm.getAdapter();
        if (ad == null || !ad.isEnabled()) {
            toast("Activa Bluetooth");
            return;
        }
        scanner = ad.getBluetoothLeScanner();
        found.clear();
        adapter.clear();
        log("escaneando 8 s… busca Cardputer-ADV");
        scanner.startScan(scanCb);
        main.postDelayed(() -> {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
            log("scan fin (" + found.size() + ")");
        }, SCAN_MS);
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null) return;
            String name = d.getName();
            if (name == null) return;
            for (BluetoothDevice x : found) if (x.getAddress().equals(d.getAddress())) return;
            found.add(d);
            adapter.add(name + "  " + d.getAddress());
            adapter.notifyDataSetChanged();
            if (name.contains("Cardputer")) devices.setSelection(found.size() - 1);
        }
    };

    private void connectSelected() {
        int i = devices.getSelectedItemPosition();
        if (i < 0 || i >= found.size()) {
            toast("Escanea y elige un dispositivo");
            return;
        }
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        nus.connect(found.get(i));
        status.setText("Conectando…");
    }

    private void startSend() {
        if (sending) return;
        if (!nus.isReady()) {
            toast("Conecta primero a Cardputer-ADV");
            return;
        }
        String src = source.getText().toString();
        if (src.trim().isEmpty()) {
            toast("Abre o pega un archivo");
            return;
        }
        int sz = 500;
        try { sz = Integer.parseInt(blockSize.getText().toString().trim()); } catch (Exception ignored) {}
        String hinted = fileName.getText() != null ? fileName.getText().toString() : null;
        if (hinted != null && hinted.contains(" ")) hinted = hinted.split(" ")[0];
        if (hinted == null || hinted.startsWith("Ningún")) hinted = "envio.py";
        List<String> blocks = BlockSplitter.split(src, sz, hinted);
        log("bloques: " + blocks.size() + "  chars=" + src.length());
        sending = true;
        btnSend.setEnabled(false);
        new Thread(() -> sendLoop(blocks), "ble-blocks").start();
    }

    private void sendLoop(List<String> blocks) {
        try {
            for (int k = 0; k < blocks.size(); k++) {
                if (!nus.isReady()) throw new IllegalStateException("se cortó BLE");
                gotMore = false;
                gotOk = false;
                gotErr = false;
                String blk = blocks.get(k);
                log(String.format(Locale.US, "enviando bloque %d/%d (%d chars)", k + 1, blocks.size(), blk.length()));
                nus.write(blk);
                boolean last = (k == blocks.size() - 1);
                long wait = last ? 8000 : WAIT_MORE_MS;
                long t0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - t0 < wait) {
                    if (gotErr) throw new IllegalStateException("ERR del Cardputer");
                    if (last && (gotOk || rxAcc.toString().contains("GOT") || rxAcc.toString().contains("OK "))) break;
                    if (!last && gotMore) break;
                    Thread.sleep(80);
                }
                if (!last && !gotMore) log("sin MORE, sigo tras " + WAIT_MORE_MS + " ms");
            }
            log("envío listo. Cardputer: nombre + ENT si lo pide");
        } catch (Exception e) {
            log("ERROR: " + e.getMessage());
        } finally {
            sending = false;
            main.post(() -> btnSend.setEnabled(true));
        }
    }

    @Override
    public void onConnected() {
        status.setText("Conectado (NUS)");
        log("conectado. Abre ble_recv (recibir) o ble_send (enviar).");
        // Nueva sesion de recepcion
        assembler.reset();
        recvStarted = false;
        inbound.setLength(0);
    }

    @Override
    public void onDisconnected() {
        status.setText("Desconectado");
        sending = false;
        btnSend.setEnabled(true);
    }

    @Override
    public void onRx(String text) {
        rxAcc.append(text);
        String acc = rxAcc.toString();
        if (acc.contains("MORE")) gotMore = true;
        if (acc.contains("OK ") || acc.contains("GOT ")) gotOk = true;
        if (acc.contains("ERR")) gotErr = true;
        if (acc.length() > 4000) rxAcc.delete(0, rxAcc.length() - 1000);

        if (recvOn && !sending) {
            inbound.append(text);
            main.removeCallbacks(flushInbound);
            // Si llega ##MORE o parece fin de bloque, procesar un poco antes
            long delay = RX_IDLE_MS;
            if (text.contains("##MORE") || inbound.toString().contains("##MORE")) {
                delay = 600; // dar tiempo a que llegue el resto del bloque actual
            }
            main.postDelayed(flushInbound, delay);
            if (text.contains("##") || inbound.length() % 400 < 40) {
                log("<< rx " + inbound.length() + " chars");
            }
        }
    }

    private void flushInbound() {
        if (inbound.length() == 0) return;
        String raw = inbound.toString();
        inbound.setLength(0);

        if (!recvStarted) {
            assembler.reset();
            recvStarted = true;
        }

        assembler.feed(raw);

        // Vista previa mientras llegan bloques
        String preview = BlockAssembler.stripProtocolLines(assembler.body.toString());
        source.setText(preview);
        fileName.setText(assembler.fileName + "  (" + preview.length() + " chars"
                + (assembler.complete ? "" : " …") + ")");

        if (!assembler.complete) {
            log("bloque parcial → " + assembler.got
                    + (assembler.expect > 0 ? "/" + assembler.expect : "")
                    + "  body=" + preview.length() + " (esperando más)");
            return;
        }

        // Archivo completo: strip final de seguridad y guardar
        String clean = BlockAssembler.stripProtocolLines(assembler.body.toString());
        if (clean.isEmpty() && !raw.trim().isEmpty() && !raw.contains("##")) {
            clean = raw;
        }
        // Ultimo pase: por si quedo algun ## suelto
        if (clean.contains("##")) {
            clean = BlockAssembler.stripProtocolLines(clean);
        }

        source.setText(clean);
        fileName.setText(assembler.fileName + "  (" + clean.length() + " chars)");
        log("archivo listo: " + assembler.fileName + "  " + clean.length() + " chars (sin ##)");

        try {
            String path = FileSaver.saveUtf8(this, assembler.fileName, clean);
            log("guardado: " + path);
            toast("Guardado " + assembler.fileName);
        } catch (Exception e) {
            log("no pude guardar: " + e.getMessage());
            toast("Recibido, no guardado: " + e.getMessage());
        }

        // Preparar para el siguiente archivo
        assembler.reset();
        recvStarted = false;
    }

    @Override
    public void onLog(String line) {
        log(line);
    }

    private void log(String line) {
        runOnUiThread(() -> {
            logBuf.append(line).append('\n');
            if (logBuf.length() > 8000) logBuf.delete(0, logBuf.length() - 4000);
            logView.setText(logBuf.toString());
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (scanner != null) scanner.stopScan(scanCb); } catch (Exception ignored) {}
        if (nus != null) nus.close();
    }
}
