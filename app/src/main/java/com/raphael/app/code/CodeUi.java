package com.raphael.app.code;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.raphael.app.ui.Ui;
import java.util.*;

public class CodeUi {
    public interface Host {
        void onSelectTab(boolean code);
        void onToggleAuto();
        void onOpenFolderPicker();
        void onOpenFiles();
        void onUndo();
        void onSlashCommand(String cmd);
        void onApproveActions(List<CodeProtocol.Action> actions, boolean sendBack, LinearLayout cardBox);
        void onRejectActions(LinearLayout cardBox);
        void onPreviewAction(CodeProtocol.Action a);
    }
    public interface FolderCb { void onPicked(String relDir); }
    public interface FilesCb { void onTreeCmd(); void onAttachRef(String relPath); void onMdCreated(); }

    public static class ModeTabs {
        public final LinearLayout wrap; public final TextView chatTab, codeTab;
        ModeTabs(LinearLayout w, TextView a, TextView b) { wrap = w; chatTab = a; codeTab = b; }
        public void setCode(boolean code) {
            chatTab.setTextColor(!code ? 0xFFFFFFFF : Ui.MUTED);
            chatTab.setBackground(Ui.ripple(!code ? Ui.round(Ui.TEXT, 13, 0, 0) : null));
            codeTab.setTextColor(code ? 0xFFFFFFFF : Ui.MUTED);
            codeTab.setBackground(Ui.ripple(code ? Ui.round(Ui.ACCENT, 13, 0, 0) : null));
        }
    }

    public static ModeTabs buildModeSwitch(Context c, View.OnClickListener onChat, View.OnClickListener onCode) {
        LinearLayout bar = new LinearLayout(c); bar.setGravity(Gravity.CENTER); bar.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(12), Ui.dp(6));
        LinearLayout seg = new LinearLayout(c); seg.setBackground(Ui.round(Ui.BUBBLE, 16, Ui.BORDER, 1)); seg.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3));
        TextView a = tabPill(c, "Raphael", onChat), b = tabPill(c, "Code", onCode);
        seg.addView(a, new LinearLayout.LayoutParams(Ui.dp(88), Ui.dp(26)));
        seg.addView(b, new LinearLayout.LayoutParams(Ui.dp(88), Ui.dp(26)));
        bar.addView(seg, new LinearLayout.LayoutParams(-2, -2));
        ModeTabs mt = new ModeTabs(bar, a, b); mt.setCode(false); return mt;
    }

    private static TextView tabPill(Context c, String txt, View.OnClickListener l) {
        TextView t = Ui.text(c, txt, 11.5f, Ui.MUTED); t.setGravity(Gravity.CENTER); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setOnClickListener(l); return t;
    }

    public static class CodeBar {
        public final HorizontalScrollView root; public final TextView dirBtn, autoBtn, filesBtn, undoBtn;
        CodeBar(HorizontalScrollView r, TextView d, TextView a, TextView f, TextView u) { root = r; dirBtn = d; autoBtn = a; filesBtn = f; undoBtn = u; }
        public void update(boolean vis, String dir, boolean auto, boolean canUndo) {
            root.setVisibility(vis ? View.VISIBLE : View.GONE);
            dirBtn.setText("Klasör: " + dir + "  ▾");
            autoBtn.setText(auto ? "İzin: Özgür" : "İzin: Güvenli");
            autoBtn.setTextColor(auto ? 0xFFFFFFFF : Ui.TEXT);
            autoBtn.setBackground(Ui.ripple(Ui.round(auto ? Ui.ACCENT : Ui.SURFACE, 14, auto ? 0 : Ui.BORDER, auto ? 0 : 1)));
            undoBtn.setVisibility(canUndo ? View.VISIBLE : View.GONE);
        }
    }

    public static CodeBar buildCodeBar(Context c, final Host host) {
        HorizontalScrollView hs = new HorizontalScrollView(c); hs.setHorizontalScrollBarEnabled(false); hs.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(12), Ui.dp(6));
        TextView d = Ui.pill(c, "Klasör: raphael  ▾", 11, Ui.TEXT, Ui.SURFACE, Ui.BORDER); d.setOnClickListener(v -> host.onOpenFolderPicker()); row.addView(d, lpGap(6));
        TextView a = Ui.pill(c, "İzin: Güvenli", 11, Ui.TEXT, Ui.SURFACE, Ui.BORDER); a.setOnClickListener(v -> host.onToggleAuto()); row.addView(a, lpGap(6));
        TextView f = Ui.pill(c, "Dosyalar", 11, Ui.TEXT, Ui.BUBBLE, 0); f.setOnClickListener(v -> host.onOpenFiles()); row.addView(f, lpGap(6));
        TextView u = Ui.pill(c, "Geri Al", 11, Ui.RED, Ui.SURFACE, Ui.BORDER); u.setOnClickListener(v -> host.onUndo()); u.setVisibility(View.GONE); row.addView(u, lpGap(0));
        hs.addView(row); return new CodeBar(hs, d, a, f, u);
    }

    private static LinearLayout.LayoutParams lpGap(int rDp) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.rightMargin = Ui.dp(rDp); return l; }

    public static void updateSlashBar(HorizontalScrollView wrap, LinearLayout row, String typed, boolean codeMode, final Host host) {
        row.removeAllViews(); String s = typed == null ? "" : typed.trim();
        if (!s.startsWith("/") || s.contains(" ") || s.contains("\n")) { wrap.setVisibility(View.GONE); return; }
        String q = s.toLowerCase(Locale.US);
        String[][] cmds = codeMode ? new String[][]{{"/oku", "Klasör haritası"}, {"/files", "Dosya gezgini"}, {"/undo", "Son işlemi geri al"}, {"/chat", "Normal sohbete dön"}} : new String[][]{{"/code", "Raphael Code aç"}, {"/files", "Dosya gezgini"}, {"/oku", "Code ile klasör oku"}};
        int n = 0;
        for (final String[] cm : cmds) {
            if (!cm[0].startsWith(q)) continue; n++;
            TextView p = Ui.pill(row.getContext(), cm[0] + " · " + cm[1], 11.5f, Ui.TEXT, Ui.BUBBLE, Ui.BORDER);
            p.setOnClickListener(v -> host.onSlashCommand(cm[0]));
            LinearLayout.LayoutParams l = lpGap(6); l.bottomMargin = Ui.dp(4); row.addView(p, l);
        }
        wrap.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }
    public static LinearLayout buildApprovalCard(Context c, final List<CodeProtocol.Action> actions, final Host host) {
        final LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.CODEBG, 14, Ui.BORDER, 1)); card.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = Ui.dp(8); card.setLayoutParams(cl);
        TextView hdr = Ui.text(c, "Onay bekleyen işlemler (" + actions.size() + ")", 12.5f, Ui.TEXT); hdr.setTypeface(Typeface.DEFAULT, Typeface.BOLD); card.addView(hdr);
        for (final CodeProtocol.Action a : actions) {
            LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.VERTICAL); r.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8));
            boolean del = a.op == CodeProtocol.Op.DELETE;
            r.setBackground(Ui.ripple(Ui.round(Ui.SURFACE, 10, del ? Ui.RED : Ui.BORDER, 1)));
            r.addView(Ui.text(c, a.label(), 12.5f, del ? Ui.RED : Ui.TEXT));
            String pv = a.preview(); if (!pv.isEmpty()) { TextView pvt = Ui.text(c, pv + " (önizlemek için dokun)", 10.5f, Ui.MUTED); LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2); pl.topMargin = Ui.dp(2); r.addView(pvt, pl); }
            r.setOnClickListener(v -> host.onPreviewAction(a));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = Ui.dp(6); card.addView(r, rl);
        }
        LinearLayout btns = new LinearLayout(c); btns.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = Ui.dp(10);
        TextView b1 = Ui.pill(c, "Onayla ve Modele Bildir", 12.5f, 0xFFFFFFFF, Ui.TEXT, 0); b1.setOnClickListener(v -> host.onApproveActions(actions, true, card)); btns.addView(b1, new LinearLayout.LayoutParams(-1, Ui.dp(38)));
        LinearLayout row2 = new LinearLayout(c); LinearLayout.LayoutParams r2l = new LinearLayout.LayoutParams(-1, -2); r2l.topMargin = Ui.dp(6);
        TextView b2 = Ui.pill(c, "Sadece Uygula", 12f, Ui.TEXT, Ui.BUBBLE, Ui.BORDER); b2.setOnClickListener(v -> host.onApproveActions(actions, false, card));
        LinearLayout.LayoutParams b2l = new LinearLayout.LayoutParams(0, Ui.dp(36), 1f); b2l.rightMargin = Ui.dp(6); row2.addView(b2, b2l);
        TextView b3 = Ui.pill(c, "Reddet", 12f, Ui.MUTED, Ui.SURFACE, Ui.BORDER); b3.setOnClickListener(v -> host.onRejectActions(card));
        row2.addView(b3, new LinearLayout.LayoutParams(0, Ui.dp(36), 1f)); btns.addView(row2, r2l); card.addView(btns, bl);
        return card;
    }

    public static void markCardDone(LinearLayout card, List<CodeProtocol.ExecResult> res, boolean sentBack, boolean hasUndo, final Runnable onUndo) {
        Context c = card.getContext(); card.removeAllViews();
        int ok = 0; for (CodeProtocol.ExecResult r : res) if (r.ok) ok++;
        String title = (ok == res.size() ? "Uygulandı" : ok + "/" + res.size() + " başarılı") + (sentBack ? " · modele iletildi" : "");
        TextView hdr = Ui.text(c, title, 12f, ok == res.size() ? Ui.GREEN : Ui.AMBER); hdr.setTypeface(Typeface.DEFAULT, Typeface.BOLD); card.addView(hdr);
        for (CodeProtocol.ExecResult r : res) {
            TextView tv = Ui.text(c, (r.ok ? "✓ " : "✕ ") + r.summary, 11.5f, r.ok ? Ui.TEXT : Ui.RED);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = Ui.dp(3); card.addView(tv, l);
        }
        if (hasUndo && onUndo != null) {
            final TextView ub = Ui.pill(c, "Son İşlemi Geri Al", 11.5f, Ui.RED, Ui.SURFACE, Ui.BORDER);
            ub.setOnClickListener(v -> { ub.setEnabled(false); onUndo.run(); });
            LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-2, Ui.dp(34)); ul.topMargin = Ui.dp(8); card.addView(ub, ul);
        }
    }

    public static void markCardRejected(LinearLayout card) {
        Context c = card.getContext(); card.removeAllViews();
        card.addView(Ui.text(c, "İşlemler reddedildi (diske dokunulmadı)", 12f, Ui.MUTED));
    }

    public static Dialog themedDialog(Context c, LinearLayout box) {
        Dialog d = new Dialog(c); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout wrap = new LinearLayout(c); wrap.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(16)); wrap.addView(box, new LinearLayout.LayoutParams(-1, -2));
        d.setContentView(wrap); Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawable(new ColorDrawable(0)); w.setGravity(Gravity.CENTER); w.setDimAmount(0.4f); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
        return d;
    }

    public static LinearLayout dialogBox(Context c) {
        LinearLayout box = new LinearLayout(c); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.BG, 22, Ui.BORDER, 1)); box.setPadding(Ui.dp(18), Ui.dp(18), Ui.dp(18), Ui.dp(16)); return box;
    }

    public static void showConfirmDialog(Context c, String title, String msg, String okTxt, String cancelTxt, final Runnable onOk) {
        LinearLayout box = dialogBox(c); final Dialog d = themedDialog(c, box);
        TextView tt = Ui.text(c, title, 17, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        TextView mt = Ui.text(c, msg, 13, Ui.MUTED); mt.setLineSpacing(0, 1.28f); LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = Ui.dp(8); box.addView(mt, ml);
        TextView b1 = Ui.pill(c, okTxt, 13.5f, 0xFFFFFFFF, Ui.TEXT, 0); b1.setOnClickListener(v -> { d.dismiss(); onOk.run(); });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-1, Ui.dp(42)); l1.topMargin = Ui.dp(16); box.addView(b1, l1);
        if (cancelTxt != null && !cancelTxt.isEmpty()) {
            TextView b2 = Ui.pill(c, cancelTxt, 13, Ui.MUTED, Ui.SURFACE, Ui.BORDER); b2.setOnClickListener(v -> d.dismiss());
            LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-1, Ui.dp(40)); l2.topMargin = Ui.dp(8); box.addView(b2, l2);
        } d.show();
    }

    public static void showActionPreview(Context c, CodeProtocol.Action a) {
        LinearLayout box = dialogBox(c); final Dialog d = themedDialog(c, box);
        TextView tt = Ui.text(c, a.label(), 15.5f, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        String body = a.op == CodeProtocol.Op.WRITE ? a.text1 : a.op == CodeProtocol.Op.PATCH ? "--- ESKİ ---\n" + a.text1 + "\n\n+++ YENİ +++\n" + a.text2 : a.arg1 + (a.arg2.isEmpty() ? "" : "\n" + a.arg2);
        ScrollView sv = maxScroll(c, 320); TextView tv = Ui.text(c, body.isEmpty() ? "(detay yok)" : body, 11.5f, Ui.TEXT); tv.setTypeface(Typeface.MONOSPACE);
        tv.setBackground(Ui.round(Ui.CODEBG, 10, Ui.BORDER, 1)); tv.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8)); sv.addView(tv);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(10); box.addView(sv, sl);
        TextView ok = Ui.pill(c, "Kapat", 13, Ui.TEXT, Ui.BUBBLE, 0); ok.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, Ui.dp(40)); ol.topMargin = Ui.dp(12); box.addView(ok, ol); d.show();
    }

    public static ScrollView maxScroll(Context c, final int maxDp) {
        ScrollView sv = new ScrollView(c) { @Override protected void onMeasure(int wS, int hS) { int m = Ui.dp(maxDp); if (MeasureSpec.getMode(hS) == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(hS) > m) hS = MeasureSpec.makeMeasureSpec(m, MeasureSpec.AT_MOST); super.onMeasure(wS, hS); } };
        sv.setVerticalScrollBarEnabled(false); return sv;
    }
    public static void showFolderPicker(final Context c, final CodeWorkspace ws, final FolderCb cb) {
        LinearLayout box = dialogBox(c); final Dialog d = themedDialog(c, box);
        TextView tt = Ui.text(c, "Çalışma klasörü seç", 16.5f, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        final TextView pathTv = Ui.text(c, "", 12, Ui.ACCENT); LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2); pl.topMargin = Ui.dp(4); box.addView(pathTv, pl);
        ScrollView sv = maxScroll(c, 260); final LinearLayout list = new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(10); box.addView(sv, sl);
        final String[] cur = { ws.getRelDir() }; final Runnable[] ref = new Runnable[1];
        ref[0] = () -> {
            pathTv.setText(cur[0].isEmpty() ? "raphael" : "raphael/" + cur[0]); list.removeAllViews();
            if (!cur[0].isEmpty()) { TextView up = rowItem(c, ".. (Üst klasör)", Ui.MUTED, Ui.SURFACE); up.setOnClickListener(v -> { int i = cur[0].lastIndexOf("/"); cur[0] = i > 0 ? cur[0].substring(0, i) : ""; ref[0].run(); }); list.addView(up, lpRow()); }
            int cnt = 0;
            for (final CodeWorkspace.FileItem it : ws.listBrowse(cur[0])) { if (!it.isDir) continue; cnt++; TextView r = rowItem(c, "Klasör: " + it.name, Ui.TEXT, Ui.SURFACE); r.setOnClickListener(v -> { cur[0] = it.relPath; ref[0].run(); }); list.addView(r, lpRow()); }
            if (cnt == 0) list.addView(Ui.text(c, "(Alt klasör yok)", 12, Ui.MUTED));
        };
        LinearLayout row1 = new LinearLayout(c); LinearLayout.LayoutParams r1l = new LinearLayout.LayoutParams(-1, -2); r1l.topMargin = Ui.dp(12);
        TextView nf = Ui.pill(c, "+ Yeni klasör", 12, Ui.TEXT, Ui.BUBBLE, Ui.BORDER); nf.setOnClickListener(v -> askNewFolder(c, ws, cur[0], ref[0]));
        LinearLayout.LayoutParams nfl = new LinearLayout.LayoutParams(0, Ui.dp(38), 1f); nfl.rightMargin = Ui.dp(6); row1.addView(nf, nfl);
        TextView sel = Ui.pill(c, "Bu klasörü seç", 12.5f, 0xFFFFFFFF, Ui.TEXT, 0); sel.setOnClickListener(v -> { d.dismiss(); cb.onPicked(cur[0]); });
        row1.addView(sel, new LinearLayout.LayoutParams(0, Ui.dp(38), 1f)); box.addView(row1, r1l);
        TextView cls = Ui.pill(c, "İptal", 12.5f, Ui.MUTED, Ui.SURFACE, Ui.BORDER); cls.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, Ui.dp(38)); cl.topMargin = Ui.dp(6); box.addView(cls, cl); ref[0].run(); d.show();
    }
    private static void askNewFolder(Context c, final CodeWorkspace ws, final String parentRel, final Runnable onDone) {
        LinearLayout box = dialogBox(c); final Dialog d = themedDialog(c, box);
        TextView tt = Ui.text(c, "Yeni klasör", 16, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        final EditText ed = new EditText(c); ed.setHint("örn: minecraft"); ed.setTextSize(Ui.sp(14)); ed.setTextColor(Ui.TEXT);
        ed.setBackground(Ui.round(Ui.SURFACE, 12, Ui.BORDER, 1)); ed.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10)); ed.setSingleLine(true);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2); el.topMargin = Ui.dp(10); box.addView(ed, el);
        LinearLayout row = new LinearLayout(c); LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = Ui.dp(12);
        TextView no = Ui.pill(c, "Vazgeç", 12.5f, Ui.MUTED, Ui.SURFACE, Ui.BORDER); no.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, Ui.dp(38), 1f); nl.rightMargin = Ui.dp(6); row.addView(no, nl);
        TextView ok = Ui.pill(c, "Oluştur", 12.5f, 0xFFFFFFFF, Ui.TEXT, 0);
        ok.setOnClickListener(v -> { if (ws.createSubdir(parentRel, ed.getText().toString())) { d.dismiss(); onDone.run(); } else Toast.makeText(c, "Klasör oluşturulamadı", Toast.LENGTH_SHORT).show(); });
        row.addView(ok, new LinearLayout.LayoutParams(0, Ui.dp(38), 1f)); box.addView(row, rl); d.show();
    }

    public static void showFilesExplorer(final Context c, final CodeWorkspace ws, final FilesCb cb) {
        LinearLayout box = dialogBox(c); final Dialog d = themedDialog(c, box);
        TextView tt = Ui.text(c, "Çalışma dosyaları", 16.5f, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        final TextView sub = Ui.text(c, "", 12, Ui.MUTED); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(4); box.addView(sub, sl);
        ScrollView sv = maxScroll(c, 280); final LinearLayout list = new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL); sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, -2); svl.topMargin = Ui.dp(10); box.addView(sv, svl);
        final String base = ws.getRelDir(); final String[] subRel = { "" }; final Runnable[] ref = new Runnable[1];
        ref[0] = () -> {
            String full = base.isEmpty() ? subRel[0] : (subRel[0].isEmpty() ? base : base + "/" + subRel[0]);
            sub.setText(full.isEmpty() ? "raphael" : "raphael/" + full); list.removeAllViews();
            if (!subRel[0].isEmpty()) { TextView up = rowItem(c, ".. (Üst klasör)", Ui.MUTED, Ui.SURFACE); up.setOnClickListener(v -> { int i = subRel[0].lastIndexOf("/"); subRel[0] = i > 0 ? subRel[0].substring(0, i) : ""; ref[0].run(); }); list.addView(up, lpRow()); }
            List<CodeWorkspace.FileItem> items = ws.listBrowse(full);
            for (final CodeWorkspace.FileItem it : items) {
                final String relAct = subRel[0].isEmpty() ? it.name : subRel[0] + "/" + it.name;
                String label = it.isDir ? "[Klasör] " + it.name : it.name + " · " + CodeWorkspace.sizeStr(it.size);
                TextView r = rowItem(c, label, it.isDir ? Ui.ACCENT : Ui.TEXT, Ui.SURFACE);
                r.setOnClickListener(v -> { if (it.isDir) { subRel[0] = relAct; ref[0].run(); } else showFileViewer(c, ws, relAct, () -> { d.dismiss(); cb.onAttachRef(relAct); }); });
                list.addView(r, lpRow());
            }
            if (items.isEmpty()) list.addView(Ui.text(c, "(Klasör boş)", 12, Ui.MUTED));
        };
        LinearLayout row1 = new LinearLayout(c); LinearLayout.LayoutParams r1l = new LinearLayout.LayoutParams(-1, -2); r1l.topMargin = Ui.dp(12);
        TextView treeB = Ui.pill(c, "/oku Harita Gönder", 12, 0xFFFFFFFF, Ui.TEXT, 0); treeB.setOnClickListener(v -> { d.dismiss(); cb.onTreeCmd(); });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, Ui.dp(38), 1f); tl.rightMargin = Ui.dp(6); row1.addView(treeB, tl);
        if (!ws.hasRaphaelMd()) {
            TextView mdB = Ui.pill(c, "+ RAPHAEL.md", 12, Ui.TEXT, Ui.BUBBLE, Ui.BORDER);
            mdB.setOnClickListener(v -> { if (ws.createStarterRaphaelMd()) { ref[0].run(); cb.onMdCreated(); } });
            row1.addView(mdB, new LinearLayout.LayoutParams(0, Ui.dp(38), 1f));
        }
        box.addView(row1, r1l);
        TextView cls = Ui.pill(c, "Kapat", 12.5f, Ui.MUTED, Ui.SURFACE, Ui.BORDER); cls.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, Ui.dp(38)); cl.topMargin = Ui.dp(6); box.addView(cls, cl); ref[0].run(); d.show();
    }

    private static void showFileViewer(Context c, CodeWorkspace ws, final String relPath, final Runnable onAdd) {
        LinearLayout box = dialogBox(c); final Dialog d = themedDialog(c, box);
        TextView tt = Ui.text(c, relPath, 15, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        CodeWorkspace.Result r = ws.read(relPath, 1, 200);
        ScrollView sv = maxScroll(c, 320); TextView tv = Ui.text(c, r.output, 11, Ui.TEXT); tv.setTypeface(Typeface.MONOSPACE);
        tv.setBackground(Ui.round(Ui.CODEBG, 10, Ui.BORDER, 1)); tv.setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(10), Ui.dp(8)); sv.addView(tv);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(10); box.addView(sv, sl);
        LinearLayout row = new LinearLayout(c); LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = Ui.dp(12);
        TextView add = Ui.pill(c, "Sohbete Ekle", 12, 0xFFFFFFFF, Ui.TEXT, 0); add.setOnClickListener(v -> { d.dismiss(); onAdd.run(); });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, Ui.dp(38), 1f); al.rightMargin = Ui.dp(6); row.addView(add, al);
        TextView cls = Ui.pill(c, "Kapat", 12, Ui.MUTED, Ui.SURFACE, Ui.BORDER); cls.setOnClickListener(v -> d.dismiss());
        row.addView(cls, new LinearLayout.LayoutParams(0, Ui.dp(38), 1f)); box.addView(row, rl); d.show();
    }

    private static TextView rowItem(Context c, String txt, int col, int bg) {
        TextView t = Ui.text(c, txt, 13, col); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10)); t.setBackground(Ui.ripple(Ui.round(bg, 10, Ui.BORDER, 1))); return t;
    }

    private static LinearLayout.LayoutParams lpRow() { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.bottomMargin = Ui.dp(6); return l; }



}

