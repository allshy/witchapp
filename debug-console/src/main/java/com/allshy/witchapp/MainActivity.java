package com.allshy.witchapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_BT_CONNECT = 10;
    private static final int REQ_ENABLE_BT = 11;
    private static final int REQ_SAVE_RX = 12;
    private static final int REQ_SAVE_TX = 13;
    private static final int MAX_VISIBLE_LOG_LINES = 1200;
    private static final long INTER_CMD_MS = 80L;

    private static final int BG = Color.rgb(17, 19, 24);
    private static final int PANEL = Color.rgb(31, 36, 44);
    private static final int PANEL_ALT = Color.rgb(38, 44, 53);
    private static final int TEXT = Color.rgb(232, 236, 243);
    private static final int MUTED = Color.rgb(160, 169, 181);
    private static final int BLUE = Color.rgb(88, 166, 255);
    private static final int GREEN = Color.rgb(115, 230, 150);
    private static final int YELLOW = Color.rgb(255, 210, 95);
    private static final int PINK = Color.rgb(255, 128, 192);
    private static final int RED = Color.rgb(255, 100, 100);

    private static final Scenario[] SCENARIOS = {
            new Scenario("静息 START", "OV+RST=HR", "OV+DBG=HR", "OV+MARK=REST_START"),
            new Scenario("静息 END", "OV+MARK=REST_END", "OV+DBG=OFF"),
            new Scenario("阅读 START", "OV+RST=HR", "OV+DBG=HR", "OV+MARK=READ_START"),
            new Scenario("阅读 END", "OV+MARK=READ_END", "OV+DBG=OFF"),
            new Scenario("慢走 START", "OV+RST=HR", "OV+DBG=HR", "OV+MARK=WALK_SLOW_START"),
            new Scenario("慢走 END", "OV+MARK=WALK_SLOW_END", "OV+DBG=OFF"),
            new Scenario("快走 START", "OV+RST=HR", "OV+DBG=HR", "OV+MARK=WALK_FAST_START"),
            new Scenario("快走 END", "OV+MARK=WALK_FAST_END", "OV+DBG=OFF"),
            new Scenario("恢复 START", "OV+RST=HR", "OV+DBG=HR", "OV+MARK=RECOVERY_START"),
            new Scenario("恢复 END", "OV+MARK=RECOVERY_END", "OV+DBG=OFF"),
            new Scenario("100步 START", "OV+RST=PED", "OV+DBG=ST", "OV+MARK=N100_NORMAL_START"),
            new Scenario("100步 END", "OV+REF=STEP:100", "OV+MARK=N100_NORMAL_END", "OV+DBG=OFF"),
            new Scenario("500步 START", "OV+RST=PED", "OV+DBG=ST", "OV+MARK=N500_START"),
            new Scenario("500步 END", "OV+REF=STEP:500", "OV+MARK=N500_END", "OV+DBG=OFF"),
            new Scenario("挥手误判 START", "OV+RST=PED", "OV+DBG=ST", "OV+MARK=FP_HANDWAVE_START"),
            new Scenario("挥手误判 END", "OV+REF=STEP:0", "OV+MARK=FP_HANDWAVE_END", "OV+DBG=OFF"),
            new Scenario("打字误判 START", "OV+RST=PED", "OV+DBG=ST", "OV+MARK=FP_TYPING_START"),
            new Scenario("打字误判 END", "OV+REF=STEP:0", "OV+MARK=FP_TYPING_END", "OV+DBG=OFF"),
    };

    private static final String[][] CFG_KEYS = {
            {"HR.OUTL", "28", "IBI 离群带 % 中位数"},
            {"HR.REFR", "280", "心搏不应期 ms"},
            {"HR.SLEW", "240", "显示限速 0.1 BPM/s"},
            {"HR.STMAX", "74", "静息态封顶 BPM"},
            {"HR.PMIN", "12", "包络阈值最低值"},
            {"HR.THPCT", "50", "峰阈 = 包络 x pct%"},
            {"PED.PROM", "250", "SW 峰最小突出度 mg"},
            {"PED.PEAKFRAC", "50", "自适应阈值 pct"},
            {"PED.REFR", "200", "全局两步最小间隔 ms"},
            {"PED.HOLD", "3000", "walking 保持 ms"},
            {"PED.WMIN", "300", "回溯步频下限 ms"},
            {"PED.WMAX", "1500", "回溯步频上限 ms"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService txExecutor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private final SimpleDateFormat fileFormat =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private final ArrayList<String> deviceLabels = new ArrayList<>();
    private final ArrayList<String> rxLines = new ArrayList<>();
    private final ArrayList<String> txHistory = new ArrayList<>();
    private final ArrayDeque<LogEntry> visibleLog = new ArrayDeque<>();
    private final SpannableStringBuilder logText = new SpannableStringBuilder();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;
    private Thread readerThread;
    private volatile boolean readerRunning;

    private DarkArrayAdapter deviceAdapter;
    private Spinner deviceSpinner;
    private Spinner cfgSpinner;
    private TextView statusView;
    private TextView bpmView;
    private TextView readyView;
    private TextView motionView;
    private TextView stepsView;
    private TextView cadenceView;
    private TextView walkingView;
    private TextView dbgView;
    private TextView cfgHintView;
    private TextView logView;
    private Button connectButton;
    private EditText markInput;
    private EditText refHrInput;
    private EditText refStepInput;
    private EditText cfgValueInput;
    private EditText customInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        ensureBluetoothReady();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("WitchApp Debug");
        title.setTextColor(TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Android SPP 调试台");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(13);
        root.addView(subtitle);

        buildConnectionPanel(root);
        buildLivePanel(root);
        buildStreamPanel(root);
        buildMarkPanel(root);
        buildResetPanel(root);
        buildScenarioPanel(root);
        buildCfgPanel(root);
        buildCustomPanel(root);
        buildLogPanel(root);

        setContentView(scroll);
    }

    private void buildConnectionPanel(LinearLayout root) {
        LinearLayout panel = section(root, "连接");
        deviceAdapter = new DarkArrayAdapter(this, deviceLabels);
        deviceSpinner = new Spinner(this);
        deviceSpinner.setAdapter(deviceAdapter);
        panel.addView(deviceSpinner, matchWrap());

        LinearLayout row = row();
        Button refresh = button("刷新已配对", v -> refreshDevices());
        connectButton = button("连接", v -> toggleConnection());
        Button settings = button("蓝牙设置", v -> openBluetoothSettings());
        row.addView(refresh, weightButtonParams());
        row.addView(connectButton, weightButtonParams());
        row.addView(settings, weightButtonParams());
        panel.addView(row);

        statusView = smallText("未连接");
        statusView.setTextColor(BLUE);
        panel.addView(statusView);

        Button help = button("配对 / 故障提示", v -> showPairingHelp());
        panel.addView(help, matchWrap());
    }

    private void buildLivePanel(LinearLayout root) {
        LinearLayout panel = section(root, "实时状态");
        bpmView = addStatRow(panel, "BPM", "—", "锁定", "—")[0];
        readyView = lastStatRight;
        motionView = addStatRow(panel, "运动评分", "—", "调试流", "OFF")[0];
        dbgView = lastStatRight;
        stepsView = addStatRow(panel, "总步数", "—", "步频 spm", "—")[0];
        cadenceView = lastStatRight;
        walkingView = addStatRow(panel, "walking", "—", "连接", "SPP")[0];
    }

    private TextView lastStatRight;

    private TextView[] addStatRow(LinearLayout panel, String leftLabel, String leftValue,
                                  String rightLabel, String rightValue) {
        LinearLayout row = row();
        TextView left = addStatTile(row, leftLabel, leftValue);
        TextView right = addStatTile(row, rightLabel, rightValue);
        lastStatRight = right;
        panel.addView(row);
        return new TextView[]{left, right};
    }

    private TextView addStatTile(LinearLayout row, String label, String value) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(10), dp(8), dp(10), dp(8));
        tile.setBackground(roundRect(PANEL_ALT, dp(8)));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(MUTED);
        labelView.setTextSize(12);
        tile.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(TEXT);
        valueView.setTextSize(20);
        valueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tile.addView(valueView);

        row.addView(tile, weightButtonParams());
        return valueView;
    }

    private void buildStreamPanel(LinearLayout root) {
        LinearLayout panel = section(root, "调试数据流");
        LinearLayout row = row();
        row.addView(button("HR", v -> sendCommand("OV+DBG=HR")), weightButtonParams());
        row.addView(button("ST", v -> sendCommand("OV+DBG=ST")), weightButtonParams());
        row.addView(button("ALL", v -> sendCommand("OV+DBG=ALL")), weightButtonParams());
        row.addView(button("OFF", v -> sendCommand("OV+DBG=OFF")), weightButtonParams());
        panel.addView(row);
    }

    private void buildMarkPanel(LinearLayout root) {
        LinearLayout panel = section(root, "标记 / 参考真值");
        markInput = input("REST_START", InputType.TYPE_CLASS_TEXT);
        addInputSend(panel, "MARK", markInput, () -> {
            String tag = markInput.getText().toString().trim();
            if (!tag.isEmpty()) {
                sendCommand("OV+MARK=" + tag);
            }
        });

        refHrInput = input("73", InputType.TYPE_CLASS_NUMBER);
        addInputSend(panel, "REF HR", refHrInput, () -> {
            String value = refHrInput.getText().toString().trim();
            if (isDigits(value)) {
                sendCommand("OV+REF=HR:" + value);
            } else {
                toast("请输入数字 BPM");
            }
        });

        refStepInput = input("100", InputType.TYPE_CLASS_NUMBER);
        addInputSend(panel, "REF STEP", refStepInput, () -> {
            String value = refStepInput.getText().toString().trim();
            if (isDigits(value)) {
                sendCommand("OV+REF=STEP:" + value);
            } else {
                toast("请输入数字步数");
            }
        });
    }

    private void buildResetPanel(LinearLayout root) {
        LinearLayout panel = section(root, "重置");
        LinearLayout row = row();
        row.addView(button("清零步数", v -> sendCommand("OV+RST=PED")), weightButtonParams());
        row.addView(button("重启心率算法", v -> sendCommand("OV+RST=HR")), weightButtonParams());
        panel.addView(row);
    }

    private void buildScenarioPanel(LinearLayout root) {
        LinearLayout panel = section(root, "测试场景一键");
        for (int i = 0; i < SCENARIOS.length; i += 2) {
            LinearLayout row = row();
            Scenario left = SCENARIOS[i];
            row.addView(button(left.label, v -> sendSequence(left.commands)), weightButtonParams());
            if (i + 1 < SCENARIOS.length) {
                Scenario right = SCENARIOS[i + 1];
                row.addView(button(right.label, v -> sendSequence(right.commands)), weightButtonParams());
            }
            panel.addView(row);
        }
    }

    private void buildCfgPanel(LinearLayout root) {
        LinearLayout panel = section(root, "参数热改");
        ArrayList<String> keys = new ArrayList<>();
        for (String[] item : CFG_KEYS) {
            keys.add(item[0]);
        }
        cfgSpinner = new Spinner(this);
        cfgSpinner.setAdapter(new DarkArrayAdapter(this, keys));
        panel.addView(cfgSpinner, matchWrap());

        cfgValueInput = input(CFG_KEYS[0][1], InputType.TYPE_CLASS_NUMBER);
        addInputSend(panel, "VAL", cfgValueInput, this::sendCfg);

        cfgHintView = smallText(CFG_KEYS[0][2]);
        panel.addView(cfgHintView);
        cfgSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                cfgValueInput.setText(CFG_KEYS[position][1]);
                cfgHintView.setText(CFG_KEYS[position][2]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void buildCustomPanel(LinearLayout root) {
        LinearLayout panel = section(root, "自定义命令");
        customInput = input("OV", InputType.TYPE_CLASS_TEXT);
        addInputSend(panel, "CMD", customInput, () -> {
            String cmd = customInput.getText().toString().trim();
            if (!cmd.isEmpty()) {
                sendCommand(cmd);
                customInput.setText("");
            }
        });
    }

    private void buildLogPanel(LinearLayout root) {
        LinearLayout panel = section(root, "日志");
        LinearLayout row = row();
        row.addView(button("清空", v -> clearLog()), weightButtonParams());
        row.addView(button("保存RX", v -> chooseSave(REQ_SAVE_RX)), weightButtonParams());
        row.addView(button("保存TX", v -> chooseSave(REQ_SAVE_TX)), weightButtonParams());
        panel.addView(row);

        logView = new TextView(this);
        logView.setTextColor(TEXT);
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMinHeight(dp(280));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackground(roundRect(Color.rgb(13, 15, 19), dp(8)));

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.addView(logView, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(hScroll, matchWrap());
    }

    private void ensureBluetoothReady() {
        if (bluetoothAdapter == null) {
            statusView.setText("此手机不支持蓝牙");
            connectButton.setEnabled(false);
            return;
        }
        if (!hasConnectPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_CONNECT);
            }
            return;
        }
        try {
            if (!bluetoothAdapter.isEnabled()) {
                startActivityForResult(
                        new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQ_ENABLE_BT);
                return;
            }
        } catch (SecurityException e) {
            requestBluetoothPermission();
            return;
        }
        refreshDevices();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_CONNECT);
        }
    }

    private void refreshDevices() {
        if (!hasConnectPermission()) {
            requestBluetoothPermission();
            return;
        }
        devices.clear();
        deviceLabels.clear();

        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bonded) {
                devices.add(device);
                deviceLabels.add(deviceLabel(device));
            }
        } catch (SecurityException e) {
            requestBluetoothPermission();
            return;
        }

        if (devices.isEmpty()) {
            deviceLabels.add("(未发现已配对设备，请先去系统蓝牙配对 SPP)");
            deviceSpinner.setEnabled(false);
            connectButton.setEnabled(false);
            statusView.setText("未发现已配对设备");
        } else {
            deviceSpinner.setEnabled(true);
            connectButton.setEnabled(true);
            int ktIndex = findLikelyKtDevice();
            if (ktIndex >= 0) {
                deviceSpinner.setSelection(ktIndex);
                statusView.setText("已找到疑似 KT/OV 设备，可连接");
            } else {
                statusView.setText("请选择 KT6328/KT6368 SPP 设备");
            }
        }
        deviceAdapter.notifyDataSetChanged();
    }

    private int findLikelyKtDevice() {
        for (int i = 0; i < deviceLabels.size(); i++) {
            String label = deviceLabels.get(i).toLowerCase(Locale.ROOT);
            if (label.contains("kt63") || label.contains("ov_watch") || label.contains("ov watch")) {
                return i;
            }
        }
        return -1;
    }

    private String deviceLabel(BluetoothDevice device) {
        String name;
        String address;
        try {
            name = device.getName();
            address = device.getAddress();
        } catch (SecurityException e) {
            name = null;
            address = "(无权限读取地址)";
        }
        if (name == null || name.trim().isEmpty()) {
            name = "(未命名)";
        }
        return name + "  " + address;
    }

    private void toggleConnection() {
        if (isConnected()) {
            disconnect("手动断开", true);
        } else {
            connectSelected();
        }
    }

    private void connectSelected() {
        if (!hasConnectPermission()) {
            requestBluetoothPermission();
            return;
        }
        if (devices.isEmpty()) {
            toast("请先配对 SPP 设备");
            return;
        }
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= devices.size()) {
            toast("请选择设备");
            return;
        }
        BluetoothDevice device = devices.get(pos);
        connectButton.setEnabled(false);
        statusView.setText("正在连接...");
        appendLog("=== 正在连接 " + deviceLabels.get(pos) + " ===", "info");

        new Thread(() -> {
            try {
                BluetoothSocket connectedSocket = openSppSocket(device);
                ui.post(() -> onConnected(device, connectedSocket));
            } catch (IOException | SecurityException e) {
                ui.post(() -> {
                    connectButton.setEnabled(true);
                    statusView.setText("连接失败");
                    appendLog("[连接失败] " + e.getMessage(), "err");
                });
            }
        }, "spp-connect").start();
    }

    private BluetoothSocket openSppSocket(BluetoothDevice device) throws IOException {
        try {
            bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }

        IOException firstError = null;
        BluetoothSocket secureSocket = null;
        try {
            secureSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            secureSocket.connect();
            return secureSocket;
        } catch (IOException e) {
            firstError = e;
            closeSocket(secureSocket);
        }

        BluetoothSocket insecureSocket = null;
        try {
            insecureSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            insecureSocket.connect();
            return insecureSocket;
        } catch (IOException e) {
            closeSocket(insecureSocket);
            if (firstError != null) {
                e.addSuppressed(firstError);
            }
            throw e;
        }
    }

    private void onConnected(BluetoothDevice device, BluetoothSocket connectedSocket) {
        try {
            socket = connectedSocket;
            input = socket.getInputStream();
            output = socket.getOutputStream();
        } catch (IOException e) {
            closeSocket(connectedSocket);
            statusView.setText("打开流失败");
            connectButton.setEnabled(true);
            appendLog("[打开输入输出流失败] " + e.getMessage(), "err");
            return;
        }

        connectButton.setEnabled(true);
        connectButton.setText("断开");
        statusView.setText("已连接 " + deviceLabel(device));
        appendLog("=== 已连接 " + deviceLabel(device) + " ===", "info");
        startReader();
        ui.postDelayed(() -> sendCommand("OV"), 300);
    }

    private void startReader() {
        readerRunning = true;
        readerThread = new Thread(() -> {
            ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);
            byte[] buffer = new byte[256];
            while (readerRunning && input != null) {
                int read;
                try {
                    read = input.read(buffer);
                } catch (IOException e) {
                    if (readerRunning) {
                        ui.post(() -> handleRemoteDisconnect("[蓝牙读取异常 - 已断开] " + e.getMessage()));
                    }
                    break;
                }
                if (read < 0) {
                    ui.post(() -> handleRemoteDisconnect("[蓝牙连接已断开]"));
                    break;
                }
                for (int i = 0; i < read; i++) {
                    byte b = buffer[i];
                    if (b == '\n') {
                        String line = new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8)
                                .replace("\r", "")
                                .trim();
                        lineBuffer.reset();
                        if (!line.isEmpty()) {
                            ui.post(() -> handleRxLine(line));
                        }
                    } else {
                        lineBuffer.write(b);
                        if (lineBuffer.size() > 4096) {
                            lineBuffer.reset();
                        }
                    }
                }
            }
        }, "spp-reader");
        readerThread.start();
    }

    private void handleRemoteDisconnect(String message) {
        appendLog(message, "err");
        disconnect("已断开", true);
    }

    private boolean isConnected() {
        return socket != null && output != null;
    }

    private void disconnect(String reason, boolean autoSave) {
        readerRunning = false;
        closeQuietly(input);
        closeQuietly(output);
        closeSocket(socket);
        input = null;
        output = null;
        socket = null;
        connectButton.setText("连接");
        connectButton.setEnabled(true);
        statusView.setText("未连接");
        if (reason != null) {
            appendLog("=== " + reason + " ===", "info");
        }
        if (autoSave) {
            autoSaveLogs();
        }
    }

    private void sendCommand(String rawCommand) {
        String cmd = rawCommand.trim();
        if (cmd.isEmpty()) {
            return;
        }
        if (!isConnected()) {
            appendLog(">> " + cmd + "   [未连接，未发送]", "err");
            return;
        }
        OutputStream currentOutput = output;
        if (currentOutput == null) {
            appendLog(">> " + cmd + "   [未连接，未发送]", "err");
            return;
        }

        txExecutor.execute(() -> {
            try {
                currentOutput.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
                currentOutput.flush();
                ui.post(() -> {
                    txHistory.add(cmd);
                    appendLog(">> " + cmd, "tx");
                });
            } catch (IOException e) {
                ui.post(() -> appendLog("[发送失败] " + e.getMessage(), "err"));
            }
        });
    }

    private void sendSequence(String[] commands) {
        if (!isConnected()) {
            appendLog("[未连接，无法执行场景]", "err");
            return;
        }
        for (int i = 0; i < commands.length; i++) {
            String cmd = commands[i];
            ui.postDelayed(() -> sendCommand(cmd), i * INTER_CMD_MS);
        }
    }

    private void sendCfg() {
        int pos = cfgSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= CFG_KEYS.length) {
            return;
        }
        String value = cfgValueInput.getText().toString().trim();
        if (!isDigits(value)) {
            toast("请输入数字参数值");
            return;
        }
        sendCommand("OV+CFG=" + CFG_KEYS[pos][0] + "=" + value);
    }

    private void handleRxLine(String line) {
        rxLines.add(line);
        String kind = "rx";
        if (line.startsWith("$HR,")) {
            kind = "hr";
            updateLiveHr(line);
        } else if (line.startsWith("$ST,")) {
            kind = "st";
            updateLiveStep(line);
        } else if (line.startsWith("$EV,")) {
            kind = "ev";
        } else if (line.startsWith("$REF,")) {
            kind = "ref";
        } else if (startsWithAny(line, "DBG=", "MARK=", "REF=", "RST=", "CFG=")) {
            kind = "info";
            if (line.startsWith("DBG=")) {
                dbgView.setText(line.substring(4));
            }
        }
        appendLog(line, kind);
    }

    private void updateLiveHr(String line) {
        String[] parts = line.split(",");
        if (parts.length >= 11) {
            motionView.setText(parts[5]);
            bpmView.setText(parts[9]);
            readyView.setText("1".equals(parts[10]) ? "YES" : "NO");
        }
    }

    private void updateLiveStep(String line) {
        String[] parts = line.split(",");
        if (parts.length >= 14) {
            stepsView.setText(parts[11]);
            cadenceView.setText(parts[12]);
            walkingView.setText("1".equals(parts[13]) ? "YES" : "NO");
        }
    }

    private void appendLog(String line, String kind) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(() -> appendLog(line, kind));
            return;
        }
        String text = "[" + clockFormat.format(new Date()) + "] " + line + "\n";
        LogEntry entry = new LogEntry(text, kind);
        visibleLog.addLast(entry);
        if (visibleLog.size() > MAX_VISIBLE_LOG_LINES) {
            while (visibleLog.size() > MAX_VISIBLE_LOG_LINES) {
                visibleLog.removeFirst();
            }
            rebuildLogText();
        } else {
            appendLogEntry(entry);
        }
        logView.setText(logText);
    }

    private void appendLogEntry(LogEntry entry) {
        int start = logText.length();
        logText.append(entry.text);
        logText.setSpan(new ForegroundColorSpan(colorForKind(entry.kind)),
                start,
                logText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void rebuildLogText() {
        logText.clear();
        for (LogEntry entry : visibleLog) {
            appendLogEntry(entry);
        }
    }

    private int colorForKind(String kind) {
        if ("tx".equals(kind)) {
            return BLUE;
        }
        if ("hr".equals(kind)) {
            return YELLOW;
        }
        if ("st".equals(kind)) {
            return GREEN;
        }
        if ("ev".equals(kind)) {
            return PINK;
        }
        if ("ref".equals(kind)) {
            return Color.rgb(255, 150, 110);
        }
        if ("err".equals(kind)) {
            return RED;
        }
        if ("info".equals(kind)) {
            return Color.rgb(150, 175, 255);
        }
        return TEXT;
    }

    private void clearLog() {
        visibleLog.clear();
        logText.clear();
        logView.setText("");
        rxLines.clear();
        txHistory.clear();
    }

    private void chooseSave(int requestCode) {
        ArrayList<String> lines = requestCode == REQ_SAVE_RX ? rxLines : txHistory;
        if (lines.isEmpty()) {
            toast(requestCode == REQ_SAVE_RX ? "暂无 RX 日志" : "暂无 TX 记录");
            return;
        }
        String suffix = requestCode == REQ_SAVE_RX ? ".txt" : ".tx.txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "ovwatch_" + fileFormat.format(new Date()) + suffix);
        startActivityForResult(intent, requestCode);
    }

    private void writeLines(Uri uri, ArrayList<String> lines) {
        ArrayList<String> snapshot = new ArrayList<>(lines);
        try (OutputStream stream = getContentResolver().openOutputStream(uri);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
            for (String line : snapshot) {
                writer.write(line);
                writer.newLine();
            }
            toast("已保存");
        } catch (IOException | NullPointerException e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    private void autoSaveLogs() {
        if (rxLines.isEmpty() && txHistory.isEmpty()) {
            return;
        }
        File baseDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (baseDir == null) {
            return;
        }
        File logDir = new File(baseDir, "logs");
        if (!logDir.exists() && !logDir.mkdirs()) {
            return;
        }
        String stamp = fileFormat.format(new Date());
        try {
            if (!rxLines.isEmpty()) {
                writeLinesToFile(new File(logDir, "ovwatch_" + stamp + ".txt"), rxLines);
            }
            if (!txHistory.isEmpty()) {
                writeLinesToFile(new File(logDir, "ovwatch_" + stamp + ".tx.txt"), txHistory);
            }
            appendLog("[已自动备份到] " + logDir.getAbsolutePath(), "info");
        } catch (IOException e) {
            appendLog("[自动备份失败] " + e.getMessage(), "err");
        }
    }

    private void writeLinesToFile(File file, ArrayList<String> lines) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    private void openBluetoothSettings() {
        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private void showPairingHelp() {
        new AlertDialog.Builder(this)
                .setTitle("SPP 连接提示")
                .setMessage("1. 手机上先进入系统蓝牙设置，配对 KT6328/KT6368 的经典蓝牙/SPP 设备。\n\n"
                        + "2. 如果列表里同时出现 SPP 和 BLE，请选 SPP/经典蓝牙。这个 App 用的是串口透传，不是 BLE GATT。\n\n"
                        + "3. 回到 App 点“刷新已配对”，选择 KT 设备后连接。\n\n"
                        + "4. 连接成功后会自动发送 OV；日志收到 OK 后再发 HR/ST/测试场景命令。\n\n"
                        + "5. 同一时间只能有一个客户端连接，电脑串口台或蓝牙助手占用时，手机端会连不上。")
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureBluetoothReady();
            } else {
                statusView.setText("缺少蓝牙连接权限");
                toast("请授予蓝牙权限");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            ensureBluetoothReady();
            return;
        }
        if ((requestCode == REQ_SAVE_RX || requestCode == REQ_SAVE_TX)
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            writeLines(data.getData(), requestCode == REQ_SAVE_RX ? rxLines : txHistory);
        }
    }

    @Override
    protected void onDestroy() {
        disconnect(null, isConnected());
        txExecutor.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout section(LinearLayout root, String title) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setBackground(roundRect(PANEL, dp(8)));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(TEXT);
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(titleView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(12), 0, 0);
        root.addView(panel, params);
        return panel;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        return row;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(TEXT);
        button.setMinHeight(dp(44));
        button.setPadding(dp(6), 0, dp(6), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(48, 56, 68)));
        }
        button.setOnClickListener(listener);
        return button;
    }

    private EditText input(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(120, 130, 145));
        input.setTextSize(15);
        input.setInputType(inputType);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setMinHeight(dp(44));
        input.setBackground(roundRect(Color.rgb(18, 21, 27), dp(8)));
        return input;
    }

    private void addInputSend(LinearLayout panel, String label, EditText input, Runnable onSend) {
        TextView labelView = smallText(label);
        panel.addView(labelView);
        LinearLayout row = row();
        row.addView(input, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        row.addView(button("发送", v -> onSend.run()), fixedButtonParams(dp(78)));
        panel.addView(row);
    }

    private TextView smallText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(13);
        view.setPadding(0, dp(8), 0, dp(2));
        return view;
    }

    private LinearLayout.LayoutParams weightButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private LinearLayout.LayoutParams fixedButtonParams(int width) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(8), 0, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isDigits(String value) {
        return value != null && value.matches("\\d+");
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeSocket(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private final class DarkArrayAdapter extends ArrayAdapter<String> {
        DarkArrayAdapter(Context context, ArrayList<String> items) {
            super(context, android.R.layout.simple_spinner_item, items);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            styleSpinnerText(view, false);
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getDropDownView(position, convertView, parent);
            styleSpinnerText(view, true);
            return view;
        }

        private void styleSpinnerText(TextView view, boolean dropdown) {
            view.setTextColor(dropdown ? Color.rgb(25, 28, 35) : TEXT);
            view.setTextSize(14);
            view.setSingleLine(false);
            view.setPadding(dp(8), dp(8), dp(8), dp(8));
        }
    }

    private static final class Scenario {
        final String label;
        final String[] commands;

        Scenario(String label, String... commands) {
            this.label = label;
            this.commands = commands;
        }
    }

    private static final class LogEntry {
        final String text;
        final String kind;

        LogEntry(String text, String kind) {
            this.text = text;
            this.kind = kind;
        }
    }
}
