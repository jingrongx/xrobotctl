# XRobot 控制中枢

一个 Android 中枢服务：让**任何程序用最简单的方式**控制机器人，屏蔽底层 SDK、签名、硬件差异。

- **首选：本地 HTTP 服务** —— 任何语言发个 HTTP 请求即可，零依赖、不冲突
- 兼容：ContentProvider 与 `XRobot.java` 客户端（老调用方不用改）
- 目前内置 **优必选「小方头」** 驱动；架构为多机器人设计，加新型号只需实现一个驱动接口

---

## 30 秒上手（HTTP，推荐）

### 1. 装中枢

把 `hub/out/XRobotHub.apk` 装到机器人上（需已 root，详见 [部署说明](docs/部署说明.md)）。装完打开一次「XRobot 中枢」，HTTP 服务（端口 8899）即启动并保持常驻。

### 2. 连通

```bash
adb forward tcp:8899 tcp:8899     # PC 通过 USB；调用方在机器人上则跳过这步
```

### 3. 调用（任选语言）

```bash
curl "http://127.0.0.1:8899/ping"                    # 看 ready=true
curl "http://127.0.0.1:8899/move?dist=30&speed=10"   # 前进30cm
curl "http://127.0.0.1:8899/turn?angle=90"           # 左转90°
curl "http://127.0.0.1:8899/head?angle=25"           # 抬头
curl "http://127.0.0.1:8899/stop"                    # 急停
```

```python
from xrobot import XRobot            # examples/xrobot.py，零依赖
r = XRobot("127.0.0.1")
r.move(30, 10); r.turn(90); r.head(25); r.stop()
```

**就这样——不用导入任何机器人代码，不用权限，不用签名。** 浏览器直接开 `http://<HOST>:8899/ping` 也能看状态。

---

## 为什么用 HTTP（而不是拷 XRobot.java）

| | HTTP 服务（推荐） | ContentProvider / XRobot.java |
|---|---|---|
| 依赖 | 无，任何语言的标准库即可 | 需拷 Java 文件 / jar 进工程 |
| 调用方 | App、脚本、CLI、网页、其他设备 | 仅 Java/Android App |
| 调试 | 浏览器/curl 直接看 JSON | 需写代码或 `content call` |
| 多调用方冲突 | 中枢串行队列统一解决 | 各调用方各自处理 |
| 跨机器人 | 调用方无感 | 换 authority 需改代码 |

两者都走**同一个串行命令队列**，所以可以混用：老 App 继续用 Provider，新代码用 HTTP，互不冲突。

---

## 架构

```
任意调用方 ──HTTP :8899──> LocalHttpServer ──┐
老 App     ──Provider────> HubProvider     ──┤
旅游宝     ──content call─┘                  │
                              ┌──────────────┴─────────────┐
                              │  CommandQueue（串行队列）     │
                              │  · 动作逐条下发 + 300ms 间隔  │
                              │  · 查询无锁立即返回           │
                              │  · stop 抢占清空队列          │
                              └──────────────┬─────────────┘
                                             │ 能力检查
                                        DriverRegistry 选驱动
                                             │
                                       RobotDriver 接口
                                             │
                                    VisbotDriver（小方头）
                                             │
                                     UBTECH SDK（反优化 dex）
                                             │
                                       底盘 MCU (ttyMT1)
```

分层的好处：
- **调用方零依赖**：HTTP 是通用协议，任何语言原生支持
- **多调用方不打架**：串行队列在中枢侧一次性解决并发冲突
- **就绪无需等待**：中枢在动作下发前自动等 SDK 就绪（最长 8 秒），调用方直接发命令即可
- **换机器人不改调用方**：驱动能力自动适配
- **能力可发现**：`/capabilities` 让调用方运行时知道能做什么，优雅降级

**延迟**（实测，adb forward）：查询 ~10-20ms；动作默认异步 ~10ms 入队即返回；加 `wait=1` 同步等执行完 ~300ms。延迟主要来自 300ms 命令间隔（保护底层），不是网络。高频导航用异步，关键动作用 `wait=1` 拿 `executed:true` 确认。

---

## 支持的机器人

| 驱动 id | 机型 | 能力 |
|---|---|---|
| `visbot` | 优必选 小方头 | move / turn / head / vision / status |

新增机型见 [新增机器人驱动](docs/新增机器人驱动.md)。

---

## 目录结构

```
client/            旧版客户端 SDK（Provider 方式，仍可用）
  src/.../XRobot.java
hub/               中枢服务 APK
  AndroidManifest.xml
  src/.../hub/     中枢：HTTP服务 / 命令队列 / Provider / 驱动接口 / 注册表
  src/.../driver/  各机型驱动
  src/com/visbot/  小方头底层（SDK 反射、运动、视觉）
examples/          各语言零依赖调用示例（Python/curl/Java/JS）
tools/             构建与部署脚本
keys/              平台签名（不入库）
docs/              文档
```

---

## 构建

```bash
./tools/build_client.sh    # 旧版 Provider 客户端 jar（可选）
./tools/build_hub.sh       # 中枢 APK（需平台签名）→ hub/out/XRobotHub.apk
```

## 自动发版

**推送到 `main` 即自动发版**，无需手动打包或上传：

- 自动按版本号递增（首次 `v1.0.0`，之后 patch +1），打 tag、建 Release
- 自动汇总本次提交生成 [更新日志](CHANGELOG.md) 作为 Release Notes
- 自动构建带平台签名的 `XRobotHub-<版本>.apk` 作为附件
- 直接从 Release 下载 APK 即可安装，不必本地跑构建脚本

配置（仅一次）：在仓库 **Settings → Secrets and variables → Actions** 添加两个 Secret：

| Secret | 内容 |
|---|---|
| `PLATFORM_PK8_B64` | `keys/platform.pk8` 的 base64 |
| `PLATFORM_X509_B64` | `keys/platform.x509.pem` 的 base64 |

生成命令：`base64 -w0 keys/platform.pk8`、`base64 -w0 keys/platform.x509.pem`。
不配也能发版，但 APK 是 debug 签名、拿不到底盘权限。详见 [发版说明](docs/发版说明.md)。

## 详细文档

- **[HTTP 接口](docs/HTTP接口.md)** — 命令清单、参数、并发规则（首选）
- [示例集](examples/) — Python / curl / Java / JS 零依赖模板
- [客户端集成指南](docs/客户端集成.md) — Provider 方式（旧版）
- [新增机器人驱动](docs/新增机器人驱动.md) — 如何接入新机型
- [部署说明](docs/部署说明.md) — root、平台签名、SDK dex、常驻要点
- [发版说明](docs/发版说明.md) — 自动发版流程、版本规则、Secrets 配置
- [命令协议](docs/命令协议.md) — Provider 方法与参数
