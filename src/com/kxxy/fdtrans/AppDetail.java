package com.kxxy.fdtrans;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Set;

/** 单个应用的详情：译文条数、清缓存、重启、移出翻译作用域。 */
public class AppDetail extends Activity {

    private String pkg;
    private String label;
    private TextView cacheText;
    private TextView probeText;
    private BroadcastReceiver probeRecv;
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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        try { iv.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Throwable ignore) {}
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

        cacheText = text("译文：读取中…", 14, 0xFFBDBDBD);
        root.addView(cacheText, lp(20));

        probeText = text("能不能翻：检测中…", 13, 0xFF9E9E9E);
        root.addView(probeText, lp(8));

        Button probeBtn = btn("重新检测能否翻译", 0xFF1B2F1B);
        probeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeText.setText("能不能翻：检测中…");
                queryProbe();
            }
        });
        root.addView(probeBtn, lp(10));

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
                toast("正在清空…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Scope.su("rm -f " + cachePath() + "; am force-stop " + pkg + "; echo DONE");
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("已清空，重开应用后会重新翻译");
                                refreshCache();
        probeRecv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!Config.PROBE.equals(i.getAction())) return;
                if (!pkg.equals(i.getStringExtra("pkg"))) return;
                applyProbe(i);
            }
        };
        try {
            IntentFilter f = new IntentFilter(Config.PROBE);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(probeRecv, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(probeRecv, f);
            }
        } catch (Throwable ignore) { }
        queryProbe();
                            }
                        });
                    }
                }, "fdtrans-clear1").start();
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
                            public void run() { toast("已停止，重新打开即可"); }
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
                toast("正在移出…约 20 秒后生效");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Set<String> s = Scope.read(AppDetail.this);
                        if (s != null) {
                            s.remove(pkg);
                            Scope.apply(AppDetail.this, new LinkedHashSet<String>(s));
                        }
                        Scope.su("am force-stop " + pkg + "; echo DONE");
                        Scope.restartLspd();
                        ui.post(new Runnable() {
                            @Override
                            public void run() { finish(); }
                        });
                    }
                }, "fdtrans-remove").start();
            }
        });
        root.addView(remove, lp(10));

        root.addView(text("译文缓存放在该应用自己的数据目录里：\n" + cachePath(), 11, 0xFF616161), lp(22));

        setContentView(sc);
        refreshCache();
    }

    private String cachePath() {
        return "/data/user/0/" + pkg + "/files/fdtrans-cache.tsv";
    }

    @Override
    protected void onDestroy() {
        if (probeRecv != null) { try { unregisterReceiver(probeRecv); } catch (Throwable ignore) { } }
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

    private void queryProbe() {
        try {
            Intent i = new Intent(Config.ACTION);
            i.setPackage(pkg);
            i.putExtra("queryProbe", true);
            sendBroadcast(i);
        } catch (Throwable t) {
            probeText.setText("能不能翻：目标应用没在跑（打开一次再回来）");
        }
    }

    private void refreshCache() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = Scope.su("wc -l < " + cachePath() + " 2>/dev/null; "
                        + "ls -l " + cachePath() + " 2>/dev/null | awk '{print $5}'");
                final String o = out == null ? "" : out.trim();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        String[] ls = o.split("\n");
                        String lines = ls.length > 0 ? ls[0].trim() : "0";
                        String bytes = ls.length > 1 ? ls[1].trim() : "0";
                        cacheText.setText("译文：" + lines + " 条"
                                + ("0".equals(bytes) ? "（还没有缓存）" : "，约 " + bytes + " 字节"));
                    }
                });
            }
        }, "fdtrans-cacheinfo").start();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0f, 1.3f);
        return t;
    }

    private Button btn(String s, int bg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(0xFFFFFFFF);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        return b;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
