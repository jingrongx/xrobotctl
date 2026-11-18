package com.xrobot.hub;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.visbot.robotctl.Sdk;
import com.xrobot.hub.driver.VisbotDriver;

/**
 * 中枢进程入口。
 *
 * 在 Application.onCreate 里完成三件事：
 * - 初始化底层 SDK（HTTP 服务可能在 Activity 之前收到调用，晚初始化会丢命令）
 * - 注册驱动
 * - 启动本地 HTTP 服务 + 命令队列
 */
public final class HubApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Sdk.init(this);
        DriverRegistry.register(new VisbotDriver());

        CommandQueue q = CommandQueue.get();
        q.setContext(this);
        q.init();

        LocalHttpServer.get().start();

        // 前台服务保活，防止进程被系统回收导致 HTTP 服务中断
        try {
            Intent si = new Intent(this, HubService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
            else startService(si);
        } catch (Throwable t) {
            Log.w("XRobotHub", "前台服务启动失败", t);
        }

        Log.i("XRobotHub", "中枢就绪: " + DriverRegistry.describe()
                + " | HTTP :" + LocalHttpServer.PORT);

        startAutoUpdateCheck();
    }

    /** 启动后异步检测更新（受设置开关控制），发现新版本发通知，不打断当前控制流程 */
    private void startAutoUpdateCheck() {
        final Updater updater = new Updater(this);
        if (!updater.autoCheck()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(3000); // 让 SDK/HTTP 服务先就绪，避免抢启动资源
                    Updater.Release r = updater.check();
                    if (updater.isNewer(r)) {
                        UpdateNotifier.show(HubApp.this,
                                "XRobot 发现新版本 " + r.tag,
                                "点击查看更新中心，一键下载安装");
                        Log.i("XRobotUpdater", "启动检测发现新版本 " + r.tag);
                    }
                } catch (Throwable t) {
                    Log.w("XRobotUpdater", "启动检测失败（不影响使用）: " + t.getMessage());
                }
            }
        }, "xrobot-auto-update").start();
    }
}
