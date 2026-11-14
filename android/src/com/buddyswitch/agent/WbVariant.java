package com.buddyswitch.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * WorkBuddy 两个档位（国内版 cn / 国际版 ai）。
 *
 * <p>照搬 fewards 的 core/workbuddy/WorkBuddyVariant.kt 与 workbuddy-console 的
 * server/workbuddy/variant.mjs。两档是两套独立客户端、两套域名、两套登录态，
 * 绝不跨档请求：国际版 token 打国内域只会 401，看起来像"凭据失效"。
 *
 * <ul>
 *   <li>积分 / 成长中心：走 apiEndpoint（官网域）</li>
 *   <li>扫码登录 / token 刷新：走 chatBase + oauthPlatform</li>
 *   <li>签到 / 成长中心：目前只有国内版有接口，国际版显式跳过</li>
 * </ul>
 */
public final class WbVariant {
    public final String id, label, appLabel, apiEndpoint, chatBase, oauthPlatform, uaPlatform;
    public final boolean supportsCheckin, supportsGrowth;

    private WbVariant(String id, String label, String appLabel, String apiEndpoint,
                      String chatBase, String oauthPlatform, String uaPlatform,
                      boolean supportsCheckin, boolean supportsGrowth) {
        this.id = id; this.label = label; this.appLabel = appLabel;
        this.apiEndpoint = apiEndpoint; this.chatBase = chatBase;
        this.oauthPlatform = oauthPlatform; this.uaPlatform = uaPlatform;
        this.supportsCheckin = supportsCheckin; this.supportsGrowth = supportsGrowth;
    }

    public static final WbVariant CN = new WbVariant(
            "cn", "国内版", "WorkBuddy",
            "https://www.codebuddy.cn", "https://copilot.tencent.com",
            "CLI", "WorkBuddy", true, true);

    public static final WbVariant AI = new WbVariant(
            "ai", "国际版", "WorkBuddy AI",
            "https://www.workbuddy.ai", "https://www.workbuddy.ai",
            "workbuddy-ai", "WorkBuddy AI", false, false);

    /** 归一化：兼容 ai / workbuddy-ai / intl，其余一律按国内版（历史数据零迁移）。 */
    public static WbVariant of(String raw) {
        if (raw == null) return CN;
        String s = raw.trim().toLowerCase();
        if ("ai".equals(s) || "workbuddy-ai".equals(s) || "intl".equals(s)) return AI;
        return CN;
    }

    public static String normalize(String raw) { return of(raw).id; }

    /**
     * 扫码授权 / 刷新用的出站指纹头。与官方 CLI 一致，Origin / Referer 随档位变。
     * 对齐 fewards WorkBuddyLogin.headersOf。
     */
    public Map<String, String> loginHeaders() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("User-Agent", "CLI/2.63.2 CodeBuddy/2.63.2");
        h.put("Origin", apiEndpoint);
        h.put("Referer", apiEndpoint + "/");
        return h;
    }

    /**
     * 积分 / 成长中心的出站头。对齐 fewards WorkBuddyCredits.authHeaders 与
     * WorkBuddyGrowth.headers：官网域 + web 平台指纹 + uid / 企业 id。
     */
    public Map<String, String> apiHeaders(String token, String uid, String enterpriseId) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + (token == null ? "" : token.trim()));
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("X-Client-Platform", "web");
        h.put("Origin", apiEndpoint);
        h.put("Referer", apiEndpoint + "/profile/plans-usage");
        if (uid != null && uid.length() > 0) h.put("X-User-Id", uid);
        if (enterpriseId != null && enterpriseId.length() > 0) {
            h.put("X-Enterprise-Id", enterpriseId);
            h.put("X-Tenant-Id", enterpriseId);
        }
        return h;
    }

    /** 签到专用头（copilot 域，Bearer token）。 */
    public Map<String, String> checkinHeaders(String token) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("Authorization", "Bearer " + (token == null ? "" : token.trim()));
        return h;
    }
}
