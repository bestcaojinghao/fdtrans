package com.kxxy.fdtrans;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块设置页：一个总开关 + 缓存清理 + 打开 F-Droid。
 * 纯代码构建 UI（模块 APK 不带资源表，避免 aapt2 资源依赖）。
 */
public class Settings extends Activity {

    private static final int PAD = 44;

    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sp = getSharedPreferences("fdtrans", MODE_PRIVATE);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF121212);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(PAD, PAD, PAD, PAD);
        sc.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("F-Droid 实时翻译", 24, 0xFFFFFFFF);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, lp(0));

        root.addView(text("开启后 F-Droid 界面里的英文（应用名、摘要、描述）会被自动翻成中文，"
                + "直接显示在原位，不加悬浮窗、不改变布局。", 14, 0xFFB0B0B0), lp(20));

        final Switch sw = new Switch(this);
        sw.setText("启用实时翻译");
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        sw.setTextColor(0xFFFFFFFF);
        sw.setChecked(sp.getBoolean("enabled", true));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                android.util.Log.i("FDTrans", "toggle -> " + v);
                boolean ok = sp.edit().putBoolean("enabled", v).commit();
                android.util.Log.i("FDTrans", "prefs write " + ok);
                // F-Droid 未运行时广播会丢，所以先把它的进程拉起来再发
                pushToTarget("enabled", v);
                toast(v ? "已开启实时翻译" : "已关闭实时翻译");
            }
        });
        root.addView(sw, lp(18));

        final Switch swName = new Switch(this);
        swName.setText("保留应用英文原名");
        swName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        swName.setTextColor(0xFFFFFFFF);
        swName.setChecked(sp.getBoolean("skipNames", true));
        swName.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                android.util.Log.i("FDTrans", "skipNames -> " + v);
                sp.edit().putBoolean("skipNames", v).commit();
                pushToTarget("skipNames", v);
                toast(v ? "应用名保留英文（推荐）" : "应用名也会被翻译");
            }
        });
        root.addView(swName, lp(8));

        root.addView(box("状态",
                "引擎  Bing（免费网页接口，无需 key；失败自动回退 MyMemory）\n"
                        + "方向  英文 → 简体中文\n"
                        + "缓存  F-Droid 私有目录 fdtrans-cache.tsv\n"
                        + "刷新  译文安静 0.7s 后一次性重建（打字时不重建）"), lp(18));

        root.addView(box("说明",
                "• 中文/数字/包名/版本号/URL 不会被翻译\n"
                        + "• 已有译文直接命中缓存，只有新出现的英文才走网络\n"
                        + "• 若某次界面更新后仍是英文，重新进入该页面即可"), lp(14));

        Button pre = new Button(this);
        pre.setText("预翻译整个仓库（名称+摘要）");
        pre.setAllCaps(false);
        pre.setTextColor(0xFFFFFFFF);
        pre.setBackground(round(0xFF00695C));
        pre.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pushToTargetInt("prewarm", 1);
                toast("已开始预翻译（约 5000 个应用的名称与摘要），完成后 F-Droid 里全是中文");
            }
        });
        root.addView(pre, lp(22));

        Button pre2 = new Button(this);
        pre2.setText("预翻译详细描述（慢，约 1-3 小时）");
        pre2.setAllCaps(false);
        pre2.setTextColor(0xFFE0E0E0);
        pre2.setBackground(round(0xFF2A2A2A));
        pre2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pushToTargetInt("prewarm", 2);
                toast("已开始预翻译描述，会持续跑一会儿，期间 F-Droid 可以正常用");
            }
        });
        root.addView(pre2, lp(10));

        Button clear = new Button(this);
        clear.setText("清空翻译缓存");
        clear.setAllCaps(false);
        clear.setTextColor(0xFFFFFFFF);
        clear.setBackground(round(0xFF303030));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long t = System.currentTimeMillis();
                sp.edit().putLong("clearCacheAt", t).apply();
                pushToTarget("clearCacheAt", t);
                toast("已通知 F-Droid 清空缓存");
            }
        });
        root.addView(clear, lp(22));

        Button open = new Button(this);
        open.setText("打开 F-Droid");
        open.setAllCaps(false);
        open.setTextColor(0xFFFFFFFF);
        open.setBackground(round(0xFF1565C0));
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage("org.fdroid.fdroid");
                if (i == null) {
                    toast("未安装 F-Droid");
                    return;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        });
        root.addView(open, lp(10));

        Button kill = new Button(this);
        kill.setText("强制重启 F-Droid（应用设置变更）");
        kill.setAllCaps(false);
        kill.setTextColor(0xFFE0E0E0);
        kill.setBackground(round(0xFF2A2A2A));
        kill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("请在系统设置里强行停止 F-Droid，或重进应用 2 次");
            }
        });
        root.addView(kill, lp(10));

        setContentView(sc);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0f, 1.25f);
        return t;
    }

    private View box(String head, String body) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(0xFF1E1E1E));
        l.setPadding(32, 28, 32, 28);
        TextView h = text(head, 13, 0xFF80CBC4);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        l.addView(h, lp(0));
        l.addView(text(body, 14, 0xFFD0D0D0), lp(8));
        return l;
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(28f);
        return g;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int) (topDp * getResources().getDisplayMetrics().density);
        return p;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** int 额外值版本（预热模式等）。 */
    private void pushToTargetInt(final String key, final int value) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Config.REMOTE_PKG);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
            } catch (Throwable ignore) {
            }
        }
        final Intent i = new Intent(Config.ACTION);
        i.setPackage(Config.REMOTE_PKG);
        i.putExtra(key, value);
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    sendBroadcast(i);
                    android.util.Log.i("FDTrans", "broadcast sent " + key + "=" + value);
                } catch (Throwable t) {
                    android.util.Log.w("FDTrans", "broadcast fail " + t);
                }
            }
        }, 1800);
    }

    /** 拉起 F-Droid 再延迟发广播：目标进程不在时广播会被丢弃。 */
    private void pushToTarget(final String key, final Object value) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Config.REMOTE_PKG);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
            } catch (Throwable ignore) {
            }
        }
        final Intent i = new Intent(Config.ACTION);
        i.setPackage(Config.REMOTE_PKG);
        if (value instanceof Boolean) i.putExtra(key, (Boolean) value);
        else if (value instanceof Integer) i.putExtra(key, (Integer) value);
        else i.putExtra(key, (Long) value);
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    sendBroadcast(i);
                    android.util.Log.i("FDTrans", "broadcast sent " + key);
                } catch (Throwable t) {
                    android.util.Log.w("FDTrans", "broadcast fail " + t);
                }
            }
        }, 1800);
    }
}
