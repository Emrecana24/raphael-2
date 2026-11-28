package com.raphael.app.codespaces;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
public class CsClient {
    public interface HealthCb { void done(boolean ok, String cwd, String cs, int undo, String msg); }
    public interface UndoCb { void done(boolean ok, String msg, String cwd, int undo); }
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
        if (u.isEmpty() || tk.isEmpty()) { cb.done(false, "", "", 0, "URL veya Token bos"); return; }
        new Thread(() -> {
            try { JSONObject r = req(u, tk, "GET", "/health", null, 12000); int c = r.optInt("_http"); boolean ok = c == 200 && r.optBoolean("ok");
                h.post(() -> cb.done(ok, r.optString("cwd", ""), r.optString("cs", ""), r.optInt("undo", 0), ok ? "Bagli" : errMsg(c, r, null)));
            } catch (Exception e) { h.post(() -> cb.done(false, "", "", 0, errMsg(0, null, e))); }
        }).start();
    }
    public static void fsUndo(final CsConfig cfg, final UndoCb cb) {
        final Handler h = new Handler(Looper.getMainLooper()); final String u = cfg.getUrl(), tk = cfg.getToken();
        new Thread(() -> {
            try { JSONObject r = req(u, tk, "POST", "/fs/undo", new JSONObject(), 15000); int c = r.optInt("_http"); boolean ok = c == 200 && r.optBoolean("ok");
                String msg = c == 200 ? r.optString("summary", "Tamam") : errMsg(c, r, null);
                h.post(() -> cb.done(ok, msg, r.optString("cwd", ""), r.optInt("undo", 0)));
            } catch (Exception e) { h.post(() -> cb.done(false, errMsg(0, null, e), "", -1)); }
        }).start();
    }
    private static String safeRel(String p) {
        if (p == null) return "";
        String s = p.trim().replace((char) 92, (char) 47);
        StringBuilder out = new StringBuilder();
        for (String seg : s.split("/")) { if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) continue; if (out.length() > 0) out.append("/"); out.append(seg); }
        return out.toString();
    }
    private static File resolveLocalDest(File root, String rel, String sub, String custom, boolean multi) {
        if (custom == null || custom.trim().isEmpty()) { String sr = safeRel(rel); return new File(root, sr.isEmpty() ? safeRel(sub) : sr); }
        String cd = safeRel(custom); if (cd.isEmpty()) { String sr = safeRel(rel); return new File(root, sr.isEmpty() ? safeRel(sub) : sr); }
        String sb = safeRel(sub); if (sb.isEmpty()) sb = "dosya";
        if (multi) return new File(root, cd + "/" + sb);
        String last = cd.contains("/") ? cd.substring(cd.lastIndexOf("/") + 1) : cd;
        boolean asDir = custom.trim().endsWith("/") || new File(root, cd).isDirectory() || (!last.contains(".") && sb.contains("."));
        return asDir ? new File(root, cd + "/" + sb) : new File(root, cd);
    }
    public static class Runner {
        private final File localRoot; private final String u, tk; private final int to; private final Handler h = new Handler(Looper.getMainLooper());
        private volatile boolean stop = false; private volatile String jid = null; private ExecCb cbRef;
        public Runner(File localRoot, CsConfig cfg) { this.localRoot = localRoot; u = cfg.getUrl(); tk = cfg.getToken(); to = cfg.getTimeout(); }
        public void cancel() { stop = true; final String id = jid; if (id != null) new Thread(() -> { try { req(u, tk, "POST", "/kill/" + id, new JSONObject(), 6000); } catch (Exception ignored) {} }).start(); }
        private String pullFile(String rpath, File dst) throws Exception {
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            File tmp = new File(dst.getPath() + ".tmp");
            HttpURLConnection c = (HttpURLConnection) new URL(u + "/fs/pull_file").openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(45000);
            c.setRequestMethod("POST"); c.setRequestProperty("Authorization", "Bearer " + tk); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(new JSONObject().put("path", rpath).toString().getBytes("UTF-8")); }
            int code = c.getResponseCode();
            if (code != 200) { InputStream es = c.getErrorStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream(); if (es != null) { byte[] bf = new byte[4096]; int n; while ((n = es.read(bf)) > 0) bo.write(bf, 0, n); es.close(); } c.disconnect(); String s = bo.toString("UTF-8").trim(); JSONObject jr = s.startsWith("{") ? new JSONObject(s) : null; return errMsg(code, jr, null); }
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768]; int n; long tot = 0;
                while ((n = in.read(buf)) > 0) { if (stop) { tmp.delete(); c.disconnect(); return "Durduruldu"; } tot += n; if (tot > 30L * 1048576) { tmp.delete(); c.disconnect(); return "30 MB siniri asildi"; } fo.write(buf, 0, n); }
            } finally { c.disconnect(); }
            if (dst.exists()) dst.delete(); if (!tmp.renameTo(dst)) { tmp.delete(); return "Dosya yazilamadi"; }
            return "";
        }
        private CsProtocol.ExecResult runPull(CsProtocol.Action a) {
            long t0 = System.currentTimeMillis();
            try {
                boolean az = a.op == CsProtocol.Op.PULL && a.arg2.toLowerCase(Locale.US).endsWith(".zip");
                JSONObject pl = req(u, tk, "POST", "/fs/pull_plan", new JSONObject().put("path", a.arg1).put("as_zip", az), 30000);
                int c = pl.optInt("_http"); if (c != 200) return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", errMsg(c, pl, null));
                if (!pl.optBoolean("ok")) return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", pl.optString("err", "Hata"));
                JSONArray arr = pl.optJSONArray("files"); if (arr == null || arr.length() == 0) return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", "Dosya bulunamadi");
                if (a.op == CsProtocol.Op.ATTACH && (pl.optBoolean("zipped") || arr.length() > 8)) return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", "Klasorde 8+ dosya var; dosya sec veya @zip + @attach kullan");
                List<File> fs = new ArrayList<>(); StringBuilder sb = new StringBuilder(); boolean multi = arr.length() > 1;
                for (int i = 0; i < arr.length(); i++) {
                    if (stop) return new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", "Durduruldu");
                    JSONObject fo = arr.getJSONObject(i); String rp = fo.optString("remote"), rel = fo.optString("rel"), sub = fo.optString("sub");
                    if (a.op == CsProtocol.Op.ATTACH && fo.optLong("size", 0) <= 0) return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", "0 B dosya yuklenemez: " + rel);
                    File dst = resolveLocalDest(localRoot, rel, sub, a.arg2, multi);
                    final String lbl = a.label() + " (" + (i + 1) + "/" + arr.length() + ")"; h.post(() -> { if (cbRef != null) cbRef.onProgress(0, lbl); });
                    String err = pullFile(rp, dst); if (!err.isEmpty()) return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", err);
                    fs.add(dst); sb.append("- ").append(dst.getPath()).append(" (").append(Math.max(1, dst.length() / 1024)).append(" KB)\n");
                }
                double el = (System.currentTimeMillis() - t0) / 1000.0;
                String sm = (a.op == CsProtocol.Op.ATTACH ? "Yuklenecek (" : "Indirildi (") + fs.size() + " dosya)";
                return new CsProtocol.ExecResult(a, true, 0, false, false, el, "", (a.op == CsProtocol.Op.ATTACH ? "CEKILDI VE EKLENECEK:\n" : "INDIRILDI:\n") + sb.toString().trim(), "", sm, fs, -1);
            } catch (Exception e) { return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", errMsg(0, null, e)); }
        }
        private CsProtocol.ExecResult runFs(CsProtocol.Action a) {
            long t0 = System.currentTimeMillis(); String ep = ""; JSONObject b = new JSONObject();
            try {
                switch (a.op) {
                    case TREE: ep = "/fs/tree"; b.put("path", a.arg1); break;
                    case LIST: ep = "/fs/list"; b.put("path", a.arg1); break;
                    case READ: ep = "/fs/read"; b.put("path", a.arg1).put("start", a.lineStart).put("end", a.lineEnd); break;
                    case SEARCH: ep = "/fs/search"; b.put("query", a.arg1).put("path", a.arg2); break;
                    case MKDIR: ep = "/fs/mkdir"; b.put("path", a.arg1); break;
                    case WRITE: ep = "/fs/write"; b.put("path", a.arg1).put("content", a.text1); break;
                    case PATCH: ep = "/fs/patch"; b.put("path", a.arg1).put("old", a.text1).put("new", a.text2); break;
                    case MOVE: ep = "/fs/move"; b.put("src", a.arg1).put("dst", a.arg2); break;
                    case DELETE: ep = "/fs/delete"; b.put("path", a.arg1); break;
                    case ZIP: ep = "/fs/zip"; b.put("args", new JSONArray(a.args.isEmpty() && !a.arg1.isEmpty() ? Collections.singletonList(a.arg1) : a.args)); break;
                    case UNDO: ep = "/fs/undo"; break;
                    default: return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", "Bilinmeyen op");
                }
                JSONObject r = req(u, tk, "POST", ep, b, 45000); int c = r.optInt("_http"); double el = (System.currentTimeMillis() - t0) / 1000.0;
                if (c != 200) return new CsProtocol.ExecResult(a, false, 1, false, false, el, "", "", errMsg(c, r, null));
                boolean ok = r.optBoolean("ok"); String sm = r.optString("summary", a.label()); String out = r.optString("output", "");
                return new CsProtocol.ExecResult(a, ok, ok ? 0 : 1, false, false, el, r.optString("cwd", ""), out, "", sm, null, r.optInt("undo", -1));
            } catch (Exception e) { return new CsProtocol.ExecResult(a, false, 1, false, false, 0, "", "", errMsg(0, null, e)); }
        }
        public void run(final List<CsProtocol.Action> list, final ExecCb cb) {
            this.cbRef = cb;
            new Thread(() -> {
                List<CsProtocol.ExecResult> res = new ArrayList<>();
                for (CsProtocol.Action a : list) {
                    if (stop) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", "Durduruldu")); break; }
                    h.post(() -> cb.onProgress(0, a.label()));
                    if (a.op == CsProtocol.Op.CD) {
                        try { JSONObject r = req(u, tk, "POST", "/cd", new JSONObject().put("path", a.cmd), 10000); int c = r.optInt("_http"); boolean ok = c == 200 && r.optBoolean("ok"); res.add(new CsProtocol.ExecResult(a, ok, ok ? 0 : 1, false, false, 0.1, r.optString("cwd", ""), ok ? "Dizin: " + r.optString("cwd", "") : "", ok ? "" : errMsg(c, r, null), "", null, r.optInt("undo", -1))); } catch (Exception e) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", errMsg(0, null, e))); }
                        continue;
                    }
                    if (a.op == CsProtocol.Op.PULL || a.op == CsProtocol.Op.ATTACH) { res.add(runPull(a)); continue; }
                    if (a.op != CsProtocol.Op.SH) { res.add(runFs(a)); continue; }
                    try {
                        JSONObject st = req(u, tk, "POST", "/exec", new JSONObject().put("cmd", a.cmd).put("timeout", to), 12000); int sc = st.optInt("_http");
                        if (sc != 200 || !st.optBoolean("ok")) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", errMsg(sc, st, null))); continue; }
                        String id = st.optString("job_id", ""); jid = id; long t0 = System.currentTimeMillis(); int errs = 0, lastS = -1;
                        while (true) {
                            if (stop) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", "Durduruldu")); break; }
                            Thread.sleep(900); if (stop) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", "Durduruldu")); break; }
                            int sec = (int) ((System.currentTimeMillis() - t0) / 1000); if (sec != lastS) { lastS = sec; final int fs = sec; h.post(() -> cb.onProgress(fs, a.label())); }
                            if (sec > to + 30) { res.add(new CsProtocol.ExecResult(a, false, -1, true, false, sec, "", "", "Yerel zaman asimi")); break; }
                            try { JSONObject jr = req(u, tk, "GET", "/job/" + id, null, 12000); int jc = jr.optInt("_http");
                                if (jc != 200 || !jr.optBoolean("ok")) { if (++errs >= 6) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, sec, "", "", errMsg(jc, jr, null))); break; } Thread.sleep(1000); continue; }
                                errs = 0; if (jr.optBoolean("done")) { int ec = jr.optInt("exit_code", -1); boolean timedOut = jr.optBoolean("timed_out"); res.add(new CsProtocol.ExecResult(a, ec == 0 && !timedOut, ec, timedOut, jr.optBoolean("truncated"), jr.optDouble("elapsed", sec), jr.optString("cwd", ""), jr.optString("output", ""), "")); break; }
                            } catch (Exception pe) { if (++errs >= 8) { res.add(new CsProtocol.ExecResult(a, false, -1, false, false, sec, "", "", errMsg(0, null, pe))); break; } Thread.sleep(1500); }
                        } jid = null;
                    } catch (Exception e) { jid = null; res.add(new CsProtocol.ExecResult(a, false, -1, false, false, 0, "", "", errMsg(0, null, e))); }
                }
                h.post(() -> cb.onDone(res));
            }).start();
        }
    }
}
