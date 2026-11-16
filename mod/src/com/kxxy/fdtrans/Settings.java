package com.kxxy.fdtrans;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 主界面：通用实时翻译。
 * 纯代码构建 UI（模块 APK 不带资源表，避免 aapt2 资源依赖）。
 */
public class Settings extends Activity {

    static class Row {
        String pkg;
        String label;
        Drawable icon;
        int cache;
        boolean known;
        String probe;
    }

    private SharedPreferences sp;
    private final Set<String> scoped = new LinkedHashSet<String>();
    private final List<Row> rows = new ArrayList<Row>();
    private ListView list;
    private TextView countText;
    private TextView noteText;
    private Adapter adapter;
    private Switch swOn;
    private Switch swNames;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private android.widget.ProgressBar liveBar;
    private android.content.BroadcastReceiver statRecv;
    /** pkg -> {缓存条数, 本次新增, 是否正在翻} */
    private final java.util.Map<String, int[]> stat = new java.util.HashMap<String, int[]>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("fdtrans", Context.MODE_PRIVATE);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF0E0E0E);
        sc.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(28), pad, dp(28));
        sc.addView(root);

        // ---- 标题 ----
        TextView title = text("实时翻译", 25, 0xFFFFFFFF);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);
        root.addView(text("界面上的英文 → 中文，随界面自动替换（LSPosed 模块）", 13, 0xFF9E9E9E), lp(6));

        // ---- 开关 ----
        LinearLayout card1 = card();
        swOn = new Switch(this);
        swOn.setText("启用实时翻译");
        swOn.setTextSize(16);
        swOn.setTextColor(0xFFEEEEEE);
        swOn.setChecked(sp.getBoolean("enabled", true));
        swOn.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton btn, boolean v) {
                sp.edit().putBoolean("enabled", v).apply();
                Push.bool(Settings.this, scoped, "enabled", v);
                toast(v ? "已开启，正在运行的应用立刻生效" : "已关闭，正在运行的应用立刻恢复英文");
            }
        });
        card1.addView(swOn, lp(0));

        swNames = new Switch(this);
        swNames.setText("应用名保留英文（括号里给中文）");
        swNames.setTextSize(15);
        swNames.setTextColor(0xFFEEEEEE);
        swNames.setChecked(sp.getBoolean("skipNames", true));
        swNames.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton btn, boolean v) {
                sp.edit().putBoolean("skipNames", v).apply();
                Push.bool(Settings.this, scoped, "skipNames", v);
                toast(v ? "应用名保留英文，例如 NodeVideo（节点视频）" : "应用名也会被直接翻译");
            }
        });
        card1.addView(swNames, lp(14));

        Switch swDbg = new Switch(this);
        swDbg.setText("诊断日志（排查某些应用没翻译）");
        swDbg.setTextSize(15);
        swDbg.setTextColor(0xFFEEEEEE);
        swDbg.setChecked(sp.getBoolean("debug", false));
        swDbg.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton btn, boolean v) {
                sp.edit().putBoolean("debug", v).apply();
                Push.bool(Settings.this, scoped, "debug", v);
                toast(v ? "已开：logcat 里看 FDTrans 的 hook skip / skip# 日志" : "已关诊断日志");
            }
        });
        card1.addView(swDbg, lp(10));
        card1.addView(text("开关会推给所有已加入的应用；没在运行的下次打开时沿用上次保存的值。",
                12, 0xFF7E7E7E), lp(12));
        root.addView(card1, lp(20));

        // ---- 已加入的应用 ----
        LinearLayout card2 = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = text("已加入翻译的应用", 15, 0xFF80CBC4);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        head.addView(h, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        countText = text("", 13, 0xFF9E9E9E);
        head.addView(countText);
        card2.addView(head, lp(0));

        list = new ListView(this);
        list.setDivider(null);
        list.setBackgroundColor(0x00000000);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= rows.size()) return;
                Intent i = new Intent(Settings.this, AppDetail.class);
                i.putExtra("pkg", rows.get(pos).pkg);
                i.putExtra("label", rows.get(pos).label);
                startActivity(i);
            }
        });
        card2.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        noteText = text("读取中…", 12, 0xFF9E9E9E);
        card2.addView(noteText, lp(8));

        liveBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        liveBar.setIndeterminate(true);
        liveBar.setVisibility(View.GONE);
        card2.addView(liveBar, lp(6));

        LinearLayout rowBtns = new LinearLayout(this);
        rowBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button add = btn("＋ 添加应用", 0xFF00695C);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.this, AppPicker.class));
            }
        });
        rowBtns.addView(add, weight(1f));
        Button refresh = btn("刷新", 0xFF2A2A2A);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { reload(); }
        });
        rowBtns.addView(refresh, weight(1f));
        card2.addView(rowBtns, lp(12));
        root.addView(card2, lp(18));

        // ---- 引擎 ----
        LinearLayout card3 = card();
        card3.addView(text("翻译引擎", 15, 0xFF80CBC4), lp(0));
        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbBing = new RadioButton(this);
        rbBing.setText("Bing（推荐，质量好、快）");
        rbBing.setTextSize(14);
        rbBing.setTextColor(0xFFDDDDDD);
        rbBing.setId(1001);
        rg.addView(rbBing);
        RadioButton rbMm = new RadioButton(this);
        rbMm.setText("MyMemory（备用，配额小）");
        rbMm.setTextSize(14);
        rbMm.setTextColor(0xFFDDDDDD);
        rbMm.setId(1002);
        rg.addView(rbMm);
        final boolean bing = !"mymemory".equals(sp.getString("engine", "bing"));
        rg.check(bing ? 1001 : 1002);
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                String e = id == 1002 ? "mymemory" : "bing";
                sp.edit().putString("engine", e).apply();
                toast("已切换到 " + ("mymemory".equals(e) ? "MyMemory" : "Bing"));
            }
        });
        card3.addView(rg, lp(6));
        card3.addView(text("换引擎后要重开目标应用才生效（翻译在目标应用的进程里跑）。",
                12, 0xFF7E7E7E), lp(8));
        root.addView(card3, lp(18));

        // ---- 维护 ----
        LinearLayout card4 = card();
        card4.addView(text("维护", 15, 0xFF80CBC4), lp(0));
        Button restart = btn("重启 lspd（改完作用域后）", 0xFF2A2A2A);
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("正在重启 lspd，约 20 秒后恢复");
                new Thread(new Runnable() {
                    @Override
                    public void run() { Scope.restartLspd(); }
                }, "fdtrans-lspd").start();
            }
        });
        card4.addView(restart, lp(12));
        Button bridge = btn(Bridge.running ? "关闭后台代翻服务" : "开启后台代翻服务（没联网权限的应用）", 0xFF2E4A44);
        bridge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Bridge.running) {
                    stopService(new Intent(Settings.this, Bridge.class));
                    toast("后台代翻已关闭");
                } else {
                    Intent i = new Intent(Settings.this, Bridge.class);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                    toast("后台代翻已开启：没有联网权限的应用也能翻");
                }
                ui.postDelayed(new Runnable() {
                    @Override public void run() { reload(); }
                }, 600L);
            }
        });
        card4.addView(bridge, lp(10));
        card4.addView(text("后台代翻 = 由本模块（有联网权限）替目标应用翻译。"
                + "目标应用自己没有网络（缺 INTERNET 权限、或冷启动后台进程被系统限制）时用它。",
                12, 0xFF7E7E7E), lp(8));
        Button clearAll = btn("清空全部应用的译文缓存", 0xFF303030);
        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("正在清空…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        StringBuilder sb = new StringBuilder();
                        for (String p : scoped) {
                            sb.append("rm -f /data/user/0/").append(p)
                              .append("/files/fdtrans-cache.tsv; ")
                              .append("am force-stop ").append(p).append("; ");
                        }
                        Scope.su(sb.toString() + "echo DONE");
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                toast("已清空，重开应用即可重新翻译");
                                reload();
                            }
                        });
                    }
                }, "fdtrans-clear").start();
            }
        });
        card4.addView(clearAll, lp(10));
        root.addView(card4, lp(18));

        // ---- 说明 ----
        LinearLayout card5 = card();
        card5.addView(text("说明", 15, 0xFF80CBC4), lp(0));
        card5.addView(text(
                "• 译文按“应用”各自缓存，换应用不会重复请求\n"
                + "• 中文、数字、版本号、URL、包名、文件大小不会被翻\n"
                + "• 品牌名（PipePipe、Bitwarden 这类）机器翻译本身不翻，会原样保留\n"
                + "• 界面上新出现的英文，首次显示仍是英文，不到一秒后自动变成中文\n"
                + "• 加进来的应用要重开一次才会被 hook", 13, 0xFFBDBDBD), lp(8));
        root.addView(card5, lp(18));

        root.addView(text("v" + version() + "  ·  通用模式（作用域内任意应用）", 11, 0xFF616161), lp(18));

        setContentView(sc);
        handleAutoPreload(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleAutoPreload(i);
    }

    /** 应用在前台时启动后台服务（后台状态下 startForegroundService 会被系统拒绝）。 */
    private static boolean fgAsked;

    private void startWorkers() {
        try {
            if (fgAsked) {
                // 已经请求过一次就只确保循环线程在跑（服务重复 start 会被系统记 warning）
                Bridge.startLoop(this);
            } else {
                fgAsked = true;
                startForegroundService(new Intent(this, Bridge.class));
            }
            android.util.Log.i("FDTrans", "settings startWorkers: Bridge requested");
        } catch (Throwable t) {
            android.util.Log.w("FDTrans", "settings startWorkers bridge err " + t);
        }
        try {
            if (Preload.pending(this) != null) {
                startForegroundService(new Intent(this, PreloadService.class));
                android.util.Log.i("FDTrans", "settings startWorkers: PreloadService requested");
            }
        } catch (Throwable t) {
            android.util.Log.w("FDTrans", "settings startWorkers preload err " + t);
        }
    }

    /** 便于自动化/排障：am start ... --es preload <包名> 直接触发预翻（onCreate 与 onNewIntent 都要处理）。 */
    private void handleAutoPreload(android.content.Intent i) {
        try {
            String auto = i == null ? null : i.getStringExtra("preload");
            if (auto == null || auto.length() == 0) return;
            i.removeExtra("preload");
            boolean ok = Preload.start(getApplicationContext(), auto);
            android.util.Log.i("FDTrans", "auto preload intent: " + auto + " started=" + ok);
            toast(ok ? ("开始预翻：" + auto) : ("正在预翻 " + Preload.runningPkg()));
        } catch (Throwable ignore) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 等窗口真正可见后再请求前台服务：系统按"进程是否可见"判定，
        // onResume 时窗口还没画出来（uidState=RCVR），会被判成后台启动而拒绝。
        ui.postDelayed(new Runnable() {
            @Override
            public void run() { startWorkers(); }
        }, 1500L);
        if (swOn != null) swOn.setChecked(sp.getBoolean("enabled", true));
        if (swNames != null) swNames.setChecked(sp.getBoolean("skipNames", true));
        startStatReceiver();
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStatReceiver();
    }

    private void startStatReceiver() {
        if (statRecv != null) return;
        statRecv = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String pkg = i.getStringExtra("pkg");
                if (pkg == null) return;
                int cache = i.getIntExtra("cache", -1);
                int added = i.getIntExtra("new", 0);
                boolean active = i.getBooleanExtra("active", false);
                int seen = i.getIntExtra("seen", 0);
                stat.put(pkg, new int[]{cache < 0 ? 0 : cache, added, active ? 1 : 0, seen});
                applyStat();
            }
        };
        IntentFilter f = new IntentFilter(Config.STAT);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statRecv, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(statRecv, f);
        }
    }

    private void stopStatReceiver() {
        if (statRecv == null) return;
        try { unregisterReceiver(statRecv); } catch (Throwable ignore) {}
        statRecv = null;
    }

    /** 把各目标应用上报的统计刷到列表和进度条上。 */
    private void applyStat() {
        boolean anyActive = false;
        int totalCache = 0;
        int busyAdded = 0, busySeen = 0;
        String busyLabel = null;
        for (Row r : rows) {
            int[] st = stat.get(r.pkg);
            if (st == null) continue;
            r.cache = st[0];
            r.known = true;
            totalCache += st[0];
            if (st[2] == 1) {
                anyActive = true;
                busyLabel = r.label;
                busyAdded += st[1];
                busySeen += st[3];
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (liveBar != null) {
            if (anyActive) {
                liveBar.setVisibility(View.VISIBLE);
                if (busySeen > 0 && busyAdded <= busySeen) {
                    liveBar.setIndeterminate(false);
                    liveBar.setMax(busySeen);
                    liveBar.setProgress(Math.min(busyAdded, busySeen));
                } else {
                    liveBar.setIndeterminate(true);
                }
            } else {
                liveBar.setVisibility(View.GONE);
            }
        }
        if (noteText != null) {
            if (anyActive) {
                String pct = busySeen > 0
                        ? "（已完成 " + busyAdded + " / " + busySeen + " 条，"
                          + (busyAdded * 100 / Math.max(1, busySeen)) + "%）"
                        : "（本次已新增 " + busyAdded + " 条）";
                noteText.setText("正在翻译：" + busyLabel + " " + pct
                        + "  缓存合计 " + totalCache + " 条");
            } else if (!rows.isEmpty()) {
                noteText.setText("点一行进入该应用的详情（清缓存 / 重启 / 移出）。缓存合计 "
                        + totalCache + " 条");
            }
        }
    }

    /** 问每个目标应用要一次统计（广播进不去说明那个应用没在跑）。 */
    private void queryStats() {
        for (String p : scoped) {
            try {
                Intent q = new Intent(Config.ACTION);
                q.setPackage(p);
                q.putExtra("queryStat", true);
                sendBroadcast(q);
            } catch (Throwable ignore) {}
        }
    }

    /** 读作用域 + 每个应用的译文条数。 */
    private void reload() {
        if (noteText != null) noteText.setText("读取作用域中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Set<String> s = Scope.read(Settings.this);
                final List<Row> out = new ArrayList<Row>();
                if (s != null) {
                    StringBuilder cmd = new StringBuilder();
                    for (String p : s) {
                        cmd.append("n=$(wc -l < /data/user/0/").append(p)
                           .append("/files/fdtrans-cache.tsv 2>/dev/null); echo \"")
                           .append(p).append(" ${n:-0}\"; ")
                           .append("echo \"P ").append(p).append(" $(grep '^probe=' /data/user/0/")
                           .append(p).append("/files/fdtrans-probe.txt 2>/dev/null | cut -c7-)\"; ");
                    }
                    String res = cmd.length() == 0 ? "" : Scope.suMM(cmd.toString());
                    java.util.Map<String, Integer> cnt = new java.util.HashMap<String, Integer>();
                    java.util.Map<String, String> probe = new java.util.HashMap<String, String>();
                    for (String line : res.split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("P ")) {
                            int sp = t.indexOf(' ', 2);
                            if (sp > 2) probe.put(t.substring(2, sp), t.substring(sp + 1).trim());
                            continue;
                        }
                        String[] kv = t.split("\\s+");
                        if (kv.length >= 2) {
                            try { cnt.put(kv[0], Integer.parseInt(kv[1])); } catch (Throwable ignore) {}
                        }
                    }
                    for (String p : s) {
                        Row r = new Row();
                        r.pkg = p;
                        r.label = labelOf(p);
                        r.cache = cnt.containsKey(p) ? cnt.get(p) : 0;
                        r.known = cnt.containsKey(p);
                        String pr = probe.get(p);
                        if (pr != null && pr.length() > 0) r.probe = pr;
                        out.add(r);
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        scoped.clear();
                        if (s != null) scoped.addAll(s);
                        rows.clear();
                        rows.addAll(out);
                        for (Row r : rows) r.icon = iconOf(r.pkg);
                        adapter.notifyDataSetChanged();
                        countText.setText(rows.isEmpty() ? "（空）" : rows.size() + " 个");
                        if (s == null) {
                            noteText.setText("读不到 LSPosed 配置（root 没授权？）。"
                                    + "点「＋ 添加应用」会再请求一次。");
                        } else if (rows.isEmpty()) {
                            noteText.setText("还没有加入任何应用。点「＋ 添加应用」勾选要翻译的应用。");
                        } else {
                            int total = 0;
                            for (Row r : rows) total += r.cache;
                            noteText.setText("点一行进入该应用的详情（清缓存 / 重启 / 移出）。"
                                    + "合计译文 " + total + " 条");
                            queryStats();
                        }
                    }
                });
            }
        }, "fdtrans-reload").start();
    }

    private String labelOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private Drawable iconOf(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---- UI 小工具 ----
    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0f, 1.3f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1A1A1A);
        g.setCornerRadius(dp(18));
        l.setBackground(g);
        l.setPadding(dp(20), dp(18), dp(20), dp(18));
        return l;
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

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.rightMargin = dp(6);
        return p;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() { return rows.size(); }

        @Override
        public Object getItem(int i) { return rows.get(i); }

        @Override
        public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row;
            if (cv instanceof LinearLayout) {
                row = (LinearLayout) cv;
            } else {
                row = new LinearLayout(Settings.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(2), dp(9), dp(2), dp(9));
            }
            row.removeAllViews();
            Row r = rows.get(pos);

            ImageView iv = new ImageView(Settings.this);
            if (r.icon != null) iv.setImageDrawable(r.icon);
            row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));

            LinearLayout col = new LinearLayout(Settings.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), 0, dp(8), 0);
            TextView name = text(r.label, 15, 0xFFFFFFFF);
            col.addView(name);
            String cnt = r.known ? ("译文 " + r.cache + " 条") : "未运行，打开一次后统计";
            String badge = "";
            if (r.probe != null) {
                if (r.probe.contains("自绘") || r.probe.contains("找不到可 hook")) badge = " · ⚠ 可能翻不到";
                else badge = " · ✓ 可翻";
            }
            TextView sub = text(r.pkg + " · " + cnt + badge, 11,
                    r.probe != null && r.probe.contains("自绘") ? 0xFFB08050 : 0xFF888888);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView arrow = text("›", 20, 0xFF666666);
            row.addView(arrow);
            return row;
        }
    }
}
