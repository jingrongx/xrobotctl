# XRobot 控制中枢

一个 Android 中枢服务：让**任何 App 用一行代码**控制机器人，屏蔽底层 SDK、签名、硬件差异。

目前内置 **优必选「小方头」** 驱动；架构为多机器人设计，加新型号只需实现一个驱动接口，调用方代码不变。

---

## 30 秒上手

### 1. 装中枢

把 `hub/out/XRobotHub.apk` 装到机器人上（需已 root，详见 [部署说明](docs/部署说明.md)）。装完打开一次「XRobot 中枢」，确认自检页显示 `ready`。

### 2. 你的 App 里集成客户端

把 `client/src/com/xrobot/client/XRobot.java` **一个文件**拷进你的工程（或引用 `client/out/xrobot-client.jar`）。不需要权限、不需要初始化。

### 3. 调用

```java
import com.xrobot.client.XRobot;

XRobot robot = XRobot.get(context);

robot.move(30, 10);      // 前进 30cm，速度 10cm/s
robot.move(30, -10);     // 后退 30cm
robot.turn(90, 60);      // 左转 90°
robot.turn(-90, 60);     // 右转 90°
robot.head(25);          // 抬头 25°
robot.stop();            // 急停

robot.gotoColor("red");  // 摄像头找红色目标并走过去
```

每个调用返回 `XRobot.Result`，`result.ok` 判断是否成功，`result.msg` 是失败原因。查询类：`robot.ping()` / `robot.capabilities()` / `robot.status()` / `robot.info()`。

**就这么简单**——没有 Bundle、没有 Uri、没有权限声明、没有跨进程样板代码。

---

## 架构

```
你的 App  ──XRobot.java──┐
旅游宝     ──XRobot.java──┼──> HubProvider（统一命令协议）
自动化脚本 ──content call─┘         │
                                    ├─ 能力检查 / 参数校验 / 错误封装
                                    ├─ DriverRegistry 选驱动
                                    └─ RobotDriver 接口
                                          │
                                    VisbotDriver（小方头）
                                          │
                                    UBTECH SDK（反优化 dex）
                                          │
                                    底盘 MCU (ttyMT1)
```

分层的好处：
- **调用方零依赖**：client 只有一个 Java 文件，不碰任何机器人细节
- **换机器人不改调用方**：中枢根据驱动能力自动适配
- **能力可发现**：`capabilities()` 让 App 在运行时知道能做什么，优雅降级

---

## 支持的机器人

| 驱动 id | 机型 | 能力 |
|---|---|---|
| `visbot` | 优必选 小方头 | move / turn / head / vision |

新增机型见 [新增机器人驱动](docs/新增机器人驱动.md)。

---

## 目录结构

```
client/            客户端 SDK（拷这一个文件就能调用）
  src/.../XRobot.java
hub/               中枢服务 APK
  AndroidManifest.xml
  src/.../hub/      中枢：Provider / 驱动接口 / 注册表
  src/.../driver/   各机型驱动
  src/com/visbot/   小方头底层（SDK 反射、运动、视觉）
tools/             构建与部署脚本
keys/              平台签名（不入库）
docs/              文档
```

---

## 构建

```bash
./tools/build_client.sh    # 生成 client/out/xrobot-client.jar
./tools/build_hub.sh       # 生成 hub/out/XRobotHub.apk（需平台签名）
```

## 详细文档

- [客户端集成指南](docs/客户端集成.md) — 各语言/场景调用示例、错误处理
- [新增机器人驱动](docs/新增机器人驱动.md) — 如何接入新机型
- [部署说明](docs/部署说明.md) — root、平台签名、SDK dex、常驻要点
- [命令协议](docs/命令协议.md) — Provider 方法与参数（命令行调试用）
