package com.buddyswitch.agent;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qoder 手机独立客户端（国内 qoder.com.cn / 国际 qoder.com）。
 *
 * <p>协议逐字对齐 dev 分支 crates/buddy-switch-core/src/modules/qoder/ 下的
 * oauth.rs / region.rs / api.rs / device.rs：
 * <ul>
 *   <li>登录：本地生成 machine_id（无桌面客户端时自造 uuid 并持久化，见
 *       oauth.rs::machine_id_for）+ PKCE(S256) → 打开 {website}/device/selectAccounts
 *       → 浏览器授权 → GET {openapi}/api/v1/deviceToken/poll 轮询换 token
 *       → GET /api/v1/userinfo 取账号身份。<b>全程纯 HTTP，无本地回环回调、无设备私钥。</b></li>
 *   <li>签到：GET/POST /sash/api/v1/me/daily-check-in/{status,claim}</li>
 *   <li>额度：GET /api/v2/quota/usage（userQuota + addOnQuota）</li>
 * </ul>
 */
public final class QoderApi {
    private static final String CN_WEBSITE = "https://qoder.com.cn";
    private static final String CN_OPENAPI = "https://openapi.qoder.com.cn";
    private static final String CN_CLIENT_ID = "1c5e33e1-364d-4ce6-b02c-acaa81274a5c";
    private static final String CN_REDIRECT = "qoder-work-cn://";
    private static final String CN_UA = "QoderWork/1.1.64";

    private static final String AI_WEBSITE = "https://qoder.com";
    private static final String AI_OPENAPI = "https://openapi.qoder.sh";
    private static final String AI_CLIENT_ID = "e883ade2-e6e3-4d6d-adf7-f92ceff5fdcb";
    private static final String AI_UA = "Qoder/1.1.64";

    private static final String P_POLL = "/api/v1/deviceToken/poll";
    private static final String P_USERINFO = "/api/v1/userinfo";
    private static final String P_CHECKIN_STATUS = "/sash/api/v1/me/daily-check-in/status";
    private static final String P_CHECKIN_CLAIM = "/sash/api/v1/me/daily-check-in/claim";
    private static final String P_QUOTA = "/api/v2/quota/usage";

    private static final String VERIFIER_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final long TTL_MS = 300_000L;
    private static final SecureRandom RND = new SecureRandom();

    private QoderApi() { }

    private static boolean isAi(String variant) { return "ai".equals(variant); }
    private static String website(String v) { return isAi(v) ? AI_WEBSITE : CN_WEBSITE; }
    private static String openapi(String v) { return isAi(v) ? AI_OPENAPI : CN_OPENAPI; }
    private static String clientId(String v) { return isAi(v) ? AI_CLIENT_ID : CN_CLIENT_ID; }
    private static String ua(String v) { return isAi(v) ? AI_UA : CN_UA; }

    // ------------------------------------------------------------------ 登录

    private static final class Session {
        String variant, verifier, nonce;
        long startedAt;
        Session(String v, String cv, String n) {
            variant = v; verifier = cv; nonce = n; startedAt = System.currentTimeMillis();
        }
    }
    private static final ConcurrentHashMap<String, Session> SESSIONS =
            new ConcurrentHashMap<String, Session>();

    /** 第一步：构造授权页 URL。对齐 oauth.rs::login_start_for —— 不发任何网络请求。 */
    public static JSONObject loginStart(Context ctx, String variantRaw) {
        String v = "ai".equals(variantRaw) ? "ai" : "cn";
        String machineId = Prefs.get(ctx, "qoder.machine." + v, "");
        if (machineId.isEmpty()) {
            machineId = UUID.randomUUID().toString();
            Prefs.put(ctx, "qoder.machine." + v, machineId);
        }
        String verifier = randomVerifier();
        String challenge = base64UrlNoPad(sha256(verifier));
        String uuid = UUID.randomUUID().toString();
        String nonce = isAi(v) ? uuid.replace("-", "") : uuid;  // region.rs::nonce_dashed

        StringBuilder url = new StringBuilder(website(v));
        url.append("/device/selectAccounts?challenge=").append(challenge)
                .append("&challenge_method=S256&nonce=").append(nonce)
                .append("&client_id=").append(clientId(v))
                .append("&machine_id=").append(machineId);
        if (!isAi(v)) url.append("&redirect_uri=").append(CN_REDIRECT);

        String loginId = "qoder_" + UUID.randomUUID().toString().replace("-", "");
        SESSIONS.put(loginId, new Session(v, verifier, nonce));
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Session> e : SESSIONS.entrySet()) {
            if (now - e.getValue().startedAt > TTL_MS * 2) SESSIONS.remove(e.getKey());
        }

        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("state", loginId).put("authUrl", url.toString())
                    .put("variant", v).put("variantLabel", Platforms.variantLabel(v))
                    .put("appLabel", "Qoder").put("platform", Platforms.QODER)
                    .put("expiresIn", TTL_MS / 1000);
        } catch (Exception ignored) { }
        return out;
    }

    /** 第二步：轮询换 token 并落本地账号库。对齐 oauth.rs::login_poll_for。 */
    public static JSONObject loginPoll(Context ctx, String state) {
        Session s = state == null ? null : SESSIONS.get(state);
        if (s == null) return expired("登录会话不存在或已过期，请重新发起");
        if (System.currentTimeMillis() - s.startedAt > TTL_MS) {
            SESSIONS.remove(state);
            return expired("授权已超时（5 分钟），请重新发起");
        }
        String url = openapi(s.variant) + P_POLL + "?nonce=" + q(s.nonce)
                + "&verifier=" + q(s.verifier) + "&challenge_method=S256";
        Map<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("User-Agent", ua(s.variant));
        Http.Resp r = Http.request("GET", url, h, null, 12000, 15000);
        if (r.error != null) return pending("网络异常，重试中…");
        if (r.status == 202 || r.status == 404) return pending("等待扫码 / 确认授权…");
        if (!r.ok()) return fail("轮询失败：HTTP " + r.status + " " + brief(r.body));

        JSONObject j = obj(r.body);
        String token = firstStr(j, "token", "device_token", "access_token", "accessToken");
        if (token.isEmpty()) return pending("授权处理中…");
        String refresh = firstStr(j, "refresh_token", "refreshToken");

        Map<String, String> uh = new HashMap<String, String>();
        uh.put("Accept", "application/json, text/plain, */*");
        uh.put("User-Agent", ua(s.variant));
        uh.put("Authorization", "Bearer " + token);
        Http.Resp ur = Http.request("GET", openapi(s.variant) + P_USERINFO, uh, null, 12000, 15000);
        if (!ur.ok()) return fail("拉取账号身份失败：HTTP " + ur.status);
        JSONObject info = obj(ur.body);
        Object idObj = dig(info, "id");
        String uid = idObj == null ? "" : String.valueOf(idObj).trim();
        if (uid.isEmpty()) return pending("账号信息尚未就绪，稍后重试…");
        String nick = firstStr(info, "name", "nickname", "email");
        if (nick.isEmpty()) nick = "Qoder 账号";

        JSONObject acc = AccountStore.makeFor(Platforms.QODER, uid, nick, token, s.variant);
        try {
            acc.put("refreshToken", refresh);
            long expSec = System.currentTimeMillis() / 1000 + 3600;
            long expiresIn = j.optLong("expires_in", 0L);
            if (expiresIn > 0) expSec = System.currentTimeMillis() / 1000 + expiresIn / 1000;
            acc.put("expiresAt", expSec);
        } catch (Exception ignored) { }
        AccountStore.put(ctx, acc);
        SESSIONS.remove(state);
        Prefs.log(ctx, "Qoder " + Platforms.variantLabel(s.variant) + " 登录成功：" + nick);

        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("status", "ready").put("account", publicOne(acc));
        } catch (Exception ignored) { }
        return out;
    }

    public static JSONObject loginCancel(String state) {
        if (state != null) SESSIONS.remove(state);
        JSONObject o = new JSONObject();
        try { o.put("ok", true); } catch (Exception ignored) { }
        return o;
    }

    /** 手动导入 access token / PAT（Qoder 无昵称时用 uid 兜底）。 */
    public static JSONObject addManual(Context ctx, String variantRaw, String text) {
        String token = text == null ? "" : text.trim();
        if (token.startsWith("Bearer ")) token = token.substring(7).trim();
        if (token.isEmpty()) return fail("请先粘贴 Qoder access token / PAT");
        String v = "ai".equals(variantRaw) ? "ai" : "cn";
        JSONObject jwt = AccountStore.decodeJwt(token);
        String uid = jwt.optString("sub", "");
        if (uid.isEmpty()) uid = "pat_" + Integer.toHexString(token.hashCode());
        JSONObject acc = AccountStore.makeFor(Platforms.QODER, uid, "Qoder 账号", token, v);
        try { acc.put("expiresAt", jwt.optLong("exp", 0L)); } catch (Exception ignored) { }
        AccountStore.put(ctx, acc);
        JSONObject out = new JSONObject();
        try { out.put("ok", true).put("account", publicOne(acc)); } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ 签到 / 额度

    /** 签到即领积分：先查状态，未签再 claim。 */
    public static JSONObject checkinOne(Context ctx, JSONObject acc) {
        String v = variantOf(acc);
        String uid = acc.optString("uid", "");
        String token = acc.optString("token", "");
        if (token.isEmpty()) return fail("凭据为空，请重新导入");
        Map<String, String> h = openapiHeaders(v, uid, token);

        Http.Resp st = Http.request("GET", openapi(v) + P_CHECKIN_STATUS, h, null, 12000, 15000);
        if (st.status == 404 || st.status == 405 || st.status == 410) {
            return skipped("该区域暂未开放每日签到接口（Qoder 已改为活动领积分）");
        }
        if (st.error == null && st.ok()) {
            JSONObject s = obj(st.body);
            if (looksCheckedIn(s)) return skipped("今日已签到");
        }
        Http.Resp cl = Http.request("POST", openapi(v) + P_CHECKIN_CLAIM, h, "{}", 12000, 15000);
        if (cl.error != null) return fail("网络不可达：" + cl.error);
        if (!cl.ok()) {
            String b = cl.body.toLowerCase();
            if (b.contains("already") || b.contains("checked") || b.contains("已签")) {
                return skipped("今日已签到");
            }
            return fail("签到失败：HTTP " + cl.status + " " + brief(cl.body));
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("message", "签到成功，积分已发放")
                    .put("detail", brief(cl.body));
        } catch (Exception ignored) { }
        Prefs.log(ctx, "Qoder 签到成功：" + acc.optString("label", ""));
        return out;
    }

    /** 额度查询：GET /api/v2/quota/usage，聚合 userQuota + addOnQuota。 */
    public static JSONObject creditsOne(Context ctx, JSONObject acc) {
        String v = variantOf(acc);
        String uid = acc.optString("uid", "");
        String token = acc.optString("token", "");
        if (token.isEmpty()) return fail("凭据为空，请重新导入");
        Http.Resp r = Http.request("GET", openapi(v) + P_QUOTA,
                openapiHeaders(v, uid, token), null, 12000, 30000);
        if (r.error != null) return fail("网络不可达：" + r.error);
        if (!r.ok()) return fail("额度查询失败：HTTP " + r.status + " " + brief(r.body));
        JSONObject j = obj(r.body);
        JSONObject uq = j.optJSONObject("userQuota");
        JSONObject aq = j.optJSONObject("addOnQuota");
        if (uq == null && aq == null) return fail("额度响应缺少 userQuota / addOnQuota");
        double remaining = num(uq, "remaining") + num(aq, "remaining");
        double used = num(uq, "used") + num(aq, "used");
        double total = num(uq, "total") + num(aq, "total");

        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("remaining", remaining).put("used", used)
                    .put("total", total).put("unit", "credits")
                    .put("platform", Platforms.QODER).put("variant", v);
        } catch (Exception ignored) { }
        return out;
    }

    public static JSONObject refreshOne(JSONObject acc) {
        return skipped("Qoder 凭据请在电脑端刷新后重新粘贴导入（手机端不做设备令牌刷新）");
    }

    // ------------------------------------------------------------------ 工具

    private static String variantOf(JSONObject acc) {
        String v = acc == null ? "" : acc.optString("variant", "cn");
        return "ai".equals(v) ? "ai" : "cn";
    }

    /** 对齐 api.rs::openapi_headers：含按 uid 稳定派生的机器码 / 会话码。 */
    private static Map<String, String> openapiHeaders(String v, String uid, String token) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("User-Agent", ua(v));
        h.put("Authorization", "Bearer " + token);
        String safeUid = uid == null || uid.isEmpty() ? "anonymous" : uid;
        h.put("X-Request-ID", md5("req:" + safeUid).substring(0, 24) + "-"
                + String.format("%06d", System.currentTimeMillis() % 1000000));
        h.put("X-Machine-ID", md5("machine:" + safeUid));   // device.rs::derive_id
        h.put("X-Session-ID", md5("session:" + safeUid));
        h.put("Origin", website(v));
        h.put("Referer", website(v) + "/");
        return h;
    }

    private static boolean looksCheckedIn(JSONObject j) {
        if (j.optBoolean("checked_in", false) || j.optBoolean("checkedIn", false)
                || j.optBoolean("claimed", false) || j.optBoolean("todayCheckedIn", false)) return true;
        String s = j.optString("status", "").toUpperCase();
        return "CLAIMED".equals(s) || "CHECKED".equals(s) || "DONE".equals(s);
    }

    private static double num(JSONObject o, String key) {
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o.opt(key))); }
        catch (Exception e) { return 0; }
    }

    private static String randomVerifier() {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) sb.append(VERIFIER_ALPHABET.charAt(RND.nextInt(VERIFIER_ALPHABET.length())));
        return sb.toString();
    }

    private static byte[] sha256(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")); }
        catch (Exception e) { return new byte[0]; }
    }

    private static String base64UrlNoPad(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP).trim();
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) { return "00000000000000000000000000000000"; }
    }

    /** 只编码非 unreserved 字符，保留 A-Za-z0-9-._~（PKCE verifier 需要）。 */
    private static String q(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') sb.append(c);
            else sb.append(String.format("%%%02X", (int) c & 0xff));
        }
        return sb.toString();
    }

    private static JSONObject obj(String s) {
        try { return new JSONObject(s == null ? "" : s); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** 在顶层 / data / result 里找第一个字符串值。 */
    private static String firstStr(JSONObject j, String... keys) {
        for (int k = 0; k < keys.length; k++) {
            Object v = dig(j, keys[k]);
            if (v != null && !(v instanceof JSONObject) && !String.valueOf(v).trim().isEmpty()) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }

    private static Object dig(JSONObject j, String key) {
        if (j == null) return null;
        if (j.has(key)) return j.opt(key);
        String[] nest = {"data", "result"};
        for (int i = 0; i < nest.length; i++) {
            JSONObject n = j.optJSONObject(nest[i]);
            if (n != null && n.has(key)) return n.opt(key);
        }
        return null;
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private static JSONObject fail(String m) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false).put("error", m); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject skipped(String m) {
        JSONObject o = new JSONObject();
        try { o.put("ok", true).put("skipped", true).put("message", m); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject pending(String m) {
        JSONObject o = new JSONObject();
        try { o.put("ok", true).put("status", "pending").put("message", m); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject expired(String m) {
        JSONObject o = new JSONObject();
        try { o.put("ok", true).put("status", "expired").put("message", m); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject publicOne(JSONObject a) {
        JSONObject p = new JSONObject();
        try {
            p.put("id", a.optString("id")).put("label", a.optString("label"))
                    .put("uid", a.optString("uid")).put("variant", a.optString("variant"))
                    .put("platform", Platforms.QODER)
                    .put("platformLabel", "Qoder")
                    .put("variantLabel", Platforms.variantLabel(a.optString("variant")))
                    .put("tokenMask", AccountStore.mask(a.optString("token")));
        } catch (Exception ignored) { }
        return p;
    }
}
