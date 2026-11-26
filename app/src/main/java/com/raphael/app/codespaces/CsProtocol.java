package com.raphael.app.codespaces;
import android.content.Context;
import java.io.*;
import java.util.*;
public class CsProtocol {
    public enum Op { SH, CD }
    public static class Action {
        public final Op op; public String cmd;
        public Action(Op o, String c) { op = o; cmd = c == null ? "" : c; }
        public String label() {
            if (op == Op.CD) return "Dizin: " + cmd;
            String[] ls = cmd.replace("\r", "").split("\n", -1); String f = "";
            for (String s : ls) { if (!s.trim().isEmpty()) { f = s.trim(); break; } }
            if (f.length() > 54) f = f.substring(0, 54) + "...";
            return f + (ls.length > 1 ? " (+" + (ls.length - 1) + " sat)" : "");
        }
    }
    public static class ExecResult {
        public final Action action; public final boolean ok, timedOut, truncated; public final int exitCode; public final double elapsed; public final String cwd, output, err;
        public ExecResult(Action a, boolean ok, int code, boolean to, boolean tr, double el, String cwd, String out, String err) {
            this.action = a; this.ok = ok; this.exitCode = code; this.timedOut = to; this.truncated = tr; this.elapsed = el; this.cwd = cwd == null ? "" : cwd; this.output = out == null ? "" : out; this.err = err == null ? "" : err;
        }
        public String summary() { return !err.isEmpty() ? action.label() + " -> HATA: " + err : (ok ? "✓ " : "✕ (" + exitCode + ") ") + action.label() + " · " + String.format(Locale.US, "%.1fs", elapsed); }
    }
    public static String loadMcpMd(Context ctx) {
        try (InputStream in = ctx.getAssets().open("codespaces_mcp.md")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return "# Codespaces MCP\nUse cs-mcp blocks with @sh <cmd>."; }
    }
    private static String fenceFor(String s){int mx=2,c=0;for(int i=0;i<s.length();i++){if(s.charAt(i)==96){if(++c>mx)mx=c;}else c=0;}StringBuilder b=new StringBuilder();for(int i=0;i<=mx;i++)b.append((char)96);return b.toString();}
    public static String buildAttachPrompt(Context ctx, String cwd, boolean autoMode, String task) {
        String md = loadMcpMd(ctx), f = fenceFor(md);
        String tail = (task == null || task.trim().isEmpty()) ? "Baglanti kuruldu. Ortami gorup hazir oldugunu dogrulamak icin cs-mcp blogu ile @sh pwd && ls -la calistir." : "Gorev: " + task.trim();
        return "Dosya: codespaces_mcp.md\n" + f + "markdown\n" + md + "\n" + f + "\n\n[CODESPACES MCP AKTIF | Dizin: " + cwd + " | Izin: " + (autoMode ? "Ozgur" : "Guvenli") + "]\n" + tail;
    }
    public static List<Action> parseActions(String md) {
        List<Action> out = new ArrayList<>(); if (md == null || md.isEmpty()) return out;
        String[] L = md.replace("\r", "").split("\n", -1); boolean inB = false, hasLn = false; char fc = 0; int fl = 0, mode = 0; StringBuilder buf = new StringBuilder();
        for (int i = 0; i < L.length; i++) {
            String ln = L[i], t = ln.trim();
            if (!inB) {
                if (t.length() >= 3 && (t.charAt(0) == 96 || t.charAt(0) == 126)) {
                    char ch = t.charAt(0); int k = 0; while (k < t.length() && t.charAt(k) == ch) k++;
                    if (k >= 3) { String tag = t.substring(k).trim().toLowerCase(Locale.US); if (tag.equals("cs-mcp") || tag.equals("codespaces-mcp")) { inB = true; fc = ch; fl = k; mode = 0; hasLn = false; buf.setLength(0); } }
                } continue;
            }
            if (t.length() >= fl && allChar(t, fc)) { if (mode == 0 || !hasEndAhead(L, i + 1, fc, fl)) { inB = false; mode = 0; hasLn = false; buf.setLength(0); continue; } }
            if (mode == 1) {
                if (t.equals("<<<END")) { String c = buf.toString(); if (!c.trim().isEmpty()) out.add(new Action(Op.SH, c)); mode = 0; hasLn = false; buf.setLength(0); }
                else { if (hasLn) buf.append("\n"); buf.append(ln); hasLn = true; } continue;
            }
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.equals("<<<SH")) { mode = 1; hasLn = false; buf.setLength(0); continue; }
            if (t.startsWith("@sh ") || t.startsWith("@sh\t")) { String c = t.substring(3).trim(); if (!c.isEmpty()) out.add(new Action(Op.SH, c)); continue; }
            if (t.startsWith("@cd ") || t.startsWith("@cd\t")) { String d = unquote(t.substring(3).trim()); if (!d.isEmpty()) out.add(new Action(Op.CD, d)); }
        } return out;
    }
    private static String unquote(String s){if(s.length()>=2&&((s.charAt(0)==34&&s.charAt(s.length()-1)==34)||(s.charAt(0)==39&&s.charAt(s.length()-1)==39)))return s.substring(1,s.length()-1);return s;}
    private static boolean allChar(String s,char ch){for(int i=0;i<s.length();i++)if(s.charAt(i)!=ch)return false;return true;}
    private static boolean hasEndAhead(String[] L,int from,char ch,int fl){for(int k=from;k<L.length;k++){String s=L[k].trim();if(s.equals("<<<END"))return true;if(s.length()>=fl&&allChar(s,ch)&&s.length()>3)return false;}return false;}
    public static String formatFeedback(List<ExecResult> res) {
        StringBuilder sb = new StringBuilder("[CODESPACES CIKTISI]\n\n");
        for (ExecResult er : res) {
            sb.append("### ").append(er.action.label());
            if (!er.err.isEmpty()) sb.append(" [HATA]\n").append(er.err);
            else sb.append(" [cikis=").append(er.exitCode).append(" | ").append(String.format(Locale.US, "%.1fs", er.elapsed)).append(er.timedOut ? " | ZAMAN ASIMI" : "").append(" | cwd=").append(er.cwd).append("]\n").append(er.output.isEmpty() ? "(cikti yok)" : er.output);
            sb.append("\n\n");
        } return sb.toString().trim();
    }
}
