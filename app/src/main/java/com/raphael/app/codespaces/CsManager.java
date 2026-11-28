package com.raphael.app.codespaces;
import android.content.Context;
import java.io.File;
import java.util.*;
public class CsManager {
    public interface Host {
        void csAcquireWake();
        void csReleaseWake();
        boolean csIsGenerating();
        String csChatId();
        void csUpdateStatus();
        void csSendFollowup(String full, String shown, List<File> files);
        void csToast(String msg);
        void csUiApproval(List<CsProtocol.Action> actions);
        void csUiExecDone(List<CsProtocol.ExecResult> res, boolean sentBack);
        void csUiChanged();
    }
    public interface DoneCb { void done(List<CsProtocol.ExecResult> res); }
    private final Context ctx;
    private final Host host;
    public final CsConfig cfg;
    public boolean mode = false, running = false, canUndo = false;
    public String curCwd = "/workspaces/raphael-2", pendingChatId = null, stage = "";
    public int autoTurns = 0;
    public List<CsProtocol.Action> pendingActions = null;
    private CsClient.Runner activeRunner = null;
    private int opSeq = 0;
    public CsManager(Context c, Host h) { ctx = c.getApplicationContext(); host = h; cfg = new CsConfig(ctx); }
    public boolean isBgActive() { return mode || running; }
    public void reset() {
        opSeq++; if (activeRunner != null) { activeRunner.cancel(); activeRunner = null; }
        mode = false; running = false; canUndo = false; autoTurns = 0; stage = "";
        pendingActions = null; pendingChatId = null; host.csUiChanged();
    }
    public void activate(String task) {
        mode = true; autoTurns = 0; pendingActions = null; pendingChatId = null;
        host.csUiChanged(); host.csUpdateStatus();
        String full = CsProtocol.buildAttachPrompt(ctx, curCwd, cfg.isAuto(), cfg.isUnlimited(), task);
        String shown = "\ud83d\udd0c /codespaces aktif \u00b7 codespaces_mcp.md" + (task == null || task.trim().isEmpty() ? "" : "\n" + task.trim());
        host.csSendFollowup(full, shown, null);
    }
    public void deactivate(boolean notify, boolean hasMsgs) {
        opSeq++; if (activeRunner != null) { activeRunner.cancel(); activeRunner = null; }
        mode = false; running = false; canUndo = false; autoTurns = 0; stage = "";
        pendingActions = null; pendingChatId = null; host.csUiChanged(); host.csUpdateStatus();
        if (notify && hasMsgs && !host.csIsGenerating()) host.csSendFollowup("[Sistem Bilgisi]: /codespaces MCP baglantisi kapatildi. Artik cs-mcp komut bloklari yazma; normal sohbete devam et.", "\ud83d\udd0c /codespaces kapatildi", null);
        else host.csToast("Codespaces modu kapatildi");
    }
    public boolean handleAiReply(String md, Runnable uiDone) {
        if (!mode) return false;
        List<CsProtocol.Action> acts = CsProtocol.parseActions(md);
        if (acts.isEmpty()) { autoTurns = 0; return false; }
        boolean hasMut = CsProtocol.hasMutating(acts);
        if (!hasMut || (cfg.isAuto() && (cfg.isUnlimited() || autoTurns < 8))) {
            uiDone.run(); execute(acts, true, hasMut, null); return true;
        }
        autoTurns = 0; pendingChatId = host.csChatId(); pendingActions = acts;
        uiDone.run(); host.csUiApproval(acts); host.csUpdateStatus(); return true;
    }
    public void execute(List<CsProtocol.Action> acts, final boolean sendBack, final boolean countTurn, final DoneCb cb) {
        if (running) { host.csToast("Codespaces komutu zaten calisiyor"); return; }
        final String tc = host.csChatId(); final int seq = ++opSeq;
        pendingActions = null; pendingChatId = null; running = true; stage = "Codespaces calisiyor\u2026";
        host.csAcquireWake(); host.csUiChanged(); host.csUpdateStatus();
        final CsClient.Runner r = new CsClient.Runner(CsConfig.localRoot(ctx), cfg); activeRunner = r;
        r.run(acts, new CsClient.ExecCb() {
            @Override public void onProgress(int sec, String lbl) {
                if (seq == opSeq && activeRunner == r && (sec == 0 || sec % 2 == 0)) {
                    stage = sec > 0 ? "Codespaces (" + sec + " sn): " + lbl : "Codespaces: " + lbl;
                    host.csUpdateStatus();
                }
            }
            @Override public void onDone(List<CsProtocol.ExecResult> res) {
                if (seq != opSeq) return;
                running = false; activeRunner = null; stage = "";
                List<File> attachFiles = new ArrayList<>();
                for (CsProtocol.ExecResult er : res) {
                    if (er.cwd != null && !er.cwd.isEmpty()) curCwd = er.cwd;
                    if (er.undoCount >= 0) canUndo = er.undoCount > 0;
                    if (er.ok && er.action.op == CsProtocol.Op.ATTACH && !er.files.isEmpty()) attachFiles.addAll(er.files);
                }
                host.csUiChanged();
                if (cb != null) cb.done(res); else host.csUiExecDone(res, sendBack);
                int ok = 0; for (CsProtocol.ExecResult er : res) if (er.ok) ok++;
                if (sendBack && mode && !host.csIsGenerating() && tc.equals(host.csChatId())) {
                    if (countTurn) autoTurns++; else autoTurns = 0;
                    host.csSendFollowup(CsProtocol.formatFeedback(res), "\ud83d\udda5\ufe0f " + ok + "/" + res.size() + " islem -> modele iletildi", attachFiles);
                } else { if (!host.csIsGenerating()) host.csReleaseWake(); host.csUpdateStatus(); }
            }
        });
    }
    public void runUndo() {
        if (running) { host.csToast("Komut calisirken geri alinamaz"); return; }
        CsClient.fsUndo(cfg, (ok, msg, cwd, undo) -> {
            if (cwd != null && !cwd.isEmpty()) curCwd = cwd;
            if (undo >= 0) canUndo = undo > 0;
            host.csUiChanged(); host.csUpdateStatus(); host.csToast(msg);
        });
    }
    public void stopRunning() { if (!running || activeRunner == null) return; stage = "Durduruluyor\u2026"; host.csUpdateStatus(); activeRunner.cancel(); }
    public void clearPending() { pendingActions = null; pendingChatId = null; host.csUpdateStatus(); }
}
