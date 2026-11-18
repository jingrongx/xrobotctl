package com.visbot.robotctl;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * 纯视觉闭环：摄像头找彩色目标 → 转向对准 → 前进接近 → 到达即停。
 *
 * 为什么用闭环：底盘转向开环、右转还会打滑，但只要每帧都能看到目标，
 * 少转了就再补一次，误差不会累积。
 */
public final class VisionCtl implements ImageReader.OnImageAvailableListener {

    private static final String TAG = RobotCtl.TAG;
    private static final int W = 640, H = 480, STEP = 8;

    private static VisionCtl inst;

    public static synchronized VisionCtl get() {
        if (inst == null) inst = new VisionCtl();
        return inst;
    }

    private Context app;
    private HandlerThread camThread, ctlThread;
    private Handler camH, ctlH;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;

    private volatile boolean running;
    private volatile int colorIdx = 0;
    private volatile String phase = "idle";
    private volatile float cxNorm = -1f, cyNorm = -1f, area = 0f;
    private volatile long lastFrameAt = 0;
    private volatile long busyUntil = 0;
    private volatile long startAt = 0;
    private volatile int searchSteps = 0;
    private volatile String lastAction = "-";
    private volatile float lastTurnDeg = 0f;      // 上一步转向角度（正=左，负=右）
    private volatile long lastTurnAt = 0;         // 上一步转向时刻
    private volatile boolean undone = false;      // 本轮是否已回退过

    /** 到达阈值：目标占画面比例 */
    public volatile float arriveArea = 0.03f;
    /** 视野死区（左右偏移比例） */
    public volatile float deadband = 0.12f;
    /** 只检测不驱动（标定用） */
    public volatile boolean dryRun = false;

    private VisionCtl() {
    }

    @Override
    public void onImageAvailable(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            float[] res = detect(img, colorIdx);
            cxNorm = res[0];
            cyNorm = res[1];
            area = res[2];
            lastFrameAt = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            Log.w(TAG, "检测异常 " + t);
        } finally {
            if (img != null) img.close();
        }
    }

    // ---------- 启动/停止 ----------
    public synchronized void start(Context ctx, String colorName) throws Exception {
        start(ctx, colorName, false);
    }

    public synchronized void start(Context ctx, String colorName, boolean dry) throws Exception {
        if (running) return;
        dryRun = dry;
        app = ctx.getApplicationContext();
        colorIdx = colorIndex(colorName);
        phase = "starting";
        startAt = SystemClock.elapsedRealtime();
        searchSteps = 0;
        busyUntil = 0;

        camThread = new HandlerThread("cam");
        camThread.start();
        camH = new Handler(camThread.getLooper());
        ctlThread = new HandlerThread("ctl");
        ctlThread.start();
        ctlH = new Handler(ctlThread.getLooper());

        reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this, camH);

        CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        String id = cm.getCameraIdList()[0];
        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice cam) {
                device = cam;
                try {
                    cam.createCaptureSession(java.util.Collections.singletonList(reader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession s) {
                                    session = s;
                                    try {
                                        CaptureRequest.Builder b = device
                                                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        b.addTarget(reader.getSurface());
                                        s.setRepeatingRequest(b.build(), null, camH);
                                        try {
                                            RobotCtl.head(-8f, 10f);   // 略低头，近距离目标更容易留在视野内
                                        } catch (Throwable ignored) {
                                        }
                                        running = true;
                                        phase = "searching";
                                        ctlH.post(control);
                                        Log.i(TAG, "视觉开始，目标色=" + colorIdx);
                                    } catch (Throwable t) {
                                        Log.e(TAG, "开始预览失败", t);
                                    }
                                }

                                @Override public void onConfigureFailed(CameraCaptureSession s) {
                                    Log.e(TAG, "会话配置失败");
                                }
                            }, camH);
                } catch (Throwable t) {
                    Log.e(TAG, "建会话失败", t);
                }
            }

            @Override public void onDisconnected(CameraDevice cam) {
                cam.close();
                device = null;
            }

            @Override public void onError(CameraDevice cam, int err) {
                Log.e(TAG, "相机错误 " + err);
                cam.close();
                device = null;
            }
        }, camH);
    }

    public synchronized void stop() {
        running = false;
        phase = "idle";
        try {
            if (session != null) session.close();
        } catch (Throwable ignored) {
        }
        try {
            if (device != null) device.close();
        } catch (Throwable ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignored) {
        }
        session = null;
        device = null;
        reader = null;
        try {
            RobotCtl.stop();
        } catch (Throwable ignored) {
        }
        try {
            RobotCtl.head(0f, 10f);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "视觉停止");
    }

    public String status() {
        return String.format("phase=%s running=%s cx=%.2f cy=%.2f area=%.3f act=%s",
                phase, running, cxNorm, cyNorm, area, lastAction);
    }

    public boolean isRunning() {
        return running;
    }

    // ---------- 控制循环 ----------
    private final Runnable control = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (now >= busyUntil) {
                try {
                    decide(now);
                } catch (Throwable t) {
                    Log.e(TAG, "控制异常", t);
                }
            }
            ctlH.postDelayed(this, 120);
        }
    };

    private void decide(long now) throws Exception {
        if (dryRun) {
            phase = "detect";
            return;
        }
        // 安全超时
        if (now - startAt > 90000) {
            phase = "timeout";
            stop();
            return;
        }
        if (now - lastFrameAt > 1500) {
            phase = "no-frame";
            return;
        }
        // 刚转过就丢了 → 先原路退回（右转不可靠时会转过头）
        if (area < 0.0015f && lastTurnDeg != 0f && now - lastTurnAt < 4000 && !undone) {
            undone = true;
            float back = -lastTurnDeg;
            if (back > 0) RobotCtl.turnRaw(back, 60);
            else RobotCtl.turnRaw(-back, -60);
            lastAction = "undo " + (int) back;
            phase = "undo";
            busyUntil = now + (long) (Math.abs(back) / 60f * 1000 * 2.0) + 600;
            return;
        }
        // 刚前进就丢了（目标跑到视野上方）→ 退一步再看
        if (area < 0.0015f && lastAction.startsWith("fwd") && now - lastTurnAt < 4000 && !undone) {
            undone = true;
            RobotCtl.move(12f, -6f);
            lastAction = "back 12";
            phase = "backoff";
            busyUntil = now + 2400;
            return;
        }
        if (area < 0.0015f) {
            // 看不到目标：小步左转搜索，最多转一圈
            phase = "searching";
            if (searchSteps > 24) {
                phase = "lost";
                stop();
                return;
            }
            searchSteps++;
            RobotCtl.turn(15, 60);
            lastAction = "search turn 15";
            busyUntil = now + 600;
            return;
        }
        searchSteps = 0;
        undone = false;
        float dx = cxNorm - 0.5f;
        if (Math.abs(dx) > deadband) {
            float deg = Math.min(12f, Math.abs(dx) * 50f);
            if (deg < 6f) deg = 6f;
            // 视觉闭环用「原始转向」：右转虽然不准，但下一帧会继续补正
            if (dx < 0) {
                RobotCtl.turnRaw(deg, 60);       // 目标在左
                lastAction = "turn L " + (int) deg;
                lastTurnDeg = deg;
                lastTurnAt = now;
                phase = "aiming";
                busyUntil = now + (long) (deg / 60f * 1000 * 1.3) + 500;
            } else {
                RobotCtl.turnRaw(deg, -60);      // 目标在右（右转不准，等待更久）
                lastAction = "turn R " + (int) deg;
                lastTurnDeg = -deg;
                lastTurnAt = now;
                phase = "aiming";
                busyUntil = now + (long) (deg / 60f * 1000 * 2.0) + 600;
            }
            return;
        }
        if (area < arriveArea) {
            float d = area < arriveArea * 0.3f ? 14f : 8f;
            RobotCtl.move(d, 8f);
            lastAction = "fwd " + (int) d;
            phase = "approaching";
            busyUntil = now + (long) (d / 8f * 1000) + 600;
            return;
        }
        phase = "arrived";
        lastAction = "arrived";
        busyUntil = now + 800;
    }

    // ---------- 颜色检测 ----------
    private static int colorIndex(String name) {
        if (name == null) return 0;
        String s = name.toLowerCase();
        if (s.startsWith("green") || s.contains("绿")) return 1;
        if (s.startsWith("blue") || s.contains("蓝")) return 2;
        return 0;
    }

    /** 返回 {cxNorm, cyNorm, areaRatio}；找不到目标时 cxNorm<0 */
    private static float[] detect(Image img, int color) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        ByteBuffer ub = p[1].getBuffer();
        ByteBuffer vb = p[2].getBuffer();
        int yStride = p[0].getRowStride();
        int uStride = p[1].getRowStride();
        int vStride = p[2].getRowStride();
        int uPix = p[1].getPixelStride();
        int vPix = p[2].getPixelStride();

        long sumX = 0, sumY = 0;
        int count = 0, total = 0;

        for (int y = 0; y < H; y += STEP) {
            int yRow = y * yStride;
            int cRow = (y / 2);
            for (int x = 0; x < W; x += STEP) {
                total++;
                int yi = yRow + x;
                if (yi >= yb.capacity()) continue;
                int Y = yb.get(yi) & 0xff;
                int ci = cRow * uStride + (x / 2) * uPix;
                int cvi = cRow * vStride + (x / 2) * vPix;
                if (ci >= ub.capacity() || cvi >= vb.capacity()) continue;
                int U = (ub.get(ci) & 0xff) - 128;
                int V = (vb.get(cvi) & 0xff) - 128;

                int r = clamp(Y + (int) (1.402f * V));
                int g = clamp(Y - (int) (0.344f * U) - (int) (0.714f * V));
                int b = clamp(Y + (int) (1.772f * U));

                int mx = Math.max(r, Math.max(g, b));
                int mn = Math.min(r, Math.min(g, b));
                if (mx < 60 || mx - mn < 38) continue;

                boolean hit;
                switch (color) {
                    case 1:
                        hit = (g == mx) && r < g * 0.85f && b < g * 0.85f;
                        break;
                    case 2:
                        hit = (b == mx) && r < b * 0.85f && g < b * 0.85f;
                        break;
                    default:
                        hit = (r == mx) && g < r * 0.85f && b < r * 0.85f;
                }
                if (hit) {
                    sumX += x;
                    sumY += y;
                    count++;
                }
            }
        }
        if (count < total * 0.002f) return new float[]{-1f, -1f, 0f};
        return new float[]{
                (sumX / (float) count) / W,
                (sumY / (float) count) / H,
                count / (float) total
        };
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
