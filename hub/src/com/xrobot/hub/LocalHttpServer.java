package com.xrobot.hub;

import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 本地 HTTP 服务 —— 任何语言、任何 App 零依赖调用机器人。
 *
 * 监听 0.0.0.0:8899（本机 App、adb forward、同一 Wi-Fi 的设备都可访问）。
 * 路径即命令：
 *   GET  /ping                     中枢状态
 *   GET  /capabilities             能力列表
 *   GET  /status  /info  /queue    状态 / 参数 / 队列
 *   GET  /move?dist=30&speed=10    前进（speed 负值=后退）
 *   GET  /turn?angle=90&speed=60   左转（负值=右转）
 *   GET  /head?angle=25            抬头
 *   GET  /stop                     急停（抢占，清空排队动作）
 *   GET  /detect?color=red         只检测不驱动
 *   GET  /goto?color=red           视觉寻物并走过去
 *
 * 也支持 POST JSON： {"command":"move","dist":30,"speed":10}
 * 统一返回 JSON： {"ok":true, ...}；失败 {"ok":false,"msg":"..."}
 */
public final class LocalHttpServer {

    private static final String TAG = "XRobotHttp";
    public static final int PORT = 8899;

    private static final LocalHttpServer INSTANCE = new LocalHttpServer();
    public static LocalHttpServer get() { return INSTANCE; }

    private volatile ServerSocket server;
    private volatile boolean running = false;

    public synchronized void start() {
        if (running) return;
        new Thread(acceptLoop, "xrobot-http-accept").start();
        Log.i(TAG, "HTTP 服务启动，端口 " + PORT);
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        Log.i(TAG, "HTTP 服务已停止");
    }

    public boolean isRunning() { return running; }

    private final Runnable acceptLoop = new Runnable() {
        @Override public void run() {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("0.0.0.0", PORT));
                running = true;
                while (running) {
                    final Socket client = server.accept();
                    // 每条连接独立线程，命令本身由 CommandQueue 串行化，不会冲突
                    new Thread(new Runnable() {
                        @Override public void run() { handle(client); }
                    }, "xrobot-http-conn").start();
                }
            } catch (Throwable t) {
                running = false;
                Log.e(TAG, "HTTP 服务异常退出", t);
            }
        }
    };

    private void handle(Socket client) {
        try {
            client.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), Charset.forName("UTF-8")));

            String requestLine = in.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) { close(client); return; }
            String[] first = requestLine.split(" ");
            String httpMethod = first.length > 0 ? first[0].toUpperCase(Locale.US) : "GET";
            String target = first.length > 1 ? first[1] : "/";

            // 读请求头，取 Content-Length
            int contentLength = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }
            StringBuilder body = new StringBuilder();
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int n = in.read(buf, 0, contentLength);
                if (n > 0) body.append(buf, 0, n);
            }

            // 拆路径与 query
            String path = target;
            Map<String, String> params = new HashMap<String, String>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                parseQuery(target.substring(q + 1), params);
            }
            // POST body：支持 JSON 与 form-urlencoded，合并进参数（body 优先）
            if (body.length() > 0) parseBody(body.toString(), params);

            // 命令名：优先 query 的 command，否则取路径首段
            String command = params.get("command");
            String seg = path.split("/").length > 1 ? path.split("/")[1] : "";
            if ((command == null || command.isEmpty()) && !seg.isEmpty()) command = seg;
            if (command == null) command = "";
            command = command.toLowerCase(Locale.US);
            if ("cmd".equals(command)) command = String.valueOf(params.containsKey("method") ? params.get("method") : "").toLowerCase(Locale.US);

            // 统一参数名
            float a = f(param(params, "dist", "angle", "arg1", "a"), def1(command));
            float b = f(param(params, "speed", "arg2", "b"), def2(command));
            String color = param(params, "color", "arg");
            boolean wait = isTrue(params.get("wait"));

            Bundle result = CommandQueue.get().exec(command, a, b, color, wait);
            String json = bundleToJson(result);

            byte[] payload = json.getBytes(Charset.forName("UTF-8"));
            OutputStream os = client.getOutputStream();
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                    + "Access-Control-Allow-Headers: Content-Type\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(header.getBytes(Charset.forName("US-ASCII")));
            os.write(payload);
            os.flush();
        } catch (Throwable t) {
            Log.w(TAG, "处理请求异常", t);
            try {
                byte[] payload = ("{\"ok\":false,\"msg\":" + quote("请求处理失败: " + t.getMessage()) + "}")
                        .getBytes(Charset.forName("UTF-8"));
                OutputStream os = client.getOutputStream();
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(Charset.forName("US-ASCII")));
                os.write(payload);
                os.flush();
            } catch (Throwable ignored) {}
        } finally {
            close(client);
        }
    }

    /** 从多个候选键里取第一个存在的值 */
    private static String param(Map<String, String> p, String... keys) {
        for (String k : keys) {
            String v = p.get(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static boolean isTrue(String s) {
        return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
    }

    /** 解析 POST body：JSON 对象或 form-urlencoded，键统一小写 */
    private static void parseBody(String body, Map<String, String> out) {
        if (body == null || body.trim().isEmpty()) return;
        String b = body.trim();
        if (b.startsWith("{")) {
            // 简单 JSON 对象：{"key":value, ...}，value 为数字/字符串/布尔
            String inner = b.substring(1, Math.max(1, b.length() - 1));
            for (String pair : inner.split(",")) {
                int i = pair.indexOf(':');
                if (i < 0) continue;
                String k = unquote(pair.substring(0, i).trim()).toLowerCase(Locale.US);
                String v = unquote(pair.substring(i + 1).trim());
                if (!k.isEmpty()) out.put(k, v);
            }
        } else {
            parseQuery(b, out);
        }
    }

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static float f(String s, float def) {
        if (s == null) return def;
        try { return Float.parseFloat(s.trim()); } catch (Throwable t) { return def; }
    }

    private static float def1(String method) {
        if ("move".equals(method)) return 30f;
        if ("head".equals(method)) return 0f;
        return 90f;
    }

    private static float def2(String method) {
        if ("move".equals(method)) return 8f;
        if ("head".equals(method)) return 0f;
        return 60f;
    }

    private static void parseQuery(String q, Map<String, String> out) {
        if (q == null) return;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            String v = i >= 0 ? pair.substring(i + 1) : "";
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Throwable t) {
                out.put(k, v);
            }
        }
    }

    /** Bundle → JSON */
    private static String bundleToJson(Bundle b) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String key : b.keySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(quote(key)).append(":");
            Object v = b.get(key);
            if (v instanceof Boolean) sb.append(v);
            else if (v instanceof Number) sb.append(v);
            else if (v instanceof String[]) {
                sb.append("[");
                String[] arr = (String[]) v;
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(quote(arr[i]));
                }
                sb.append("]");
            } else sb.append(quote(String.valueOf(v)));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append("\"").toString();
    }

    private static void close(Socket s) {
        try { if (s != null && !s.isClosed()) s.close(); } catch (IOException ignored) {}
    }

    private LocalHttpServer() {}
}
