package com.buddyswitch.agent;

import android.content.Context;

import org.json.JSONObject;

/**
 * 按账号所属平台分派签到 / 领积分 / 查额度 / 刷新凭据。
 *
 * <p>{@link JsBridge} 与 {@link AutoCheckinReceiver} 都只调这里，避免两处各写一套
 * 平台判断导致行为不一致。历史账号没有 platform 字段，{@link Platforms#of} 归为 WorkBuddy，
 * 因此 v2.0 的用户升级后原有账号行为完全不变。
 */
public final class PlatformRouter {
    private PlatformRouter() { }

    public static JSONObject checkinOne(Context ctx, JSONObject acc) {
        String p = Platforms.of(acc);
        String v = acc == null ? "cn" : acc.optString("variant", "cn");
        if (!Platforms.supportsCheckin(p, v)) {
            return skipped("该平台 / 档位没有签到接口（WorkBuddy 国际版上游未提供）");
        }
        if (Platforms.QODER.equals(p)) return QoderApi.checkinOne(ctx, acc);
        if (Platforms.TRAE.equals(p)) return TraeApi.checkinOne(ctx, acc);
        return WorkBuddyApi.checkinOne(ctx, acc);
    }

    public static JSONObject growthOne(Context ctx, JSONObject acc) {
        String p = Platforms.of(acc);
        String v = acc == null ? "cn" : acc.optString("variant", "cn");
        if (!Platforms.supportsGrowth(p, v)) {
            return skipped("该平台没有成长中心；Qoder / Trae 的签到即领积分");
        }
        return WorkBuddyApi.growthOne(ctx, acc);
    }

    public static JSONObject creditsOne(Context ctx, JSONObject acc) {
        String p = Platforms.of(acc);
        if (Platforms.QODER.equals(p)) return QoderApi.creditsOne(ctx, acc);
        if (Platforms.TRAE.equals(p)) return TraeApi.creditsOne(ctx, acc);
        return WorkBuddyApi.creditsOne(ctx, acc);
    }

    public static JSONObject refreshOne(Context ctx, JSONObject acc) {
        String p = Platforms.of(acc);
        if (Platforms.QODER.equals(p)) return QoderApi.refreshOne(acc);
        if (Platforms.TRAE.equals(p)) return TraeApi.refreshOne(acc);
        return WorkBuddyApi.refreshOne(ctx, acc);
    }

    private static JSONObject skipped(String m) {
        JSONObject o = new JSONObject();
        try { o.put("ok", true).put("skipped", true).put("message", m); } catch (Exception ignored) { }
        return o;
    }
}
