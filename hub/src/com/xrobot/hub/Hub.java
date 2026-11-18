package com.xrobot.hub;

import android.os.Bundle;

/** 中枢公共工具：命令→能力映射、能力检查、错误封装。Provider 与队列共用。 */
final class Hub {

    /** 把动作方法映射到能力名；非动作返回 null */
    static String capOf(String method) {
        if (method == null) return null;
        if ("move".equals(method)) return "move";
        if ("turn".equals(method) || "rawTurn".equals(method)) return "turn";
        if ("head".equals(method)) return "head";
        return null;
    }

    static boolean has(RobotDriver d, String cap) {
        String[] cs = d.capabilities();
        if (cs == null) return false;
        for (String c : cs) if (c.equals(cap)) return true;
        return false;
    }

    static Bundle unsupported(Bundle out, RobotDriver d, String cap) {
        out.putString("msg", "驱动 " + d.id() + " 不支持能力: " + cap);
        return out;
    }

    static String join(String[] arr) {
        if (arr == null || arr.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        return sb.toString();
    }

    private Hub() {}
}
