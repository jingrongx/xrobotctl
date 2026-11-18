package com.visbot.robotctl;

/**
 * 机器人控制层（轮子 + 头部）。所有方法不抛受检异常，失败抛 RuntimeException。
 *
 * 实测经验：
 *  1. 必须用 LocomotionOption.Builder 构造参数再调 locomote(option)；
 *     SDK 自带 moveStraightBy() 在本机 dex 上会传错参数（distance=0、速度溢出）。
 *  2. 距离不能为负，后退靠「负速度」。
 *  3. 左转精确；右转机械打滑，负角度自动换算成等效左转 (360-|a|)。
 *  4. 头部只有一个舵机 id=head，速度 2~30。
 *  5. 头部舵机「正角度=低头」，本层对角度取反：调用方传正数=抬头，
 *     因此调用方有效范围是 +23（抬头极限）~ -25（低头极限）。
 */
public final class RobotCtl {

    public static final String TAG = "ROBOTCTL";

    private RobotCtl() {
    }

    private static RuntimeException rt(Throwable t) {
        return (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
    }

    private static Object newBuilder() throws Exception {
        Class<?> ob = Class.forName("com.ubtrobot.locomotion.LocomotionOption$Builder",
                true, Sdk.loader());
        return ob.newInstance();
    }

    private static void send(Object builder) throws Exception {
        Object opt = builder.getClass().getMethod("build").invoke(builder);
        Object svc = Sdk.service("locomotion");
        Class<?> oc = Class.forName("com.ubtrobot.locomotion.LocomotionOption", true, Sdk.loader());
        svc.getClass().getMethod("locomote", oc).invoke(svc, opt);
    }

    /** 前进/后退：dist 厘米（必须为正），speed 厘米/秒（负数=后退） */
    public static void move(float dist, float speed) {
        if (dist < 0) throw new IllegalArgumentException("distance 必须为正，后退请用负速度");
        try {
            Object b = newBuilder();
            b.getClass().getMethod("setMovingDistance", float.class).invoke(b, dist);
            b.getClass().getMethod("setMovingSpeed", float.class).invoke(b, speed);
            send(b);
        } catch (Throwable t) {
            throw rt(t);
        }
    }

    /** 原地转向：deg 正=左转，负=右转（内部换算为等效左转） */
    public static void turn(float deg, float speed) {
        try {
            float eff = deg;
            boolean converted = false;
            if (deg < 0) {
                float m = Math.abs(deg) % 360f;
                eff = (m == 0f) ? 360f : 360f - m;
                converted = true;
            } else if (deg > 0 && deg % 360f == 0f) {
                eff = 360f;
            }
            Object b = newBuilder();
            b.getClass().getMethod("setTurningAngle", float.class).invoke(b, eff);
            b.getClass().getMethod("setTurningSpeed", float.class).invoke(b, Math.abs(speed));
            send(b);
            if (converted) android.util.Log.i(TAG, "turn(" + deg + ") → 左转 " + eff + "°");
        } catch (Throwable t) {
            throw rt(t);
        }
    }

    /** 原始转向（不换算方向），仅用于实验 */
    public static void turnRaw(float angle, float speed) {
        try {
            Object b = newBuilder();
            b.getClass().getMethod("setTurningAngle", float.class).invoke(b, Math.abs(angle));
            b.getClass().getMethod("setTurningSpeed", float.class).invoke(b, speed);
            send(b);
        } catch (Throwable t) {
            throw rt(t);
        }
    }

    /** 急停 */
    public static void stop() {
        try {
            Object b = newBuilder();
            try {
                b.getClass().getMethod("setEmergency", boolean.class).invoke(b, true);
            } catch (Throwable ignored) {
            }
            try {
                b.getClass().getMethod("setMovingDistance", float.class).invoke(b, 1f);
                b.getClass().getMethod("setMovingSpeed", float.class).invoke(b, 1f);
            } catch (Throwable ignored) {
            }
            send(b);
        } catch (Throwable t) {
            throw rt(t);
        }
    }

    /** 转向 + 直行组合下发 */
    public static void moveAt(float dist, float speed, float angle) {
        try {
            Object b = newBuilder();
            b.getClass().getMethod("setMovingDistance", float.class).invoke(b, Math.abs(dist));
            b.getClass().getMethod("setMovingSpeed", float.class).invoke(b, speed);
            if (angle != 0f) {
                float a = angle < 0 ? 360f + (angle % 360f) : angle;
                b.getClass().getMethod("setMovingAngle", float.class).invoke(b, a);
            }
            send(b);
        } catch (Throwable t) {
            throw rt(t);
        }
    }

    /** 头部：角度 正=抬头（+23 极限）、负=低头（-25 极限），速度 2~30（0=默认）。
     *  舵机本体正角度=低头，这里统一取反，保证 UI 与 HTTP 的「正=抬头」语义正确。 */
    public static void head(float angle, float speed) {
        try {
            Object svc = Sdk.service("servo");
            java.util.List<?> list = (java.util.List<?>) svc.getClass()
                    .getMethod("getDeviceList").invoke(svc);
            Object dev = list.get(0);
            String id = (String) dev.getClass().getMethod("getId").invoke(dev);
            float sp = speed > 0 ? speed
                    : (Float) dev.getClass().getMethod("getDefaultSpeed").invoke(dev);
            svc.getClass().getMethod("rotateTo", String.class, float.class, float.class)
                    .invoke(svc, id, -angle, sp);
        } catch (Throwable t) {
            throw rt(t);
        }
    }

    public static String deviceInfo() {
        try {
            Object svc = Sdk.service("locomotion");
            Object dev = svc.getClass().getMethod("getDevice").invoke(svc);
            return "id=" + dev.getClass().getMethod("getId").invoke(dev)
                    + " speed=" + dev.getClass().getMethod("getMinMovingSpeed").invoke(dev)
                    + "~" + dev.getClass().getMethod("getMaxMovingSpeed").invoke(dev)
                    + "(" + dev.getClass().getMethod("getDefaultMovingSpeed").invoke(dev) + ")"
                    + " turn=" + dev.getClass().getMethod("getMinTurningSpeed").invoke(dev)
                    + "~" + dev.getClass().getMethod("getMaxTurningSpeed").invoke(dev);
        } catch (Throwable t) {
            return "deviceInfo 失败: " + t;
        }
    }

    public static boolean isLocomoting() {
        try {
            Object svc = Sdk.service("locomotion");
            return (Boolean) svc.getClass().getMethod("isLocomoting").invoke(svc);
        } catch (Throwable t) {
            return false;
        }
    }
}
