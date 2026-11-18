package com.xrobot.hub;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xrobot.hub.Updater.Callback;
import com.xrobot.hub.Updater.Line;
import com.xrobot.hub.Updater.Release;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * XRobot 主界面：横屏紧凑布局，两个 Tab（控制 / 更新中心）。
 * 全代码构建 UI（构建脚本不编译 XML 资源），配色按功能区分色系。
 */
public final class MainActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private Updater updater;

    private TextView tvService, tvBattery, tvQueue;
    /** 用户主动退出标志：设置页「退出应用」置位后，不再自动拉回 */
    private static volatile boolean reallyExit = false;
    /** 最近一次用户操作（点按钮）的时间，用于区分"被系统踢走"和"用户主动离开" */
    private static volatile long lastUserActionMs = 0;
    private TextView tvLog;
    private TextView tvVersion, tvUpdateInfo, tvLines, tvUpdateLog, tvRootState;
    private ProgressBar progress;
    private Button btnCheck, btnUpdate;
    private CheckBox ckAutoCheck, ckAutoInstall, ckRootSilent;
    private EditText etProxy;

    private Release latest;
    private boolean busyCheck = false, busyUpdate = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        updater = new Updater(this);

        // 固件有"空闲 30 秒踢回桌面"的定制行为（PowerManagerService idle timeout →
        // com.ubtech.visbot.LOCK_UI / HOME）。控制界面必须保持屏幕活跃，否则等测速/下载
        // 时手一停就被踢。FLAG_KEEP_SCREEN_ON 在窗口可见期间持续刷新屏幕状态。
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F7F6F2"));

        // 内容区（两页切换）。不设自绘标题行：系统标题栏已显示应用名，节省竖向空间。
        final View pageControl = controlPage();
        final View pageUpdate = updatePage();
        final LinearLayout pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.VERTICAL);
        pages.addView(pageControl, vlp(1));
        LinearLayout updateHolder = new LinearLayout(this);
        updateHolder.setOrientation(LinearLayout.VERTICAL);
        updateHolder.addView(pageUpdate, vlp(1));
        pages.addView(updateHolder, vlp(1));
        updateHolder.setVisibility(View.GONE);
        root.addView(pages, vlp(1));

        // 底部 Tab
        LinearLayout nav = row();
        nav.setBackgroundColor(Color.WHITE);
        final Button tabControl = navBtn("控制", true);
        final Button tabUpdate = navBtn("更新中心", false);
        tabControl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pageControl.setVisibility(View.VISIBLE);
                updateHolder.setVisibility(View.GONE);
                tabControl.setBackgroundColor(Color.parseColor("#E6F1FB"));
                tabUpdate.setBackgroundColor(Color.WHITE);
            }
        });
        tabUpdate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pageControl.setVisibility(View.GONE);
                updateHolder.setVisibility(View.VISIBLE);
                tabUpdate.setBackgroundColor(Color.parseColor("#E6F1FB"));
                tabControl.setBackgroundColor(Color.WHITE);
            }
        });
        nav.addView(tabControl, hlp(1));
        nav.addView(tabUpdate, hlp(1));
        root.addView(nav, wrapW());

        setContentView(root);

        refreshVersionText();
        applySettings(true);
        logUpdate("本机 " + updater.localVersionName() + " · "
                + (Updater.hasRoot() ? "已 Root" : "无 Root"), "#5F5E5A");
        apiLog("› 准备就绪，点按钮下发命令", "#5F5E5A");

        startStatusTick();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    // ——————————————— 控制页 ———————————————

    private LinearLayout controlPage() {
        LinearLayout page = row();
        page.setPadding(dp(8), dp(8), dp(8), dp(8));

        // 左：方向键 + 停止
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        LinearLayout padTop = row();
        padTop.addView(dirBtn("前进", "#E6F1FB", "#0C447C", "#185FA5", new Runnable() {
            @Override public void run() { send("move", 30f, 8f, null, "前进 30cm"); }
        }), hlp(1));
        padTop.addView(dirBtn("后退", "#EEEDFE", "#3C3489", "#534AB7", new Runnable() {
            @Override public void run() { send("move", 30f, -8f, null, "后退 30cm"); }
        }), hlp(1));
        pad.addView(padTop, vlp(1));
        LinearLayout padBot = row();
        padBot.addView(dirBtn("左转", "#E1F5EE", "#085041", "#0F6E56", new Runnable() {
            @Override public void run() { send("turn", 90f, 60f, null, "左转 90°"); }
        }), hlp(1));
        padBot.addView(dirBtn("右转", "#FAEEDA", "#854F0B", "#BA7517", new Runnable() {
            @Override public void run() { send("turn", -90f, 60f, null, "右转 90°"); }
        }), hlp(1));
        pad.addView(padBot, vlp(1));
        left.addView(pad, vlp(76));
        left.addView(dirBtn("■  停止", "#FCEBEB", "#791F1F", "#A32D2D", new Runnable() {
            @Override public void run() { send("stop", 0, 0, null, "停止"); }
        }), vlp(24));
        page.addView(left, hlp(44));

        // 右：状态徽章 / 头部 / 快捷 / 日志
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout badges = row();
        badges.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tvService = badge("#EAF3DE", "#27500A", "服务 检测中", 9);
        tvBattery = badge("#FAEEDA", "#854F0B", "电量 --", 9);
        tvQueue = badge("#E6F1FB", "#0C447C", "队列 0", 9);
        badges.addView(tvService);
        badges.addView(space(4));
        badges.addView(tvBattery);
        badges.addView(space(4));
        badges.addView(tvQueue);
        right.addView(badges, wrapW());
        right.addView(section("头部控制", "#FBEAF0", "#993556", new String[]{"抬头", "回正", "低头"},
                new String[]{"#ED93B1", "#ED93B1", "#ED93B1"}, new Runnable[]{
                        new Runnable() { @Override public void run() { send("head", 23f, 15f, null, "抬头"); } },
                        new Runnable() { @Override public void run() { send("head", 0f, 15f, null, "头部回正"); } },
                        new Runnable() { @Override public void run() { send("head", -25f, 15f, null, "低头"); } }
                }), vlp(36));
        right.addView(section("快捷动作", "#EAF3DE", "#3B6D11", new String[]{"找红球", "走方形", "转一圈"},
                new String[]{"#97C459", "#97C459", "#97C459"}, new Runnable[]{
                        new Runnable() { @Override public void run() { send("goto", 0, 0, "red", "找红球"); } },
                        new Runnable() { @Override public void run() { square(); } },
                        new Runnable() { @Override public void run() { send("turn", 360f, 60f, null, "转一圈 360°"); } }
                }), vlp(36));
        LinearLayout logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logBox.setBackgroundColor(Color.parseColor("#EDEBE4"));
        logBox.setPadding(dp(8), dp(5), dp(8), dp(5));
        logBox.addView(tv("命令日志", 11, "#5F5E5A", true), wrapW());
        tvLog = tv("› 准备就绪", 11, "#2C2C2A", false);
        tvLog.setTypeface(Typeface.MONOSPACE);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(tvLog);
        logBox.addView(logScroll, vlp(1));
        right.addView(logBox, vlp(28));
        page.addView(right, hlp(56));
        return page;
    }

    private LinearLayout section(String title, String bg, String titleColor, String[] labels,
                                 String[] borders, Runnable[] actions) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.parseColor(bg));
        box.setPadding(dp(7), dp(5), dp(7), dp(5));
        box.addView(tv(title, 12, titleColor, true), wrapW());
        LinearLayout r = row();
        for (int i = 0; i < labels.length; i++) {
            Button b = smallBtn(labels[i], titleColor, borders[i]);
            final Runnable a = actions[i];
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { a.run(); }
            });
            r.addView(b, hlp(1));
        }
        box.addView(r, vlp(1));
        return box;
    }

    private void send(final String method, final float a, final float b, final String color, final String label) {
        lastUserActionMs = System.currentTimeMillis();
        apiLog("› " + label + " 已发送", "#185FA5");
        new Thread(new Runnable() {
            @Override public void run() {
                Bundle res = CommandQueue.get().exec(method, a, b, color, false);
                final boolean ok = res.getBoolean("ok", false);
                final String msg = res.getString("msg");
                h.post(new Runnable() {
                    @Override public void run() {
                        if (ok) apiLog("✓ " + label + " 已执行", "#0F6E56");
                        else apiLog("✗ " + label + " " + (msg == null ? "失败" : msg), "#A32D2D");
                    }
                });
            }
        }, "xrobot-ui-cmd").start();
    }

    private void square() {
        apiLog("› 走方形 已发送", "#185FA5");
        new Thread(new Runnable() {
            @Override public void run() {
                float[][] seq = {{30, 8}, {90, 60}, {30, 8}, {90, 60}, {30, 8}, {90, 60}, {30, 8}, {90, 60}};
                String[] method = {"move", "turn", "move", "turn", "move", "turn", "move", "turn"};
                for (int i = 0; i < seq.length; i++) {
                    CommandQueue.get().exec(method[i], seq[i][0], seq[i][1], null, false);
                    try { Thread.sleep(CommandQueue.GAP_MS); } catch (InterruptedException ignored) { break; }
                }
                h.post(new Runnable() {
                    @Override public void run() { apiLog("✓ 走方形 队列已排完", "#0F6E56"); }
                });
            }
        }, "xrobot-ui-square").start();
    }

    private void apiLog(String s, String color) {
        if (tvLog == null) return;
        tvLog.append("\n" + s);
        String all = tvLog.getText().toString();
        String[] arr = all.split("\n");
        if (arr.length > 30) {
            StringBuilder sb = new StringBuilder();
            for (int i = arr.length - 30; i < arr.length; i++) sb.append(arr[i]).append("\n");
            tvLog.setText(sb.toString());
        }
    }

    // ——————————————— 更新页 ———————————————

    private ScrollView updatePage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(8), dp(8), dp(8), dp(8));

        LinearLayout verCard = card("#E6F1FB", "#85B7EB");
        tvVersion = tv("当前版本 " + updater.localVersionName(), 13, "#0C447C", true);
        verCard.addView(tvVersion, wrapW());
        tvUpdateInfo = tv("点击下方检查更新", 11, "#185FA5", false);
        verCard.addView(tvUpdateInfo, wrapW());
        page.addView(verCard, wrapW());

        LinearLayout mid = row();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(tv("下载线路（自动测速）", 11, "#5F5E5A", true), wrapW());
        tvLines = tv("尚未测速", 11, "#2C2C2A", false);
        tvLines.setTypeface(Typeface.MONOSPACE);
        left.addView(tvLines, vlp(1));
        mid.addView(left, hlp(46));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        btnCheck = bigBtn("检查更新", "#378ADD", new View.OnClickListener() {
            @Override public void onClick(View v) { doCheck(); }
        });
        btnUpdate = bigBtn("一键更新", "#D4537E", new View.OnClickListener() {
            @Override public void onClick(View v) { doUpdate(); }
        });
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        right.addView(btnCheck, vlp(1));
        right.addView(btnUpdate, vlp(1));
        right.addView(progress, wrapW());
        mid.addView(right, hlp(54));
        mid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96)));
        page.addView(mid);

        LinearLayout settings = card("#F1EFE8", "#D3D1C7");
        settings.addView(tv("更新设置", 11, "#5F5E5A", true), wrapW());
        LinearLayout sw = row();
        ckAutoCheck = chk("启动自动检测", updater.autoCheck());
        ckAutoInstall = chk("下载后自动装", updater.autoInstall());
        ckRootSilent = chk("Root 静默装", updater.rootSilent());
        sw.addView(ckAutoCheck, hlp(1));
        sw.addView(ckAutoInstall, hlp(1));
        sw.addView(ckRootSilent, hlp(1));
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        settings.addView(sw);
        tvRootState = tv("Root 状态: 检测中", 11, "#5F5E5A", false);
        settings.addView(tvRootState, wrapW());
        LinearLayout proxyRow = row();
        proxyRow.addView(tv("代理", 11, "#5F5E5A", false), wrapW());
        etProxy = new EditText(this);
        etProxy.setHint("留空=自动测速；如 https://ghfast.top/");
        etProxy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        etProxy.setSingleLine(true);
        etProxy.setText(updater.customProxy());
        proxyRow.addView(etProxy, hlp(1));
        proxyRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        settings.addView(proxyRow);
        page.addView(settings, wrapW());

        // 固件 IdleScreen 会把本应用踢回桌面（自动拉回对策），需要真退出时用这个按钮
        LinearLayout exitRow = row();
        Button btnExit = bigBtn("退出应用", "#A32D2D", new View.OnClickListener() {
            @Override public void onClick(View v) {
                reallyExit = true;
                try { stopService(new Intent(MainActivity.this, HubService.class)); } catch (Throwable ignored) {}
                finishAffinity();
            }
        });
        exitRow.addView(btnExit, hlp(1));
        exitRow.addView(new View(this), hlp(1));
        exitRow.addView(new View(this), hlp(1));
        exitRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
        page.addView(exitRow, wrapW());

        LinearLayout logBox = card("#FFFFFF", "#D3D1C7");
        tvUpdateLog = tv("等待操作…", 11, "#2C2C2A", false);
        tvUpdateLog.setTypeface(Typeface.MONOSPACE);
        logBox.addView(tvUpdateLog, wrapW());
        logBox.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));
        page.addView(logBox);

        sv.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private void applySettings(boolean attachListeners) {
        ckAutoCheck.setChecked(updater.autoCheck());
        ckAutoInstall.setChecked(updater.autoInstall());
        ckRootSilent.setChecked(updater.rootSilent());
        etProxy.setText(updater.customProxy());
        tvRootState.setText("Root 状态: " + (Updater.hasRoot() ? "已 Root（可静默安装）" : "无 Root（需手动点安装）"));
        tvRootState.setTextColor(Color.parseColor(Updater.hasRoot() ? "#0F6E56" : "#BA7517"));
        if (!attachListeners) return;

        CompoundButton.OnCheckedChangeListener l = new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton v, boolean c) {
                updater.setAutoCheck(ckAutoCheck.isChecked());
                updater.setAutoInstall(ckAutoInstall.isChecked());
                updater.setRootSilent(ckRootSilent.isChecked());
            }
        };
        ckAutoCheck.setOnCheckedChangeListener(l);
        ckAutoInstall.setOnCheckedChangeListener(l);
        ckRootSilent.setOnCheckedChangeListener(l);
        etProxy.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) updater.setCustomProxy(etProxy.getText().toString());
            }
        });
    }

    private void refreshVersionText() {
        String cur = updater.localVersionName();
        tvVersion.setText("当前版本 " + cur);
        if (latest != null && updater.isNewer(latest)) {
            tvUpdateInfo.setText("发现新版本 " + latest.tag + " · " + friendlySize(latest.size));
            tvUpdateInfo.setTextColor(Color.parseColor("#A32D2D"));
        } else {
            tvUpdateInfo.setText("已是最新版本（或尚未检测）");
            tvUpdateInfo.setTextColor(Color.parseColor("#185FA5"));
        }
    }

    private String friendlySize(long size) {
        if (size <= 0) return "约 53 KB";
        return size < 1024 * 1024 ? (size / 1024) + " KB"
                : String.format("%.1f MB", size / 1024f / 1024f);
    }

    private void doCheck() {
        if (busyCheck) return;
        busyCheck = true;
        btnCheck.setEnabled(false);
        tvUpdateInfo.setText("正在检测…");
        lastUserActionMs = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Release r = updater.check();
                    h.post(new Runnable() {
                        @Override public void run() {
                            latest = r;
                            refreshVersionText();
                            boolean newer = updater.isNewer(r);
                            logUpdate(newer ? "发现新版 " + r.tag : "已是最新 " + r.tag,
                                    newer ? "#A32D2D" : "#0F6E56");
                            if (newer) probeLines(r);
                        }
                    });
                } catch (final Throwable t) {
                    h.post(new Runnable() {
                        @Override public void run() {
                            tvUpdateInfo.setText("检测失败: " + t.getMessage());
                            logUpdate("检测失败: " + t.getMessage(), "#A32D2D");
                        }
                    });
                } finally {
                    h.post(new Runnable() {
                        @Override public void run() { busyCheck = false; btnCheck.setEnabled(true); }
                    });
                }
            }
        }, "xrobot-check").start();
    }

    private void probeLines(final Release r) {
        tvLines.setText("测速中…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Line> ls = updater.measureLines(r);
                h.post(new Runnable() {
                    @Override public void run() {
                        showLines(ls);
                        logUpdate("最快线路: " + (ls.isEmpty() ? "无" : ls.get(0).label), "#0F6E56");
                    }
                });
            }
        }, "xrobot-probe").start();
    }

    private void showLines(List<Line> ls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ls.size(); i++) {
            Line l = ls.get(i);
            sb.append(i == 0 ? "★ " : "  ").append(l.label).append(" ")
                    .append(l.ok ? l.latencyMs + "ms" : "不可用").append("\n");
        }
        tvLines.setText(sb.toString().trim());
    }

    private void doUpdate() {
        lastUserActionMs = System.currentTimeMillis();
        if (busyUpdate) return;
        if (latest == null) {
            logUpdate("请先点「检查更新」", "#BA7517");
            doCheck();
            return;
        }
        if (!updater.isNewer(latest)) {
            logUpdate("已是最新版本，无需更新", "#0F6E56");
            return;
        }
        busyUpdate = true;
        btnUpdate.setEnabled(false);
        progress.setProgress(0);
        logUpdate("开始更新 " + latest.tag, "#993556");

        final Release r = latest;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final List<Line> ls = updater.measureLines(r);
                    Line best = null;
                    for (Line l : ls) if (l.ok) { best = l; break; }
                    if (best == null) throw new IllegalStateException("所有线路都不可用");
                    final String label = best.label;
                    h.post(new Runnable() {
                        @Override public void run() {
                            logUpdate("选用 " + label + " 下载", "#534AB7");
                            showLines(ls);
                        }
                    });

                    final Callback cb = new Callback() {
                        @Override public void onStatus(final String msg) {
                            h.post(new Runnable() {
                                @Override public void run() { logUpdate(msg, "#185FA5"); }
                            });
                        }
                        @Override public void onProgress(final int percent) {
                            h.post(new Runnable() {
                                @Override public void run() { progress.setProgress(percent); }
                            });
                        }
                        @Override public void onDone(boolean success, String msg) { }
                    };
                    cb.onStatus("下载中…");
                    File apk = updater.download(r, best.url, cb);
                    cb.onStatus("下载完成 " + friendlySize(apk.length()) + "，准备安装");
                    String how = updater.install(apk);
                    final String msg = "root".equals(how)
                            ? "静默安装完成（Root）" : "已弹出系统安装器，请手动点安装";
                    h.post(new Runnable() {
                        @Override public void run() {
                            progress.setProgress(100);
                            logUpdate("✓ " + msg, "#0F6E56");
                        }
                    });
                } catch (final Throwable t) {
                    h.post(new Runnable() {
                        @Override public void run() {
                            logUpdate("✗ 更新失败: " + t.getMessage(), "#A32D2D");
                        }
                    });
                } finally {
                    h.post(new Runnable() {
                        @Override public void run() { busyUpdate = false; btnUpdate.setEnabled(true); }
                    });
                }
            }
        }, "xrobot-update").start();
    }

    private void logUpdate(String s, String color) {
        if (tvUpdateLog == null) return;
        String cur = tvUpdateLog.getText().toString();
        tvUpdateLog.setText((TextUtils.isEmpty(cur) ? "" : cur + "\n") + "› " + s);
    }

    // ——————————————— 状态轮询 ———————————————

    private void startStatusTick() {
        final Runnable tick = new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        Bundle q = CommandQueue.get().exec("queue", 0, 0, null, false);
                        final int pending = q.getInt("pending", 0);
                        final boolean busy = q.getBoolean("busy", false);
                        final boolean ready = CommandQueue.get().exec("ping", 0, 0, null, false)
                                .getBoolean("ready", false);
                        h.post(new Runnable() {
                            @Override public void run() {
                                tvQueue.setText("队列 " + pending + (busy ? " 忙" : ""));
                                tvService.setText(ready ? "服务 正常" : "服务 启动中");
                            }
                        });
                    }
                }, "xrobot-status").start();
                h.postDelayed(this, 1500);
            }
        };
        h.post(tick);
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = scale > 0 ? level * 100 / scale : -1;
            if (pct >= 0) tvBattery.setText("电量 " + pct + "%");
        }
    };

    // ——————————————— UI 工具 ———————————————

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 水平方向权重：宽=0 权重=w，高=match */
    private static LinearLayout.LayoutParams hlp(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
    }

    /** 垂直方向权重：宽=match，高=0 权重=w */
    private static LinearLayout.LayoutParams vlp(float weight) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private static LinearLayout.LayoutParams wrapW() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private TextView tv(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView badge(String bg, String fg, String text, int sp) {
        TextView t = tv(text, sp, fg, false);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bg));
        d.setCornerRadius(dp(99));
        t.setBackground(d);
        return t;
    }

    private View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dp), 1));
        return v;
    }

    private Button dirBtn(String text, String bg, String fg, String stroke, final Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        b.setTextColor(Color.parseColor(fg));
        b.setAllCaps(false);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bg));
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), Color.parseColor(stroke));
        b.setBackground(d);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }

    private Button smallBtn(String text, String fg, String stroke) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        b.setTextColor(Color.parseColor(fg));
        b.setAllCaps(false);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.WHITE);
        d.setCornerRadius(dp(6));
        d.setStroke(dp(1), Color.parseColor(stroke));
        b.setBackground(d);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(4), dp(3), dp(4), dp(3));
        return b;
    }

    private Button bigBtn(String text, String bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bg));
        d.setCornerRadius(dp(8));
        b.setBackground(d);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    private Button navBtn(String text, boolean active) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        b.setTextColor(Color.parseColor("#374151"));
        b.setAllCaps(false);
        b.setBackgroundColor(Color.parseColor(active ? "#E6F1FB" : "#FFFFFF"));
        b.setMinHeight(0);
        b.setMinWidth(0);
        return b;
    }

    private LinearLayout card(String bg, String stroke) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bg));
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), Color.parseColor(stroke));
        l.setBackground(d);
        l.setPadding(dp(7), dp(6), dp(7), dp(6));
        return l;
    }

    private CheckBox chk(String text, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        c.setTextColor(Color.parseColor("#374151"));
        c.setChecked(def);
        c.setMinimumHeight(0);
        return c;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        scheduleSelfRevive();
    }

    @Override
    protected void onStop() {
        super.onStop();
        scheduleSelfRevive();
    }

    /** 固件 IdleScreen 会把非待机应用踢回桌面（点抬头/更新中途都会被踢）。
     *  这里在"非用户主动离开"时自动把自己拉回前台；真正退出走「退出应用」按钮。 */
    private void scheduleSelfRevive() {
        if (reallyExit) return;
        boolean recent = System.currentTimeMillis() - lastUserActionMs < 15000;
        if (!recent) return; // 用户主动离开（无近期操作），尊重其选择
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (reallyExit) return;
                try {
                    Intent it = new Intent(MainActivity.this, MainActivity.class);
                    it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(it);
                } catch (Throwable ignored) {}
            }
        }, 500);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(batteryReceiver); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
