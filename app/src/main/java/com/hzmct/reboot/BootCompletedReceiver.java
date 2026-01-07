package com.hzmct.reboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String APP_LOG_TAG = "RebootTester";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        // 仅记录开机信息，不做任何网络检测和计时
        String bootTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        Log.d(APP_LOG_TAG, "【设备开机信息】: " + bootTime + "（非APK启动时间）");
        Log.d(APP_LOG_TAG, "Broadcast received: " + intent.getAction());

        // 唯一功能：通过Root命令启动APK（不做其他逻辑）
        Log.d(APP_LOG_TAG, "Launching UI via root command...");
        executeRootCommand("am start -n " + context.getPackageName() + "/" + MainActivity.class.getName());
    }

    // 仅保留Root启动命令方法，其他方法（网络检测、日志写入等）全部删除
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
            Log.e(APP_LOG_TAG, "Root command execute failed", e);
        }
    }
}