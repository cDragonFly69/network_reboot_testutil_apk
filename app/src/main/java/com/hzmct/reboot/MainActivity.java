package com.hzmct.reboot;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView tvTotal, tvSuccess, tvFail, tvStatus, tvRebootProgress;
    private Button btnStartTest, btnStopTest, btnResetStats, btnRefreshStats, btnViewLog;
    private EditText etRebootCount;
    private SharedPreferences prefs;

    public static final String PREFS_NAME = "RebootTesterPrefs";
    public static final String KEY_TOTAL = "total_count";
    public static final String KEY_SUCCESS = "success_count";
    public static final String KEY_FAIL = "fail_count";
    public static final String KEY_IS_TEST_RUNNING = "is_test_running";
    public static final String KEY_TARGET_REBOOT_COUNT = "target_reboot_count";
    public static final String KEY_CURRENT_REBOOT_COUNT = "current_reboot_count";
    public static final long NETWORK_TIMEOUT_MS = 180000;
    private static final String APP_LOG_TAG = "RebootTester";
    private static final String KEY_APK_START_TIME = "apk_start_time";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化SharedPreferences（初始值默认是0，无需额外设置）
        Context storageContext = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                createDeviceProtectedStorageContext() : this;
        prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);


        initializeUI();
        checkAndGrantPermissions();
        setupClickListeners();
        updateUI(); // 初始化UI，显示初始统计数（0,0,0）
        // 关键修改：APK启动时立即记录时间并开始网络检测
        prefs.edit().putLong(KEY_APK_START_TIME, System.currentTimeMillis()).apply();
        startNetworkDetection(); // 移除之前的修复，重新启用自动检测

    }

    // ==============================================
    // 核心：网络检测+计数逻辑（仅手动启动后执行）
    // ==============================================
    private void startNetworkDetection() {
        new Thread(() -> {
            long apkStartTime = prefs.getLong(KEY_APK_START_TIME, System.currentTimeMillis());
            Log.d(APP_LOG_TAG, "网络检测使用的启动时间：" + apkStartTime);

            Log.d(APP_LOG_TAG, "开始网络检测（最大等待3分钟）");
            boolean networkReady = waitForNetwork();
            long networkConnectTime = System.currentTimeMillis();
            long realDelayMs = networkConnectTime - apkStartTime;
            long realDelaySec = realDelayMs / 1000;
            long realDelayMsRemain = realDelayMs % 1000;

            String apkStartTimeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(apkStartTime));
            String networkTimeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(networkConnectTime));

            if (networkReady) {
                String[] logs = {
                        "=== APK启动 → 网络连通 真实耗时 ===",
                        "APK启动时间：" + apkStartTimeStr + "（时间戳：" + apkStartTime + "）",
                        "网络连通时间：" + networkTimeStr + "（时间戳：" + networkConnectTime + "）",
                        "真实耗时：" + realDelaySec + " 秒 " + realDelayMsRemain + " 毫秒（总计 " + realDelayMs + " 毫秒）",
                        "当前活跃网络IP：" + getActiveNetworkIp()
                };
                for (String log : logs) {
                    Log.d(APP_LOG_TAG, log);
                    logToFile(log);
                }
                updateStatsAndProgress(true);
            } else {
                String[] logs = {
                        "=== APK启动 → 网络超时 真实耗时 ===",
                        "APK启动时间：" + apkStartTimeStr + "（时间戳：" + apkStartTime + "）",
                        "网络超时时间：" + networkTimeStr + "（时间戳：" + networkConnectTime + "）",
                        "真实耗时：" + realDelaySec + " 秒 " + realDelayMsRemain + " 毫秒（总计 " + realDelayMs + " 毫秒，已达3分钟阈值）"
                };
                for (String log : logs) {
                    Log.e(APP_LOG_TAG, log);
                    logToFile(log);
                }
                updateStatsAndProgress(false);
            }
        }).start();
    }

    // ==============================================
    // 统计更新+进度管理（逻辑不变）
    // ==============================================
    private void updateStatsAndProgress(boolean isSuccess) {
        runOnUiThread(() -> {
            SharedPreferences.Editor editor = prefs.edit();
            int total = prefs.getInt(KEY_TOTAL, 0) + 1;
            editor.putInt(KEY_TOTAL, total);

            int currentReboot = prefs.getInt(KEY_CURRENT_REBOOT_COUNT, 0);
            int targetReboot = prefs.getInt(KEY_TARGET_REBOOT_COUNT, 0);
            boolean isTestRunning = prefs.getBoolean(KEY_IS_TEST_RUNNING, false);

            if (isSuccess) {
                editor.putInt(KEY_SUCCESS, prefs.getInt(KEY_SUCCESS, 0) + 1);
                currentReboot++;
                editor.putInt(KEY_CURRENT_REBOOT_COUNT, currentReboot);
                Log.d(APP_LOG_TAG, "重启进度更新：" + currentReboot + "/" + targetReboot);
            } else {
                editor.putInt(KEY_FAIL, prefs.getInt(KEY_FAIL, 0) + 1);
            }
            editor.apply();

            updateUI();

            // 达标自动停止
            if (isTestRunning && targetReboot > 0 && currentReboot >= targetReboot) {
                prefs.edit().putBoolean(KEY_IS_TEST_RUNNING, false).apply();
                String stopLog = "已达到目标重启次数（" + targetReboot + "次），自动停止测试！";
                Log.d(APP_LOG_TAG, stopLog);
                logToFile(stopLog);
                Toast.makeText(MainActivity.this, stopLog, Toast.LENGTH_LONG).show();
                updateUI();
                return;
            }

            // 未达标继续重启
            if (isTestRunning) {
                Log.d(APP_LOG_TAG, "测试运行中，" + (isSuccess ? "10秒后触发下一次重启" : "网络失败，10秒后重试重启"));
                logToFile("测试运行中，" + (isSuccess ? "10秒后触发下一次重启" : "网络失败，10秒后重试重启"));
                new Handler().postDelayed(this::triggerReboot, 10000);
            }
        });
    }

    // ==============================================
    // 点击事件修复：仅点击「开始测试」后才触发网络检测
    // ==============================================
    private void setupClickListeners() {
        btnStartTest.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限！", Toast.LENGTH_LONG).show();
                return;
            }

            String countStr = etRebootCount.getText().toString();
            int targetCount = 0;
            try {
                targetCount = Integer.parseInt(countStr);
                if (targetCount <= 0) {
                    Toast.makeText(this, "目标次数请输入大于0的数字", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效次数", Toast.LENGTH_SHORT).show();
                return;
            }

            // 关键修复2：初始化统计数为0（避免残留旧数据）
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_TARGET_REBOOT_COUNT, targetCount);
            editor.putInt(KEY_CURRENT_REBOOT_COUNT, 0);
            editor.putInt(KEY_TOTAL, 0); // 初始总次数=0
            editor.putInt(KEY_SUCCESS, 0); // 初始正常次数=0
            editor.putInt(KEY_FAIL, 0); // 初始失败次数=0
            editor.putBoolean(KEY_IS_TEST_RUNNING, true);
            editor.putLong(KEY_APK_START_TIME, System.currentTimeMillis());
            editor.apply();

            updateUI(); // 刷新UI，显示初始0值
            Toast.makeText(this, "循环重启已开始（目标" + targetCount + "次），即将第一次重启...", Toast.LENGTH_LONG).show();

            // 关键修复3：仅此处触发网络检测（第一次重启前先执行一次检测）
            new Handler().postDelayed(() -> {
                startNetworkDetection();
            }, 2000);
        });

        btnStopTest.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_IS_TEST_RUNNING, false).apply();
            updateUI();
            Toast.makeText(this, "循环已停止", Toast.LENGTH_LONG).show();
        });

        btnResetStats.setOnClickListener(v -> {
            // 重置为初始0值
            prefs.edit().clear().apply();
            etRebootCount.setText("");
            updateUI();
        });

        btnRefreshStats.setOnClickListener(v -> {
            updateUI();
            Toast.makeText(this, "数据已刷新", Toast.LENGTH_SHORT).show();
        });

        btnViewLog.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, LogViewActivity.class));
        });
    }

    // ==============================================
    // 其他原有方法（保持不变）
    // ==============================================
    private boolean waitForNetwork() {
        long startTime = System.currentTimeMillis();
        long timeout = NETWORK_TIMEOUT_MS;
        while (System.currentTimeMillis() - startTime < timeout) {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork != null && activeNetwork.isConnectedOrConnecting()) {
                    if (isHostReachable()) return true;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    private boolean isHostReachable() {
        String PING_HOST = "aliyun.com";
        try {
            return InetAddress.getByName(PING_HOST).isReachable(1000);
        } catch (IOException e) {
            return false;
        }
    }

    private String getActiveNetworkIp() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "ConnectivityManager为空";
        android.net.NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
        if (activeNetworkInfo == null || !activeNetworkInfo.isConnected()) return "无活跃网络";

        try {
            StringBuilder ipSb = new StringBuilder();
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress ia = inetAddresses.nextElement();
                    if (!ia.isLoopbackAddress()) {
                        String ip = ia.getHostAddress();
                        if (ia instanceof java.net.Inet4Address) {
                            ipSb.append("IPv4: ").append(ip).append("; ");
                        } else {
                            ipSb.append("IPv6: ").append(ip).append("; ");
                        }
                    }
                }
            }
            return ipSb.length() > 0 ? ipSb.delete(ipSb.length() - 2, ipSb.length()).toString() : "无有效IP";
        } catch (SocketException e) {
            return "IP获取异常：" + e.getMessage();
        }
    }

    private void logToFile(String message) {
        java.io.File logFile = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "reboot_test_log.txt");
        try {
            if (!logFile.exists()) logFile.createNewFile();
            java.io.BufferedWriter buf = new java.io.BufferedWriter(new java.io.FileWriter(logFile, true));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            buf.append(timestamp).append(" - ").append(message);
            buf.newLine();
            buf.flush();
            buf.close();
        } catch (IOException e) {
            Log.e(APP_LOG_TAG, "日志写入失败：" + e.getMessage(), e);
        }
    }

    private void triggerReboot() {
        prefs.edit().remove(KEY_APK_START_TIME).apply();
        executeRootCommand("reboot");
    }

    private void initializeUI() {
        tvTotal = findViewById(R.id.tv_total_count);
        tvSuccess = findViewById(R.id.tv_success_count);
        tvFail = findViewById(R.id.tv_fail_count);
        tvStatus = findViewById(R.id.tv_status);
        tvRebootProgress = findViewById(R.id.tv_reboot_progress);
        etRebootCount = findViewById(R.id.et_reboot_count);
        btnStartTest = findViewById(R.id.btn_start_test);
        btnStopTest = findViewById(R.id.btn_stop_test);
        btnResetStats = findViewById(R.id.btn_reset_stats);
        btnRefreshStats = findViewById(R.id.btn_refresh_stats);
        btnViewLog = findViewById(R.id.btn_cat_logs);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI(); // 页面恢复时刷新，确保统计数正确
    }

    private void updateUI() {
        int total = prefs.getInt(KEY_TOTAL, 0);
        int success = prefs.getInt(KEY_SUCCESS, 0);
        int fail = prefs.getInt(KEY_FAIL, 0);
        int currentReboot = prefs.getInt(KEY_CURRENT_REBOOT_COUNT, 0);
        int targetReboot = prefs.getInt(KEY_TARGET_REBOOT_COUNT, 0);
        boolean isTestRunning = prefs.getBoolean(KEY_IS_TEST_RUNNING, false);

        // 初始状态显示0
        tvTotal.setText("网络检查总次数: " + total);
        tvSuccess.setText("网络正常次数: " + success);
        tvFail.setText("网络异常次数: " + fail);

        if (isTestRunning && targetReboot > 0) {
            tvRebootProgress.setText("重启进度: " + currentReboot + " / " + targetReboot);
            tvRebootProgress.setVisibility(View.VISIBLE);
            tvStatus.setText("循环软重启进行中...");
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            btnStartTest.setEnabled(false);
            btnStopTest.setEnabled(true);
            etRebootCount.setEnabled(false);
        } else {
            tvRebootProgress.setVisibility(View.GONE);
            tvStatus.setText("测试已停止 (可进行硬重启)");
            tvStatus.setTextColor(Color.parseColor("#FF9800"));
            btnStartTest.setEnabled(true);
            btnStopTest.setEnabled(false);
            etRebootCount.setEnabled(true);
        }
    }

    private void checkAndGrantPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "正在自动授予悬浮窗权限...", Toast.LENGTH_LONG).show();
            new Thread(() -> {
                executeRootCommand("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                runOnUiThread(() -> Toast.makeText(this, "悬浮窗权限授予完成", Toast.LENGTH_SHORT).show());
            }).start();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "正在自动授予存储权限...", Toast.LENGTH_LONG).show();
            new Thread(() -> {
                executeRootCommand("pm grant " + getPackageName() + " android.permission.WRITE_EXTERNAL_STORAGE");
                executeRootCommand("pm grant " + getPackageName() + " android.permission.READ_EXTERNAL_STORAGE");
                runOnUiThread(() -> Toast.makeText(this, "存储权限授予完成", Toast.LENGTH_SHORT).show());
            }).start();
        }
    }

    private void executeRootCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
            os.close();
            process.destroy();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            Log.e(APP_LOG_TAG, "Root命令执行失败：" + e.getMessage(), e);
        }
    }
}