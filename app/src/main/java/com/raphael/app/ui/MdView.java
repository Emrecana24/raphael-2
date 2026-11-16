package com.raphael.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hafif Markdown çizici (kütüphane yok). Bloklara ayırır; akış sırasında sadece değişen bloğu günceller.
 * Kod blokları: ayrı kutu, yatay kaydırma, her birinde "Kopyala" düğmesi.
 */
public class MdView extends LinearLayout {
    public interface Copier { void copy(String text, String what); }

    private static final int PARA = 0, HEAD = 1, LIST = 2, QUOTE = 3, CODE = 4, TABLE = 5, HR = 6;

    private static class Block {
        int type, level;
        String text = "", lang = "", marker = "";
        boolean open;
        Block(int t) { type = t; }
        boolean same(Block o) {
            return type == o.type && level == o.level && text.equals(o.text) && lang.equals(o.lang) && marker.equals(o.marker);
        }
    }

    private static class CodeHolder { TextView code, lang, copy; String text = ""; }

    private final Handler h = new Handler(Looper.getMainLooper());
    private List<Block> cur = new ArrayList<>();
    private float sp = 14.5f;
    private Typeface face = Typeface.SERIF;
    private int color = Ui.TEXT;
    private Copier copier;

    public MdView(Context c) {
        super(c);
        setOrientation(VERTICAL);
    }

    public void setStyle(float sp, Typeface face, int color) { this.sp = sp; this.face = face; this.color = color; }
    public void setCopier(Copier c) { copier = c; }

    public void setMarkdown(String md) {
        List<Block> nb = parse(md == null ? "" : md);
        for (int i = 0; i < nb.size(); i++) {
            Block b = nb.get(i);
            if (i < cur.size()) {
                Block o = cur.get(i);
                if (o.same(b)) continue;
                View v = getChildAt(i);
                if (!(o.type == b.type && update(v, b))) { removeViewAt(i); addView(build(b, i), i); }
            } else addView(build(b, i));
        }
        while (getChildCount() > nb.size()) removeViewAt(getChildCount() - 1);
        cur = nb;
    }

    // ------------------------------------------------------------------ ayrıştırma
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})\\s*([^\\s`]*).*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern HRULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TSEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern QUOTEP = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern LISTP = Pattern.compile("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$");

    private static boolean isClose(String l, String fence) {
        String t = l.trim();
        return t.length() >= fence.length() && t.charAt(0) == fence.charAt(0) && t.matches("[`~]+");
    }

    private static boolean startsBlock(String ln, String next) {
        return FENCE.matcher(ln).matches() || HEADING.matcher(ln).matches() || HRULE.matcher(ln).matches()
                || QUOTEP.matcher(ln).matches() || LISTP.matcher(ln).matches()
                || (ln.contains("|") && next != null && TSEP.matcher(next).matches());
    }

    private static String[] cells(String row) {
        String t = row.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
        String[] p = t.split("(?<!\\\\)\\|", -1);
        for (int i = 0; i < p.length; i++) p[i] = p[i].replace("\\|", "|").trim();
        return p;
    }

    private static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        String[] L = md.replace("\r", "").split("\n", -1);
        int i = 0;
        StringBuilder para = null;
        while (i < L.length) {
            String ln = L[i], nx = i + 1 < L.length ? L[i + 1] : null;
            Matcher fm = FENCE.matcher(ln);
            if (fm.matches()) {
                para = flush(out, para);
                String fence = fm.group(1), lang = fm.group(2);
                StringBuilder code = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < L.length) {
                    if (isClose(L[i], fence)) { closed = true; i++; break; }
                    if (code.length() > 0) code.append('\n');
                    code.append(L[i]);
                    i++;
                }
                Block b = new Block(CODE); b.text = code.toString(); b.lang = lang; b.open = !closed;
                out.add(b);
                continue;
            }
            Matcher hm = HEADING.matcher(ln);
            if (hm.matches()) {
                para = flush(out, para);
                Block b = new Block(HEAD); b.level = hm.group(1).length(); b.text = hm.group(2);
                out.add(b); i++; continue;
            }
            if (HRULE.matcher(ln).matches()) { para = flush(out, para); out.add(new Block(HR)); i++; continue; }
            if (ln.contains("|") && nx != null && TSEP.matcher(nx).matches()) {
                para = flush(out, para);
                StringBuilder tb = new StringBuilder();
                tb.append(join(cells(ln)));
                i += 2;
                while (i < L.length && L[i].contains("|") && !L[i].trim().isEmpty()) { tb.append('\n').append(join(cells(L[i]))); i++; }
                Block b = new Block(TABLE); b.text = tb.toString();
                out.add(b); continue;
            }
            Matcher qm = QUOTEP.matcher(ln);
            if (qm.matches()) {
                para = flush(out, para);
                StringBuilder qb = new StringBuilder(qm.group(1));
                i++;
                while (i < L.length) { Matcher q2 = QUOTEP.matcher(L[i]); if (!q2.matches()) break; qb.append('\n').append(q2.group(1)); i++; }
                Block b = new Block(QUOTE); b.text = qb.toString();
                out.add(b); continue;
            }
            Matcher lm = LISTP.matcher(ln);
            if (lm.matches()) {
                para = flush(out, para);
                int ind = lm.group(1).replace("\t", "    ").length();
                Block b = new Block(LIST);
                b.level = ind == 0 ? 0 : ind <= 3 ? 1 : ind <= 7 ? 2 : 3;
                String mk = lm.group(2);
                b.marker = Character.isDigit(mk.charAt(0)) ? mk.replace(")", ".") : "•";
                StringBuilder tb = new StringBuilder(lm.group(3));
                i++;
                while (i < L.length && !L[i].trim().isEmpty() && (L[i].startsWith(" ") || L[i].startsWith("\t"))
                        && !LISTP.matcher(L[i]).matches() && !FENCE.matcher(L[i]).matches()) {
                    tb.append('\n').append(L[i].trim()); i++;
                }
                b.text = tb.toString();
                out.add(b); continue;
            }
            if (ln.trim().isEmpty()) { para = flush(out, para); i++; continue; }
            if (para == null) para = new StringBuilder(); else para.append('\n');
            para.append(ln);
            i++;
        }
        flush(out, para);
        return out;
    }

    private static StringBuilder flush(List<Block> out, StringBuilder para) {
        if (para != null && para.length() > 0) { Block b = new Block(PARA); b.text = para.toString().trim(); out.add(b); }
        return null;
    }

    private static String join(String[] c) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < c.length; i++) { if (i > 0) s.append('\u0001'); s.append(c[i]); }
        return s.toString();
    }

    // ------------------------------------------------------------------ görünüm üretimi
    private LayoutParams lp(int type, int idx) {
        LayoutParams p = new LayoutParams(-1, -2);
        int top = idx == 0 ? 0 : type == HEAD ? 14 : type == LIST ? 3 : type == CODE || type == TABLE ? 10 : 9;
        p.topMargin = Ui.dp(top);
        return p;
    }

    private TextView tv(float size) {
        TextView t = new TextView(getContext());
        t.setTextSize(Ui.sp(size));
        t.setTextColor(color);
        t.setTypeface(face);
        t.setLineSpacing(0, 1.28f);
        t.setMovementMethod(LinkMovementMethod.getInstance());
        t.setLinkTextColor(0xFFB5502F);
        return t;
    }

    private CharSequence rich(Block b) {
        if (b.type == LIST) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(b.marker).append("  ");
            appendInline(sb, b.text);
            int first = Ui.dp(14 * b.level), rest = first + Ui.dp(b.marker.length() > 1 ? 24 : 16);
            sb.setSpan(new LeadingMarginSpan.Standard(first, rest), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            Linkify.addLinks(sb, Linkify.WEB_URLS);
            return sb;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendInline(sb, b.text);
        if (b.type == PARA) Linkify.addLinks(sb, Linkify.WEB_URLS);
        return sb;
    }

    private float headSize(int level) { return level == 1 ? sp + 5.5f : level == 2 ? sp + 3.5f : level == 3 ? sp + 1.5f : sp + 0.5f; }

    private boolean update(View v, Block b) {
        switch (b.type) {
            case PARA: case LIST: ((TextView) v).setText(rich(b)); return true;
            case HEAD: ((TextView) v).setText(rich(b)); return true;
            case QUOTE: ((TextView) ((LinearLayout) v).getChildAt(1)).setText(rich(b)); return true;
            case CODE: {
                CodeHolder ch = (CodeHolder) v.getTag();
                ch.text = b.text; ch.code.setText(b.text); ch.lang.setText(b.lang.isEmpty() ? "kod" : b.lang);
                return true;
            }
            default: return false;
        }
    }

    private View build(Block b, int idx) {
        Context c = getContext();
        switch (b.type) {
            case HEAD: {
                TextView t = tv(headSize(b.level));
                t.setTypeface(face, Typeface.BOLD);
                t.setText(rich(b)); t.setLayoutParams(lp(HEAD, idx));
                return t;
            }
            case LIST: { TextView t = tv(sp); t.setText(rich(b)); t.setLayoutParams(lp(LIST, idx)); return t; }
            case QUOTE: {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(HORIZONTAL);
                View bar = new View(c); bar.setBackgroundColor(0xFFD8D5CC);
                row.addView(bar, new LayoutParams(Ui.dp(3), -1));
                TextView t = tv(sp); t.setTextColor(Ui.MUTED); t.setText(rich(b)); t.setPadding(Ui.dp(10), 0, 0, 0);
                row.addView(t, new LayoutParams(-1, -2));
                row.setLayoutParams(lp(QUOTE, idx));
                return row;
            }
            case HR: {
                View v = new View(c); v.setBackgroundColor(Ui.BORDER);
                LayoutParams p = new LayoutParams(-1, Ui.dp(1)); p.topMargin = Ui.dp(12); p.bottomMargin = Ui.dp(4);
                v.setLayoutParams(p); return v;
            }
            case CODE: return buildCode(b, idx);
            case TABLE: return buildTable(b, idx);
            default: { TextView t = tv(sp); t.setText(rich(b)); t.setLayoutParams(lp(PARA, idx)); return t; }
        }
    }

    private View buildCode(Block b, int idx) {
        Context c = getContext();
        final CodeHolder ch = new CodeHolder();
        ch.text = b.text;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(VERTICAL);
        box.setBackground(Ui.round(Ui.CODEBG, 10, Ui.BORDER, 1));
        box.setLayoutParams(lp(CODE, idx));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(4), Ui.dp(0));
        ch.lang = new TextView(c);
        ch.lang.setTextSize(Ui.sp(11)); ch.lang.setTextColor(Ui.MUTED); ch.lang.setTypeface(Typeface.MONOSPACE);
        ch.lang.setText(b.lang.isEmpty() ? "kod" : b.lang);
        head.addView(ch.lang, new LayoutParams(0, -2, 1f));
        ch.copy = new TextView(c);
        ch.copy.setTextSize(Ui.sp(11.5f)); ch.copy.setTextColor(Ui.TEXT);
        ch.copy.setText("Kopyala");
        ch.copy.setPadding(Ui.dp(10), Ui.dp(9), Ui.dp(10), Ui.dp(9));
        ch.copy.setBackground(Ui.ripple(null));
        ch.copy.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) {
            if (copier != null) copier.copy(ch.text, "Kod kopyalandı");
            ch.copy.setText("Kopyalandı ✓");
            h.postDelayed(new Runnable() { @Override public void run() { ch.copy.setText("Kopyala"); } }, 1600);
        } });
        head.addView(ch.copy, new LayoutParams(-2, -2));
        box.addView(head, new LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(OVER_SCROLL_NEVER);
        ch.code = new TextView(c);
        ch.code.setTypeface(Typeface.MONOSPACE);
        ch.code.setTextSize(Ui.sp(12));
        ch.code.setTextColor(0xFF2B2A28);
        ch.code.setHorizontallyScrolling(true);
        ch.code.setText(b.text);
        ch.code.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(12), Ui.dp(12));
        hs.addView(ch.code, new LayoutParams(-2, -2));
        box.addView(hs, new LayoutParams(-1, -2));
        box.setTag(ch);
        return box;
    }

    private View buildTable(Block b, int idx) {
        Context c = getContext();
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setLayoutParams(lp(TABLE, idx));
        TableLayout tl = new TableLayout(c);
        tl.setBackground(Ui.round(Ui.SURFACE, 8, Ui.BORDER, 1));
        String[] rows = b.text.split("\n", -1);
        for (int r = 0; r < rows.length; r++) {
            TableRow tr = new TableRow(c);
            String[] cs = rows[r].split("\u0001", -1);
            for (String cell : cs) {
                TextView t = tv(sp - 1.5f);
                t.setTypeface(Typeface.DEFAULT, r == 0 ? Typeface.BOLD : Typeface.NORMAL);
                SpannableStringBuilder sb = new SpannableStringBuilder();
                appendInline(sb, cell);
                t.setText(sb);
                t.setPadding(Ui.dp(10), Ui.dp(7), Ui.dp(10), Ui.dp(7));
                t.setBackground(Ui.round(r == 0 ? Ui.BUBBLE : Ui.SURFACE, 0, Ui.BORDER, 0.5f));
                t.setMaxWidth(Ui.dp(260));
                tr.addView(t);
            }
            tl.addView(tr);
        }
        hs.addView(tl);
        return hs;
    }

    // ------------------------------------------------------------------ satır içi biçim
    private static boolean word(char ch) { return Character.isLetterOrDigit(ch); }

    private void appendInline(SpannableStringBuilder sb, String s) {
        int n = s.length(), i = 0;
        StringBuilder buf = new StringBuilder();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!|~<>".indexOf(s.charAt(i + 1)) >= 0) { buf.append(s.charAt(i + 1)); i += 2; continue; }
            if (c == '`') {
                int run = 1;
                while (i + run < n && s.charAt(i + run) == '`') run++;
                String tick = s.substring(i, i + run);
                int end = s.indexOf(tick, i + run);
                if (end > i + run - 1 && end >= i + run) {
                    sb.append(buf); buf.setLength(0);
                    int st = sb.length();
                    sb.append(s.substring(i + run, end).trim());
                    sb.setSpan(new TypefaceSpan("monospace"), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(Ui.INLINE_CODE), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.9f), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + run; continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) == c && (c == '*' || i == 0 || !word(s.charAt(i - 1)))) {
                String d = "" + c + c;
                int end = s.indexOf(d, i + 2);
                if (end > i + 2) {
                    sb.append(buf); buf.setLength(0);
                    int st = sb.length();
                    appendInline(sb, s.substring(i + 2, end));
                    sb.setSpan(new StyleSpan(Typeface.BOLD), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2; continue;
                }
            }
            if (c == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    sb.append(buf); buf.setLength(0);
                    int st = sb.length();
                    appendInline(sb, s.substring(i + 2, end));
                    sb.setSpan(new StrikethroughSpan(), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2; continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1)) && s.charAt(i + 1) != c
                    && (c == '*' || i == 0 || !word(s.charAt(i - 1)))) {
                int end = -1;
                for (int j = i + 2; j < n; j++) {
                    if (s.charAt(j) == c && !Character.isWhitespace(s.charAt(j - 1)) && (j + 1 >= n || s.charAt(j + 1) != c)
                            && (c == '*' || j + 1 >= n || !word(s.charAt(j + 1)))) { end = j; break; }
                }
                if (end > 0) {
                    sb.append(buf); buf.setLength(0);
                    int st = sb.length();
                    appendInline(sb, s.substring(i + 1, end));
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1; continue;
                }
            }
            if (c == '[') {
                int cb = s.indexOf("](", i);
                if (cb > i) {
                    int cp = s.indexOf(')', cb + 2);
                    if (cp > cb && s.substring(i + 1, cb).indexOf('\n') < 0) {
                        sb.append(buf); buf.setLength(0);
                        int st = sb.length();
                        appendInline(sb, s.substring(i + 1, cb));
                        String url = s.substring(cb + 2, cp).trim();
                        if (url.startsWith("http")) {
                            sb.setSpan(new URLSpan(url), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = cp + 1; continue;
                    }
                }
            }
            buf.append(c);
            i++;
        }
        sb.append(buf);
    }
}
