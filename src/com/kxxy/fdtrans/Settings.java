package com.kxxy.fdtrans;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private ProgressBar bar;
    private TextView scopeList;
    private TextView progText;
    private android.content.BroadcastReceiver progRecv;
    private volatile boolean prewarmAck;

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
        swName.setText("应用名保留英文（括号显示中文）");
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
                        + "• 若某次界面更新后仍是英文，重新进入该页面即可\n"
                        + "• 预翻译要等 F-Droid 打开过一次之后才联网（系统限制冷启动的后台进程）"), lp(14));

        // ---- 翻译哪些应用（LSPosed 作用域）----
        root.addView(box("翻译哪个应用",
                "本模块对所有已勾选为作用域的应用生效（View 与 Compose 两种界面都支持）。\n"
                + "• 加进来 / 移出去都走 root 改 LSPosed 配置，改完 lspd 会自动重启（约 20 秒）\n"
                + "• 首次操作会弹一次 root 授权，允许即可\n"
                + "• 已加进来的应用要重开一次才会生效"), lp(22));

        final EditText pkgIn = new EditText(this);
        pkgIn.setHint("包名，例如 me.timschneeberger.shizustore");
        pkgIn.setTextSize(13);
        pkgIn.setTextColor(0xFFEEEEEE);
        pkgIn.setHintTextColor(0xFF888888);
        root.addView(pkgIn, lp(0));

        scopeList = text("作用域：读取中…", 13, 0xFFB0B0B0);
        root.addView(scopeList, lp(10));

        LinearLayout rowScope = new LinearLayout(this);
        rowScope.setOrientation(LinearLayout.HORIZONTAL);
        Button addB = new Button(this);
        addB.setText("加入");
        addB.setAllCaps(false);
        addB.setTextColor(0xFFFFFFFF);
        addB.setBackground(round(0xFF00695C));
        addB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String p = pkgIn.getText().toString().trim();
                if (p.length() < 3) { toast("请输入包名"); return; }
                scopeEdit(true, p);
            }
        });
        rowScope.addView(addB, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button delB = new Button(this);
        delB.setText("移出");
        delB.setAllCaps(false);
        delB.setTextColor(0xFFE0E0E0);
        delB.setBackground(round(0xFF402020));
        delB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String p = pkgIn.getText().toString().trim();
                if (p.length() < 3) { toast("请输入包名"); return; }
                scopeEdit(false, p);
            }
        });
        rowScope.addView(delB, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(rowScope, lp(8));

        Button refreshScope = new Button(this);
        refreshScope.setText("刷新作用域列表");
        refreshScope.setAllCaps(false);
        refreshScope.setTextColor(0xFFE0E0E0);
        refreshScope.setBackground(round(0xFF2A2A2A));
        refreshScope.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { refreshScopeAsync(); }
        });
        root.addView(refreshScope, lp(8));

        // ---- 进度面板 ----
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(round(0xFF1E1E1E));
        panel.setPadding(32, 28, 32, 28);
        progText = text("预翻译进度：尚未开始", 14, 0xFFD0D0D0);
        panel.addView(progText, lp(0));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        panel.addView(bar, lp(12));
        root.addView(panel, lp(22));

        Button pre = new Button(this);
        pre.setText("预翻译整个仓库（名称+摘要）");
        pre.setAllCaps(false);
        pre.setTextColor(0xFFFFFFFF);
        pre.setBackground(round(0xFF00695C));
        pre.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSilent(1);
                toast("已在后台开始预翻译（名称+摘要），你可以继续用手机，回来这里看进度");
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
                startSilent(2);
                toast("已在后台开始预翻译详细描述，会跑一会儿，不影响你使用");
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

    @Override
    protected void onResume() {
        super.onResume();
        refreshScopeAsync();
        progRecv = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, Intent i) {
                int done = i.getIntExtra("done", 0);
                int total = i.getIntExtra("total", 0);
                boolean running = i.getBooleanExtra("running", false);
                String phase = i.getStringExtra("phase");
                int cache = i.getIntExtra("cache", 0);
                if (running) prewarmAck = true;
                int pct = total > 0 ? (int) (done * 100L / total) : 0;
                if (bar != null) {
                    bar.setMax(100);
                    bar.setProgress(running ? pct : (total > 0 ? 100 : 0));
                    bar.setIndeterminate(running && total == 0);
                }
                if (progText != null) {
                    if (total == 0 && !running) {
                        progText.setText("预翻译进度：尚未开始（缓存 " + cache + " 条）");
                    } else if (running) {
                        progText.setText("预翻译中：" + done + " / " + total + "（" + pct + "%）"
                                + "\n阶段 " + (phase == null ? "-" : phase) + "，缓存 " + cache + " 条");
                    } else {
                        progText.setText("预翻译完成：本次处理 " + (total > 0 ? total : done)
                                + " 条（100%）\n缓存共 " + cache + " 条，界面已可直接复用");
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter(Prewarm.ACTION_PROGRESS);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(progRecv, f, android.content.Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(progRecv, f);
        }
        // 问一次 F-Droid 当前状态（它在跑才会回）
        final boolean[] replied = {false};
        final Runnable[] mark = new Runnable[1];
        Intent q = new Intent(Config.ACTION);
        q.setPackage(Config.REMOTE_PKG);
        q.putExtra("queryStatus", true);
        try {
            sendBroadcast(q);
        } catch (Throwable ignore) { }
        final android.content.BroadcastReceiver[] holder = {progRecv};
        final android.content.BroadcastReceiver orig = progRecv;
        // 2.5s 内没收到回包 → 说明 F-Droid 没在跑
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (progText != null && progText.getText().toString().contains("尚未开始")) {
                    progText.setText("预翻译进度：未知（F-Droid 没在运行，点下面的按钮会自动启动它）");
                }
            }
        }, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (progRecv != null) {
            try {
                unregisterReceiver(progRecv);
            } catch (Throwable ignore) { }
            progRecv = null;
        }
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

    // ---- LSPosed 作用域管理（走 root，直接改 modules_config.db）----
    private static final String CFG_DB = "/data/adb/lspd/config/modules_config.db";
    private static final String MOD_PKG = "com.kxxy.fdtrans";

    private String su(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            return "ERR " + t;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) { }
        }
    }

    private void refreshScopeAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String out = su("sqlite3 " + CFG_DB
                        + " \"select app_pkg_name from scope where module_pkg_name='" + MOD_PKG
                        + "' order by app_pkg_name;\" 2>&1");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (scopeList == null) return;
                        if (out.startsWith("ERR")) {
                            scopeList.setText("作用域：读不到（" + out + "）\n可能还没给过 root 权限");
                            return;
                        }
                        String[] lines = out.trim().split("\n");
                        int n = out.trim().isEmpty() ? 0 : lines.length;
                        scopeList.setText("作用域（" + n + " 个）：" + (n == 0 ? "无" : "")
                                + (n == 0 ? "" : "\n" + out.trim()));
                    }
                });
            }
        }, "fdtrans-scope").start();
    }

    private void scopeEdit(final boolean add, final String pkg) {
        toast(add ? "正在加入…（首次会弹 root 授权）" : "正在移出…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String sql = add
                        ? "INSERT OR REPLACE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES('"
                          + MOD_PKG + "','" + pkg + "',0);"
                        : "DELETE FROM scope WHERE module_pkg_name='" + MOD_PKG
                          + "' AND app_pkg_name='" + pkg + "';";
                String out = su("sqlite3 " + CFG_DB + " \"" + sql + "\" 2>&1; echo done");
                // 重启 lspd（守守护脚本会在 15-22 秒内把它拉回来）
                su("rm -f /data/adb/lspd/monitor; kill -9 $(pidof lspd) 2>/dev/null; echo killed");
                if (!add) su("am force-stop " + pkg);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(add ? "已加入，约 20 秒后生效，请重开该应用"
                                  : "已移出，重开该应用后恢复原文");
                        refreshScopeAsync();
                    }
                });
            }
        }, "fdtrans-scope-edit").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /**
     * 后台静默启动预翻译：不把 F-Droid 切到前台。
     * 1) 先发一条预翻译广播（F-Droid 已在跑就直接开工）
     * 2) 给 F-Droid 的 exported 诊断接收器发广播，把进程拉起来（无界面）
     * 3) 800/1800/3200ms 重发预翻译广播，用同一个 req 去重
     */
    private void startSilent(final int mode) {
        final long req = System.currentTimeMillis();
        final Intent cmd = new Intent(Config.ACTION);
        cmd.setPackage(Config.REMOTE_PKG);
        cmd.putExtra("prewarm", mode);
        cmd.putExtra("req", req);

        Runnable fire = new Runnable() {
            @Override
            public void run() {
                try {
                    sendBroadcast(cmd);
                } catch (Throwable ignore) { }
            }
        };
        fire.run();

        try {
            Intent wake = new Intent();
            wake.setComponent(new android.content.ComponentName("org.fdroid.fdroid",
                    "androidx.work.impl.diagnostics.DiagnosticsReceiver"));
            wake.setAction("androidx.work.diagnostics.REQUEST_DIAGNOSTICS");
            sendBroadcast(wake);
            android.util.Log.i("FDTrans", "headless start sent via DiagnosticsReceiver");
        } catch (Throwable t) {
            android.util.Log.w("FDTrans", "headless start err " + t);
        }

        // F-Droid 冷启动可能要 3-6 秒，收到 running 回包前每 700ms 补一枪（req 去重，重复无害）
        prewarmAck = false;
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        final int[] tries = {0};
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (prewarmAck || tries[0] >= 20) return;
                tries[0]++;
                fire.run();
                h.postDelayed(this, 700L);
            }
        }, 700L);
    }

    /** 旧方案：拉起 F-Droid 后回到本页（已不再使用）。 */
    private void comeBack() {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        // F-Droid 冷启动会晚于第一次回跳，重复三次保证落回本页
        for (final long delay : new long[]{2600L, 5200L, 8200L}) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Settings.this, Settings.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable ignore) { }
                }
            }, delay);
        }
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
