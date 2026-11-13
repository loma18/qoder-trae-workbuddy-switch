package com.buddyswitch.agent;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地配置。全部只存在 App 私有目录，不上传任何第三方。
 * 对应桌面端「设置 → 通用」里的服务地址 / 自动签到配置。
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

    public static final String K_HOST = "host";
    public static final String K_PORT = "port";
    public static final String K_SCHEME = "scheme";
    public static final String K_TOKEN = "token";
    public static final String K_SCOPE = "scope";
    public static final String K_DEMO = "demo_mode";
    public static final String K_AUTO = "auto_checkin";
    public static final String K_INTERVAL = "auto_interval_min";
    public static final String K_NOTIFY = "notify_enabled";
    public static final String K_CONFIGURED = "configured";
    public static final String K_LAST_CHECKIN = "last_checkin";
    public static final String K_LOG = "log";

    public static String host(Context c) { return get(c, K_HOST, "192.168.1.1"); }
    public static int port(Context c) { return getInt(c, K_PORT, 57890); }
    public static String scheme(Context c) { String s = get(c, K_SCHEME, "http"); return "https".equals(s) ? "https" : "http"; }
    public static String token(Context c) { return get(c, K_TOKEN, ""); }
    public static String scope(Context c) { return get(c, K_SCOPE, "wb-cn"); }
    public static boolean demo(Context c) { return getBool(c, K_DEMO, false); }
    public static boolean autoCheckin(Context c) { return getBool(c, K_AUTO, false); }
    public static int intervalMin(Context c) { int v = getInt(c, K_INTERVAL, 360); return v < 15 ? 15 : v; }
    public static boolean notifyEnabled(Context c) { return getBool(c, K_NOTIFY, true); }

    /** 完整服务基址，末尾不带斜杠。 */
    public static String baseUrl(Context c) {
        return scheme(c) + "://" + host(c) + ":" + port(c);
    }

    /** 写一行带时间戳的本地日志（保留最近约 60 条）。 */
    public static synchronized void log(Context c, String line) {
        String old = get(c, K_LOG, "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\\n");
        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis()).append('\t').append(line.replace('\n', ' ')).append('\n');
        int keep = Math.min(lines.length, 59);
        for (int i = 0; i < keep; i++) sb.append(lines[i]).append('\n');
        put(c, K_LOG, sb.toString());
    }
}
