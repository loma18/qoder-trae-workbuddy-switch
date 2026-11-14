package com.buddyswitch.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 三平台 × 两区域的能力矩阵。
 *
 * <p>v2.1 起账号库支持 WorkBuddy / Qoder / Trae。历史账号没有 platform 字段，
 * 一律按 "wb" 处理（零迁移、零破坏）。
 *
 * <p>能力边界来自 qoder-trae-workbuddy-switch dev 分支的 Rust core 实证：
 * <ul>
 *   <li><b>Qoder</b>：设备授权是纯 HTTP（PKCE + /api/v1/deviceToken/poll），
 *       machine_id 缺失时自生成 uuid 并持久化，手机可独立登录。</li>
 *   <li><b>Trae</b>：授权 URL 需要桌面客户端 storage.json 里的 privateKeyPEM，
 *       且授权页会探测 127.0.0.1:17388 —— 手机无法独立 OAuth 登录；
 *       但签到 / 积分接口只校验 JWT + 自造设备头，故支持粘贴 JWT 后独立签到。</li>
 * </ul>
 */
public final class Platforms {
    public static final String WB = "wb";
    public static final String QODER = "qoder";
    public static final String TRAE = "trae";

    private Platforms() { }

    /** 归一化平台名；历史数据 / 空值一律按 WorkBuddy。 */
    public static String normalize(String raw) {
        if (raw == null) return WB;
        String s = raw.trim().toLowerCase();
        if (s.startsWith("qoder")) return QODER;
        if (s.startsWith("trae")) return TRAE;
        return WB;
    }

    public static String of(JSONObject acc) {
        return acc == null ? WB : normalize(acc.optString("platform", WB));
    }

    public static String label(String platform) {
        if (QODER.equals(platform)) return "Qoder";
        if (TRAE.equals(platform)) return "Trae";
        return "WorkBuddy";
    }

    public static String variantLabel(String variant) {
        return "ai".equals(variant) ? "国际版" : "国内版";
    }

    public static String scopeLabel(String platform, String variant) {
        return label(platform) + " · " + variantLabel(variant);
    }

    /** Trae 不能独立扫码登录（上游设备身份 + 本地回环限制）。 */
    public static boolean supportsQrLogin(String platform) {
        return !TRAE.equals(platform);
    }

    public static boolean supportsCheckin(String platform, String variant) {
        if (WB.equals(platform)) return !"ai".equals(variant);  // WorkBuddy 国际版无签到接口
        return QODER.equals(platform) || TRAE.equals(platform);
    }

    public static boolean supportsGrowth(String platform, String variant) {
        return WB.equals(platform) && !"ai".equals(variant);
    }

    public static String manualHint(String platform) {
        if (TRAE.equals(platform)) return "粘贴 Trae JWT（Cloud-IDE-JWT）";
        if (QODER.equals(platform)) return "粘贴 Qoder access token / PAT";
        return "粘贴 WorkBuddy 凭据 JSON";
    }

    /** 供 SPA 渲染平台选择器与能力提示。 */
    public static JSONObject capabilities() {
        JSONObject o = new JSONObject();
        try {
            JSONArray list = new JSONArray();
            String[] ps = {WB, QODER, TRAE};
            String[] vs = {"cn", "ai"};
            for (int i = 0; i < ps.length; i++) {
                for (int j = 0; j < vs.length; j++) {
                    JSONObject x = new JSONObject();
                    x.put("platform", ps[i]).put("variant", vs[j])
                            .put("label", label(ps[i]))
                            .put("variantLabel", variantLabel(vs[j]))
                            .put("qrLogin", supportsQrLogin(ps[i]))
                            .put("checkin", supportsCheckin(ps[i], vs[j]))
                            .put("growth", supportsGrowth(ps[i], vs[j]))
                            .put("credits", true)
                            .put("manualHint", manualHint(ps[i]));
                    list.put(x);
                }
            }
            o.put("ok", true).put("items", list);
        } catch (Exception ignored) { }
        return o;
    }
}
