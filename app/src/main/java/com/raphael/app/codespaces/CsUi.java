package com.raphael.app.codespaces;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.raphael.app.code.CodeUi;
import com.raphael.app.ui.Ui;
import java.util.*;
public class CsUi {
    public interface Host {
        void onCsToggleAuto();
        void onCsConfig();
        void onCsStop();
        void onCsUndo();
        void onCsExit();
        void onCsApprove(List<CsProtocol.Action> actions, boolean sendBack, LinearLayout card);
        void onCsReject(LinearLayout card);
    }
    public static class CsBar {
        public final HorizontalScrollView root;
        public final TextView cwdBtn, autoBtn, stopBtn, undoBtn, exitBtn;
        CsBar(HorizontalScrollView r, TextView c, TextView a, TextView s, TextView u, TextView e) {
            root = r; cwdBtn = c; autoBtn = a; stopBtn = s; undoBtn = u; exitBtn = e;
        }
        public void update(boolean vis, String cwd, boolean auto, boolean running, boolean canUndo) {
            root.setVisibility(vis ? View.VISIBLE : View.GONE);
            String sh = cwd == null || cwd.isEmpty() ? "~" : cwd;
            int ix = sh.lastIndexOf("/"); if (ix > 0 && ix < sh.length() - 1) sh = sh.substring(ix + 1);
            cwdBtn.setText("CS: " + sh + "  \u25be");
            autoBtn.setText(auto ? "Izin: Ozgur" : "Izin: Guvenli");
            autoBtn.setTextColor(auto ? 0xFFFFFFFF : Ui.TEXT);
            autoBtn.setBackground(Ui.ripple(Ui.round(auto ? Ui.ACCENT : Ui.SURFACE, 14, auto ? 0 : Ui.BORDER, auto ? 0 : 1)));
            stopBtn.setVisibility(running ? View.VISIBLE : View.GONE);
            undoBtn.setVisibility(canUndo && !running ? View.VISIBLE : View.GONE);
        }
    }
    public static CsBar buildCsBar(Context c, final Host host) {
        HorizontalScrollView hs = new HorizontalScrollView(c); hs.setHorizontalScrollBarEnabled(false); hs.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(12), Ui.dp(6));
        TextView d = Ui.pill(c, "CS  \u25be", 11, Ui.TEXT, Ui.SURFACE, Ui.BORDER); d.setOnClickListener(v -> host.onCsConfig()); row.addView(d, lpGap(6));
        TextView a = Ui.pill(c, "Izin: Guvenli", 11, Ui.TEXT, Ui.SURFACE, Ui.BORDER); a.setOnClickListener(v -> host.onCsToggleAuto()); row.addView(a, lpGap(6));
        TextView s = Ui.pill(c, "Komutu Durdur", 11, Ui.RED, Ui.SURFACE, Ui.BORDER); s.setOnClickListener(v -> host.onCsStop()); s.setVisibility(View.GONE); row.addView(s, lpGap(6));
        TextView u = Ui.pill(c, "Geri Al", 11, Ui.RED, Ui.SURFACE, Ui.BORDER); u.setOnClickListener(v -> host.onCsUndo()); u.setVisibility(View.GONE); row.addView(u, lpGap(6));
        TextView e = Ui.pill(c, "CS Kapat", 11, Ui.MUTED, Ui.BUBBLE, 0); e.setOnClickListener(v -> host.onCsExit()); row.addView(e, lpGap(0));
        hs.addView(row); return new CsBar(hs, d, a, s, u, e);
    }
    private static LinearLayout.LayoutParams lpGap(int r) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.rightMargin = Ui.dp(r); return l; }
    private static LinearLayout.LayoutParams lpTop(int dp) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = Ui.dp(dp); return l; }
    private static EditText inp(Context c, String hint, String val, boolean multi) {
        EditText e = new EditText(c); e.setHint(hint); e.setText(val); e.setTextSize(Ui.sp(12.5f)); e.setTextColor(Ui.TEXT); e.setHintTextColor(Ui.MUTED);
        e.setBackground(Ui.round(Ui.SURFACE, 10, Ui.BORDER, 1)); e.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
        if (multi) e.setMaxLines(3); else e.setSingleLine(true); return e;
    }
    public static void showConfigDialog(final Context c, final CsManager mgr, final Runnable onStart) {
        LinearLayout box = CodeUi.dialogBox(c); final Dialog d = CodeUi.themedDialog(c, box);
        TextView tt = Ui.text(c, "Codespaces MCP Baglantisi", 16.5f, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        final EditText edPaste = inp(c, "Hizli yapistir (URL: ... / TOKEN: ...)", "", true);
        final EditText edUrl = inp(c, "https://...-7788.app.github.dev", mgr.cfg.getUrl(), false);
        final EditText edTok = inp(c, "Gizli Token (Bearer)", mgr.cfg.getToken(), false);
        edPaste.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int k) {}
            public void onTextChanged(CharSequence s, int a, int b, int k) {}
            public void afterTextChanged(Editable e) { String[] kv = CsConfig.parseQuickPaste(e.toString()); if (kv != null) { if (!kv[0].isEmpty()) edUrl.setText(kv[0]); if (!kv[1].isEmpty()) edTok.setText(kv[1]); } }
        });
        box.addView(edPaste, lpTop(8)); box.addView(edUrl, lpTop(8)); box.addView(edTok, lpTop(8));
        final boolean[] auto = { mgr.cfg.isAuto() }, unlim = { mgr.cfg.isUnlimited() };
        final TextView perm = Ui.pill(c, "", 12, Ui.TEXT, Ui.BUBBLE, Ui.BORDER);
        final TextView unl = Ui.pill(c, "", 12, Ui.TEXT, Ui.BUBBLE, Ui.BORDER);
        Runnable up = () -> {
            perm.setText(auto[0] ? "Izin: Ozgur (Otonom)" : "Izin: Guvenli (Okuma/Cekme serbest)");
            unl.setText(unlim[0] ? "Otonom Tur Siniri: Kapali (Sinirsiz)" : "Otonom Tur Siniri: 8 Islemde Onay Sor");
            unl.setTextColor(unlim[0] ? 0xFFFFFFFF : Ui.TEXT);
            unl.setBackground(Ui.ripple(Ui.round(unlim[0] ? Ui.ACCENT : Ui.BUBBLE, 16, unlim[0] ? 0 : Ui.BORDER, unlim[0] ? 0 : 1)));
        };
        up.run();
        perm.setOnClickListener(v -> { auto[0] = !auto[0]; up.run(); });
        unl.setOnClickListener(v -> { unlim[0] = !unlim[0]; up.run(); });
        box.addView(perm, lpTop(8)); box.addView(unl, lpTop(6));
        final TextView st = Ui.text(c, "Durum: hazir", 11.5f, Ui.MUTED); box.addView(st, lpTop(8));
        TextView test = Ui.pill(c, "Baglantiyi Test Et", 12.5f, Ui.TEXT, Ui.SURFACE, Ui.BORDER);
        test.setOnClickListener(v -> {
            mgr.cfg.setUrl(edUrl.getText().toString()); mgr.cfg.setToken(edTok.getText().toString());
            st.setText("Test ediliyor\u2026"); st.setTextColor(Ui.AMBER);
            CsClient.testHealth(mgr.cfg, (ok, cwd, cs, undo, msg) -> { if (ok) { if (!cwd.isEmpty()) mgr.curCwd = cwd; mgr.canUndo = undo > 0; } st.setText(ok ? "\u2713 Bagli (" + cwd + ")" : "\u2715 " + msg); st.setTextColor(ok ? Ui.GREEN : Ui.RED); });
        }); box.addView(test, lpTop(8));
        TextView go = Ui.pill(c, mgr.mode ? "Kaydet" : "Kaydet ve Baslat", 13, 0xFFFFFFFF, Ui.TEXT, 0);
        go.setOnClickListener(v -> {
            mgr.cfg.setUrl(edUrl.getText().toString()); mgr.cfg.setToken(edTok.getText().toString()); mgr.cfg.setAuto(auto[0]); mgr.cfg.setUnlimited(unlim[0]);
            if (!mgr.cfg.isConfigured()) { Toast.makeText(c, "URL ve Token gerekli", Toast.LENGTH_SHORT).show(); return; }
            d.dismiss(); if (onStart != null) onStart.run();
        }); box.addView(go, lpTop(10));
        TextView no = Ui.pill(c, "Iptal", 12.5f, Ui.MUTED, Ui.SURFACE, Ui.BORDER); no.setOnClickListener(v -> d.dismiss()); box.addView(no, lpTop(6)); d.show();
    }
    public static LinearLayout buildApprovalCard(Context c, final List<CsProtocol.Action> actions, final Host host) {
        final LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.CODEBG, 14, Ui.BORDER, 1)); card.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        card.setLayoutParams(lpTop(8));
        TextView hdr = Ui.text(c, "Codespaces islem onayi (" + actions.size() + ")", 12.5f, Ui.TEXT); hdr.setTypeface(Typeface.DEFAULT, Typeface.BOLD); card.addView(hdr);
        for (final CsProtocol.Action a : actions) {
            LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
            boolean del = a.op == CsProtocol.Op.DELETE;
            r.setBackground(Ui.ripple(Ui.round(Ui.SURFACE, 10, del ? Ui.RED : Ui.BORDER, 1)));
            TextView lt = Ui.text(c, a.label(), 12f, del ? Ui.RED : Ui.TEXT); lt.setTypeface(Typeface.MONOSPACE); r.addView(lt);
            String pv = a.preview(); if (!pv.isEmpty()) { TextView pvt = Ui.text(c, pv + " (onizlemek icin dokun)", 10.5f, Ui.MUTED); pvt.setPadding(0, Ui.dp(2), 0, 0); r.addView(pvt); }
            r.setOnClickListener(v -> showTextModal(c, a.label(), a.detail())); card.addView(r, lpTop(6));
        }
        TextView b1 = Ui.pill(c, "Onayla ve Modele Gonder", 12.5f, 0xFFFFFFFF, Ui.TEXT, 0);
        b1.setOnClickListener(v -> { card.removeAllViews(); card.addView(Ui.text(c, "Calistiriliyor\u2026", 12f, Ui.AMBER)); host.onCsApprove(actions, true, card); });
        card.addView(b1, lpTop(10));
        LinearLayout r2 = new LinearLayout(c);
        TextView b2 = Ui.pill(c, "Sadece Uygula", 12f, Ui.TEXT, Ui.BUBBLE, Ui.BORDER);
        b2.setOnClickListener(v -> { card.removeAllViews(); card.addView(Ui.text(c, "Calistiriliyor\u2026", 12f, Ui.AMBER)); host.onCsApprove(actions, false, card); });
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, Ui.dp(36), 1f); l2.rightMargin = Ui.dp(6); r2.addView(b2, l2);
        TextView b3 = Ui.pill(c, "Reddet", 12f, Ui.MUTED, Ui.SURFACE, Ui.BORDER); b3.setOnClickListener(v -> host.onCsReject(card));
        r2.addView(b3, new LinearLayout.LayoutParams(0, Ui.dp(36), 1f)); card.addView(r2, lpTop(6)); return card;
    }
    public static LinearLayout buildResultCard(Context c, List<CsProtocol.ExecResult> res, boolean sentBack) {
        LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.CODEBG, 14, Ui.BORDER, 1)); card.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        card.setLayoutParams(lpTop(8)); markCardDone(card, res, sentBack); return card;
    }
    public static void markCardDone(LinearLayout card, List<CsProtocol.ExecResult> res, boolean sentBack) {
        final Context c = card.getContext(); card.removeAllViews(); int ok = 0; for (CsProtocol.ExecResult r : res) if (r.ok) ok++;
        String t = (ok == res.size() ? "Tamamlandi" : ok + "/" + res.size() + " basarili") + (sentBack ? " \u00b7 modele iletildi" : "");
        TextView hdr = Ui.text(c, t, 12f, ok == res.size() ? Ui.GREEN : Ui.AMBER); hdr.setTypeface(Typeface.DEFAULT, Typeface.BOLD); card.addView(hdr);
        for (final CsProtocol.ExecResult r : res) {
            TextView tv = Ui.text(c, r.summary() + " (dokun)", 11.5f, r.ok ? Ui.TEXT : Ui.RED);
            tv.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4)); tv.setBackground(Ui.ripple(null));
            tv.setOnClickListener(v -> showTextModal(c, r.action.label(), r.err.isEmpty() ? (r.output.isEmpty() ? "(cikti yok)" : r.output) : r.err));
            card.addView(tv, lpTop(3));
        }
    }
    public static void markCardRejected(LinearLayout card) { card.removeAllViews(); card.addView(Ui.text(card.getContext(), "Islemler reddedildi", 12f, Ui.MUTED)); }
    public static void showTextModal(Context c, String title, String body) {
        LinearLayout box = CodeUi.dialogBox(c); final Dialog d = CodeUi.themedDialog(c, box);
        TextView tt = Ui.text(c, title, 15, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        ScrollView sv = CodeUi.maxScroll(c, 340); TextView tv = Ui.text(c, body == null || body.isEmpty() ? "(bos)" : body, 11f, Ui.TEXT);
        tv.setTypeface(Typeface.MONOSPACE); tv.setBackground(Ui.round(Ui.CODEBG, 10, Ui.BORDER, 1)); tv.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8)); sv.addView(tv);
        box.addView(sv, lpTop(10));
        TextView ok = Ui.pill(c, "Kapat", 13, Ui.TEXT, Ui.BUBBLE, 0); ok.setOnClickListener(v -> d.dismiss()); box.addView(ok, lpTop(12)); d.show();
    }
}
