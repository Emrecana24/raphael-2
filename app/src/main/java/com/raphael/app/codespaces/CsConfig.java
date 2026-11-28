package com.raphael.app.codespaces;
import android.content.*;
import java.util.Locale;
public class CsConfig {
    private final SharedPreferences p;
    public CsConfig(Context c) { p = c.getSharedPreferences("raphael_cs", Context.MODE_PRIVATE); }
    public String getUrl() { return normUrl(p.getString("url", "")); }
    public void setUrl(String u) { p.edit().putString("url", normUrl(u)).apply(); }
    public String getToken() { return p.getString("token", "").trim(); }
    public void setToken(String t) { p.edit().putString("token", t == null ? "" : t.trim()).apply(); }
    public boolean isAuto() { return p.getBoolean("auto", false); }
    public void setAuto(boolean a) { p.edit().putBoolean("auto", a).apply(); }
    public boolean isUnlimited() { return p.getBoolean("unlimited", false); }
    public void setUnlimited(boolean u) { p.edit().putBoolean("unlimited", u).apply(); }
    public static java.io.File localRoot(Context c) { java.io.File d = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "raphael/codespaces"); if (!d.exists()) d.mkdirs(); if (!d.isDirectory()) { d = new java.io.File(c.getExternalFilesDir(null), "codespaces"); d.mkdirs(); } return d; }
    public int getTimeout() { return Math.max(10, Math.min(900, p.getInt("timeout", 180))); }
    public void setTimeout(int s) { p.edit().putInt("timeout", Math.max(10, Math.min(900, s))).apply(); }
    public boolean isConfigured() { return !getUrl().isEmpty() && !getToken().isEmpty(); }
    public static String normUrl(String u) { if (u == null) return ""; String s = u.trim(); if (s.isEmpty()) return ""; if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s; while (s.endsWith("/")) s = s.substring(0, s.length() - 1); return s; }
    public static String[] parseQuickPaste(String raw) { if (raw == null) return null; String u = null, t = null; for (String ln : raw.replace("\r", "").split("\n")) { String s = ln.trim(), up = s.toUpperCase(Locale.US); if (up.startsWith("URL:")) u = normUrl(s.substring(4)); else if (up.startsWith("TOKEN:")) t = s.substring(6).trim(); } return (u != null || t != null) ? new String[]{u == null ? "" : u, t == null ? "" : t} : null; }
}
