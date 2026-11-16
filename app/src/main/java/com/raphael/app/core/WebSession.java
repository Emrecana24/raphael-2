package com.raphael.app.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tek bir (görünmez/görünür) WebView'i yönetir: siteyi yükler, JS sürücüsünü enjekte eder,
 * mesaj gönderir, cevabı izler. Gemini ve AI Studio aynı WebView'i (aynı çerezler) kullanır.
 */
public class WebSession {
    public static final int ST_OFF = 0, ST_LOADING = 1, ST_LOGIN = 2, ST_READY = 3;

    public interface Listener {
        void onStatus(int status, String info);
        /** Cevap metni (Markdown). done=true: cevap tamamlandı. */
        void onReply(String markdown, boolean done);
        void onReplyError(String err);
        void onNote(String msg);
        void onImages(List<String> names);
        void onStage(String text);
        /** true dönerse sistem dosya seçicisini aktivite açar. */
        boolean onFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p);
    }

    public static class Upload {
        public String id, name, mime;
        public File file;
        public Upload(String id, String name, String mime, File file) { this.id = id; this.name = name; this.mime = mime; this.file = file; }
    }

    public interface ImgCb { void done(List<String> names); }
    public static final String AUTH = "com.raphael.app.files";

    public interface OpCb { void done(JSONObject res); }
    public interface AttachCb { void done(boolean ok, String tokens, String wname, int idx, String err); }

    private static final String UA_FIREFOX = "Mozilla/5.0 (Android 10; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0";

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout host;
    private WebView web;
    private Listener lis;
    private Site site = Site.GEMINI;

    private int status = ST_OFF;
    private String info = "";
    private boolean usable = false, fresh = false, prefsApplied = false, applying = false;
    private boolean destroyed = false;
    private int readyTries = 0, uaMode = 0;
    private String prefThinking = "", prefModel = "";
    private final Map<String, String> scriptCache = new HashMap<>();
    private final List<Runnable> queue = new ArrayList<>();
    private final Map<String, OpCb> ops = new HashMap<>();
    private int opSeq = 0;

    // cevap takibi
    private boolean tracking = false;
    private int trackM0 = 0, stable = 0;
    private long trackStart = 0;
    private String sig = "", lastText = "", sentText = "";
    private boolean noReplyReported = false;
    private int idleBad = 0;
    private String baseSig = "", dbg = "", curUa = "";
    private boolean sawGen = false;
    private int errCount = 0, imgSeq = 0;
    private List<String> lastImgs = new ArrayList<>();
    private final Map<String, Upload> uploads = new HashMap<>();
    private final List<Uri> pendingUris = new ArrayList<>();

    private final Runnable tickR = new Runnable() { @Override public void run() { tick(); } };

    public WebSession(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public void setListener(Listener l) { lis = l; }
    public Site site() { return site; }
    public int status() { return status; }
    public String info() { return info; }
    public boolean isUsable() { return usable; }
    public boolean isFresh() { return fresh; }
    public boolean isConnected() { return web != null; }
    public WebView webView() { return web; }
    public void setUaMode(int m) { uaMode = m; }
    public void markUsed() { fresh = false; }

    public void attach(FrameLayout host) { this.host = host; }

    public void setPrefs(String thinking, String model) { prefThinking = thinking == null ? "" : thinking; prefModel = model == null ? "" : model; }

    // ------------------------------------------------------------------ WebView
    @SuppressWarnings("SetJavaScriptEnabled")
    private void ensureWeb() {
        if (web != null || host == null) return;
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        WebView.setWebContentsDebuggingEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (!pendingUris.isEmpty()) {
                    Uri[] u = pendingUris.toArray(new Uri[0]);
                    pendingUris.clear();
                    cb.onReceiveValue(u);
                    return true;
                }
                if (lis != null && lis.onFileChooser(cb, p)) return true;
                cb.onReceiveValue(null);
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap fav) {
                usable = false; prefsApplied = false; applying = false;
                setStatus(ST_LOADING, "Yükleniyor…");
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (v != web) return;
                inject();
                readyTries = 0;
                h.removeCallbacks(tickR);
                h.postDelayed(tickR, 500);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                return sc != null && !sc.equals("http") && !sc.equals("https") && !sc.equals("about");
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) { setStatus(ST_OFF, "Bağlantı hatası"); }
            }
        });
        host.addView(web, new FrameLayout.LayoutParams(-1, -1));
    }

    private String chromeVer() {
        try {
            Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(WebSettings.getDefaultUserAgent(ctx));
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return "124.0.0.0";
    }

    private void applyUa() {
        String ua = uaMode == 1 ? UA_FIREFOX
                : "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromeVer() + " Mobile Safari/537.36";
        curUa = ua;
        web.getSettings().setUserAgentString(ua);
    }

    /** Siteyi (yeni sohbet sayfasını) yükler. */
    public void open(Site s, String url) {
        destroyed = false;
        site = s;
        ensureWeb();
        if (web == null) return;
        stopTracking();
        usable = false; fresh = true; prefsApplied = false;
        applyUa();
        setStatus(ST_LOADING, "Yükleniyor…");
        web.loadUrl(url == null ? s.newChatUrl : url);
    }

    public void newChat() { open(site, site.newChatUrl); }
    public void reload() { if (web != null) { usable = false; web.reload(); } else open(site, site.newChatUrl); }

    public void loginPage() {
        ensureWeb();
        if (web == null) return;
        applyUa();
        usable = false;
        String cont = android.net.Uri.encode(site.newChatUrl);
        web.loadUrl("https://accounts.google.com/ServiceLogin?continue=" + cont);
    }

    /** RAM'i boşaltmak için WebView'i tamamen yok eder. */
    public void disconnect() {
        stopTracking();
        h.removeCallbacks(tickR);
        usable = false; fresh = false;
        queue.clear();
        if (web != null) {
            try { web.loadUrl("about:blank"); web.stopLoading(); } catch (Throwable ignored) {}
            if (host != null) host.removeView(web);
            try { web.removeAllViews(); web.destroy(); } catch (Throwable ignored) {}
            web = null;
        }
        setStatus(ST_OFF, "Bağlantı kesildi");
    }

    public void destroy() { destroyed = true; disconnect(); }

    private void setStatus(int st, String in) {
        status = st; info = in;
        if (lis != null) lis.onStatus(st, in);
    }

    // ------------------------------------------------------------------ JS yardımcıları
    private String script(Site s) {
        String c = scriptCache.get(s.id);
        if (c != null) return c;
        String common = asset("js/common.js"), site = asset("js/" + s.id + ".js");
        c = "(function(){\n" + common + "\n" + site + "\n})();";
        scriptCache.put(s.id, c);
        return c;
    }

    private String asset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    private void inject() {
        if (web == null) return;
        String u = web.getUrl();
        if (u != null && u.contains(site.host)) web.evaluateJavascript(script(site), null);
    }

    private interface JCb { void got(JSONObject o); }

    private void agent(String call, final JCb cb) {
        if (web == null) { cb.got(null); return; }
        String code = "(function(){try{return window.__rph?JSON.stringify(window.__rph." + call + "):'{\"noagent\":1}';}catch(e){return JSON.stringify({err:String(e)});}})()";
        web.evaluateJavascript(code, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                JSONObject o = null;
                try {
                    if (v != null && !v.equals("null")) {
                        Object x = new JSONTokener(v).nextValue();
                        if (x instanceof String) o = new JSONObject((String) x);
                    }
                } catch (Exception ignored) {}
                cb.got(o);
            }
        });
    }

    public void op(String name, JSONObject args, final OpCb cb) { op(name, args, cb, 60000); }

    public void op(String name, JSONObject args, final OpCb cb, long timeoutMs) {
        if (web == null) { JSONObject r = new JSONObject(); try { r.put("ok", false).put("err", "bagli-degil"); } catch (Exception ignored) {} cb.done(r); return; }
        final String id = "o" + (++opSeq);
        ops.put(id, cb);
        String code = "(function(){try{if(!window.__rph)return 'noagent';window.__rph.run(" + JSONObject.quote(id) + "," + JSONObject.quote(name) + ","
                + (args == null ? "{}" : args.toString()) + ");return 'ok';}catch(e){return 'err:'+e;}})()";
        web.evaluateJavascript(code, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (v != null && (v.contains("noagent") || v.contains("err:"))) finishOp(id, "{\"ok\":false,\"err\":\"ajan-yok\"}");
            }
        });
        h.postDelayed(new Runnable() { @Override public void run() { finishOp(id, "{\"ok\":false,\"err\":\"zaman-asimi\"}"); } }, timeoutMs);
    }

    private void finishOp(String id, String json) {
        OpCb cb = ops.remove(id);
        if (cb == null) return;
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { o = new JSONObject(); }
        cb.done(o);
    }

    private class Bridge {
        @JavascriptInterface public void opResult(final String id, final String json) {
            h.post(new Runnable() { @Override public void run() { finishOp(id, json); } });
        }
        @JavascriptInterface public String fileInfo(String id) {
            Upload u = uploads.get(id);
            if (u == null) return "{\"name\":\"x\",\"mime\":\"\",\"size\":0}";
            try { return new JSONObject().put("name", u.name).put("mime", u.mime == null ? "" : u.mime).put("size", u.file.length()).toString(); }
            catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String readChunk(String id, int off, int len) {
            Upload u = uploads.get(id);
            if (u == null) return "";
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(u.file, "r")) {
                byte[] b = new byte[len];
                f.seek(off);
                f.readFully(b);
                return Base64.encodeToString(b, Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public String saveImage(String mime, String b64) {
            try { return writeImage(mime, Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public void saveDom(final String name, final String html) {
            new Thread(new Runnable() { @Override public void run() {
                String msg;
                try {
                    File dir = ctx.getExternalFilesDir("DOM");
                    if (dir == null) dir = new File(ctx.getFilesDir(), "DOM");
                    dir.mkdirs();
                    String safe = name.replaceAll("[^a-zA-Z0-9_-]", "");
                    File f = new File(dir, "dom_" + safe + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".html");
                    try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(html.getBytes("UTF-8")); }
                    msg = "DOM kaydedildi (" + (html.length() / 1024) + " KB):\n" + f.getAbsolutePath();
                } catch (Exception e) { msg = "DOM kaydedilemedi: " + e; }
                final String m = msg;
                h.post(new Runnable() { @Override public void run() { if (lis != null) lis.onNote(m); } });
            } }).start();
        }
    }

    private synchronized String writeImage(String mime, byte[] data) throws Exception {
        File dir = new File(ctx.getFilesDir(), "imgs");
        dir.mkdirs();
        String ext = mime != null && mime.contains("jpeg") ? "jpg" : mime != null && mime.contains("webp") ? "webp" : mime != null && mime.contains("gif") ? "gif" : "png";
        File f = new File(dir, "g_" + System.currentTimeMillis() + "_" + (++imgSeq) + "." + ext);
        try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(data); }
        return f.getAbsolutePath();
    }

    private String download(String u) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.flush();
            String cur = u;
            for (int hop = 0; hop < 8; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", curUa);
                c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                String ck = cm.getCookie(cur);
                if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                c.setRequestProperty("Referer", "https://" + site.host + "/");
                int code = c.getResponseCode();
                List<String> sc = c.getHeaderFields().get("Set-Cookie");
                if (sc != null) for (String v : sc) cm.setCookie(cur, v);
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null || loc.isEmpty()) return null;
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                if (code != 200) { c.disconnect(); return null; }
                String ct = c.getContentType();
                if (ct != null && !ct.toLowerCase(Locale.US).startsWith("image/") && !ct.toLowerCase(Locale.US).contains("octet-stream")) {
                    c.disconnect();
                    return null;
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[16384]; int n, tot = 0;
                    while ((n = in.read(b)) > 0) { tot += n; if (tot > 25 * 1024 * 1024) return null; bo.write(b, 0, n); }
                } finally { c.disconnect(); }
                return bo.size() > 0 ? writeImage(ct, bo.toByteArray()) : null;
            }
            return null;
        } catch (Exception e) { return null; }
    }

    private static List<String> toList(JSONArray a) {
        List<String> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return l;
    }

    /** Sayfadaki görselleri uygulama deposuna alır (önce sayfa içinden, olmazsa doğrudan indirerek). */
    private void grabImages(final List<String> urls, final ImgCb cb) {
        JSONObject a = new JSONObject();
        try { a.put("urls", new JSONArray(urls)); } catch (Exception ignored) {}
        op("grabImages", a, new OpCb() { @Override public void done(JSONObject r) {
            final List<String> names = new ArrayList<>();
            final List<String> failed = new ArrayList<>();
            if (r.optBoolean("ok")) {
                JSONArray f = r.optJSONArray("files");
                if (f != null) for (int i = 0; i < f.length(); i++) names.add(new File(f.optString(i)).getName());
                failed.addAll(toList(r.optJSONArray("failed")));
            } else failed.addAll(urls);
            if (failed.isEmpty()) { cb.done(names); return; }
            new Thread(new Runnable() { @Override public void run() {
                for (String u : failed) {
                    if (!u.startsWith("http")) continue;
                    String p = download(u);
                    if (p != null) names.add(new File(p).getName());
                }
                h.post(new Runnable() { @Override public void run() { cb.done(names); } });
            } }).start();
        } }, 120000);
    }

    public void saveDom() {
        try { op("dom", new JSONObject().put("site", site.id), new OpCb() { @Override public void done(JSONObject r) {
            if (!r.optBoolean("ok") && lis != null) lis.onNote("DOM alınamadı: " + r.optString("err")); } }); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ hazır olma / izleme döngüsü
    private void tick() {
        if (web == null || destroyed) return;
        if (tracking) { pollReply(); return; }
        idleProbe();
    }

    private void idleProbe() {
        String cu = web == null ? null : web.getUrl();
        if (cu != null && cu.contains("accounts.google.com")) {
            usable = false;
            setStatus(ST_LOGIN, "Giriş gerekli — Web sekmesinden hesabına gir");
            next(2000); return;
        }
        agent("probe()", new JCb() { @Override public void got(JSONObject o) {
            if (web == null) return;
            if (o == null) { next(1500); return; }
            if (o.optBoolean("noagent")) { inject(); next(1200); return; }
            boolean login = o.optBoolean("login"), ready = o.optBoolean("ready"), guest = o.optBoolean("guest");
            if (login) {
                usable = false;
                setStatus(ST_LOGIN, "Giriş gerekli — Web sekmesinden hesabına gir");
                next(2500); return;
            }
            if (ready) {
                idleBad = 0;
                if (!prefsApplied && !applying) { applyPrefs(guest); next(1500); return; }
                if (usable && status != ST_READY) setStatus(ST_READY, guest ? "Hazır (misafir)" : "Hazır");
                next(usable ? 4000 : 1000); return;
            }
            // sayfa var ama kutu yok
            readyTries++;
            if (usable && ++idleBad >= 2) { usable = false; setStatus(ST_LOADING, "Sayfa hazır değil…"); }
            else if (!usable) setStatus(readyTries > 40 ? ST_LOGIN : ST_LOADING, readyTries > 40 ? "Mesaj kutusu bulunamadı (Web sekmesine bak)" : "Hazırlanıyor…");
            next(readyTries > 40 ? 3000 : 800);
        } });
    }

    private void next(long ms) { if (web == null || destroyed) return; h.removeCallbacks(tickR); h.postDelayed(tickR, ms); }

    /** Kullanıcı tercihlerini (model, düşünme düzeyi) siteye uygular, sonra kuyruğu çalıştırır. */
    private void applyPrefs(final boolean guest) {
        applying = true;
        final List<String[]> steps = new ArrayList<>();
        if (site.modelPicker && !prefModel.isEmpty()) steps.add(new String[]{"setModel", prefModel});
        if (!prefThinking.isEmpty()) steps.add(new String[]{"setThinking", prefThinking});
        runSteps(steps, 0, guest);
    }

    private void runSteps(final List<String[]> steps, final int i, final boolean guest) {
        if (web == null) return;
        if (i >= steps.size()) {
            applying = false; prefsApplied = true; usable = true;
            setStatus(ST_READY, guest ? "Hazır (misafir)" : "Hazır");
            List<Runnable> q = new ArrayList<>(queue); queue.clear();
            for (Runnable r : q) r.run();
            next(3000);
            return;
        }
        String[] st = steps.get(i);
        JSONObject a = new JSONObject();
        try { if (st[0].equals("setModel")) a.put("name", st[1]); else a.put("level", st[1]); } catch (Exception ignored) {}
        setStatus(ST_LOADING, st[0].equals("setModel") ? "Model ayarlanıyor…" : "Düşünme düzeyi ayarlanıyor…");
        final String what = st[0];
        op(what, a, new OpCb() { @Override public void done(JSONObject r) {
            if (!r.optBoolean("ok") && lis != null)
                lis.onNote((what.equals("setModel") ? "Model" : "Düşünme düzeyi") + " ayarlanamadı (" + r.optString("err") + "). Web sekmesinden elle seçebilirsin.");
            runSteps(steps, i + 1, guest);
        } });
    }

    /** Tercih değişince açık sayfaya hemen uygula. */
    public void reapplyPrefs() {
        if (web == null || !usable) return;
        prefsApplied = false; applying = false; usable = false;
        next(100);
    }

    /** Oturum hazır olunca çalıştır (hazırsa hemen). */
    public void whenUsable(Runnable r) {
        if (usable) { r.run(); return; }
        queue.add(r);
        if (web == null) open(site, site.newChatUrl);
    }

    public void cancelQueue() { queue.clear(); }

    // ------------------------------------------------------------------ mesaj gönder + cevap izle
    public void send(String text) { sendWithFiles(text, new ArrayList<Upload>()); }

    /** Önce dosyaları siteye ekler (sırayla), sonra mesajı gönderir. */
    public void attachOne(final Upload u, final AttachCb cb) {
        uploads.put(u.id, u);
        pendingUris.clear();
        pendingUris.add(Uri.parse("content://" + AUTH + "/up/" + Uri.encode(u.file.getName())));
        JSONObject a = new JSONObject();
        try { a.put("id", u.id); } catch (Exception ignored) {}
        op("attach", a, new OpCb() { @Override public void done(JSONObject r) {
            pendingUris.clear();
            cb.done(r.optBoolean("ok"), r.optString("tokens", ""), r.optString("wname", ""), r.optInt("idx", -1), r.optString("err", ""));
        } }, 150000);
    }

    public void removeAttached(String name, String wname, int idx) {
        if (web == null || !usable) return;
        JSONObject a = new JSONObject();
        try { a.put("name", name == null ? "" : name).put("wname", wname == null ? "" : wname).put("idx", idx); } catch (Exception ignored) {}
        op("removeAttach", a, new OpCb() { @Override public void done(JSONObject r) {} }, 10000);
    }

    public void sendWithFiles(final String text, final List<Upload> ups) {
        sentText = text;
        attachNext(ups, 0, text);
    }

    private void attachNext(final List<Upload> ups, final int i, final String text) {
        if (i >= ups.size()) { doSend(text, !ups.isEmpty()); return; }
        final Upload u = ups.get(i);
        uploads.put(u.id, u);
        pendingUris.clear();
        pendingUris.add(Uri.parse("content://" + AUTH + "/up/" + Uri.encode(u.file.getName())));
        if (lis != null) lis.onStage("Dosya ekleniyor: " + u.name + " (" + (i + 1) + "/" + ups.size() + ")");
        JSONObject a = new JSONObject();
        try { a.put("id", u.id); } catch (Exception ignored) {}
        op("attach", a, new OpCb() { @Override public void done(JSONObject r) {
            pendingUris.clear();
            if (r.optBoolean("ok")) attachNext(ups, i + 1, text);
            else if (lis != null) lis.onReplyError("Dosya siteye eklenemedi: " + u.name + ". Web sekmesinden elle ekleyip tekrar dene (site bu dosya türünü kabul etmiyor olabilir).");
        } }, 150000);
    }

    private void doSend(String text, boolean hadFiles) {
        if (lis != null) lis.onStage("");
        JSONObject a = new JSONObject();
        try { a.put("text", text).put("waitSend", hadFiles ? 90000 : 6000); } catch (Exception ignored) {}
        op("send", a, new OpCb() { @Override public void done(JSONObject r) {
            if (r.optBoolean("ok")) { fresh = false; track(r.optInt("m0", 0), r.optString("base", "")); }
            else if (lis != null) lis.onReplyError("Mesaj gönderilemedi (" + r.optString("err") + "). Web sekmesinden kontrol et.");
        } }, hadFiles ? 120000 : 60000);
    }

    private void track(int m0, String base) {
        trackM0 = m0; baseSig = base == null ? "" : base; sawGen = false; errCount = 0;
        sig = ""; lastText = ""; lastImgs = new ArrayList<>(); stable = 0; tracking = true; noReplyReported = false; dbg = "";
        trackStart = System.currentTimeMillis();
        next(500);
    }

    private void stopTracking() { tracking = false; }

    private String stateCall(String s) {
        return "state(" + JSONObject.quote(s) + "," + trackM0 + "," + JSONObject.quote(baseSig) + "," + sawGen + ")";
    }

    /** Durdur: üretim sürüyorsa siteyi durdurur; zaten bitmişse web'deki cevabı hemen alır. */
    public void stopGeneration() {
        agent("stop()", new JCb() { @Override public void got(JSONObject o) {
            if (o != null && o.optBoolean("ok")) return;
            if (tracking) forceFinish();
        } });
    }

    /** Takip kaçırdıysa: web'deki son cevabı koşulsuz oku. */
    public void recover() {
        if (web == null) { if (lis != null) lis.onReplyError("Web bağlantısı yok."); return; }
        trackM0 = -1; baseSig = ""; sawGen = true; errCount = 0;
        sig = ""; lastText = ""; lastImgs = new ArrayList<>(); tracking = true; trackStart = System.currentTimeMillis();
        finish();
    }

    private void forceFinish() {
        agent(stateCall(""), new JCb() { @Override public void got(JSONObject o) {
            String t = o != null ? o.optString("text", "") : "";
            JSONArray im = o != null ? o.optJSONArray("imgs") : null;
            if (o != null && o.optBoolean("fresh") && (!t.isEmpty() || (im != null && im.length() > 0))) {
                lastText = t; lastImgs = toList(im);
                complete();
            } else {
                tracking = false;
                if (lis != null) lis.onReplyError("Durdurulacak yanıt yok; web'de de henüz okunabilir cevap görünmüyor (" + (o == null ? "" : o.optString("dbg", "")) + ").");
            }
        } });
    }

    private void pollReply() {
        agent(stateCall(sig), new JCb() { @Override public void got(JSONObject o) {
            if (!tracking) return;
            long age = System.currentTimeMillis() - trackStart;
            if (o == null || o.optBoolean("noagent")) { if (o != null) inject(); next(900); return; }
            if (o.has("err") && ++errCount == 4 && lis != null) lis.onNote("Sürücü hatası: " + o.optString("err"));
            dbg = o.optString("dbg", dbg);
            boolean gen = o.optBoolean("gen");
            if (gen) sawGen = true;
            boolean fresh = o.optBoolean("fresh");
            boolean changed = false;
            if (fresh && o.has("text")) {
                String t = o.optString("text", "");
                if (!t.equals(lastText)) { lastText = t; changed = true; }
                sig = o.optString("sig", sig);
                JSONArray im = o.optJSONArray("imgs");
                if (im != null) { List<String> n = toList(im); if (!n.equals(lastImgs)) { lastImgs = n; changed = true; } }
            }
            boolean haveReply = fresh && (!lastText.isEmpty() || !lastImgs.isEmpty());
            if (changed && haveReply && lis != null) lis.onReply(lastText, false);
            if (gen || changed) stable = 0; else stable++;

            if (haveReply && !gen && stable >= 5) { finish(); return; }
            if (!haveReply && !gen && age > 120000) {
                tracking = false;
                if (lis != null) lis.onReplyError("Cevap algılanamadı (" + dbg + ").");
                next(1500); return;
            }
            if (age > 25 * 60 * 1000) { finish(); return; }
            next(600);
        } });
    }

    /** Son kez tam metni oku (kesik kalmasın), sonra görselleri alıp bitir. */
    private void finish() {
        agent(stateCall(""), new JCb() { @Override public void got(JSONObject o) {
            if (o != null && o.optBoolean("fresh") && o.has("text")) {
                String t = o.optString("text", "");
                if (lastText.isEmpty() || t.length() >= lastText.length()) lastText = t;
                JSONArray im = o.optJSONArray("imgs");
                if (im != null && im.length() >= lastImgs.size()) lastImgs = toList(im);
            }
            if (o != null) dbg = o.optString("dbg", dbg);
            complete();
        } });
    }

    private void complete() {
        tracking = false;
        if (lastText.isEmpty() && lastImgs.isEmpty()) {
            if (lis != null) lis.onReplyError("Sayfada okunabilir bir cevap bulunamadı (" + dbg + ").");
            next(2500); return;
        }
        if (lastImgs.isEmpty()) { if (lis != null) lis.onReply(lastText, true); next(2500); return; }
        if (lis != null) lis.onStage("Görsel alınıyor…");
        grabImages(lastImgs, new ImgCb() { @Override public void done(List<String> names) {
            if (lis != null) {
                if (names.isEmpty() && lastText.isEmpty()) {
                    lis.onReplyError("Görsel algılandı fakat indirilemedi (" + lastImgs.get(0) + ").");
                } else {
                    if (names.isEmpty()) lis.onNote("Görsel indirilemedi");
                    lis.onImages(names);
                    lis.onReply(lastText, true);
                }
            }
            next(2500);
        } });
    }
}
