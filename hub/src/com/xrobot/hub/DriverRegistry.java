package com.xrobot.hub;

import java.util.ArrayList;
import java.util.List;

/**
 * 驱动注册表。当前设备只有一个驱动，但结构为多机器人预留：
 * 按优先级排序，中枢总是取第一个 isReady() 的驱动。
 *
 * 新增机器人：实现 RobotDriver，在 HubApp.onCreate 里 register(new XxxDriver())。
 */
public final class DriverRegistry {

    private static final List<RobotDriver> DRIVERS = new ArrayList<RobotDriver>();

    private DriverRegistry() {
    }

    public static synchronized void register(RobotDriver driver) {
        if (driver == null) return;
        for (RobotDriver d : DRIVERS) {
            if (d.id().equals(driver.id())) return;   // 去重
        }
        DRIVERS.add(driver);
    }

    /** 当前生效的驱动（第一个就绪的）；都不就绪时返回第一个，便于上层报"SDK 未就绪" */
    public static synchronized RobotDriver active() {
        for (RobotDriver d : DRIVERS) {
            if (d.isReady()) return d;
        }
        return DRIVERS.isEmpty() ? null : DRIVERS.get(0);
    }

    public static synchronized RobotDriver find(String id) {
        for (RobotDriver d : DRIVERS) {
            if (d.id().equals(id)) return d;
        }
        return null;
    }

    public static synchronized String[] ids() {
        String[] out = new String[DRIVERS.size()];
        for (int i = 0; i < DRIVERS.size(); i++) out[i] = DRIVERS.get(i).id();
        return out;
    }

    public static synchronized String describe() {
        if (DRIVERS.isEmpty()) return "no driver registered";
        StringBuilder sb = new StringBuilder();
        for (RobotDriver d : DRIVERS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(d.id()).append("(").append(d.displayName()).append(")");
            sb.append(d.isReady() ? ":ready" : ":not-ready");
        }
        return sb.toString();
    }
}
