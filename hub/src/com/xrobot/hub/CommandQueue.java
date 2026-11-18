package com.xrobot.hub;

import android.os.Bundle;
import android.util.Log;

import com.visbot.robotctl.Sdk;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局串行命令队列 —— 解决「多个应用/命令同时调用会冲突」+「SDK 就绪等待」的核心。
 *
 * 设计要点：
 * - 所有「动作类」命令（move/turn/head/视觉）进同一个 FIFO 队列，单工作线程逐条下发，
 *   两条命令之间强制最小间隔 GAP_MS（底层 locomote 是异步下发，发太快会被截断）。
 * - 【就绪由中枢负责】每条动作下发前 worker 调用 Sdk.waitReady()，等底层 SDK 就绪再执行，
 *   调用方无需自己 ping 判断、无需重试。等待耗时体现在返回的 waited_ms 里。
 * - 查询类命令（ping/status/info/capabilities/queue）只读，不进队列，立即返回。
 * - stop 抢占：清空尚未执行的动作 + 立刻下发急停，不受队列阻塞。
 * - ContentProvider 与 HTTP 服务共用本队列，因此新旧调用方之间也不会打架。
 *
 * 返回语义：
 * - 默认（异步）：动作入队即返回 ok，附 pending=排队数、mode=queued（低延迟 ~10ms）。
 * - wait=1（同步）：等到该命令真正执行完才返回，附 executed=true、waited_ms=等待耗时。
 *   无论哪种模式，命令最终都会被 worker 在就绪后执行，不会静默丢弃。
 */
public final class CommandQueue {

    private static final String TAG = "XRobotQueue";
    private static final CommandQueue INSTANCE = new CommandQueue();
    public static CommandQueue get() { return INSTANCE; }

    /** 两条动作命令之间的最小间隔（毫秒） */
    public static final long GAP_MS = 300;
    /** SDK 就绪最长等待（毫秒） */
    public static final long READY_TIMEOUT_MS = 8000;
    /** 同步模式下等命令执行完的最长时间（毫秒） */
    public static final long EXEC_TIMEOUT_MS = 12000;

    private static final class Cmd {
        final String method;
        final float a, b;
        final String color;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean executed = false;
        volatile String error = null;
        volatile long waitedMs = 0;

        Cmd(String method, float a, float b, String color) {
            this.method = method; this.a = a; this.b = b; this.color = color;
        }
    }

    private final LinkedBlockingQueue<Cmd> queue = new LinkedBlockingQueue<Cmd>();
    private final Object execLock = new Object();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean started = false;
    private volatile long lastDispatchMs = 0;
    private volatile int executedCount = 0;

    /** 在 HubApp.onCreate 调用一次 */
    public synchronized void init() {
        if (started) return;
        started = true;
        Thread t = new Thread(worker, "xrobot-cmd-queue");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "命令队列已启动，间隔 " + GAP_MS + "ms，就绪超时 " + READY_TIMEOUT_MS + "ms");
    }

    private final Runnable worker = new Runnable() {
        @Override public void run() {
            while (true) {
                Cmd c = null;
                try {
                    c = queue.take();

                    // 就绪等待由中枢承担：SDK 未就绪时在这里等，不让调用方操心
                    long w0 = System.currentTimeMillis();
                    boolean ready = Sdk.waitReady(READY_TIMEOUT_MS);
                    c.waitedMs = System.currentTimeMillis() - w0;
                    if (!ready) {
                        c.error = "底层 SDK 在 " + READY_TIMEOUT_MS + "ms 内未就绪";
                        c.done.countDown();
                        Log.w(TAG, c.error);
                        continue;
                    }

                    // 强制命令间隔（不持 execLock，急停可抢占）
                    long wait = GAP_MS - (System.currentTimeMillis() - lastDispatchMs);
                    if (wait > 0) Thread.sleep(wait);

                    synchronized (execLock) {
                        busy.set(true);
                        try {
                            dispatch(c);
                            c.executed = true;
                            executedCount++;
                        } catch (Throwable t) {
                            c.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                            Log.e(TAG, "命令执行失败: " + c.method, t);
                        } finally {
                            lastDispatchMs = System.currentTimeMillis();
                            busy.set(false);
                        }
                    }
                    c.done.countDown();
                } catch (InterruptedException e) {
                    if (c != null) { c.error = "队列被中断"; c.done.countDown(); }
                } catch (Throwable t) {
                    if (c != null) { c.error = "执行异常: " + t.getMessage(); c.done.countDown(); }
                    Log.e(TAG, "worker 异常", t);
                    busy.set(false);
                }
            }
        }
    };

    /** 真正下发到驱动（调用方需持有 execLock 且 SDK 已就绪） */
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
     * 统一入口：Provider 和 HTTP 都调用它。
     * @param wait true=同步等执行完；false=入队即返回
     */
    public Bundle exec(String method, float a, float b, String color, boolean wait) {
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
                out.putInt("executed", executedCount);
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
                out.putInt("executed", executedCount);
                out.putBoolean("ok", true);
                return out;
            }

            // ---- 急停：抢占 + 清空队列 ----
            if ("stop".equals(method)) {
                java.util.List<Cmd> pending = new java.util.ArrayList<Cmd>();
                queue.drainTo(pending);
                int cleared = pending.size();
                for (Cmd p : pending) { p.error = "被急停取消"; p.done.countDown(); }
                synchronized (execLock) {
                    busy.set(true);
                    try {
                        if (!Sdk.waitReady(2000)) {  // 急停也尽量等就绪，但最多2秒
                            out.putString("msg", "SDK 未就绪，急停未下发");
                            out.putBoolean("ok", false);
                            return out;
                        }
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
                Cmd c = new Cmd(method, 0, 0, color == null ? "red" : color);
                return submitAndBuild(out, c, wait);
            }

            // ---- 动作类：能力检查后入队 ----
            String cap = Hub.capOf(method);
            if (cap == null) { out.putString("msg", "未知命令: " + method); return out; }
            if (!Hub.has(d, cap)) return Hub.unsupported(out, d, cap);
            Cmd c = new Cmd(method, a, b, null);
            return submitAndBuild(out, c, wait);
        } catch (Throwable t) {
            Log.e(TAG, "exec " + method + " 失败", t);
            out.putString("msg", t.getClass().getSimpleName() + ": " + t.getMessage());
            return out;
        }
    }

    /** 入队并按 wait 模式构造返回 */
    private Bundle submitAndBuild(Bundle out, Cmd c, boolean wait) {
        queue.offer(c);
        out.putInt("pending", queue.size());
        if (!wait) {
            out.putString("mode", "queued");
            out.putBoolean("ok", true);
            return out;
        }
        boolean done;
        try { done = c.done.await(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { done = false; }
        out.putString("mode", "sync");
        out.putLong("waited_ms", c.waitedMs);
        if (!done) {
            out.putString("msg", "等待执行超时(" + EXEC_TIMEOUT_MS + "ms)");
            return out;
        }
        if (c.error != null) {
            out.putString("msg", c.error);
            return out;
        }
        out.putBoolean("executed", true);
        out.putBoolean("ok", true);
        return out;
    }
}
