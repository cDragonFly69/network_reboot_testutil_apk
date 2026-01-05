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

public class MainActivity extends Activity {

    private TextView tvTotal, tvSuccess, tvFail, tvStatus, tvRebootProgress;
    private Button btnStartTest, btnStopTest, btnResetStats, btnRefreshStats;
    private EditText etRebootCount;
    private SharedPreferences prefs;

    public static final String PREFS_NAME = "RebootTesterPrefs";
    public static final String KEY_TOTAL = "total_count";
    public static final String KEY_SUCCESS = "success_count";
    public static final String KEY_FAIL = "fail_count";
    public static final String KEY_IS_TEST_RUNNING = "is_test_running";
    public static final String KEY_TARGET_REBOOT_COUNT = "target_reboot_count";
    public static final String KEY_CURRENT_REBOOT_COUNT = "current_reboot_count";
    public static final String KEY_APP_START_TIME = "app_start_time"; // 新增：APP启动时间戳key
    public static final long NETWORK_TIMEOUT_MS = 180000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        /* 1. 记录 APP 启动时间戳（毫秒） */
        long appStartTime = System.currentTimeMillis();
        // 2. 声明并赋值 storageContext（仅声明一次，兼容所有Android版本）
        Context storageContext;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            storageContext = this.createDeviceProtectedStorageContext();
        } else {
            storageContext = this;
        }

        // 存储APP启动时间戳
        SharedPreferences tempPrefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = tempPrefs.edit();
        editor.putLong(KEY_APP_START_TIME, appStartTime);
        editor.apply(); // 异步提交，不阻塞UI

        /* 2. 通过 Intent 把值带给 BootCompletedReceiver（可选） */
        Intent i = new Intent(this, BootCompletedReceiver.class);
        i.setAction("com.hzmct.reboot.APP_START");   // 自定义 action
        i.putExtra("app_start_time", appStartTime);
        sendBroadcast(i);

        initializeUI();

        // 直接复用上面已赋值的 storageContext，无需重新声明
        prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        checkAndGrantPermissions();
        setupClickListeners();
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
    }

    private void setupClickListeners() {
        btnStartTest.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限！", Toast.LENGTH_LONG).show();
                return;
            }

            String countStr = etRebootCount.getText().toString();
            int targetCount = 0;
            if (!countStr.isEmpty()) {
                try {
                    targetCount = Integer.parseInt(countStr);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_TARGET_REBOOT_COUNT, targetCount);
            editor.putInt(KEY_CURRENT_REBOOT_COUNT, 0);
            editor.putBoolean(KEY_IS_TEST_RUNNING, true);
            editor.apply();

            updateUI();
            Toast.makeText(this, "循环软重启已开始，即将第一次重启...", Toast.LENGTH_LONG).show();
            new Handler().postDelayed(() -> executeRootCommand("reboot"), 2000);
        });

        btnStopTest.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_IS_TEST_RUNNING, false).apply();
            updateUI();
            Toast.makeText(this, "循环已停止，本次重启将被取消。", Toast.LENGTH_LONG).show();
        });

        btnResetStats.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_IS_TEST_RUNNING, false).apply();
            resetStats();
            updateUI();
        });

        btnRefreshStats.setOnClickListener(v -> {
            updateUI();
            Toast.makeText(this, "数据已刷新", Toast.LENGTH_SHORT).show();
        });
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

        tvTotal.setText("网络检查总次数: " + total);
        tvSuccess.setText("网络正常次数: " + success);
        tvFail.setText("网络异常次数: " + fail);

        boolean isTestRunning = prefs.getBoolean(KEY_IS_TEST_RUNNING, false);
        if (isTestRunning) {
            tvStatus.setText("循环软重启进行中...");
            tvStatus.setTextColor(Color.parseColor("#4CAF50")); // Green
            btnStartTest.setEnabled(false);
            btnStopTest.setEnabled(true);
            etRebootCount.setEnabled(false);

            if (targetReboot > 0) {
                tvRebootProgress.setText("重启进度: " + currentReboot + " / " + targetReboot);
                tvRebootProgress.setVisibility(View.VISIBLE);
            } else {
                tvRebootProgress.setVisibility(View.GONE);
            }

        } else {
            tvStatus.setText("测试已停止 (可进行硬重启)");
            tvStatus.setTextColor(Color.parseColor("#FF9800")); // Orange
            btnStartTest.setEnabled(true);
            btnStopTest.setEnabled(false);
            etRebootCount.setEnabled(true);
            tvRebootProgress.setVisibility(View.GONE);
        }
    }

    private void resetStats() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_TOTAL, 0);
        editor.putInt(KEY_SUCCESS, 0);
        editor.putInt(KEY_FAIL, 0);
        editor.putInt(KEY_CURRENT_REBOOT_COUNT, 0);
        editor.putInt(KEY_TARGET_REBOOT_COUNT, 0);
        editor.apply();
        etRebootCount.setText("");
    }

    private void checkAndGrantPermissions() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限未授予，正在尝试用Root权限自动授予...", Toast.LENGTH_LONG).show();
            new Thread(() -> {
                executeRootCommand("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                runOnUiThread(() -> new Handler().postDelayed(() -> {
                    if (Settings.canDrawOverlays(MainActivity.this)) {
                        Toast.makeText(MainActivity.this, "悬浮窗权限授予成功！", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "悬浮窗权限自动授予失败。", Toast.LENGTH_LONG).show();
                    }
                }, 1000));
            }).start();
        }

        // 检查存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "存储权限未授予，正在尝试用Root权限自动授予...", Toast.LENGTH_LONG).show();
            new Thread(() -> {
                executeRootCommand("pm grant " + getPackageName() + " android.permission.WRITE_EXTERNAL_STORAGE");
                executeRootCommand("pm grant " + getPackageName() + " android.permission.READ_EXTERNAL_STORAGE");
                runOnUiThread(() -> new Handler().postDelayed(() -> {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(MainActivity.this, "存储权限授予成功！", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "存储权限自动授予失败。", Toast.LENGTH_LONG).show();
                    }
                }, 1000));
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
            Log.e("RebootTester", "Root command failed", e);
        }
    }
}
