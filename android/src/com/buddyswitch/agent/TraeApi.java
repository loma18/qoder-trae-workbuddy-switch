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
 * <p><b>网关单一来源（v2.1.2 修正）</b>：{@code /trae/api/v2/*} 只挂载在国内官方网关
 * {@code api.trae.cn}；国际版 {@code grow-normal.trae.ai} 是<b>账号 / OAuth 网关</b>
 * （只代理 /trae/api/v3/oauth/*、/cloudide/api/v3/*），并没有这套签到 / 积分路由。
 * 参考实现 {@code credits.rs::post_json} 固定 {@code TraeVariant::default()}，
 * {@code mod.rs::endpoints_for} 只返回 {@code cn_endpoints} —— 国际版账号的签到也是发往
 * {@code api.trae.cn}。因此这里<b>不按 variant 切换主通道</b>，国际账号网关仅作回落候选。
 *
 * <p>对齐 crates/buddy-switch-core/src/modules/trae/{jwt,credits,checkin,mod,variant}.rs。
 */
public final class TraeApi {
    /** 签到 / 积分 API 网关：{@code /trae/api/v2/*} 唯一实测挂载点。 */
    private static final String CN_BASE = "https://api.trae.cn";
    /**
     * 国际版「账号 / OAuth」网关，仅作上游迁移后的回落候选。
     * ⚠️ 实测其 {@code /trae/api/v2/*} 全部 nginx 404 —— 绝不作为签到 / 积分主通道。
     */
    private static final String AI_ACCOUNT_BASE = "https://grow-normal.trae.ai";

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

    /**
     * 签到 / 积分网关候选，主通道恒为 {@link #CN_BASE}（与 variant 无关）。
     * 国际版账号额外挂一个账号网关作回落：若上游把 v2 路由迁过去仍能自动适配。
     */
    private static String[] bases(String variant) {
        return "ai".equals(variant)
                ? new String[]{CN_BASE, AI_ACCOUNT_BASE}
                : new String[]{CN_BASE};
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
            acc.put("domain", CN_BASE);
        } catch (Exception ignored) { }
        AccountStore.put(ctx, acc);
        Prefs.log(ctx, "Trae " + Platforms.variantLabel(v) + " JWT 已导入：" + nick);

        JSONObject out = new JSONObject();
        try { out.put("ok", true).put("account", publicOne(acc)); } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ 签到 / 积分

    /** 签到领积分：先 status（checked_in），未签再 claim。两跳都走 {@link #postAny}。 */
    public static JSONObject checkinOne(Context ctx, JSONObject acc) {
        String v = variantOf(acc);
        String uid = acc.optString("uid", "");
        String jwt = acc.optString("token", "");
        if (jwt.isEmpty()) return fail("JWT 为空，请重新导入");
        if (uid.isEmpty()) return fail("账号缺少 userId，请重新导入 JWT");
        Map<String, String> h = buildHeaders(ctx, jwt, uid);

        Http.Resp st = postAny(variantOf(acc), P_STATUS, h, "{}", 20000);
        if (st == null) return noRoute(v);
        if (isAuthReject(st)) return authRejected(v);
        if (st.error == null && st.ok()) {
            JSONObject s = obj(st.body);
            if (s.optBoolean("checked_in", false) || s.optBoolean("checkedIn", false)) {
                return skipped("今日已签到");
            }
        }
        Http.Resp cl = postAny(variantOf(acc), P_CLAIM, h, "{}", 20000);
        if (cl == null) return noRoute(v);
        if (cl.error != null) return fail("网络不可达：" + cl.error);
        if (isAuthReject(cl)) return authRejected(v);
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
        Http.Resp r = postAny(v, P_ENTITLEMENT,
                buildHeaders(ctx, jwt, uid), body, 30000);
        if (r == null) return noRoute(v);
        if (r.error != null) return fail("网络不可达：" + r.error);
        if (isAuthReject(r)) return authRejected(v);
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

    // ------------------------------------------------------------------ 多网关自适应（v2.1.2）

    /**
     * 依次尝试候选网关，返回第一个「有该路由」的响应；全部都是网关级 404 时返回 null。
     *
     * <p>背景：{@code /trae/api/v2/*} 只挂载在 {@link #CN_BASE}。国际版账号网关
     * {@link #AI_ACCOUNT_BASE} 只代理 OAuth/账号路由，打 v2 会返回 nginx 的
     * {@code <html>404 Not Found</html>} 或 {@code 404 Page not found}，与业务 JSON 的
     * 4xx 完全不同 —— 前者是「这个网关没有这套接口」，应换下一个；后者是真实业务响应，
     * 必须交给上层解析，不能当成路由缺失。
     */
    private static Http.Resp postAny(String variant, String path, Map<String, String> h,
                                     String body, int readMs) {
        String[] bs = bases(variant);
        for (int i = 0; i < bs.length; i++) {
            Http.Resp r = Http.request("POST", bs[i] + path, h, body, 12000, readMs);
            if (isRouteMissing(r)) continue;  // 网关没挂这套路由 → 换下一个
            return r;                          // 非「路由缺失」的响应（含网络错误/401/业务），交给上层
        }
        return null;                           // 所有候选网关都没有这套路由 → 未开放
    }

    /**
     * 网关级「路由不存在 / 非 API 响应」：
     * 404 的 nginx HTML / CDN 纯文本 404，或任意状态码下返回 HTML（SPA fallback）。
     * 业务响应一定以 {@code {} 或 JSON 起头}，HTML 绝不能当业务结果解析。
     */
    private static boolean isRouteMissing(Http.Resp r) {
        if (r == null || r.error != null) return false;
        String b = (r.body == null ? "" : r.body.trim().toLowerCase());
        if (b.startsWith("{")) return false;          // 业务 JSON，交上层
        if (b.isEmpty()) return true;                 // 空体
        if (b.contains("<html") || b.contains("<!doctype html")) return true;  // HTML 页（含 200 fallback）
        if (r.status == 404 && (b.contains("404 not found") || b.contains("404 page not found")))
            return true;                              // CDN 纯文本 404
        return false;
    }

    /**
     * 鉴权被拒：上游在国内网关用「HTTP 200 + code:1001 / not able to authenticate you」
     * 表达会话失效（不是 401），国际版账号因账号体系不通也常落到这里。必须有可操作诊断，
     * 否则用户只看到一串英文或莫名其妙的 404。
     */
    private static boolean isAuthReject(Http.Resp r) {
        if (r == null || r.error != null) return false;
        if (r.status == 401 || r.status == 403) return true;
        String b = (r.body == null ? "" : r.body.toLowerCase());
        if (b.contains("not able to authenticate") || b.contains("unauthoriz")
                || b.contains("invalid token") || b.contains("token expired")) return true;
        JSONObject j = obj(r.body);
        long code = j.optLong("code", 0L);
        return code == 1001 || code == 401 || code == 403;
    }

    /** 所有候选网关都没有这套路由：如实判「未开放」，不计成功也不计失败。 */
    private static JSONObject noRoute(String variant) {
        String head = "ai".equals(variant)
                ? "Trae 国际版账号网关（grow-normal.trae.ai）未开放 /trae/api/v2 签到 / 积分路由，官方只在该网关提供账号与 OAuth 接口"
                : "Trae 上游暂未开放该区域的签到 / 积分路由";
        return disabled(head + "；已如实标记为「未开放」，不做假登录、不虚报成功。"
                + "请到电脑端 Trae 客户端确认该账号权益，或改用官方客户端签到。");
    }

    /** 凭据被上游拒绝（含国内/国际账号体系不通）：给出可操作诊断，而不是裸英文报错。 */
    private static JSONObject authRejected(String variant) {
        String head = "ai".equals(variant)
                ? "Trae 国际版账号的 JWT 未被积分网关接受：国内 / 国际是两套独立账号体系，JWT 不通用；上游目前只对国内网关开放签到 / 积分"
                : "Trae JWT 已失效或不被接受（会话过期 / 账号不匹配）";
        return fail(head + "。请在电脑端重新导出该账号最新的 Cloud-IDE-JWT 后重新导入；"
                + "若反复出现，说明该账号所在区域暂不支持手机端签到 / 查积分。");
    }

    /** 第三档「未开放」：既非成功也非失败，单独计数（对齐 QoderApi）。 */
    private static JSONObject disabled(String m) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("disabled", true).put("message", m).put("detail", m);
        } catch (Exception ignored) { }
        return o;
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
