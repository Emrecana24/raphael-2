package com.raphael.app.codespaces;
import android.content.Context;
import java.util.*;
public class CsManager {
    public interface Host { void csAcquireWake(); void csReleaseWake(); boolean csIsGenerating(); String csChatId(); void csUpdateStatus(); void csSendFollowup(String full, String shown); void csToast(String msg); void csUiApproval(List<CsProtocol.Action> actions); void csUiExecDone(List<CsProtocol.ExecResult> res, boolean sentBack); void csUiChanged(); }
    public interface DoneCb { void done(List<CsProtocol.ExecResult> res); }
    private final Context ctx; private final Host host; public final CsConfig cfg;
    public boolean mode = false, running = false; public String curCwd = "/workspaces/raphael-2", pendingChatId = null, stage = ""; public int autoTurns = 0;
    public List<CsProtocol.Action> pendingActions = null; private CsClient.Runner activeRunner = null; private int opSeq = 0;
    public CsManager(Context c, Host h) { ctx = c.getApplicationContext(); host = h; cfg = new CsConfig(ctx); }
    public boolean isBgActive() { return mode || running; }
    public void reset() { opSeq++; if (activeRunner != null) { activeRunner.cancel(); activeRunner = null; } mode = false; running = false; autoTurns = 0; stage = ""; pendingActions = null; pendingChatId = null; host.csUiChanged(); }
    public void activate(String task) { mode = true; autoTurns = 0; pendingActions = null; pendingChatId = null; host.csUiChanged(); host.csUpdateStatus(); String full = CsProtocol.buildAttachPrompt(ctx, curCwd, cfg.isAuto(), task); String shown = "🔌 /codespaces aktif · codespaces_mcp.md" + (task == null || task.trim().isEmpty() ? "" : "\n" + task.trim()); host.csSendFollowup(full, shown); }
    public void deactivate(boolean notify, boolean hasMsgs) { opSeq++; if (activeRunner != null) { activeRunner.cancel(); activeRunner = null; } mode = false; running = false; autoTurns = 0; stage = ""; pendingActions = null; pendingChatId = null; host.csUiChanged(); host.csUpdateStatus(); if (notify && hasMsgs && !host.csIsGenerating()) host.csSendFollowup("[Sistem Bilgisi]: /codespaces MCP baglantisi kapatildi. Artik cs-mcp komut bloklari yazma; normal sohbete devam et.", "🔌 /codespaces kapatildi"); else host.csToast("Codespaces modu kapatildi"); }
    public boolean handleAiReply(String md, Runnable uiDone) {
        if (!mode) return false; List<CsProtocol.Action> acts = CsProtocol.parseActions(md); if (acts.isEmpty()) { autoTurns = 0; return false; }
        if (cfg.isAuto() && autoTurns < 8) { uiDone.run(); execute(acts, true, true, null); return true; }
        autoTurns = 0; pendingChatId = host.csChatId(); pendingActions = acts; uiDone.run(); host.csUiApproval(acts); host.csUpdateStatus(); return true;
    }
    public void execute(List<CsProtocol.Action> acts, final boolean sendBack, final boolean countTurn, final DoneCb cb) {
        if (running) { host.csToast("Codespaces komutu zaten calisiyor"); return; }
        final String tc = host.csChatId(); final int seq = ++opSeq; pendingActions = null; pendingChatId = null; running = true; stage = "Codespaces baslatiliyor…";
        host.csAcquireWake(); host.csUiChanged(); host.csUpdateStatus(); final CsClient.Runner r = new CsClient.Runner(cfg); activeRunner = r;
        r.run(acts, new CsClient.ExecCb() {
            @Override public void onProgress(int sec, String lbl) { if (seq == opSeq && activeRunner == r && (sec == 0 || sec % 2 == 0)) { stage = "Codespaces (" + sec + " sn): " + lbl; host.csUpdateStatus(); } }
            @Override public void onDone(List<CsProtocol.ExecResult> res) {
                if (seq != opSeq) return; running = false; activeRunner = null; stage = "";
                for (CsProtocol.ExecResult er : res) if (er.cwd != null && !er.cwd.isEmpty()) curCwd = er.cwd;
                host.csUiChanged(); if (cb != null) cb.done(res); else host.csUiExecDone(res, sendBack);
                int ok = 0; for (CsProtocol.ExecResult er : res) if (er.ok) ok++;
                if (sendBack && mode && !host.csIsGenerating() && tc.equals(host.csChatId())) { if (countTurn) autoTurns++; else autoTurns = 0; host.csSendFollowup(CsProtocol.formatFeedback(res), "🖥️ " + ok + "/" + res.size() + " komut -> modele iletildi"); }
                else { if (!host.csIsGenerating()) host.csReleaseWake(); host.csUpdateStatus(); }
            }
        });
    }
    public void stopRunning() { if (!running || activeRunner == null) return; stage = "Durduruluyor…"; host.csUpdateStatus(); activeRunner.cancel(); }
    public void clearPending() { pendingActions = null; pendingChatId = null; host.csUpdateStatus(); }
}
