package com.blckswan.julilys;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final int REQ_BLE = 42;
    private static final String PREFS = "blckswan_gatt_mesh";
    private static final String PREFERRED_GATEWAY = "D0:00:ED:9C:01:5F";

    private static final UUID PLEJD_SERVICE = UUID.fromString("31ba0001-6085-4726-be45-040c957391b5");
    private static final UUID PLEJD_LIGHTLEVEL = UUID.fromString("31ba0003-6085-4726-be45-040c957391b5");
    private static final UUID PLEJD_DATA = UUID.fromString("31ba0004-6085-4726-be45-040c957391b5");
    private static final UUID PLEJD_AUTH = UUID.fromString("31ba0009-6085-4726-be45-040c957391b5");
    private static final UUID PLEJD_PING = UUID.fromString("31ba000a-6085-4726-be45-040c957391b5");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LinkedHashSet<Integer> meshTargets = new LinkedHashSet<>();

    private EditText keyInput;
    private Button rootButton;
    private Button linkButton;
    private TextView status;
    private TextView nodes;
    private TextView value;
    private SeekBar dimmer;

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic lightLevelChar;
    private BluetoothGattCharacteristic dataChar;
    private BluetoothGattCharacteristic authChar;
    private BluetoothGattCharacteristic pingChar;

    private byte[] cryptoKey;
    private String gatewayAddress;
    private int authStage;
    private byte lastPing;
    private boolean meshReady;
    private Runnable pendingDim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadKey();
        requestBlePermissionsIfNeeded();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private void buildUi() {
        int green = Color.rgb(112, 255, 156);
        int fg = Color.rgb(240, 247, 242);
        int muted = Color.rgb(142, 160, 150);
        int panel = Color.rgb(13, 18, 15);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(5, 7, 6));

        TextView brand = text("BLCKSWAN", 34, green);
        brand.setTypeface(null, 1);
        root.addView(brand);

        TextView sub = text("JULILYS // RAW GATT DIMMER", 13, muted);
        sub.setPadding(0, 0, 0, dp(20));
        root.addView(sub);

        status = text("BLE ONLY // NO CLOUD // NO API", 14, green);
        status.setPadding(0, 0, 0, dp(14));
        root.addView(status);

        keyInput = new EditText(this);
        keyInput.setHint("cryptoKey // 32 hex");
        keyInput.setHintTextColor(muted);
        keyInput.setTextColor(fg);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyInput.setBackgroundColor(panel);
        keyInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(keyInput, new LinearLayout.LayoutParams(-1, -2));

        rootButton = new Button(this);
        rootButton.setText("AUTO KEY // ROOT");
        rootButton.setTextColor(green);
        rootButton.setBackgroundColor(panel);
        rootButton.setOnClickListener(v -> importKeyFromPlejd());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(50));
        rp.topMargin = dp(10);
        root.addView(rootButton, rp);

        linkButton = new Button(this);
        linkButton.setText("LINK RAW GATT");
        linkButton.setTextColor(Color.BLACK);
        linkButton.setBackgroundColor(green);
        linkButton.setOnClickListener(v -> startLink());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.topMargin = dp(8);
        root.addView(linkButton, lp);

        nodes = text("0 mesh outputs discovered", 13, muted);
        nodes.setGravity(Gravity.CENTER_HORIZONTAL);
        nodes.setPadding(0, dp(20), 0, 0);
        root.addView(nodes);

        LinearLayout spacer = new LinearLayout(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        value = text("0%", 58, fg);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(value);

        dimmer = new SeekBar(this);
        dimmer.setMax(100);
        dimmer.setProgress(0);
        dimmer.setEnabled(false);
        dimmer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                value.setText(p + "%");
                if (fromUser && meshReady) scheduleDim(p);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (meshReady) sendDim(bar.getProgress());
            }
        });
        root.addView(dimmer, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView foot = text("0009 AUTH → 0003 DISCOVERY → 0004 CONTROL", 11, muted);
        foot.setGravity(Gravity.CENTER_HORIZONTAL);
        foot.setPadding(0, dp(10), 0, 0);
        root.addView(foot);

        setContentView(root);
    }

    private void loadKey() {
        keyInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("key", ""));
    }

    private void saveKey(String key) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("key", key).apply();
    }

    private void setStatus(String s) {
        main.post(() -> status.setText(s));
    }

    private void updateNodeLabel() {
        main.post(() -> nodes.setText(meshTargets.size() + " mesh output" + (meshTargets.size() == 1 ? "" : "s") + " discovered"));
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissionsIfNeeded() {
        if (hasBlePermissions()) return;
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLE);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BLE);
        }
    }

    private void importKeyFromPlejd() {
        rootButton.setEnabled(false);
        linkButton.setEnabled(false);
        setStatus("ROOT // SEARCHING LOCAL PLEJD CACHE…");
        io.execute(() -> {
            try {
                String listing = rootShell("for d in /data/user/0/com.plejd.plejdapp /data/data/com.plejd.plejdapp; do [ -d \"$d\" ] && grep -RIl -m1 -E 'cryptoKey|CryptoKey' \"$d\" 2>/dev/null; done | head -50", 256 * 1024);
                Pattern p = Pattern.compile("(?i)(?:cryptoKey|CryptoKey)[\\\"'\\s:=]+([0-9a-f-]{32,40})");
                String found = null;
                for (String path : listing.split("\\r?\\n")) {
                    path = path.trim();
                    if (path.isEmpty()) continue;
                    String raw = rootShell("head -c 8388608 " + shellQuote(path) + " 2>/dev/null", 8 * 1024 * 1024);
                    Matcher m = p.matcher(raw.replace("&quot;", "\"").replace("\\\"", "\""));
                    if (m.find()) {
                        String k = m.group(1).replace("-", "");
                        if (k.length() == 32) { found = k; break; }
                    }
                }
                if (found == null) throw new Exception("cryptoKey not found");
                final String key = found;
                main.post(() -> {
                    keyInput.setText(key);
                    saveKey(key);
                    rootButton.setEnabled(true);
                    linkButton.setEnabled(true);
                    setStatus("KEY FOUND // LINKING GATT…");
                    startLink();
                });
            } catch (Exception e) {
                main.post(() -> {
                    rootButton.setEnabled(true);
                    linkButton.setEnabled(true);
                });
                fail("ROOT KEY: " + e.getMessage());
            }
        });
    }

    private void startLink() {
        if (!hasBlePermissions()) {
            requestBlePermissionsIfNeeded();
            setStatus("BLE PERMISSION REQUIRED");
            return;
        }
        try {
            String k = keyInput.getText().toString().replace("-", "").replace(" ", "").trim();
            cryptoKey = hexToBytes(k);
            if (cryptoKey.length != 16) throw new Exception("need 32 hex cryptoKey");
            saveKey(k);
            meshTargets.clear();
            updateNodeLabel();
            meshReady = false;
            dimmer.setEnabled(false);
            rootButton.setEnabled(false);
            linkButton.setEnabled(false);
            connectPreferredOrScan();
        } catch (Exception e) {
            fail("KEY: " + e.getMessage());
        }
    }

    private void connectPreferredOrScan() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            fail("BLUETOOTH OFF");
            return;
        }
        try {
            BluetoothDevice preferred = adapter.getRemoteDevice(PREFERRED_GATEWAY);
            gatewayAddress = preferred.getAddress();
            setStatus("GATT // CONNECTING " + gatewayAddress);
            gatt = preferred.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            main.postDelayed(() -> {
                if (!meshReady && (gatt == null || authStage == 0)) {
                    closeGatt();
                    startScan();
                }
            }, 4500);
        } catch (Exception e) {
            startScan();
        }
    }

    private void startScan() {
        setStatus("GATT // SCANNING P-MESH…");
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { fail("BLUETOOTH OFF"); return; }
        try {
            scanner = adapter.getBluetoothLeScanner();
            ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(PLEJD_SERVICE)).build();
            ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Arrays.asList(f), s, scanCallback);
            main.postDelayed(() -> {
                if (!meshReady && gatt == null) {
                    stopScan();
                    fail("NO P-MESH FOUND");
                }
            }, 12000);
        } catch (Exception e) {
            fail("SCAN: " + e.getMessage());
        }
    }

    private void stopScan() {
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        scanner = null;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (gatt != null) return;
            stopScan();
            try {
                BluetoothDevice d = result.getDevice();
                gatewayAddress = d.getAddress();
                setStatus("GATT // CONNECTING " + gatewayAddress);
                gatt = d.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (Exception e) { fail("CONNECT: " + e.getMessage()); }
        }
        @Override public void onScanFailed(int errorCode) { fail("SCAN FAILED " + errorCode); }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("GATT // DISCOVERING SERVICES…");
                try { g.discoverServices(); } catch (Exception e) { fail("DISCOVERY: " + e.getMessage()); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                meshReady = false;
                main.post(() -> dimmer.setEnabled(false));
                if (gatt == g) gatt = null;
                try { g.close(); } catch (Exception ignored) {}
                setStatus("GATT // DISCONNECTED");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { fail("SERVICE DISCOVERY FAILED"); return; }
            BluetoothGattService svc = g.getService(PLEJD_SERVICE);
            if (svc == null) { fail("PLEJD SERVICE MISSING"); return; }
            lightLevelChar = svc.getCharacteristic(PLEJD_LIGHTLEVEL);
            dataChar = svc.getCharacteristic(PLEJD_DATA);
            authChar = svc.getCharacteristic(PLEJD_AUTH);
            pingChar = svc.getCharacteristic(PLEJD_PING);
            if (lightLevelChar == null || dataChar == null || authChar == null || pingChar == null) {
                fail("PLEJD GATT CHARACTERISTIC MISSING"); return;
            }
            try {
                g.setCharacteristicNotification(lightLevelChar, true);
                BluetoothGattDescriptor cccd = lightLevelChar.getDescriptor(CCCD);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(cccd)) beginAuth();
                } else beginAuth();
            } catch (Exception e) { fail("NOTIFY: " + e.getMessage()); }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            if (descriptor.getCharacteristic().getUuid().equals(PLEJD_LIGHTLEVEL)) beginAuth();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { fail("GATT WRITE FAILED " + statusCode); return; }
            UUID id = c.getUuid();
            if (id.equals(PLEJD_AUTH)) {
                if (authStage == 1) { authStage = 2; readGatt(authChar); }
                else if (authStage == 3) {
                    authStage = 4;
                    lastPing = (byte) (System.nanoTime() & 0xff);
                    writeGatt(pingChar, new byte[]{lastPing});
                }
            } else if (id.equals(PLEJD_PING) && authStage == 4) {
                authStage = 5;
                readGatt(pingChar);
            }
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { fail("GATT READ FAILED " + statusCode); return; }
            byte[] b = c.getValue();
            if (c.getUuid().equals(PLEJD_AUTH) && authStage == 2) {
                try {
                    authStage = 3;
                    writeGatt(authChar, authResponse(cryptoKey, b));
                } catch (Exception e) { fail("AUTH CRYPTO: " + e.getMessage()); }
            } else if (c.getUuid().equals(PLEJD_PING) && authStage == 5) {
                int expected = ((lastPing & 0xff) + 1) & 0xff;
                if (b != null && b.length > 0 && (b[0] & 0xff) == expected) {
                    authStage = 6;
                    setStatus("GATT AUTH OK // POLLING MESH…");
                    pollMesh();
                } else fail("P-MESH AUTH FAILED");
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (c.getUuid().equals(PLEJD_LIGHTLEVEL)) parseLightLevels(c.getValue());
        }
    };

    private void beginAuth() {
        authStage = 1;
        setStatus("0009 // AUTHENTICATING…");
        writeGatt(authChar, new byte[]{0x00});
    }

    private void pollMesh() {
        try {
            lightLevelChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            lightLevelChar.setValue(new byte[]{0x01});
            if (!gatt.writeCharacteristic(lightLevelChar)) { fail("0003 POLL REJECTED"); return; }
            main.postDelayed(() -> {
                if (meshTargets.isEmpty()) {
                    try { gatt.readCharacteristic(lightLevelChar); } catch (Exception ignored) {}
                }
            }, 700);
        } catch (Exception e) { fail("0003 POLL: " + e.getMessage()); }
    }

    private void parseLightLevels(byte[] data) {
        if (data == null || data.length < 10) return;
        for (int i = 0; i + 9 < data.length; i += 10) {
            int address = data[i] & 0xff;
            if (address > 0) meshTargets.add(address);
        }
        updateNodeLabel();
        if (!meshTargets.isEmpty()) {
            meshReady = true;
            setStatus("P-MESH ONLINE // RAW GATT");
            main.post(() -> {
                dimmer.setEnabled(true);
                rootButton.setEnabled(true);
                linkButton.setEnabled(true);
            });
        }
    }

    private void scheduleDim(int p) {
        if (pendingDim != null) main.removeCallbacks(pendingDim);
        pendingDim = () -> sendDim(p);
        main.postDelayed(pendingDim, 100);
    }

    private void sendDim(int percent) {
        if (!meshReady || dataChar == null || cryptoKey == null || gatewayAddress == null) return;
        percent = Math.max(0, Math.min(100, percent));
        int level = Math.max(1, Math.round(percent * 255f / 100f));
        List<Integer> targets = new ArrayList<>(meshTargets);
        for (int i = 0; i < targets.size(); i++) {
            final int address = targets.get(i);
            final int p = percent;
            final int l = level;
            main.postDelayed(() -> sendOne(address, p, l), i * 28L);
        }
    }

    private void sendOne(int address, int percent, int level) {
        try {
            byte[] plain = percent == 0
                    ? new byte[]{(byte) address, 0x01, 0x10, 0x00, (byte) 0x97, 0x00}
                    : new byte[]{(byte) address, 0x01, 0x10, 0x00, (byte) 0x98, 0x01, (byte) level, (byte) level};
            byte[] encrypted = crypt(cryptoKey, gatewayAddress, plain);
            dataChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            dataChar.setValue(encrypted);
            gatt.writeCharacteristic(dataChar);
        } catch (Exception e) { setStatus("0004 WRITE: " + e.getMessage()); }
    }

    private void writeGatt(BluetoothGattCharacteristic c, byte[] value) {
        try {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            c.setValue(value);
            if (!gatt.writeCharacteristic(c)) fail("GATT WRITE REJECTED");
        } catch (Exception e) { fail("GATT WRITE: " + e.getMessage()); }
    }

    private void readGatt(BluetoothGattCharacteristic c) {
        try { if (!gatt.readCharacteristic(c)) fail("GATT READ REJECTED"); }
        catch (Exception e) { fail("GATT READ: " + e.getMessage()); }
    }

    private static byte[] crypt(byte[] key, String address, byte[] input) throws Exception {
        byte[] addr = hexToBytes(address.replace(":", "").replace("-", ""));
        reverse(addr);
        byte[] block = new byte[16];
        System.arraycopy(addr, 0, block, 0, 6);
        System.arraycopy(addr, 0, block, 6, 6);
        System.arraycopy(addr, 0, block, 12, 4);
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] stream = cipher.doFinal(block);
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) out[i] = (byte) (input[i] ^ stream[i % 16]);
        return out;
    }

    private static byte[] authResponse(byte[] key, byte[] challenge) throws Exception {
        if (key == null || key.length != 16 || challenge == null || challenge.length != 16) throw new Exception("bad key/challenge length");
        byte[] x = new byte[16];
        for (int i = 0; i < 16; i++) x[i] = (byte) (key[i] ^ challenge[i]);
        byte[] d = MessageDigest.getInstance("SHA-256").digest(x);
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) (d[i] ^ d[i + 16]);
        return out;
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static void reverse(byte[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            byte t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }

    private String rootShell(String command, int limit) throws Exception {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                int room = limit - out.size();
                if (room <= 0) break;
                out.write(buf, 0, Math.min(n, room));
            }
        }
        int rc = p.waitFor();
        String s = out.toString("UTF-8");
        if (rc != 0 && s.trim().isEmpty()) throw new Exception("su failed rc=" + rc);
        return s;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void fail(String msg) {
        meshReady = false;
        setStatus((msg == null ? "ERROR" : msg).toUpperCase(Locale.ROOT));
        main.post(() -> {
            dimmer.setEnabled(false);
            rootButton.setEnabled(true);
            linkButton.setEnabled(true);
        });
    }

    private void closeGatt() {
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {}
        gatt = null;
        authStage = 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        closeGatt();
        io.shutdownNow();
    }
}
