package com.buddyswitch.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorkBuddy 官方 API 客户端 —— 手机独立运行，无需电脑参与。
 *
 * <p>协议全部照搬已上线的 fewards（手机端 Kotlin）与 workbuddy-console（电脑端 JS）：
 * <ul>
 *   <li><b>扫码登录</b>：POST /v2/plugin/auth/state → 轮询 /v2/plugin/auth/token
 *       → /v2/plugin/login/account，拿到的 access/refresh token 只存手机本地。</li>
 *   <li><b>每日签到</b>：POST copilot.tencent.com/billing/meter/daily-checkin（Bearer）。</li>
 *   <li><b>成长中心领积分</b>：www.codebuddy.cn/v2/activity/growth（任务接单/领奖 + Buddy 旅行）。</li>
 *   <li><b>积分余额</b>：{apiEndpoint}/billing/meter/get-user-resource-summary。</li>
 *   <li><b>凭据刷新</b>：POST {chatBase}/v2/plugin/auth/token/refresh（X-Refresh-Token）。</li>
 *   <li><b>可选同步</b>：局域网 /api/lansync/*，把账号推给电脑端控制台。</li>
 * </ul>
 *
 * 所有请求走 {@link Http}（Java 层），因为 WebView 里的 JS 不能伪造 Origin / UA 指纹头。
 */
public final class WorkBuddyApi {

    // ---- 上游端点（与 fewards 常量逐字对齐） ----
    private static final String P_AUTH_STATE = "/v2/plugin/auth/state";
    private static final String P_AUTH_TOKEN = "/v2/plugin/auth/token";
    private static final String P_LOGIN_ACCOUNT = "/v2/plugin/login/account";
    private static final String P_REFRESH = "/v2/plugin/auth/token/refresh";
    private static final String CHECKIN_BASE = "https://copilot.tencent.com";
    private static final String P_CHECKIN_STATUS = "/billing/meter/checkin-status";
    private static final String P_DAILY = "/billing/meter/daily-checkin";
    private static final String P_SUMMARY = "/billing/meter/get-user-resource-summary";
    private static final String P_PAID = "/billing/meter/get-user-resource-paid-packages";
    private static final String P_FREE = "/billing/meter/get-user-resource-free-packages";
    private static final String GROWTH_BASE = "https://www.codebuddy.cn/v2/activity/growth";

    /** 官方「每月赠送基础 Credits」免费包商品码（对齐 core credits.rs::FREE_PACKAGE_CODES）。 */
    private static final String[] FREE_PACKAGE_CODES = {
            "TCACA_code_008_cfWoLwvjU4", "TCACA_code_007_nzdH5h4Nl0",
            "TCACA_code_028_NtpWi0jzXs", "TCACA_code_029_6wCGEWquYy",
            "TCACA_code_030_BjSt89qTvr"
    };

    private static final long STATE_TTL_MS = 300_000L;   // 二维码 5 分钟
    private static final int CODE_WAITING = 11217;       // 未扫码
    private static final int CODE_ALREADY = 10001;       // 今日已签

    private static final String JSON = "{}";

    private WorkBuddyApi() { }

    // ------------------------------------------------------------------ 工具

    private static JSONObject obj(String s) {
        try { return new JSONObject(s == null ? "" : s); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** 腾讯统一信封 {code,msg,data}：判定只看 code，缺 code 一律算失败。 */
    private static boolean okCode(JSONObject j) {
        return j.has("code") && j.optInt("code", -1) == 0;
    }

    private static int envCode(JSONObject j) {
        return j.has("code") ? j.optInt("code", -1) : -1;
    }

    /** 在可能被 data / result / resp / response 包裹的响应里递归找字段。 */
    private static Object dig(JSONObject j, String key) {
        if (j == null) return null;
        if (j.has(key)) return j.opt(key);
        String[] wrap = {"data", "result", "resp", "response"};
        for (String w : wrap) {
            JSONObject sub = j.optJSONObject(w);
            if (sub != null) {
                Object v = dig(sub, key);
                if (v != null) return v;
            }
        }
        return null;
    }

    private static JSONArray digArr(JSONObject j, String key) {
        Object v = dig(j, key);
        if (v instanceof JSONArray) return (JSONArray) v;
        if (j != null && j.optJSONArray(key) != null) return j.optJSONArray(key);
        return null;
    }

    private static int digInt(JSONObject j, String key) {
        Object v = dig(j, key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) { try { return Integer.parseInt((String) v); } catch (Exception ignored) { } }
        return 0;
    }

    private static double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) { try { return Double.parseDouble((String) v); } catch (Exception ignored) { } }
        return 0;
    }

    private static String firstStr(JSONObject j, String... keys) {
        for (String k : keys) {
            String v = j.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static Object firstVal(JSONObject j, String... keys) {
        for (String k : keys) { Object v = j.opt(k); if (v != null) return v; }
        return null;
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ");
        return t.length() > 180 ? t.substring(0, 180) + "…" : t;
    }

    private static JSONObject fail(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false).put("error", msg); } catch (Exception ignored) { }
        return o;
    }

    private static Http.Resp call(String method, String url, Map<String, String> h, String body) {
        return Http.request(method, url, h, body, 12000, 20000);
    }

    private static Http.Resp callLong(String method, String url, Map<String, String> h, String body) {
        return Http.request(method, url, h, body, 10000, 90000);
    }

    private static String q(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }

    // ================================================================== 扫码登录

    private static final class Session {
        String variant; long startedAt;
        Session(String v) { variant = v; startedAt = System.currentTimeMillis(); }
    }
    private static final ConcurrentHashMap<String, Session> SESSIONS =
            new ConcurrentHashMap<String, Session>();

    /** 第一步：申请授权链接。authUrl 即二维码内容，也是手机浏览器可直接打开的授权页。 */
    public static JSONObject loginStart(Context ctx, String variantRaw) {
        WbVariant v = WbVariant.of(variantRaw);
        String url = v.chatBase + P_AUTH_STATE + "?platform=" + q(v.oauthPlatform);
        Http.Resp r = call("POST", url, v.loginHeaders(), JSON);
        if (r.error != null) return fail("网络不可达：" + r.error);
        JSONObject j = obj(r.body);
        if (!okCode(j)) return fail("获取授权链接失败 code=" + envCode(j)
                + " " + firstStr(j, "msg", "message"));
        JSONObject d = j.optJSONObject("data");
        String state = d == null ? "" : d.optString("state", "").trim();
        String authUrl = d == null ? "" : d.optString("authUrl", "").trim();
        if (state.isEmpty() || authUrl.isEmpty()) return fail("授权链接响应缺少 state / authUrl");
        SESSIONS.put(state, new Session(v.id));
        // 清理过期会话
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Session> e : SESSIONS.entrySet()) {
            if (now - e.getValue().startedAt > STATE_TTL_MS * 2) SESSIONS.remove(e.getKey());
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("state", state).put("authUrl", authUrl)
                    .put("variant", v.id).put("variantLabel", v.label)
                    .put("appLabel", v.appLabel).put("expiresIn", STATE_TTL_MS / 1000);
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * 第二/三步：查询一次扫码结果。成功时取回账号信息并落本地库。
     * 未扫码（code != 0，实测 11217）不是错误，返回 status=pending。
     */
    public static JSONObject loginPoll(Context ctx, String state) {
        Session s = state == null ? null : SESSIONS.get(state);
        if (s == null) return loginExpired("登录会话不存在或已过期，请重新发起");
        if (System.currentTimeMillis() - s.startedAt > STATE_TTL_MS) {
            SESSIONS.remove(state);
            return loginExpired("二维码已过期（5 分钟），请重新发起");
        }
        WbVariant v = WbVariant.of(s.variant);

        Http.Resp tr = call("GET", v.chatBase + P_AUTH_TOKEN + "?state=" + q(state),
                v.loginHeaders(), null);
        if (tr.error != null) return loginPending("网络异常，重试中…");
        JSONObject tj = obj(tr.body);
        int code = tj.has("code") ? tj.optInt("code", -1) : -1;
        if (code != 0) return loginPending(code == CODE_WAITING ? "等待扫码 / 确认授权…" : "等待授权…");

        JSONObject td = tj.optJSONObject("data");
        String access = td == null ? "" : td.optString("accessToken", "").trim();
        if (access.isEmpty()) return loginPending("授权处理中…");
        String refresh = td == null ? "" : td.optString("refreshToken", "").trim();
        int expiresIn = td.optInt("expiresIn", 3600);
        if (expiresIn <= 0) expiresIn = 3600;
        String domain = td.optString("domain", "").trim();

        // 跨档 token 入库后会一直"登录失效"，宁可这一步拒掉
        if (!domain.isEmpty()) {
            String dl = domain.toLowerCase();
            boolean isAi = dl.equals("workbuddy.ai") || dl.endsWith(".workbuddy.ai");
            if ((v == WbVariant.AI) != isAi) {
                SESSIONS.remove(state);
                return fail("登录响应的 domain（" + domain + "）与" + v.label + "不符，已拒绝入库");
            }
        }

        Map<String, String> h = v.loginHeaders();
        h.put("Authorization", "Bearer " + access);
        Http.Resp ar = call("GET", v.chatBase + P_LOGIN_ACCOUNT + "?state=" + q(state), h, null);
        JSONObject aj = obj(ar.body);
        JSONObject ad = aj.optJSONObject("data");
        String uid = ad == null ? "" : ad.optString("uid", "").trim();
        if (uid.isEmpty()) {
            // 有些响应把字段包在 data.result 里
            Object u = dig(aj, "uid");
            if (u instanceof String) uid = ((String) u).trim();
        }
        if (uid.isEmpty()) return loginPending("账号信息尚未就绪，稍后重试…");

        String nick = ad == null ? "" : ad.optString("nickname", "").trim();
        String ent = ad == null ? "" : ad.optString("enterpriseId", "").trim();
        JSONObject jwt = AccountStore.decodeJwt(access);
        if (nick.isEmpty()) nick = jwt.optString("nickname", "");
        if (ent.isEmpty()) ent = jwt.optString("enterpriseId", "");
        if (nick.isEmpty()) nick = "WorkBuddy 账号";

        JSONObject acc = AccountStore.make(uid, nick, access, v.id);
        try {
            acc.put("enterpriseId", ent);
            acc.put("refreshToken", refresh);
            acc.put("expiresAt", System.currentTimeMillis() / 1000 + expiresIn);
            acc.put("obtainedAt", System.currentTimeMillis());
            acc.put("avatarUrl", jwt.optString("avatarUrl", ""));
            if (!domain.isEmpty()) acc.put("domain", domain);
        } catch (Exception ignored) { }
        AccountStore.put(ctx, acc);
        SESSIONS.remove(state);
        Prefs.log(ctx, "扫码登录成功：" + v.label + " " + nick);

        JSONObject out = new JSONObject();
        try {
            out.put("ok", true).put("status", "ready")
                    .put("account", AccountStore.find(ctx, acc.optString("id")) == null
                            ? new JSONObject() : publicOne(acc));
        } catch (Exception ignored) { }
        return out;
    }

    private static JSONObject loginPending(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", true).put("status", "pending").put("message", msg); }
        catch (Exception ignored) { }
        return o;
    }

    private static JSONObject loginExpired(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", true).put("status", "expired").put("message", msg); }
        catch (Exception ignored) { }
        return o;
    }

    public static JSONObject loginCancel(String state) {
        if (state != null) SESSIONS.remove(state);
        JSONObject o = new JSONObject();
        try { o.put("ok", true); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject publicOne(JSONObject a) {
        JSONObject p = new JSONObject();
        try {
            p.put("id", a.optString("id")).put("label", a.optString("label"))
                    .put("uid", a.optString("uid")).put("variant", WbVariant.normalize(a.optString("variant")))
                    .put("variantLabel", WbVariant.of(a.optString("variant")).label)
                    .put("tokenMask", AccountStore.mask(a.optString("token")));
        } catch (Exception ignored) { }
        return p;
    }

    /** 手动粘贴 token 导入（无需扫码）。对齐全平台：JWT 里能解出 uid / 昵称就用上。 */
    public static JSONObject addManual(Context ctx, String variantRaw, String text) {
        String token = text == null ? "" : text.trim();
        if (token.isEmpty()) return fail("请先粘贴 access_token 或凭据 JSON");
        // 兼容粘贴整个凭据 JSON
        if (token.startsWith("{") || token.startsWith("[")) {
            int n = AccountStore.importAll(ctx, token);
            if (n <= 0) return fail("没有解析到有效账号（需含 token / accessToken 字段）");
            JSONObject o = new JSONObject();
            try { o.put("ok", true).put("count", n); } catch (Exception ignored) { }
            return o;
        }
        JSONObject jwt = AccountStore.decodeJwt(token);
        String uid = jwt.optString("sub", "");
        String nick = jwt.optString("nickname", "");
        if (nick.isEmpty()) nick = "手动导入账号";
        WbVariant v = WbVariant.of(variantRaw);
        JSONObject acc = AccountStore.make(uid, nick, token, v.id);
        try {
            acc.put("avatarUrl", jwt.optString("avatarUrl", ""));
            acc.put("enterpriseId", jwt.optString("enterpriseId", ""));
            long exp = jwt.optLong("exp", 0L);
            if (exp > 0) acc.put("expiresAt", exp);
        } catch (Exception ignored) { }
        AccountStore.put(ctx, acc);
        Prefs.log(ctx, "手动导入账号：" + v.label + " " + nick);
        JSONObject o = new JSONObject();
        try { o.put("ok", true).put("count", 1).put("account", publicOne(acc)); }
        catch (Exception ignored) { }
        return o;
    }

    // ================================================================== 每日签到

    /** 单个账号签到。返回 {ok, already, expired, skipped, detail}。 */
    public static JSONObject checkinOne(Context ctx, JSONObject acc) {
        JSONObject out = new JSONObject();
        String label = acc.optString("label", acc.optString("id", "?"));
        WbVariant v = WbVariant.of(acc.optString("variant", "cn"));
        String token = acc.optString("token", "").trim();
        try {
            if (!v.supportsCheckin) {
                out.put("ok", true).put("skipped", true)
                        .put("detail", v.label + "（" + v.appLabel + "）暂无签到接口，已跳过");
                return out;
            }
            if (token.isEmpty()) {
                out.put("ok", false).put("detail", "未配置 access_token");
                return out;
            }
            Map<String, String> h = v.checkinHeaders(token);

            // 1) 状态查询：省一次请求 + 401 探测
            Http.Resp st = call("POST", CHECKIN_BASE + P_CHECKIN_STATUS, h, JSON);
            if (st.status == 401 || st.status == 403) {
                out.put("ok", false).put("expired", true).put("detail", "token 已过期，请刷新凭据或重新扫码");
                return out;
            }
            JSONObject sj = obj(st.body);
            JSONObject sd = sj.optJSONObject("data");
            if (st.status >= 200 && st.status < 300 && sd != null && sd.optBoolean("today_checked_in", false)) {
                out.put("ok", true).put("already", true).put("detail", "今日已签到（状态接口）");
                return out;
            }

            // 2) 领取（小随机延迟，避免整点风控）
            try { Thread.sleep(300 + (System.currentTimeMillis() % 500)); } catch (Exception ignored) { }
            Http.Resp dr = call("POST", CHECKIN_BASE + P_DAILY, h, JSON);
            if (dr.status == 401 || dr.status == 403) {
                out.put("ok", false).put("expired", true).put("detail", "token 已过期，请刷新凭据或重新扫码");
                return out;
            }
            JSONObject dj = obj(dr.body);
            int code = dj.optInt("code", dr.status >= 200 && dr.status < 300 ? 0 : -1);
            if (code == 0) {
                JSONObject dd = dj.optJSONObject("data");
                String credit = dd == null ? "" : String.valueOf(dd.opt("credit"));
                String streak = dd == null ? "" : String.valueOf(dd.opt("streak_days"));
                out.put("ok", true).put("detail", "领取成功" +
                        (dd != null && !"null".equals(credit) ? " +" + credit + " 积分" : "") +
                        (dd != null && !"null".equals(streak) ? "，连签 " + streak + " 天" : ""));
            } else if (code == CODE_ALREADY) {
                out.put("ok", true).put("already", true).put("detail", "今日已签到（code=10001）");
            } else {
                out.put("ok", false).put("detail", "签到失败 code=" + code + " "
                        + firstStr(dj, "msg", "message") + " " + brief(dr.body));
            }
        } catch (Exception e) {
            try { out.put("ok", false).put("detail", "异常：" + e.getMessage()); } catch (Exception ignored) { }
        }
        try { out.put("label", label); } catch (Exception ignored) { }
        return out;
    }

    // ================================================================== 成长中心领积分

    /**
     * 一键领取成长中心积分（仅国内版）。对齐 fewards WorkBuddyGrowth.run：
     * 任务接单 + 已完成任务领奖 + Buddy 旅行领取。返回报告与本次累计积分。
     */
    public static JSONObject growthOne(Context ctx, JSONObject acc) {
        JSONObject out = new JSONObject();
        JSONArray parts = new JSONArray();
        int credits = 0, successes = 0, failures = 0;
        WbVariant v = WbVariant.of(acc.optString("variant", "cn"));
        String token = acc.optString("token", "").trim();
        try {
            if (!v.supportsGrowth) {
                out.put("ok", true).put("skipped", true).put("creditsGained", 0)
                        .put("report", v.label + "暂无成长中心，已跳过").put("parts", parts);
                return out;
            }
            if (token.isEmpty()) {
                out.put("ok", false).put("creditsGained", 0).put("report", "未配置 access_token")
                        .put("parts", parts);
                return out;
            }
            Map<String, String> h = v.apiHeaders(token, acc.optString("uid", ""),
                    acc.optString("enterpriseId", ""));

            // ---- 1. 任务：查列表 → 接单（复数 task_codes）→ 已完成领奖 ----
            Http.Resp t = call("GET", GROWTH_BASE + "/tasks", h, null);
            if (t.status == 401 || t.status == 403) {
                out.put("ok", false).put("unauthorized", true).put("creditsGained", 0)
                        .put("report", "登录态被拒绝（HTTP " + t.status + "），请刷新凭据").put("parts", parts);
                return out;
            }
            JSONArray tasks = digArr(obj(t.body), "tasks");
            if (tasks != null) {
                List<String> pending = new ArrayList<String>();
                List<JSONObject> completed = new ArrayList<JSONObject>();
                Map<String, String> titles = new HashMap<String, String>();
                for (int i = 0; i < tasks.length(); i++) {
                    JSONObject task = tasks.optJSONObject(i);
                    if (task == null) continue;
                    String code = task.optString("task_code", "");
                    if (code.isEmpty()) continue;
                    String title = task.optString("title", "");
                    titles.put(code, title.isEmpty() ? code : title);
                    boolean locked = task.optBoolean("locked", false);
                    String as = task.optString("accept_status", "");
                    if (!locked && "not_accepted".equals(as)) pending.add(code);
                    if (!locked && "completed".equals(as)) completed.add(task);
                }
                // 分批接单，避免 body 过大
                for (int from = 0; from < pending.size(); from += 20) {
                    List<String> batch = pending.subList(from, Math.min(from + 20, pending.size()));
                    JSONObject body = new JSONObject().put("task_codes", new JSONArray(batch));
                    Http.Resp a = call("POST", GROWTH_BASE + "/tasks/accept", h, body.toString());
                    if (a.status == 401 || a.status == 403) {
                        out.put("ok", false).put("unauthorized", true).put("creditsGained", credits)
                                .put("report", "登录态被拒绝，请刷新凭据").put("parts", parts);
                        return out;
                    }
                    JSONArray results = digArr(obj(a.body), "results");
                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject rr = results.optJSONObject(i);
                            if (rr == null) continue;
                            String code = rr.optString("task_code", "");
                            String title = titles.containsKey(code) ? titles.get(code) : code;
                            if ("error".equals(rr.optString("status", ""))) {
                                parts.put("接单「" + title + "」失败：" + rr.optString("message", ""));
                                failures++;
                            } else {
                                parts.put("已接单「" + title + "」"); successes++;
                            }
                        }
                    } else if (a.status >= 200 && a.status < 300) {
                        for (String code : batch) {
                            parts.put("已接单「" + (titles.containsKey(code) ? titles.get(code) : code) + "」");
                            successes++;
                        }
                    } else {
                        parts.put("接单失败：" + brief(a.body)); failures++;
                    }
                }
                // 领奖：只有 completed 才发奖
                for (JSONObject task : completed) {
                    String code = task.optString("task_code", "");
                    String title = titles.containsKey(code) ? titles.get(code) : code;
                    Http.Resp c = call("POST", GROWTH_BASE + "/tasks/" + q(code) + "/claim", h, JSON);
                    if (c.status == 401 || c.status == 403) {
                        out.put("ok", false).put("unauthorized", true).put("creditsGained", credits)
                                .put("report", "登录态被拒绝，请刷新凭据").put("parts", parts);
                        return out;
                    }
                    JSONObject cj = obj(c.body);
                    if (c.status >= 200 && c.status < 300 && dig(cj, "already_claimed") != Boolean.TRUE) {
                        int rc = digInt(cj, "credit");
                        if (rc == 0) rc = task.optInt("reward_credit", 0);
                        credits += rc;
                        parts.put("领任务奖「" + title + "」+" + rc + " 积分"); successes++;
                    } else if (c.status >= 200 && c.status < 300) {
                        parts.put("任务奖「" + title + "」已领过");
                    } else {
                        parts.put("领任务奖「" + title + "」失败：" + brief(c.body)); failures++;
                    }
                }
            } else {
                parts.put("查任务列表失败：" + brief(t.body)); failures++;
            }

            // ---- 2. Buddy 旅行：到达才能领 ----
            Http.Resp s = call("GET", GROWTH_BASE + "/buddy/travel/status", h, null);
            JSONObject sj = obj(s.body);
            Object arrived = dig(sj, "arrived");
            if (Boolean.TRUE.equals(arrived) || "arrived".equals(String.valueOf(dig(sj, "status")))) {
                JSONObject claim = new JSONObject();
                Object rid = dig(sj, "record_id");
                if (rid != null) claim.put("record_id", rid);
                Http.Resp c = call("POST", GROWTH_BASE + "/buddy/travel/claim", h, claim.toString());
                JSONObject cj = obj(c.body);
                if (c.status >= 200 && c.status < 300) {
                    int g = digInt(cj, "credit_granted");
                    credits += g;
                    parts.put("Buddy 旅行礼物 +" + g + " 积分"); successes++;
                } else {
                    parts.put("旅行礼物领取失败：" + brief(c.body)); failures++;
                }
            }

            out.put("ok", failures == 0).put("creditsGained", credits)
                    .put("successes", successes).put("failures", failures)
                    .put("report", "领取完成：成功 " + successes + " 项，累计 +" + credits + " 积分")
                    .put("parts", parts);
        } catch (Exception e) {
            try {
                out.put("ok", false).put("creditsGained", credits)
                        .put("report", "成长中心异常：" + e.getMessage()).put("parts", parts);
            } catch (Exception ignored) { }
        }
        return out;
    }

    // ================================================================== 积分余额

    /** 查单个账号积分。汇总接口给权威数额，付费/免费明细只补到期时间。 */
    public static JSONObject creditsOne(Context ctx, JSONObject acc) {
        JSONObject out = new JSONObject();
        WbVariant v = WbVariant.of(acc.optString("variant", "cn"));
        String token = acc.optString("token", "").trim();
        String origin = v.apiEndpoint;
        try {
            if (token.isEmpty()) {
                out.put("ok", false).put("error", "未配置 access_token"); return out;
            }
            if (acc.optString("uid", "").isEmpty()) {
                // 缺少 uid 仍尝试（部分端点只认 Bearer），但如实标注
                out.put("warn", "缺 uid，部分接口可能查询失败");
            }
            Map<String, String> h = v.apiHeaders(token, acc.optString("uid", ""),
                    acc.optString("enterpriseId", ""));
            Http.Resp r = call("POST", origin + P_SUMMARY, h, JSON);
            if (r.status == 401 || r.status == 403) {
                out.put("ok", false).put("expired", true).put("error", "登录态已失效，请刷新凭据或重新扫码");
                return out;
            }
            JSONObject j = obj(r.body);
            int code = j.optInt("code", j.optInt("Code", 0));
            if (code != 0) {
                out.put("ok", false).put("error", firstStr(j, "msg", "message", "Message")
                        + "（code=" + code + "）");
                return out;
            }
            JSONObject data = j.optJSONObject("data");
            if (data == null) data = j.optJSONObject("Data");
            if (data == null) data = j;
            JSONArray packs = data.optJSONArray("Packages");
            if (packs == null) packs = data.optJSONArray("packages");

            double total = 0, remaining = 0, used = 0;
            String unit = "credits";
            JSONArray details = new JSONArray();
            long earliestExpiry = 0;
            long now = System.currentTimeMillis();
            if (packs != null) {
                for (int i = 0; i < packs.length(); i++) {
                    JSONObject p = packs.optJSONObject(i);
                    if (p == null) continue;
                    double t = num(firstVal(p, "CycleTotalCapacity", "totalCapacity"));
                    double rem = num(firstVal(p, "CycleRemainCapacity", "remainCapacity"));
                    double u = num(firstVal(p, "CycleUsedCapacity", "usedCapacity"));
                    total += t; remaining += rem; used += u;
                    if (i == 0) {
                        String cu = p.optString("CapacityUnit", "");
                        if (!cu.isEmpty()) unit = cu;
                    }
                    long end = parseEnd(p);
                    if (end > now && rem > 0 && (earliestExpiry == 0 || end < earliestExpiry)) earliestExpiry = end;
                    JSONObject d = new JSONObject();
                    d.put("name", firstStr(p, "PackageName", "packageName", "name", "PackageCode"));
                    d.put("code", firstStr(p, "PackageCode", "packageCode"));
                    d.put("total", t).put("remaining", rem).put("used", u);
                    d.put("expireAt", end);
                    details.put(d);
                }
            } else {
                // 有些版本直接给汇总字段
                total = num(firstVal(data, "TotalCapacity", "total"));
                remaining = num(firstVal(data, "RemainCapacity", "remaining"));
                used = num(firstVal(data, "UsedCapacity", "used"));
            }
            out.put("ok", true).put("total", total).put("remaining", remaining)
                    .put("used", used).put("unit", unit).put("packages", details)
                    .put("expireAt", earliestExpiry).put("variant", v.id);
            enrichFreePackages(out, origin, h, details);
            if (out.has("warn")) { /* 保留 */ }
        } catch (Exception e) {
            try { out.put("ok", false).put("error", "解析失败：" + e.getMessage()); }
            catch (Exception ignored) { }
        }
        return out;
    }

    /**
     * 补齐「每月赠送的基础 Credits」：官方把免费月度包单独放在
     * {@code /billing/meter/get-user-resource-free-packages}，与汇总接口的资源包是两路数据。
     *
     * <p>纪律：这里只做「分类展示」，<b>不改汇总接口给出的权威 total/remaining</b> ——
     * 免费包接口与 summary 的口径可能重叠，贸然相加会重复计数、把余额算大。
     * 接口失败 / 返回空时退化为按包名识别，并在 baseHint 如实说明，绝不假装查到了。
     */
    private static void enrichFreePackages(JSONObject out, String origin,
                                           Map<String, String> h, JSONArray summaryDetails) {
        double baseTotal = 0, baseRem = 0, addonTotal = 0, addonRem = 0;
        JSONArray freeRows = null;
        try {
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            StringBuilder codes = new StringBuilder();
            for (int i = 0; i < FREE_PACKAGE_CODES.length; i++) {
                if (i > 0) codes.append(',');
                codes.append('"').append(FREE_PACKAGE_CODES[i]).append('"');
            }
            String body = "{\"PageNumber\":1,\"PageSize\":200,\"Status\":[0,3],"
                    + "\"SlicePeriodStartTime\":\"" + day + " 00:00:00\","
                    + "\"SlicePeriodEndTime\":\"" + day + " 23:59:59\","
                    + "\"PackageCodes\":[" + codes + "]}";
            Http.Resp fr = call("POST", origin + P_FREE, h, body);
            if (fr != null && fr.error == null && fr.ok()) {
                JSONObject j = obj(fr.body);
                int code = j.optInt("code", j.optInt("Code", 0));
                if (code == 0) {
                    freeRows = digArr(j, "Accounts");
                    if (freeRows == null) freeRows = digArr(j, "accounts");
                }
            }
        } catch (Exception ignored) { }

        try {
            java.util.HashSet<String> freeCodes = new java.util.HashSet<String>();
            JSONArray merged = new JSONArray();
            if (freeRows != null) {
                for (int i = 0; i < freeRows.length(); i++) {
                    JSONObject p = freeRows.optJSONObject(i);
                    if (p == null) continue;
                    double t = num(firstVal(p, "CycleTotalCapacity", "CycleCapacitySize", "totalCapacity"));
                    double rem = num(firstVal(p, "CycleRemainCapacity", "CycleCapacityRemain", "remainCapacity"));
                    double u = num(firstVal(p, "CycleUsedCapacity", "CycleCapacityUsed", "usedCapacity"));
                    String code = firstStr(p, "PackageCode", "packageCode");
                    if (!code.isEmpty()) freeCodes.add(code);
                    String name = firstStr(p, "PackageName", "packageName", "name");
                    if (name.isEmpty()) name = "每月赠送基础 Credits";
                    JSONObject d = new JSONObject();
                    d.put("name", name).put("code", code).put("kind", "base")
                            .put("total", t).put("remaining", rem).put("used", u)
                            .put("expireAt", parseEnd(p));
                    merged.put(d);
                    baseTotal += t; baseRem += rem;
                }
            }
            for (int i = 0; i < summaryDetails.length(); i++) {
                JSONObject d = summaryDetails.optJSONObject(i);
                if (d == null) continue;
                String code = d.optString("code", "");
                if (!code.isEmpty() && freeCodes.contains(code)) continue;  // 免费包已列过，不重复
                String name = d.optString("name", "");
                boolean free = name.contains("免费") || name.contains("赠送")
                        || name.contains("月度") || name.contains("基础")
                        || code.toLowerCase().contains("free");
                d.put("kind", free ? "base" : "addon");
                merged.put(d);
                if (free) {
                    baseTotal += d.optDouble("total", 0); baseRem += d.optDouble("remaining", 0);
                } else {
                    addonTotal += d.optDouble("total", 0); addonRem += d.optDouble("remaining", 0);
                }
            }
            out.put("packages", merged)
                    .put("baseTotal", baseTotal).put("baseRemaining", baseRem)
                    .put("addonTotal", addonTotal).put("addonRemaining", addonRem)
                    .put("source", freeRows != null ? "summary+free" : "summary");
            if (freeRows == null) {
                out.put("baseHint", "每月赠送基础 Credits 的独立接口未返回明细，已按资源包名称识别分类");
            }
        } catch (Exception ignored) { }
    }

    private static long parseEnd(JSONObject p) {
        Object v = firstVal(p, "DeductionEndTime", "CycleEndTime", "ExpireTime", "expireAt");
        if (v instanceof Number) {
            long n = ((Number) v).longValue();
            return n < 100000000000L ? n * 1000 : n;   // 秒 → 毫秒
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            try {
                if (s.matches("\\d+")) {
                    long n = Long.parseLong(s);
                    return n < 100000000000L ? n * 1000 : n;
                }
                return java.text.SimpleDateFormat.getDateInstance() == null ? 0
                        : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .parse(s).getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    // ================================================================== 凭据刷新

    /** 用 refresh_token 换一份新凭据（轮换式，成功后旧值作废）。 */
    public static JSONObject refreshOne(Context ctx, JSONObject acc) {
        JSONObject out = new JSONObject();
        String refresh = acc.optString("refreshToken", "").trim();
        if (refresh.isEmpty()) {
            try { out.put("ok", false).put("error", "该账号没有 refresh_token，需重新扫码登录"); }
            catch (Exception ignored) { }
            return out;
        }
        WbVariant v = WbVariant.of(acc.optString("variant", "cn"));
        Map<String, String> h = v.loginHeaders();
        h.put("User-Agent", "WorkBuddy/5.5.4 " + v.uaPlatform + "/5.5.4 CLI/2.137.1");
        h.put("X-CodeBuddy-Request", "1");
        h.put("X-Refresh-Token", refresh);
        h.put("X-Auth-Refresh-Source", "plugin");
        if (!acc.optString("uid", "").isEmpty()) h.put("X-User-Id", acc.optString("uid"));

        Http.Resp r = call("POST", v.chatBase + P_REFRESH, h, JSON);
        if (r.error != null) return fail("网络不可达：" + r.error);
        if (r.status < 200 || r.status >= 300) {
            String body = r.body.toLowerCase();
            if (body.contains("invalid") && (body.contains("refresh") || body.contains("token") || body.contains("grant"))) {
                return fail("refresh_token 已失效：官方刷新会轮换，同一账号在别处刷新过后旧值作废。请重新扫码，或先「从电脑同步凭据」");
            }
            return fail("刷新失败（HTTP " + r.status + "）" + brief(r.body));
        }
        JSONObject j = obj(r.body);
        JSONObject d = j.optJSONObject("data");
        if (d == null) d = j;
        String access = firstStr(d, "accessToken", "access_token");
        if (access.isEmpty()) return fail("刷新响应里没有 accessToken，需重新扫码登录");
        String newRefresh = firstStr(d, "refreshToken", "refresh_token");
        long expiresIn = d.optLong("expiresIn", d.optLong("expires_in", 0L));
        long refreshIn = d.optLong("refreshExpiresIn", d.optLong("refresh_expires_in", 0L));
        long now = System.currentTimeMillis();
        long expiresAtSec = expiresIn > 0 ? now / 1000 + expiresIn : 0;
        long refreshExpiresSec = refreshIn > 0 ? now / 1000 + refreshIn : 0;
        boolean ok = AccountStore.updateCredential(ctx, acc.optString("uid", acc.optString("id", "")),
                access, newRefresh, expiresAtSec, refreshExpiresSec, 0, "");
        Prefs.log(ctx, "刷新凭据：" + acc.optString("label", "") + (ok ? " 成功" : " 本机未找到该账号"));
        try {
            out.put("ok", ok).put("message", ok ? "凭据已刷新（旧 refresh_token 立即作废）"
                    : "本机未找到该账号");
        } catch (Exception ignored) { }
        return out;
    }

    // ================================================================== 可选：同步到电脑

    /** 只允许私有网段，避免把长期凭据发到公网。对齐 fewards LanSyncProtocol.isPrivateHost。 */
    private static String syncBase(String host, int port) {
        if (host == null || host.isEmpty()) return null;
        String h = host.trim().toLowerCase();
        if (h.equals("localhost")) h = "127.0.0.1";
        String[] p = h.split("\\.");
        boolean priv = false;
        if (p.length == 4) {
            try {
                int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
                priv = a == 127 || a == 10 || (a == 192 && b == 168)
                        || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254);
            } catch (Exception ignored) { }
        }
        if (!priv) return null;
        return "http://" + h + ":" + (port > 0 ? port : 57900);
    }

    private static Map<String, String> syncHeaders(String code) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + (code == null ? "" : code));
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        return h;
    }

    public static JSONObject syncPing(String host, int port, String code) {
        String base = syncBase(host, port);
        if (base == null) return fail("只能填局域网地址（192.168.x.x / 10.x.x.x / 172.16-31.x.x）");
        Http.Resp r = call("GET", base + "/api/lansync/ping", null, null);
        JSONObject out = new JSONObject();
        try {
            if (r.error != null) {
                out.put("ok", false).put("error", "连不上电脑：" + r.error
                        + "（确认控制台已开启局域网同步、手机与电脑在同一 Wi-Fi）");
            } else {
                JSONObject j = obj(r.body);
                out.put("ok", r.status >= 200 && r.status < 300)
                        .put("paired", j.optBoolean("paired", false))
                        .put("message", r.status >= 200 && r.status < 300
                                ? "电脑端在线" + (j.optBoolean("paired", false) ? "（已设置配对码）" : "（未设置配对码）")
                                : "电脑端返回 HTTP " + r.status);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 推送选中账号到电脑；一次往返完成双向对齐。 */
    public static JSONObject syncPush(Context ctx, String host, int port, String code, JSONArray ids) {
        String base = syncBase(host, port);
        if (base == null) return fail("只能填局域网地址（192.168.x.x / 10.x.x.x / 172.16-31.x.x）");
        JSONArray all = AccountStore.load(ctx);
        JSONArray accounts = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject a = all.optJSONObject(i);
            if (a == null) continue;
            if (ids != null && ids.length() > 0 && !contains(ids, a.optString("id", ""))) continue;
            JSONObject x = new JSONObject();
            try {
                x.put("uid", a.optString("uid", ""))
                        .put("label", a.optString("label", ""))
                        .put("enterpriseId", a.optString("enterpriseId", ""))
                        .put("accessToken", a.optString("token", ""))
                        .put("refreshToken", a.optString("refreshToken", ""))
                        .put("expiresAt", a.optLong("expiresAt", 0L))
                        .put("refreshExpiresAt", a.optLong("refreshExpiresAt", 0L))
                        .put("obtainedAt", a.optLong("obtainedAt", 0L))
                        .put("version", 0)
                        .put("variant", WbVariant.normalize(a.optString("variant", "cn")));
                accounts.put(x);
            } catch (Exception ignored) { }
        }
        if (accounts.length() == 0) return fail("没有可推送的账号");
        JSONObject body = new JSONObject();
        try {
            body.put("version", 1)
                    .put("device", new JSONObject().put("name", android.os.Build.MODEL).put("platform", "android"))
                    .put("mode", "merge")
                    .put("accounts", accounts);
        } catch (Exception ignored) { }

        Http.Resp r = callLong("POST", base + "/api/lansync/push", syncHeaders(code), body.toString());
        JSONObject out = new JSONObject();
        try {
            if (r.error != null) { out.put("ok", false).put("error", "同步失败：" + r.error); return out; }
            if (r.status < 200 || r.status >= 300) {
                out.put("ok", false).put("error", syncError(r.body, r.status)); return out;
            }
            JSONObject j = obj(r.body);
            JSONArray results = j.optJSONArray("results");
            out.put("ok", true).put("results", results == null ? new JSONArray() : results)
                    .put("message", "已推送 " + accounts.length() + " 个账号，电脑端完成合并");
            Prefs.log(ctx, "同步到电脑：推送 " + accounts.length() + " 个账号");
        } catch (Exception ignored) { }
        return out;
    }

    /** 从电脑只读拉回凭据：本机失效的补 token，本机没有的直接建号，仍有效的不动。 */
    public static JSONObject syncFetch(Context ctx, String host, int port, String code) {
        String base = syncBase(host, port);
        if (base == null) return fail("只能填局域网地址（192.168.x.x / 10.x.x.x / 172.16-31.x.x）");
        JSONObject body = new JSONObject();
        try { body.put("version", 1).put("uids", new JSONArray()); } catch (Exception ignored) { }
        Http.Resp r = callLong("POST", base + "/api/lansync/fetch", syncHeaders(code), body.toString());
        JSONObject out = new JSONObject();
        int added = 0, updated = 0, skipped = 0;
        try {
            if (r.error != null) { out.put("ok", false).put("error", "同步失败：" + r.error); return out; }
            if (r.status == 404) {
                out.put("ok", false).put("error", "电脑端控制台版本过旧（不支持 /api/lansync/fetch），请升级后再试");
                return out;
            }
            if (r.status < 200 || r.status >= 300) {
                out.put("ok", false).put("error", syncError(r.body, r.status)); return out;
            }
            JSONArray remotes = obj(r.body).optJSONArray("accounts");
            if (remotes == null) remotes = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < remotes.length(); i++) {
                JSONObject rm = remotes.optJSONObject(i);
                if (rm == null) continue;
                String token = rm.optString("token", "").trim();
                String uid = rm.optString("uid", "");
                if (token.isEmpty() || rm.optBoolean("encrypted", false) || uid.isEmpty()) { skipped++; continue; }
                JSONObject local = findByUid(ctx, uid);
                if (local == null) {
                    String variant = WbVariant.normalize(rm.optString("variant", "cn"));
                    JSONObject a = AccountStore.make(uid, rm.optString("label", ""), token, variant);
                    try {
                        a.put("enterpriseId", rm.optString("enterpriseId", ""))
                                .put("refreshToken", rm.optString("refreshToken", ""))
                                .put("expiresAt", rm.optLong("expiresAt", 0L))
                                .put("refreshExpiresAt", rm.optLong("refreshExpiresAt", 0L))
                                .put("syncVersion", rm.optLong("version", 0L));
                    } catch (Exception ignored) { }
                    AccountStore.put(ctx, a);
                    added++;
                } else if (!usable(local.optString("token", ""), local.optLong("expiresAt", 0L), now)) {
                    // 已有账号只写 token 字段；档位只做 cn → ai 单向纠正
                    String variant = "";
                    if ("cn".equals(WbVariant.normalize(local.optString("variant", "cn")))
                            && "ai".equals(WbVariant.normalize(rm.optString("variant", "cn")))) variant = "ai";
                    boolean ok = AccountStore.updateCredential(ctx, uid, token,
                            rm.optString("refreshToken", ""), rm.optLong("expiresAt", 0L),
                            rm.optLong("refreshExpiresAt", 0L), rm.optLong("version", 0L), variant);
                    if (ok) updated++; else skipped++;
                } else {
                    skipped++;   // 本机仍有效，一律不动
                }
            }
            out.put("ok", true).put("added", added).put("updated", updated).put("skipped", skipped)
                    .put("message", "从电脑同步：新建 " + added + "，补凭据 " + updated + "，跳过 " + skipped);
            Prefs.log(ctx, out.optString("message"));
        } catch (Exception e) {
            try { out.put("ok", false).put("error", "解析失败：" + e.getMessage()); } catch (Exception ignored) { }
        }
        return out;
    }

    private static boolean usable(String token, long expiresAtSec, long nowMs) {
        if (token == null || token.isEmpty()) return false;
        return expiresAtSec <= 0 || expiresAtSec * 1000 > nowMs;   // 0 = 未知，不算过期
    }

    private static JSONObject findByUid(Context ctx, String uid) {
        JSONArray arr = AccountStore.load(ctx);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a != null && (uid.equals(a.optString("uid", "")) || uid.equals(a.optString("id", "")))) return a;
        }
        return null;
    }

    private static boolean contains(JSONArray arr, String v) {
        for (int i = 0; i < arr.length(); i++) if (v.equals(arr.optString(i, ""))) return true;
        return false;
    }

    private static String syncError(String body, int status) {
        String e = obj(body).optString("error", "");
        if (!e.isEmpty()) return e;
        switch (status) {
            case 401: return "配对码不正确";
            case 404: return "电脑端不支持该操作（版本过旧）";
            case 429: return "请求过于频繁，请稍后再试";
            case 413: return "账号太多，请求体超出上限";
            default: return "同步失败（HTTP " + status + "）";
        }
    }
}
