package com.buddyswitch.agent;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * WebView ↔ 原生桥。前端 SPA 只通过这里访问网络 / 本地配置 / 系统能力。
 *
 * <p>为什么要桥接而不是让 WebView 直接 fetch：页面来自 {@code file://}，
 * 请求 {@code http://} 属于跨源，而服务端没有 CORS 头；统一走 Java 的
 * HttpURLConnection 还能精确控制 Header，满足 {@code api_guard} 的来源校验。
 */
public final class JsBridge {
    private final Context ctx;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ApiClient api;

    public JsBridge(Context ctx, WebView web) {
        this.ctx = ctx.getApplicationContext();
        this.web = web;
        this.api = new ApiClient(ctx);
    }

    // ------------------------------------------------------------------ 配置

    @JavascriptInterface
    public String getConfig() {
        try {
            JSONObject o = new JSONObject();
            o.put("host", Prefs.host(ctx));
            o.put("port", Prefs.port(ctx));
            o.put("scheme", Prefs.scheme(ctx));
            o.put("token", Prefs.token(ctx));
            o.put("scope", Prefs.scope(ctx));
            o.put("demo", Prefs.demo(ctx));
            o.put("autoCheckin", Prefs.autoCheckin(ctx));
            o.put("intervalMin", Prefs.intervalMin(ctx));
            o.put("notify", Prefs.notifyEnabled(ctx));
            o.put("configured", Prefs.getBool(ctx, Prefs.K_CONFIGURED, false));
            o.put("baseUrl", Prefs.baseUrl(ctx));
            o.put("version", "1.0.0");
            o.put("sdk", Build.VERSION.SDK_INT);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface
    public void setConfig(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("host")) Prefs.put(ctx, Prefs.K_HOST, o.optString("host", "192.168.1.1").trim());
            if (o.has("port")) Prefs.putInt(ctx, Prefs.K_PORT, o.optInt("port", 57890));
            if (o.has("scheme")) Prefs.put(ctx, Prefs.K_SCHEME, o.optString("scheme", "http"));
            if (o.has("token")) Prefs.put(ctx, Prefs.K_TOKEN, o.optString("token", ""));
            if (o.has("scope")) Prefs.put(ctx, Prefs.K_SCOPE, o.optString("scope", "wb-cn"));
            if (o.has("demo")) Prefs.putBool(ctx, Prefs.K_DEMO, o.optBoolean("demo", false));
            if (o.has("notify")) Prefs.putBool(ctx, Prefs.K_NOTIFY, o.optBoolean("notify", true));
            if (o.has("configured")) Prefs.putBool(ctx, Prefs.K_CONFIGURED, o.optBoolean("configured", true));
            if (o.has("autoCheckin") || o.has("intervalMin")) {
                boolean en = o.has("autoCheckin") ? o.optBoolean("autoCheckin") : Prefs.autoCheckin(ctx);
                int iv = o.has("intervalMin") ? o.optInt("intervalMin", 360) : Prefs.intervalMin(ctx);
                Prefs.putBool(ctx, Prefs.K_AUTO, en);
                Prefs.putInt(ctx, Prefs.K_INTERVAL, Math.max(15, iv));
                AutoCheckinReceiver.schedule(ctx);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * 异步请求。完成后回调 JS 的 {@code window.__bsCallback(cbId, ok, status, body)}。
     * body 原样是服务端返回的文本（通常是 JSON 字符串）。
     */
    @JavascriptInterface
    public void request(final String cbId, final String method, final String path, final String body) {
        new Thread(new Runnable() {
            @Override public void run() {
                final ApiClient.Result r = api.request(method, path, body);
                final String payload = r.ok ? r.body : errJson(r.error);
                main.post(new Runnable() {
                    @Override public void run() {
                        try {
                            String js = "window.__bsCallback(" + JSONObject.quote(cbId) + ","
                                    + (r.ok ? "true" : "false") + "," + r.status + ","
                                    + JSONObject.quote(payload) + ")";
                            web.evaluateJavascript(js, null);
                        } catch (Exception ignored) { }
                    }
                });
            }
        }, "bs-http").start();
    }

    private static String errJson(String msg) {
        try { return new JSONObject().put("error", msg).toString(); }
        catch (Exception e) { return "{\"error\":\"unknown\"}"; }
    }

    // ------------------------------------------------------------------ 系统能力

    @JavascriptInterface
    public void setAutoCheckin(boolean enabled, int intervalMin) {
        Prefs.putBool(ctx, Prefs.K_AUTO, enabled);
        Prefs.putInt(ctx, Prefs.K_INTERVAL, Math.max(15, intervalMin));
        AutoCheckinReceiver.schedule(ctx);
        Prefs.log(ctx, "自动签到 " + (enabled ? "已开启，间隔 " + intervalMin + " 分钟" : "已关闭"));
    }

    @JavascriptInterface
    public void toast(String msg) {
        main.post(new Runnable() {
            @Override public void run() { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); }
        });
    }

    @JavascriptInterface
    public void notify(String title, String msg) { showNotification(title, msg); }

    @JavascriptInterface
    public void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public String getLog() { return Prefs.get(ctx, Prefs.K_LOG, ""); }

    @JavascriptInterface
    public void appendLog(String line) { Prefs.log(ctx, line); }

    @JavascriptInterface
    public void clearLog() { Prefs.put(ctx, Prefs.K_LOG, ""); }

    @JavascriptInterface
    public String lastCheckin() { return Prefs.get(ctx, Prefs.K_LAST_CHECKIN, ""); }

    // ------------------------------------------------------------------ 通知

    public void showNotification(String title, String msg) {
        try {
            if (!Prefs.notifyEnabled(ctx)) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String channel = "buddy_switch_checkin";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channel,
                        "自动签到", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("定时签到结果与异常提醒");
                nm.createNotificationChannel(ch);
            }
            Intent intent = new Intent(ctx, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 1, intent, flags);

            android.app.Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new android.app.Notification.Builder(ctx, channel)
                    : new android.app.Notification.Builder(ctx);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title == null ? "Buddy Switch" : title)
                    .setContentText(msg == null ? "" : msg)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(msg == null ? "" : msg))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() & 0xffff), b.build());
        } catch (Exception ignored) { }
    }
}
