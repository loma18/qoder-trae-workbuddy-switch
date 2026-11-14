package com.buddyswitch.agent;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地配置。全部只存在 App 私有目录，不上传任何第三方。
 * v2.0：账号凭据改存 {@link AccountStore}（secure_store），这里只留设置项。
 */
public final class Prefs {
    private static final String FILE = "buddy_switch_agent";
    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String get(Context c, String key, String def) { return sp(c).getString(key, def); }
    public static void put(Context c, String key, String value) { sp(c).edit().putString(key, value).apply(); }
    public static boolean getBool(Context c, String key, boolean def) { return sp(c).getBoolean(key, def); }
    public static void putBool(Context c, String key, boolean v) { sp(c).edit().putBoolean(key, v).apply(); }
    public static int getInt(Context c, String key, int def) { return sp(c).getInt(key, def); }
    public static void putInt(Context c, String key, int v) { sp(c).edit().putInt(key, v).apply(); }

    // ---- 可选：同步到电脑（lansync，默认端口 57900） ----
    public static final String K_SYNC_HOST = "sync_host";
    public static final String K_SYNC_PORT = "sync_port";
    public static final String K_SYNC_CODE = "sync_code";

    // ---- 本机行为 ----
    public static final String K_DEMO = "demo_mode";
    public static final String K_AUTO = "auto_checkin";
    public static final String K_INTERVAL = "auto_interval_min";
    public static final String K_NOTIFY = "notify_enabled";
    public static final String K_LOG = "log";
    public static final String K_LAST_CHECKIN = "last_checkin";
    public static final String K_LAST_GROWTH = "last_growth";

    public static String syncHost(Context c) { return get(c, K_SYNC_HOST, ""); }
    public static int syncPort(Context c) { int v = getInt(c, K_SYNC_PORT, 57900); return v <= 0 ? 57900 : v; }
    public static String syncCode(Context c) { return get(c, K_SYNC_CODE, ""); }
    public static boolean demo(Context c) { return getBool(c, K_DEMO, false); }
    public static boolean autoCheckin(Context c) { return getBool(c, K_AUTO, false); }
    public static int intervalMin(Context c) { int v = getInt(c, K_INTERVAL, 360); return v < 15 ? 15 : v; }
    public static boolean notifyEnabled(Context c) { return getBool(c, K_NOTIFY, true); }

    /** 写一行带时间戳的本地日志（保留最近约 60 条）。 */
    public static synchronized void log(Context c, String line) {
        String old = get(c, K_LOG, "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\\n");
        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis()).append('\t')
                .append(line == null ? "" : line.replace('\n', ' ')).append('\n');
        int keep = Math.min(lines.length, 59);
        for (int i = 0; i < keep; i++) sb.append(lines[i]).append('\n');
        put(c, K_LOG, sb.toString());
    }
}
