package com.raphael.app.code;
import java.util.*;
public class CodeProtocol {
    public enum Op { TREE, LIST, READ, SEARCH, MKDIR, WRITE, PATCH, MOVE, DELETE }
    public static class Action {
        public Op op; public String arg1 = "", arg2 = "", text1 = "", text2 = ""; public int lineStart = 0, lineEnd = 0;
        public Action(Op o) { op = o; }
        public boolean isReadOnly() { return op == Op.TREE || op == Op.LIST || op == Op.READ || op == Op.SEARCH; }
        public String label() {
            switch (op) {
                case TREE: return "Ağaç: " + (arg1.isEmpty() ? "." : arg1);
                case LIST: return "Listele: " + (arg1.isEmpty() ? "." : arg1);
                case READ: return "Oku: " + arg1 + (lineStart > 0 ? " (" + lineStart + "-" + lineEnd + ")" : "");
                case SEARCH: return "Ara: " + arg1 + (arg2.isEmpty() ? "" : " [" + arg2 + "]");
                case MKDIR: return "Klasör aç: " + arg1;
                case WRITE: return "Yaz: " + arg1;
                case PATCH: return "Düzenle: " + arg1;
                case MOVE: return "Taşı: " + arg1 + " -> " + arg2;
                case DELETE: return "Sil: " + arg1;
            } return op.name();
        }
        public String preview() {
            if (op == Op.WRITE) { int n = text1.isEmpty() ? 0 : text1.split("\n", -1).length; return n + " satır yazılacak"; }
            if (op == Op.PATCH) { int d = text1.isEmpty() ? 0 : text1.split("\n", -1).length, a = text2.isEmpty() ? 0 : text2.split("\n", -1).length; return "-" + d + " / +" + a + " satır yama"; }
            return "";
        }
    }
    public static class ExecResult { public final Action action; public final boolean ok; public final String summary, output; public ExecResult(Action a, boolean o, String s, String out) { action = a; ok = o; summary = s; output = out; } }
    public static String buildSystemPrompt(String displayDir, boolean autoMode, boolean hasRaphaelMd) {
        String modeNote = autoMode ? "ÖZGÜR (OTONOM) MOD AKTİF: Okuma, yazma, yama, taşıma ve silme komutların aktif çalışma klasöründe doğrudan uygulanır ve sonuçlar sana geri iletilir." : "GÜVENLİ (ONAYLI) MOD AKTİF: @tree, @list, @read ve @search komutların otomatik çalışır. @mkdir, @write, @patch, @move ve @delete komutların ise kullanıcı onayından sonra uygulanır.";
        String mdHint = hasRaphaelMd ? "Klasörde RAPHAEL.md mevcut; göreve başlamadan önce @read RAPHAEL.md ile proje notlarını oku." : "Klasörde henüz RAPHAEL.md yok (@read RAPHAEL.md çağırma). Proje büyürse önemli notları RAPHAEL.md dosyasına kaydedebilirsin.";
        return "Sen Raphael Code asistanısın. Kullanıcının Android cihazındaki yerel çalışma alanında dosya işlemleri yapabiliyorsun.\n"
            + "AKTİF ÇALIŞMA DİZİNİ: " + displayDir + "\n"
            + "GÜVENLİK: Tüm dosya yollarını bu aktif klasöre göre göreli yaz (ör. index.html, src/app.js). Üst klasörlere (../) veya dış dizinlere erişemezsin.\n"
            + modeNote + "\n\n"
            + "ÇALIŞMA DİSİPLİNİ:\n"
            + "1. Klasörü tanımak için @tree kullan (dosya ve alt klasörleri tek seferde görürsün); gereksiz yere tüm dosyaları okuma.\n"
            + "2. " + mdHint + "\n"
            + "3. Büyük dosyalarda @search ile ilgili yeri bul, @read dosya 1-120 şeklinde sadece gereken satırları oku.\n"
            + "4. Küçük değişikliklerde bütün dosyayı @write ile baştan yazmak yerine @patch kullan.\n"
            + "5. Komut bloğu yazdıktan sonra sonucu kendin tahmin etme; uygulamadan gelecek [RAPHAEL SONUÇLARI] yanıtını bekle.\n\n"
            + "KOMUT DİLİ: Sadece işlem yaptırmak istediğinde cevabına rph kod bloğu ekle (normal kod örnekleri için rph kullanma):\n"
            + "````rph\n@tree [alt_klasor]\n@list [alt_klasor]\n@read dosya.txt [baslangic-bitis]\n@search kelime [alt_klasor]\n@mkdir klasor\n@move eski yeni\n@delete dosya\n@write dosya.txt\n<<<EOF\niçerik\n<<<END\n@patch dosya.txt\n<<<OLD\neski satırlar\n<<<NEW\nyeni satırlar\n<<<END\n````";
    }
    public static List<Action> parseActions(String md) {
        List<Action> out = new ArrayList<>(); if (md == null || md.isEmpty()) return out;
        String[] L = md.replace("\r", "").split("\n", -1); boolean inRph = false; char fc = 0; int fl = 0, mode = 0; Action cur = null; StringBuilder buf = new StringBuilder();
        for (int i = 0; i < L.length; i++) {
            String ln = L[i], t = ln.trim();
            if (!inRph) {
                if (t.length() >= 3 && (t.charAt(0) == 96 || t.charAt(0) == 126)) {
                    char ch = t.charAt(0); int k = 0; while (k < t.length() && t.charAt(k) == ch) k++;
                    if (k >= 3) { String tag = t.substring(k).trim().toLowerCase(Locale.US); if (tag.equals("rph") || tag.equals("raphael")) { inRph = true; fc = ch; fl = k; mode = 0; cur = null; buf.setLength(0); } }
                } continue;
            }
            if (t.length() >= fl && allChar(t, fc)) { if (mode == 0 || !hasEndAhead(L, i + 1, fc, fl)) { inRph = false; mode = 0; cur = null; buf.setLength(0); continue; } }
            if (mode == 1) {
                if (t.equals("<<<NEW") && cur != null && cur.op == Op.PATCH) { cur.text1 = buf.toString(); buf.setLength(0); mode = 2; }
                else if (t.equals("<<<END") && cur != null) { cur.text1 = buf.toString(); out.add(cur); cur = null; mode = 0; buf.setLength(0); }
                else { if (buf.length() > 0) buf.append("\n"); buf.append(ln); } continue;
            }
            if (mode == 2) {
                if (t.equals("<<<END") && cur != null) { cur.text2 = buf.toString(); out.add(cur); cur = null; mode = 0; buf.setLength(0); }
                else { if (buf.length() > 0) buf.append("\n"); buf.append(ln); } continue;
            }
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.equals("<<<EOF") && cur != null && cur.op == Op.WRITE) { mode = 1; buf.setLength(0); continue; }
            if (t.equals("<<<OLD") && cur != null && cur.op == Op.PATCH) { mode = 1; buf.setLength(0); continue; }
            cur = null; List<String> ps = splitArgs(t); if (ps.isEmpty()) continue;
            String cmd = ps.get(0).toLowerCase(Locale.US); if (cmd.startsWith("@")) cmd = cmd.substring(1);
            String a1 = ps.size() > 1 ? ps.get(1) : "", a2 = ps.size() > 2 ? ps.get(2) : "";
            if (cmd.equals("tree") || cmd.equals("oku")) { Action a = new Action(Op.TREE); a.arg1 = a1; out.add(a); }
            else if (cmd.equals("list") || cmd.equals("ls")) { Action a = new Action(Op.LIST); a.arg1 = a1; out.add(a); }
            else if (cmd.equals("read") || cmd.equals("cat")) { Action a = new Action(Op.READ); a.arg1 = a1; parseRange(a2, a); out.add(a); }
            else if (cmd.equals("search") || cmd.equals("grep")) { Action a = new Action(Op.SEARCH); a.arg1 = a1; a.arg2 = a2; out.add(a); }
            else if (cmd.equals("mkdir")) { Action a = new Action(Op.MKDIR); a.arg1 = a1; out.add(a); }
            else if (cmd.equals("move") || cmd.equals("mv")) { Action a = new Action(Op.MOVE); a.arg1 = a1; a.arg2 = a2; out.add(a); }
            else if (cmd.equals("delete") || cmd.equals("del") || cmd.equals("rm")) { Action a = new Action(Op.DELETE); a.arg1 = a1; out.add(a); }
            else if (cmd.equals("write")) { cur = new Action(Op.WRITE); cur.arg1 = a1; }
            else if (cmd.equals("patch")) { cur = new Action(Op.PATCH); cur.arg1 = a1; }
        } return out;
    }
    private static boolean allChar(String s, char ch) { for (int i = 0; i < s.length(); i++) if (s.charAt(i) != ch) return false; return true; }
    private static boolean hasEndAhead(String[] L, int from, char ch, int fl) { for (int k = from; k < L.length; k++) { String s = L[k].trim(); if (s.equals("<<<END")) return true; if (s.length() >= fl && allChar(s, ch) && s.length() > 3) return false; } return false; }
    private static void parseRange(String s, Action a) { if (s == null || s.isEmpty()) return; String[] p = s.split("[-:]"); try { a.lineStart = Integer.parseInt(p[0].trim()); if (p.length > 1) a.lineEnd = Integer.parseInt(p[1].trim()); } catch (Exception ignored) {} }
    private static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>(); StringBuilder b = new StringBuilder(); boolean q = false; char qc = 0;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i);
            if (q) { if (c == qc) q = false; else b.append(c); }
            else if (c == 34 || c == 39) { q = true; qc = c; }
            else if (Character.isWhitespace(c)) { if (b.length() > 0) { out.add(b.toString()); b.setLength(0); } }
            else b.append(c);
        } if (b.length() > 0) out.add(b.toString()); return out;
    }
    public static boolean hasMutating(List<Action> list) { for (Action a : list) if (!a.isReadOnly()) return true; return false; }
    public static ExecResult executeOne(CodeWorkspace ws, Action a) {
        CodeWorkspace.Result r;
        switch (a.op) {
            case TREE: r = ws.tree(a.arg1); break; case LIST: r = ws.list(a.arg1); break;
            case READ: r = ws.read(a.arg1, a.lineStart, a.lineEnd); break; case SEARCH: r = ws.search(a.arg1, a.arg2); break;
            case MKDIR: r = ws.mkdir(a.arg1); break; case WRITE: r = ws.write(a.arg1, a.text1); break;
            case PATCH: r = ws.patch(a.arg1, a.text1, a.text2); break; case MOVE: r = ws.move(a.arg1, a.arg2); break;
            case DELETE: r = ws.delete(a.arg1); break; default: r = new CodeWorkspace.Result(false, "Bilinmeyen", "HATA"); break;
        } return new ExecResult(a, r.ok, r.summary, r.output);
    }
    public static String formatFeedback(List<ExecResult> res, CodeWorkspace ws) {
        StringBuilder sb = new StringBuilder("[RAPHAEL SONUÇLARI | Dizin: " + ws.getDisplayPath() + "]\n\n");
        for (ExecResult er : res) sb.append("### ").append(er.action.label()).append(er.ok ? " [OK]" : " [HATA]").append("\n").append(er.output).append("\n\n");
        return sb.toString().trim();
    }
}
