package com.raphael.app.codespaces;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
public class CsClient {
    public interface HealthCb { void done(boolean ok, String cwd, String cs, String msg); }
    public interface ExecCb { void onProgress(int sec, String lbl); void onDone(List<CsProtocol.ExecResult> res); }
    private static JSONObject req(String u, String tk, String m, String p, JSONObject b, int ms) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u + p).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(ms);
        c.setRequestMethod(m); c.setRequestProperty("Authorization", "Bearer " + tk); c.setRequestProperty("Accept", "application/json");
        if (b != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); try (OutputStream o = c.getOutputStream()) { o.write(b.toString().getBytes("UTF-8")); } }
        int code = c.getResponseCode(); InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close(); }
        c.disconnect(); String s = bo.toString("UTF-8").trim();
        JSONObject o = s.startsWith("{") ? new JSONObject(s) : new JSONObject(); o.put("_http", code); return o;
    }
    private static String errMsg(int c, JSONObject r, Exception e) {
        if (e != null) return "Ag hatasi: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        if (c == 301 || c == 302) return "Port private gorunuyor (Codespaces: csctl.sh start calistir)";
        if (c == 401 || c == 403) return "Token hatali (" + c + ")";
        if (c == 404) return "Sunucu kapali veya URL yanlis (404)";
        return r != null && r.has("err") ? r.optString("err") : "HTTP " + c;
    }
    public static void testHealth(final CsConfig cfg, final HealthCb cb) {
        final Handler h = new Handler(Looper.getMainLooper()); final String u = cfg.getUrl(), tk = cfg.getToken();
        if (u.isEmpty() || tk.isEmpty()) { cb.done(false, "", "", "URL veya Token bos"); return; }
        new Thread(() -> {
            try {
                JSONObject r = req(u, tk, "GET", "/health", null, 12000); int c = r.optInt("_http"); boolean ok = c == 200 && r.optBoolean("ok");
                h.post(() -> cb.done(ok, r.optString("cwd", ""), r.optString("cs", ""), ok ? "Bagli" : errMsg(c, r, null)));
            } catch (Exception e) { h.post(() -> cb.done(false, "", "", errMsg(0, null, e))); }
        }).start();
    }
    public static class Runner {
        private final String u, tk; private final int to; private final Handler h = new Handler(Looper.getMainLooper());
        private volatile boolean stop = false; private volatile String jid = null;
        public Runner(CsConfig cfg) { u = cfg.getUrl(); tk = cfg.getToken(); to = cfg.getTimeout(); }
        public void cancel() { stop = true; final String id = jid; if (id != null) new Thread(() -> { try { req(u, tk, "POST", "/kill/" + id, new JSONObject(), 6000); } catch (Exception ignored) {} }).start(); }
        public void run(final List<CsProtocol.Action> list, final ExecCb cb) {
            new Thread(() -> {
                List<CsProtocol.ExecResult> res = new ArrayList<>();
                for (CsProtocol.Action a : list) {
                    if (stop) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", "Durduruldu")); break; }
                    if (a.op == CsProtocol.Op.CD) {
                        try { JSONObject r = req(u, tk, "POST", "/cd", new JSONObject().put("path", a.cmd), 10000); int c = r.optInt("_http"); boolean ok = c == 200 && r.optBoolean("ok"); res.add(new CsProtocol.ExecResult(a, ok, ok ? 0 : 1, false, false, 0.1, r.optString("cwd", ""), ok ? "Dizin: " + r.optString("cwd", "") : "", ok ? "" : errMsg(c, r, null))); } catch (Exception e) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", errMsg(0, null, e))); }
                        continue;
                    }
                    try {
                        JSONObject st = req(u, tk, "POST", "/exec", new JSONObject().put("cmd", a.cmd).put("timeout", to), 12000); int sc = st.optInt("_http");
                        if (sc != 200 || !st.optBoolean("ok")) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", errMsg(sc, st, null))); continue; }
                        String id = st.optString("job_id", ""); jid = id; long t0 = System.currentTimeMillis(); int errs = 0, lastS = -1;
                        while (true) {
                            if (stop) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", "Durduruldu")); break; }
                            Thread.sleep(900);
                            if (stop) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", "Durduruldu")); break; }
                            int sec = (int) ((System.currentTimeMillis() - t0) / 1000);
                            if (sec != lastS) { lastS = sec; final int fs = sec; h.post(() -> cb.onProgress(fs, a.label())); }
                            if (sec > to + 30) { res.add(new CsProtocol.ExecResult(a, false, -1, true, false, sec, "", "", "Yerel zaman asimi")); break; }
                            try {
                                JSONObject jr = req(u, tk, "GET", "/job/" + id, null, 12000); int jc = jr.optInt("_http");
                                if (jc != 200 || !jr.optBoolean("ok")) { if (++errs >= 6) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, sec, "", "", errMsg(jc, jr, null))); break; } Thread.sleep(1000); continue; }
                                errs = 0;
                                if (jr.optBoolean("done")) { int ec = jr.optInt("exit_code", -1); boolean timedOut = jr.optBoolean("timed_out"); res.add(new CsProtocol.ExecResult(a, ec == 0 && !timedOut, ec, timedOut, jr.optBoolean("truncated"), jr.optDouble("elapsed", sec), jr.optString("cwd", ""), jr.optString("output", ""), "")); break; }
                            } catch (Exception pe) { if (++errs >= 8) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, sec, "", "", errMsg(0, null, pe))); break; } Thread.sleep(1500); }
                        } jid = null;
                    } catch (Exception e) { jid = null; res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", errMsg(0, null, e))); }
                }
                h.post(() -> cb.onDone(res));
            }).start();
        }
    }
}
