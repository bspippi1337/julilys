package com.blckswan.julilys;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final int REQ_BLE = 42;
    private static final String PREFS = "blckswan_local_mesh";

    private static final UUID PLEJD_SERVICE = UUID.fromString("31ba0001-6085-4726-be45-040c957391b5");
    private static final UUID PLEJD_DATA = UUID.fromString("31ba0004-6085-4726-be45-040c957391b5");
    private static final UUID PLEJD_AUTH = UUID.fromString("31ba0009-6085-4726-be45-040c957391b5");
    private static final UUID PLEJD_PING = UUID.fromString("31ba000a-6085-4726-be45-040c957391b5");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText keyInput;
    private EditText targetsInput;
    private Button importRoot;
    private Button connect;
    private TextView status;
    private TextView value;
    private TextView targetCount;
    private SeekBar dimmer;

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic authChar;
    private BluetoothGattCharacteristic pingChar;
    private BluetoothGattCharacteristic dataChar;

    private byte[] cryptoKey;
    private String gatewayAddress;
    private final List<Integer> dimTargets = new ArrayList<>();

    private int authStage = 0;
    private byte lastPing = 0;
    private boolean meshReady = false;
    private boolean dataWriteBusy = false;
    private final ArrayList<byte[]> dataQueue = new ArrayList<>();
    private Integer pendingPercent = null;
    private Runnable pendingDimRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadSavedConfig();
        requestBlePermissionsIfNeeded();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private void buildUi() {
        int green = Color.rgb(112, 255, 156);
        int text = Color.rgb(240, 247, 242);
        int muted = Color.rgb(142, 160, 150);
        int panel = Color.rgb(13, 18, 15);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(5, 7, 6));

        TextView brand = label("BLCKSWAN", 34, green);
        brand.setTypeface(null, 1);
        root.addView(brand);

        TextView sub = label("JULILYS // P-MESH DIMMER", 13, muted);
        sub.setPadding(0, 0, 0, dp(20));
        root.addView(sub);

        status = label("LOCAL MODE // API BYPASSED", 14, green);
        status.setPadding(0, 0, 0, dp(14));
        root.addView(status);

        keyInput = new EditText(this);
        keyInput.setHint("P-mesh cryptoKey (32 hex)");
        keyInput.setHintTextColor(muted);
        keyInput.setTextColor(text);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyInput.setBackgroundColor(panel);
        keyInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(keyInput, new LinearLayout.LayoutParams(-1, -2));

        targetsInput = new EditText(this);
        targetsInput.setHint("Dimmable addresses, e.g. 11,12,17");
        targetsInput.setHintTextColor(muted);
        targetsInput.setTextColor(text);
        targetsInput.setSingleLine(true);
        targetsInput.setInputType(InputType.TYPE_CLASS_TEXT);
        targetsInput.setBackgroundColor(panel);
        targetsInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(8);
        root.addView(targetsInput, tp);

        importRoot = new Button(this);
        importRoot.setText("AUTO IMPORT // ROOT");
        importRoot.setTextColor(green);
        importRoot.setBackgroundColor(panel);
        importRoot.setOnClickListener(v -> autoImportRoot());
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(50));
        ip.topMargin = dp(10);
        root.addView(importRoot, ip);

        connect = new Button(this);
        connect.setText("LINK LOCAL P-MESH");
        connect.setTextColor(Color.BLACK);
        connect.setBackgroundColor(green);
        connect.setOnClickListener(v -> startLocalLink());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(54));
        bp.topMargin = dp(8);
        root.addView(connect, bp);

        LinearLayout spacer = new LinearLayout(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        value = label("0%", 58, text);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(value);

        targetCount = label("mesh not linked", 13, muted);
        targetCount.setGravity(Gravity.CENTER_HORIZONTAL);
        targetCount.setPadding(0, 0, 0, dp(12));
        root.addView(targetCount);

        dimmer = new SeekBar(this);
        dimmer.setMax(100);
        dimmer.setProgress(0);
        dimmer.setEnabled(false);
        dimmer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + "%");
                if (fromUser && meshReady) scheduleDim(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (meshReady) sendDim(seekBar.getProgress());
            }
        });
        root.addView(dimmer, new LinearLayout.LayoutParams(-1, dp(64)));

        TextView footer = label("NO CLOUD // NO API // BLE ONLY", 11, muted);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(0, dp(10), 0, 0);
        root.addView(footer);

        setContentView(root);
    }

    private void loadSavedConfig() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        keyInput.setText(p.getString("key", ""));
        targetsInput.setText(p.getString("targets", ""));
    }

    private void saveConfig() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("key", keyInput.getText().toString().trim())
                .putString("targets", targetsInput.getText().toString().trim())
                .apply();
    }

    private void setStatus(String s) {
        main.post(() -> status.setText(s));
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

    private void startLocalLink() {
        if (!hasBlePermissions()) {
            requestBlePermissionsIfNeeded();
            setStatus("BLE PERMISSION REQUIRED");
            return;
        }

        try {
            String key = keyInput.getText().toString().replace("-", "").replace(" ", "").trim();
            cryptoKey = hexToBytes(key);
            if (cryptoKey.length != 16) throw new Exception("cryptoKey must be 16 bytes / 32 hex");

            List<Integer> targets = parseTargets(targetsInput.getText().toString());
            if (targets.isEmpty()) throw new Exception("No dimmable addresses");
            dimTargets.clear();
            dimTargets.addAll(targets);
            saveConfig();

            connect.setEnabled(false);
            importRoot.setEnabled(false);
            dimmer.setEnabled(false);
            meshReady = false;
            targetCount.setText(dimTargets.size() + " local dimmer" + (dimTargets.size() == 1 ? "" : "s"));
            startBleScan();
        } catch (Exception e) {
            fail("LOCAL CONFIG: " + e.getMessage());
        }
    }

    private List<Integer> parseTargets(String raw) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            if (part.trim().isEmpty()) continue;
            try {
                int n = Integer.decode(part.trim());
                if (n >= 0 && n <= 255) out.add(n);
            } catch (Exception ignored) {}
        }
        return new ArrayList<>(out);
    }

    private void autoImportRoot() {
        importRoot.setEnabled(false);
        connect.setEnabled(false);
        setStatus("ROOT IMPORT // SEARCHING PLEJD CACHE…");

        io.execute(() -> {
            try {
                ImportData found = new ImportData();
                String cmd = "for d in /data/user/0/com.plejd.plejdapp /data/data/com.plejd.plejdapp; do "
                        + "[ -d \\\"$d\\\" ] && grep -RIl -m1 -E 'cryptoKey|CryptoKey|outputAddress|_outputAddresses' \\\"$d\\\" 2>/dev/null; "
                        + "done | head -40";
                String listing = rootShell(cmd, 128 * 1024);
                for (String path : listing.split("\\r?\\n")) {
                    path = path.trim();
                    if (path.isEmpty()) continue;
                    try {
                        String raw = rootShell("head -c 8388608 " + shellQuote(path) + " 2>/dev/null", 8 * 1024 * 1024);
                        inspectLocalData(raw, found);
                        if (found.key != null && !found.targets.isEmpty()) break;
                    } catch (Exception ignored) {}
                }

                if (found.key == null) {
                    throw new Exception("cryptoKey not found in local Plejd data");
                }

                StringBuilder targets = new StringBuilder();
                for (int n : found.targets) {
                    if (targets.length() > 0) targets.append(',');
                    targets.append(n);
                }

                final String importedKey = found.key;
                final String importedTargets = targets.toString();
                main.post(() -> {
                    keyInput.setText(importedKey);
                    if (!importedTargets.isEmpty()) targetsInput.setText(importedTargets);
                    saveConfig();
                    importRoot.setEnabled(true);
                    connect.setEnabled(true);
                    if (!importedTargets.isEmpty()) {
                        setStatus("ROOT IMPORT OK // LINKING LOCAL MESH…");
                        startLocalLink();
                    } else {
                        setStatus("KEY IMPORTED // ENTER DIMMER ADDRESSES");
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    importRoot.setEnabled(true);
                    connect.setEnabled(true);
                });
                fail("ROOT IMPORT: " + e.getMessage());
            }
        });
    }

    private void inspectLocalData(String raw, ImportData out) {
        if (raw == null || raw.isEmpty()) return;
        String normalized = raw.replace("&quot;", "\"").replace("\\\"", "\"");

        if (out.key == null) {
            Pattern p = Pattern.compile("(?i)(?:cryptoKey|CryptoKey)[\\\"'\\s:=]+([0-9a-f-]{32,40})");
            Matcher m = p.matcher(normalized);
            if (m.find()) {
                String k = m.group(1).replace("-", "");
                if (k.length() == 32) out.key = k;
            }
        }

        try {
            String trimmed = normalized.trim();
            if (trimmed.startsWith("{")) {
                scanJson(new JSONObject(trimmed), out, 0);
            } else if (trimmed.startsWith("[")) {
                scanJson(new JSONArray(trimmed), out, 0);
            } else {
                int first = normalized.indexOf('{');
                int last = normalized.lastIndexOf('}');
                if (first >= 0 && last > first) {
                    String candidate = normalized.substring(first, last + 1);
                    try { scanJson(new JSONObject(candidate), out, 0); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private void scanJson(Object node, ImportData out, int depth) {
        if (node == null || depth > 10) return;
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;

                Iterator<String> names = o.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    if (out.key == null && name.equalsIgnoreCase("cryptoKey")) {
                        String k = o.optString(name, "").replace("-", "");
                        if (k.length() == 32) out.key = k;
                    }
                }

                if (o.has("devices") && o.has("outputSettings") && o.has("outputAddress")) {
                    try { out.targets.addAll(findDimmableTargets(o)); } catch (Exception ignored) {}
                }

                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = o.opt(k);
                    if (v instanceof JSONObject || v instanceof JSONArray) {
                        scanJson(v, out, depth + 1);
                    } else if (v instanceof String && depth < 4) {
                        String s = ((String) v).trim();
                        if (s.startsWith("{") || s.startsWith("[")) {
                            try {
                                scanJson(s.startsWith("{") ? new JSONObject(s) : new JSONArray(s), out, depth + 1);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } else if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                for (int i = 0; i < a.length(); i++) scanJson(a.opt(i), out, depth + 1);
            }
        } catch (Exception ignored) {}
    }

    private List<Integer> findDimmableTargets(JSONObject details) throws Exception {
        Map<String, JSONObject> devicesByObjectId = new HashMap<>();
        JSONArray devices = details.optJSONArray("devices");
        if (devices != null) {
            for (int i = 0; i < devices.length(); i++) {
                JSONObject d = devices.getJSONObject(i);
                devicesByObjectId.put(d.optString("objectId"), d);
            }
        }

        JSONObject outputAddress = details.optJSONObject("outputAddress");
        JSONArray outputSettings = details.optJSONArray("outputSettings");
        LinkedHashSet<Integer> targets = new LinkedHashSet<>();
        if (outputAddress == null || outputSettings == null) return new ArrayList<>(targets);

        for (int i = 0; i < outputSettings.length(); i++) {
            JSONObject setting = outputSettings.getJSONObject(i);
            JSONObject device = devicesByObjectId.get(setting.optString("deviceParseId"));
            if (device == null) continue;
            int traits = device.optInt("traits", 0);
            String outputType = device.optString("outputType", "");
            boolean dimmable = "LIGHT".equalsIgnoreCase(outputType) || (traits & 0x02) != 0;
            if (!dimmable) continue;

            String deviceId = setting.optString("deviceId", "");
            int output = setting.optInt("output", -1);
            JSONObject outputs = outputAddress.optJSONObject(deviceId);
            if (outputs == null || output < 0) continue;
            int address = outputs.optInt(String.valueOf(output), -1);
            if (address >= 0 && address <= 255) targets.add(address);
        }
        return new ArrayList<>(targets);
    }

    private String rootShell(String command, int maxBytes) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                int take = Math.min(n, maxBytes - total);
                if (take > 0) out.write(buf, 0, take);
                total += take;
                if (total >= maxBytes) break;
            }
        }
        int rc = p.waitFor();
        if (rc != 0 && out.size() == 0) throw new Exception("su denied or Plejd cache unreadable");
        return out.toString("UTF-8");
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void startBleScan() {
        setStatus("SCANNING LOCAL P-MESH…");
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            fail("BLUETOOTH IS OFF");
            return;
        }
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) throw new Exception("BLE scanner unavailable");
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(PLEJD_SERVICE)).build();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Arrays.asList(filter), settings, scanCallback);
            main.postDelayed(() -> {
                if (!meshReady && gatt == null) {
                    stopScan();
                    fail("NO LOCAL P-MESH FOUND");
                }
            }, 15000);
        } catch (Exception e) {
            fail("SCAN: " + e.getMessage());
        }
    }

    private void stopScan() {
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {}
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (gatt != null) return;
            stopScan();
            BluetoothDevice device = result.getDevice();
            gatewayAddress = device.getAddress();
            setStatus("CONNECTING " + gatewayAddress + "…");
            try {
                gatt = device.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                fail("CONNECT: " + e.getMessage());
            }
        }

        @Override public void onScanFailed(int errorCode) {
            fail("SCAN FAILED: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("DISCOVERING P-MESH…");
                try { g.discoverServices(); } catch (SecurityException e) { fail("GATT: " + e.getMessage()); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                meshReady = false;
                main.post(() -> dimmer.setEnabled(false));
                setStatus("P-MESH DISCONNECTED");
                try { g.close(); } catch (Exception ignored) {}
                if (gatt == g) gatt = null;
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                fail("SERVICE DISCOVERY FAILED");
                return;
            }
            BluetoothGattService svc = g.getService(PLEJD_SERVICE);
            if (svc == null) {
                fail("PLEJD SERVICE MISSING");
                return;
            }
            authChar = svc.getCharacteristic(PLEJD_AUTH);
            pingChar = svc.getCharacteristic(PLEJD_PING);
            dataChar = svc.getCharacteristic(PLEJD_DATA);
            if (authChar == null || pingChar == null || dataChar == null) {
                fail("PLEJD CHARACTERISTICS MISSING");
                return;
            }
            authStage = 1;
            setStatus("AUTHENTICATING LOCAL P-MESH…");
            writeGatt(authChar, new byte[]{0x00});
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                if (characteristic.getUuid().equals(PLEJD_DATA)) {
                    dataWriteBusy = false;
                    dataQueue.clear();
                    fail("DIM WRITE FAILED");
                } else {
                    fail("GATT WRITE FAILED");
                }
                return;
            }
            UUID id = characteristic.getUuid();
            if (id.equals(PLEJD_AUTH)) {
                if (authStage == 1) {
                    authStage = 2;
                    readGatt(authChar);
                } else if (authStage == 3) {
                    authStage = 4;
                    lastPing = (byte) (System.nanoTime() & 0xff);
                    writeGatt(pingChar, new byte[]{lastPing});
                }
            } else if (id.equals(PLEJD_PING) && authStage == 4) {
                authStage = 5;
                readGatt(pingChar);
            } else if (id.equals(PLEJD_DATA)) {
                writeNextData();
            }
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                fail("GATT READ FAILED");
                return;
            }
            UUID id = characteristic.getUuid();
            byte[] bytes = characteristic.getValue();
            if (id.equals(PLEJD_AUTH) && authStage == 2) {
                try {
                    byte[] response = authResponse(cryptoKey, bytes);
                    authStage = 3;
                    writeGatt(authChar, response);
                } catch (Exception e) {
                    fail("AUTH CRYPTO: " + e.getMessage());
                }
            } else if (id.equals(PLEJD_PING) && authStage == 5) {
                int expected = ((lastPing & 0xff) + 1) & 0xff;
                if (bytes != null && bytes.length > 0 && (bytes[0] & 0xff) == expected) {
                    authStage = 6;
                    meshReady = true;
                    setStatus("LOCAL P-MESH ONLINE // " + dimTargets.size() + " DIMMERS");
                    main.post(() -> {
                        dimmer.setEnabled(true);
                        connect.setEnabled(true);
                        importRoot.setEnabled(true);
                    });
                } else {
                    fail("P-MESH AUTH FAILED");
                }
            }
        }
    };

    private void writeGatt(BluetoothGattCharacteristic c, byte[] data) {
        try {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            c.setValue(data);
            if (!gatt.writeCharacteristic(c)) fail("GATT WRITE REJECTED");
        } catch (SecurityException e) {
            fail("BLE PERMISSION LOST");
        }
    }

    private void readGatt(BluetoothGattCharacteristic c) {
        try {
            if (!gatt.readCharacteristic(c)) fail("GATT READ REJECTED");
        } catch (SecurityException e) {
            fail("BLE PERMISSION LOST");
        }
    }

    private void scheduleDim(int percent) {
        if (pendingDimRunnable != null) main.removeCallbacks(pendingDimRunnable);
        pendingDimRunnable = () -> sendDim(percent);
        main.postDelayed(pendingDimRunnable, 90);
    }

    private synchronized void sendDim(int percent) {
        if (!meshReady || dataChar == null || cryptoKey == null || gatewayAddress == null) return;
        percent = Math.max(0, Math.min(100, percent));
        if (dataWriteBusy) {
            pendingPercent = percent;
            return;
        }
        try {
            dataQueue.clear();
            int level = Math.max(1, Math.round(percent * 255f / 100f));
            for (int address : dimTargets) {
                byte[] plain;
                if (percent == 0) {
                    plain = new byte[]{(byte) address, 0x01, 0x10, 0x00, (byte) 0x97, 0x00};
                } else {
                    plain = new byte[]{(byte) address, 0x01, 0x10, 0x00, (byte) 0x98, 0x01, (byte) level, (byte) level};
                }
                dataQueue.add(crypt(cryptoKey, gatewayAddress, plain));
            }
            dataWriteBusy = true;
            pendingPercent = null;
            writeNextData();
        } catch (Exception e) {
            fail("DIM CRYPTO: " + e.getMessage());
        }
    }

    private synchronized void writeNextData() {
        if (!dataQueue.isEmpty()) {
            byte[] next = dataQueue.remove(0);
            writeGatt(dataChar, next);
            return;
        }
        dataWriteBusy = false;
        if (pendingPercent != null) {
            int p = pendingPercent;
            pendingPercent = null;
            main.post(() -> sendDim(p));
        }
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
        if (key.length != 16 || challenge == null || challenge.length != 16) throw new Exception("Expected 16-byte key/challenge");
        byte[] x = new byte[16];
        for (int i = 0; i < 16; i++) x[i] = (byte) (key[i] ^ challenge[i]);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(x);
        byte[] response = new byte[16];
        for (int i = 0; i < 16; i++) response[i] = (byte) (digest[i] ^ digest[i + 16]);
        return response;
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) throw new IllegalArgumentException("Odd-length hex");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static void reverse(byte[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            byte t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }

    private void fail(String message) {
        meshReady = false;
        setStatus(message == null ? "ERROR" : message.toUpperCase(Locale.ROOT));
        main.post(() -> {
            connect.setEnabled(true);
            importRoot.setEnabled(true);
            dimmer.setEnabled(false);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {}
        io.shutdownNow();
    }

    private static class ImportData {
        String key;
        final LinkedHashSet<Integer> targets = new LinkedHashSet<>();
    }
}
