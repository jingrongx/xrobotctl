package com.xrobot.hub;

import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局串行命令队列 —— 解决「多个应用/命令同时调用会冲突」的核心。
 *
 * 设计要点：
 * - 所有「动作类」命令（move/turn/head/视觉）进同一个 FIFO 队列，单工作线程逐条下发，
 *   两条命令之间强制最小间隔 GAP_MS（底层 locomote 是异步下发，发太快会被截断）。
 * - 查询类命令（ping/status/info/capabilities/queue）只读，不进队列，立即返回。
 * - stop 抢占：清空尚未执行的动作 + 立刻下发急停，不受队列阻塞。
 * - ContentProvider 与 HTTP 服务共用本队列，因此新旧调用方之间也不会打架。
 */
public final class CommandQueue {

    private static final String TAG = "XRobotQueue";
    private static final CommandQueue INSTANCE = new CommandQueue();
    public static CommandQueue get() { return INSTANCE; }

    /** 两条动作命令之间的最小间隔（毫秒） */
    public static final long GAP_MS = 300;

    private static final class Cmd {
        final String method;
        final float a, b;
        final String color;
        Cmd(String method, float a, float b, String color) {
            this.method = method; this.a = a; this.b = b; this.color = color;
        }
    }

    private final LinkedBlockingQueue<Cmd> queue = new LinkedBlockingQueue<Cmd>();
    private final Object execLock = new Object();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean started = false;
    private volatile long lastDispatchMs = 0;

    /** 在 HubApp.onCreate 调用一次 */
    public synchronized void init() {
        if (started) return;
        started = true;
        Thread t = new Thread(worker, "xrobot-cmd-queue");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "命令队列已启动，最小间隔 " + GAP_MS + "ms");
    }

    private final Runnable worker = new Runnable() {
        @Override public void run() {
            while (true) {
                try {
                    Cmd c = queue.take();
                    // 强制命令间隔（不持锁，急停可抢占）
                    long wait = GAP_MS - (System.currentTimeMillis() - lastDispatchMs);
                    if (wait > 0) Thread.sleep(wait);
                    synchronized (execLock) {
                        if (c == null) continue;
                        busy.set(true);
                        try { dispatch(c); }
                        finally {
                            lastDispatchMs = System.currentTimeMillis();
                            busy.set(false);
                        }
                    }
                } catch (InterruptedException e) {
                    // ignore
                } catch (Throwable t) {
                    Log.e(TAG, "命令执行失败", t);
                    busy.set(false);
                }
            }
        }
    };

    /** 真正下发到驱动（调用方需持有 execLock） */
    private void dispatch(Cmd c) {
        RobotDriver d = DriverRegistry.active();
        if (d == null) throw new IllegalStateException("没有已注册的机器人驱动");
        String m = c.method;
        if ("move".equals(m)) d.move(c.a, c.b);
        else if ("turn".equals(m)) d.turn(c.a, c.b);
        else if ("rawTurn".equals(m)) d.turnRaw(c.a, c.b);
        else if ("head".equals(m)) d.head(c.a, c.b);
        else if ("detect".equals(m) || "goto".equals(m)) {
            d.startVision(currentContext(), c.color, "detect".equals(m));
        } else {
            Log.w(TAG, "队列收到未知动作: " + m);
        }
    }

    private volatile android.content.Context appCtx;
    public void setContext(android.content.Context ctx) {
        this.appCtx = ctx == null ? null : ctx.getApplicationContext();
    }
    private android.content.Context currentContext() { return appCtx; }

    /**
     * 统一入口：Provider 和 HTTP 都调用它。返回 Bundle（含 ok/msg 及方法相关字段）。
     * a/b 语义随 method 而定；color 仅视觉命令用。
     */
    public Bundle exec(String method, float a, float b, String color) {
        Bundle out = new Bundle();
        out.putBoolean("ok", false);
        try {
            RobotDriver d = DriverRegistry.active();
            if (d == null) { out.putString("msg", "没有已注册的机器人驱动"); return out; }

            // ---- 查询类：立即返回，不进队列 ----
            if ("ping".equals(method)) {
                out.putString("driver", d.id());
                out.putString("name", d.displayName());
                out.putBoolean("ready", d.isReady());
                out.putString("drivers", Hub.join(DriverRegistry.ids()));
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
            if ("queue".equals(method)) {
                out.putInt("pending", queue.size());
                out.putBoolean("busy", busy.get());
                out.putLong("gap_ms", GAP_MS);
                out.putBoolean("ok", true);
                return out;
            }

            // ---- 急停：抢占 + 清空队列 ----
            if ("stop".equals(method)) {
                int cleared = queue.size();
                queue.clear();
                synchronized (execLock) {
                    busy.set(true);
                    try {
                        d.stopVision();
                        d.stop();
                    } finally {
                        busy.set(false);
                    }
                }
                out.putInt("cleared", cleared);
                out.putBoolean("ok", true);
                return out;
            }

            // ---- 视觉类：能力检查后入队 ----
            if ("detect".equals(method) || "goto".equals(method)) {
                if (!Hub.has(d, "vision")) return Hub.unsupported(out, d, "vision");
                queue.offer(new Cmd(method, 0, 0, color == null ? "red" : color));
                out.putInt("pending", queue.size());
                out.putBoolean("ok", true);
                return out;
            }

            // ---- 动作类：能力检查后入队 ----
            String cap = Hub.capOf(method);
            if (cap == null) { out.putString("msg", "未知命令: " + method); return out; }
            if (!Hub.has(d, cap)) return Hub.unsupported(out, d, cap);
            queue.offer(new Cmd(method, a, b, null));
            out.putInt("pending", queue.size());
            out.putBoolean("ok", true);
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "exec " + method + " 失败", t);
            out.putString("msg", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
    }
}
