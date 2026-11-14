package com.buddyswitch.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * 极简 HTTP(S) 引擎。手机直连腾讯官方 API 时，WebView 里的 JS 无法伪造
 * Origin / Referer / User-Agent / X-Requested-With 这类指纹头，也不能绕过 CORS，
 * 因此所有请求必须由 Java 层发出。这里只用 JDK + Android Framework，无第三方依赖。
 *
 * <p>刻意每次 Connection: close：官方网关与部分路由在 keep-alive 复用死连接时，
 * 会让客户端拿到 unexpected end of stream。
 */
public final class Http {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Resp {
        public int status;
        public String body = "";
        public String error;
        public boolean ok() { return error == null && status >= 200 && status < 300; }
    }

    private Http() { }

    public static Resp request(String method, String url, Map<String, String> headers,
                               String body, int connectMs, int readMs) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method == null ? "GET" : method);
            c.setConnectTimeout(connectMs <= 0 ? 12000 : connectMs);
            c.setReadTimeout(readMs <= 0 ? 20000 : readMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Connection", "close");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (body != null && ("POST".equals(method) || "PUT".equals(method))) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.getBytes(UTF8).length);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes(UTF8));
                os.flush();
                os.close();
            }
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = readAll(in);
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + safe(e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        } catch (Exception e) {
            return "";
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    private static String safe(String s) { return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s); }
}
