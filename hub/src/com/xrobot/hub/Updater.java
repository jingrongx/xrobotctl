package com.xrobot.hub;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.app.PendingIntent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 应用内自动更新：检测 GitHub Release → 多线路测速 → 流式下载 → 安装。
 *
 * 安装策略：
 * - 有 root：su 调 pm install -r 静默安装；
 * - 无 root / root 失败：用 PackageInstaller 会话提交，弹出系统确认，由用户手动点安装。
 *
 * 仓库是公开的，官方源和国内镜像都能匿名访问；逐条探测取响应最快的一条下载。
 */
public final class Updater {

    public static final String TAG = "XRobotUpdater";

    private static final String REPO_OWNER = "jingrongx";
    private static final String REPO_NAME = "xrobotctl";
    private static final String API_LATEST =
            "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest";

    /** 预置的国内镜像前缀，会把官方 asset 地址拼在后面 */
    private static final String[] MIRROR_PREFIXES = new String[]{
            "",                          // 官方源
            "https://ghfast.top/",       // 国内镜像 1
            "https://gh-proxy.com/",     // 国内镜像 2
            "https://ghproxy.net/"       // 国内镜像 3
    };

    private static final int TIMEOUT_MS = 6000;
    private static final String PREF = "xrobot_update";

    // —— 设置项 ——
    private static final String K_AUTO_CHECK = "auto_check";
    private static final String K_AUTO_INSTALL = "auto_install";
    private static final String K_ROOT_SILENT = "root_silent";
    private static final String K_PROXY = "proxy_prefix";
    private static final String K_SKIP_TAG = "skip_tag";

    /** 一次更新的信息 */
    public static final class Release {
        public String tag;          // v1.0.2
        public String versionName;  // 1.0.2
        public int versionCode;     // 10002
        public String notes;        // release body
        public String assetUrl;     // 官方 browser_download_url
        public long size;           // 字节
        public String assetName;    // XRobotHub-v1.0.2.apk
    }

    /** 测速结果 */
    public static final class Line {
        public String url;
        public String label;
        public long latencyMs;
        public boolean ok;
    }

    /** 进度回调，运行在主线程 */
    public interface Callback {
        void onStatus(String msg);
        void onProgress(int percent);
        void onDone(boolean success, String msg);
    }

    private final Context ctx;

    public Updater(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ————————————————— 设置读写 —————————————————

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public boolean autoCheck() { return prefs().getBoolean(K_AUTO_CHECK, true); }
    public boolean autoInstall() { return prefs().getBoolean(K_AUTO_INSTALL, true); }
    public boolean rootSilent() { return prefs().getBoolean(K_ROOT_SILENT, true); }
    public String customProxy() { return prefs().getString(K_PROXY, ""); }
    public String skipTag() { return prefs().getString(K_SKIP_TAG, ""); }

    public void setAutoCheck(boolean v) { prefs().edit().putBoolean(K_AUTO_CHECK, v).apply(); }
    public void setAutoInstall(boolean v) { prefs().edit().putBoolean(K_AUTO_INSTALL, v).apply(); }
    public void setRootSilent(boolean v) { prefs().edit().putBoolean(K_ROOT_SILENT, v).apply(); }
    public void setCustomProxy(String v) { prefs().edit().putString(K_PROXY, v == null ? "" : v.trim()).apply(); }
    public void setSkipTag(String v) { prefs().edit().putString(K_SKIP_TAG, v == null ? "" : v).apply(); }

    // ————————————————— 版本 —————————————————

    /** 本机版本名，如 1.0.1 */
    public String localVersionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0.0.0";
        }
    }

    public int localVersionCode() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** v1.0.2 → code 10002；取 tag 里的数字，兼容 1.0.2 / v1.0.2 */
    public static int codeOfTag(String tag) {
        if (tag == null) return 0;
        String s = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        int major = 0, minor = 0, patch = 0;
        try {
            String[] parts = s.split("[.\\-]");
            if (parts.length > 0) major = parseInt(parts[0]);
            if (parts.length > 1) minor = parseInt(parts[1]);
            if (parts.length > 2) patch = parseInt(parts[2]);
        } catch (Throwable ignored) {
        }
        return major * 10000 + minor * 100 + patch;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; }
    }

    public boolean isNewer(Release r) {
        if (r == null || r.versionCode <= 0) return false;
        if (r.tag.equals(skipTag())) return false;
        return r.versionCode > localVersionCode();
    }

    // ————————————————— 1. 检测 —————————————————

    /** 同步检测，请在子线程调用 */
    public Release check() throws Exception {
        byte[] body = httpGet(API_LATEST, TIMEOUT_MS);
        JSONObject obj = new JSONObject(new String(body, "UTF-8"));
        Release r = new Release();
        r.tag = obj.optString("tag_name", "");
        r.versionName = r.tag.startsWith("v") ? r.tag.substring(1) : r.tag;
        r.versionCode = codeOfTag(r.tag);
        r.notes = obj.optString("body", "");
        if (r.notes.length() > 300) r.notes = r.notes.substring(0, 300) + "…";

        JSONArray assets = obj.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                    r.assetName = name;
                    r.assetUrl = a.optString("browser_download_url", "");
                    r.size = a.optLong("size", 0);
                    break;
                }
            }
        }
        if (r.assetUrl == null || r.assetUrl.length() == 0) {
            // 兜底：按约定地址拼
            r.assetName = "XRobotHub-" + r.tag + ".apk";
            r.assetUrl = "https://github.com/" + REPO_OWNER + "/" + REPO_NAME
                    + "/releases/download/" + r.tag + "/" + r.assetName;
        }
        Log.i(TAG, "检测到最新版本 " + r.tag + " (code=" + r.versionCode + ")");
        return r;
    }

    // ————————————————— 2. 线路测速 —————————————————

    /** 同步测速，请在子线程调用；返回按延迟升序、只保留可用线路 */
    public List<Line> measureLines(Release r) {
        List<String> urls = new ArrayList<String>();
        if (r != null && r.assetUrl != null) {
            urls.add(r.assetUrl); // 官方
            for (int i = 1; i < MIRROR_PREFIXES.length; i++) {
                urls.add(MIRROR_PREFIXES[i] + r.assetUrl);
            }
            String proxy = customProxy();
            if (proxy != null && proxy.length() > 0) {
                urls.add(normalizePrefix(proxy) + r.assetUrl);
            }
        }

        List<Line> result = new ArrayList<Line>();
        for (String u : urls) {
            Line l = new Line();
            l.url = u;
            l.label = labelOf(u);
            long t0 = System.currentTimeMillis();
            try {
                l.latencyMs = probe(u);
                l.ok = l.latencyMs >= 0;
            } catch (Throwable t) {
                l.latencyMs = -1;
                l.ok = false;
            }
            if (l.latencyMs < 0) l.latencyMs = System.currentTimeMillis() - t0;
            result.add(l);
            Log.i(TAG, "线路 " + l.label + " " + l.latencyMs + "ms ok=" + l.ok);
        }
        // 可用的排前面，再按延迟升序
        final List<Line> out = new ArrayList<Line>();
        for (Line l : result) if (l.ok) out.add(l);
        for (Line l : result) if (!l.ok) out.add(l);
        java.util.Collections.sort(out, new Comparator<Line>() {
            @Override public int compare(Line a, Line b) {
                if (a.ok != b.ok) return a.ok ? -1 : 1;
                return Long.compare(a.latencyMs, b.latencyMs);
            }
        });
        return out;
    }

    private String labelOf(String url) {
        if (url.startsWith("https://github.com")) return "GitHub 官方源";
        if (url.contains("ghfast.top")) return "国内镜像 A";
        if (url.contains("gh-proxy.com")) return "国内镜像 B";
        if (url.contains("ghproxy.net")) return "国内镜像 C";
        return "自定义代理";
    }

    private String normalizePrefix(String p) {
        p = p.trim();
        if (!p.endsWith("/")) p += "/";
        if (!p.startsWith("http://") && !p.startsWith("https://")) p = "https://" + p;
        return p;
    }

    /** HEAD 探测；不支持 HEAD 时用 Range GET。返回首个响应耗时(ms)，失败 -1 */
    private long probe(String url) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod("HEAD");
            c.setRequestProperty("User-Agent", "XRobotUpdater/1.0");
            int code = c.getResponseCode();
            if (code == 405 || code == 403) {
                c.disconnect();
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setInstanceFollowRedirects(true);
                c.setRequestMethod("GET");
                c.setRequestProperty("Range", "bytes=0-1023");
                c.setRequestProperty("User-Agent", "XRobotUpdater/1.0");
                code = c.getResponseCode();
                InputStream in = c.getInputStream();
                if (in != null) { in.read(); in.close(); }
            }
            long ms = System.currentTimeMillis() - t0;
            return (code >= 200 && code < 400) ? ms : -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ————————————————— 3. 下载 —————————————————

    /** 下载到 app 缓存目录；主线程回调进度 */
    public File download(Release r, String url, final Callback cb) throws Exception {
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, r.assetName != null ? r.assetName : "XRobotHub.apk");
        if (out.exists()) out.delete();

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "XRobotUpdater/1.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

        long total = c.getContentLength();
        if (total <= 0 && r.size > 0) total = r.size;
        InputStream in = c.getInputStream();
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[8192];
        long done = 0;
        int n;
        int lastPct = -1;
        try {
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    final int pct = (int) (done * 100 / total);
                    if (pct != lastPct && cb != null) {
                        lastPct = pct;
                        post(cb, pct);
                    }
                }
            }
        } finally {
            os.flush(); os.close();
            in.close();
            c.disconnect();
        }
        if (out.length() < 10000) throw new IllegalStateException("文件过小，下载可能失败");
        Log.i(TAG, "下载完成 " + out.getAbsolutePath() + " " + out.length() + " bytes");
        return out;
    }

    private void post(final Callback cb, final int pct) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(new Runnable() {
            @Override public void run() {
                try { cb.onProgress(pct); } catch (Throwable ignored) {}
            }
        });
    }

    // ————————————————— 4. 安装 —————————————————

    /** 返回 "root" / "manual" / 抛出异常 */
    public String install(File apk) throws Exception {
        if (rootSilent() && hasRoot()) {
            try {
                silentInstall(apk);
                return "root";
            } catch (Throwable t) {
                Log.w(TAG, "root 静默安装失败，回退系统安装器", t);
            }
        }
        manualInstall(apk);
        return "manual";
    }

    public static boolean hasRoot() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id -u"});
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            return line != null && line.trim().equals("0");
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private void silentInstall(File apk) throws Exception {
        // pm install 覆盖自己时系统会立刻杀掉本包（含前台界面），看起来像崩溃。
        // 把 am start 串在同一 su shell 里：装完无论本进程死活，都会自动拉回主界面。
        String cmd = "pm install -r " + apk.getAbsolutePath()
                + " && am start -n com.visbot.robotctl/com.xrobot.hub.MainActivity";
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        InputStream in = p.getInputStream();
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in = p.getErrorStream();
        while (in != null && (n = in.read(buf)) > 0) err.write(buf, 0, n);
        int rc = p.waitFor();
        String msg = out.toString("UTF-8") + err.toString("UTF-8");
        Log.i(TAG, "pm install rc=" + rc + " out=" + msg);
        if (rc != 0 || msg.toLowerCase().contains("failure")) {
            throw new IllegalStateException("pm install 失败: " + msg.trim());
        }
    }

    /** PackageInstaller 会话提交 → 系统弹确认框，由用户手动点安装 */
    private void manualInstall(File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(sessionId);
        try {
            InputStream in = new java.io.FileInputStream(apk);
            OutputStream os = session.openWrite("base.apk", 0, apk.length());
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            session.fsync(os);
            os.close();
            in.close();

            Intent intent = new Intent(ctx, MainActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pend = PendingIntent.getActivity(ctx, sessionId, intent, flags);
            session.commit(pend.getIntentSender());
            Log.i(TAG, "已提交系统安装器，等待用户手动确认");
        } finally {
            session.close();
        }
    }

    // ————————————————— HTTP 工具 —————————————————

    private static byte[] httpGet(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "XRobotUpdater/1.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bos.toByteArray();
    }
}
