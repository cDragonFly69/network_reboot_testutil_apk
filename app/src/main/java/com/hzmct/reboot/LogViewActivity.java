package com.hzmct.reboot;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class LogViewActivity extends Activity {
    private TextView tvLogContent;
    private Button btnCloseLog;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 简单布局（也可通过xml布局实现，这里直接代码创建，无需额外xml文件）
        initView();
        // 加载并显示日志内容
        loadLogFileContent();
    }
    /**
     * 初始化界面控件
     */
    private void initView() {
        // 1. 创建垂直布局的根视图
        android.widget.LinearLayout rootLayout = new android.widget.LinearLayout(this);
        rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        rootLayout.setPadding(20, 20, 20, 20);
        setContentView(rootLayout);

        // 2. 创建关闭按钮
        btnCloseLog = new Button(this);
        btnCloseLog.setText("关闭日志返回");
        btnCloseLog.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        // 按钮点击事件：关闭当前Activity，返回MainActivity
        btnCloseLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 关闭当前日志查看窗口，返回上一个界面（MainActivity）
                finish();
            }
        });
        rootLayout.addView(btnCloseLog);

        // 3. 创建滚动视图（支持日志内容过多时上下滑动）
        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1 // 占剩余所有空间
        ));
        rootLayout.addView(scrollView);

        // 4. 创建文本视图，用于显示日志内容
        tvLogContent = new TextView(this);
        tvLogContent.setPadding(10, 10, 10, 10);
        tvLogContent.setTextSize(12);
        tvLogContent.setLineSpacing(1.2f, 1.2f); // 设置行间距，便于阅读
        scrollView.addView(tvLogContent);
    }

    /**
     * 加载 reboot_test_log.txt 日志文件内容并显示
     */
    private void loadLogFileContent() {
        // 获取日志文件路径（与 logToFile 方法一致）
        File logFile = new File(Environment.getExternalStorageDirectory(), "reboot_test_log.txt");
        StringBuilder logContent = new StringBuilder();

        // 判断日志文件是否存在
        if (!logFile.exists()) {
            logContent.append("日志文件不存在！");
            tvLogContent.setText(logContent.toString());
            return;
        }

        // 读取日志文件内容
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(logFile));
            String line;
            // 按行读取日志，逐行拼接（保留原有格式）
            while ((line = br.readLine()) != null) {
                logContent.append(line).append("\n");
            }
        } catch (IOException e) {
            logContent.append("读取日志失败：").append(e.getMessage());
            e.printStackTrace();
        } finally {
            // 关闭流，避免资源泄漏
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        // 显示日志内容
        tvLogContent.setText(logContent.toString());
        // 滚动到日志底部（显示最新日志）
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

}
