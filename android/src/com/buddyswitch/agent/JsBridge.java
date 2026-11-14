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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * WebView ↔ 原生桥。前端 SPA 只通过 {@code call(cbId, action, args)} 访问本机能力与上游 API。
 *
 * <p>为什么不让 WebView 直接 fetch：页面来自 file://，跨源请求没有 CORS 头；更关键的是
 * 腾讯官方接口校验 Origin / Referer / User-Agent 指纹，JS 无法伪造，必须由 Java 层发请求。
 */
public final class JsBridge {
    private final Context ctx;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    public JsBridge(Context ctx, WebView web) {
        this.ctx = ctx.getApplicationContext();
        this.web = web;
    }

    /** 统一异步入口：后台执行，完成后回调 window.__bsCallback(cbId, ok, status, json)。 */
    @JavascriptInterface
    public void call(final String cbId, final String action, final String args) {
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject res;
                try {
                    res = dispatch(action, args == null ? "{}" : args);
                } catch (Exception e) {
                    res = new JSONObject();
                    try { res.put("ok", false).put("error", "内部异常：" + e.getMessage()); }
                    catch (Exception ignored) { }
                }
                final boolean ok = res.optBoolean("ok", false);
                final int status = res.optInt("status", ok ? 200 : 400);
                final String payload = res.toString();
                main.post(new Runnable() {
                    @Override public void run() {
                        if (web == null) return;
                        try {
                            String js = "window.__bsCallback(" + JSONObject.quote(cbId) + ","
                                    + ok + "," + status + "," + JSONObject.quote(payload) + ")";
                            web.evaluateJavascript(js, null);
                        } catch (Exception ignored) { }
                    }
                });
            }
        }, "bs-call").start();
    }

    private JSONObject dispatch(String action, String argsJson) {
        JSONObject a = parse(argsJson);
        if ("state".equals(action)) return state();
        if ("setConfig".equals(action)) return setConfig(a);
        if ("loginStart".equals(action)) {
            String pf = Platforms.normalize(a.optString("platform", Platforms.WB));
            String v = a.optString("variant", "cn");
            if (Platforms.QODER.equals(pf)) return QoderApi.loginStart(ctx, v);
            if (Platforms.TRAE.equals(pf)) return err(
                    "Trae 上游要求桌面客户端设备私钥与 127.0.0.1:17388 回环探测，"
                    + "手机无法独立扫码登录；请改用「粘贴 Trae JWT」导入后独立签到");
            return WorkBuddyApi.loginStart(ctx, v);
        }
        if ("loginPoll".equals(action)) {
            String st = a.optString("state", "");
            return st.startsWith("qoder_") ? QoderApi.loginPoll(ctx, st)
                    : WorkBuddyApi.loginPoll(ctx, st);
        }
        if ("loginCancel".equals(action)) {
            String st = a.optString("state", "");
            return st.startsWith("qoder_") ? QoderApi.loginCancel(st)
                    : WorkBuddyApi.loginCancel(st);
        }
        if ("platforms".equals(action)) return Platforms.capabilities();
        if ("addManual".equals(action)) {
            String pf = Platforms.normalize(a.optString("platform", Platforms.WB));
            String v = a.optString("variant", "cn");
            String text = a.optString("text", "");
            if (Platforms.QODER.equals(pf)) return QoderApi.addManual(ctx, v, text);
            if (Platforms.TRAE.equals(pf)) return TraeApi.addManual(ctx, v, text);
            return WorkBuddyApi.addManual(ctx, v, text);
        }
        if ("removeAccount".equals(action)) {
            AccountStore.remove(ctx, a.optString("id", ""));
            return ok("账号已删除");
        }
        if ("setRemark".equals(action)) {
            String rid = a.optString("id", "");
            if (rid.isEmpty()) return err("缺少账号 id");
            boolean done = AccountStore.setRemark(ctx, rid, a.optString("remark", ""));
            return done ? ok("备注已保存") : err("账号不存在，备注未保存");
        }
        if ("checkin".equals(action)) return forEach(a.optJSONArray("ids"), "checkin");
        if ("growth".equals(action)) return forEach(a.optJSONArray("ids"), "growth");
        if ("credits".equals(action)) {
            JSONObject acc = AccountStore.find(ctx, a.optString("id", ""));
            return acc == null ? err("账号不存在") : wrapOne(PlatformRouter.creditsOne(ctx, acc));
        }
        if ("creditsAll".equals(action)) return forEach(null, "credits");
        if ("refresh".equals(action)) {
            JSONObject acc = AccountStore.find(ctx, a.optString("id", ""));
            return acc == null ? err("账号不存在") : wrapOne(PlatformRouter.refreshOne(ctx, acc));
        }
        if ("syncPing".equals(action)) return WorkBuddyApi.syncPing(
                a.optString("host", ""), a.optInt("port", 57900), a.optString("code", ""));
        if ("syncPush".equals(action)) return WorkBuddyApi.syncPush(ctx,
                a.optString("host", ""), a.optInt("port", 57900), a.optString("code", ""),
                a.optJSONArray("ids"));
        if ("syncFetch".equals(action)) return WorkBuddyApi.syncFetch(ctx,
                a.optString("host", ""), a.optInt("port", 57900), a.optString("code", ""));
        if ("export".equals(action)) {
            JSONObject o = new JSONObject();
            try { o.put("ok", true).put("json", AccountStore.exportAll(ctx).toString()); }
            catch (Exception ignored) { }
            return o;
        }
        if ("importAccounts".equals(action)) {
            int n = AccountStore.importAll(ctx, a.optString("text", ""));
            if (n <= 0) return err("没有解析到有效账号（需含 token / accessToken 字段）");
            return ok("已导入 " + n + " 个账号");
        }
        if ("getLog".equals(action)) {
            JSONObject o = new JSONObject();
            try { o.put("ok", true).put("log", Prefs.get(ctx, Prefs.K_LOG, "")); }
            catch (Exception ignored) { }
            return o;
        }
        if ("clearLog".equals(action)) { Prefs.put(ctx, Prefs.K_LOG, ""); return ok("日志已清空"); }
        if ("setAutoCheckin".equals(action)) {
            Prefs.putBool(ctx, Prefs.K_AUTO, a.optBoolean("enabled", false));
            Prefs.putInt(ctx, Prefs.K_INTERVAL, Math.max(15, a.optInt("intervalMin", 360)));
            AutoCheckinReceiver.schedule(ctx);
            return ok(a.optBoolean("enabled", false) ? "自动签到已开启" : "自动签到已关闭");
        }
        return err("未知操作：" + action);
    }

    /** 批量执行签到 / 成长领取 / 积分查询，串行，避免整点风控。 */
    private JSONObject forEach(JSONArray ids, String what) {
        JSONArray all = AccountStore.load(ctx);
        JSONArray items = new JSONArray();
        int okN = 0, failN = 0, disabledN = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject acc = all.optJSONObject(i);
            if (acc == null) continue;
            if (ids != null && ids.length() > 0 && !hasId(ids, acc.optString("id", ""))) continue;
            JSONObject r;
            if ("checkin".equals(what)) r = PlatformRouter.checkinOne(ctx, acc);
            else if ("growth".equals(what)) r = PlatformRouter.growthOne(ctx, acc);
            else r = PlatformRouter.creditsOne(ctx, acc);
            JSONObject item = new JSONObject();
            try {
                item.put("id", acc.optString("id", ""))
                        .put("label", acc.optString("label", ""))
                        .put("variant", WbVariant.normalize(acc.optString("variant", "cn")))
                        .put("variantLabel", WbVariant.of(acc.optString("variant", "cn")).label)
                        .put("platform", Platforms.of(acc))
                        .put("platformLabel", Platforms.label(Platforms.of(acc)))
                        .put("result", r);
            } catch (Exception ignored) { }
            items.put(item);
            boolean good = r.optBoolean("ok", false) || r.optBoolean("skipped", false);
            // 第三档「未开放」（Qoder 活动平台整区不可用 / 账号无积分类活动）：
            // 既不算成功，也不算失败 —— 单独计数，避免界面谎报「N 个成功」。
            boolean disabled = r.optBoolean("disabled", false);
            if (good) okN++;
            else if (!disabled) failN++;
            else disabledN++;
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", failN == 0).put("items", items).put("okCount", okN)
                    .put("failCount", failN).put("disabledCount", disabledN)
                    .put("at", System.currentTimeMillis());
            if ("checkin".equals(what)) {
                Prefs.put(ctx, Prefs.K_LAST_CHECKIN, out.toString());
                Prefs.log(ctx, "签到完成：成功 " + okN + " / 失败 " + failN
                        + " / 未开放 " + disabledN);
            } else if ("growth".equals(what)) {
                Prefs.put(ctx, Prefs.K_LAST_GROWTH, out.toString());
                Prefs.log(ctx, "成长中心领取完成：成功 " + okN + " / 失败 " + failN);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private JSONObject wrapOne(JSONObject r) {
        try { r.put("ok", r.optBoolean("ok", false)); } catch (Exception ignored) { }
        return r;
    }

    private JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            JSONArray accounts = AccountStore.publicList(ctx);
            int cn = 0, ai = 0, nWb = 0, nQoder = 0, nTrae = 0;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject x = accounts.optJSONObject(i);
                if (x == null) continue;
                if ("ai".equals(x.optString("variant"))) ai++; else cn++;
                String pf = Platforms.of(x);
                if (Platforms.QODER.equals(pf)) nQoder++;
                else if (Platforms.TRAE.equals(pf)) nTrae++;
                else nWb++;
            }
            // 首页「6 个产品」矩阵：按 平台 × 档位 分组统计账号数；
            // 汇总积分由前端拿 S.credits 按同样的 scopeKey 聚合（积分不落盘，避免读到过期值）。
            JSONArray scopes = Platforms.scopes();
            for (int s = 0; s < scopes.length(); s++) {
                JSONObject sc = scopes.optJSONObject(s);
                if (sc == null) continue;
                String key = sc.optString("key", "");
                int n = 0;
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject x = accounts.optJSONObject(i);
                    if (x == null) continue;
                    if (key.equals(Platforms.scopeKey(Platforms.of(x),
                            x.optString("variant", "cn")))) n++;
                }
                sc.put("accountCount", n);
            }
            JSONObject sync = new JSONObject();
            sync.put("host", Prefs.syncHost(ctx)).put("port", Prefs.syncPort(ctx))
                    .put("code", Prefs.syncCode(ctx));
            // 版本名以 AndroidManifest.xml 为唯一数据源，避免升级时漏改
            String ver = "2.1.0";
            try {
                ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Exception ignored) { }
            o.put("ok", true).put("version", ver)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("demo", Prefs.demo(ctx))
                    .put("autoCheckin", Prefs.autoCheckin(ctx))
                    .put("intervalMin", Prefs.intervalMin(ctx))
                    .put("notify", Prefs.notifyEnabled(ctx))
                    .put("sync", sync)
                    .put("accounts", accounts)
                    .put("scopes", scopes)
                    .put("stats", new JSONObject().put("total", accounts.length())
                            .put("cn", cn).put("ai", ai)
                            .put("wb", nWb).put("qoder", nQoder).put("trae", nTrae))
                    .put("platforms", Platforms.capabilities().optJSONArray("items"))
                    .put("lastCheckin", Prefs.get(ctx, Prefs.K_LAST_CHECKIN, ""))
                    .put("lastGrowth", Prefs.get(ctx, Prefs.K_LAST_GROWTH, ""))
                    .put("log", Prefs.get(ctx, Prefs.K_LOG, ""));
        } catch (Exception e) {
            return err("读取状态失败：" + e.getMessage());
        }
        return o;
    }

    private JSONObject setConfig(JSONObject a) {
        if (a.has("syncHost")) Prefs.put(ctx, Prefs.K_SYNC_HOST, a.optString("syncHost", "").trim());
        if (a.has("syncPort")) Prefs.putInt(ctx, Prefs.K_SYNC_PORT, a.optInt("syncPort", 57900));
        if (a.has("syncCode")) Prefs.put(ctx, Prefs.K_SYNC_CODE, a.optString("syncCode", "").trim());
        if (a.has("demo")) Prefs.putBool(ctx, Prefs.K_DEMO, a.optBoolean("demo", false));
        if (a.has("notify")) Prefs.putBool(ctx, Prefs.K_NOTIFY, a.optBoolean("notify", true));
        if (a.has("autoCheckin") || a.has("intervalMin")) {
            boolean en = a.has("autoCheckin") ? a.optBoolean("autoCheckin") : Prefs.autoCheckin(ctx);
            int iv = a.has("intervalMin") ? a.optInt("intervalMin", 360) : Prefs.intervalMin(ctx);
            Prefs.putBool(ctx, Prefs.K_AUTO, en);
            Prefs.putInt(ctx, Prefs.K_INTERVAL, Math.max(15, iv));
            AutoCheckinReceiver.schedule(ctx);
        }
        return ok("设置已保存");
    }

    // ------------------------------------------------------------------ 系统能力

    @JavascriptInterface public void toast(String msg) {
        main.post(new Runnable() {
            @Override public void run() { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); }
        });
    }

    @JavascriptInterface public void notify(String title, String msg) { showNotification(title, msg); }

    @JavascriptInterface public void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) { }
    }

    public void showNotification(String title, String msg) {
        try {
            if (!Prefs.notifyEnabled(ctx)) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String channel = "buddy_switch_checkin";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(channel,
                        "签到与领取", NotificationManager.IMPORTANCE_DEFAULT);
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
                    .setContentIntent(pi).setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() & 0xffff), b.build());
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ 工具

    private static JSONObject parse(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return new JSONObject(); }
    }

    private static JSONObject ok(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", true).put("message", msg); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false).put("error", msg); } catch (Exception ignored) { }
        return o;
    }

    private static boolean hasId(JSONArray arr, String id) {
        for (int i = 0; i < arr.length(); i++) if (id.equals(arr.optString(i, ""))) return true;
        return false;
    }
}
