# XRobot HTTP 调用示例（纯命令行，零依赖）

中枢在机器人上监听 `0.0.0.0:8899`。下面命令在**能访问到机器人 IP 的任意设备**上执行。

## 连接准备

方式一：本机（在机器人上，或用 adb forward）

```bash
adb forward tcp:8899 tcp:8899        # PC 上执行一次，把本机 8899 转发到设备
HOST=127.0.0.1
```

方式二：同一 Wi-Fi 下直接访问

```bash
HOST=192.168.x.x                      # 机器人的实际 IP
```

## curl 调用

```bash
# 查询
curl "http://$HOST:8899/ping"
curl "http://$HOST:8899/capabilities"
curl "http://$HOST:8899/status"
curl "http://$HOST:8899/queue"

# 动作
curl "http://$HOST:8899/move?dist=30&speed=10"     # 前进30cm
curl "http://$HOST:8899/move?dist=30&speed=-10"    # 后退30cm
curl "http://$HOST:8899/turn?angle=90&speed=60"    # 左转90°
curl "http://$HOST:8899/turn?angle=-90&speed=60"   # 右转90°
curl "http://$HOST:8899/head?angle=25"             # 抬头25°
curl "http://$HOST:8899/stop"                      # 急停
curl "http://$HOST:8899/goto?color=red"            # 找红色目标并走过去

# wait=1：等到动作真正执行完才返回（确认执行，~300ms）
curl "http://$HOST:8899/turn?angle=90&wait=1"
# 返回 {"ok":true,"executed":true,"waited_ms":0,"pending":0,"mode":"sync"}

# POST JSON（等价写法）
curl -X POST "http://$HOST:8899/move" -H "Content-Type: application/json" \
     -d '{"dist":30,"speed":10,"wait":true}'
```

返回示例：

```json
{"ok":true,"pending":1}
{"ok":true,"driver":"visbot","ready":true,"name":"优必选 小方头"}
```

失败时：`{"ok":false,"msg":"驱动 visbot 不支持能力: xxx"}`

## 浏览器直接调

在浏览器地址栏输入 `http://192.168.x.x:8899/ping` 就能看到 JSON——调试极其方便，不需要任何工具。

## Windows PowerShell 调用

```powershell
(Invoke-RestMethod "http://127.0.0.1:8899/ping") | ConvertTo-Json
Invoke-RestMethod "http://127.0.0.1:8899/move?dist=30&speed=10"
```

## 速度与就绪

- **不用先 ping 等就绪**：中枢在动作下发前会自动等底层 SDK（最长 8 秒），直接发命令即可
- **异步（默认）**：~10ms 返回，`mode:queued`，适合高频连续调用
- **同步（`wait=1`）**：~300ms 返回，带 `executed:true`，适合要确认执行的关键动作
- 查询类（ping/status/queue）~10-20ms

延迟主要来自 300ms 命令间隔（保护底层），不是网络。用 `/queue` 的 `pending` 看积压。

## 多调用方不冲突

所有动作命令在中枢内部走**串行队列**（最小间隔 300ms），所以：

- 多个 App / 多条命令同时发，会按到达顺序逐条执行，不会同时驱动底盘
- 查询命令（ping/status/queue）不进队列，永远立即返回
- `stop` 是抢占式的：会清空所有排队动作并立即急停

可以先用 `/queue` 观察当前积压：

```bash
curl "http://127.0.0.1:8899/queue"
# {"gap_ms":300,"pending":0,"busy":false,"ok":true}
```
