#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
XRobot Python 客户端 —— 零依赖，只用标准库。
无论 App、脚本、PC、树莓派，只要能访问中枢 IP 就能控制机器人。

用法：
    from xrobot import XRobot
    r = XRobot("127.0.0.1")          # 设备上用 127.0.0.1；PC 走 adb forward 也是它
    r.move(30, 10)                    # 前进 30cm
    r.move(30, -10)                   # 后退
    r.turn(90)                        # 左转 90°
    r.turn(-90)                       # 右转 90°
    r.head(23)                        # 抬头（正=抬头，+23 极限；负=低头，-25 极限）
    r.stop()                          # 急停
    r.goto_color("red")               # 找红色目标并走过去
    print(r.status())
"""
import json
import urllib.parse
import urllib.request


class XRobot:
    def __init__(self, host="127.0.0.1", port=8899, timeout=10):
        self.base = "http://%s:%d" % (host, port)
        self.timeout = timeout

    def call(self, command, **params):
        query = urllib.parse.urlencode(params)
        url = self.base + "/" + command + ("?" + query if query else "")
        try:
            with urllib.request.urlopen(url, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            return {"ok": False, "msg": str(e)}

    # ----- 动作 -----
    def move(self, dist=30, speed=8, wait=False):
        """前进；speed 为负即后退。wait=True 时等到真正执行完才返回"""
        return self.call("move", dist=dist, speed=speed, wait=int(wait))

    def turn(self, angle=90, speed=60, wait=False):
        """原地转向；正=左，负=右"""
        return self.call("turn", angle=angle, speed=speed, wait=int(wait))

    def head(self, angle=0, speed=0, wait=False):
        """头部俯仰，正=抬头（+23 极限）、负=低头（-25 极限）"""
        return self.call("head", angle=angle, speed=speed, wait=int(wait))

    def stop(self):
        """急停（抢占并清空排队动作）"""
        return self.call("stop")

    def goto_color(self, color="red", wait=False):
        """视觉寻物并走过去"""
        return self.call("goto", color=color, wait=int(wait))

    def detect(self, color="red", wait=False):
        """只检测目标，不驱动"""
        return self.call("detect", color=color, wait=int(wait))

    # ----- 查询 -----
    def ping(self):
        return self.call("ping")

    def capabilities(self):
        return self.call("capabilities")

    def status(self):
        return self.call("status")

    def info(self):
        return self.call("info")

    def queue(self):
        return self.call("queue")


if __name__ == "__main__":
    r = XRobot()
    print("ping:", r.ping())
    print("capabilities:", r.capabilities())
    # 演示：抬头再回正（不移动，安全）
    print("head(20):", r.head(20))
    print("head(0):", r.head(0))
    print("status:", r.status())
