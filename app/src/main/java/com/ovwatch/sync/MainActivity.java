package com.ovwatch.sync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String PREFS = "ov_watch_sync";
    private static final String HISTORY = "history";

    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private final ArrayList<String> deviceNames = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean readerRunning;
    private boolean autoSync;
    private int highHrCount;
    private int lowHrCount;
    private int lowSpo2Count;
    private long lastAlertAt;

    private Spinner deviceSpinner;
    private ArrayAdapter<String> deviceAdapter;
    private TextView statusText;
    private TextView alertText;
    private TextView timeText;
    private TextView hrText;
    private TextView spo2Text;
    private TextView stepText;
    private TextView tempText;
    private TextView humiText;
    private TextView batText;
    private TextView fallText;
    private TextView historyText;
    private Button autoButton;

    private final Runnable autoSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (autoSync) {
                syncOnce();
                handler.postDelayed(this, 10000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        requestBluetoothPermission();
        loadBondedDevices();
        loadHistory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoSync = false;
        closeSocket();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadBondedDevices();
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("OV Watch Sync");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(25, 38, 54));
        root.addView(title);

        statusText = label("未连接，请先在系统蓝牙中完成配对");
        statusText.setPadding(0, dp(8), 0, dp(12));
        root.addView(statusText);

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, deviceNames);
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);
        root.addView(deviceSpinner, matchWrap());

        LinearLayout controls = row();
        controls.addView(button("刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadBondedDevices();
            }
        }));
        controls.addView(button("连接", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectSelectedDevice();
            }
        }));
        controls.addView(button("同步", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncOnce();
            }
        }));
        root.addView(controls);

        autoButton = button("自动同步: 关", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoSync = !autoSync;
                autoButton.setText(autoSync ? "自动同步: 开" : "自动同步: 关");
                if (autoSync) {
                    handler.post(autoSyncRunnable);
                }
            }
        });
        root.addView(autoButton, matchWrap());

        alertText = card(root, "状态提醒", "暂无异常");
        timeText = card(root, "同步时间", "--");
        hrText = card(root, "心率", "-- bpm");
        spo2Text = card(root, "血氧", "-- %");
        stepText = card(root, "今日步数", "--");
        tempText = card(root, "温度", "-- C");
        humiText = card(root, "湿度", "-- %");
        batText = card(root, "电量", "-- %");
        fallText = card(root, "摔倒标志", "未触发");

        Button clearFall = button("清除摔倒标志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendCommand("OV+FALLCLR");
                fallText.setText("未触发");
                alertText.setText("已发送清除命令");
            }
        });
        root.addView(clearFall, matchWrap());

        TextView historyTitle = sectionTitle("最近记录");
        root.addView(historyTitle);
        historyText = label("");
        root.addView(historyText);

        setContentView(scrollView);
    }

    private TextView card(LinearLayout root, String title, String value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackgroundColor(Color.rgb(244, 247, 250));

        TextView titleView = label(title);
        titleView.setTextColor(Color.rgb(90, 105, 120));
        titleView.setTextSize(13);
        TextView valueView = label(value);
        valueView.setTextSize(22);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setTextColor(Color.rgb(20, 31, 43));

        box.addView(titleView);
        box.addView(valueView);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(8), 0, 0);
        root.addView(box, params);
        return valueView;
    }

    private TextView sectionTitle(String text) {
        TextView view = label(text);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(18), 0, dp(6));
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(Color.rgb(45, 56, 68));
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1001);
        }
    }

    private void loadBondedDevices() {
        if (bluetoothAdapter == null) {
            statusText.setText("当前手机不支持蓝牙");
            return;
        }
        if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            statusText.setText("蓝牙未开启，请先打开手机蓝牙");
            return;
        }

        devices.clear();
        deviceNames.clear();
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bonded) {
                devices.add(device);
                String name = device.getName();
                if (name == null || name.trim().isEmpty()) {
                    name = "Unknown";
                }
                deviceNames.add(name + "  " + device.getAddress());
            }
            if (deviceNames.isEmpty()) {
                deviceNames.add("没有已配对设备，请先到系统蓝牙配对");
            }
            deviceAdapter.notifyDataSetChanged();
        } catch (SecurityException ex) {
            statusText.setText("缺少蓝牙权限");
        }
    }

    private void connectSelectedDevice() {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
            return;
        }
        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) {
            toast("请先选择已配对的手环蓝牙");
            return;
        }

        final BluetoothDevice device = devices.get(index);
        statusText.setText("正在连接 " + deviceNames.get(index));
        new Thread(new Runnable() {
            @Override
            public void run() {
                closeSocket();
                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                    inputStream = socket.getInputStream();
                    outputStream = socket.getOutputStream();
                    readerRunning = true;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("已连接: " + device.getName());
                        }
                    });
                    startReader();
                    syncOnce();
                } catch (IOException | SecurityException ex) {
                    closeSocket();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("连接失败，请确认手环蓝牙已开启");
                        }
                    });
                }
            }
        }).start();
    }

    private void startReader() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[128];
                StringBuilder line = new StringBuilder();
                while (readerRunning && inputStream != null) {
                    try {
                        int count = inputStream.read(buffer);
                        for (int i = 0; i < count; i++) {
                            char ch = (char) buffer[i];
                            if (ch == '\n') {
                                final String message = line.toString().trim();
                                line.setLength(0);
                                if (!message.isEmpty()) {
                                    handleMessage(message);
                                }
                            } else if (ch != '\r') {
                                line.append(ch);
                            }
                        }
                    } catch (IOException ex) {
                        readerRunning = false;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                statusText.setText("连接已断开");
                            }
                        });
                    }
                }
            }
        }).start();
    }

    private void syncOnce() {
        sendCommand("OV+SEND");
    }

    private void sendCommand(final String command) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (outputStream == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast("蓝牙未连接");
                            }
                        });
                        return;
                    }
                    outputStream.write(command.getBytes());
                    outputStream.flush();
                } catch (IOException ex) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("发送失败");
                        }
                    });
                }
            }
        }).start();
    }

    private void handleMessage(final String message) {
        if (message.startsWith("OVDATA,")) {
            final WatchData data = WatchData.parse(message);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateData(data);
                }
            });
        } else if (message.startsWith("FALLCLR=OK")) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    fallText.setText("未触发");
                    alertText.setText("摔倒标志已清除");
                }
            });
        }
    }

    private void updateData(WatchData data) {
        timeText.setText(data.date + " " + data.time);
        hrText.setText(data.hr >= 0 ? data.hr + " bpm" : "-- bpm");
        spo2Text.setText(data.spo2 >= 0 ? data.spo2 + " %" : "-- %");
        stepText.setText(data.step >= 0 ? String.valueOf(data.step) : "--");
        tempText.setText(data.temp >= -100 ? data.temp + " C" : "-- C");
        humiText.setText(data.humi >= 0 ? data.humi + " %" : "-- %");
        batText.setText(data.bat >= 0 ? data.bat + " %" : "-- %");
        fallText.setText(data.fall == 1 ? "疑似摔倒" : "未触发");
        saveHistory(data);
        evaluateAlert(data);
    }

    private void evaluateAlert(WatchData data) {
        String alert = "暂无异常";
        boolean shouldAlert = false;

        if (data.fall == 1) {
            alert = "疑似摔倒，请立即确认";
            shouldAlert = true;
        }

        if (data.hr > 120) {
            highHrCount++;
        } else {
            highHrCount = 0;
        }
        if (data.hr > 0 && data.hr < 50) {
            lowHrCount++;
        } else {
            lowHrCount = 0;
        }
        if (data.spo2 > 0 && data.spo2 < 94) {
            lowSpo2Count++;
        } else {
            lowSpo2Count = 0;
        }

        if (highHrCount >= 3) {
            alert = "心率持续偏高";
            shouldAlert = true;
        } else if (lowHrCount >= 3) {
            alert = "心率持续偏低";
            shouldAlert = true;
        } else if (lowSpo2Count >= 2) {
            alert = "血氧偏低，请确认佩戴状态";
            shouldAlert = true;
        }

        alertText.setText(alert);
        if (shouldAlert) {
            showAlert(alert);
        }
    }

    private void showAlert(String alert) {
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < 10000) {
            return;
        }
        lastAlertAt = now;
        vibrate();
        new AlertDialog.Builder(this)
                .setTitle("异常提醒")
                .setMessage(alert)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(500);
        }
    }

    private void saveHistory(WatchData data) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String row = stamp + "  HR " + data.hr + "  SpO2 " + data.spo2 + "  Step " + data.step + "  Fall " + data.fall;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String old = prefs.getString(HISTORY, "");
        String[] rows = old.split("\n");
        StringBuilder builder = new StringBuilder(row);
        int keep = Math.min(rows.length, 19);
        for (int i = 0; i < keep; i++) {
            if (!rows[i].trim().isEmpty()) {
                builder.append('\n').append(rows[i]);
            }
        }
        prefs.edit().putString(HISTORY, builder.toString()).apply();
        historyText.setText(builder.toString());
    }

    private void loadHistory() {
        String history = getSharedPreferences(PREFS, MODE_PRIVATE).getString(HISTORY, "");
        historyText.setText(history);
    }

    private void closeSocket() {
        readerRunning = false;
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        inputStream = null;
        outputStream = null;
        socket = null;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static class WatchData {
        String date = "--";
        String time = "--";
        int hr = -1;
        int spo2 = -1;
        int temp = -100;
        int humi = -1;
        int step = -1;
        int bat = -1;
        int fall = 0;

        static WatchData parse(String line) {
            WatchData data = new WatchData();
            String[] parts = line.split(",");
            for (String part : parts) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = part.substring(0, eq);
                String value = part.substring(eq + 1);
                if ("DATE".equals(key)) {
                    data.date = value;
                } else if ("TIME".equals(key)) {
                    data.time = value;
                } else if ("HR".equals(key)) {
                    data.hr = parseInt(value, -1);
                } else if ("SPO2".equals(key)) {
                    data.spo2 = parseInt(value, -1);
                } else if ("TEMP".equals(key)) {
                    data.temp = parseInt(value, -100);
                } else if ("HUMI".equals(key)) {
                    data.humi = parseInt(value, -1);
                } else if ("STEP".equals(key)) {
                    data.step = parseInt(value, -1);
                } else if ("BAT".equals(key)) {
                    data.bat = parseInt(value, -1);
                } else if ("FALL".equals(key)) {
                    data.fall = parseInt(value, 0);
                }
            }
            return data;
        }

        static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                return fallback;
            }
        }
    }
}
