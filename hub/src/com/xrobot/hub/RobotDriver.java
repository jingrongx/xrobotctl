package com.xrobot.hub;

import android.content.Context;

/**
 * 机器人驱动契约。
 *
 * 中枢只认这个接口，不关心底层是哪家机器人。要支持新型号，
 * 只需实现本接口并在 {@link DriverRegistry} 注册，中枢的对外 API 一行都不用改。
 *
 * 约定：
 * - 所有动作方法非阻塞下发，立即返回；失败抛 RuntimeException。
 * - 距离单位厘米，角度单位度，速度单位由驱动自己定义（文档写明）。
 * - 不支持的能力不要实现抛异常，而是从 capabilities() 里省略，
 *   中枢会返回明确的 "not supported" 而不是崩溃。
 */
public interface RobotDriver {

    /** 唯一标识，如 "visbot"、"dog"、"arm" */
    String id();

    /** 人类可读名称，如 "优必选 小方头" */
    String displayName();

    /** 支持的能力集合：move / turn / head / vision / status */
    String[] capabilities();

    /** 底层 SDK 是否就绪 */
    boolean isReady();

    /** 前进/后退：dist 恒为正，speed 负值表示后退 */
    void move(float dist, float speed);

    /** 原地转向：正=左，负=右（驱动内部决定如何换算） */
    void turn(float angle, float speed);

    /** 原始转向，不做方向换算，仅用于标定/视觉闭环 */
    void turnRaw(float angle, float speed);

    /** 头部俯仰，范围见各驱动文档 */
    void head(float angle, float speed);

    /** 急停 */
    void stop();

    /** 一行状态摘要 */
    String status();

    /** 设备参数摘要 */
    String info();

    /** 启动视觉，拉起必要的界面；dry=true 时只检测不驱动 */
    void startVision(Context ctx, String color, boolean dry);

    /** 停止视觉 */
    void stopVision();
}
