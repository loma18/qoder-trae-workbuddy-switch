package com.buddyswitch.agent;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Buddy Switch 服务端 HTTP 客户端。
 *
 * <p>契约来源：桌面端 webui 的 {@code src/lib/api.ts}（Tauri command → /api/* 路由表），
 * 与 {@code crates/buddy-switch-server/src/api.rs} 的 axum 路由一一对应。
 *
 * <p>服务端 {@code api_guard.rs} 的四条来源校验：
 * <ol>
 *   <li>Host 必须是回环或在 {@code BUDDY_SWITCH_ALLOWED_HOSTS} 白名单里；</li>
 *   <li>若带 Origin，必须与 Host 同源；</li>
 *   <li>不能带 {@code Sec-Fetch-Site: cross-site}；</li>
 *   <li>非 GET/HEAD/OPTIONS 的请求必须带 {@code Content-Type: application/json}。</li>
 * </ol>
 * 因此这里刻意<b>不设置</b> Origin / Sec-Fetch-Site，所有 POST 都发送 JSON（空参数发 {@code {}}）。
 */
public final class ApiClient {
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 25000;

    public static final class Result {
        public boolean ok;
        public int status;
        public String body = "";
        public String error = "";
    }

    private final Context ctx;

    public ApiClient(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** 同步发起一次 API 调用。path 形如 {@code /api/accounts/list?scope=wb-cn}。 */
    public Result request(String method, String path, String jsonBody) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            String base = Prefs.baseUrl(ctx);
            if (!path.startsWith("/")) path = "/" + path;
            URL url = new URL(base + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET".equalsIgnoreCase(method) ? "GET" : "POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json");
            // 不设置 Origin / Sec-Fetch-Site —— 见类注释。
            String tok = Prefs.token(ctx);
            if (tok != null && !tok.isEmpty()) conn.setRequestProperty("X-Buddy-Switch-Token", tok);

            if (!"GET".equalsIgnoreCase(method)) {
                byte[] payload = (jsonBody == null || jsonBody.trim().isEmpty() ? "{}" : jsonBody)
                        .getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = conn.getOutputStream();
                os.write(payload);
                os.close();
            }

            r.status = conn.getResponseMessage() == null ? -1 : conn.getResponseCode();
            InputStream in = r.status >= 200 && r.status < 400
                    ? conn.getInputStream() : conn.getErrorStream();
            r.body = in == null ? "" : readAll(in);
            r.ok = r.status >= 200 && r.status < 300;
            if (!r.ok) {
                r.error = friendly(r.status, r.body);
            }
        } catch (java.net.SocketTimeoutException e) {
            r.error = "请求超时，请检查服务是否运行、手机与电脑是否在同一网络";
        } catch (java.net.ConnectException e) {
            r.error = "无法连接 " + Prefs.baseUrl(ctx) + "，请检查地址/端口与防火墙";
        } catch (java.net.UnknownHostException e) {
            r.error = "找不到主机 " + Prefs.host(ctx);
        } catch (Exception e) {
            r.error = "请求失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private static String friendly(int status, String body) {
        String b = body == null ? "" : body;
        if (b.contains("host_not_allowed")) {
            return "服务端拒绝了来源 Host（403）。请在电脑上设置环境变量 "
                    + "BUDDY_SWITCH_ALLOWED_HOSTS=" + "手机访问的主机:端口 后重启服务";
        }
        if (b.contains("not_json")) return "服务端要求 POST 带 application/json";
        if (b.contains("cross_origin")) return "跨站请求被服务端拒绝";
        if (status == 404) return "接口不存在（404），请确认服务端版本是否包含该路由";
        if (status == 403) return "服务端拒绝访问（403），请检查 Host 白名单设置";
        if (status == 401) return "未授权（401）";
        if (status >= 500) return "服务端错误（" + status + "）";
        String msg = b.length() > 220 ? b.substring(0, 220) : b;
        return "HTTP " + status + (msg.isEmpty() ? "" : "：" + msg);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 连接自检：走 /api/status（只读、无副作用）。 */
    public Result ping() { return request("GET", "/api/status", null); }
}
