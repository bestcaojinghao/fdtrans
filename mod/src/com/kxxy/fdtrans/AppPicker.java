package com.kxxy.fdtrans;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 应用选择器：图标 + 名称 + 包名，点一下就加入/移出翻译作用域。 */
public class AppPicker extends Activity {

    private static class Entry {
        String label;
        String pkg;
        Drawable icon;
        String hay;          // 小写搜索用
    }

    private final List<Entry> all = new ArrayList<Entry>();
    private final List<Entry> shown = new ArrayList<Entry>();
    private final Set<String> scoped = new LinkedHashSet<String>();

    private EditText search;
    private ListView list;
    private TextView footer;
    private Adapter adapter;
    private boolean dirty;
    private android.widget.Button saveBtn;
    private boolean loading = true;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101010);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, dp(8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("选择要翻译的应用");
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, dp(4));
        topRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        saveBtn = new android.widget.Button(this);
        saveBtn.setText("保存");
        saveBtn.setAllCaps(false);
        saveBtn.setTextSize(13);
        saveBtn.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(0xFF00695C);
        sg.setCornerRadius(dp(12));
        saveBtn.setBackground(sg);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { saveAndExit(); }
        });
        topRow.addView(saveBtn);
        root.addView(topRow);

        TextView hint = new TextView(this);
        hint.setText("点一下应用名即加入（右边显示“已加入”），再点一下移出。"
                + "返回时自动重启 lspd 使改动生效（约 20 秒）。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF9E9E9E);
        hint.setPadding(0, 0, 0, dp(10));
        root.addView(hint);

        search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(0xFFEEEEEE);
        search.setHintTextColor(0xFF777777);
        search.setBackgroundColor(0xFF1E1E1E);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { filter(e.toString()); }
        });

        list = new ListView(this);
        list.setDivider(null);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= shown.size()) return;
                toggle(shown.get(pos).pkg);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footer = new TextView(this);
        footer.setTextSize(12);
        footer.setTextColor(0xFF9E9E9E);
        footer.setPadding(0, dp(8), 0, dp(4));
        footer.setText("读取作用域中…");
        root.addView(footer);

        setContentView(root);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Entry> es = loadApps();       // 先出列表，root 慢不挡 UI
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        all.clear();
                        all.addAll(es);
                        filter(search.getText().toString());
                    }
                });
                final Set<String> s = Scope.read(AppPicker.this);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (s != null) {
                            scoped.clear();
                            scoped.addAll(s);
                        }
                        filter(search.getText().toString());
                        if (s == null) {
                            footer.setText("读不到 LSPosed 配置（root 没授权？），"
                                    + "下面只显示已装应用，点了可能加不进去");
                        }
                    }
                });
            }
        }, "fdtrans-picker").start();
    }

    private List<Entry> loadApps() {
        List<Entry> out = new ArrayList<Entry>();
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            return out;
        }
        for (ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            if (pkg == null) continue;
            if (pkg.equals(getPackageName())) continue;
            if (pkg.startsWith("com.kxxy.")) continue;
            if (pkg.equals("android")) continue;
            Entry e = new Entry();
            e.pkg = pkg;
            CharSequence lb = null;
            try { lb = pm.getApplicationLabel(ai); } catch (Throwable ignored) {}
            e.label = (lb == null || lb.length() == 0) ? pkg : lb.toString();
            try { e.icon = ai.loadIcon(pm); } catch (Throwable ignored) {}
            e.hay = (e.label + " " + pkg).toLowerCase(Locale.ROOT);
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = a.label.compareToIgnoreCase(b.label);
                return c != 0 ? c : a.pkg.compareTo(b.pkg);
            }
        });
        return out;
    }

    private void filter(String q) {
        String k = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Entry e : all) {
            if (k.length() == 0 || e.hay.indexOf(k) >= 0) shown.add(e);
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        int n = scoped.size();
        footer.setText("共 " + all.size() + " 个应用，显示 " + shown.size()
                + " 个；已勾选：" + n + " 个" + (dirty ? "（未保存，点右上角「保存」）" : ""));
    }

    private void toggle(final String pkg) {
        final boolean add = !scoped.contains(pkg);
        if (add) scoped.add(pkg); else scoped.remove(pkg);
        dirty = true;
        adapter.notifyDataSetChanged();
        filter(search.getText().toString());
        toast(add ? "已勾选：" + pkg : "已取消：" + pkg);
    }

    /** 一次性写入作用域并重启 lspd（每点一次就重启会太暴力）。 */
    private void saveAndExit() {
        if (!dirty) { finish(); return; }
        toast("正在保存，lspd 会重启，约 20 秒后生效");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Scope.apply(AppPicker.this, new LinkedHashSet<String>(scoped));
                Scope.restartLspd();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? "已保存" : "写入失败（root 未授权？）");
                        finish();
                    }
                });
            }
        }, "fdtrans-scope-save").start();
    }

    @Override
    public void onBackPressed() {
        if (dirty) { saveAndExit(); return; }
        super.onBackPressed();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() { return shown.size(); }

        @Override
        public Object getItem(int i) { return shown.get(i); }

        @Override
        public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row;
            if (cv instanceof LinearLayout) {
                row = (LinearLayout) cv;
            } else {
                row = new LinearLayout(AppPicker.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(10), dp(4), dp(10));
                row.setBackgroundColor(0xFF161616);
            }
            row.removeAllViews();
            Entry e = shown.get(pos);

            ImageView iv = new ImageView(AppPicker.this);
            if (e.icon != null) iv.setImageDrawable(e.icon);
            row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));

            LinearLayout col = new LinearLayout(AppPicker.this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(14), 0, dp(8), 0);
            TextView name = new TextView(AppPicker.this);
            name.setText(e.label);
            name.setTextSize(15);
            name.setTextColor(0xFFFFFFFF);
            col.addView(name);
            TextView pkg = new TextView(AppPicker.this);
            pkg.setText(e.pkg);
            pkg.setTextSize(11);
            pkg.setTextColor(0xFF888888);
            col.addView(pkg);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView st = new TextView(AppPicker.this);
            boolean on = scoped.contains(e.pkg);
            st.setText(on ? "已加入" : "未加入");
            st.setTextSize(12);
            st.setTextColor(on ? 0xFF4CAF50 : 0xFF666666);
            row.addView(st);

            return row;
        }
    }
}
