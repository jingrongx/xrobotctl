package com.xrobot.hub.driver;

import android.content.Context;

import com.visbot.robotctl.RobotCtl;
import com.visbot.robotctl.Sdk;
import com.visbot.robotctl.VisionCtl;
import com.xrobot.hub.RobotDriver;

/**
 * 优必选「小方头」驱动。
 *
 * 把该机型的全部特性封在这里：平台签名、反优化 dex、右转打滑换算、
 * 视觉闭环跟踪等。中枢和其它驱动都不需要知道这些细节。
 */
public final class VisbotDriver implements RobotDriver {

    public static final String ID = "visbot";

    @Override public String id() { return ID; }
    @Override public String displayName() { return "优必选 小方头"; }

    @Override
    public String[] capabilities() {
        return new String[]{"move", "turn", "head", "vision", "status"};
    }

    @Override
    public boolean isReady() {
        return Sdk.context() != null;
    }

    @Override public void move(float dist, float speed) { RobotCtl.move(dist, speed); }
    @Override public void turn(float angle, float speed) { RobotCtl.turn(angle, speed); }
    @Override public void turnRaw(float angle, float speed) { RobotCtl.turnRaw(angle, speed); }
    @Override public void head(float angle, float speed) { RobotCtl.head(angle, speed); }
    @Override public void stop() { RobotCtl.stop(); }

    @Override
    public String status() {
        return "moving=" + RobotCtl.isLocomoting() + ", " + VisionCtl.get().status();
    }

    @Override
    public String info() {
        return RobotCtl.deviceInfo();
    }

    @Override
    public void startVision(Context ctx, String color, boolean dry) {
        try {
            VisionCtl.get().start(ctx.getApplicationContext(), color, dry);
        } catch (Throwable t) {
            throw new RuntimeException("启动视觉失败: " + t.getMessage(), t);
        }
    }

    @Override
    public void stopVision() {
        VisionCtl.get().stop();
    }
}
