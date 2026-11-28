package com.raphael.app.codespaces;
import android.content.Context;
import java.io.*;
import java.util.*;
public class CsProtocol {
    public enum Op { TREE, LIST, READ, SEARCH, PULL, ATTACH, MKDIR, WRITE, PATCH, MOVE, DELETE, ZIP, UNDO, SH, CD }
    public static class Action {
        public final Op op;
        public String cmd = "", arg1 = "", arg2 = "", text1 = "", text2 = "";
        public int lineStart = 0, lineEnd = 0;
        public final List<String> args = new ArrayList<>();
        public Action(Op o) { op = o; }
        public Action(Op o, String c) { op = o; cmd = c == null ? "" : c; arg1 = cmd; }
        public boolean isReadOnly() { return op == Op.TREE || op == Op.LIST || op == Op.READ || op == Op.SEARCH || op == Op.PULL || op == Op.ATTACH; }
        public String label() {
            switch (op) {
                case TREE: return "Agac: " + (arg1.isEmpty() ? "." : arg1);
                case LIST: return "Listele: " + (arg1.isEmpty() ? "." : arg1);
                case READ: return "Oku: " + arg1 + (lineStart > 0 ? " (" + lineStart + "-" + lineEnd + ")" : "");
                case SEARCH: return "Ara: " + arg1 + (arg2.isEmpty() ? "" : " [" + arg2 + "]");
                case PULL: return "Indir: " + arg1 + (arg2.isEmpty() ? "" : " -> " + arg2);
                case ATTACH: return "Cek ve yukle: " + arg1 + (arg2.isEmpty() ? "" : " -> " + arg2);
                case MKDIR: return "Klasor ac: " + arg1;
                case WRITE: return "Yaz: " + arg1;
                case PATCH: return "Duzenle: " + arg1;
                case MOVE: return "Tasi: " + arg1 + " -> " + arg2;
                case DELETE: return "Sil: " + arg1;
                case ZIP: return "ZIP paketle: " + arg1 + (arg2.isEmpty() ? "" : " <- " + arg2);
                case UNDO: return "Geri al (Codespaces)";
                case CD: return "Dizin: " + cmd;
                default: String[] ls = cmd.replace("\r", "").split("\n", -1); String f = ""; for (String s : ls) { if (!s.trim().isEmpty()) { f = s.trim(); break; } } if (f.length() > 54) f = f.substring(0, 54) + "..."; return "$ " + f + (ls.length > 1 ? " (+" + (ls.length - 1) + " sat)" : "");
            }
        }
        public String preview() {
            if (op == Op.WRITE) return (text1.isEmpty() ? 0 : text1.split("\n", -1).length) + " satir yazilacak";
            if (op == Op.PATCH) return "-" + (text1.isEmpty() ? 0 : text1.split("\n", -1).length) + " / +" + (text2.isEmpty() ? 0 : text2.split("\n", -1).length) + " satir yama";
            return "";
        }
        public String detail() {
            if (op == Op.WRITE) return text1;
            if (op == Op.PATCH) return "--- ESKI ---\n" + text1 + "\n\n+++ YENI +++\n" + text2;
            if (op == Op.SH || op == Op.CD) return cmd;
            return arg1 + (arg2.isEmpty() ? "" : "\n" + arg2);
        }
    }
    public static class ExecResult {
        public final Action action; public final boolean ok, timedOut, truncated;
        public final int exitCode, undoCount; public final double elapsed;
        public final String cwd, output, err, summaryTxt; public final List<File> files;
        public ExecResult(Action a, boolean ok, int code, boolean to, boolean tr, double el, String cwd, String out, String err) { this(a, ok, code, to, tr, el, cwd, out, err, "", null, -1); }
        public ExecResult(Action a, boolean ok, int code, boolean to, boolean tr, double el, String cwd, String out, String err, String sum, List<File> fs, int undo) {
            this.action = a; this.ok = ok; this.exitCode = code; this.timedOut = to; this.truncated = tr; this.elapsed = el;
            this.cwd = cwd == null ? "" : cwd; this.output = out == null ? "" : out; this.err = err == null ? "" : err;
            this.summaryTxt = sum == null ? "" : sum; this.files = fs == null ? Collections.emptyList() : fs; this.undoCount = undo;
        }
        public String summary() {
            if (!err.isEmpty()) return action.label() + " -> HATA: " + err;
            if (!summaryTxt.isEmpty()) return (ok ? "\u2713 " : "\u2715 ") + summaryTxt;
            return (ok ? "\u2713 " : "\u2715 (" + exitCode + ") ") + action.label() + " \u00b7 " + String.format(Locale.US, "%.1fs", elapsed);
        }
    }
    public static String loadMcpMd(Context ctx) {
        try (InputStream in = ctx.getAssets().open("codespaces_mcp.md")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return "# Codespaces MCP\nUse cs-mcp blocks."; }
    }
    private static String fenceFor(String s) { int mx = 2, c = 0; for (int i = 0; i < s.length(); i++) { if (s.charAt(i) == 96) { if (++c > mx) mx = c; } else c = 0; } StringBuilder b = new StringBuilder(); for (int i = 0; i <= mx; i++) b.append((char) 96); return b.toString(); }
    public static String buildAttachPrompt(Context ctx, String cwd, boolean autoMode, String task) { return buildAttachPrompt(ctx, cwd, autoMode, false, task); }
    public static String buildAttachPrompt(Context ctx, String cwd, boolean autoMode, boolean unlimited, String task) {
        String md = loadMcpMd(ctx), f = fenceFor(md);
        String mode = autoMode ? (unlimited ? "Ozgur (Sinirsiz)" : "Ozgur (8 Tur)") : "Guvenli (Okuma/Cekme serbest, Duzenleme/Terminal onayli)";
        String tail = (task == null || task.trim().isEmpty()) ? "Baglanti kuruldu. Ortami gormek icin cs-mcp blogu ile @tree calistir." : "Gorev: " + task.trim();
        return "Dosya: codespaces_mcp.md\n" + f + "markdown\n" + md + "\n" + f + "\n\n[CODESPACES MCP AKTIF | Dizin: " + cwd + " | Izin: " + mode + "]\n" + tail;
    }
    public static List<Action> parseActions(String md) {
        List<Action> out = new ArrayList<>(); if (md == null || md.isEmpty()) return out;
        String[] L = md.replace("\r", "").split("\n", -1); boolean inB = false, hasLn = false; char fc = 0; int fl = 0, mode = 0; Action cur = null; StringBuilder buf = new StringBuilder();
        for (int i = 0; i < L.length; i++) {
            String ln = L[i], t = ln.trim();
            if (!inB) {
                if (t.length() >= 3 && (t.charAt(0) == 96 || t.charAt(0) == 126)) {
                    char ch = t.charAt(0); int k = 0; while (k < t.length() && t.charAt(k) == ch) k++;
                    if (k >= 3) { String tag = t.substring(k).trim().toLowerCase(Locale.US); if (tag.equals("cs-mcp") || tag.equals("codespaces-mcp")) { inB = true; fc = ch; fl = k; mode = 0; cur = null; hasLn = false; buf.setLength(0); } }
                } continue;
            }
            if (t.length() >= fl && allChar(t, fc)) { if (mode == 0 || !hasEndAhead(L, i + 1, fc, fl)) { inB = false; mode = 0; cur = null; hasLn = false; buf.setLength(0); continue; } }
            if (mode == 1) {
                if (t.equals("<<<END")) { String c = buf.toString(); if (!c.trim().isEmpty()) out.add(new Action(Op.SH, c)); mode = 0; hasLn = false; buf.setLength(0); }
                else { if (hasLn) buf.append("\n"); buf.append(ln); hasLn = true; } continue;
            }
            if (mode == 2) {
                if (t.equals("<<<END") && cur != null) { cur.text1 = buf.toString(); out.add(cur); cur = null; mode = 0; hasLn = false; buf.setLength(0); }
                else { if (hasLn) buf.append("\n"); buf.append(ln); hasLn = true; } continue;
            }
            if (mode == 3) {
                if (t.equals("<<<NEW") && cur != null && cur.op == Op.PATCH) { cur.text1 = buf.toString(); buf.setLength(0); hasLn = false; mode = 4; }
                else if (t.equals("<<<END") && cur != null) { cur.text1 = buf.toString(); out.add(cur); cur = null; mode = 0; hasLn = false; buf.setLength(0); }
                else { if (hasLn) buf.append("\n"); buf.append(ln); hasLn = true; } continue;
            }
            if (mode == 4) {
                if (t.equals("<<<END") && cur != null) { cur.text2 = buf.toString(); out.add(cur); cur = null; mode = 0; hasLn = false; buf.setLength(0); }
                else { if (hasLn) buf.append("\n"); buf.append(ln); hasLn = true; } continue;
            }
            if(t.isEmpty()||t.startsWith("#"))continue;
            if(t.equals("<<<SH")){cur=null;mode=1;hasLn=false;buf.setLength(0);continue;}
            if(t.equals("<<<EOF")&&cur!=null&&cur.op==Op.WRITE){mode=2;hasLn=false;buf.setLength(0);continue;}
            if(t.equals("<<<OLD")&&cur!=null&&cur.op==Op.PATCH){mode=3;hasLn=false;buf.setLength(0);continue;}
            cur=null; if(t.equals("@sh"))continue;
            if(t.startsWith("@sh ")||t.startsWith("@sh\t")){String c=t.substring(3).trim();if(!c.isEmpty())out.add(new Action(Op.SH,c));continue;}
            if(t.startsWith("@cd ")||t.startsWith("@cd\t")){String d=unquote(t.substring(3).trim());if(!d.isEmpty())out.add(new Action(Op.CD,d));continue;}
            List<String> ps=splitArgs(t); if(ps.isEmpty())continue;
            String cmd=ps.get(0).toLowerCase(Locale.US); if(cmd.startsWith("@"))cmd=cmd.substring(1);
            String a1=ps.size()>1?ps.get(1):"", a2=ps.size()>2?ps.get(2):"";
            if(cmd.equals("tree")||cmd.equals("oku")){Action a=new Action(Op.TREE);a.arg1=a1;out.add(a);}
            else if(cmd.equals("list")||cmd.equals("ls")){Action a=new Action(Op.LIST);a.arg1=a1;out.add(a);}
            else if(cmd.equals("read")||cmd.equals("cat")){Action a=new Action(Op.READ);a.arg1=a1;parseRange(a2,a);out.add(a);}
            else if(cmd.equals("search")||cmd.equals("grep")){Action a=new Action(Op.SEARCH);a.arg1=a1;a.arg2=a2;out.add(a);}
            else if(cmd.equals("pull")||cmd.equals("indir")){Action a=new Action(Op.PULL);a.arg1=a1;a.arg2=a2;out.add(a);}
            else if(cmd.equals("attach")||cmd.equals("upload")||cmd.equals("yukle")||cmd.equals("al")){Action a=new Action(Op.ATTACH);a.arg1=a1;a.arg2=a2;out.add(a);}
            else if(cmd.equals("mkdir")){Action a=new Action(Op.MKDIR);a.arg1=a1;out.add(a);}
            else if(cmd.equals("move")||cmd.equals("mv")){Action a=new Action(Op.MOVE);a.arg1=a1;a.arg2=a2;out.add(a);}
            else if(cmd.equals("delete")||cmd.equals("del")||cmd.equals("rm")){Action a=new Action(Op.DELETE);a.arg1=a1;out.add(a);}
            else if(cmd.equals("zip")||cmd.equals("pack")){Action a=new Action(Op.ZIP);for(int k=1;k<ps.size();k++)a.args.add(ps.get(k));a.arg1=a1;if(ps.size()>2)a.arg2=String.join(" ",ps.subList(2,ps.size()));out.add(a);}
            else if(cmd.equals("undo")){out.add(new Action(Op.UNDO));}
            else if(cmd.equals("write")){cur=new Action(Op.WRITE);cur.arg1=a1;}
            else if(cmd.equals("patch")){cur=new Action(Op.PATCH);cur.arg1=a1;}
        } return out;
    }
    public static boolean hasMutating(List<Action> list){for(Action a:list)if(!a.isReadOnly())return true;return false;}
    private static void parseRange(String s,Action a){if(s==null||s.isEmpty())return;String[] p=s.split("[-:]");try{a.lineStart=Integer.parseInt(p[0].trim());if(p.length>1)a.lineEnd=Integer.parseInt(p[1].trim());}catch(Exception ignored){}}
    private static List<String> splitArgs(String s){List<String> out=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;char qc=0;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(q){if(c==qc)q=false;else b.append(c);}else if(c==34||c==39){q=true;qc=c;}else if(Character.isWhitespace(c)){if(b.length()>0){out.add(b.toString());b.setLength(0);}}else b.append(c);}if(b.length()>0)out.add(b.toString());return out;}
    private static String unquote(String s){if(s.length()>=2&&((s.charAt(0)==34&&s.charAt(s.length()-1)==34)||(s.charAt(0)==39&&s.charAt(s.length()-1)==39)))return s.substring(1,s.length()-1);return s;}
    private static boolean allChar(String s,char ch){for(int i=0;i<s.length();i++)if(s.charAt(i)!=ch)return false;return true;}
    private static boolean hasEndAhead(String[] L,int from,char ch,int fl){for(int k=from;k<L.length;k++){String s=L[k].trim();if(s.equals("<<<END"))return true;if(s.length()>=fl&&allChar(s,ch)&&s.length()>3)return false;}return false;}
    public static String formatFeedback(List<ExecResult> res){StringBuilder sb=new StringBuilder("[CODESPACES CIKTISI]\n\n");for(ExecResult er:res){sb.append("### ").append(er.action.label());if(er.action.op==Op.SH||er.action.op==Op.CD){if(!er.err.isEmpty())sb.append(" [HATA]\n").append(er.err);else sb.append(" [cikis=").append(er.exitCode).append(" | ").append(String.format(Locale.US,"%.1fs",er.elapsed)).append(er.timedOut?" | ZAMAN ASIMI":"").append(" | cwd=").append(er.cwd).append("]\n").append(er.output.isEmpty()?"(cikti yok)":er.output);}else{sb.append(er.ok?" [OK]":" [HATA]").append("\n").append(!er.output.isEmpty()?er.output:er.err);}sb.append("\n\n");}return sb.toString().trim();}
}
