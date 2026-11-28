package com.raphael.app.code;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.*;
public class CodeWorkspace {
    public static class Result { public final boolean ok; public final String summary, output; public final List<File> files; public Result(boolean o, String s, String out) { this(o, s, out, Collections.emptyList()); } public Result(boolean o, String s, String out, List<File> fs) { ok = o; summary = s; output = out; files = fs; } }
    public static class FileItem { public final String name, relPath; public final boolean isDir; public final long size; public FileItem(String n, String r, boolean d, long s) { name = n; relPath = r; isDir = d; size = s; } }
    private final File root; private String activeRel = "";
    public CodeWorkspace(String relDir) { root = new File(Environment.getExternalStorageDirectory(), "raphael"); if (!root.exists()) root.mkdirs(); setRelDir(relDir, false); }
    public File rootDir() { return root; } public String getRelDir() { return activeRel; }
    public String getDisplayPath() { return activeRel.isEmpty() ? "raphael" : "raphael/" + activeRel; }
    public File activeDir() { return activeRel.isEmpty() ? root : new File(root, activeRel); }
    public boolean setRelDir(String rel, boolean create) {
        String p = cleanRel(rel); if (p.equals("raphael")) p = ""; else if (p.startsWith("raphael/")) p = p.substring(8);
        if (p.isEmpty()) { activeRel = ""; return true; }
        try { File r = root.getCanonicalFile(), d = new File(r, p).getCanonicalFile();
            if (!d.getPath().startsWith(r.getPath() + File.separator) || skipName(d.getName())) return false;
            if (create && !d.exists()) d.mkdirs(); if (!d.isDirectory()) return false;
            activeRel = relOf(r, d); return true;
        } catch (Exception e) { return false; }
    }
    public boolean createSubdir(String parentRel, String name) {
        String n = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_"); if (n.isEmpty() || n.equals(".") || n.equals("..") || skipName(n)) return false;
        String p = cleanRel(parentRel); String full = p.isEmpty() ? n : p + "/" + n;
        try { File r = root.getCanonicalFile(), d = new File(r, full).getCanonicalFile(); return d.getPath().startsWith(r.getPath() + File.separator) && (d.exists() ? d.isDirectory() : d.mkdirs()); } catch (Exception e) { return false; }
    }
    public List<FileItem> listBrowse(String relFromRoot) {
        List<FileItem> out = new ArrayList<>(); String p = cleanRel(relFromRoot);
        try { File r = root.getCanonicalFile(), d = (p.isEmpty() ? r : new File(r, p)).getCanonicalFile();
            if (!d.getPath().equals(r.getPath()) && !d.getPath().startsWith(r.getPath() + File.separator)) return out;
            File[] ks = d.listFiles(); if (ks == null) return out;
            Arrays.sort(ks, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
            for (File f : ks) { if (skipName(f.getName())) continue; out.add(new FileItem(f.getName(), relOf(r, f), f.isDirectory(), f.isDirectory() ? 0 : f.length())); }
        } catch (Exception ignored) {} return out;
    }
    public boolean hasRaphaelMd() { return new File(activeDir(), "RAPHAEL.md").isFile(); }
    public boolean createStarterRaphaelMd() {
        File f = new File(activeDir(), "RAPHAEL.md"); if (f.exists()) return true;
        String txt = "# " + getDisplayPath() + "\n\n## Proje Özeti\n- Amaç: (kısa özet)\n\n## Dosya Haritası\n- (önemli dosyalar)\n\n## Öğrenilen Notlar\n- (çözülen hatalar ve kurallar)\n";
        try { writeUtf8(f, txt); return true; } catch (Exception e) { return false; }
    }
    private static String cleanRel(String s) { String p = s == null ? "" : s.trim().replace((char) 92, (char) 47); while (p.startsWith("./")) p = p.substring(2); while (p.startsWith("/")) p = p.substring(1); while (p.endsWith("/")) p = p.substring(0, p.length() - 1); return p; }
    private static boolean skipName(String n) { return n.equals(".git") || n.equals(".gradle") || n.equals("build") || n.equals("node_modules") || n.equals(".raphael_undo"); }
    private static String relOf(File base, File target) { String b = base.getPath(), t = target.getPath(); return t.equals(b) ? "" : t.substring(b.length() + 1).replace(File.separatorChar, (char) 47); }
    private File resolve(String req, boolean allowSelf) throws Exception {
        String p = cleanRel(req), disp = getDisplayPath();
        if (p.equals(disp) || p.equals("raphael")) p = allowSelf ? "" : p;
        else if (!disp.equals("raphael") && p.startsWith(disp + "/") && !new File(activeDir(), p).exists()) p = p.substring(disp.length() + 1);
        else if (p.startsWith("raphael/") && !new File(activeDir(), p).exists()) p = p.substring(8);
        File base = activeDir().getCanonicalFile(), targ = (p.isEmpty() ? base : new File(base, p)).getCanonicalFile();
        String bc = base.getPath(), tc = targ.getPath(), rc = root.getCanonicalPath();
        if (!tc.equals(bc) && !tc.startsWith(bc + File.separator)) throw new SecurityException("Aktif klasör dışına çıkılamaz: " + req);
        if (!allowSelf && tc.equals(bc)) throw new SecurityException("Aktif kök klasör hedef alınamaz");
        if (tc.equals(rc + File.separator + ".raphael_undo") || tc.startsWith(rc + File.separator + ".raphael_undo" + File.separator)) throw new SecurityException("Yedek klasörüne dokunulamaz");
        return targ;
    }
    public Result tree(String sub) {
        try { File d = resolve(sub, true), base = activeDir().getCanonicalFile(); if (!d.isDirectory()) return new Result(false, "Klasör yok", "HATA: Klasör bulunamadı: " + sub);
            List<String> lines = new ArrayList<>(); walkTree(base, d, 0, lines, 180);
            String hdr = "DİZİN: " + getDisplayPath() + (hasRaphaelMd() ? "  [★ RAPHAEL.md mevcut]" : "") + " (" + lines.size() + " öğe)";
            StringBuilder sb = new StringBuilder(hdr).append("\n"); for (String l : lines) sb.append(l).append("\n");
            if (lines.size() >= 180) sb.append("... (180 öğe sınırı)\n"); return new Result(true, hdr, sb.toString().trim());
        } catch (Exception e) { return new Result(false, "Ağaç hatası", "HATA: " + e.getMessage()); }
    }
    private void walkTree(File base, File dir, int dep, List<String> out, int max) {
        if (dep > 6 || out.size() >= max) return; File[] ks = dir.listFiles(); if (ks == null) return;
        Arrays.sort(ks, (a, b) -> { if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1; if (a.getName().equals("RAPHAEL.md")) return -1; if (b.getName().equals("RAPHAEL.md")) return 1; return a.getName().compareToIgnoreCase(b.getName()); });
        for (File f : ks) { if (out.size() >= max) break; if (skipName(f.getName())) continue; String r = relOf(base, f);
            if (f.isDirectory()) { out.add("D " + r + "/"); walkTree(base, f, dep + 1, out, max); } else { out.add("F " + r + " (" + sizeStr(f.length()) + lineTag(f) + ")"); }
        }
    }
    public Result list(String sub) {
        try { File d = resolve(sub, true), base = activeDir().getCanonicalFile(); if (!d.isDirectory()) return new Result(false, "Klasör yok", "HATA: Klasör bulunamadı");
            File[] ks = d.listFiles(); List<String> out = new ArrayList<>();
            if (ks != null) { Arrays.sort(ks, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
                for (File f : ks) { if (skipName(f.getName())) continue; String r = relOf(base, f); out.add(f.isDirectory() ? "D " + r + "/" : "F " + r + " (" + sizeStr(f.length()) + lineTag(f) + ")"); } }
            StringBuilder sb = new StringBuilder("KLASÖR: " + (sub == null || sub.trim().isEmpty() ? "." : sub) + " (" + out.size() + " öğe)\n");
            for (String l : out) sb.append(l).append("\n"); return new Result(true, out.size() + " öğe listelendi", sb.toString().trim());
        } catch (Exception e) { return new Result(false, "Liste hatası", "HATA: " + e.getMessage()); }
    }
    public Result read(String path, int sL, int eL) {
        try { File f = resolve(path, false), base = activeDir().getCanonicalFile(); if (!f.isFile()) return new Result(false, "Dosya yok", "HATA: Dosya bulunamadı: " + path);
            if (f.length() > 1024 * 1024) return new Result(false, "Çok büyük", "HATA: 1 MB sınırını aşıyor");
            if (!looksText(f)) return new Result(false, "İkili dosya", "HATA: İkili dosya okunamaz (" + sizeStr(f.length()) + ")");
            List<String> ls = readLines(f); int tot = ls.size(); if (tot == 0) return new Result(true, relOf(base, f) + " (boş)", "DOSYA: " + relOf(base, f) + " (0 satır)");
            int a = sL > 0 ? Math.min(sL, tot) : 1, b = eL > 0 ? Math.min(eL, tot) : Math.min(tot, a + 219); if (b - a > 299) b = a + 299; if (b < a) b = a;
            StringBuilder sb = new StringBuilder("DOSYA: " + relOf(base, f) + " [satır " + a + "-" + b + " / toplam " + tot + "]\n");
            for (int i = a; i <= b; i++) sb.append(i).append("| ").append(ls.get(i - 1)).append("\n");
            if (b < tot) sb.append("--- Devamı: @read " + relOf(base, f) + " " + (b + 1) + "-" + Math.min(tot, b + 220) + " ---");
            return new Result(true, relOf(base, f) + " (" + a + "-" + b + "/" + tot + ")", sb.toString().trim());
        } catch (Exception e) { return new Result(false, "Okuma hatası", "HATA: " + e.getMessage()); }
    }
    public Result search(String q, String sub) {
        try { if (q == null || q.trim().isEmpty()) return new Result(false, "Boş arama", "HATA: Arama kelimesi boş");
            File d = resolve(sub, true), base = activeDir().getCanonicalFile(); List<String> hits = new ArrayList<>(); walkSearch(base, d, q.trim().toLowerCase(Locale.US), hits, 40);
            StringBuilder sb = new StringBuilder("ARAMA: " + q + " (" + hits.size() + " eşleşme)\n"); for (String h : hits) sb.append(h).append("\n");
            return new Result(true, hits.size() + " eşleşme", sb.toString().trim());
        } catch (Exception e) { return new Result(false, "Arama hatası", "HATA: " + e.getMessage()); }
    }
    private void walkSearch(File base, File dir, String needle, List<String> hits, int max) {
        if (hits.size() >= max) return; File[] ks = dir.listFiles(); if (ks == null) return;
        for (File f : ks) { if (hits.size() >= max) break; if (skipName(f.getName())) continue;
            if (f.isDirectory()) walkSearch(base, f, needle, hits, max); else if (f.length() <= 300 * 1024 && looksText(f)) {
                try { List<String> ls = readLines(f); String rp = relOf(base, f);
                    for (int i = 0; i < ls.size() && hits.size() < max; i++) { String ln = ls.get(i); if (ln.toLowerCase(Locale.US).contains(needle)) { String tr = ln.trim(); if (tr.length() > 140) tr = tr.substring(0, 140) + "…"; hits.add(rp + ":" + (i + 1) + ": " + tr); } }
                } catch (Exception ignored) {} } }
    }
    public Result mkdir(String path) {
        try { File f = resolve(path, false), base = activeDir().getCanonicalFile(); if (f.isFile()) return new Result(false, "Çakışma", "HATA: Bu isimde dosya var");
            if (f.isDirectory() || f.mkdirs()) return new Result(true, "Klasör hazır: " + relOf(base, f), "OK: Klasör hazır: " + relOf(base, f));
            return new Result(false, "Hata", "HATA: Klasör oluşturulamadı");
        } catch (Exception e) { return new Result(false, "Klasör hatası", "HATA: " + e.getMessage()); }
    }
    public Result write(String path, String content) {
        try { File f = resolve(path, false), base = activeDir().getCanonicalFile(); if (f.isDirectory()) return new Result(false, "Klasör hedef", "HATA: Hedef klasör");
            backupForUndo("WRITE", f, null); if (f.getParentFile() != null) f.getParentFile().mkdirs();
            String txt = content == null ? "" : content; if (!txt.isEmpty() && !txt.endsWith("\n")) txt += "\n";
            writeUtf8(f, txt); int lines = txt.isEmpty() ? 0 : txt.split("\n", -1).length - 1;
            return new Result(true, "Yazıldı: " + relOf(base, f) + " (" + lines + " sat)", "OK: Yazıldı: " + relOf(base, f) + " (" + lines + " satır, " + f.length() + " B)");
        } catch (Exception e) { return new Result(false, "Yazma hatası", "HATA: " + e.getMessage()); }
    }
    public Result patch(String path, String oldTxt, String newTxt) {
        try { File f = resolve(path, false), base = activeDir().getCanonicalFile(); if (!f.isFile()) return new Result(false, "Dosya yok", "HATA: Dosya bulunamadı: " + path);
            String raw = readAll(f); boolean crlf = raw.contains("\r\n"); String norm = raw.replace("\r\n", "\n");
            String o = (oldTxt == null ? "" : oldTxt).replace("\r\n", "\n"), n = (newTxt == null ? "" : newTxt).replace("\r\n", "\n");
            if (o.isEmpty()) return new Result(false, "Boş OLD", "HATA: OLD bloğu boş");
            int cnt = 0, pos = 0, hit = -1; while ((pos = norm.indexOf(o, pos)) >= 0) { if (cnt == 0) hit = pos; cnt++; pos += Math.max(1, o.length()); }
            if (cnt == 0) return new Result(false, "Eşleşme yok", "HATA: OLD bloğu bulunamadı; @read ile güncel satırları oku");
            if (cnt > 1) return new Result(false, "Çoklu eşleşme", "HATA: OLD bloğu " + cnt + " yerde geçiyor; daha fazla satır ver");
            backupForUndo("PATCH", f, null); String res = norm.substring(0, hit) + n + norm.substring(hit + o.length());
            if (crlf) res = res.replace("\n", "\r\n"); writeUtf8(f, res);
            int del = o.split("\n", -1).length, add = n.split("\n", -1).length;
            return new Result(true, "Güncellendi: " + relOf(base, f) + " (+" + add + "/-" + del + ")", "OK: Güncellendi: " + relOf(base, f) + " (+" + add + " / -" + del + " satır)");
        } catch (Exception e) { return new Result(false, "Yama hatası", "HATA: " + e.getMessage()); }
    }
    public Result move(String src, String dst) {
        try { File f1 = resolve(src, false), f2 = resolve(dst, false), base = activeDir().getCanonicalFile();
            if (!f1.exists()) return new Result(false, "Kaynak yok", "HATA: Kaynak yok: " + src);
            if (f2.exists()) return new Result(false, "Hedef var", "HATA: Hedef mevcut: " + dst);
            if (f2.getParentFile() != null) f2.getParentFile().mkdirs(); backupForUndo("MOVE", f2, f1);
            if (f1.renameTo(f2)) return new Result(true, "Taşındı: " + relOf(base, f2), "OK: Taşındı -> " + relOf(base, f2));
            return new Result(false, "Taşınamadı", "HATA: Taşıma başarısız");
        } catch (Exception e) { return new Result(false, "Taşıma hatası", "HATA: " + e.getMessage()); }
    }
    public Result delete(String path) {
        try { File f = resolve(path, false), base = activeDir().getCanonicalFile(); if (!f.exists()) return new Result(false, "Yok", "HATA: Öğe yok: " + path);
            if (f.isDirectory()) { String[] ls = f.list(); if (ls != null && ls.length > 0) return new Result(false, "Klasör dolu", "HATA: Klasör boş değil"); }
            String rp = relOf(base, f); if (f.isFile()) backupForUndo("DELETE", f, null);
            if (f.delete()) return new Result(true, "Silindi: " + rp, "OK: Silindi: " + rp);
            return new Result(false, "Silinemedi", "HATA: Silinemedi");
        } catch (Exception e) { return new Result(false, "Silme hatası", "HATA: " + e.getMessage()); }
    }
    public Result attach(String path) {
        try {
            if (path == null || path.trim().isEmpty()) return new Result(false, "Yol bos", "HATA: @attach icin yol belirt");
            File t = resolve(path, true), base = activeDir().getCanonicalFile();
            if (!t.exists()) return new Result(false, "Yok", "HATA: Bulunamadi: " + path);
            List<File> fs = new ArrayList<>();
            if (t.isFile()) {
                if (t.length() <= 0) return new Result(false, "Bos", "HATA: 0 byte dosya yuklenemez");
                if (t.length() > 30L * 1024 * 1024) return new Result(false, "Buyuk", "HATA: 30 MB siniri asildi");
                fs.add(t);
            } else {
                long[] tot = {0}; collectAttach(t, fs, tot, 12);
                if (fs.isEmpty()) return new Result(false, "Bos", "HATA: Klasorde dosya yok");
                if (fs.size() > 8) return new Result(false, "Cok dosya", "HATA: Klasorde 8+ dosya var; tek sec veya @zip kullan");
                if (tot[0] > 30L * 1024 * 1024) return new Result(false, "Buyuk", "HATA: Toplam > 30 MB; @zip kullan");
            }
            StringBuilder sb = new StringBuilder("EKLENECEK (" + fs.size() + "):\n");
            for (File f : fs) sb.append("- ").append(relOf(base, f)).append(" (").append(sizeStr(f.length())).append(")\n");
            return new Result(true, fs.size() + " dosya hazir", sb.toString().trim(), fs);
        } catch (Exception e) { return new Result(false, "Hata", "HATA: " + e.getMessage()); }
    }
    private void collectAttach(File dir, List<File> out, long[] tot, int cap) {
        if (out.size() >= cap) return; File[] ks = dir.listFiles(); if (ks == null) return;
        Arrays.sort(ks, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : ks) { if (out.size() >= cap) break; if (skipName(f.getName())) continue; if (f.isDirectory()) collectAttach(f, out, tot, cap); else if (f.length() > 0) { out.add(f); tot[0] += f.length(); } }
    }
    public Result zip(List<String> raw) {
        try {
            if (raw == null || raw.isEmpty()) return new Result(false, "Eksik", "HATA: @zip hedef.zip kaynak...");
            String oa; List<String> srcs = new ArrayList<>();
            if (raw.size() == 1) { String x = raw.get(0), cl = cleanRel(x);
                if (x.toLowerCase(Locale.US).endsWith(".zip") && !resolve(x, true).exists()) { oa = x; srcs.add("."); }
                else { srcs.add(x); String nm = (cl.isEmpty() || cl.equals(".") || cl.equals("raphael")) ? (activeRel.isEmpty() ? "raphael" : new File(activeRel).getName()) : new File(cl).getName(); oa = (nm.toLowerCase(Locale.US).endsWith(".zip") ? nm + "_p" : nm) + ".zip"; }
            } else { String f0 = raw.get(0), fn = raw.get(raw.size() - 1);
                if (f0.toLowerCase(Locale.US).endsWith(".zip")) { oa = f0; srcs.addAll(raw.subList(1, raw.size())); }
                else if (fn.toLowerCase(Locale.US).endsWith(".zip")) { oa = fn; srcs.addAll(raw.subList(0, raw.size() - 1)); }
                else if (raw.size() == 2 && resolve(f0, true).exists() && !resolve(fn, false).exists()) { srcs.add(f0); oa = fn + ".zip"; }
                else { oa = "paket.zip"; srcs.addAll(raw); }
            }
            File b = activeDir().getCanonicalFile(), outF = resolve(oa, false); if (outF.isDirectory()) return new Result(false, "Klasor", "HATA: Hedef klasor");
            List<File> sfs = new ArrayList<>(); for (String x : srcs) { File f = resolve(x, true); if (!f.exists()) return new Result(false, "Yok", "HATA: Kaynak yok: " + x); sfs.add(f); }
            if (outF.getParentFile() != null) outF.getParentFile().mkdirs(); File tmp = new File(outF.getPath() + ".tmp"); int[] cnt = {0}; long[] bs = {0};
            String oc = outF.getCanonicalPath(), tc = tmp.getCanonicalPath(); Set<String> seen = new HashSet<>();
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                for (File sf : sfs) addZip(sf, sf.equals(b) ? "" : sf.getName(), zos, oc, tc, seen, cnt, bs);
            } catch (Exception ze) { tmp.delete(); throw ze; }
            if (cnt[0] == 0) { tmp.delete(); return new Result(false, "Bos", "HATA: Dosya yok"); }
            backupForUndo("WRITE", outF, null); if (outF.exists()) outF.delete(); if (!tmp.renameTo(outF)) { copyFile(tmp, outF); tmp.delete(); }
            String rp = relOf(b, outF); return new Result(true, "ZIP: " + rp + " (" + cnt[0] + ", " + sizeStr(outF.length()) + ")", "OK: " + rp + " (" + cnt[0] + " dosya, " + sizeStr(outF.length()) + ")");
        } catch (Exception e) { return new Result(false, "ZIP hatasi", "HATA: " + e.getMessage()); }
    }
    private void addZip(File f, String en, ZipOutputStream zos, String oc, String tc, Set<String> seen, int[] cnt, long[] bs) throws Exception {
        String fc = f.getCanonicalPath(); if (fc.equals(oc) || fc.equals(tc) || (!en.isEmpty() && skipName(f.getName()))) return;
        if (f.isDirectory()) { File[] ks = f.listFiles(); if (ks == null) return; Arrays.sort(ks, (x, y) -> x.getName().compareToIgnoreCase(y.getName())); for (File k : ks) addZip(k, en.isEmpty() ? k.getName() : en + "/" + k.getName(), zos, oc, tc, seen, cnt, bs); return; }
        if (!f.isFile()) return; if (cnt[0] >= 2000) throw new IllegalStateException("2000 dosya siniri"); bs[0] += f.length(); if (bs[0] > 50L * 1024 * 1024) throw new IllegalStateException("50 MB siniri"); if (!seen.add(en)) return;
        zos.putNextEntry(new ZipEntry(en)); try (InputStream in = new FileInputStream(f)) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) zos.write(buf, 0, n); } zos.closeEntry(); cnt[0]++;
    }
    private File undoDir() { File d = new File(root, ".raphael_undo"); if (!d.exists()) d.mkdirs(); return d; }
    private JSONArray readUndoMeta() { try { File m = new File(undoDir(), "meta.json"); if (m.isFile()) return new JSONArray(readAll(m)); } catch (Exception ignored) {} return new JSONArray(); }
    private void writeUndoMeta(JSONArray a) { try { writeUtf8(new File(undoDir(), "meta.json"), a.toString()); } catch (Exception ignored) {} }
    private void backupForUndo(String op, File target, File src) {
        try { File r = root.getCanonicalFile(); JSONArray a = readUndoMeta(); JSONObject e = new JSONObject();
            e.put("op", op).put("t", relOf(r, target)); if (src != null) e.put("s", relOf(r, src)); boolean ex = target.isFile(); e.put("ex", ex);
            if (ex && target.length() <= 2 * 1024 * 1024) { String bk = "bk_" + System.nanoTime(); copyFile(target, new File(undoDir(), bk)); e.put("bk", bk); }
            a.put(e); while (a.length() > 12) { JSONObject old = a.optJSONObject(0); if (old != null && old.has("bk")) new File(undoDir(), old.optString("bk")).delete(); a.remove(0); }
            writeUndoMeta(a);
        } catch (Exception ignored) {}
    }
    public boolean canUndo() { return readUndoMeta().length() > 0; }
    public String undoLast() {
        JSONArray a = readUndoMeta(); if (a.length() == 0) return "Geri alınacak işlem yok";
        JSONObject e = a.optJSONObject(a.length() - 1); a.remove(a.length() - 1); writeUndoMeta(a); if (e == null) return "Geri alınamadı";
        try { File r = root.getCanonicalFile(); File t = new File(r, e.optString("t")); String op = e.optString("op");
            if ("MOVE".equals(op)) { File s = new File(r, e.optString("s")); if (s.getParentFile() != null) s.getParentFile().mkdirs(); t.renameTo(s); return "Geri alındı: " + e.optString("s"); }
            if (!e.optBoolean("ex")) { if (t.exists()) t.delete(); return "Yeni dosya silindi: " + e.optString("t"); }
            String bk = e.optString("bk", ""); if (bk.isEmpty()) return "Yedek yok"; File bf = new File(undoDir(), bk);
            if (bf.isFile()) { if (t.getParentFile() != null) t.getParentFile().mkdirs(); copyFile(bf, t); bf.delete(); return "Geri yüklendi: " + e.optString("t"); }
        } catch (Exception ex) { return "Geri alma hatası: " + ex.getMessage(); } return "Geri alınamadı";
    }
    private static void copyFile(File a, File b) throws Exception { try (InputStream in = new FileInputStream(a); OutputStream out = new FileOutputStream(b)) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); } }
    private static boolean looksText(File f) { try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[2048]; int n = in.read(b); for (int i = 0; i < n; i++) if (b[i] == 0) return false; return true; } catch (Exception e) { return false; } }
    private static String lineTag(File f) { if (f.length() > 262144 || !looksText(f)) return ""; try { return ", " + readLines(f).size() + " sat"; } catch (Exception e) { return ""; } }
    private static List<String> readLines(File f) throws Exception { List<String> l = new ArrayList<>(); try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) { String s; while ((s = br.readLine()) != null) l.add(s); } return l; }
    private static String readAll(File f) throws Exception { ByteArrayOutputStream bo = new ByteArrayOutputStream(); try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); } return bo.toString("UTF-8"); }
    private static void writeUtf8(File f, String s) throws Exception { try (OutputStream out = new FileOutputStream(f)) { out.write(s.getBytes("UTF-8")); } }
    public static String sizeStr(long b) { return b >= 1024 * 1024 ? String.format(Locale.US, "%.1f MB", b / 1048576f) : Math.max(1, b / 1024) + " KB"; }
}
