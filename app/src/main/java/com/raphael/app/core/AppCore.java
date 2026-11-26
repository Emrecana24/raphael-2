package com.raphael.app.core;
import android.content.*;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.*;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import com.raphael.app.code.*;
import com.raphael.app.data.*;
import com.raphael.app.ui.Ui;
import java.io.File;
import java.util.*;

public class AppCore implements WebSession.Listener {
    public static class Att { public String id, name, mime, content, thumb, tokens = "", wname = ""; public File file; public long size; public boolean text, removed; public int upState = 0, webIdx = -1; }
    public interface UiBridge {
        void onCoreStatus(int st, String info);
        void onCoreChatChanged();
        void onCoreUserMsg(Msg m);
        void onCoreAiStart(String switchBanner);
        void onCoreAiThinking(String text, boolean active);
        void onCoreAiReply(String md);
        void onCoreAiDone(Msg am, List<CodeProtocol.Action> approval);
        void onCoreCodeAutoDone(List<CodeProtocol.ExecResult> res, boolean sentBack, boolean hasUndo);
        void onCoreAiError(String err);
        void onCoreCodeUi();
        void onCoreAttachUi();
        void onCoreToast(String msg);
        void onCoreNote(String msg);
        boolean onCoreFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p);
        void onCoreFinish();
    }
    public interface ApproveCb { void done(List<CodeProtocol.ExecResult> res, boolean hasUndo); }
    private static AppCore inst;
    public final Context ctx;
    public final Handler h = new Handler(Looper.getMainLooper());
    public final SharedPreferences prefs;
    public final ChatStore store;
    public final WebSession session;
    public CodeWorkspace ws;
    public Site site = Site.GEMINI;
    public Chat chat = new Chat();
    public String boundChatId = null;
    public Site boundSite = null;
    public boolean generating = false, awaitingStart = false, gotFirstText = false, codeMode = false, openCodeAfterReply = false, wasBoundOnSend = false, curThActive = false, stopped = false;
    public long genStart = 0;
    public int sendGen = 0, lastSendDelay = 0, uploadGen = 0, autoTurns = 0, codeOpSeq = 0, importSeq = 0;
    public final List<Att> atts = new ArrayList<>();
    public Att activeUpload = null;
    public List<String> pendingImgs = new ArrayList<>();
    public String stage = "", lastPayload = "", curText = "", curThText = "", pendingCodeTask = null, lastErr = null, siteInfo = "Bağlı değil";
    public int siteSt = WebSession.ST_OFF;
    public List<WebSession.Upload> lastUps = new ArrayList<>();
    public String pendingApprovalChatId = null;
    public List<CodeProtocol.Action> pendingApprovalActions = null;
    private Runnable pendingSendR, pendingTimeoutR;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private UiBridge ui;

    public static synchronized AppCore get(Context c) { if (inst == null) inst = new AppCore(c.getApplicationContext()); return inst; }
    public static void stopFromService(Context c) { if (inst != null) inst.shutdown(); }

    private AppCore(Context c) {
        ctx = c; prefs = c.getSharedPreferences("raphael", Context.MODE_PRIVATE); Ui.fontScale = prefs.getFloat("fs", 1f);
        store = new ChatStore(c); cleanUpCache(); site = Site.fromId(prefs.getString("site", "gemini"));
        session = new WebSession(c); session.setListener(this); session.setUaMode(prefs.getInt("ua", 0));
        loadInitialChat(); ws = new CodeWorkspace(chat.codeDir); pushPrefs();
        if (prefs.getBoolean("codeSysDirty", false)) session.setSysPrompt("", true);
    }

    private void cleanUpCache() { File[] old = new File(ctx.getCacheDir(), "up").listFiles(); if (old != null) for (File f : old) f.delete(); }
    private void loadInitialChat() { List<Chat> all = store.list(); if (!all.isEmpty() && prefs.getBoolean("openLast", true)) { Chat c = store.load(all.get(0).id); if (c != null && !c.msgs.isEmpty()) chat = c; } }
    public void ensureStarted() {
        if (stopped) { stopped = false; boundChatId = null; boundSite = null; if (chat.msgs.isEmpty()) loadInitialChat(); ws = new CodeWorkspace(chat.codeDir); if (prefs.getBoolean("codeSysDirty", false)) session.setSysPrompt("", true); }
        RaphaelService.start(ctx); if (!session.isConnected()) session.open(site, site.newChatUrl); updateNotif();
    }
    public void attachUi(UiBridge u, android.widget.FrameLayout webHost) { ui = u; session.attach(webHost); ensureStarted(); }
    public void detachUi(UiBridge u, android.widget.FrameLayout webHost) { if (ui == u) ui = null; session.detach(webHost); }
    public void shutdown() {
        stopped = true; cancelPendingSend(); importSeq++; codeOpSeq++; uploadGen++; activeUpload = null; releaseWake();
        h.removeCallbacks(timerR); generating = false; awaitingStart = false; codeMode = false; pendingApprovalActions = null; lastErr = null;
        session.setBgActive(false); session.destroy(); if (ui != null) ui.onCoreFinish(); ui = null;
    }
    private void acquireWake() { try { if (wakeLock == null) { PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE); if (pm != null) { wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Raphael:BgWork"); wakeLock.setReferenceCounted(false); } } if (wakeLock != null) wakeLock.acquire(1800000L); if (wifiLock == null) { WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE); if (wm != null) { wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Raphael:WifiLock"); wifiLock.setReferenceCounted(false); } } if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); } catch (Throwable ignored) {} }
    private void releaseWake() { try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {} try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) {} }
    private void updateBgActive() { session.setBgActive(!stopped && (generating || codeMode || activeUpload != null)); }
    public void updateNotif() {
        if (stopped) return;
        updateBgActive();
        String ttl = codeMode ? "Raphael Code · " + ws.getDisplayPath() : "Raphael · " + site.label;
        String msg = generating ? (!stage.isEmpty() ? stage : awaitingStart ? "Oturum hazırlanıyor…" : gotFirstText ? (codeMode && autoTurns > 0 ? "Yazıyor… (Tur " + autoTurns + "/8)" : "Yazıyor…") : curThActive ? "Düşünüyor…" : "Yanıt bekleniyor…") : (codeMode && pendingApprovalActions != null ? "Onay bekleyen " + pendingApprovalActions.size() + " işlem var" : siteInfo);
        RaphaelService.update(ctx, ttl, msg);
    }
    public String thinking() { String lv = prefs.getString("think_" + site.id, ""); return (site != Site.AISTUDIO && "xhigh".equals(lv)) ? "" : lv; }
    public String maxOutput() { String v = prefs.getString("out_" + site.id, "65536"); return v.isEmpty() ? "65536" : v; }
    public void pushPrefs() { session.setPrefs(thinking(), site.modelPicker ? prefs.getString("model_" + site.id, "") : "", site == Site.AISTUDIO ? maxOutput() : ""); }
    public void setThinking(String lv) { prefs.edit().putString("think_" + site.id, lv).apply(); pushPrefs(); if (session.isConnected()) session.reapplyPrefs(); }
    public void setMaxOutput(String v) { if (v == null || v.isEmpty()) v = "65536"; String prev = maxOutput(); prefs.edit().putString("out_" + site.id, v).apply(); pushPrefs(); if (session.isConnected()) { if ("65536".equals(v) && !"65536".equals(prev)) session.resetOutput(); else session.reapplyPrefs(); } toast("Output: " + v + " token"); }
    private void toast(String s) { if (ui != null) ui.onCoreToast(s); }
    public void applySiteSwitch(Site s, boolean transfer) {
        if (s != Site.AISTUDIO && codeMode) { codeMode = false; autoTurns = 0; if (prefs.getBoolean("codeSysDirty", false)) session.setSysPrompt("", true); if (ui != null) ui.onCoreCodeUi(); }
        pendingApprovalActions = null; pendingApprovalChatId = null; lastErr = null; openCodeAfterReply = false; pendingCodeTask = null; codeOpSeq++;
        if (transfer && !chat.msgs.isEmpty()) {
            store.save(chat); String prompt = buildTransferPrompt();
            site = s; prefs.edit().putString("site", s.id).apply(); boundChatId = null; boundSite = null;
            pushPrefs(); invalidateUploads(false); startContextTransfer(s.label, prompt); return;
        }
        if (!chat.msgs.isEmpty()) store.save(chat);
        site = s; prefs.edit().putString("site", s.id).apply();
        if (!chat.msgs.isEmpty()) { chat = new Chat(); chat.site = site.id; ws = new CodeWorkspace(""); if (ui != null) { ui.onCoreChatChanged(); ui.onCoreCodeUi(); } } else chat.site = site.id;
        boundChatId = null; boundSite = null; pushPrefs(); session.open(s, s.newChatUrl); invalidateUploads(true); updateNotif();
    }

    public void applyModelSwitch(String name, boolean transfer) {
        String lbl = name.isEmpty() ? site.label + " (Varsayılan)" : name;
        pendingApprovalActions = null; pendingApprovalChatId = null; lastErr = null; openCodeAfterReply = false; pendingCodeTask = null; codeOpSeq++;
        if (transfer && !chat.msgs.isEmpty()) {
            store.save(chat); String prompt = buildTransferPrompt();
            prefs.edit().putString("model_" + site.id, name).apply(); boundChatId = null; boundSite = null;
            pushPrefs(); invalidateUploads(false); toast("Model: " + lbl); startContextTransfer(lbl, prompt); return;
        }
        if (!chat.msgs.isEmpty()) store.save(chat);
        prefs.edit().putString("model_" + site.id, name).apply(); pushPrefs();
        if (!chat.msgs.isEmpty()) { codeMode = false; autoTurns = 0; chat = new Chat(); chat.site = site.id; ws = new CodeWorkspace(""); if (prefs.getBoolean("codeSysDirty", false)) session.setSysPrompt("", true); boundChatId = null; boundSite = null; if (ui != null) { ui.onCoreChatChanged(); ui.onCoreCodeUi(); } session.open(site, site.newChatUrl); invalidateUploads(true); }
        else if (name.isEmpty() || !session.isFresh() || !session.isConnected()) { session.open(site, site.newChatUrl); invalidateUploads(true); }
        else session.reapplyPrefs();
        toast(name.isEmpty() ? "Model: varsayılan" : "Model: " + name); updateNotif();
    }

    private void startContextTransfer(String targetLabel, String prompt) {
        wasBoundOnSend = false; lastPayload = prompt; lastUps = new ArrayList<>();
        startAi("Sohbet geçmişi aktarılıyor · " + targetLabel);
        stage = "Model hazırlanıyor…"; updateNotif(); launchSend(true, 3000);
    }

    public void newChat() {
        if (generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        chat = new Chat(); chat.site = site.id; boundChatId = null; boundSite = null; lastErr = null;
        codeMode = false; autoTurns = 0; openCodeAfterReply = false; pendingCodeTask = null; pendingApprovalActions = null; pendingApprovalChatId = null; codeOpSeq++;
        ws = new CodeWorkspace(""); if (prefs.getBoolean("codeSysDirty", false)) session.setSysPrompt("", true);
        if (ui != null) { ui.onCoreChatChanged(); ui.onCoreCodeUi(); }
        boolean rel = false;
        if (session.isConnected() && !session.isFresh()) { session.newChat(); rel = true; } else if (!session.isConnected()) { session.open(site, site.newChatUrl); rel = true; }
        if (rel) invalidateUploads(true); updateNotif();
    }

    public void openChat(String id) {
        if (generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        Chat c = store.load(id); if (c == null) return;
        chat = c; boundChatId = null; boundSite = null; lastErr = null;
        codeMode = false; autoTurns = 0; openCodeAfterReply = false; pendingCodeTask = null; pendingApprovalActions = null; pendingApprovalChatId = null; codeOpSeq++;
        ws = new CodeWorkspace(chat.codeDir); if (prefs.getBoolean("codeSysDirty", false)) session.setSysPrompt("", true);
        if (ui != null) { ui.onCoreChatChanged(); ui.onCoreCodeUi(); }
        if (!atts.isEmpty() && !session.isFresh() && session.isConnected()) { session.open(site, site.newChatUrl); invalidateUploads(true); } updateNotif();
    }

    private static String fenceFor(String s) {
        int max = 2, cur = 0; for (int i = 0; i < s.length(); i++) { if (s.charAt(i) == 96) { cur++; if (cur > max) max = cur; } else cur = 0; }
        StringBuilder f = new StringBuilder(); for (int i = 0; i < max + 1; i++) f.append((char) 96); return f.toString();
    }

    public boolean doSendUser(String typed) {
        if (typed.isEmpty() && atts.isEmpty()) return false;
        if (activeUpload != null) { toast("Dosya işlemi sürüyor, bir saniye bekle…"); return false; }
        for (Att a : atts) { if (!a.text && a.upState == 1) { toast("Dosya siteye yükleniyor, bitmesini bekle…"); return false; } if (!a.text && a.upState == 3) { toast("Yüklenemeyen dosya var; çarpıdan kaldır veya dokun"); return false; } }
        autoTurns = 0; boolean boundOk = chat.id.equals(boundChatId) && boundSite == site && session.isConnected();
        boolean reload = !boundOk && !session.isFresh();
        StringBuilder full = new StringBuilder(), names = new StringBuilder();
        List<WebSession.Upload> ups = new ArrayList<>(); List<String> imgs = new ArrayList<>();
        for (Att a : atts) {
            if (names.length() > 0) names.append(", "); names.append(a.name); if (!a.tokens.isEmpty()) names.append(" (").append(a.tokens).append(")");
            if (a.text) { String f = fenceFor(a.content); full.append("Dosya: ").append(a.name).append("\n").append(f).append("\n").append(a.content).append("\n").append(f).append("\n\n"); }
            else { if (reload || a.upState != 2) ups.add(new WebSession.Upload(a.id, a.name, a.mime, a.file)); if (a.thumb != null) imgs.add(a.thumb); }
        }
        full.append(typed); String shown = (names.length() > 0 ? "📎 " + names + "\n" : "") + typed;
        atts.clear(); if (ui != null) ui.onCoreAttachUi();
        sendMsgInternal(full.toString(), shown, ups, imgs, 0, null); return true;
    }

    public void sendAgentTurn(String fullText, String shownText) { if (!generating) sendMsgInternal(fullText, shownText, new ArrayList<>(), null, 0, null); }

    private void sendMsgInternal(String fullText, String shownText, List<WebSession.Upload> ups, List<String> imgNames, int delayMs, String banner) {
        boolean boundOk = chat.id.equals(boundChatId) && boundSite == site && session.isConnected();
        int prior = chat.msgs.size(); boolean reload = !boundOk && !session.isFresh();
        Msg um = new Msg(Msg.USER, fullText, shownText, null); if (imgNames != null) um.imgs.addAll(imgNames);
        String payload = um.text; if (!boundOk && prior > 0) payload = contextPrefix(prior) + payload;
        chat.msgs.add(um); if (prior == 0) { chat.title = makeTitle(um.display()); chat.created = System.currentTimeMillis(); }
        chat.site = site.id; store.save(chat); if (ui != null) ui.onCoreUserMsg(um);
        startAi(banner); wasBoundOnSend = boundOk; lastPayload = payload; lastUps = ups; launchSend(reload, delayMs);
    }
    public void cancelPendingSend() {
        sendGen++;
        if (pendingSendR != null) { h.removeCallbacks(pendingSendR); pendingSendR = null; }
        if (pendingTimeoutR != null) { h.removeCallbacks(pendingTimeoutR); pendingTimeoutR = null; }
    }

    public void launchSend(boolean reload, final int delayMs) {
        final String fp = lastPayload; final List<WebSession.Upload> ups = lastUps;
        lastSendDelay = delayMs; cancelPendingSend(); final int myGen = sendGen; awaitingStart = true; updateNotif();
        if (reload) session.open(site, site.newChatUrl);
        pendingTimeoutR = () -> { if (myGen == sendGen && generating && awaitingStart) { session.cancelQueue(); failAi("Web oturumu hazır olmadı. Web sekmesinden giriş yapıp tekrar dene."); } };
        h.postDelayed(pendingTimeoutR, 80000);
        session.whenUsable(() -> {
            if (myGen != sendGen || !generating) return;
            if (pendingTimeoutR != null) { h.removeCallbacks(pendingTimeoutR); pendingTimeoutR = null; }
            if (delayMs > 0) {
                stage = "Model aktifleşti, bağlam hazırlanıyor (" + (delayMs / 1000) + " sn)…"; updateNotif();
                pendingSendR = () -> { if (myGen != sendGen || !generating) return; pendingSendR = null; awaitingStart = false; stage = "Bağlam gönderiliyor…"; updateNotif(); boundChatId = chat.id; boundSite = site; session.sendWithFiles(fp, ups); };
                h.postDelayed(pendingSendR, delayMs);
            } else { awaitingStart = false; boundChatId = chat.id; boundSite = site; session.sendWithFiles(fp, ups); }
        });
    }

    private String buildTranscript(int upto, int budget, int cap) {
        List<String> parts = new ArrayList<>(); int half = cap / 2 - 200;
        for (int i = upto - 1; i >= 0 && budget > 0; i--) {
            Msg m = chat.msgs.get(i); String t = m.text == null ? "" : m.text.trim();
            if (t.isEmpty() && !m.imgs.isEmpty()) t = "[Görsel paylaşıldı]"; if (t.isEmpty()) continue;
            if (t.length() > cap) t = t.substring(0, half) + "\n[...kısaltıldı...]\n" + t.substring(t.length() - half);
            String who = m.role.equals(Msg.USER) ? "[Kullanıcı]" : "[Asistan" + (m.site != null ? " - " + Site.fromId(m.site).label : "") + "]";
            String p = who + "\n" + t; if (!parts.isEmpty() && budget - p.length() < 0) break; budget -= p.length(); parts.add(0, p);
        }
        StringBuilder sb = new StringBuilder(); for (String p : parts) sb.append(p).append("\n\n"); return sb.toString().trim();
    }
    private String contextPrefix(int upto) { return "Aşağıda bu sohbetin önceki bölümü var. Sadece bağlam olarak kullan, tekrar özetleme.\n\n" + buildTranscript(upto, 28000, 9000) + "\n\n--- Önceki bölümün sonu. Yeni mesajım: ---\n"; }
    private String buildTransferPrompt() { return "Aşağıda devam etmekte olan bir sohbetin önceki geçmişi yer almaktadır. Bu konuşmayı bağlam olarak oku ve hafızana al.\n\nÖNEMLİ TALİMAT:\n- Geçmişi özetleme, analiz etme veya önceki sorulara yeniden cevap verme.\n- Sadece bağlamı aldığını teyit etmek için kısa bir yanıt ver (örneğin: \"Bağlamı aldım, kaldığımız yerden devam edelim.\").\n\n=== SOHBET GEÇMİŞİ ===\n" + buildTranscript(chat.msgs.size(), 36000, 12000) + "\n=== SOHBET GEÇMİŞİ SONU ==="; }
    public String makeTitle(String s) { String t = s.replace("📎", "").replaceAll("\\s+", " ").trim(); if (t.length() > 42) t = t.substring(0, 42).trim() + "…"; return t.isEmpty() ? "Sohbet" : t; }

    private final Runnable timerR = new Runnable() { @Override public void run() { if (!generating) return; updateNotif(); h.postDelayed(this, 5000); } };

    public void startAi(String banner) {
        generating = true; gotFirstText = false; stage = ""; curText = ""; curThText = ""; curThActive = false; lastErr = null;
        pendingApprovalActions = null; pendingApprovalChatId = null; genStart = System.currentTimeMillis(); acquireWake();
        if (ui != null) ui.onCoreAiStart(banner); updateNotif(); h.removeCallbacks(timerR); h.postDelayed(timerR, 5000);
    }

    private void finishAi(String md) {
        generating = false; h.removeCallbacks(timerR); releaseWake();
        Msg am = new Msg(Msg.AI, md, null, site.id); am.imgs.addAll(pendingImgs); pendingImgs = new ArrayList<>();
        chat.msgs.add(am); store.save(chat); stage = ""; curText = ""; curThText = ""; curThActive = false;
        if (openCodeAfterReply) {
            openCodeAfterReply = false; String tk = pendingCodeTask; pendingCodeTask = null; updateNotif();
            if (ui != null) { ui.onCoreAiDone(am, null); ui.onCoreNote("__OPEN_CODE__:" + (tk == null ? "" : tk)); } else activateCodeMode(tk);
            pumpPreUpload(); return;
        }
        if (codeMode && site == Site.AISTUDIO) {
            List<CodeProtocol.Action> actions = CodeProtocol.parseActions(md);
            if (!actions.isEmpty()) {
                boolean hasMut = CodeProtocol.hasMutating(actions);
                if (!hasMut || (chat.codeAuto && autoTurns < 8)) { if (ui != null) ui.onCoreAiDone(am, null); executeCodeActions(actions, true, true, null); pumpPreUpload(); return; }
                autoTurns = 0; pendingApprovalChatId = chat.id; pendingApprovalActions = actions; updateNotif();
                if (ui != null) ui.onCoreAiDone(am, actions); pumpPreUpload(); return;
            } else autoTurns = 0;
        }
        updateNotif(); if (ui != null) ui.onCoreAiDone(am, null); pumpPreUpload();
    }

    private void failAi(String err) {
        generating = false; awaitingStart = false; releaseWake(); cancelPendingSend();
        if (!wasBoundOnSend && curText.isEmpty()) { boundChatId = null; boundSite = null; }
        if (!curText.isEmpty()) { chat.msgs.add(new Msg(Msg.AI, curText, null, site.id)); store.save(chat); }
        lastErr = err; stage = ""; curText = ""; curThText = ""; curThActive = false; pendingImgs = new ArrayList<>(); h.removeCallbacks(timerR);
        updateNotif(); if (ui != null) ui.onCoreAiError(err); pumpPreUpload();
    }

    public void stopGen() { if (awaitingStart) { cancelPendingSend(); session.cancelQueue(); failAi("İptal edildi."); return; } session.stopGeneration(); }
    public void recoverFromWeb() { if (generating) return; startAi(null); awaitingStart = false; session.recover(); }
    public void resendLast() { if (generating || lastPayload == null || (lastPayload.isEmpty() && lastUps.isEmpty())) { toast("Tekrar gönderilecek bir şey yok"); return; } wasBoundOnSend = chat.id.equals(boundChatId) && boundSite == site && session.isConnected(); startAi(null); launchSend(!session.isConnected(), lastSendDelay); }
    @Override public void onStatus(int st, String info) {
        siteSt = st; siteInfo = info;
        if (st == WebSession.ST_READY && site == Site.AISTUDIO && !codeMode && !session.isSysPending() && prefs.getBoolean("codeSysDirty", false)) prefs.edit().putBoolean("codeSysDirty", false).apply();
        updateNotif(); if (ui != null) ui.onCoreStatus(st, info);
    }
    @Override public void onReply(String md, boolean done) {
        curText = md; if (!md.isEmpty()) { boolean first = !gotFirstText; gotFirstText = true; stage = ""; if (first) updateNotif(); }
        if (done) finishAi(md); else if (ui != null) ui.onCoreAiReply(md);
    }
    @Override public void onReplyError(String err) { if (generating) failAi(err); }
    @Override public void onImages(List<String> names) { pendingImgs = new ArrayList<>(names); }
    @Override public void onStage(String t) { stage = t == null ? "" : t; updateNotif(); }
    @Override public void onThinking(String text, boolean active) {
        boolean has = text != null && !text.trim().isEmpty(); if (!has && !active) return;
        boolean ch = curThActive != active; curThActive = active; if (has) curThText = text.trim();
        if (ch) updateNotif(); if (ui != null) ui.onCoreAiThinking(curThText, active);
    }
    @Override public boolean onFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) { return ui != null && ui.onCoreFileChooser(cb, p); }
    @Override public void onNote(String msg) { if (ui != null) ui.onCoreNote(msg); }

    public void invalidateUploads(boolean restart) {
        uploadGen++; activeUpload = null; updateBgActive(); boolean any = false;
        for (Att a : atts) if (!a.text && !a.removed) { a.upState = 1; a.tokens = ""; a.wname = ""; a.webIdx = -1; any = true; }
        if (any) { if (ui != null) ui.onCoreAttachUi(); if (restart) pumpPreUpload(); }
    }

    public void pumpPreUpload() {
        if (activeUpload != null || generating) return; Att next = null;
        for (Att a : atts) if (!a.text && !a.removed && a.upState == 1) { next = a; break; }
        if (next == null) return;
        if (!chat.id.equals(boundChatId) && !session.isFresh() && session.isConnected()) { invalidateUploads(false); session.open(site, site.newChatUrl); }
        final Att cur = next; final Site upSite = site; final int gen = uploadGen; activeUpload = cur; updateBgActive();
        session.whenUsable(() -> {
            if (gen != uploadGen || site != upSite || cur.removed || !atts.contains(cur)) { if (gen == uploadGen && activeUpload == cur) { activeUpload = null; if (cur.removed && cur.file != null) cur.file.delete(); pumpPreUpload(); } return; }
            session.attachOne(new WebSession.Upload(cur.id, cur.name, cur.mime, cur.file), (ok, tokens, wname, idx, err) -> {
                if (gen != uploadGen) { if (cur.removed && cur.file != null) cur.file.delete(); return; }
                if (activeUpload == cur) { activeUpload = null; updateBgActive(); } if (site != upSite) { pumpPreUpload(); return; }
                if (cur.removed || !atts.contains(cur)) { if (ok) session.removeAttached(cur.name, (wname != null && !wname.isEmpty()) ? wname : cur.wname, idx); if (cur.file != null) cur.file.delete(); pumpPreUpload(); return; }
                if (ok) { cur.upState = 2; cur.tokens = tokens == null ? "" : tokens; cur.wname = wname == null ? "" : wname; cur.webIdx = idx; }
                else { cur.upState = 3; toast(cur.name + " yüklenemedi" + (err != null && !err.isEmpty() ? " (" + err + ")" : "")); }
                if (ui != null) ui.onCoreAttachUi(); pumpPreUpload();
            });
        });
    }

    public void syncCodeSystemPrompt() {
        if (!codeMode || site != Site.AISTUDIO) return;
        String sys = CodeProtocol.buildSystemPrompt(ws.getDisplayPath(), chat.codeAuto, ws.hasRaphaelMd());
        prefs.edit().putBoolean("codeSysDirty", true).apply(); session.applySystemInstruction(sys, null); updateNotif();
    }

    public void activateCodeMode(final String task) {
        ws = new CodeWorkspace(chat.codeDir); codeMode = true; autoTurns = 0; chat.site = Site.AISTUDIO.id; store.save(chat);
        if (ui != null) ui.onCoreCodeUi(); updateNotif();
        String sys = CodeProtocol.buildSystemPrompt(ws.getDisplayPath(), chat.codeAuto, ws.hasRaphaelMd());
        prefs.edit().putBoolean("codeSysDirty", true).apply(); final int myOp = ++codeOpSeq;
        toast("Sistem talimatı AI Studio içine yazılıyor…");
        if (session.isUsable()) {
            session.applySystemInstruction(sys, r -> { if (myOp != codeOpSeq || !codeMode) return; toast(r.optBoolean("ok") ? "Raphael Code hazır (" + ws.getDisplayPath() + ")" : "Sistem talimatı yazılamadı, Web sekmesine bak"); runPendingCodeTask(task); });
        } else {
            session.setSysPrompt(sys, true); if (!session.isConnected()) session.open(Site.AISTUDIO, Site.AISTUDIO.newChatUrl);
            session.whenUsable(() -> { if (myOp == codeOpSeq && codeMode) { toast("Raphael Code hazır (" + ws.getDisplayPath() + ")"); runPendingCodeTask(task); } });
        }
    }

    public void runPendingCodeTask(String task) {
        if (task == null || task.isEmpty() || generating) return;
        if (task.equals("__OKU__")) sendOkuTree(""); else if (task.startsWith("__OKU__:")) sendOkuTree(task.substring(8)); else sendAgentTurn(task, task);
    }

    public void exitCodeMode() {
        codeMode = false; autoTurns = 0; openCodeAfterReply = false; pendingCodeTask = null; pendingApprovalActions = null; pendingApprovalChatId = null; codeOpSeq++;
        if (ui != null) ui.onCoreCodeUi(); updateNotif();
        final boolean hadMsgs = !chat.msgs.isEmpty(); final int myOp = codeOpSeq;
        final String bye = "[Sistem Bilgisi]: Raphael Code modu kapatıldı. Artık rph dosya/klasör komut blokları yazma; normal sohbet asistanı olarak kurallarsız devam et.";
        if (site == Site.AISTUDIO && session.isUsable()) {
            toast("Sistem talimatı temizleniyor…");
            session.applySystemInstruction("", r -> { if (myOp != codeOpSeq) return; if (r.optBoolean("ok")) prefs.edit().putBoolean("codeSysDirty", false).apply(); if (hadMsgs) h.postDelayed(() -> { if (myOp == codeOpSeq && !codeMode && !generating) sendAgentTurn(bye, "🔄 Raphael Code kapatıldı — normal sohbete dönüldü"); }, 350); else toast("Normal sohbete geçildi"); });
        } else {
            session.setSysPrompt("", true);
            if (hadMsgs && site == Site.AISTUDIO) session.whenUsable(() -> { if (myOp == codeOpSeq && !codeMode && !generating) sendAgentTurn(bye, "🔄 Raphael Code kapatıldı — normal sohbete dönüldü"); }); else toast("Normal sohbete geçildi");
        }
    }

    public void sendOkuTree(String extra) {
        CodeWorkspace.Result r = ws.tree("");
        String full = "[RAPHAEL /oku HARİTASI | Dizin: " + ws.getDisplayPath() + "]\n" + r.output + "\n\n" + (ws.hasRaphaelMd() ? "Not: Klasörde RAPHAEL.md var; gerekiyorsa önce @read RAPHAEL.md ile oku.\n" : "") + (extra.isEmpty() ? "Klasör yapısını incele ve hazır olduğunu kısaca belirt." : "Görev: " + extra);
        sendAgentTurn(full, "📂 /oku: " + ws.getDisplayPath() + (extra.isEmpty() ? "" : "\n" + extra));
    }

    public void executeCodeActions(final List<CodeProtocol.Action> actions, final boolean sendBack, final boolean countTurn, final ApproveCb cb) {
        final String turnChatId = chat.id; final int seq = codeOpSeq; pendingApprovalActions = null; pendingApprovalChatId = null; updateNotif();
        if (sendBack) acquireWake();
        new Thread(() -> {
            final List<CodeProtocol.ExecResult> res = new ArrayList<>(); for (CodeProtocol.Action a : actions) res.add(CodeProtocol.executeOne(ws, a));
            final String feedback = CodeProtocol.formatFeedback(res, ws); final boolean hasUndo = ws.canUndo();
            int ok = 0; for (CodeProtocol.ExecResult er : res) if (er.ok) ok++; final int okCnt = ok;
            h.post(() -> {
                if (ui != null) ui.onCoreCodeUi(); if (cb != null) cb.done(res, hasUndo); else if (ui != null) ui.onCoreCodeAutoDone(res, sendBack, hasUndo);
                if (sendBack && seq == codeOpSeq && codeMode && !generating && chat.id.equals(turnChatId)) { if (countTurn) autoTurns++; else autoTurns = 0; sendAgentTurn(feedback, "⚙️ " + okCnt + "/" + res.size() + " işlem → modele iletildi"); }
                else { if (sendBack && !generating) releaseWake(); updateNotif(); }
            });
        }).start();
    }

    public void clearPendingApproval() { pendingApprovalActions = null; pendingApprovalChatId = null; updateNotif(); }
    public void runUndo() { String msg = ws.undoLast(); if (ui != null) ui.onCoreCodeUi(); toast(msg); }
    public void finishImportedChat(Chat c) {
        store.save(c); chat = c; boundChatId = c.id; boundSite = Site.GEMINI; lastErr = null;
        codeMode = false; autoTurns = 0; openCodeAfterReply = false; pendingCodeTask = null; pendingApprovalActions = null; pendingApprovalChatId = null; codeOpSeq++;
        ws = new CodeWorkspace(c.codeDir); if (prefs.getBoolean("codeSysDirty", false)) session.setSysPrompt("", true);
        if (ui != null) { ui.onCoreChatChanged(); ui.onCoreCodeUi(); }
        invalidateUploads(true); updateNotif();
    }
    public void deleteAllChats() {
        store.deleteAll(); chat = new Chat(); chat.site = site.id; boundChatId = null; boundSite = null; lastErr = null;
        codeMode = false; autoTurns = 0; openCodeAfterReply = false; pendingCodeTask = null; pendingApprovalActions = null; pendingApprovalChatId = null; codeOpSeq++;
        ws = new CodeWorkspace(""); if (prefs.getBoolean("codeSysDirty", false)) session.setSysPrompt("", true);
        if (ui != null) { ui.onCoreChatChanged(); ui.onCoreCodeUi(); }
        updateNotif();
    }



}

