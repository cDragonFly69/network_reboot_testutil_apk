package com.hzmct.reboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String PING_HOST = "aliyun.com";
    public static final String PREFS_NAME = "RebootTesterPrefs";
    public static final String KEY_TOTAL = "total_count";
    public static final String KEY_SUCCESS = "success_count";
    public static final String KEY_FAIL = "fail_count";
    public static final String KEY_IS_TEST_RUNNING = "is_test_running";
    public static final String KEY_TARGET_REBOOT_COUNT = "target_reboot_count";
    public static final String KEY_CURRENT_REBOOT_COUNT = "current_reboot_count";
    public static final String KEY_APP_START_TIME = "app_start_time";
    public static final long NETWORK_TIMEOUT_MS = 180000;


    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        long startTime = System.currentTimeMillis();

        // 记录开机时间
        String bootTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        logToFile("=== 设备开机时间: " + bootTime + " ===");

        logToFile("Broadcast received: " + intent.getAction());

        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }
        // 外部唯一声明prefs变量，线程内部直接复用
        SharedPreferences prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 外部唯一声明isLoopingTest变量，线程内部直接复用
        boolean isLoopingTest = prefs.getBoolean(KEY_IS_TEST_RUNNING, false);

        logToFile("Launching UI via root command...");
        executeRootCommand("am start -n " + context.getPackageName() + "/" + MainActivity.class.getName());

        new Thread(() -> {
            logToFile("Background thread started. Waiting for network (max 3 minutes)...");
            boolean networkReady = waitForNetwork(context);

            // 1. 读取APP启动时间，计算并打印时间间隔（无论成功/失败，均打印）
            // 删除线程内部重复的prefs声明，直接复用外部prefs
            long appStartTime = prefs.getLong(KEY_APP_START_TIME, 0);
            long currentTime = System.currentTimeMillis();
            if (appStartTime > 0) {
                long deltaMs = currentTime - appStartTime;
                long deltaSec = deltaMs / 1000;
                long deltaMsRemain = deltaMs % 1000;
                if (networkReady) {
                    logToFile("=== APP启动 ➜ 网络连通 时间间隔 ===");
                    logToFile("APP启动时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(appStartTime)));
                    logToFile("网络连通时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(currentTime)));
                    logToFile("间隔时长：" + deltaSec + " 秒 " + deltaMsRemain + " 毫秒（总计 " + deltaMs + " 毫秒）");
                } else {
                    logToFile("=== APP启动 ➜ 网络超时 时间间隔 ===");
                    logToFile("APP启动时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(appStartTime)));
                    logToFile("网络超时时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(currentTime)));
                    logToFile("间隔时长：" + deltaSec + " 秒 " + deltaMsRemain + " 毫秒（总计 " + deltaMs + " 毫秒，已达3分钟超时阈值）");
                }
            } else {
                logToFile("未获取到APP启动时间戳，无法计算时间间隔");
            }

            SharedPreferences.Editor editor = prefs.edit();
            int total = prefs.getInt(KEY_TOTAL, 0) + 1;
            editor.putInt(KEY_TOTAL, total);
            String rebootTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());

            if (networkReady) {
                // 网络连通：记录成功+打印IP
                String activeNetworkIp = getActiveNetworkIp(context);
                logToFile("当前活跃网络IP地址：" + activeNetworkIp);
                int success = prefs.getInt(KEY_SUCCESS, 0) + 1;
                editor.putInt(KEY_SUCCESS, success);
                logToFile("Cycle " + total + ": SUCCESS(Duration: " + (currentTime - startTime) + "ms)");
                logToFile("=== 联网成功时间: " + rebootTime + " ===");
                // 清除APP启动时间戳，避免重复计算
                editor.remove(KEY_APP_START_TIME);
            } else {
                // 网络超时：记录失败+自动重启（核心新增逻辑）
                int fail = prefs.getInt(KEY_FAIL, 0) + 1;
                editor.putInt(KEY_FAIL, fail);
                logToFile("Cycle " + total + ": FAILURE(Duration: " + (currentTime - startTime) + "ms)");
                logToFile("=== 联网失败时间: " + rebootTime + "（3分钟网络超时，即将自动重启） ===");
                // 清除APP启动时间戳
                editor.remove(KEY_APP_START_TIME);
                editor.apply(); // 先提交失败记录，避免重启后数据丢失

                // 网络超时后自动重启（无需等待10秒，直接触发）
                // 删除线程内部重复的isLoopingTest声明，直接复用外部isLoopingTest
                if (isLoopingTest) {
                    logToFile("网络3分钟超时，触发自动重启...");
                    executeRootCommand("reboot");
                } else {
                    // 若未开启循环测试，也强制重启（按需可选，注释则不重启）
                    logToFile("网络3分钟超时，强制触发自动重启...");
                    executeRootCommand("reboot");
                }
                return; // 超时重启后，无需执行后续原有循环逻辑
            }
            editor.apply();

            // 原有循环重启逻辑（仅网络成功后执行，不影响超时重启逻辑）
            if (isLoopingTest) {
                int currentCount = prefs.getInt(KEY_CURRENT_REBOOT_COUNT, 0) + 1;
                int targetCount = prefs.getInt(KEY_TARGET_REBOOT_COUNT, 0);
                prefs.edit().putInt(KEY_CURRENT_REBOOT_COUNT, currentCount).apply();

                if (targetCount > 0 && currentCount == targetCount) {
                    logToFile("Target reboot count ("+ targetCount +") reached. Stopping test.");
                    prefs.edit().putBoolean(KEY_IS_TEST_RUNNING, false).apply();
                    return;
                }

                try {
                    logToFile("Looping test active. Waiting 10s before next reboot...");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                boolean stillRunning = prefs.getBoolean(KEY_IS_TEST_RUNNING, false);
                if (stillRunning) {
                    logToFile("Triggering next soft reboot...");
                    executeRootCommand("reboot");
                } else {
                    logToFile("Looping test was stopped during delay. Reboot cancelled.");
                }
            } else {
                logToFile("Hard reboot test cycle finished. Waiting for next manual reboot.");
            }

        }).start();
    }

    // 修改后：不区分IPv4/IPv6，自适应获取所有活跃网络的有效IP地址（解决爆红版本）
    private String getActiveNetworkIp(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return "获取失败：ConnectivityManager 为空";
        }

        // 先判断是否有活跃网络连接
        NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
        if (activeNetworkInfo == null || !activeNetworkInfo.isConnected()) {
            return "无活跃网络连接";
        }

        try {
            StringBuilder ipSb = new StringBuilder(); // 用于拼接所有IP地址
            // 1. 获取所有网络接口（添加异常捕获，解决爆红）
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();

            // 2. 遍历网络接口（使用枚举遍历，兼容所有Android版本，避免遍历报错）
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                // 3. 遍历当前网络接口下的所有IP地址（同样使用枚举遍历）
                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress ia = inetAddresses.nextElement();
                    // 仅过滤回环地址（127.0.0.1 / ::1），保留所有公网/内网的IPv4和IPv6
                    if (!ia.isLoopbackAddress()) {
                        String ip = ia.getHostAddress();
                        // 区分标注IPv4/IPv6（可选，更直观）
                        if (ia instanceof java.net.Inet4Address) {
                            ipSb.append("IPv4: ").append(ip).append("; ");
                        } else {
                            ipSb.append("IPv6: ").append(ip).append("; ");
                        }
                    }
                }
            }

            // 处理拼接结果，避免空值
            if (ipSb.length() > 0) {
                // 移除最后一个多余的分号和空格
                return ipSb.delete(ipSb.length() - 2, ipSb.length()).toString();
            } else {
                return "未获取到有效IPv4/IPv6地址";
            }
        } catch (java.net.SocketException e) { // 明确捕获SocketException
            e.printStackTrace();
            return "获取IP异常（SocketException）：" + e.getMessage();
        } catch (Exception e) { // 兜底捕获所有异常，避免崩溃
            e.printStackTrace();
            return "获取IP异常：" + e.getMessage();
        }
    }

    private boolean waitForNetwork(Context context) {
        long startTime = System.currentTimeMillis();
        long timeout = NETWORK_TIMEOUT_MS; // 使用常量，3分钟超时
        while (System.currentTimeMillis() - startTime < timeout) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                // 先判断网络已连接，再判断能否ping通公网（真正的网络就绪）
                if (activeNetwork != null && activeNetwork.isConnectedOrConnecting()) {
                    if (isHostReachable()) return true;
                }
            }
            try {
                Thread.sleep(200); // 每200毫秒检测一次
            } catch (InterruptedException e) {
                e.printStackTrace();
                return false;
            }

        }
        // 超时返回false（网络未就绪）
        return false;
    }

    private boolean isHostReachable() {
        try { return InetAddress.getByName(PING_HOST).isReachable(1000); } catch (IOException e) { return false; }
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
        }
    }

    private void logToFile(String message) {
        File logFile = new File(Environment.getExternalStorageDirectory(), "reboot_test_log.txt");
        try {
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
            // BufferedWriter for performance, true for append mode
            BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            buf.append(timestamp).append(" - ").append(message);
            buf.newLine();
            buf.close();
        } catch (IOException e) {
            Log.e("RebootTester", "Could not write to log file", e);
        }
    }

}