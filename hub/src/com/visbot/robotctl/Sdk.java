package com.visbot.robotctl;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

import dalvik.system.DexClassLoader;

/** UBTECH SDK 静态入口：必须在 Application.onCreate 里初始化 */
public final class Sdk {

    private static ClassLoader loader;
    private static Object robotContext;

    private Sdk() {
    }

    public static synchronized void init(Context app) {
        if (loader != null) return;
        try {
            // 从 visbot 的 vdex 反优化出来的 SDK dex
            loader = new DexClassLoader("/data/local/tmp/visbot1.dex", null, null,
                    Sdk.class.getClassLoader());
            Class<?> robot = Class.forName("com.ubtrobot.Robot", true, loader);
            robot.getMethod("initialize", Context.class).invoke(null, app);
            robotContext = robot.getMethod("globalContext").invoke(null);
            Log.i(RobotCtl.TAG, "SDK init ok: " + robotContext);
        } catch (Throwable t) {
            Log.e(RobotCtl.TAG, "SDK init 失败", t);
        }
    }

    /** 等待 SDK 初始化完成（HTTP 请求可能先于就绪到达） */
    public static boolean waitReady(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (robotContext == null && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
        return robotContext != null;
    }

    public static ClassLoader loader() {
        return loader;
    }

    public static Object context() {
        return robotContext;
    }

    public static Object service(String name) throws Exception {
        if (robotContext == null) throw new IllegalStateException("SDK 未初始化");
        Method gs = robotContext.getClass().getMethod("getSystemService", String.class);
        return gs.invoke(robotContext, name);
    }
}
