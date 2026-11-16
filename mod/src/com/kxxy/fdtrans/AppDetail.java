package com.kxxy.fdtrans;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/**
 * 单个应用的详情页：
 *  - 这个应用翻不翻（开关，直接改 LSPosed 作用域）
 *  - 它现在翻了多少条（缓存条数 / 体积）
 *  - 不用打开它，直接从安装包里把英文抽出来预翻（进度条实时显示）
 *  - 其他维护动作：打开、清缓存、重启、移出
 */
public class AppDetail extends Activity {

    private String pkg;
    private String label;

    private Switch swOn;
    private TextView cacheText;
    private TextView probeText;
    private TextView preText;
    private ProgressBar preBar;

    private BroadcastReceiver probeRecv;
    private BroadcastReceiver preRecv;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pkg = getIntent().getStringExtra("pkg");
        label = getIntent().getStringExtra("label");
        if (pkg == null) { finish(); return; }
        if (label == null || label.length() == 0) label = pkg;

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF0E0E0E);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(28), pad, dp(28));
        sc.addView(root);

        // ---- 头部：图标 + 名称 + 包名 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        try { iv.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Throwable ignore) { }
        head.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, 0, 0);
        TextView name = text(label, 20, 0xFFFFFFFF);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(name);
        col.addView(text(pkg, 12, 0xFF888888), lp(4));
        head.addView(col);
        root.addView(head);

        // ---- 开关：翻不翻这个应用 ----
        LinearLayout card = card();
        swOn = new Switch(this);
        swOn.setText("翻译这个应用");
        swOn.setTextColor(0xFFE0E0E0);
        swOn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        swOn.setPadding(0, dp(6), 0, dp(6));
        swOn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setScoped(isChecked);
            }
        });
        card.addView(swOn);
        card.addView(text("关掉它会把这个应用从 LSPosed 作用域移出，界面恢复英文；重新打开后立即生效。",
                11, 0xFF7A7A7A), lp(4));
        root.addView(card, lp(18));

        // ---- 现在翻了多少 ----
        cacheText = text("译文：读取中…", 14, 0xFFBDBDBD);
        root.addView(cacheText, lp(16));

        // ---- 不用打开也能预翻 ----
        LinearLayout pre = card();
        pre.addView(text("不用打开这个应用也能翻", 15, 0xFFFFFFFF));
        pre.addView(text("直接读它的安装包，把里面的界面文字（resources.arsc + dex 字符串）提取出来翻译，"
                + "结果写进这个应用自己的译文缓存。之后你第一次打开它，界面多半已经是中文。", 11, 0xFF7A7A7A), lp(4));
        preText = text("", 12, 0xFF9CCC65);
        pre.addView(preText, lp(8));
        preBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        preBar.setMax(100);
        preBar.setProgress(0);
        preBar.setVisibility(View.GONE);
        pre.addView(preBar, lp(6));
        Button preBtn = btn("预翻这个应用的安装包文字", 0xFF1B5E20);
        preBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startPreload(); }
        });
        pre.addView(preBtn, lp(10));
        root.addView(pre, lp(14));

        // ---- 能不能翻 ----
        probeText = text("能不能翻：检测中…", 13, 0xFF9E9E9E);
        root.addView(probeText, lp(16));
        queryCapability();
        Button probeBtn = btn("重新检测能否翻译", 0xFF1B2F1B);
        probeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeText.setText("能不能翻：检测中…");
                queryCapability();
                queryProbe();
            }
        });
        root.addView(probeBtn, lp(10));

        // ---- 维护 ----
        Button open = btn("打开这个应用", 0xFF1565C0);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) { toast("打不开（没找到启动入口）"); return; }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Throwable t) { toast("打不开：" + t); }
            }
        });
        root.addView(open, lp(18));

        Button clear = btn("清空这个应用的译文缓存", 0xFF303030);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Scope.suMM("rm -f /data/user/0/" + pkg + "/files/fdtrans-cache.tsv /data/user/0/"
                                + pkg + "/files/fdtrans-req.tsv /data/user/0/" + pkg + "/files/fdtrans-res.tsv");
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("已清空，重启应用后重新开始翻译");
                                refreshCache();
                            }
                        });
                    }
                }, "fdtrans-clear").start();
            }
        });
        root.addView(clear, lp(10));

        Button restart = btn("重启这个应用（让改动生效）", 0xFF2A2A2A);
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Scope.su("am force-stop " + pkg + "; echo DONE");
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("已停止，重新打开即可");
                                refreshCache();
                            }
                        });
                    }
                }, "fdtrans-restart").start();
            }
        });
        root.addView(restart, lp(10));

        Button remove = btn("不再翻译这个应用", 0xFF402020);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setScoped(false);
                swOn.setChecked(false);
            }
        });
        root.addView(remove, lp(10));

        root.addView(text("译文缓存：\n" + cachePath(), 11, 0xFF616161), lp(22));

        setContentView(sc);
        refreshCache();
        initScoped();
        startPreReceiver();
    }

    private String cachePath() {
        return "/data/user/0/" + pkg + "/files/fdtrans-cache.tsv";
    }

    // ---------------- 作用域开关 ----------------

    private void initScoped() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean in;
                try {
                    Set<String> all = Scope.read(AppDetail.this);
                    in = all.contains(pkg);
                } catch (Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() { cacheText.setText("作用域状态读不到：" + "需要 root"); }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() { swOn.setChecked(in); }
                });
            }
        }, "fdtrans-scope-read").start();
    }

    private void setScoped(final boolean want) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Set<String> all = Scope.read(AppDetail.this);
                    boolean in = all.contains(pkg);
                    if (in == want) return;
                    if (want) all.add(pkg); else all.remove(pkg);
                    boolean ok = Scope.apply(AppDetail.this, all);
                    Scope.restartLspd();
                    if (!want) Scope.su("am force-stop " + pkg);
                    final String msg = ok ? (want ? "已加入翻译：重启该应用后生效" : "已移出翻译")
                            : "改作用域失败（root 没授权？）";
                    ui.post(new Runnable() {
                        @Override
                        public void run() { toast(msg); }
                    });
                } catch (Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() { toast("改作用域出错：" + "root 权限"); }
                    });
                }
            }
        }, "fdtrans-scope-write").start();
    }

    // ---------------- 预翻 ----------------

    private void startPreload() {
        if (Preload.isRunning(pkg)) {
            toast("这个应用正在预翻");
            return;
        }
        String other = Preload.runningPkg();
        if (other != null) {
            toast("正在预翻 " + other + "，等它跑完");
            return;
        }
        boolean ok = Preload.start(getApplicationContext(), pkg);
        if (!ok) { toast("启动失败"); return; }
        preBar.setVisibility(View.VISIBLE);
        preBar.setProgress(0);
        preText.setText("准备中…");
        // 定时拉状态（广播可能因为进程刚起而丢）
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) {
                    Preload.sendStatus(AppDetail.this, pkg);
                    if (Preload.isRunning(pkg)) ui.postDelayed(this, 1200L);
                }
            }
        }, 800L);
    }

    /** 本进程没在跑预翻时，显示上次跑完的结果。 */
    private void showLastPreload() {
        int last = Preload.lastDone(AppDetail.this, pkg);
        if (last > 0) preText.setText("上次已预翻 " + last + " 条（已完成：打开这个应用首屏就是中文）");
        else preText.setText("还没预翻过。点下面的按钮，不用打开这个应用也能把它的界面文字先翻好。");
    }

    private void startPreReceiver() {
        preRecv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String p = i.getStringExtra("pkg");
                if (p == null || !p.equals(pkg)) return;
                boolean running = i.getBooleanExtra("running", false);
                int d = i.getIntExtra("done", 0);
                int t = i.getIntExtra("total", 0);
                String ph = i.getStringExtra("phase");
                if (t > 0) {
                    preBar.setVisibility(View.VISIBLE);
                    preBar.setMax(t);
                    preBar.setProgress(Math.min(d, t));
                }
                String s = (ph == null ? "" : ph);
                if (t > 0) s += "   " + d + " / " + t + (t > 0 ? "（" + (d * 100 / t) + "%）" : "");
                preText.setText(s);
                if (!running) {
                    if (t > 0) {
                        preBar.setVisibility(View.VISIBLE);
                        preBar.setMax(t);
                        preBar.setProgress(Math.min(d, t));
                    } else {
                        preBar.setVisibility(View.GONE);
                        showLastPreload();
                    }
                    refreshCache();
                }
            }
        };
        IntentFilter f = new IntentFilter(Preload.ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(preRecv, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(preRecv, f);

        probeRecv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String p = i.getStringExtra("pkg");
                if (p != null && !p.equals(pkg)) return;
                applyProbe(i);
            }
        };
        IntentFilter pf = new IntentFilter(Config.PROBE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(probeRecv, pf, Context.RECEIVER_EXPORTED);
        else registerReceiver(probeRecv, pf);

        Preload.sendStatus(this, pkg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCache();
        if (Preload.isRunning(pkg)) Preload.sendStatus(this, pkg);
        else showLastPreload();
    }

    @Override
    protected void onDestroy() {
        try { if (preRecv != null) unregisterReceiver(preRecv); } catch (Throwable ignore) { }
        try { if (probeRecv != null) unregisterReceiver(probeRecv); } catch (Throwable ignore) { }
        super.onDestroy();
    }

    private void applyProbe(Intent i) {
        boolean tv = i.getBooleanExtra("tv", false);
        int named = i.getIntExtra("named", 0), struct = i.getIntExtra("struct", 0);
        String p = i.getStringExtra("probe");
        StringBuilder sb = new StringBuilder("能不能翻：");
        if (named > 0) sb.append("Compose 标准命名 ✓");
        else if (struct > 0) sb.append("Compose 混淆名（结构匹配 ").append(struct).append(" 个）✓");
        else sb.append("Compose ✗");
        sb.append(" · TextView ").append(tv ? "✓" : "✗");
        if (p != null && p.length() > 0) sb.append("\n").append(p);
        probeText.setText(sb.toString());
    }

    /** 静态判定：目标应用有没有联网权限（没权限就必须靠模块的「后台代翻服务」）。 */
    private void queryCapability() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String r = Scope.suMM("pm dump " + pkg + " | grep -c android.permission.INTERNET");
                int inet = parseIntSafe(r == null ? "" : r.trim().split("\n")[0]);
                String dir = Scope.suMM("ls -ld /data/user/0/" + pkg + "/files 2>/dev/null | cut -c1-10");
                final StringBuilder sb = new StringBuilder("能不能翻：");
                if (inet > 0) sb.append("有联网权限 → 可直接实时翻");
                else sb.append("没有联网权限 → 需要「后台代翻服务」（设置页开启）");
                if (dir != null && dir.trim().length() > 0) sb.append("\n译文缓存目录可写 ✓");
                sb.append("\n（界面控件形态检测需要目标应用在跑：打开一次再点「重新检测」）");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) probeText.setText(sb.toString());
                    }
                });
            }
        }).start();
    }

    private void queryProbe() {
        try {
            Intent i = new Intent(Config.ACTION);
            i.setPackage(pkg);
            i.putExtra("queryProbe", true);
            sendBroadcast(i);
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && probeText.getText().toString().endsWith("检测中…")) {
                        probeText.setText("能不能翻：目标应用没在跑（打开一次再回来点检测）");
                    }
                }
            }, 2500L);
        } catch (Throwable t) {
            probeText.setText("能不能翻：目标应用没在跑（打开一次再回来点检测）");
        }
    }

    private void refreshCache() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = Scope.suMM("wc -l < /data/user/0/" + pkg + "/files/fdtrans-cache.tsv 2>/dev/null; "
                        + "du -b /data/user/0/" + pkg + "/files/fdtrans-cache.tsv 2>/dev/null | cut -f1");
                String[] parts = out == null ? new String[0] : out.trim().split("\n");
                final int lines = parts.length > 0 ? parseIntSafe(parts[0]) : 0;
                final long bytes = parts.length > 1 ? parseIntSafe(parts[1]) : 0;
                final String req = Scope.suMM("wc -l < /data/user/0/" + pkg + "/files/fdtrans-req.tsv 2>/dev/null");
                final int waiting = req == null ? 0 : parseIntSafe(req.trim());
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        String s = "译文：" + lines + " 条" + (bytes > 0 ? "，约 " + (bytes / 1024) + " KB" : "");
                        if (waiting > 0) s += "\n待翻：" + waiting + " 条排队中（打开应用或开后台代翻服务后处理）";
                        cacheText.setText(s);
                    }
                });
            }
        }, "fdtrans-cache-read").start();
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; }
    }

    // ---------------- 小工具 ----------------

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        l.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1A1A1A);
        g.setCornerRadius(dp(14));
        l.setBackground(g);
        return l;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private Button btn(String s, int bg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        return b;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
