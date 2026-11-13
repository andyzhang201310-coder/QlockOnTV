package com.family.studytv;

import android.app.Application;

/**
 * 全局入口：负责首次运行时把内置题库释放到私有目录。
 */
public class App extends Application {

    private static App instance;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 应用更新 / 进程重建后，把家长设定的「隐藏图标」状态重新落到系统
        LauncherIcon.enforce(this);
        // 首次启动释放题库（失败不阻塞启动）
        try {
            QuestionBank.get().ensureSeeded();
        } catch (Throwable ignored) {
        }
    }
}
