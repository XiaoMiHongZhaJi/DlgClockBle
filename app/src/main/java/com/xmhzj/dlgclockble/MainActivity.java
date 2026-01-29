package com.xmhzj.dlgclockble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.ByteBuffer;
import java.util.*;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private static final String SERVICE_UUID = "00001f10-0000-1000-8000-00805f9b34fb";
    private static final String CHAR_UUID = "00001f1f-0000-1000-8000-00805f9b34fb";
    private static final String PREFS_NAME = "BleHistory";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxTxChar;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText etMac;
    private TextView tvLog;
    private ScrollView scrollLog;
    private ListView lvScan, lvHistory;
    private Button btnConnect, btnHistoryToggle, btnSync, btnRefresh, btnInvert/*, btnRestart*/;
    private Button btnClearHistory; // 在类成员变量位置增加
    private Button btnRescan;

    private ArrayAdapter<String> scanAdapter, historyAdapter;
    private List<String> scanList = new ArrayList<>();
    private List<String> historyList = new ArrayList<>();

    private boolean isConnected = false;
    private String currentDeviceName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI();
        checkPermissions();
        setupBluetooth();
        loadHistory();
        startScan();
        addLog("正在扫描设备...");
    }

    private void initUI() {
        etMac = findViewById(R.id.et_mac);
        tvLog = findViewById(R.id.tv_log);
        scrollLog = findViewById(R.id.scroll_log);
        lvScan = findViewById(R.id.lv_scan);
        lvHistory = findViewById(R.id.lv_history);
        btnConnect = findViewById(R.id.btn_connect);
        btnHistoryToggle = findViewById(R.id.btn_history_toggle);
        btnSync = findViewById(R.id.btn_sync);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnInvert = findViewById(R.id.btn_invert);
//        btnRestart = findViewById(R.id.btn_restart);

        // 扫描列表点击：自动连接
        scanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, scanList);
        lvScan.setAdapter(scanAdapter);
        lvScan.setOnItemClickListener((p, v, pos, id) -> {
            String item = scanList.get(pos);
            String[] split = item.split("\n");
            String name = split[0];
            String mac = split[1];
            etMac.setText(mac + " | " + name);
            connectToDevice(mac);
        });

        // 历史列表点击：自动连接
        historyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, historyList);
        lvHistory.setAdapter(historyAdapter);
        lvHistory.setOnItemClickListener((p, v, pos, id) -> {
            String item = historyList.get(pos);
            String mac = item.split("\\|")[0].trim();
            lvHistory.setVisibility(View.GONE);
            btnClearHistory.setVisibility(View.GONE); // 同步显示/隐藏清空按钮
            btnHistoryToggle.setText("历史设备");
            etMac.setText(item);
            connectToDevice(mac);
        });

        // 连接/断开 按钮逻辑
        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                disconnectDevice();
            } else {
                String mac = formatMac(etMac.getText().toString());
                if (mac.isEmpty()) {
                    addLog("请选择或输入设备 MAC 地址");
                } else if (isValidMac(mac)) {
                    connectToDevice(mac);
                } else {
                    addLog("MAC 地址格式错误");
                }
            }
        });

        btnRescan = findViewById(R.id.btn_rescan);

        btnRescan.setOnClickListener(v -> {
            refreshDeviceList();
        });

        btnClearHistory = findViewById(R.id.btn_clear_history);

        // 修改：历史切换按钮逻辑，同步控制“清空按钮”的显示
        btnHistoryToggle.setOnClickListener(v -> {
            boolean isNowVisible = (lvHistory.getVisibility() == View.GONE);
            int visibility = isNowVisible ? View.VISIBLE : View.GONE;

            lvHistory.setVisibility(visibility);
            btnClearHistory.setVisibility(visibility); // 同步显示/隐藏清空按钮
            btnHistoryToggle.setText(isNowVisible ? "隐藏" : "历史设备");
        });

        // 清空历史点击事件
        btnClearHistory.setOnClickListener(v -> {
            if (historyList.isEmpty()) {
                Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show();
                return;
            }

            // 弹出确认对话框
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("确认清空")
                    .setMessage("确定要删除所有历史连接记录吗？")
                    .setPositiveButton("确定", (dialog, which) -> {
                        performClearHistory();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnSync.setOnClickListener(v -> syncTime());
        btnRefresh.setOnClickListener(v -> {
            addLog("刷新屏幕...");
            triggerRxTxCmd("E2");
        });
        btnInvert.setOnClickListener(v -> {
            addLog("屏幕反色... (部分固件可能不支持)");
            triggerRxTxCmd("E3");
        });
//        btnRestart.setOnClickListener(v -> triggerRxTxCmd("EFEEEF"));
    }

    private void refreshDeviceList() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            addLog("刷新失败: 蓝牙未开启");
            return;
        }

        addLog("正在重新扫描设备...");

        // 1. 清空当前扫描到的列表
        scanList.clear();
        scanAdapter.notifyDataSetChanged();

        // 2. 停止当前扫描（如果有）并重新开始
        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner != null) {
            // 先停止旧扫描防止重叠
            scanner.stopScan(scanCallback);
            // 延迟一下开始新扫描，确保清理完成
            mainHandler.postDelayed(this::startScan, 200);
        }
    }

    private void performClearHistory() {
        // 1. 清空内存列表
        historyList.clear();

        // 2. 清空持久化存储 (SharedPreferences)
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().remove("list").apply();

        // 3. 通知适配器刷新UI
        historyAdapter.notifyDataSetChanged();

        // 4. 自动收起面板 (可选)
        lvHistory.setVisibility(View.GONE);
        btnClearHistory.setVisibility(View.GONE);
        btnHistoryToggle.setText("历史设备");

        addLog("历史记录已清空");
    }

    private void addLog(String msg) {
        mainHandler.post(() -> {
            if (currentDeviceName.isEmpty()) {
                tvLog.append(msg + "\n");
            } else {
                tvLog.append("[" + currentDeviceName + "] " + msg + "\n");
            }
            scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void updateUIState(String text, boolean canConnect, boolean functionsEnabled) {
        mainHandler.post(() -> {
            btnConnect.setText(text);
            btnConnect.setEnabled(canConnect);
            btnSync.setEnabled(functionsEnabled);
            btnRefresh.setEnabled(functionsEnabled);
            btnInvert.setEnabled(functionsEnabled);
//            btnRestart.setEnabled(functionsEnabled);
        });
    }

    private void connectToDevice(String mac) {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        updateUIState("正在连接...", false, false);
        addLog("尝试连接: " + mac);

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mac);
        currentDeviceName = device.getName() != null ? device.getName() : "Unknown";
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private void disconnectDevice() {
        if (bluetoothGatt != null) {
            addLog("断开连接...");
            bluetoothGatt.disconnect();
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                updateUIState("正在获取服务...", false, false);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                rxTxChar = null;
                addLog("已断开连接");
                currentDeviceName = "";

                // 需求：显示“已断开连接”，一秒后改回“连接设备”
                updateUIState("已断开连接", false, false);
                mainHandler.postDelayed(() -> updateUIState("连接设备", true, false), 1000);

                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(UUID.fromString(SERVICE_UUID));
                if (service != null) {
                    rxTxChar = service.getCharacteristic(UUID.fromString(CHAR_UUID));
                    addLog("服务已就绪");
                    updateUIState("已连接 (点击断开)", true, true);
                    saveHistory(currentDeviceName, gatt.getDevice().getAddress());
                } else {
                    addLog("错误: 找不到服务UUID");
                    disconnectDevice();
                }
            }
        }
    };

    // --- 以下逻辑保持与前版本一致或微调 ---

    private void triggerRxTxCmd(String hex) {
        addLog("发送: " + hex);
        sendBleCmd(hexToBytes(hex));
    }

    private void syncTime() {
        new Thread(() -> {
            try {
                Calendar cal = Calendar.getInstance();
                long unixNow = cal.getTimeInMillis() / 1000 + 8 * 3600;
                ByteBuffer buf = ByteBuffer.allocate(10);
                buf.put((byte) 0xDD);
                buf.putInt((int) unixNow);
                buf.putShort((short) cal.get(Calendar.YEAR));
                buf.put((byte) (cal.get(Calendar.MONTH) + 1));
                buf.put((byte) cal.get(Calendar.DAY_OF_MONTH));
                buf.put((byte) (cal.get(Calendar.DAY_OF_WEEK) - 1));

                sendBleCmd(buf.array());
                Thread.sleep(150);
                sendBleCmd(new byte[]{(byte) 0xE2});
                addLog("对时成功: " + String.format("%tT", cal));
                addLog("若屏幕无反应，请点击[刷新屏幕]");
            } catch (Exception e) {
                addLog("对时失败");
            }
        }).start();
    }

    private void sendBleCmd(byte[] data) {
        if (rxTxChar == null || bluetoothGatt == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt.writeCharacteristic(rxTxChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            rxTxChar.setValue(data);
            bluetoothGatt.writeCharacteristic(rxTxChar);
        }
    }

    private String formatMac(String raw) {
        raw = raw.split("\\|")[0].trim();
        String clean = raw.replaceAll("[^a-fA-F0-9]", "").toUpperCase();
        if (clean.length() != 12) return clean;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            sb.append(clean, i, i + 2);
            if (i < 10) sb.append(":");
        }
        return sb.toString();
    }

    private boolean isValidMac(String mac) {
        return mac.matches("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");
    }

    private void setupBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager.getAdapter();
    }

    // 1. 提取为成员变量
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name != null && (name.startsWith("DLG-") || name.startsWith("NRF-"))) {
                String entry = name + "\n" + device.getAddress();
                if (!scanList.contains(entry)) {
                    runOnUiThread(() -> {
                        scanList.add(entry);
                        scanAdapter.notifyDataSetChanged();
                    });
                }
            }
        }
    };

    // 2. 修改 startScan 方法
    private void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner != null) {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(), scanCallback);
        }
    }

    private void saveHistory(String name, String mac) {
        String entry = mac + " | " + (name == null ? "Unknown" : name);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> set = new LinkedHashSet<>(prefs.getStringSet("list", new LinkedHashSet<>()));
        set.remove(entry);
        Set<String> newSet = new LinkedHashSet<>();
        newSet.add(entry);
        newSet.addAll(set);
        prefs.edit().putStringSet("list", newSet).apply();
        runOnUiThread(() -> {
            historyList.clear();
            historyList.addAll(newSet);
            historyAdapter.notifyDataSetChanged();
        });
    }

    private void loadHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        historyList.addAll(prefs.getStringSet("list", new LinkedHashSet<>()));
        historyAdapter.notifyDataSetChanged();
    }

    private byte[] hexToBytes(String s) {
        byte[] data = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }
}