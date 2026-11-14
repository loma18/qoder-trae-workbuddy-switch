package com.buddyswitch.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 手机本地账号库。凭据只存在 App 私有 SharedPreferences，绝不上传第三方。
 * 字段与 fewards 的 AccountRepository.WorkBuddyAccount 一一对应，保证
 * 「可选同步到电脑」时载荷能被 workbuddy-console 直接吞下。
 *
 * <p>时间单位刻意不统一（照搬上游，写错不崩但会显示"刚同步就过期"）：
 * expiresAt / refreshExpiresAt 是秒，obtainedAt 是毫秒。
 */
public final class AccountStore {
    private static final String FILE = "secure_store";
    private static final String KEY = "sec.wb.accounts";

    private AccountStore() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static JSONArray load(Context c) {
        String raw = sp(c).getString(KEY, null);
        if (raw == null) return new JSONArray();
        try { return new JSONArray(raw); }
        catch (Exception e) { return new JSONArray(); }
    }

    public static void save(Context c, JSONArray arr) {
        sp(c).edit().putString(KEY, arr.toString()).apply();
    }

    /** 构造一条 WorkBuddy 账号记录（v2.0 及历史数据形态，保持不变）。 */
    public static JSONObject make(String uid, String label, String token, String variant) {
        return makeFor(Platforms.WB, uid, label, token, variant);
    }

    /**
     * 构造任意平台的账号记录。id 前缀按「平台 + 档位」隔离，避免同一 uid
     * 在 WorkBuddy / Qoder / Trae 之间或国内 / 国际档之间互相覆盖。
     */
    public static JSONObject makeFor(String platform, String uid, String label,
                                     String token, String variant) {
        JSONObject o = new JSONObject();
        try {
            String pf = Platforms.normalize(platform);
            String v = WbVariant.normalize(variant);
            String prefix = Platforms.WB.equals(pf)
                    ? ("ai".equals(v) ? "wb_ai_" : "wb_")
                    : (pf + "_" + v + "_");
            o.put("id", prefix + (uid == null || uid.isEmpty()
                    ? Long.toString(System.currentTimeMillis()) : uid));
            o.put("platform", pf);
            o.put("label", label == null ? "" : label);
            o.put("token", token == null ? "" : token);
            o.put("avatarUrl", "");
            o.put("uid", uid == null ? "" : uid);
            o.put("enterpriseId", "");
            o.put("remark", "");            // 用户自定义备注（账号名是无意义字符时用它辨认）
            o.put("refreshToken", "");
            o.put("expiresAt", 0L);
            o.put("obtainedAt", System.currentTimeMillis());
            o.put("refreshExpiresAt", 0L);
            o.put("syncVersion", 0L);
            o.put("variant", v);
        } catch (Exception ignored) { }
        return o;
    }

    /**
     * 写入 / 更新（多账号去重）。去重顺序对齐 fewards addWorkBuddyAccount：
     * 同 id → 同 uid+档位 → 老账号(uid 空)同 label+档位 → 否则新增。
     */
    public static JSONObject put(Context c, JSONObject acc) {
        JSONArray arr = load(c);
        String uid = acc.optString("uid", "");
        String id = acc.optString("id", "");
        String label = acc.optString("label", "");
        String variant = WbVariant.normalize(acc.optString("variant", "cn"));
        String accPlatform = Platforms.of(acc);
        int hit = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            String ev = WbVariant.normalize(e.optString("variant", "cn"));
            boolean sameVariant = ev.equals(variant)
                    && Platforms.of(e).equals(accPlatform);
            if (id.equals(e.optString("id", ""))
                    || (!uid.isEmpty() && uid.equals(e.optString("uid", "")) && sameVariant)
                    || (!uid.isEmpty() && e.optString("uid", "").isEmpty()
                        && !label.isEmpty() && label.equals(e.optString("label", "")) && sameVariant)) {
                hit = i; break;
            }
        }
        try { acc.put("variant", variant).put("platform", accPlatform); } catch (Exception ignored) { }
        if (hit >= 0) {
            // 保留已有头像 / 备注等非凭据字段（电脑端回传的是瘦数据）
            JSONObject old = arr.optJSONObject(hit);
            if (old != null) {
                copyIfEmpty(acc, old, "avatarUrl");
                copyIfEmpty(acc, old, "enterpriseId");
                // 备注是本机私有字段：电脑端回传 / 刷新 / 重新扫码的瘦数据里没有时，
                // 必须原样保留，不能被空串覆盖掉。
                if (!acc.has("remark") || acc.optString("remark", "").isEmpty()) {
                    try { acc.put("remark", old.optString("remark", "")); }
                    catch (Exception ignored) { }
                }
            }
            try { arr.put(hit, acc); } catch (Exception ignored) { }
        } else {
            arr.put(acc);
        }
        save(c, arr);
        return acc;
    }

    private static void copyIfEmpty(JSONObject dst, JSONObject src, String key) {
        try { if (dst.optString(key, "").isEmpty()) dst.put(key, src.optString(key, "")); }
        catch (Exception ignored) { }
    }

    public static JSONObject find(Context c, String id) {
        JSONArray arr = load(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null && id.equals(e.optString("id", ""))) return e;
        }
        return null;
    }

    public static void remove(Context c, String id) {
        JSONArray arr = load(c), out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e != null && !id.equals(e.optString("id", ""))) out.put(e);
        }
        save(c, out);
    }

    /**
     * 设置 / 清除本机账号备注。备注只改这一个字段，绝不动凭据；
     * 长度上限 60 字符，避免把 SharedPreferences 撑大或把 UI 撑破。
     */
    public static boolean setRemark(Context c, String id, String remark) {
        if (id == null || id.isEmpty()) return false;
        String value = remark == null ? "" : remark.trim();
        if (value.length() > 60) value = value.substring(0, 60);
        JSONArray arr = load(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null || !id.equals(e.optString("id", ""))) continue;
            try {
                e.put("remark", value);
                arr.put(i, e);
                save(c, arr);
                return true;
            } catch (Exception ignored) { return false; }
        }
        return false;
    }

    /** 局部更新凭据（刷新 / 同步回写）。空值不覆盖，避免把有效 token 清空。 */
    public static boolean updateCredential(Context c, String uid, String access, String refresh,
                                           long expiresAtSec, long refreshExpiresAtSec,
                                           long syncVersion, String variant) {
        if (uid == null || uid.isEmpty()) return false;
        JSONArray arr = load(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            if (!uid.equals(e.optString("uid", "")) && !uid.equals(e.optString("id", ""))) continue;
            try {
                if (access != null && !access.isEmpty()) e.put("token", access);
                if (refresh != null && !refresh.isEmpty()) e.put("refreshToken", refresh);
                if (expiresAtSec > 0) e.put("expiresAt", expiresAtSec);
                if (refreshExpiresAtSec > 0) e.put("refreshExpiresAt", refreshExpiresAtSec);
                if (syncVersion > 0) e.put("syncVersion", syncVersion);
                if (variant != null && !variant.isEmpty()) e.put("variant", WbVariant.normalize(variant));
                e.put("obtainedAt", System.currentTimeMillis());
                arr.put(i, e);
                save(c, arr);
                return true;
            } catch (Exception ignored) { return false; }
        }
        return false;
    }

    /** 给 UI 的脱敏列表：token 只留首尾各 6 位，避免整屏铺满上千字符。 */
    public static JSONArray publicList(Context c) {
        JSONArray arr = load(c), out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            JSONObject p = new JSONObject();
            try {
                String[] keys = {"id", "label", "avatarUrl", "uid", "enterpriseId",
                        "expiresAt", "obtainedAt", "refreshExpiresAt", "syncVersion",
                        "variant", "platform", "remark"};
                for (String k : keys) p.put(k, e.opt(k));
                if (!p.has("remark")) p.put("remark", "");
                p.put("tokenMask", mask(e.optString("token", "")));
                p.put("hasRefresh", !e.optString("refreshToken", "").isEmpty());
                p.put("variant", WbVariant.normalize(e.optString("variant", "cn")));
                p.put("variantLabel", WbVariant.of(p.optString("variant")).label);
                String pf = Platforms.of(e);
                p.put("platform", pf)
                        .put("platformLabel", Platforms.label(pf))
                        .put("scopeLabel", Platforms.scopeLabel(pf, p.optString("variant")));
                out.put(p);
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static String mask(String t) {
        if (t == null || t.isEmpty()) return "（无）";
        if (t.length() <= 14) return "••••••";
        return t.substring(0, 6) + "…" + t.substring(t.length() - 6);
    }

    /** 导出全量明文（用户主动触发，用于备份 / 手动同步，返回给 UI 复制或分享）。 */
    public static JSONArray exportAll(Context c) { return load(c); }

    /** 导入：接受 [{...}] 或单个 {...}，字段按白名单收纳。 */
    public static int importAll(Context c, String json) {
        JSONArray items = new JSONArray();
        try {
            String t = json.trim();
            if (t.startsWith("[")) items = new JSONArray(t);
            else if (t.startsWith("{")) items.put(new JSONObject(t));
            else return 0;
        } catch (Exception e) { return 0; }
        int n = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject src = items.optJSONObject(i);
            if (src == null) continue;
            String token = src.optString("token", src.optString("accessToken", ""));
            if (token.isEmpty()) continue;
            String uid = src.optString("uid", "");
            String variant = WbVariant.normalize(src.optString("variant", "cn"));
            JSONObject a = makeFor(src.optString("platform", Platforms.WB),
                    uid, src.optString("label", ""), token, variant);
            try {
                a.put("refreshToken", src.optString("refreshToken", ""));
                a.put("enterpriseId", src.optString("enterpriseId", ""));
                a.put("avatarUrl", src.optString("avatarUrl", ""));
                a.put("remark", src.optString("remark", ""));
                a.put("expiresAt", src.optLong("expiresAt", 0L));
                a.put("refreshExpiresAt", src.optLong("refreshExpiresAt", 0L));
            } catch (Exception ignored) { }
            // 没有 uid 时从 JWT 补
            if (a.optString("uid", "").isEmpty()) {
                JSONObject jwt = decodeJwt(token);
                if (!jwt.optString("sub", "").isEmpty()) {
                    try { a.put("uid", jwt.optString("sub")); } catch (Exception ignored) { }
                }
            }
            put(c, a);
            n++;
        }
        return n;
    }

    /**
     * 从 JWT 解 profile。对齐 fewards WorkBuddyLabel.decodeProfile：
     * nickname（去掉控制字符）优先，否则 preferred_username；sub → uid；exp → expiresAt。
     */
    public static JSONObject decodeJwt(String token) {
        JSONObject out = new JSONObject();
        if (token == null) return out;
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return out;
            String p = parts[1].replace('-', '+').replace('_', '/');
            while (p.length() % 4 != 0) p += "=";
            byte[] raw = Base64.decode(p, Base64.DEFAULT);
            JSONObject j = new JSONObject(new String(raw, "UTF-8"));
            String nick = j.optString("nickname", "").replaceAll("[\\p{Cntrl}]", "").trim();
            if (nick.isEmpty()) nick = j.optString("preferred_username", "").trim();
            out.put("nickname", nick);
            out.put("sub", j.optString("sub", ""));
            out.put("exp", j.optLong("exp", 0L));
            String pic = j.optString("picture", j.optString("avatar_url", ""));
            if (pic.startsWith("http")) out.put("avatarUrl", pic);
            out.put("enterpriseId", j.optString("enterpriseId", j.optString("enterprise_id", "")));
        } catch (Exception ignored) { }
        return out;
    }
}
