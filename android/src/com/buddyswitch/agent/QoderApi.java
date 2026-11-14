package com.buddyswitch.agent;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
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
    private static final String P_QUOTA = "/api/v2/quota/usage";

    // 活动平台（官方现行签到 / 领积分通道）：每日领取 100 Credits 在这里。
    // 对齐 qoder/mod.rs::PATH_CAMPAIGNS / PATH_CAMPAIGN_CLAIM_TEMPLATE。
    private static final String P_CAMPAIGNS = "/sash/api/v1/me/campaigns";
    private static final String P_CAMPAIGN_CLAIM = "/sash/api/v1/me/campaigns/{id}/claim";
    // 对齐 qoder/campaign.rs::desktop_headers（桌面端身份，手机无原生机器身份）。
    private static final String DESKTOP_CLIENT_TYPE = "10";
    private static final String DESKTOP_CLIENT_VERSION = "0.4.3";
    private static final long CLAIM_GAP_MS = 1000L;   // campaign.rs::CLAIM_GAP

    // 旧签到通道：国内版实测 DISABLED、国际版 404，只在活动平台整区不可用时兜底。
    private static final String P_CHECKIN_STATUS = "/sash/api/v1/me/daily-check-in/status";
    private static final String P_CHECKIN_CLAIM = "/sash/api/v1/me/daily-check-in/claim";

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

    /**
     * 签到即领积分：**活动平台优先**，只有整区没有活动平台时才回落旧通道。
     *
     * <p>对齐 qoder/checkin.rs::claim_for + qoder/campaign.rs::checkin_for。
     * 旧 /sash/daily-check-in/* 通道国内版实测 DISABLED、国际版 404，
     * 「每日领取 100 Credits」早已迁到 /sash/api/v1/me/campaigns。
     * v2.1.0 只打旧通道，遇到 404 就返回 skipped → 用户看到的「跳过」即由此而来。
     */
    public static JSONObject checkinOne(Context ctx, JSONObject acc) {
        String v = variantOf(acc);
        String uid = acc.optString("uid", "");
        String token = acc.optString("token", "");
        if (token.isEmpty()) return fail("凭据为空，请重新导入");
        // campaign.rs::access_token_for：PAT 不能用于活动平台（官方服务端限制）。
        if (token.startsWith("pt-")) {
            return fail("PAT 不能用于活动平台签到，请用「发起登录」扫码获得 access token");
        }

        JSONObject out = checkinViaCampaigns(ctx, v, uid, token, acc);
        if (out.optBoolean("regionUnavailable", false)) {
            return checkinViaLegacy(ctx, v, uid, token, acc);
        }
        return out;
    }

    /** 活动平台签到：GET campaigns → 逐条领取积分类活动。对齐 campaign.rs::checkin_for。 */
    private static JSONObject checkinViaCampaigns(Context ctx, String v, String uid,
                                                  String token, JSONObject acc) {
        Http.Resp r = fetchCampaigns(v, uid, token);
        if (isRegionGone(r)) {
            // issue #66：一次 404 不足以断言「本区域没有活动平台」，原样重发一次再回落。
            r = fetchCampaigns(v, uid, token);
            if (isRegionGone(r)) {
                JSONObject gone = new JSONObject();
                try { gone.put("regionUnavailable", true); } catch (Exception ignored) { }
                return gone;
            }
        }
        if (r.error != null) return fail("活动列表不可达：" + r.error);
        if (!r.ok()) return fail("活动列表失败：HTTP " + r.status + " " + brief(r.body));

        JSONObject payload = obj(r.body);
        JSONArray rows = payload.optJSONArray("campaigns");
        if (rows == null) {
            JSONObject d = payload.optJSONObject("data");
            if (d != null) rows = d.optJSONArray("campaigns");
        }
        if (rows == null) return fail("活动列表响应缺少 campaigns[]");

        boolean hasBenefit = false;
        int claimed = 0, earned = 0, already = 0, blocked = 0, pending = 0, locked = 0;
        int claimAttempts = 0;              // 相邻 claim 之间留间隔，成败都算刚打过上游
        JSONArray parts = new JSONArray();
        StringBuilder errors = new StringBuilder();

        for (int i = 0; i < rows.length(); i++) {
            JSONObject c = rows.optJSONObject(i);
            if (c == null || !isCreditKind(c)) continue;   // 只领积分类，不动券 / 兑换码
            hasBenefit = true;
            String id = campaignId(c);
            if (id.isEmpty()) continue;
            String title = campaignTitle(c);
            String status = pick(c, "claimStatus", "claim_status").toUpperCase();
            String action = pick(c, "actionType", "action_type");
            boolean actionable = action.isEmpty() || "CLAIM_BENEFIT".equals(action);
            int amount = (int) benefitAmount(c);
            String reason = pick(c, "unavailableReason", "unavailable_reason").toUpperCase();

            // ★ 归类先于 actionType 过滤：领取后上游会把 actionType 降级成 VIEW_DETAILS，
            //   先过滤会把「已领」误判成「暂无可领活动」（campaign.rs::classify）。
            if ("CLAIMED".equals(status)) {
                if (amount > 0 || actionable) {
                    already++;
                    parts.put("今日已领取 · " + title);
                }
                continue;
            }
            if (!actionable) continue;                       // View：详情类无奖励
            if (!"CLAIMABLE".equals(status)) {
                if ("REDEMPTION_CODE_OUT_OF_STOCK".equals(reason)) {
                    pending++;
                    parts.put("名额已发完 · " + title);
                } else if ("ACHIEVEMENT_NOT_COMPLETED".equals(reason)
                        || !c.optBoolean("achievementCompleted", false)) {
                    locked++;
                    parts.put("需先完成新人任务 · " + title);
                }
                continue;
            }

            if (claimAttempts > 0) sleepClaimGap();   // 相邻 claim 间隔，防风控
            claimAttempts++;
            JSONObject co = claimCampaign(v, uid, token, id);
            if (co.optBoolean("ok", false)) {
                if (co.optBoolean("replayed", false)) {
                    already++;
                    parts.put("今日已领取 · " + title);
                } else {
                    int got = co.optInt("amount", 0);
                    if (got <= 0) got = amount;
                    earned += got;
                    claimed++;
                    parts.put("领取成功 +" + got + " Credits · " + title);
                }
            } else if (co.optBoolean("blocked", false)) {
                blocked++;
                parts.put("同人已领取 · " + title);
            } else {
                if (errors.length() > 0) errors.append("; ");
                errors.append(title).append(": ").append(co.optString("message", "领取失败"));
            }
        }

        // 三档语义（checkin.rs 模块头，不许回退）：
        // 成功 = 真领到 / 今日已领 / 上游明确给出已领、名额、任务等状态；
        // 未开放 = 没有积分类活动，或全部都是详情类(View)（ok:false + disabled:true）；
        // 失败 = 上游报错。**未开放绝不能写成 ok:true**，否则界面显示「N 个成功」而额度纹丝不动。
        // 比上游 checkin_ok() 更保守一档：只按「有没有积分类活动」判定，遇到一条
        // DISABLED 的积分类活动也会报成功；这里要求至少归类到一条非 View 活动。
        boolean touched = claimed + already + blocked + pending + locked > 0;
        boolean ok = errors.length() == 0 && touched;
        String message;
        if (claimed > 0) message = "活动领取成功 +" + earned + " Credits（" + claimed + " 项）";
        else if (blocked > 0) message = "同人已领取：同一设备 / 身份下其他账号本轮已领过";
        else if (already > 0) message = "今日活动奖励已领取（" + already + " 项）";
        else if (pending > 0) message = "名额已发完，次日 10:00 后可再领（" + pending + " 项）";
        else if (locked > 0) message = "需先在官方桌面端完成新人任务（" + locked + " 项）";
        else if (errors.length() > 0) message = "活动领取失败：" + brief(errors.toString());
        else message = "当前账号暂无可领取的官方活动（签到通道未开放）";

        JSONObject out = new JSONObject();
        try {
            out.put("ok", ok)
                    .put("disabled", !touched)      // 未开放：不计成功、也不显示成崩溃
                    .put("hasBenefit", hasBenefit)
                    .put("already", already > 0)
                    .put("claimedCount", claimed)
                    .put("earned", earned)
                    .put("creditsGained", earned)
                    .put("source", "campaign")
                    .put("message", message)
                    .put("detail", message)
                    .put("parts", parts)
                    .put("nextAvailableNote", nextCheckinNote());
        } catch (Exception ignored) { }
        if (ok) Prefs.log(ctx, "Qoder 活动签到成功：" + acc.optString("label", "") + " " + message);
        return out;
    }

    /** 旧 /sash/daily-check-in/* 兜底，仅在活动平台整区不可用时调用。 */
    private static JSONObject checkinViaLegacy(Context ctx, String v, String uid,
                                               String token, JSONObject acc) {
        Map<String, String> h = openapiHeaders(v, uid, token);
        Http.Resp st = Http.request("GET", openapi(v) + P_CHECKIN_STATUS, h, null, 12000, 15000);
        if (st.status == 404 || st.status == 405 || st.status == 410) {
            return disabled("该区域没有活动平台，旧签到通道也已下线（HTTP " + st.status + "）");
        }
        if (st.error != null) return fail("网络不可达：" + st.error);
        if (!st.ok()) return fail("签到状态失败：HTTP " + st.status + " " + brief(st.body));

        JSONObject s = obj(st.body);
        String status = pick(s, "status").toUpperCase();
        if ("DISABLED".equals(status)) {
            return disabled("签到通道已停用（上游 DISABLED），Qoder 已改为活动平台领积分");
        }
        if ("CLAIMED".equals(status) || looksCheckedIn(s)) {
            return alreadyClaimed("今日已签到");
        }

        Http.Resp cl = Http.request("POST", openapi(v) + P_CHECKIN_CLAIM, h, "{}", 12000, 15000);
        if (cl.error != null) return fail("网络不可达：" + cl.error);
        if (!cl.ok()) {
            String b = cl.body.toLowerCase();
            if (b.contains("already") || b.contains("checked") || b.contains("已签")) {
                return alreadyClaimed("今日已签到");
            }
            return fail("签到失败：HTTP " + cl.status + " " + brief(cl.body));
        }
        JSONObject j = obj(cl.body);
        if (j.has("success") && !j.optBoolean("success", true)) {
            // 上游「先扣后报错」：复查一次状态，可能已记账。
            Http.Resp re = Http.request("GET", openapi(v) + P_CHECKIN_STATUS, h, null, 12000, 15000);
            if (re.ok() && looksCheckedIn(obj(re.body))) return alreadyClaimed("今日已签到");
            return fail("签到失败：" + brief(j.optString("error", cl.body)));
        }
        int reward = (int) j.optDouble("rewardCredits", 0);  // 上游可能写 100.0，不可只按整数取
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("earned", reward).put("creditsGained", reward)
                    .put("source", "legacy")
                    .put("message", "签到成功 +" + reward + " Credits")
                    .put("detail", "签到成功 +" + reward + " Credits（旧通道）");
        } catch (Exception ignored) { }
        Prefs.log(ctx, "Qoder 旧通道签到成功：" + acc.optString("label", ""));
        return out;
    }

    // ------------------------------------------------------------------ 活动平台协议

    /** 对齐 campaign.rs::desktop_headers。★ 刻意不发 cosy-machine* 头。 */
    private static Map<String, String> desktopHeaders(String v, String uid, String token) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("User-Agent", "Qoder");                 // 活动平台要求，别用 QoderWork/x.y.z
        h.put("Authorization", "Bearer " + token);
        String safeUid = uid == null || uid.isEmpty() ? "anonymous" : uid;
        h.put("X-Request-ID", md5("req:" + safeUid) + "-"
                + String.format("%06d", System.currentTimeMillis() % 1000000));
        h.put("X-Machine-ID", md5("machine:" + safeUid));   // device.rs::derive_id
        h.put("X-Session-ID", md5("session:" + safeUid));
        h.put("Origin", website(v));
        h.put("Referer", website(v) + "/");
        h.put("cosy-clienttype", DESKTOP_CLIENT_TYPE);
        h.put("cosy-version", DESKTOP_CLIENT_VERSION);
        // 手机没有官方原生机器身份：发派生假头会被服务端判定为非官方客户端，
        // 把 CLAIMABLE 的「每日领取 100 Credits」整条过滤掉，比不发更糟。
        return h;
    }

    private static Http.Resp fetchCampaigns(String v, String uid, String token) {
        return Http.request("GET", openapi(v) + P_CAMPAIGNS,
                desktopHeaders(v, uid, token), null, 12000, 15000);
    }

    private static boolean isRegionGone(Http.Resp r) {
        return r != null && r.error == null
                && (r.status == 404 || r.status == 405 || r.status == 410);
    }

    /** POST /sash/api/v1/me/campaigns/{id}/claim（官方幂等）。 */
    private static JSONObject claimCampaign(String v, String uid, String token, String id) {
        String url = openapi(v) + P_CAMPAIGN_CLAIM.replace("{id}", q(id));
        Http.Resp r = Http.request("POST", url, desktopHeaders(v, uid, token), "{}", 12000, 20000);
        if (r.status == 409) {                       // 上游幂等确认：今日已领
            return claimOutcome(true, true, false, 0, "今日已领取（上游幂等确认）");
        }
        if (r.error != null) return claimOutcome(false, false, false, 0, "领取请求失败：" + r.error);
        if (!r.ok()) {
            String code = pick(obj(r.body), "errorCode", "error_code");
            if ("ALREADY_CLAIMED".equalsIgnoreCase(code) || "REPLAYED".equalsIgnoreCase(code)) {
                return claimOutcome(true, true, false, 0, "今日已领取（上游幂等确认）");
            }
            return claimOutcome(false, false, false, 0,
                    "领取失败：HTTP " + r.status + " " + brief(r.body));
        }
        return interpretClaim(obj(r.body));
    }

    /** 对齐 campaign.rs::interpret_claim：幂等重放 / 同人已领 / 已知失败码 / 真成功。 */
    private static JSONObject interpretClaim(JSONObject p) {
        String status = pick(p, "status").toUpperCase();
        String code = pick(p, "failureCode", "failure_code").toUpperCase();
        String redeem = pick(p, "redemptionCode", "redemption_code");
        boolean replayed = p.optBoolean("replayed", false);
        int amount = (int) benefitAmount(p);
        if (amount <= 0) amount = (int) p.optDouble("amount", 0);

        if ("SAME_PERSON_ALREADY_CLAIMED".equals(code) || "BLOCKED".equals(status)) {
            return claimOutcome(false, false, true, 0, "同人已领取（服务端按人去重）");
        }
        if (status.isEmpty() && p.has("success") && !p.optBoolean("success", true)) {
            return claimOutcome(false, false, false, 0,
                    "领取失败：" + brief(p.optString("error", p.toString())));
        }
        String reason = failureReason(code);
        if (reason != null) return claimOutcome(false, false, false, 0, reason);

        boolean granted = "CLAIMED".equals(status) || "GRANTED".equals(status) || "SUCCESS".equals(status);
        String msg = replayed ? "今日已领取"
                : granted ? (redeem.isEmpty() ? "领取成功" : "领取成功，兑换码：" + redeem)
                : "上游状态：" + (status.isEmpty() ? "未知" : status);
        return claimOutcome(granted, replayed, false, amount, msg);
    }

    private static JSONObject claimOutcome(boolean ok, boolean replayed, boolean blocked,
                                           int amount, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok).put("replayed", replayed).put("blocked", blocked)
                    .put("amount", amount).put("message", message);
        } catch (Exception ignored) { }
        return o;
    }

    /** campaign.rs::FAILURE_REASONS —— 只是一张展示表，判据永远是 claimStatus / actionType。 */
    private static String failureReason(String code) {
        if (code.isEmpty()) return null;
        if ("REDEMPTION_CODE_OUT_OF_STOCK".equals(code)) return "今日名额已发完（每日 10:00 刷新）";
        if ("ACHIEVEMENT_NOT_COMPLETED".equals(code)) return "需先完成新人任务";
        if ("CAMPAIGN_NOT_ACTIVE".equals(code)) return "活动已结束 / 未开始";
        if ("RISK_BLOCKED".equals(code)) return "风控拦截（当前设备 / 账号不可领取）";
        if ("RISK_DEPENDENCY_UNAVAILABLE".equals(code)) return "风控服务不可用，稍后重试";
        return null;
    }

    /** 积分类活动：benefit.kind 为 CREDITS 或空（老活动「每日领取 100 Credits」就是空 kind）。 */
    private static boolean isCreditKind(JSONObject c) {
        JSONObject b = c == null ? null : c.optJSONObject("benefit");
        String kind = b == null ? "" : b.optString("kind", "").trim().toUpperCase();
        return kind.isEmpty() || "CREDITS".equals(kind);
    }

    private static double benefitAmount(JSONObject c) {
        JSONObject b = c == null ? null : c.optJSONObject("benefit");
        return b == null ? 0 : b.optDouble("amount", 0);
    }

    private static String campaignId(JSONObject c) {
        String id = pick(c, "campaignId", "campaign_id");
        return id.isEmpty() ? pick(c, "campaignKey", "campaign_key") : id;
    }

    /** 标题：placements[].content.zh.title → en → campaignKey → id。 */
    private static String campaignTitle(JSONObject c) {
        JSONArray ps = c == null ? null : c.optJSONArray("placements");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null) continue;
                JSONObject content = p.optJSONObject("content");
                if (content == null) continue;
                String t = pick(content.optJSONObject("zh"), "title");
                if (t.isEmpty()) t = pick(content.optJSONObject("en"), "title");
                if (!t.isEmpty()) return t;
            }
        }
        String t = pick(c, "campaignKey", "campaign_key");
        return t.isEmpty() ? campaignId(c) : t;
    }

    /** 取第一个非空字符串字段（兼容 camelCase / snake_case）。 */
    private static String pick(JSONObject o, String... keys) {
        if (o == null) return "";
        for (int i = 0; i < keys.length; i++) {
            String s = o.optString(keys[i], "").trim();
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    private static void sleepClaimGap() {
        try { Thread.sleep(CLAIM_GAP_MS); } catch (Exception ignored) { }
    }

    /** 上游每日实例在 10:00（UTC+8）切换，窗口 10:00 → 次日 09:59。 */
    private static String nextCheckinNote() {
        try {
            java.util.TimeZone tz = java.util.TimeZone.getTimeZone("GMT+08:00");
            java.util.Calendar now = java.util.Calendar.getInstance(tz);
            java.util.Calendar target = (java.util.Calendar) now.clone();
            target.set(java.util.Calendar.HOUR_OF_DAY, 10);
            target.set(java.util.Calendar.MINUTE, 0);
            target.set(java.util.Calendar.SECOND, 0);
            target.set(java.util.Calendar.MILLISECOND, 0);
            if (!target.after(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1);
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
            fmt.setTimeZone(tz);
            return "下次可领 " + fmt.format(target.getTime()) + "（UTC+8）";
        } catch (Exception e) {
            return "次日 10:00（UTC+8）后可再领";
        }
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
        return skipped("Qoder 手机端不做设备令牌刷新；token 过期请重新「发起登录」扫码");
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
        try { o.put("ok", true).put("skipped", true).put("message", m).put("detail", m); }
        catch (Exception ignored) { }
        return o;
    }

    /** 第三档「未开放」：**不能**写成 ok:true，否则界面显示成功而额度纹丝不动。 */
    private static JSONObject disabled(String m) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("disabled", true).put("message", m).put("detail", m);
        } catch (Exception ignored) { }
        return o;
    }

    /** 今日已领：上游 CLAIMED 是事实，属于成功档，不是「跳过」。 */
    private static JSONObject alreadyClaimed(String m) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("already", true).put("message", m).put("detail", m);
        } catch (Exception ignored) { }
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
