package com.buddyswitch.agent;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Trae 手机端：粘贴 JWT 导入 + 独立签到领积分 + 独立查积分。
 *
 * <p><b>为什么不做独立扫码登录</b>：dev 分支 trae/oauth.rs 的授权 URL 必须携带从桌面客户端
 * storage.json 解密出的 privateKeyPEM / deviceId（icube::device_identity_for），且授权页会主动
 * 探测 127.0.0.1:17388 判断客户端在线。两者在手机上都不存在，上游不通过。
 *
 * <p>但 trae/credits.rs 与 trae/checkin.rs 证明：<b>签到与积分接口只校验 JWT + 本机自造的
 * 设备请求头</b>（device::derive(user_id)）。所以用户从桌面客户端导出一次 JWT 后，
 * 手机即可长期独立签到、领积分、查余额，不再依赖电脑。
 *
 * <p>对齐 crates/buddy-switch-core/src/modules/trae/{jwt,credits,checkin,mod}.rs。
 */
public final class TraeApi {
    private static final String CN_BASE = "https://api.trae.cn";
    private static final String AI_BASE = "https://grow-normal.trae.ai";

    private static final String P_STATUS = "/trae/api/v2/ug/checkin_credits/status";
    private static final String P_CLAIM = "/trae/api/v2/ug/checkin_credits/claim";
    private static final String P_ENTITLEMENT = "/trae/api/v2/pay/ide_user_ent_usage";

    // credits.rs 里明确标注「线上实测契约，不要顺手改」
    private static final String UA_PRODUCT = "TRAE SOLO CN";
    private static final String VSCODE_VERSION = "1.107.1";
    private static final String MARKET_AID = "787976";
    private static final String SDK_TYPE = "3";
    private static final String PACKAGE_TYPE = "stable_cn";
    private static final String APP_VERSION = "0.1.45";
    private static final String USER_REGION = "CN";

    private static final SecureRandom RND = new SecureRandom();

    private TraeApi() { }

    private static String base(String variant) {
        return "ai".equals(variant) ? AI_BASE : CN_BASE;
    }

    // ------------------------------------------------------------------ 凭据导入

    /** 粘贴 JWT 导入：从 payload 解出 userId / exp，无需用户手填 uid。 */
    public static JSONObject addManual(Context ctx, String variantRaw, String text) {
        String raw = normalize(text);
        if (raw.isEmpty()) return fail("请先粘贴 Trae JWT");
        JSONObject payload = decodePayload(raw);
        if (payload == null) return fail("JWT 格式不正确（应为 xxxxx.yyyyy.zzzzz 三段）");
        String uid = payloadId(payload);
        if (uid.isEmpty()) return fail("JWT 中找不到用户 ID（data.id / auth_id / sub）");
        String nick = firstNonEmpty(payload, "nickname", "name", "email");
        if (nick.isEmpty()) nick = "Trae 账号";
        String v = "ai".equals(variantRaw) ? "ai" : "cn";

        JSONObject acc = AccountStore.makeFor(Platforms.TRAE, uid, nick, raw, v);
        try {
            long exp = payload.optLong("exp", 0L);
            acc.put("expiresAt", exp);
            acc.put("domain", base(v));
        } catch (Exception ignored) { }
        AccountStore.put(ctx, acc);
        Prefs.log(ctx, "Trae " + Platforms.variantLabel(v) + " JWT 已导入：" + nick);

        JSONObject out = new JSONObject();
        try { out.put("ok", true).put("account", publicOne(acc)); } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ 签到 / 积分

    /** 签到领积分：先 status（checked_in），未签再 claim。 */
    public static JSONObject checkinOne(Context ctx, JSONObject acc) {
        String v = variantOf(acc);
        String uid = acc.optString("uid", "");
        String jwt = acc.optString("token", "");
        if (jwt.isEmpty()) return fail("JWT 为空，请重新导入");
        if (uid.isEmpty()) return fail("账号缺少 userId，请重新导入 JWT");
        Map<String, String> h = buildHeaders(ctx, jwt, uid);

        Http.Resp st = Http.request("POST", base(v) + P_STATUS, h, "{}", 12000, 20000);
        if (st.error == null && st.ok()) {
            JSONObject s = obj(st.body);
            if (s.optBoolean("checked_in", false) || s.optBoolean("checkedIn", false)) {
                return skipped("今日已签到");
            }
        }
        Http.Resp cl = Http.request("POST", base(v) + P_CLAIM, h, "{}", 12000, 20000);
        if (cl.error != null) return fail("网络不可达：" + cl.error);
        if (!cl.ok()) return fail("签到失败：HTTP " + cl.status + " " + brief(cl.body));
        JSONObject j = obj(cl.body);
        long code = j.optLong("code", 0L);
        String msg = j.optString("message", "");
        if (code != 0 && !"success".equalsIgnoreCase(msg) && !msg.isEmpty()) {
            if (msg.contains("已签") || msg.toLowerCase().contains("already")) return skipped("今日已签到");
            return fail("签到失败：" + msg);
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("message", "签到成功，积分已发放")
                    .put("credits", j.optLong("credits", 0L));
        } catch (Exception ignored) { }
        Prefs.log(ctx, "Trae 签到成功：" + acc.optString("label", ""));
        return out;
    }

    /**
     * 积分查询：POST /trae/api/v2/pay/ide_user_ent_usage，
     * 聚合 user_entitlement_pack_list 中 entitlement_base_info.quota.credits_limit
     * 减去 usage.credits_amount（对齐 credits.rs::parse_credit_packages）。
     */
    public static JSONObject creditsOne(Context ctx, JSONObject acc) {
        String v = variantOf(acc);
        String uid = acc.optString("uid", "");
        String jwt = acc.optString("token", "");
        if (jwt.isEmpty()) return fail("JWT 为空，请重新导入");
        if (uid.isEmpty()) return fail("账号缺少 userId，请重新导入 JWT");

        String body = "{\"require_usage\":true,\"full_data\":true}";
        Http.Resp r = Http.request("POST", base(v) + P_ENTITLEMENT,
                buildHeaders(ctx, jwt, uid), body, 12000, 30000);
        if (r.error != null) return fail("网络不可达：" + r.error);
        if (!r.ok()) return fail("积分查询失败：HTTP " + r.status + " " + brief(r.body));

        JSONObject j = obj(r.body);
        JSONArray packs = j.optJSONArray("user_entitlement_pack_list");
        if (packs == null) {
            JSONObject d = j.optJSONObject("data");
            if (d != null) packs = d.optJSONArray("user_entitlement_pack_list");
        }
        if (packs == null) return fail("积分响应缺少 user_entitlement_pack_list");

        double total = 0, used = 0, remaining = 0;
        long expireAt = 0;
        JSONArray outPacks = new JSONArray();
        for (int i = 0; i < packs.length(); i++) {
            JSONObject p = packs.optJSONObject(i);
            if (p == null) continue;
            JSONObject info = p.optJSONObject("entitlement_base_info");
            if (info == null) continue;
            JSONObject quota = info.optJSONObject("quota");
            if (quota == null || !quota.has("credits_limit")) continue;
            double limit = quota.optDouble("credits_limit", 0);
            JSONObject usage = p.optJSONObject("usage");
            double amount = usage == null ? 0 : usage.optDouble("credits_amount", 0);
            double rem = Math.max(0, limit - amount);
            total += limit; used += amount; remaining += rem;
            long exp = p.optLong("expire_time", 0L);
            if (exp > 0 && (expireAt == 0 || exp < expireAt)) expireAt = exp;
            JSONObject op = new JSONObject();
            try {
                op.put("name", firstNonEmpty(info, "package_name", "name"));
                putIf(op, "code", firstNonEmpty(info, "package_code", "package_type"));
                putIf(op, "total", limit);
                putIf(op, "remaining", rem);
                putIf(op, "used", amount);
                putIf(op, "expireAt", exp > 0 ? exp * 1000 : 0);
                outPacks.put(op);
            } catch (Exception ignored) { }
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("total", total).put("used", used)
                    .put("remaining", remaining).put("unit", "credits")
                    .put("packages", outPacks).put("expireAt", expireAt > 0 ? expireAt * 1000 : 0)
                    .put("platform", Platforms.TRAE).put("variant", v);
        } catch (Exception ignored) { }
        return out;
    }

    public static JSONObject refreshOne(JSONObject acc) {
        return skipped("Trae JWT 由客户端签发，过期后请重新导出并粘贴导入");
    }

    // ------------------------------------------------------------------ 请求头

    /**
     * 对齐 credits.rs::build_headers。设备标识按 uid 稳定派生并持久化：
     * 上游按机器身份做风控，同一账号每次换设备头会被关联/限流。
     */
    private static Map<String, String> buildHeaders(Context ctx, String jwt, String uid) {
        JSONObject dev = deviceFor(ctx, uid);
        Map<String, String> h = new HashMap<String, String>();
        h.put("accept", "*/*");
        h.put("accept-language", "zh-CN");
        h.put("authorization", authorizationHeader(jwt));
        h.put("content-type", "application/json");
        h.put("user-agent", "VSCode " + VSCODE_VERSION + " (" + UA_PRODUCT + ")");
        h.put("x-market-client-id", "VSCode " + VSCODE_VERSION);
        h.put("x-market-user-id", dev.optString("marketUserId", ""));
        h.put("x-user-region", USER_REGION);
        h.put("x-device-id", dev.optString("deviceId", ""));
        h.put("x-lgw-req-sdk-type", SDK_TYPE);
        h.put("package-type", PACKAGE_TYPE);
        h.put("x-request-id", UUID.randomUUID().toString());
        h.put("x-lscbd-aid", MARKET_AID);
        h.put("x-lscbd-platform", "linux");
        h.put("app-version", APP_VERSION);
        h.put("x-tt-trace-id", "00-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01");
        h.put("vscode-sessionid", dev.optString("sessionId", ""));
        h.put("sec-fetch-dest", "empty");
        h.put("sec-fetch-mode", "no-cors");
        h.put("sec-fetch-site", "none");
        return h;
    }

    /** 一账号一设备，首次生成后写入 App 私有存储，长期稳定。 */
    private static JSONObject deviceFor(Context ctx, String uid) {
        String key = "trae.dev." + md5ish(uid);
        String raw = Prefs.get(ctx, key, "");
        JSONObject d = new JSONObject();
        try {
            if (raw != null && !raw.isEmpty()) d = new JSONObject(raw);
        } catch (Exception ignored) { }
        if (d.optString("deviceId", "").isEmpty()) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < 15; i++) digits.append(RND.nextInt(10));
            try {
                d.put("deviceId", digits.toString());
                d.put("marketUserId", UUID.randomUUID().toString().replace("-", ""));
                d.put("sessionId", UUID.randomUUID().toString());
                Prefs.put(ctx, key, d.toString());
            } catch (Exception ignored) { }
        }
        return d;
    }

    private static String md5ish(String s) {
        try {
            byte[] b = java.security.MessageDigest.getInstance("MD5")
                    .digest((s == null ? "" : s).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length; i++) sb.append(String.format("%02x", b[i] & 0xff));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s == null ? 0 : s.hashCode()); }
    }

    // ------------------------------------------------------------------ JWT 工具

    /** 剥掉 Cloud-IDE-JWT / Bearer 前缀与空白（jwt.rs::normalize）。 */
    private static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("Cloud-IDE-JWT ")) s = s.substring("Cloud-IDE-JWT ".length()).trim();
        else if (s.startsWith("Bearer ")) s = s.substring(7).trim();
        return s;
    }

    private static String authorizationHeader(String raw) {
        String s = raw.trim();
        return s.startsWith("Cloud-IDE-JWT ") ? s : "Cloud-IDE-JWT " + normalize(s);
    }

    private static JSONObject decodePayload(String raw) {
        try {
            String[] parts = normalize(raw).split("\\.");
            if (parts.length < 2) return null;
            String p = parts[1].replace('-', '+').replace('_', '/');
            while (p.length() % 4 != 0) p += "=";
            byte[] bytes = Base64.decode(p, Base64.DEFAULT);
            return new JSONObject(new String(bytes, "UTF-8"));
        } catch (Exception e) { return null; }
    }

    private static String payloadId(JSONObject p) {
        JSONObject data = p.optJSONObject("data");
        if (data != null) {
            Object id = data.opt("id");
            if (id != null && !String.valueOf(id).trim().isEmpty()) return String.valueOf(id).trim();
        }
        String s = firstNonEmpty(p, "auth_id", "sub", "user_id", "uid");
        return s;
    }

    // ------------------------------------------------------------------ 通用工具

    private static String variantOf(JSONObject acc) {
        return "ai".equals(acc == null ? "" : acc.optString("variant", "cn")) ? "ai" : "cn";
    }

    private static String firstNonEmpty(JSONObject j, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String s = j == null ? "" : j.optString(keys[i], "").trim();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static void putIf(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) { }
    }

    private static JSONObject obj(String s) {
        try { return new JSONObject(s == null ? "" : s); }
        catch (Exception e) { return new JSONObject(); }
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

    private static JSONObject publicOne(JSONObject a) {
        JSONObject p = new JSONObject();
        try {
            p.put("id", a.optString("id")).put("label", a.optString("label"))
                    .put("uid", a.optString("uid")).put("variant", a.optString("variant"))
                    .put("platform", Platforms.TRAE).put("platformLabel", "Trae")
                    .put("variantLabel", Platforms.variantLabel(a.optString("variant")))
                    .put("tokenMask", AccountStore.mask(a.optString("token")));
        } catch (Exception ignored) { }
        return p;
    }
}
