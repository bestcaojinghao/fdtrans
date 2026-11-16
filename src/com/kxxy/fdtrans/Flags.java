package com.kxxy.fdtrans;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 跨进程传开关：F-Droid 进程读不到模块私有 prefs（uid 不同、文件 660），
 * 所以由模块自己暴露一个只读 ContentProvider，供 hook 侧 call() 取配置。
 */
public class Flags extends ContentProvider {

    public static final String AUTHORITY = "com.kxxy.fdtrans.flags";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!"get".equals(method)) return out;
        Context c = getContext();
        if (c == null) return out;
        SharedPreferences sp = c.getSharedPreferences("fdtrans", Context.MODE_PRIVATE);
        out.putBoolean("enabled", sp.getBoolean("enabled", true));
        out.putString("engine", sp.getString("engine", "mymemory"));
        out.putLong("clearCacheAt", sp.getLong("clearCacheAt", 0L));
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
