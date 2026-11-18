package com.xrobot.hub;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xrobot.hub.driver.VisbotDriver;

/** 调试面板：状态自检 + 常用动作，日常由其他 App 通过 API 调用，这里只是验证用 */
public final class DebugActivity extends Activity {

    private TextView status;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            VisbotDriver d = new VisbotDriver();
            status.setText("中枢: " + DriverRegistry.describe()
                    + "\n\n驱动: " + d.id() + " / " + d.displayName()
                    + "\n参数: " + d.info()
                    + "\n状态: " + d.status());
            h.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        status = new TextView(this);
        status.setTextSize(12);
        root.addView(status);

        root.addView(btn("自检：前进 30cm", new Runnable() {
            @Override public void run() { act(0); }
        }));
        root.addView(btn("自检：左转 90°", new Runnable() {
            @Override public void run() { act(1); }
        }));
        root.addView(btn("自检：抬头 25°", new Runnable() {
            @Override public void run() { act(2); }
        }));
        root.addView(btn("急停", new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        RobotDriver d = DriverRegistry.active();
                        if (d != null) d.stop();
                    }
                }).start();
            }
        }));

        setContentView(root);
        h.post(tick);
    }

    private void act(final int what) {
        new Thread(new Runnable() {
            @Override public void run() {
                RobotDriver d = DriverRegistry.active();
                if (d == null) return;
                try {
                    if (what == 0) d.move(30f, 8f);
                    else if (what == 1) d.turn(90f, 60f);
                    else d.head(25f, 15f);
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    private View btn(String text, Runnable r) {
        Button b = new Button(this);
        b.setText(text);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        return b;
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(tick);
        super.onDestroy();
    }
}
