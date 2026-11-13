package com.buddyswitch.agent;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 定时自动签到。对应桌面端「设置 → 自动签到」与 core 的 {@code checkin} / {@code scheduler} 模块。
 *
 * <p>用 AlarmManager（RTC_WAKEUP + setExactAndAllowWhileIdle）链式调度，
 * 每次执行完再排下一次，避免 Android 后台限制把重复闹钟吞掉。
 * 所有请求都发往用户自己填写的服务端，App 不直连任何第三方平台。
 */
public final class AutoCheckinReceiver extends BroadcastReceiver {
    private static final int REQ = 7701;

    public static void schedule(Context c) {
        Context ctx = c.getApplicationContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx);
        am.cancel(pi);
        if (!Prefs.autoCheckin(ctx) || !Prefs.getBool(ctx, Prefs.K_CONFIGURED, false)) return;
        long interval = (long) Prefs.intervalMin(ctx) * 60L * 1000L;
        long trigger = System.currentTimeMillis() + Math.max(15 * 60 * 1000L, interval);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (Exception ignored) { }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, AutoCheckinReceiver.class);
        i.setAction("com.buddyswitch.agent.AUTO_CHECKIN");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final Context ctx = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!Prefs.autoCheckin(ctx)) return;
                    runCheckin(ctx);
                } finally {
                    schedule(ctx);           // 链式排下一次
                    pr.finish();
                }
            }
        }, "bs-checkin").start();
    }

    /** 供前端「立即签到」按钮复用（同步执行，需在子线程调用）。 */
    public static JSONObject runCheckin(Context ctx) {
        JSONObject report = new JSONObject();
        JSONArray items = new JSONArray();
        ApiClient api = new ApiClient(ctx);
        int ok = 0, fail = 0;

        if (Prefs.demo(ctx)) {
            try {
                report.put("demo", true);
                report.put("message", "演示模式：未向服务端发起签到");
                Prefs.log(ctx, "自动签到（演示模式）跳过实际请求");
            } catch (Exception ignored) { }
            return report;
        }

        // WorkBuddy：国内版 + 国际版各一次（对齐前端 checkinAll(region)）
        String[] regions = {"cn", "global"};
        for (String region : regions) {
            ApiClient.Result r = api.request("POST", "/api/checkin/all",
                    "{\"region\":\"" + region + "\"}");
            try {
                JSONObject item = new JSONObject();
                item.put("platform", "WorkBuddy " + region);
                item.put("ok", r.ok);
                item.put("detail", r.ok ? brief(r.body) : r.error);
                items.put(item);
            } catch (Exception ignored) { }
            if (r.ok) ok++; else fail++;
        }

        // Trae：批量签到（skipCheckedIn 对齐桌面端默认行为）
        ApiClient.Result tr = api.request("POST", "/api/trae/checkin",
                "{\"options\":{\"scope\":\"all\",\"skipCheckedIn\":true}}");
        try {
            JSONObject item = new JSONObject();
            item.put("platform", "Trae");
            item.put("ok", tr.ok);
            item.put("detail", tr.ok ? brief(tr.body) : tr.error);
            items.put(item);
        } catch (Exception ignored) { }
        if (tr.ok) ok++; else fail++;

        // Qoder：先取账号清单，再逐个领取（服务端没有批量路由）
        ApiClient.Result qa = api.request("GET", "/api/qoder/accounts?scope=qoder-cn", null);
        JSONArray uids = new JSONArray();
        if (qa.ok) uids = extractUids(qa.body);
        for (int i = 0; i < uids.length() && i < 20; i++) {
            String uid = uids.optString(i, "");
            if (uid.isEmpty()) continue;
            try {
                String body = new JSONObject().put("scope", "qoder-cn").put("uid", uid).toString();
                ApiClient.Result qr = api.request("POST", "/api/qoder/checkin/claim", body);
                JSONObject item = new JSONObject();
                item.put("platform", "Qoder " + uid.substring(0, Math.min(8, uid.length())));
                item.put("ok", qr.ok);
                item.put("detail", qr.ok ? brief(qr.body) : qr.error);
                items.put(item);
                if (qr.ok) ok++; else fail++;
            } catch (Exception ignored) { }
        }

        try {
            report.put("at", System.currentTimeMillis());
            report.put("ok", ok);
            report.put("fail", fail);
            report.put("items", items);
            report.put("demo", false);
        } catch (Exception ignored) { }

        Prefs.put(ctx, Prefs.K_LAST_CHECKIN, report.toString());
        Prefs.log(ctx, "自动签到完成：成功 " + ok + " / 失败 " + fail);
        try {
            JsBridge bridge = new JsBridge(ctx, null);
            bridge.showNotification("自动签到完成", "成功 " + ok + " 项，失败 " + fail + " 项");
        } catch (Exception ignored) { }
        return report;
    }

    private static JSONArray extractUids(String body) {
        JSONArray out = new JSONArray();
        try {
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("accounts");
            if (arr == null) arr = o.optJSONArray("items");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                Object e = arr.opt(i);
                if (e instanceof JSONObject) {
                    JSONObject a = (JSONObject) e;
                    String uid = a.optString("uid", a.optString("id", ""));
                    if (!uid.isEmpty()) out.put(uid);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
