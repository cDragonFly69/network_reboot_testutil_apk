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
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.File;
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
    public static final String KEY_FIRST_LAUNCH = "first_launch"; // 首次启动标记

    // 日志文件路径（改为Download目录，所有设备都能访问）
    private static final String LOG_FILE_PATH = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/reboot_test_log.txt";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化SharedPreferences
        Context storageContext = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                createDeviceProtectedStorageContext() : this;
        prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initializeUI();
        // 优先校验存储权限（关键修复）
        checkStoragePermission();
        checkFloatWindowPermission();
        setupClickListeners();
        updateUI();

        // 首次启动标记
        boolean isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true);
        int targetCount = prefs.getInt(KEY_TARGET_REBOOT_COUNT, 0);
        if (!isFirstLaunch && targetCount > 0) {
            prefs.edit().putLong(KEY_APK_START_TIME, System.currentTimeMillis()).apply();
            startNetworkDetection();
        } else if (isFirstLaunch) {
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
        }

        // 启动时打印日志路径，便于调试
        Log.d(APP_LOG_TAG, "日志文件最终路径：" + LOG_FILE_PATH);
        // 提前尝试创建日志文件
        createLogFileIfNotExist();
    }

    // ==============================================
    // 核心修复1：强制创建日志文件（解决文件不存在问题）
    // ==============================================
    private void createLogFileIfNotExist() {
        new Thread(() -> {
            File logFile = new File(LOG_FILE_PATH);
            try {
                // 1. 创建父目录（如果不存在）
                File parentDir = logFile.getParentFile();
                if (!parentDir.exists()) {
                    boolean dirCreated = parentDir.mkdirs();
                    Log.d(APP_LOG_TAG, "日志父目录创建：" + (dirCreated ? "成功" : "失败") + "，路径：" + parentDir.getAbsolutePath());
                }

                // 2. 创建日志文件
                if (!logFile.exists()) {
                    boolean fileCreated = logFile.createNewFile();
                    if (fileCreated) {
                        Log.d(APP_LOG_TAG, "日志文件创建成功：" + logFile.getAbsolutePath());
                        // 写入测试日志
                        logToFile("APP启动，日志文件初始化成功");
                    } else {
                        Log.e(APP_LOG_TAG, "日志文件创建失败！路径：" + logFile.getAbsolutePath());
                        runOnUiThread(() -> Toast.makeText(this, "日志文件创建失败，请检查存储权限", Toast.LENGTH_LONG).show());
                    }
                } else {
                    Log.d(APP_LOG_TAG, "日志文件已存在：" + logFile.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(APP_LOG_TAG, "创建日志文件异常：" + e.getMessage(), e);
                runOnUiThread(() -> Toast.makeText(this, "日志初始化异常：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ==============================================
    // 核心修复2：稳定的日志写入方法（兼容所有设备）
    // ==============================================
    private void logToFile(String message) {
        // 1. 校验文件是否存在
        File logFile = new File(LOG_FILE_PATH);
        if (!logFile.exists()) {
            Log.e(APP_LOG_TAG, "日志文件不存在，先尝试创建");
            createLogFileIfNotExist();
            if (!logFile.exists()) {
                Log.e(APP_LOG_TAG, "创建失败，日志写入终止：" + message);
                return;
            }
        }

        // 2. 写入日志（子线程执行，避免阻塞UI）
        new Thread(() -> {
            try (java.io.BufferedWriter buf = new java.io.BufferedWriter(new java.io.FileWriter(logFile, true))) {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
                String logContent = timestamp + " - " + message;
                buf.write(logContent);
                buf.newLine();
                buf.flush(); // 强制刷盘，确保写入
                Log.d(APP_LOG_TAG, "日志写入成功：" + logContent);
            } catch (IOException e) {
                Log.e(APP_LOG_TAG, "日志写入失败：" + e.getMessage(), e);
                // 兜底：写入Logcat
                Log.e(APP_LOG_TAG, "兜底日志：" + message);
                runOnUiThread(() -> Toast.makeText(this, "日志写入失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ==============================================
    // 核心修复3：强化存储权限校验（Root+手动双保险）
    // ==============================================
    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 1. 先尝试Root自动授予
            new Thread(() -> {
                executeRootCommand("pm grant " + getPackageName() + " android.permission.WRITE_EXTERNAL_STORAGE");
                executeRootCommand("pm grant " + getPackageName() + " android.permission.READ_EXTERNAL_STORAGE");
                // 2. 校验是否授予成功
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                        Log.d(APP_LOG_TAG, "存储权限授予成功");
                        Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show();
                    } else {
                        Log.e(APP_LOG_TAG, "Root授予存储权限失败，请手动授予");
                        Toast.makeText(this, "存储权限授予失败，请手动开启", Toast.LENGTH_LONG).show();
                        // 3. 引导手动授予
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
                    }
                });
            }).start();
        } else {
            // 低版本无需动态权限，直接创建文件
            createLogFileIfNotExist();
        }
    }

    // 悬浮窗权限校验（拆分出来，避免逻辑混乱）
    private void checkFloatWindowPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "正在授予悬浮窗权限...", Toast.LENGTH_LONG).show();
            new Thread(() -> {
                executeRootCommand("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                runOnUiThread(() -> Toast.makeText(this, "悬浮窗权限授予完成", Toast.LENGTH_SHORT).show());
            }).start();
        }
    }

    // ==============================================
    // 其他核心逻辑（保持不变，仅补充日志调用）
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

    private void updateStatsAndProgress(boolean isSuccess) {
        runOnUiThread(() -> {
            SharedPreferences.Editor editor = prefs.edit();
            int total = prefs.getInt(KEY_TOTAL, 0) + 1;
            editor.putInt(KEY_TOTAL, total);

            int currentReboot = prefs.getInt(KEY_CURRENT_REBOOT_COUNT, 0);
            int targetReboot = prefs.getInt(KEY_TARGET_REBOOT_COUNT, 0);

            if (isSuccess) {
                editor.putInt(KEY_SUCCESS, prefs.getInt(KEY_SUCCESS, 0) + 1);
                currentReboot++;
                editor.putInt(KEY_CURRENT_REBOOT_COUNT, currentReboot);
            } else {
                editor.putInt(KEY_FAIL, prefs.getInt(KEY_FAIL, 0) + 1);
            }
            editor.apply();

            updateUI();

            if (targetReboot > 0 && currentReboot >= targetReboot) {
                prefs.edit().putBoolean(KEY_IS_TEST_RUNNING, false).apply();
                String stopLog = "已达到目标重启次数（" + targetReboot + "次），停止测试！";
                Log.d(APP_LOG_TAG, stopLog);
                logToFile(stopLog);
                Toast.makeText(this, stopLog, Toast.LENGTH_LONG).show();
                updateUI();
                return;
            }

            String rebootLog = "继续重启，10秒后执行...";
            Log.d(APP_LOG_TAG, rebootLog);
            logToFile(rebootLog);
            new Handler().postDelayed(this::triggerReboot, 10000);
        });
    }

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

            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_TARGET_REBOOT_COUNT, targetCount);
            editor.putInt(KEY_CURRENT_REBOOT_COUNT, 0);
            editor.putInt(KEY_TOTAL, 0);
            editor.putInt(KEY_SUCCESS, 0);
            editor.putInt(KEY_FAIL, 0);
            editor.putBoolean(KEY_IS_TEST_RUNNING, true);
            editor.putLong(KEY_APK_START_TIME, System.currentTimeMillis());
            editor.apply();

            prefs.edit()
                    .putInt(KEY_TARGET_REBOOT_COUNT, targetCount)
                    .putInt(KEY_CURRENT_REBOOT_COUNT, 0)
                    .putBoolean(KEY_IS_TEST_RUNNING, true)
                    .apply();

            updateUI();
            String startLog = "循环重启已开始（目标" + targetCount + "次），即将第一次重启...";
            Toast.makeText(this, startLog, Toast.LENGTH_LONG).show();
            Log.d(APP_LOG_TAG, startLog);
            logToFile(startLog);

            new Handler().postDelayed(() -> {
                prefs.edit().putLong(KEY_APK_START_TIME, System.currentTimeMillis()).apply();
                startNetworkDetection();
            }, 2000);
        });

        btnStopTest.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_IS_TEST_RUNNING, false).apply();
            String stopLog = "手动停止循环测试";
            Log.d(APP_LOG_TAG, stopLog);
            logToFile(stopLog);
            updateUI();
            Toast.makeText(this, "循环已停止", Toast.LENGTH_LONG).show();
        });

        btnResetStats.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            etRebootCount.setText("");
            String resetLog = "重置所有统计数据为0";
            Log.d(APP_LOG_TAG, resetLog);
            logToFile(resetLog);
            updateUI();
        });

        btnRefreshStats.setOnClickListener(v -> {
            updateUI();
            Toast.makeText(this, "数据已刷新", Toast.LENGTH_SHORT).show();
        });

        btnViewLog.setOnClickListener(v -> {
            // 传递日志文件路径给LogViewActivity
            Intent intent = new Intent(MainActivity.this, LogViewActivity.class);
            intent.putExtra("LOG_FILE_PATH", LOG_FILE_PATH);
            startActivity(intent);
        });
    }

    // ==============================================
    // 其他方法（保持不变）
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

    private void triggerReboot() {
        prefs.edit().remove(KEY_APK_START_TIME).apply();
        String rebootLog = "执行重启命令：reboot";
        Log.d(APP_LOG_TAG, rebootLog);
        logToFile(rebootLog);
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
        updateUI();
    }

    private void updateUI() {
        int total = prefs.getInt(KEY_TOTAL, 0);
        int success = prefs.getInt(KEY_SUCCESS, 0);
        int fail = prefs.getInt(KEY_FAIL, 0);
        int currentReboot = prefs.getInt(KEY_CURRENT_REBOOT_COUNT, 0);
        int targetReboot = prefs.getInt(KEY_TARGET_REBOOT_COUNT, 0);
        boolean isTestRunning = prefs.getBoolean(KEY_IS_TEST_RUNNING, false);

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

    // 权限请求结果回调
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(APP_LOG_TAG, "手动授予存储权限成功");
                createLogFileIfNotExist();
            } else {
                Log.e(APP_LOG_TAG, "手动授予存储权限失败");
                Toast.makeText(this, "存储权限授予失败，日志无法写入", Toast.LENGTH_LONG).show();
            }
        }
    }
}