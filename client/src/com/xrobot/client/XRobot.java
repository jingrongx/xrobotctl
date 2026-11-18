package com.xrobot.client;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * XRobot 客户端 —— 一行代码控制机器人。
 *
 *   XRobot.get(context).move(30, 10);      // 前进 30cm，速度 10cm/s
 *   XRobot.get(context).turn(90, 60);      // 左转 90°
 *   XRobot.get(context).head(25, 15);      // 抬头 25°
 *   XRobot.get(context).stop();            // 急停
 *
 * 只依赖 android.jar，把这个文件拷进你的工程即可，无需权限、无需初始化。
 * 需要先安装「XRobot 中枢」APK 并保持其进程常驻。
 */
public final class XRobot {

    private static final String AUTHORITY = "com.xrobot.hub.api";
    private static final String LEGACY_AUTHORITY = "com.visbot.robotctl.api";

    private final ContentResolver cr;
    private Uri uri;

    private XRobot(Context ctx) {
        this.cr = ctx.getApplicationContext().getContentResolver();
        this.uri = Uri.parse("content://" + AUTHORITY);
    }

    /** 全局入口。可重复调用，很轻量 */
    public static synchronized XRobot get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new XRobot(ctx);
        return INSTANCE;
    }

    private static volatile XRobot INSTANCE;

    // ---------- 极简动作 API ----------

    /** 前进/后退：dist 恒为正，speed 为负即后退 */
    public Result move(float dist, float speed) {
        return call("move", dist, speed);
    }

    /** 前进：默认速度 8cm/s */
    public Result move(float dist) {
        return move(dist, 8f);
    }

    /** 原地转向：正=左，负=右 */
    public Result turn(float angle, float speed) {
        return call("turn", angle, speed);
    }

    /** 左转：默认速度 60°/s */
    public Result turn(float angle) {
        return turn(angle, 60f);
    }

    /** 头部：−23 ~ +25° */
    public Result head(float angle) {
        return call("head", angle, 0f);
    }

    /** 急停 */
    public Result stop() {
        return call("stop", 0f, 0f);
    }

    /** 视觉追踪并走过去：color = red / green / blue */
    public Result gotoColor(String color) {
        return raw("goto", color == null ? "red" : color, null);
    }

    /** 只检测目标不驱动（标定用） */
    public Result detect(String color) {
        return raw("detect", color == null ? "red" : color, null);
    }

    // ---------- 查询 ----------

    /** 中枢是否在、当前哪个驱动、是否就绪 */
    public Result ping() {
        return raw("ping", null, null);
    }

    /** 当前驱动支持的能力数组 */
    public String[] capabilities() {
        Result r = raw("capabilities", null, null);
        return r.ok ? r.raw.getStringArray("capabilities") : new String[0];
    }

    /** 当前状态文本 */
    public String status() {
        Result r = raw("status", null, null);
        return r.ok ? r.raw.getString("status") : ("查询失败: " + r.msg);
    }

    /** 设备参数文本 */
    public String info() {
        Result r = raw("info", null, null);
        return r.ok ? r.raw.getString("info") : ("查询失败: " + r.msg);
    }

    // ---------- 底层（正常不需要） ----------

    public Result raw(String method, String arg, Bundle extras) {
        Bundle out = safeCall(uri, method, arg, extras);
        if (out == null && !uri.equals(legacyUri())) {
            // 兼容旧版中枢：新 authority 没响应，回退旧 authority
            uri = legacyUri();
            out = safeCall(uri, method, arg, extras);
        }
        if (out == null) return Result.error("未找到 XRobot 中枢，请确认已安装并保持常驻");
        Result r = new Result();
        r.ok = out.getBoolean("ok", false);
        r.msg = out.getString("msg");
        r.raw = out;
        return r;
    }

    private Result call(String method, float a, float b) {
        Bundle in = new Bundle();
        in.putFloat("arg1", a);
        in.putFloat("arg2", b);
        return raw(method, null, in);
    }

    private Bundle safeCall(Uri u, String method, String arg, Bundle extras) {
        try {
            return cr.call(u, method, arg, extras);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Uri legacyUri() {
        return Uri.parse("content://" + LEGACY_AUTHORITY);
    }

    /** 统一返回结果 */
    public static final class Result {
        public boolean ok;
        public String msg;
        public Bundle raw;

        public boolean failed() { return !ok; }
        public String message() { return ok ? "OK" : (msg == null ? "失败" : msg); }

        static Result error(String m) {
            Result r = new Result();
            r.ok = false;
            r.msg = m;
            r.raw = new Bundle();
            return r;
        }
    }
}
