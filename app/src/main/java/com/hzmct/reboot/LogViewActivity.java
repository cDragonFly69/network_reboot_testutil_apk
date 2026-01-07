package com.hzmct.reboot;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class LogViewActivity extends Activity {

    private TextView tvLogContent;
    private Button btnBack;
    private static final String APP_LOG_TAG = "RebootTester";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 绑定布局文件（解决找不到布局的错误）
        setContentView(R.layout.activity_log_view);

        // 绑定控件
        tvLogContent = findViewById(R.id.tv_log_content);
        btnBack = findViewById(R.id.btn_back);

        // 返回按钮点击事件
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(); // 关闭当前页面，返回上一页
            }
        });

        // 获取传递的日志文件路径
        String logFilePath = getIntent().getStringExtra("LOG_FILE_PATH");

        if (logFilePath == null || logFilePath.isEmpty()) {
            tvLogContent.setText("日志文件路径为空");
            return;
        }

        Log.d(APP_LOG_TAG, "LogViewActivity 读取日志路径：" + logFilePath);
        readLogFile(logFilePath);
    }

    // 读取日志文件内容
    private void readLogFile(String logFilePath) {
        new Thread(() -> {
            File logFile = new File(logFilePath);
            StringBuilder logContent = new StringBuilder();

            if (!logFile.exists()) {
                logContent.append("日志文件不存在！\n路径：").append(logFilePath);
                Log.e(APP_LOG_TAG, "日志文件不存在：" + logFilePath);
            } else {
                try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                    String line;
                    // 按行读取日志
                    while ((line = br.readLine()) != null) {
                        logContent.append(line).append("\n");
                    }
                    Log.d(APP_LOG_TAG, "日志读取成功，共" + logContent.toString().split("\n").length + "行");
                } catch (IOException e) {
                    logContent.append("读取日志失败：").append(e.getMessage());
                    Log.e(APP_LOG_TAG, "读取日志异常：" + e.getMessage(), e);
                }
            }

            // 主线程更新UI
            runOnUiThread(() -> {
                tvLogContent.setText(logContent.toString());
                Toast.makeText(LogViewActivity.this, "日志加载完成", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
}