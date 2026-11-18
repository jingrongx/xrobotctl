package com.xrobot.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * 机器人中枢 · ContentProvider 入口（兼容老调用方）。
 *
 * 新 authority（推荐）： content://com.xrobot.hub.api
 * 兼容 authority（旧版）： content://com.visbot.robotctl.api
 *
 * 本类只做参数解析，实际执行委托给全局 CommandQueue —— 因此 Provider 调用与
 * HTTP 调用走同一条串行队列，多调用方之间不会冲突。
 *
 * 注意：新的首选调用方式是本地 HTTP 服务，任何语言零依赖，见 README。
 */
public final class HubProvider extends ContentProvider {

    public static final String AUTHORITY = "com.xrobot.hub.api";
    public static final String LEGACY_AUTHORITY = "com.visbot.robotctl.api";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri LEGACY_URI = Uri.parse("content://" + LEGACY_AUTHORITY);

    private static final String TAG = "XRobotHub";

    @Override
    public boolean onCreate() {
        CommandQueue.get().setContext(getContext());
        CommandQueue.get().init();
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            float[] p = parseParams(method, arg, extras);
            String color = parseColor(method, arg, extras);
            return CommandQueue.get().exec(method, p[0], p[1], color);
        } catch (Throwable t) {
            Log.e(TAG, "call " + method + " 失败", t);
            Bundle out = new Bundle();
            out.putBoolean("ok", false);
            out.putString("msg", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
    }

    private static float[] parseParams(String method, String arg, Bundle extras) {
        float d1 = def1(method), d2 = def2(method);
        float[] p = pair(arg, d1, d2);
        float a = req(extras, "arg1", p[0]);
        float b = req(extras, "arg2", p[1]);
        return new float[]{a, b};
    }

    private static String parseColor(String method, String arg, Bundle extras) {
        if (!"detect".equals(method) && !"goto".equals(method)) return null;
        if (arg != null && !arg.contains(",")) return arg;   // 颜色字符串，无逗号
        if (extras != null && extras.containsKey("color")) return String.valueOf(extras.get("color"));
        return "red";
    }

    private static float def1(String method) {
        if ("move".equals(method)) return 30f;
        if ("head".equals(method)) return 0f;
        return 90f;
    }

    private static float def2(String method) {
        if ("move".equals(method)) return 8f;
        if ("head".equals(method)) return 0f;
        return 60f;
    }

    /** 兼容旧协议的 "数值,数值" 字符串 */
    private static float[] pair(String arg, float d1, float d2) {
        if (arg == null) return new float[]{d1, d2};
        try {
            String[] parts = arg.split(",");
            float a = Float.parseFloat(parts[0].trim());
            float b = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : d2;
            return new float[]{a, b};
        } catch (Throwable t) {
            return new float[]{d1, d2};
        }
    }

    private static float req(Bundle b, String key, float def) {
        if (b == null || !b.containsKey(key)) return def;
        try {
            return b.getFloat(key, def);
        } catch (Throwable t) {
            try {
                return Float.parseFloat(String.valueOf(b.get(key)));
            } catch (Throwable t2) {
                return def;
            }
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
