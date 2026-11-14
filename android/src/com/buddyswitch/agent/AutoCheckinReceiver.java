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
 * 定时自动签到 —— 手机本地执行，直连腾讯官方 API，不依赖电脑。
 *
 * <p>遍历本机账号库，按 {@link PlatformRouter} 分派到 WorkBuddy / Qoder / Trae
 * 各自的签到实现；不支持签到的档位自动跳过。用 AlarmManager 链式调度。
 */
public final class AutoCheckinReceiver extends BroadcastReceiver {
    private static final int REQ = 7701;

    public static void schedule(Context c) {
        Context ctx = c.getApplicationContext();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx);
        am.cancel(pi);
        if (!Prefs.autoCheckin(ctx) || AccountStore.load(ctx).length() == 0) return;
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
                    if (Prefs.autoCheckin(ctx) && !Prefs.demo(ctx)) {
                        JSONObject report = runCheckin(ctx);
                        try {
                            new JsBridge(ctx, null).showNotification("自动签到完成",
                                    "成功 " + report.optInt("ok", 0) + " 项，失败 " + report.optInt("fail", 0) + " 项");
                        } catch (Exception ignored) { }
                    }
                } finally {
                    schedule(ctx);
                    pr.finish();
                }
            }
        }, "bs-checkin").start();
    }

    /** 供前端「立即签到」复用（需在子线程调用）。 */
    public static JSONObject runCheckin(Context ctx) {
        JSONObject report = new JSONObject();
        JSONArray items = new JSONArray();
        int ok = 0, fail = 0;
        JSONArray accounts = AccountStore.load(ctx);
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject acc = accounts.optJSONObject(i);
            if (acc == null) continue;
            JSONObject r = PlatformRouter.checkinOne(ctx, acc);
            JSONObject item = new JSONObject();
            try {
                item.put("id", acc.optString("id", ""))
                        .put("label", acc.optString("label", ""))
                        .put("platform", Platforms.of(acc))
                        .put("platformLabel", Platforms.label(Platforms.of(acc)))
                        .put("result", r);
            } catch (Exception ignored) { }
            items.put(item);
            if (r.optBoolean("ok", false)) ok++; else fail++;
        }
        try {
            report.put("at", System.currentTimeMillis()).put("ok", ok).put("fail", fail)
                    .put("items", items);
            Prefs.put(ctx, Prefs.K_LAST_CHECKIN, report.toString());
            Prefs.log(ctx, "定时签到：成功 " + ok + " / 失败 " + fail);
        } catch (Exception ignored) { }
        return report;
    }
}
