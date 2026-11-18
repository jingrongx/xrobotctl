package com.xrobot.hub;

import android.app.Application;
import android.util.Log;

import com.visbot.robotctl.Sdk;
import com.xrobot.hub.driver.VisbotDriver;

/**
 * 中枢进程入口。必须在 Application.onCreate 初始化 SDK：
 * Provider 可能在 Activity 之前收到调用，晚初始化会丢命令。
 *
 * 保持 com.visbot.robotctl 包名：设备上已装的应用就地升级，
 * 不需要改启动器/无障碍配置。
 */
public final class HubApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Sdk.init(this);
        DriverRegistry.register(new VisbotDriver());
        Log.i("XRobotHub", "中枢就绪: " + DriverRegistry.describe());
    }
}
