# XRobot HTTP 接口（推荐调用方式）

中枢在机器人上监听 **`0.0.0.0:8899`**，任何语言发一个 HTTP 请求即可控制机器人，**不需要导入任何 Java 文件、不需要权限、不需要签名**。

这是一次性解决两个问题的方案：
- **零依赖调用** —— App / 脚本 / 命令行 / 网页 / 另一台设备都能调
- **多调用方不冲突** —— 所有动作进串行队列，同一时刻只执行一条；急停抢占

---

## 1. 连通

| 场景 | HOST 填什么 |
|---|---|
| 调用方就运行在机器人上（App / 本地脚本） | `127.0.0.1` |
| PC 通过 USB（先 `adb forward tcp:8899 tcp:8899`） | `127.0.0.1` |
| 同一 Wi-Fi 的另一台设备 / PC | 机器人的局域网 IP，如 `192.168.3.102` |

验证连通：浏览器打开 `http://<HOST>:8899/ping`，应看到：

```json
{"ok":true,"driver":"visbot","ready":true,"name":"优必选 小方头","drivers":"visbot"}
```

---

## 2. 接口一览

统一格式：`GET http://<HOST>:8899/<命令>?参数`

所有返回都是 JSON，成功含 `"ok":true`，失败含 `"ok":false` 和 `"msg"`。

### 查询类（不进队列，立即返回）

| 命令 | 返回要点 |
|---|---|
| `/ping` | `ok, driver, name, ready, drivers` |
| `/capabilities` | `capabilities[]`（当前机型支持的能力） |
| `/status` | `status`（一行状态文本）、`driver` |
| `/info` | `info`（设备参数） |
| `/queue` | `pending`（排队数）、`busy`、`gap_ms` |

### 动作类（进串行队列）

| 命令 | 参数 | 说明 |
|---|---|---|
| `/move` | `dist`=厘米(默认30), `speed`=厘米/秒(默认8) | **speed 负值=后退** |
| `/turn` | `angle`=度(默认90), `speed`=度/秒(默认60) | **正=左转，负=右转** |
| `/rawTurn` | 同上 | 原始转向，不换算，标调用 |
| `/head` | `angle`=-23~25(默认0), `speed`=默认0 | 抬头/低头 |
| `/detect` | `color`=red/green/blue(默认red) | 只检测目标，不驱动 |
| `/goto` | `color`=同上 | 视觉追踪并走过去（会切到中枢界面） |

动作命令返回示例：

```json
{"ok":true,"pending":1}
```

`pending` 是下发瞬间队列里的排队数，可据此判断是否有其他调用方在同时操作。

### 控制类

| 命令 | 说明 |
|---|---|
| `/stop` | **急停**：清空所有排队动作 + 立即停止底盘与视觉。返回 `cleared`（被清掉的动作数） |

### 通用写法

- 也支持 POST JSON：`POST /move`，body `{"dist":30,"speed":10}`
- 也支持 `/cmd?method=move&arg1=30&arg2=10`（兼容旧参数风格）
- 未知命令返回 `{"ok":false,"msg":"未知命令: xxx"}`

---

## 3. 多应用/多命令如何不冲突

中枢内部只有一个工作线程处理动作命令，所有动作按到达顺序逐条下发，**两条命令之间强制 300ms 最小间隔**（底层 locomote 是异步下发，太快会被截断）。

实测：瞬时并发 8 条命令，全部成功返回不同的 `pending` 序号（按序入队），无一条同时执行。

规则：

1. **动作串行** —— 无论多少个 App、多少条命令同时发，都排队逐条执行
2. **查询无锁** —— ping/status/info/queue 不进队列，任何时候立即返回，不影响正在执行的动作
3. **急停抢占** —— stop 不受队列阻塞，清空积压并立即生效
4. **能力检查** —— 调用当前机型不支持的能力返回明确错误，不会崩溃

建议：动作较多时用 `/queue` 监控积压；发现 `pending` 持续上涨说明调用过密，中枢会自动按序消化，但可考虑降低频率。

---

## 4. 各语言示例

仓库 `examples/` 目录提供可直接使用的模板：

| 文件 | 场景 |
|---|---|
| `xrobot.py` | Python 客户端类（零依赖标准库），`from xrobot import XRobot` |
| `curl-示例.md` | 命令行 / 浏览器 / PowerShell，含 adb forward 与 Wi-Fi 两种连法 |
| `XRobotHttp.java` | Android App 内用，仅依赖 `HttpURLConnection` + 系统 `org.json` |
| `xrobot.js` | Node.js / 浏览器（内置 fetch，中枢已开 CORS） |

---

## 5. 保活与前提

- 中枢是前台服务（`HubService`），进程常驻，HTTP 端口长期可用
- 需要机器人已装中枢 APK（平台签名，见 [部署说明](部署说明.md)）且已 root
- 开机后需等底层 SDK 就绪（约几秒），`ping` 的 `ready` 为 `true` 后即可调用；未就绪时动作会返回明确错误，重试即可
