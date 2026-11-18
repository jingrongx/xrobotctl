/*
 * XRobot HTTP 客户端 —— Android App 里零依赖调用，不用拷贝任何机器人代码。
 * 只依赖 JDK/Android 自带的 java.net.HttpURLConnection，把这个类丢进工程即可。
 *
 * 用法（Activity 里，网络请求放子线程）：
 *   XRobotHttp robot = new XRobotHttp("127.0.0.1");   // 设备上
 *   robot.move(30, 10);
 *   robot.turn(90);
 *   JSONObject st = robot.status();
 */
package com.example;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class XRobotHttp {
    private final String base;

    public XRobotHttp(String host) { this(base(host, 8899)); }
    public XRobotHttp(String host, int port) { this.base = "http://" + host + ":" + port; }
    private XRobotHttp(String base) { this.base = base; }

    /** 通用调用，返回中枢 JSON（含 ok/msg）。不要在 UI 线程调用。 */
    public JSONObject call(String command, String... kv) {
        try {
            StringBuilder q = new StringBuilder();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (q.length() > 0) q.append('&');
                q.append(URLEncoder.encode(kv[i], "UTF-8")).append('=')
                 .append(URLEncoder.encode(kv[i + 1], "UTF-8"));
            }
            String url = base + "/" + command + (q.length() > 0 ? "?" + q : "");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            c.disconnect();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            try {
                return new JSONObject().put("ok", false).put("msg", e.getMessage());
            } catch (Exception ignored) { return null; }
        }
    }

    public JSONObject move(float dist, float speed) { return call("move", "dist", "" + dist, "speed", "" + speed); }
    public JSONObject move(float dist) { return move(dist, 8f); }
    public JSONObject turn(float angle, float speed) { return call("turn", "angle", "" + angle, "speed", "" + speed); }
    public JSONObject turn(float angle) { return turn(angle, 60f); }
    public JSONObject head(float angle) { return call("head", "angle", "" + angle); }
    public JSONObject stop() { return call("stop"); }
    public JSONObject gotoColor(String color) { return call("goto", "color", color); }
    public JSONObject ping() { return call("ping"); }
    public JSONObject status() { return call("status"); }
    public JSONObject queue() { return call("queue"); }
}
