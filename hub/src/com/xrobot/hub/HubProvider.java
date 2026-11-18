package com.xrobot.hub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * 机器人中枢：所有 App 通过本 Provider 下发命令，中枢选择驱动执行。
 *
 * 新 authority（推荐）： content://com.xrobot.hub.api
 * 兼容 authority（旧版）： content://com.visbot.robotctl.api
 *
 * 客户端只需 ContentResolver.call(uri, method, arg, extras)，
 * 返回值统一含 ok(boolean)，失败时 msg(String) 给出原因。
 */
public final class HubProvider extends ContentProvider {

    public static final String AUTHORITY = "com.xrobot.hub.api";
    public static final String LEGACY_AUTHORITY = "com.visbot.robotctl.api";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri LEGACY_URI = Uri.parse("content://" + LEGACY_AUTHORITY);

    private static final String TAG = "XRobotHub";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        out.putBoolean("ok", false);
        try {
            RobotDriver d = DriverRegistry.active();
            if (d == null) {
                out.putString("msg", "没有已注册的机器人驱动");
                return out;
            }
            if ("ping".equals(method)) {
                out.putString("driver", d.id());
                out.putString("name", d.displayName());
                out.putBoolean("ready", d.isReady());
                out.putString("drivers", join(DriverRegistry.ids()));
                out.putBoolean("ok", true);
                return out;
            }
            if ("capabilities".equals(method)) {
                out.putStringArray("capabilities", d.capabilities());
                out.putString("driver", d.id());
                out.putString("name", d.displayName());
                out.putBoolean("ok", true);
                return out;
            }
            if ("status".equals(method)) {
                out.putString("status", d.status());
                out.putString("driver", d.id());
                out.putBoolean("ok", true);
                return out;
            }
            if ("info".equals(method)) {
                out.putString("info", d.info());
                out.putString("driver", d.id());
                out.putString("name", d.displayName());
                out.putBoolean("ok", true);
                return out;
            }
            if ("stop".equals(method)) {
                d.stopVision();
                d.stop();
                out.putBoolean("ok", true);
                return out;
            }
            if ("detect".equals(method) || "goto".equals(method)) {
                if (!has(d, "vision")) return unsupported(out, d, "vision");
                Context ctx = getContext();
                d.startVision(ctx, arg, "detect".equals(method));
                out.putBoolean("ok", true);
                return out;
            }

            // 动作类命令：先做能力检查，再取参数，最后下发
            String cap = capOf(method);
            if (cap == null) {
                out.putString("msg", "未知命令: " + method);
                return out;
            }
            if (!has(d, cap)) return unsupported(out, d, cap);

            float[] p = pair(arg, def1(method), def2(method));
            float a = req(extras, "arg1", p[0]);
            float b = req(extras, "arg2", p[1]);

            if ("move".equals(method)) d.move(a, b);
            else if ("turn".equals(method)) d.turn(a, b);
            else if ("rawTurn".equals(method)) d.turnRaw(a, b);
            else if ("head".equals(method)) d.head(a, b);

            out.putBoolean("ok", true);
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "call " + method + " 失败", t);
            out.putString("msg", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
    }

    /** 把动作方法映射到能力名 */
    private static String capOf(String method) {
        if (method == null) return null;
        if ("move".equals(method)) return "move";
        if ("turn".equals(method) || "rawTurn".equals(method)) return "turn";
        if ("head".equals(method)) return "head";
        return null;
    }

    private static boolean has(RobotDriver d, String cap) {
        String[] cs = d.capabilities();
        if (cs == null) return false;
        for (String c : cs) if (c.equals(cap)) return true;
        return false;
    }

    private static Bundle unsupported(Bundle out, RobotDriver d, String cap) {
        out.putString("msg", "驱动 " + d.id() + " 不支持能力: " + cap);
        return out;
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

    private static String join(String[] arr) {
        if (arr == null || arr.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        return sb.toString();
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
