package com.raphael.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.OutputStream;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.raphael.app.code.CodeProtocol;
import com.raphael.app.code.CodeUi;
import com.raphael.app.code.CodeWorkspace;
import com.raphael.app.codespaces.*;
import com.raphael.app.core.AppCore;
import com.raphael.app.core.Site;
import com.raphael.app.core.WebSession;
import com.raphael.app.data.Chat;
import com.raphael.app.data.ChatStore;
import com.raphael.app.data.Msg;
import com.raphael.app.ui.MdView;
import com.raphael.app.ui.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class MainActivity extends Activity implements AppCore.UiBridge {
    private static final int REQ_PICK = 11, REQ_WEBFILE = 12, REQ_SAVE_PERM = 13, REQ_CODE_PERM = 14, REQ_NOTIF_PERM = 15;
    private static final String[] LEVELS = {"", "low", "medium", "high", "xhigh"};
    private static final String[] LEVEL_LABELS = {"Varsayılan", "Low", "Medium", "High", "Extra High"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AppCore core;
    private ChatStore store;
    private WebSession session;

    private boolean webMode = false;
    private View lastSwitchBanner, lastErrView;
    private LinearLayout lastAiCol;
    private ValueCallback<Uri[]> webFileCb;
    private File pendingSaveFile;
    private byte[] pendingSaveBytes;
    private String pendingSaveName, pendingSaveMime;
    private Runnable pendingPermR = null;
    private CodeUi.ModeTabs modeTabs;
    private CodeUi.CodeBar codeBar;
    private CsUi.CsBar csBar;
    private HorizontalScrollView slashWrap;
    private LinearLayout slashRow;

    // görünümler
    private FrameLayout root, chatArea, webHost;
    private LinearLayout chatPage, webPage, messages, attachInner, drawerPanel;
    private HorizontalScrollView attachRow;
    private ScrollView scroll;
    private View welcome, drawerScrim, statusDot, webDot;
    private TextView titleTv, statusTv, chipTv, webStatusTv, webSiteBtn, webDisplayBtn, webConnBtn;
    private EditText input;
    private ImageView sendBtn;
    private LinearLayout drawerList;
    private int drawerW;

    // akış
    private MdView curMd;
    private TextView curFoot;
    private LinearLayout curThWrap;
    private TextView curThHdr, curThBody;
    private boolean curThOpen = false, hasUiText = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        core = AppCore.get(this); prefs = core.prefs; store = core.store; session = core.session;
        drawerW = Math.min(Ui.dp(300), (int) (getResources().getDisplayMetrics().widthPixels * 0.85f));
        buildUi(); setContentView(root);
        core.attachUi(this, webHost);
        renderChat(); refreshCodeUi(); refreshAttach(); updateChip(); applyBgRender();
        onCoreStatus(core.siteSt, core.siteInfo);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("askedNotif", false)) {
            prefs.edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF_PERM);
        }
    }
    @Override protected void onResume() { super.onResume(); if (core != null) { updateChip(); refreshCodeUi(); refreshAttach(); updateWebBar(); onCoreStatus(core.siteSt, core.siteInfo); } }
    @Override protected void onDestroy() { super.onDestroy(); h.removeCallbacks(uiTimerR); if (core != null) core.detachUi(this, webHost); }

    @Override
    public void onBackPressed() {
        if (drawerScrim.getVisibility() == View.VISIBLE) { closeDrawer(); return; }
        if (webMode) { showWeb(false); return; }
        if (core != null && (core.generating || core.codeMode || core.cs.isBgActive())) { moveTaskToBack(true); return; }
        super.onBackPressed();
    }

    // ================================================================== UI inşası
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        // ---- Web sayfası (altta)
        webPage = new LinearLayout(this);
        webPage.setOrientation(LinearLayout.VERTICAL);
        webPage.setBackgroundColor(Ui.BG);
        webPage.addView(buildWebBar(), new LinearLayout.LayoutParams(-1, Ui.dp(44)));
        webHost = new FrameLayout(this);
        webPage.addView(webHost, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(webPage, new FrameLayout.LayoutParams(-1, -1));

        // ---- Sohbet sayfası (üstte, opak)
        chatPage = new LinearLayout(this);
        chatPage.setOrientation(LinearLayout.VERTICAL);
        chatPage.setBackgroundColor(Ui.BG);
        chatPage.setClickable(true);
        chatPage.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, Ui.dp(52)));
        modeTabs = CodeUi.buildModeSwitch(this, v -> { if (core.codeMode) requestExitCodeMode(); }, v -> { if (!core.codeMode) requestEnterCodeMode(null); });
        chatPage.addView(modeTabs.wrap, new LinearLayout.LayoutParams(-1, -2));
        codeBar = CodeUi.buildCodeBar(this, codeHost());
        chatPage.addView(codeBar.root, new LinearLayout.LayoutParams(-1, -2));
        csBar = CsUi.buildCsBar(this, csUiHost());
        chatPage.addView(csBar.root, new LinearLayout.LayoutParams(-1, -2));

        chatArea = new FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(24));
        scroll.addView(messages, new ViewGroup.LayoutParams(-1, -2));
        chatArea.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        welcome = buildWelcome();
        chatArea.addView(welcome, new FrameLayout.LayoutParams(-1, -1));
        chatPage.addView(chatArea, new LinearLayout.LayoutParams(-1, 0, 1f));
        chatPage.addView(buildInputCard(), new LinearLayout.LayoutParams(-1, -2));
        root.addView(chatPage, new FrameLayout.LayoutParams(-1, -1));

        // ---- Çekmece
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x66000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        root.addView(drawerScrim, new FrameLayout.LayoutParams(-1, -1));
        drawerPanel = buildDrawer();
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(drawerW, -1);
        root.addView(drawerPanel, dp);
        drawerPanel.setTranslationX(-drawerW);
        drawerPanel.setVisibility(View.INVISIBLE);
    }

    private ImageView iconBtn(int res, int tint, View.OnClickListener l) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        v.setBackground(Ui.ripple(null));
        v.setOnClickListener(l);
        return v;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(4), 0, Ui.dp(4), 0);
        bar.addView(iconBtn(R.drawable.ic_menu, Ui.TEXT, v -> openDrawer()), new LinearLayout.LayoutParams(Ui.dp(48), Ui.dp(48)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        titleTv = Ui.text(this, "Raphael", 15, Ui.TEXT);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleTv.setTypeface(Typeface.SERIF, Typeface.BOLD);
        mid.addView(titleTv);
        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        statusDot.setBackground(Ui.round(Ui.MUTED, 4, 0, 0));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(7), Ui.dp(7));
        dl.rightMargin = Ui.dp(5);
        st.addView(statusDot, dl);
        statusTv = Ui.text(this, "Bağlı değil", 10.5f, Ui.MUTED);
        statusTv.setSingleLine(true);
        statusTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        st.addView(statusTv);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = Ui.dp(3);
        mid.addView(st, sl);
        mid.setOnClickListener(v -> showWeb(true));
        bar.addView(mid, new LinearLayout.LayoutParams(0, -1, 1f));

        bar.addView(iconBtn(R.drawable.ic_globe, Ui.TEXT, v -> showWeb(true)), new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(48)));
        bar.addView(iconBtn(R.drawable.ic_new, Ui.TEXT, v -> newChat()), new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(48)));
        return bar;
    }

    private View buildWelcome() {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setGravity(Gravity.CENTER);
        w.setPadding(0, 0, 0, Ui.dp(60));
        ImageView star = new ImageView(this);
        star.setImageResource(R.drawable.ic_star);
        w.addView(star, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        TextView t = Ui.text(this, greeting(), 27, Ui.TEXT);
        t.setTypeface(Typeface.SERIF);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.topMargin = Ui.dp(18);
        w.addView(t, l);
        w.setClickable(false);
        return w;
    }

    private String greeting() {
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hr >= 5 && hr < 12) return "Sabah düşünceleri";
        if (hr >= 12 && hr < 18) return "Öğleden sonra düşünceleri";
        if (hr >= 18 && hr < 23) return "Akşam düşünceleri";
        return "Gece düşünceleri";
    }

    private View buildInputCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.SURFACE, 26, Ui.BORDER, 1));
        card.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(10), Ui.dp(10));
        card.setElevation(Ui.dp(2));

        slashWrap = new HorizontalScrollView(this); slashWrap.setHorizontalScrollBarEnabled(false); slashWrap.setVisibility(View.GONE);
        slashRow = new LinearLayout(this); slashRow.setOrientation(LinearLayout.HORIZONTAL); slashWrap.addView(slashRow);
        card.addView(slashWrap, new LinearLayout.LayoutParams(-1, -2));
        attachRow = new HorizontalScrollView(this);
        attachRow.setHorizontalScrollBarEnabled(false);
        attachRow.setVisibility(View.GONE);
        attachInner = new LinearLayout(this);
        attachInner.setOrientation(LinearLayout.HORIZONTAL);
        attachRow.addView(attachInner);
        card.addView(attachRow, new LinearLayout.LayoutParams(-1, -2));

        input = new EditText(this);
        input.setHint("Mesaj yaz…");
        input.setHintTextColor(Ui.MUTED);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(Ui.sp(15));
        input.setBackground(null);
        input.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(6));
        input.setMaxLines(7);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { updateSendBtn(); if (slashWrap != null) CodeUi.updateSlashBar(slashWrap, slashRow, e.toString(), core.codeMode, codeHost()); }
        });
        card.addView(input, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(4), 0, 0);

        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_plus);
        plus.setColorFilter(Ui.TEXT);
        plus.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(9), Ui.dp(9));
        plus.setBackground(Ui.ripple(Ui.round(Ui.BUBBLE, 20, 0, 0)));
        plus.setOnClickListener(v -> pickFile());
        row.addView(plus, new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38)));

        chipTv = Ui.pill(this, "", 12.5f, Ui.TEXT, Ui.BUBBLE, 0);
        chipTv.setOnClickListener(v -> showModelSheet());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, Ui.dp(38));
        cl.leftMargin = Ui.dp(8);
        row.addView(chipTv, cl);

        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        sendBtn = new ImageView(this);
        sendBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sendBtn.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(9), Ui.dp(9));
        sendBtn.setOnClickListener(v -> onSendClick());
        row.addView(sendBtn, new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38)));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        updateSendBtn();

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(8));
        wrap.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private View buildWebBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(Ui.BG);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(6), Ui.dp(5), Ui.dp(6), Ui.dp(5));

        TextView back = Ui.pill(this, "‹ Sohbet", 11.5f, 0xFFFFFFFF, Ui.TEXT, 0);
        back.setOnClickListener(v -> showWeb(false));
        row.addView(back, wl());

        LinearLayout st = new LinearLayout(this);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setPadding(Ui.dp(8), 0, Ui.dp(6), 0);
        webDot = new View(this);
        webDot.setBackground(Ui.round(Ui.MUTED, 4, 0, 0));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(7), Ui.dp(7));
        dl.rightMargin = Ui.dp(5);
        st.addView(webDot, dl);
        webStatusTv = Ui.text(this, "—", 11, Ui.MUTED);
        webStatusTv.setSingleLine(true);
        st.addView(webStatusTv);
        row.addView(st, new LinearLayout.LayoutParams(-2, -2));

        webSiteBtn = Ui.pill(this, "", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        webSiteBtn.setOnClickListener(v -> setSite(core.site == Site.GEMINI ? Site.AISTUDIO : Site.GEMINI));
        row.addView(webSiteBtn, wl());
        TextView reload = Ui.pill(this, "Yenile", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        reload.setOnClickListener(v -> { core.boundChatId = null; core.boundSite = null; session.reload(); core.invalidateUploads(true); core.updateNotif(); });
        row.addView(reload, wl());
        TextView acct = Ui.pill(this, "Hesap girişi", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        acct.setOnClickListener(v -> { core.invalidateUploads(false); if (!session.isConnected()) session.open(core.site, core.site.newChatUrl); session.loginPage(); core.updateNotif(); });
        row.addView(acct, wl());
        webDisplayBtn = Ui.pill(this, "", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        webDisplayBtn.setOnClickListener(v -> { prefs.edit().putBoolean("bg", !prefs.getBoolean("bg", true)).apply(); applyBgRender(); });
        row.addView(webDisplayBtn, wl());
        webConnBtn = Ui.pill(this, "Bağlantıyı kes", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        webConnBtn.setOnClickListener(v -> {
            if (session.isConnected()) { session.disconnect(); core.boundChatId = null; core.boundSite = null; core.invalidateUploads(false); }
            else { session.open(core.site, core.site.newChatUrl); core.invalidateUploads(true); }
            core.updateNotif(); updateWebBar();
        });
        row.addView(webConnBtn, wl());
        TextView dom = Ui.pill(this, "DOM kaydet", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        dom.setOnClickListener(v -> { Toast.makeText(this, "DOM alınıyor…", Toast.LENGTH_SHORT).show(); session.saveDom(); });
        row.addView(dom, wl());
        hs.addView(row);
        return hs;
    }

    private LinearLayout.LayoutParams wl() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.rightMargin = Ui.dp(6);
        return l;
    }

    private LinearLayout buildDrawer() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xFFF5F4EE);
        p.setClickable(true);
        p.setPadding(Ui.dp(14), Ui.dp(22), Ui.dp(14), Ui.dp(10));
        p.setElevation(Ui.dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView star = new ImageView(this);
        star.setImageResource(R.drawable.ic_star);
        head.addView(star, new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));
        TextView t = Ui.text(this, "Raphael", 20, Ui.TEXT);
        t.setTypeface(Typeface.SERIF);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = Ui.dp(10);
        head.addView(t, tl);
        p.addView(head, new LinearLayout.LayoutParams(-1, -2));

        TextView nc = Ui.pill(this, "＋  Yeni sohbet", 13.5f, 0xFFFFFFFF, Ui.TEXT, 0);
        nc.setOnClickListener(v -> { closeDrawer(); newChat(); });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = Ui.dp(18); nl.bottomMargin = Ui.dp(8);
        p.addView(nc, nl);
        TextView imp = Ui.pill(this, "↓  Web'den sohbet al", 13f, Ui.TEXT, Ui.SURFACE, Ui.BORDER);
        imp.setOnClickListener(v -> { closeDrawer(); openImportPicker(); });
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.bottomMargin = Ui.dp(14);
        p.addView(imp, il);

        p.addView(Ui.text(this, "Sohbetler", 11, Ui.MUTED), new LinearLayout.LayoutParams(-2, -2));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        drawerList = new LinearLayout(this);
        drawerList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(drawerList, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sl.topMargin = Ui.dp(6);
        p.addView(sv, sl);

        p.addView(Ui.text(this, "Dışa aktarmak/silmek için basılı tut", 10.5f, Ui.MUTED), new LinearLayout.LayoutParams(-2, -2));
        TextView set = Ui.pill(this, "Ayarlar", 13, Ui.TEXT, Ui.BUBBLE, 0);
        set.setOnClickListener(v -> { closeDrawer(); showSettings(); });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = Ui.dp(8);
        p.addView(set, gl);
        return p;
    }

    // ================================================================== çekmece
    private void openImportPicker() {
        if (core.generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        final Dialog d = new Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout wrap = new LinearLayout(this); wrap.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(16));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setBackground(Ui.round(Ui.BG, 22, Ui.BORDER, 1)); box.setPadding(Ui.dp(18), Ui.dp(18), Ui.dp(18), Ui.dp(16));
        if (core.site != Site.GEMINI) {
            TextView tt = Ui.text(this, core.site.label + " sohbet çekme", 17, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
            TextView msg = Ui.text(this, "AI Studio için web sohbet geçmişi çekme özelliği henüz eklenmedi. Şimdilik bu özelliği Gemini kaynağında kullanabilirsin.", 13, Ui.MUTED);
            msg.setLineSpacing(0, 1.28f); LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = Ui.dp(10); box.addView(msg, ml);
            TextView ok = Ui.pill(this, "Tamam", 13.5f, 0xFFFFFFFF, Ui.TEXT, 0); ok.setOnClickListener(v -> d.dismiss());
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, Ui.dp(42)); ol.topMargin = Ui.dp(16); box.addView(ok, ol);
            wrap.addView(box, new LinearLayout.LayoutParams(-1, -2)); d.setContentView(wrap);
            Window w = d.getWindow(); if (w != null) { w.setBackgroundDrawable(new ColorDrawable(0)); w.setGravity(Gravity.CENTER); w.setDimAmount(0.4f); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
            d.show(); return;
        }
        TextView tt = Ui.text(this, "Gemini sohbetleri", 17, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        final TextView sub = Ui.text(this, "Gemini geçmişi yükleniyor…", 12, Ui.MUTED); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(5); box.addView(sub, sl);
        ScrollView sv = new ScrollView(this) { @Override protected void onMeasure(int wS, int hS) { int m = Ui.dp(320); if (MeasureSpec.getMode(hS) == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(hS) > m) hS = MeasureSpec.makeMeasureSpec(m, MeasureSpec.AT_MOST); super.onMeasure(wS, hS); } };
        sv.setVerticalScrollBarEnabled(false); final LinearLayout listBox = new LinearLayout(this); listBox.setOrientation(LinearLayout.VERTICAL); sv.addView(listBox, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(-1, -2); svl.topMargin = Ui.dp(12); box.addView(sv, svl);
        LinearLayout bot = new LinearLayout(this); bot.setGravity(Gravity.END); LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = Ui.dp(14);
        final TextView ref = Ui.pill(this, "Yenile", 12.5f, Ui.TEXT, Ui.BUBBLE, Ui.BORDER); bot.addView(ref);
        TextView cls = Ui.pill(this, "Kapat", 12.5f, Ui.MUTED, Ui.SURFACE, Ui.BORDER); cls.setOnClickListener(v -> d.dismiss()); LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.leftMargin = Ui.dp(8); bot.addView(cls, cl); box.addView(bot, bl);
        wrap.addView(box, new LinearLayout.LayoutParams(-1, -2)); d.setContentView(wrap);
        Window w = d.getWindow(); if (w != null) { w.setBackgroundDrawable(new ColorDrawable(0)); w.setGravity(Gravity.CENTER); w.setDimAmount(0.4f); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
        final boolean[] alive = { true, false }; d.setOnDismissListener(di -> { alive[0] = false; if (!alive[1] && !core.generating) session.cancelQueue(); });
        ref.setOnClickListener(v -> loadImportList(d, sub, listBox, alive)); d.show(); loadImportList(d, sub, listBox, alive);
    }
    private void loadImportList(final Dialog d, final TextView sub, final LinearLayout listBox, final boolean[] alive) {
        listBox.removeAllViews(); listBox.addView(Ui.text(this, "Sohbet başlıkları alınıyor…", 12.5f, Ui.MUTED));
        if (session.status() == WebSession.ST_LOGIN) {
            sub.setText("Giriş gerekli — Web sekmesinden hesabına gir"); listBox.removeAllViews();
            TextView wb = Ui.pill(this, "Web sekmesini aç", 13, 0xFFFFFFFF, Ui.TEXT, 0); wb.setOnClickListener(v -> { d.dismiss(); showWeb(true); }); listBox.addView(wb, new LinearLayout.LayoutParams(-1, Ui.dp(42))); return;
        }
        sub.setText(session.isUsable() ? "Başlıklar okunuyor…" : "Gemini oturumu hazırlanıyor…");
        session.whenUsable(() -> {
            if (!alive[0] || core.site != Site.GEMINI) return; sub.setText("Başlıklar okunuyor…");
            session.op("listChats", null, r -> {
                if (!alive[0]) return; listBox.removeAllViews();
                JSONArray arr = r.optBoolean("ok") ? r.optJSONArray("chats") : null;
                if (arr == null || arr.length() == 0) { sub.setText("Sohbet bulunamadı"); listBox.addView(Ui.text(this, "Gemini geçmişi boş veya henüz yüklenemedi. Web sekmesinden giriş durumunu kontrol edip Yenile'ye dokunabilirsin.", 12.5f, Ui.MUTED)); return; }
                sub.setText(arr.length() + " sohbet · yüklemek istediğine dokun");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i); if (item == null) continue;
                    final String cTitle = item.optString("title", "Sohbet"), cUrl = item.optString("url", ""); if (cUrl.isEmpty()) continue;
                    TextView row = Ui.text(this, cTitle, 13.5f, Ui.TEXT); row.setSingleLine(true); row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.setPadding(Ui.dp(12), Ui.dp(11), Ui.dp(12), Ui.dp(11)); row.setBackground(Ui.ripple(Ui.round(Ui.SURFACE, 12, Ui.BORDER, 1)));
                    row.setOnClickListener(v -> { alive[1] = true; d.dismiss(); importGeminiChat(cTitle, cUrl); });
                    LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); if (i > 0) rl.topMargin = Ui.dp(6); listBox.addView(row, rl);
                }
            }, 25000);
        });
    }
    private void importGeminiChat(final String title, final String url) {
        if (core.generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        final int seq = ++core.importSeq; final Dialog ld = new Dialog(this); ld.requestWindowFeature(Window.FEATURE_NO_TITLE); ld.setCancelable(false);
        LinearLayout wrap = new LinearLayout(this); wrap.setPadding(Ui.dp(26), Ui.dp(16), Ui.dp(26), Ui.dp(16));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setBackground(Ui.round(Ui.BG, 22, Ui.BORDER, 1)); box.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(18));
        TextView tt = Ui.text(this, "Sohbet yükleniyor", 16.5f, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        final TextView st = Ui.text(this, title + "\n\nSayfa açılıyor…", 13, Ui.MUTED); st.setLineSpacing(0, 1.28f); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(8); box.addView(st, sl);
        TextView cancel = Ui.pill(this, "İptal", 13, Ui.MUTED, Ui.SURFACE, Ui.BORDER); cancel.setOnClickListener(v -> { core.importSeq++; session.cancelQueue(); ld.dismiss(); });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, Ui.dp(40)); cl.topMargin = Ui.dp(14); box.addView(cancel, cl); wrap.addView(box, new LinearLayout.LayoutParams(-1, -2)); ld.setContentView(wrap);
        Window w = ld.getWindow(); if (w != null) { w.setBackgroundDrawable(new ColorDrawable(0)); w.setGravity(Gravity.CENTER); w.setDimAmount(0.4f); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); } ld.show();
        core.boundChatId = null; core.boundSite = null; session.open(Site.GEMINI, url);
        final Runnable[] to = { () -> { if (seq == core.importSeq && ld.isShowing()) { core.importSeq++; session.cancelQueue(); ld.dismiss(); toast("Sohbet zamanında yüklenemedi, Web sekmesinden kontrol et"); } } };
        h.postDelayed(to[0], 55000);
        session.whenUsable(() -> {
            h.removeCallbacks(to[0]); if (seq != core.importSeq) return; st.setText(title + "\n\nMesajlar alınıyor…");
            session.op("readChat", null, r -> {
                if (seq != core.importSeq) return; JSONArray arr = r.optBoolean("ok") ? r.optJSONArray("turns") : null;
                if (arr == null || arr.length() == 0) { core.importSeq++; ld.dismiss(); toast("Sohbet okunamadı (" + r.optString("err", "boş") + ")"); return; }
                final List<Msg> msgs = new ArrayList<>(); final List<List<String>> imgUrls = new ArrayList<>(); int tot = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i); if (o == null) continue; String role = "a".equals(o.optString("role")) ? Msg.AI : Msg.USER;
                    Msg m = new Msg(role, o.optString("text", ""), null, role.equals(Msg.AI) ? Site.GEMINI.id : null);
                    List<String> u = new ArrayList<>(); JSONArray ia = o.optJSONArray("imgs"); if (ia != null) for (int k = 0; k < ia.length(); k++) { String s = ia.optString(k); if (!s.isEmpty()) u.add(s); }
                    tot += u.size(); msgs.add(m); imgUrls.add(u);
                }
                if (msgs.isEmpty()) { core.importSeq++; ld.dismiss(); toast("Sohbette okunabilir mesaj yok"); return; }
                if (tot > 0) st.setText(title + "\n\nGörseller indiriliyor (" + tot + ")…");
                importTurnsImages(msgs, imgUrls, 0, seq, () -> {
                    if (seq != core.importSeq) return; core.importSeq++; if (!isFinishing() && !isDestroyed() && ld.isShowing()) ld.dismiss(); session.markUsed();
                    Chat c = new Chat(); c.title = core.makeTitle(title); c.site = Site.GEMINI.id; c.msgs.addAll(msgs); core.finishImportedChat(c); toast("Sohbet yüklendi (" + c.msgs.size() + " mesaj)");
                });
            }, 45000);
        });
    }
    private void importTurnsImages(final List<Msg> msgs, final List<List<String>> imgUrls, final int idx, final int seq, final Runnable done) {
        if (seq != core.importSeq) return; if (idx >= msgs.size()) { done.run(); return; } List<String> urls = imgUrls.get(idx);
        if (urls.isEmpty()) { importTurnsImages(msgs, imgUrls, idx + 1, seq, done); return; }
        session.fetchImages(urls, names -> { if (seq != core.importSeq) return; msgs.get(idx).imgs.addAll(names); importTurnsImages(msgs, imgUrls, idx + 1, seq, done); });
    }

    private void openDrawer() {
        refreshDrawer();
        hideKeyboard();
        drawerScrim.setAlpha(0f);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerPanel.setVisibility(View.VISIBLE);
        drawerScrim.animate().alpha(1f).setDuration(160).start();
        drawerPanel.animate().translationX(0).setDuration(180).start();
    }

    private void closeDrawer() {
        drawerScrim.animate().alpha(0f).setDuration(140).withEndAction(() -> drawerScrim.setVisibility(View.GONE)).start();
        drawerPanel.animate().translationX(-drawerW).setDuration(160).withEndAction(() -> drawerPanel.setVisibility(View.INVISIBLE)).start();
    }

    private void refreshDrawer() {
        drawerList.removeAllViews();
        List<Chat> all = store.list();
        if (all.isEmpty()) {
            drawerList.addView(Ui.text(this, "Henüz sohbet yok", 12.5f, Ui.MUTED));
            return;
        }
        for (final Chat c : all) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(10), Ui.dp(9), Ui.dp(10), Ui.dp(9));
            boolean cur = c.id.equals(core.chat.id);
            row.setBackground(Ui.ripple(cur ? Ui.round(Ui.BUBBLE, 10, 0, 0) : null));
            TextView t = Ui.text(this, c.title, 13.5f, Ui.TEXT);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t);
            TextView s = Ui.text(this, Site.fromId(c.site).label + " · " + c.count + " mesaj", 10.5f, Ui.MUTED);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = Ui.dp(3);
            row.addView(s, sl);
            row.setOnClickListener(v -> { closeDrawer(); openChat(c.id); });
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle(c.title)
                        .setItems(new String[]{"Dışa aktar (.md)", "Sil"}, (d, w) -> {
                            if (w == 0) { exportChat(c); return; }
                            new AlertDialog.Builder(this).setTitle("Sohbet silinsin mi?").setMessage(c.title)
                                    .setPositiveButton("Sil", (d2, w2) -> {
                                        store.delete(c.id);
                                        if (c.id.equals(core.chat.id)) core.newChat();
                                        refreshDrawer();
                                    }).setNegativeButton("Vazgeç", null).show();
                        }).show();
                return true;
            });
            drawerList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // ================================================================== sohbet yönetimi
    private void newChat() { core.newChat(); }
    private void openChat(String id) { core.openChat(id); }

    private void renderChat() {
        messages.removeAllViews();
        lastSwitchBanner = null; lastErrView = null; lastAiCol = null;
        curMd = null; curFoot = null; curThWrap = null; curThHdr = null; curThBody = null; curThOpen = false;
        h.removeCallbacks(uiTimerR);
        for (Msg m : core.chat.msgs) {
            View v = m.role.equals(Msg.USER) ? userView(m) : aiView(m);
            messages.addView(v);
            if (!m.role.equals(Msg.USER) && v instanceof LinearLayout) lastAiCol = (LinearLayout) v;
        }
        if (core.generating) {
            startAiUi(false);
            if (!core.curThText.isEmpty() || core.curThActive) {
                curThWrap.setVisibility(View.VISIBLE);
                if (!core.curThText.isEmpty()) curThBody.setText(core.curThText);
                curThHdr.setText((curThOpen ? "▾ " : "▸ ") + (core.curThActive && core.curText.isEmpty() ? "Düşünüyor…" : "Düşünce"));
            }
            if (!core.curText.isEmpty()) { hasUiText = true; curMd.setMarkdown(core.curText); }
            updateTimerFoot();
        } else if (core.cs.pendingActions != null && core.chat.id.equals(core.cs.pendingChatId) && lastAiCol != null) {
            lastAiCol.addView(CsUi.buildApprovalCard(this, core.cs.pendingActions, csUiHost()));
        } else if (core.pendingApprovalActions != null && core.chat.id.equals(core.pendingApprovalChatId) && lastAiCol != null) {
            lastAiCol.addView(CodeUi.buildApprovalCard(this, core.pendingApprovalActions, codeHost()));
        } else if (core.lastErr != null) {
            showErrView(core.lastErr);
        }
        welcome.setVisibility(core.chat.msgs.isEmpty() && !core.generating ? View.VISIBLE : View.GONE);
        titleTv.setText(core.chat.msgs.isEmpty() ? "Raphael" : core.chat.title);
        updateSendBtn();
        scroll.post(() -> scroll.scrollTo(0, messages.getBottom()));
    }

    private View userView(final Msg m) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.END);
        if (!m.imgs.isEmpty()) {
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-2, -2);
            il.bottomMargin = Ui.dp(6);
            wrap.addView(imagesRow(m.imgs, true), il);
        }
        final TextView t = new TextView(this);
        t.setTextSize(Ui.sp(14.5f));
        t.setTextColor(Ui.TEXT);
        t.setLineSpacing(0, 1.2f);
        t.setBackground(Ui.round(Ui.BUBBLE, 18, 0, 0));
        t.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        t.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.84f));
        final String full = m.display();
        if (full.length() > 800) {
            final String shortS = full.substring(0, 700) + "…\n(devamı için dokun)";
            t.setText(shortS);
            t.setTag(Boolean.FALSE);
            t.setOnClickListener(v -> {
                boolean open = Boolean.TRUE.equals(t.getTag());
                t.setText(open ? shortS : full);
                t.setTag(!open);
            });
        } else t.setText(full);
        t.setOnLongClickListener(v -> { copyText(m.display(), "Mesaj kopyalandı"); return true; });
        if (!full.trim().isEmpty()) wrap.addView(t, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(16);
        wrap.setLayoutParams(lp);
        return wrap;
    }

    // ---- görsel yardımcıları
    private Bitmap decode(File f, int maxPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) { return null; }
    }

    private void loadInto(final ImageView iv, final File f, final int maxPx) {
        new Thread(() -> {
            final Bitmap b = decode(f, maxPx);
            runOnUiThread(() -> { if (b != null) iv.setImageBitmap(b); });
        }).start();
    }

    private View imagesRow(List<String> names, boolean small) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        for (String n : names) {
            final File f = store.img(n);
            if (!f.exists()) continue;
            ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setMaxHeight(Ui.dp(small ? 170 : 460));
            iv.setBackground(Ui.round(Ui.CODEBG, 12, Ui.BORDER, 1));
            iv.setPadding(Ui.dp(1), Ui.dp(1), Ui.dp(1), Ui.dp(1));
            iv.setOnClickListener(v -> showImage(f));
            loadInto(iv, f, small ? 480 : 1080);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(small ? -2 : -1, -2);
            l.topMargin = Ui.dp(8);
            col.addView(iv, l);
        }
        return col;
    }

    private void showImage(final File f) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        loadInto(iv, f, 2048);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView close = Ui.pill(this, "‹ Kapat", 13, 0xFFFFFFFF, 0x66000000, 0);
        close.setOnClickListener(v -> d.dismiss());
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cl.setMargins(Ui.dp(12), Ui.dp(28), 0, 0);
        box.addView(close, cl);
        LinearLayout acts = new LinearLayout(this);
        TextView save = Ui.pill(this, "Kaydet", 13, 0xFFFFFFFF, 0x66000000, 0);
        save.setOnClickListener(v -> saveImageToDownloads(f));
        acts.addView(save, new LinearLayout.LayoutParams(-2, -2));
        TextView share = Ui.pill(this, "Paylaş", 13, 0xFFFFFFFF, 0x66000000, 0);
        share.setOnClickListener(v -> shareFile("imgs", f.getName()));
        LinearLayout.LayoutParams shl = new LinearLayout.LayoutParams(-2, -2);
        shl.leftMargin = Ui.dp(8);
        acts.addView(share, shl);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sl.setMargins(0, Ui.dp(28), Ui.dp(12), 0);
        box.addView(acts, sl);
        d.setContentView(box);
        d.show();
    }

    private void saveImageToDownloads(final File f) {
        if (f == null || !f.exists()) { toast("Dosya bulunamadı"); return; }
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSaveFile = f;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_SAVE_PERM);
            return;
        }
        new Thread(() -> {
            try {
                String n = f.getName().toLowerCase();
                String ext = n.endsWith(".jpg") || n.endsWith(".jpeg") ? "jpg" : n.endsWith(".webp") ? "webp" : n.endsWith(".gif") ? "gif" : "png";
                String mime = ext.equals("jpg") ? "image/jpeg" : ext.equals("webp") ? "image/webp" : ext.equals("gif") ? "image/gif" : "image/png";
                String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
                String outName = "Raphael_" + ts + "." + ext;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, outName);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("MediaStore hatası");
                    try (InputStream in = new java.io.FileInputStream(f); OutputStream os = getContentResolver().openOutputStream(uri)) {
                        byte[] b = new byte[16384]; int r;
                        while ((r = in.read(b)) > 0) os.write(b, 0, r);
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    File out = new File(dir, outName);
                    int k = 1;
                    while (out.exists()) { outName = "Raphael_" + ts + "_" + (k++) + "." + ext; out = new File(dir, outName); }
                    try (InputStream in = new java.io.FileInputStream(f); OutputStream os = new FileOutputStream(out)) {
                        byte[] b = new byte[16384]; int r;
                        while ((r = in.read(b)) > 0) os.write(b, 0, r);
                    }
                    MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()}, new String[]{mime}, null);
                }
                final String fn = outName;
                runOnUiThread(() -> toast("İndirilenlere kaydedildi: " + fn));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Kaydedilemedi: " + e.getMessage()));
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_CODE_PERM) { Runnable r = pendingPermR; pendingPermR = null; if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) { if (r != null) r.run(); } else toast("Depolama izni olmadan raphael klasörü kullanılamaz"); return; }
        if (req == REQ_SAVE_PERM) {
            File f = pendingSaveFile; byte[] d = pendingSaveBytes; String nm = pendingSaveName, mm = pendingSaveMime;
            pendingSaveFile = null; pendingSaveBytes = null; pendingSaveName = null; pendingSaveMime = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                if (f != null) saveImageToDownloads(f);
                else if (d != null && nm != null) saveBytesToDownloads(d, nm, mm);
            } else toast("İzin verilmediği için kaydedilemedi");
        }
    }

    private String extForLang(String lang) {
        String l = lang == null ? "" : lang.trim().toLowerCase(java.util.Locale.US);
        switch (l) {
            case "python": case "py": return "py";
            case "javascript": case "js": return "js";
            case "typescript": case "ts": return "ts";
            case "html": case "htm": return "html";
            case "markdown": case "md": return "md";
            case "kotlin": case "kt": return "kt";
            case "bash": case "sh": case "shell": case "zsh": return "sh";
            case "c++": case "cpp": return "cpp";
            case "c#": case "csharp": case "cs": return "cs";
            case "rust": case "rs": return "rs";
            case "yaml": case "yml": return "yml";
            case "java": case "json": case "xml": case "css": case "scss": case "sql":
            case "go": case "php": case "c": case "dart": case "swift": case "txt":
            case "csv": case "toml": case "ini": case "jsx": case "tsx": case "gradle": return l;
            default: return "txt";
        }
    }

    private void showSaveCodeDialog(final String text, String lang) {
        final String ext = extForLang(lang);
        String ts = new java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(new java.util.Date());
        final String def = "kod_" + ts + "." + ext;
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(Ui.dp(20), Ui.dp(16), Ui.dp(20), Ui.dp(16));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(Ui.BG, 20, Ui.BORDER, 1));
        box.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(16));
        TextView tt = Ui.text(this, "Kodu kaydet", 16, Ui.TEXT);
        tt.setTypeface(Typeface.SERIF, Typeface.BOLD);
        box.addView(tt);
        TextView sub = Ui.text(this, "Doğrudan telefonun Download klasörüne kaydedilir", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = Ui.dp(4);
        box.addView(sub, sl);
        final EditText ed = new EditText(this);
        ed.setText(def); ed.setTextSize(Ui.sp(14)); ed.setTextColor(Ui.TEXT);
        ed.setBackground(Ui.round(Ui.SURFACE, 12, Ui.BORDER, 1));
        ed.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        ed.setSingleLine(true);
        int dot = def.lastIndexOf("."); if (dot > 0) ed.setSelection(0, dot);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2); el.topMargin = Ui.dp(12);
        box.addView(ed, el);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = Ui.dp(14);
        TextView no = Ui.pill(this, "Vazgeç", 12.5f, Ui.TEXT, Ui.BUBBLE, 0);
        no.setOnClickListener(v -> d.dismiss());
        row.addView(no);
        TextView ok = Ui.pill(this, "İndir", 12.5f, 0xFFFFFFFF, Ui.TEXT, 0);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2); ol.leftMargin = Ui.dp(8);
        ok.setOnClickListener(v -> {
            String nm = ed.getText().toString().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (nm.isEmpty()) nm = def;
            if (!nm.contains(".")) nm += "." + ext;
            d.dismiss();
            try {
                String mm = (nm.endsWith(".html") || nm.endsWith(".htm")) ? "text/html" : "text/plain";
                saveBytesToDownloads(text.getBytes("UTF-8"), nm, mm);
            } catch (Exception ex) { toast("Kaydedilemedi"); }
        });
        row.addView(ok, ol);
        box.addView(row, rl);
        wrap.addView(box, new LinearLayout.LayoutParams(-1, -2));
        d.setContentView(wrap);
        Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawable(new ColorDrawable(0)); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
        d.show();
    }

    private void saveBytesToDownloads(final byte[] data, final String name, final String mime) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSaveBytes = data; pendingSaveName = name; pendingSaveMime = mime;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_SAVE_PERM);
            return;
        }
        new Thread(() -> {
            try {
                String outName = name;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, outName);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("MediaStore hatası");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(data); }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    File out = new File(dir, outName);
                    int k = 1, dot = name.lastIndexOf(".");
                    String base = dot > 0 ? name.substring(0, dot) : name, ep = dot > 0 ? name.substring(dot) : "";
                    while (out.exists()) { outName = base + "_" + (k++) + ep; out = new File(dir, outName); }
                    try (OutputStream os = new FileOutputStream(out)) { os.write(data); }
                    MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()}, new String[]{mime}, null);
                }
                final String fn = outName;
                runOnUiThread(() -> toast("İndirilenlere kaydedildi: " + fn));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Kaydedilemedi: " + e.getMessage()));
            }
        }).start();
    }

    private void shareFile(String sub, String name) {
        try {
            Uri u = Uri.parse("content://" + WebSession.AUTH + "/" + sub + "/" + Uri.encode(name));
            Intent i = new Intent(Intent.ACTION_SEND);
            String n = name.toLowerCase();
            i.setType(n.endsWith(".md") ? "text/markdown" : n.endsWith(".jpg") ? "image/jpeg" : n.endsWith(".webp") ? "image/webp" : n.endsWith(".png") ? "image/png" : "*/*");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Paylaş / Kaydet"));
        } catch (Exception e) { toast("Paylaşılamadı: " + e.getMessage()); }
    }

    private void exportChat(Chat c) {
        Chat full = store.load(c.id);
        if (full == null) return;
        StringBuilder sb = new StringBuilder("# ").append(full.title).append("\n\n");
        for (Msg m : full.msgs) {
            sb.append(m.role.equals(Msg.USER) ? "**Sen:**\n\n" : "**" + (m.site == null ? "Asistan" : Site.fromId(m.site).label) + ":**\n\n").append(m.display()).append("\n\n---\n\n");
        }
        try {
            File dir = new File(getCacheDir(), "export");
            dir.mkdirs();
            String nm = full.title.replaceAll("[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ _-]", "").trim().replace(' ', '_');
            if (nm.isEmpty()) nm = "sohbet";
            File f = new File(dir, nm + ".md");
            try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(sb.toString().getBytes("UTF-8")); }
            shareFile("export", f.getName());
        } catch (Exception e) { toast("Dışa aktarılamadı"); }
    }

    private MdView newMd() {
        MdView md = new MdView(this);
        md.setStyle(14.5f, Typeface.SERIF, Ui.TEXT);
        md.setCopier((text, what) -> copyText(text, what));
        md.setSaver((text, lang) -> showSaveCodeDialog(text, lang));
        return md;
    }

    private View aiView(final Msg m) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        MdView md = newMd();
        md.setMarkdown(m.text);
        col.addView(md, new LinearLayout.LayoutParams(-1, -2));
        if (!m.imgs.isEmpty()) col.addView(imagesRow(m.imgs, false), new LinearLayout.LayoutParams(-1, -2));
        col.addView(doneFooter(m.text, m.site), footLp());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(18);
        col.setLayoutParams(lp);
        return col;
    }

    private LinearLayout.LayoutParams footLp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.topMargin = Ui.dp(6);
        return l;
    }

    private View doneFooter(final String text, String siteId) {
        LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER_VERTICAL);
        final TextView copy = Ui.text(this, "Kopyala", 11.5f, Ui.MUTED);
        copy.setPadding(Ui.dp(2), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        ImageView ic = Ui.icon(this, R.drawable.ic_copy, 14, Ui.MUTED);
        f.addView(ic);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = Ui.dp(4);
        f.addView(copy, cl);
        View.OnClickListener l = v -> {
            copyText(text, "Yanıt kopyalandı");
            copy.setText("Kopyalandı ✓");
            h.postDelayed(() -> copy.setText("Kopyala"), 1600);
        };
        f.setOnClickListener(l);
        if (siteId != null) f.addView(Ui.text(this, Site.fromId(siteId).label, 11, 0xFFB4B1A8));
        return f;
    }

    private void onSendClick() { if (core.generating) { core.stopGen(); if (!core.awaitingStart && curFoot != null) curFoot.setText("Durduruluyor…"); return; } doSend(); }
    private void doSend() { String t = input.getText().toString().trim(); if (t.isEmpty() && core.atts.isEmpty()) return; if (core.atts.isEmpty() && handleSlashInput(t)) return; if (core.doSendUser(t)) input.setText(""); }
    private void startAiUi(boolean resetTh) {
        if (resetTh) curThOpen = false; hasUiText = false;
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); lastAiCol = col;
        curThWrap = new LinearLayout(this); curThWrap.setOrientation(LinearLayout.VERTICAL); curThWrap.setBackground(Ui.round(Ui.CODEBG, 10, Ui.BORDER, 1)); curThWrap.setPadding(Ui.dp(10), Ui.dp(7), Ui.dp(10), Ui.dp(7)); curThWrap.setVisibility(View.GONE);
        LinearLayout.LayoutParams thl = new LinearLayout.LayoutParams(-1, -2); thl.bottomMargin = Ui.dp(8);
        curThHdr = Ui.text(this, "▸ Düşünüyor…", 11.5f, Ui.MUTED); curThWrap.addView(curThHdr, new LinearLayout.LayoutParams(-1, -2));
        curThBody = Ui.text(this, "", 12f, Ui.MUTED); curThBody.setLineSpacing(0, 1.25f); curThBody.setPadding(0, Ui.dp(6), 0, 0); curThBody.setVisibility(View.GONE); curThWrap.addView(curThBody, new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout tw = curThWrap; final TextView th = curThHdr, tb = curThBody;
        tw.setOnClickListener(v -> { boolean o = tb.getVisibility() != View.VISIBLE; tb.setVisibility(o ? View.VISIBLE : View.GONE); if (tw == curThWrap) curThOpen = o; String ct = th.getText().toString(); th.setText((o ? "▾ " : "▸ ") + (ct.length() > 2 ? ct.substring(2) : ct)); });
        col.addView(curThWrap, thl); curMd = newMd(); col.addView(curMd, new LinearLayout.LayoutParams(-1, -2));
        curFoot = Ui.text(this, "Yanıt bekleniyor…", 11.5f, Ui.MUTED); col.addView(curFoot, footLp());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = Ui.dp(18); messages.addView(col, lp); updateSendBtn(); scrollBottom(); h.removeCallbacks(uiTimerR); h.postDelayed(uiTimerR, 1000);
    }
    private void updateTimerFoot() { if (!core.generating || curFoot == null) return; long sec = Math.max(0, (System.currentTimeMillis() - core.genStart) / 1000); String b = !core.stage.isEmpty() ? core.stage : core.awaitingStart ? "Web oturumu hazırlanıyor…" : core.gotFirstText ? "Yazıyor…" : core.curThActive ? "Düşünüyor…" : "Yanıt bekleniyor…"; curFoot.setText(b + " " + sec + " sn"); }
    private final Runnable uiTimerR = new Runnable() { @Override public void run() { if (!core.generating || curFoot == null) return; updateTimerFoot(); h.postDelayed(this, 1000); } };
    private void showErrView(String err) { boolean act = !err.startsWith("İptal"); TextView e = Ui.text(this, act ? err + "  (Seçenekler için dokun)" : err, 12.5f, Ui.RED); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = Ui.dp(14); messages.addView(e, lp); if (act) { e.setOnClickListener(v -> errorActions()); lastErrView = e; } }
    private void errorActions() { if (!core.generating) new AlertDialog.Builder(this).setTitle("Ne yapalım?").setItems(new String[]{"Cevabı web'den çek", "Mesajı tekrar gönder", "Web sekmesini aç"}, (d, w) -> { if (w == 0) recoverFromWeb(); else if (w == 1) resendLast(); else showWeb(true); }).show(); }
    private void dropErrView() { if (lastErrView != null && lastErrView.getParent() == messages) messages.removeView(lastErrView); lastErrView = null; }
    private void recoverFromWeb() { if (!core.generating) { dropErrView(); core.recoverFromWeb(); } }
    private void resendLast() { if (!core.generating) { dropErrView(); core.resendLast(); } }
    private void addSwitchBanner(String txt) { TextView tv = Ui.pill(this, txt, 11f, Ui.MUTED, Ui.BUBBLE, 0); tv.setPadding(Ui.dp(12), Ui.dp(5), Ui.dp(12), Ui.dp(5)); LinearLayout w = new LinearLayout(this); w.setGravity(Gravity.CENTER); w.addView(tv, new LinearLayout.LayoutParams(-2, -2)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = Ui.dp(16); messages.addView(w, lp); lastSwitchBanner = w; }

    @Override public void onCoreStatus(int st, String info) {
        int c = st == WebSession.ST_READY ? Ui.GREEN : st == WebSession.ST_LOADING ? Ui.AMBER : st == WebSession.ST_LOGIN ? Ui.RED : Ui.MUTED;
        String tk = session.tokens(); String txt = core.site.label + " · " + info + (core.site == Site.AISTUDIO && !tk.isEmpty() ? " · " + tk : "");
        statusDot.setBackground(Ui.round(c, 4, 0, 0)); statusTv.setText(txt); webDot.setBackground(Ui.round(c, 4, 0, 0)); webStatusTv.setText(info); updateWebBar();
    }
    @Override public void onCoreChatChanged() { renderChat(); refreshCodeUi(); refreshAttach(); updateChip(); }
    @Override public void onCoreUserMsg(Msg m) { dropErrView(); welcome.setVisibility(View.GONE); titleTv.setText(core.chat.title); messages.addView(userView(m)); scrollBottom(); }
    @Override public void onCoreAiStart(String switchBanner) { dropErrView(); welcome.setVisibility(View.GONE); if (switchBanner != null && !switchBanner.isEmpty()) addSwitchBanner(switchBanner); startAiUi(true); }
    @Override public void onCoreAiThinking(String text, boolean active) { if (curThWrap == null) return; boolean at = isAtBottom(); curThWrap.setVisibility(View.VISIBLE); if (text != null && !text.isEmpty()) curThBody.setText(text); curThHdr.setText((curThOpen ? "▾ " : "▸ ") + (active && !hasUiText ? "Düşünüyor…" : "Düşünce")); if (at) scrollBottom(); }
    @Override public void onCoreAiReply(String md) { if (curMd == null) return; boolean at = isAtBottom(); if (!md.isEmpty()) { hasUiText = true; if (curThHdr != null && curThWrap != null && curThWrap.getVisibility() == View.VISIBLE) curThHdr.setText((curThOpen ? "▾ " : "▸ ") + "Düşünce"); } curMd.setMarkdown(md); if (at) scrollBottom(); }
    @Override public void onCoreAiDone(Msg am, List<CodeProtocol.Action> approval) {
        h.removeCallbacks(uiTimerR); boolean at = isAtBottom(); if (curMd != null) curMd.setMarkdown(am.text);
        if (curFoot != null && curFoot.getParent() instanceof LinearLayout) { LinearLayout col = (LinearLayout) curFoot.getParent(); lastAiCol = col; col.removeView(curFoot); if (!am.imgs.isEmpty()) col.addView(imagesRow(am.imgs, false), new LinearLayout.LayoutParams(-1, -2)); col.addView(doneFooter(am.text, am.site), footLp()); }
        if (curThHdr != null && curThWrap != null && curThWrap.getVisibility() == View.VISIBLE) curThHdr.setText((curThOpen ? "▾ " : "▸ ") + "Düşünce");
        if (approval != null && !approval.isEmpty() && lastAiCol != null) lastAiCol.addView(CodeUi.buildApprovalCard(this, approval, codeHost()));
        curThWrap = null; curThHdr = null; curThBody = null; lastSwitchBanner = null; curMd = null; curFoot = null; updateSendBtn(); if (at) scrollBottom();
    }
    @Override public void onCoreCodeAutoDone(List<CodeProtocol.ExecResult> res, boolean sentBack, boolean hasUndo) { if (lastAiCol == null) return; LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(Ui.round(Ui.CODEBG, 14, Ui.BORDER, 1)); card.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10)); LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = Ui.dp(8); card.setLayoutParams(cl); CodeUi.markCardDone(card, res, sentBack, hasUndo, () -> runUndoWithPerm()); lastAiCol.addView(card); scrollBottom(); }
    @Override public void onCoreCsApproval(List<CsProtocol.Action> actions) { if (lastAiCol == null || actions == null || actions.isEmpty()) return; lastAiCol.addView(CsUi.buildApprovalCard(this, actions, csUiHost())); scrollBottom(); }
    @Override public void onCoreCsExecDone(List<CsProtocol.ExecResult> res, boolean sentBack) { if (lastAiCol == null) return; lastAiCol.addView(CsUi.buildResultCard(this, res, sentBack)); scrollBottom(); }
    @Override public void onCoreAiError(String err) {
        h.removeCallbacks(uiTimerR); if (err.startsWith("İptal") && lastSwitchBanner != null && lastSwitchBanner.getParent() == messages) messages.removeView(lastSwitchBanner);
        lastSwitchBanner = null; boolean act = !err.startsWith("İptal"); String shownErr = act ? err + "  (Seçenekler için dokun)" : err; lastErrView = null;
        if (curMd != null && !hasUiText && curMd.getParent() instanceof LinearLayout) { messages.removeView((View) curMd.getParent()); showErrView(err); }
        else if (curFoot != null) { curFoot.setText(shownErr); curFoot.setTextColor(Ui.RED); if (act) { curFoot.setOnClickListener(v -> errorActions()); if (curFoot.getParent() instanceof View) lastErrView = (View) curFoot.getParent(); } }
        if (curThHdr != null && curThWrap != null && curThWrap.getVisibility() == View.VISIBLE) curThHdr.setText((curThOpen ? "▾ " : "▸ ") + "Düşünce");
        curThWrap = null; curThHdr = null; curThBody = null; curMd = null; curFoot = null; updateSendBtn(); scrollBottom();
    }
    @Override public void onCoreCodeUi() { refreshCodeUi(); }
    @Override public void onCoreAttachUi() { refreshAttach(); }
    @Override public void onCoreToast(String msg) { toast(msg); }
    @Override public void onCoreNote(String msg) { if (msg.startsWith("__OPEN_CODE__:")) requestEnterCodeMode(msg.substring(14)); else if (msg.startsWith("DOM kaydedildi")) new AlertDialog.Builder(this).setTitle("DOM kaydedildi").setMessage(msg.replace("DOM kaydedildi", "").trim() + "\n\nBu dosyayı DOM Analizcisi modeline yükleyebilirsin.").setPositiveButton("Tamam", null).show(); else toast(msg); }
    @Override public boolean onCoreFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) { if (webFileCb != null) webFileCb.onReceiveValue(null); webFileCb = cb; try { startActivityForResult(p.createIntent(), REQ_WEBFILE); return true; } catch (Exception e) { webFileCb = null; return false; } }
    @Override public void onCoreFinish() { finish(); }

    // ================================================================== web sekmesi
    private void showWeb(boolean on) {
        webMode = on;
        hideKeyboard();
        chatPage.setVisibility(on ? View.GONE : View.VISIBLE);
        applyBgRender();
        updateWebBar();
    }

    private void applyBgRender() {
        boolean bg = prefs.getBoolean("bg", true);
        webPage.setVisibility(webMode || bg ? View.VISIBLE : View.INVISIBLE);
        updateWebBar();
    }

    private void updateWebBar() {
        if (webSiteBtn == null) return;
        webSiteBtn.setText("Kaynak: " + core.site.label);
        webDisplayBtn.setText("Arka plan görüntü: " + (prefs.getBoolean("bg", true) ? "Açık" : "Kapalı"));
        webConnBtn.setText(session.isConnected() ? "Bağlantıyı kes" : "Bağlan");
    }

    // ================================================================== kaynak / model / düşünme
    private String thinking() { return core.thinking(); }
    private String maxOutput() { return core.maxOutput(); }
    private void pushPrefs() { core.pushPrefs(); }
    private void updateChip() { String lv = core.thinking(); int i = 0; for (int k = 0; k < LEVELS.length; k++) if (LEVELS[k].equals(lv)) i = k; chipTv.setText(core.site.label + " · " + (i == 0 ? "Otomatik" : LEVEL_LABELS[i]) + "  ▾"); updateWebBar(); }

    private void setSite(final Site s) { if (s == core.site) return; if (core.generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; } if (core.chat.msgs.isEmpty()) { core.applySiteSwitch(s, false); updateChip(); return; } showSwitchDialog(s.label, () -> { core.applySiteSwitch(s, true); updateChip(); }, () -> { core.applySiteSwitch(s, false); updateChip(); }); }
    private void showSwitchDialog(String targetLabel, final Runnable onTransfer, final Runnable onFresh) {
        final Dialog d = new Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout wrap = new LinearLayout(this); wrap.setPadding(Ui.dp(22), Ui.dp(16), Ui.dp(22), Ui.dp(16));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setBackground(Ui.round(Ui.BG, 22, Ui.BORDER, 1)); box.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(18));
        TextView tt = Ui.text(this, "Model değiştir", 17, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        TextView msg = Ui.text(this, "Bu sohbette " + core.chat.msgs.size() + " mesaj var. " + targetLabel + " modeline geçerken sohbet geçmişi ne yapılsın?", 13, Ui.MUTED);
        msg.setLineSpacing(0, 1.28f); LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = Ui.dp(8); box.addView(msg, ml);
        TextView b1 = Ui.pill(this, "Sohbet geçmişini aktar", 13.5f, 0xFFFFFFFF, Ui.TEXT, 0); b1.setOnClickListener(v -> { d.dismiss(); onTransfer.run(); });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-1, Ui.dp(44)); l1.topMargin = Ui.dp(16); box.addView(b1, l1);
        TextView b2 = Ui.pill(this, "Aktarmadan geçiş yap", 13.5f, Ui.TEXT, Ui.BUBBLE, Ui.BORDER); b2.setOnClickListener(v -> { d.dismiss(); onFresh.run(); });
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-1, Ui.dp(44)); l2.topMargin = Ui.dp(8); box.addView(b2, l2);
        TextView b3 = Ui.pill(this, "İptal", 13.5f, Ui.MUTED, Ui.SURFACE, Ui.BORDER); b3.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(-1, Ui.dp(42)); l3.topMargin = Ui.dp(8); box.addView(b3, l3);
        wrap.addView(box, new LinearLayout.LayoutParams(-1, -2)); d.setContentView(wrap);
        Window w = d.getWindow(); if (w != null) { w.setBackgroundDrawable(new ColorDrawable(0)); w.setGravity(Gravity.CENTER); w.setDimAmount(0.4f); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
        d.show();
    }

    private void setThinking(String lv) { core.setThinking(lv); updateChip(); }

    private Dialog sheet(View content) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.35f);
        }
        return d;
    }

    private GradientDrawable topRound() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.BG);
        float r = Ui.dp(22);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    private void showModelSheet() {
        if (core.generating) { toast("Yanıt sürerken değiştirilemez"); return; }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(topRound());
        c.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(22));
        final Dialog d = sheet(c);

        c.addView(Ui.text(this, "Kaynak", 11.5f, Ui.MUTED));
        for (final Site s : Site.values()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(12), Ui.dp(11), Ui.dp(12), Ui.dp(11));
            boolean sel = s == core.site;
            row.setBackground(Ui.ripple(Ui.round(sel ? Ui.BUBBLE : Ui.SURFACE, 12, Ui.BORDER, sel ? 0 : 1)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(Ui.text(this, s.label, 14, Ui.TEXT));
            TextView sub = Ui.text(this, s.host, 10.5f, Ui.MUTED);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.topMargin = Ui.dp(3);
            tx.addView(sub, sl);
            row.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
            if (sel) row.addView(Ui.icon(this, R.drawable.ic_check, 18, Ui.TEXT));
            row.setOnClickListener(v -> { d.dismiss(); setSite(s); });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = Ui.dp(8);
            c.addView(row, rl);
        }

        TextView th = Ui.text(this, "Düşünme düzeyi", 11.5f, Ui.MUTED);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = Ui.dp(18);
        c.addView(th, tl);
        int count = core.site == Site.AISTUDIO ? LEVELS.length : 4;
        String cur = thinking();
        if (count <= 4) {
            LinearLayout chips = new LinearLayout(this);
            for (int i = 0; i < count; i++) {
                final String lv = LEVELS[i];
                boolean sel = lv.equals(cur);
                TextView p = Ui.pill(this, LEVEL_LABELS[i], 12.5f, sel ? 0xFFFFFFFF : Ui.TEXT, sel ? Ui.TEXT : Ui.SURFACE, sel ? 0 : Ui.BORDER);
                p.setOnClickListener(v -> { d.dismiss(); setThinking(lv); });
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, Ui.dp(38), 1f);
                if (i < count - 1) pl.rightMargin = Ui.dp(6);
                chips.addView(p, pl);
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.topMargin = Ui.dp(8);
            c.addView(chips, cl);
        } else {
            int[][] rows = {{0, 3}, {3, count}};
            for (int r = 0; r < rows.length; r++) {
                LinearLayout chips = new LinearLayout(this);
                for (int i = rows[r][0]; i < rows[r][1]; i++) {
                    final String lv = LEVELS[i];
                    boolean sel = lv.equals(cur);
                    TextView p = Ui.pill(this, LEVEL_LABELS[i], 12.5f, sel ? 0xFFFFFFFF : Ui.TEXT, sel ? Ui.TEXT : Ui.SURFACE, sel ? 0 : Ui.BORDER);
                    p.setOnClickListener(v -> { d.dismiss(); setThinking(lv); });
                    LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, Ui.dp(38), 1f);
                    if (i < rows[r][1] - 1) pl.rightMargin = Ui.dp(6);
                    chips.addView(p, pl);
                }
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
                cl.topMargin = Ui.dp(r == 0 ? 8 : 6);
                c.addView(chips, cl);
            }
        }

        if (core.site.modelPicker) {
            TextView mh = Ui.text(this, "Model", 11.5f, Ui.MUTED);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.topMargin = Ui.dp(18);
            c.addView(mh, ml);
            String m = prefs.getString("model_" + core.site.id, "");
            TextView mb = Ui.pill(this, (m.isEmpty() ? "Sitenin varsayılanı" : m) + "   ›", 13, Ui.TEXT, Ui.SURFACE, Ui.BORDER);
            mb.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            mb.setOnClickListener(v -> { d.dismiss(); pickModel(); });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(44));
            bl.topMargin = Ui.dp(8);
            c.addView(mb, bl);
            String curOut = maxOutput();
            TextView oh = Ui.text(this, "Output uzunluğu · " + curOut, 11.5f, Ui.MUTED);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
            olp.topMargin = Ui.dp(18);
            c.addView(oh, olp);
            LinearLayout orow = new LinearLayout(this);
            String[] oVals = {"65536", "262144", "1048576"};
            String[] oLbls = {"65K", "262K", "1M"};
            boolean isPre = "65536".equals(curOut) || "262144".equals(curOut) || "1048576".equals(curOut);
            for (int i = 0; i < 3; i++) {
                final String ov = oVals[i];
                boolean sel = ov.equals(curOut);
                TextView p = Ui.pill(this, oLbls[i], 12.5f, sel ? 0xFFFFFFFF : Ui.TEXT, sel ? Ui.TEXT : Ui.SURFACE, sel ? 0 : Ui.BORDER);
                p.setOnClickListener(v -> { d.dismiss(); setMaxOutput(ov); });
                LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, Ui.dp(38), 1f);
                pl.rightMargin = Ui.dp(6);
                orow.addView(p, pl);
            }
            TextView cp = Ui.pill(this, "Özel", 12.5f, !isPre ? 0xFFFFFFFF : Ui.TEXT, !isPre ? Ui.TEXT : Ui.SURFACE, !isPre ? 0 : Ui.BORDER);
            cp.setOnClickListener(v -> { d.dismiss(); askMaxOutput(); });
            orow.addView(cp, new LinearLayout.LayoutParams(0, Ui.dp(38), 1f));
            LinearLayout.LayoutParams orl = new LinearLayout.LayoutParams(-1, -2);
            orl.topMargin = Ui.dp(8);
            c.addView(orow, orl);
        } else {
            TextView n = Ui.text(this, "Ücretsiz Gemini'de model sabit (Auto); sadece düşünme düzeyi seçilebilir.", 10.5f, Ui.MUTED);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
            nl.topMargin = Ui.dp(14);
            c.addView(n, nl);
        }
        d.show();
    }

    private void pickModel() {
        if (!session.isUsable()) { toast("Önce oturumun hazır olmasını bekle (üstte yeşil nokta)"); return; }
        toast("Modeller getiriliyor…");
        session.op("listModels", null, r -> {
            JSONArray arr = r.optJSONArray("models");
            final List<String> names = new ArrayList<>();
            names.add("Sitenin varsayılanı");
            if (arr != null) for (int i = 0; i < arr.length(); i++) names.add(arr.optString(i));
            names.add("Adı elle yaz…");
            new AlertDialog.Builder(this).setTitle(arr == null ? "Liste alınamadı" : "Model seç")
                    .setItems(names.toArray(new String[0]), (d, which) -> {
                        if (which == 0) { setModel(""); }
                        else if (which == names.size() - 1) askModelName();
                        else setModel(names.get(which));
                    }).show();
        });
    }

    private void askModelName() {
        final EditText e = new EditText(this);
        e.setHint("örn: Gemini 3.8 Flash");
        e.setText(prefs.getString("model_" + core.site.id, ""));
        new AlertDialog.Builder(this).setTitle("AI Studio model adı").setView(e)
                .setPositiveButton("Kaydet", (d, w) -> setModel(e.getText().toString().trim()))
                .setNegativeButton("Vazgeç", null).show();
    }

    private void setModel(final String name) { String cur = prefs.getString("model_" + core.site.id, ""); if (name.equals(cur)) { toast(name.isEmpty() ? "Model: varsayılan" : "Model: " + name); return; } if (core.generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; } if (core.chat.msgs.isEmpty()) { core.applyModelSwitch(name, false); return; } String lbl = name.isEmpty() ? core.site.label + " (Varsayılan)" : name; showSwitchDialog(lbl, () -> core.applyModelSwitch(name, true), () -> core.applyModelSwitch(name, false)); }
    private void askMaxOutput() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint("örn: 65536 - 1048576");
        e.setText(maxOutput());
        new AlertDialog.Builder(this).setTitle("Max Output Token").setView(e)
                .setPositiveButton("Kaydet", (d, w) -> {
                    String v = e.getText().toString().replaceAll("[^0-9]", "");
                    if (v.isEmpty()) v = "65536";
                    else { try { if (Integer.parseInt(v) > 1048576) v = "1048576"; } catch (Exception ex) { v = "1048576"; } }
                    setMaxOutput(v);
                })
                .setNeutralButton("65K", (d, w) -> setMaxOutput("65536"))
                .setNegativeButton("Vazgeç", null).show();
    }

    private void setMaxOutput(String v) { core.setMaxOutput(v); }

    // ================================================================== ayarlar
    private void showSettings() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dp(20), Ui.dp(14), Ui.dp(20), Ui.dp(6));

        final Switch bg = new Switch(this);
        bg.setText("Web'i arka planda görünür tut (kapalıysa RAM tasarrufu, ama yavaşlayabilir)");
        bg.setTextSize(Ui.sp(13));
        bg.setChecked(prefs.getBoolean("bg", true));
        bg.setOnCheckedChangeListener((b, on) -> { prefs.edit().putBoolean("bg", on).apply(); applyBgRender(); });
        c.addView(bg, new LinearLayout.LayoutParams(-1, -2));

        final Switch ua = new Switch(this);
        ua.setText("Google girişi engellenirse: Firefox kimliği kullan (sayfayı yenile)");
        ua.setTextSize(Ui.sp(13));
        ua.setChecked(prefs.getInt("ua", 0) == 1);
        ua.setOnCheckedChangeListener((b, on) -> { prefs.edit().putInt("ua", on ? 1 : 0).apply(); session.setUaMode(on ? 1 : 0); });
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-1, -2);
        ul.topMargin = Ui.dp(16);
        c.addView(ua, ul);

        final Switch ol = new Switch(this);
        ol.setText("Açılışta son sohbeti göster");
        ol.setTextSize(Ui.sp(13));
        ol.setChecked(prefs.getBoolean("openLast", true));
        ol.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("openLast", on).apply());
        LinearLayout.LayoutParams ol2 = new LinearLayout.LayoutParams(-1, -2);
        ol2.topMargin = Ui.dp(16);
        c.addView(ol, ol2);

        TextView fl = Ui.text(this, "Yazı boyutu", 12, Ui.MUTED);
        LinearLayout.LayoutParams fl2 = new LinearLayout.LayoutParams(-2, -2);
        fl2.topMargin = Ui.dp(18);
        c.addView(fl, fl2);
        LinearLayout fr = new LinearLayout(this);
        final float[] sizes = {0.9f, 1f, 1.12f};
        String[] names = {"Küçük", "Orta", "Büyük"};
        for (int i = 0; i < 3; i++) {
            final float s = sizes[i];
            boolean sel = Math.abs(Ui.fontScale - s) < 0.01f;
            TextView p = Ui.pill(this, names[i], 12.5f, sel ? 0xFFFFFFFF : Ui.TEXT, sel ? Ui.TEXT : Ui.BUBBLE, 0);
            p.setOnClickListener(v -> {
                prefs.edit().putFloat("fs", s).apply();
                toast("Yazı boyutu uygulamayı yeniden açınca tam yerleşir");
                Ui.fontScale = s;
                renderChat();
            });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, Ui.dp(38), 1f);
            pl.rightMargin = Ui.dp(6);
            fr.addView(p, pl);
        }
        LinearLayout.LayoutParams frl = new LinearLayout.LayoutParams(-1, -2);
        frl.topMargin = Ui.dp(8);
        c.addView(fr, frl);

        TextView dom = Ui.pill(this, "Sayfanın DOM'unu kaydet (teşhis)", 12.5f, Ui.TEXT, Ui.BUBBLE, 0);
        dom.setOnClickListener(v -> session.saveDom());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Ui.dp(40));
        dl.topMargin = Ui.dp(18);
        c.addView(dom, dl);

        TextView del = Ui.pill(this, "Tüm sohbetleri sil", 12.5f, Ui.RED, Ui.BUBBLE, 0);
        del.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Tüm sohbetler silinsin mi?")
                .setPositiveButton("Sil", (d, w) -> core.deleteAllChats())
                .setNegativeButton("Vazgeç", null).show());
        LinearLayout.LayoutParams del2 = new LinearLayout.LayoutParams(-1, Ui.dp(40));
        del2.topMargin = Ui.dp(8);
        c.addView(del, del2);

        new AlertDialog.Builder(this).setTitle("Ayarlar").setView(c).setPositiveButton("Kapat", null).show();
    }

    // ================================================================== dosya ekleme
    private static final String TEXT_EXT = ",txt,md,csv,tsv,json,xml,html,htm,css,js,mjs,ts,tsx,jsx,java,kt,kts,py,c,cc,cpp,h,hpp,cs,go,rs,rb,php,sh,bat,yml,yaml,toml,ini,cfg,conf,sql,gradle,properties,log,tex,svg,vue,swift,dart,lua,r,env,gitignore,";
    private static final String BIN_EXT = ",zip,tar,gz,tgz,bz2,xz,7z,rar,png,jpg,jpeg,gif,webp,bmp,heic,pdf,mp3,wav,m4a,ogg,flac,aac,mp4,mov,webm,mkv,avi,3gp,doc,docx,xls,xlsx,ppt,pptx,apk,exe,bin,";

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_PICK); } catch (Exception e) { toast("Dosya seçici açılamadı"); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_WEBFILE) {
            if (webFileCb != null) {
                webFileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                webFileCb = null;
            }
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        toast("Dosyalar hazırlanıyor…");
        final List<Uri> fu = uris;
        new Thread(() -> {
            final List<AppCore.Att> added = new ArrayList<>();
            for (Uri u : fu) { AppCore.Att a = readAttachment(u); if (a != null) added.add(a); }
            runOnUiThread(() -> { core.atts.addAll(added); refreshAttach(); core.pumpPreUpload(); });
        }).start();
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private String guessMime(String name) {
        String e = ext(name);
        switch (e) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mov": return "video/quicktime";
            default: return "application/octet-stream";
        }
    }

    private boolean looksText(String name, String mime, File f) {
        String e = "," + ext(name) + ",";
        if (BIN_EXT.contains(e) && e.length() > 2) return false;
        if (TEXT_EXT.contains(e) && e.length() > 2) return true;
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/"))) return false;
        if (mime != null && mime.startsWith("text/")) return true;
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4096];
            int n = in.read(b);
            for (int i = 0; i < n; i++) if (b[i] == 0) return false;
            return true;
        } catch (Exception ex) { return false; }
    }

    /** Arka plan iş parçacığında çalışır: dosyayı önbelleğe kopyalar, metin mi ikili mi karar verir. */
    private AppCore.Att readAttachment(Uri u) {
        String name = "dosya";
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0 && c.getString(ix) != null) name = c.getString(ix);
            }
        } catch (Exception ignored) {}
        final String fname = name;
        String mime = getContentResolver().getType(u);
        if (mime == null || mime.equals("application/octet-stream")) mime = guessMime(name);
        File dir = new File(getCacheDir(), "up");
        dir.mkdirs();
        String id = Long.toHexString(System.nanoTime());
        File f = new File(dir, id + "_" + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        long total = 0;
        try (InputStream in = getContentResolver().openInputStream(u); FileOutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > 30L * 1024 * 1024) { f.delete(); runOnUiThread(() -> toast(fname + " çok büyük (en fazla 30 MB). Büyük dosyayı Web sekmesinden yükle.")); return null; }
                out.write(b, 0, n);
            }
        } catch (Exception e) { f.delete(); runOnUiThread(() -> toast("Okunamadı: " + fname)); return null; }

        AppCore.Att a = new AppCore.Att();
        a.id = id; a.name = name; a.mime = mime; a.size = total; a.file = f;
        if (looksText(name, mime, f) && total <= 600 * 1024) {
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = new java.io.FileInputStream(f)) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                }
                a.text = true;
                a.content = bo.toString("UTF-8");
                f.delete();
            } catch (Exception ignored) { a.text = false; }
        }
        if (!a.text && mime.startsWith("image/")) {
            try {
                File th = store.img("u_" + id + "." + (ext(name).isEmpty() ? "png" : ext(name)));
                try (InputStream in = new java.io.FileInputStream(f); FileOutputStream out = new FileOutputStream(th)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                a.thumb = th.getName();
            } catch (Exception ignored) {}
        }
        if (!a.text) a.upState = 1;
        return a;
    }

    private String sizeStr(long b) { return b >= 1024 * 1024 ? String.format(java.util.Locale.US, "%.1f MB", b / 1048576f) : Math.max(1, b / 1024) + " KB"; }

    private void refreshAttach() {
        attachInner.removeAllViews();
        attachRow.setVisibility(core.atts.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < core.atts.size(); i++) {
            final AppCore.Att a = core.atts.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(Ui.dp(6), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            chip.setBackground(Ui.round(Ui.BUBBLE, 14, 0, 0));
            if (a.thumb != null) {
                ImageView th = new ImageView(this);
                th.setScaleType(ImageView.ScaleType.CENTER_CROP);
                loadInto(th, store.img(a.thumb), 96);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28));
                tl.rightMargin = Ui.dp(6);
                chip.addView(th, tl);
            } else chip.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            String tag = a.text ? "metin"
                    : a.upState == 1 ? "loading…"
                    : a.upState == 2 ? (!a.tokens.isEmpty() ? a.tokens : "yüklendi")
                    : a.upState == 3 ? "yüklenemedi (dokun)"
                    : "yüklenecek";
            chip.addView(Ui.text(this, a.name + " · " + sizeStr(a.size) + " · " + tag, 11.5f, a.upState == 3 ? Ui.RED : Ui.TEXT));
            if (a.upState == 3) {
                chip.setOnClickListener(v -> {
                    a.upState = 1;
                    refreshAttach();
                    core.pumpPreUpload();
                });
            }
            ImageView x = Ui.icon(this, R.drawable.ic_close, 22, Ui.MUTED);
            x.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            x.setOnClickListener(v -> {
                boolean busy = (a == core.activeUpload);
                a.removed = true;
                int ridx = a.webIdx;
                boolean wasUp = a.upState == 2;
                core.atts.remove(a);
                if (wasUp) {
                    for (AppCore.Att o : core.atts) if (!o.text && o.upState == 2 && ridx >= 0 && o.webIdx > ridx) o.webIdx--;
                    session.removeAttached(a.name, a.wname, ridx);
                }
                if (!busy && a.file != null) a.file.delete();
                if (a.thumb != null) store.img(a.thumb).delete();
                refreshAttach();
            });
            chip.addView(x);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.rightMargin = Ui.dp(6); l.bottomMargin = Ui.dp(4);
            attachInner.addView(chip, l);
        }
        updateSendBtn();
    }

    private void refreshCodeUi() {
        if (modeTabs != null) modeTabs.setCode(core.codeMode);
        if (codeBar != null && core.ws != null) codeBar.update(core.codeMode, core.ws.getDisplayPath(), core.chat.codeAuto, core.codeMode && core.ws.canUndo());
        if (csBar != null) csBar.update(core.cs.isBgActive(), core.cs.curCwd, core.cs.cfg.isAuto(), core.cs.running, core.cs.canUndo);
        if (slashWrap != null && input != null) CodeUi.updateSlashBar(slashWrap, slashRow, input.getText().toString(), core.codeMode, codeHost());
    }

    private CsUi.Host csUiHost() { return new CsUi.Host() {
        public void onCsToggleAuto() { boolean na = !core.cs.cfg.isAuto(); core.cs.cfg.setAuto(na); refreshCodeUi(); core.updateNotif(); toast(na ? "Codespaces: Ozgur" : "Codespaces: Guvenli"); }
        public void onCsConfig() { CsUi.showConfigDialog(MainActivity.this, core.cs, () -> { refreshCodeUi(); core.updateNotif(); if (!core.cs.mode) withStoragePerm(() -> core.cs.activate("")); }); }
        public void onCsStop() { core.cs.stopRunning(); }
        public void onCsUndo() { core.cs.runUndo(); }
        public void onCsExit() { if (core.generating || core.cs.running) { toast("Once aktif islemi durdur"); return; } core.cs.deactivate(true, !core.chat.msgs.isEmpty()); }
        public void onCsApprove(List<CsProtocol.Action> a, boolean sb, LinearLayout c) { core.cs.execute(a, sb, false, r -> CsUi.markCardDone(c, r, sb)); }
        public void onCsReject(LinearLayout c) { core.cs.clearPending(); CsUi.markCardRejected(c); }
    }; }
    private void startCodespaces(final String t) { if (core.generating) { toast("Once yaniti bekle ya da durdur"); return; } if (core.codeMode) { toast("Once /chat ile Code modundan cik"); return; } withStoragePerm(() -> { if (!core.cs.cfg.isConfigured()) { CsUi.showConfigDialog(MainActivity.this, core.cs, () -> core.cs.activate(t)); return; } core.cs.activate(t); }); }
    private CodeUi.Host codeHost() {
        return new CodeUi.Host() {
            @Override public void onSelectTab(boolean code) { if (code && !core.codeMode) requestEnterCodeMode(null); else if (!code && core.codeMode) requestExitCodeMode(); }
            @Override public void onToggleAuto() { core.chat.codeAuto = !core.chat.codeAuto; store.save(core.chat); refreshCodeUi(); core.syncCodeSystemPrompt(); toast(core.chat.codeAuto ? "İzin: Özgür (otonom)" : "İzin: Güvenli (yazma onaylı)"); }
            @Override public void onOpenFolderPicker() { withStoragePerm(() -> CodeUi.showFolderPicker(MainActivity.this, core.ws, rel -> { core.ws.setRelDir(rel, true); core.chat.codeDir = core.ws.getRelDir(); store.save(core.chat); refreshCodeUi(); core.syncCodeSystemPrompt(); toast("Klasör: " + core.ws.getDisplayPath()); })); }
            @Override public void onOpenFiles() { openFilesWithPerm(); }
            @Override public void onUndo() { runUndoWithPerm(); }
            @Override public void onSlashCommand(String cmd) { input.setText(""); handleSlashInput(cmd); }
            @Override public void onApproveActions(List<CodeProtocol.Action> actions, boolean sendBack, LinearLayout cardBox) { core.executeCodeActions(actions, sendBack, false, (res, hasUndo) -> { if (cardBox != null) CodeUi.markCardDone(cardBox, res, sendBack, hasUndo, () -> runUndoWithPerm()); }); }
            @Override public void onRejectActions(LinearLayout cardBox) { core.clearPendingApproval(); CodeUi.markCardRejected(cardBox); }
            @Override public void onPreviewAction(CodeProtocol.Action a) { CodeUi.showActionPreview(MainActivity.this, a); }
        };
    }

    private void withStoragePerm(Runnable r) {
        if (Build.VERSION.SDK_INT <= 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPermR = r;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_CODE_PERM);
            return;
        }
        r.run();
    }

    private boolean handleSlashInput(String typed) {
        String lo = typed.toLowerCase(java.util.Locale.US);
        if (lo.equals("/codespaces") || lo.startsWith("/codespaces ") || lo.equals("/cs") || lo.startsWith("/cs ")) {
            int sp = typed.indexOf(32); String arg = sp > 0 ? typed.substring(sp + 1).trim() : ""; input.setText("");
            if (arg.equalsIgnoreCase("kapat") || arg.equalsIgnoreCase("off")) { if (core.cs.mode) core.cs.deactivate(true, !core.chat.msgs.isEmpty()); else toast("Codespaces modu kapali"); }
            else if (arg.equalsIgnoreCase("ayar") || arg.equalsIgnoreCase("config")) { CsUi.showConfigDialog(this, core.cs, () -> { refreshCodeUi(); core.updateNotif(); }); }
            else startCodespaces(arg);
            return true;
        }
        if (lo.equals("/code") || lo.startsWith("/code ")) { String task = typed.length() > 5 ? typed.substring(5).trim() : ""; input.setText(""); requestEnterCodeMode(task.isEmpty() ? null : task); return true; }
        if (lo.equals("/chat") || lo.equals("/normal")) { input.setText(""); if (core.codeMode) requestExitCodeMode(); else toast("Zaten normal sohbet modundasın"); return true; }
        if (lo.equals("/files")) { input.setText(""); openFilesWithPerm(); return true; }
        if (lo.equals("/undo")) { input.setText(""); runUndoWithPerm(); return true; }
        if (lo.equals("/oku") || lo.startsWith("/oku ")) {
            String extra = typed.length() > 4 ? typed.substring(4).trim() : ""; input.setText("");
            if (!core.codeMode) requestEnterCodeMode(extra.isEmpty() ? "__OKU__" : "__OKU__:" + extra); else sendOkuTree(extra);
            return true;
        }
        return false;
    }

    private void sendOkuTree(final String extra) { withStoragePerm(() -> core.sendOkuTree(extra)); }
    private void openFilesWithPerm() {
        withStoragePerm(() -> CodeUi.showFilesExplorer(this, core.ws, new CodeUi.FilesCb() {
            @Override public void onTreeCmd() { if (!core.codeMode) requestEnterCodeMode("__OKU__"); else sendOkuTree(""); }
            @Override public void onAttachRef(String relPath) { String cur = input.getText().toString(); String add = (cur.isEmpty() || cur.endsWith(" ") || cur.endsWith("\n") ? "" : " ") + relPath + " "; input.setText(cur + add); input.setSelection(input.getText().length()); toast("Eklendi: " + relPath); }
            @Override public void onMdCreated() { if (core.codeMode) core.syncCodeSystemPrompt(); toast("RAPHAEL.md oluşturuldu"); }
        }));
    }

    private void runUndoWithPerm() { withStoragePerm(() -> { core.runUndo(); }); }

    private void requestEnterCodeMode(final String task) {
        if (core.generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        if (core.site != Site.AISTUDIO) {
            if (core.chat.msgs.isEmpty()) CodeUi.showConfirmDialog(this, "AI Studio gerekli", "Raphael Code şimdilik sadece AI Studio ile çalışır. AI Studio kaynağına geçilip Code modu açılsın mı?", "AI Studio Geç", "İptal", () -> { core.applySiteSwitch(Site.AISTUDIO, false); updateChip(); requestEnterCodeMode(task); });
            else CodeUi.showConfirmDialog(this, "AI Studio gerekli", "Raphael Code şimdilik sadece AI Studio ile çalışır. Mevcut sohbeti AI Studio içine aktarıp Code moduna geçmek ister misin?", "Geçmişi Aktar ve Aç", "İptal", () -> { core.pendingCodeTask = task; core.openCodeAfterReply = true; core.applySiteSwitch(Site.AISTUDIO, true); updateChip(); });
            return;
        }
        withStoragePerm(() -> showEnterCodeDialog(task));
    }

    private void showEnterCodeDialog(final String task) {
        LinearLayout box = CodeUi.dialogBox(this); final Dialog d = CodeUi.themedDialog(this, box);
        TextView tt = Ui.text(this, "Raphael Code Modu", 17, Ui.TEXT); tt.setTypeface(Typeface.SERIF, Typeface.BOLD); box.addView(tt);
        TextView mt = Ui.text(this, "Raphael Code moduna geçilsin mi? AI Studio sistem talimatı yüklenir ve model yalnızca seçili klasörde çalışır.", 13, Ui.MUTED);
        mt.setLineSpacing(0, 1.28f); LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2); ml.topMargin = Ui.dp(8); box.addView(mt, ml);
        final TextView dirBtn = Ui.pill(this, "Klasör: " + core.ws.getDisplayPath() + "  ▾", 12.5f, Ui.TEXT, Ui.SURFACE, Ui.BORDER);
        dirBtn.setOnClickListener(v -> CodeUi.showFolderPicker(this, core.ws, rel -> { core.ws.setRelDir(rel, true); core.chat.codeDir = core.ws.getRelDir(); dirBtn.setText("Klasör: " + core.ws.getDisplayPath() + "  ▾"); }));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Ui.dp(40)); dl.topMargin = Ui.dp(12); box.addView(dirBtn, dl);
        final TextView permBtn = Ui.pill(this, "", 12.5f, Ui.TEXT, Ui.BUBBLE, Ui.BORDER);
        Runnable updP = () -> permBtn.setText(core.chat.codeAuto ? "İzin: Özgür (Otonom)" : "İzin: Güvenli (Okuma serbest, Yazma onaylı)");
        updP.run(); permBtn.setOnClickListener(v -> { core.chat.codeAuto = !core.chat.codeAuto; updP.run(); });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, Ui.dp(40)); pl.topMargin = Ui.dp(8); box.addView(permBtn, pl);
        TextView b1 = Ui.pill(this, "Geç ve Hazırla", 13.5f, 0xFFFFFFFF, Ui.TEXT, 0); b1.setOnClickListener(v -> { d.dismiss(); core.activateCodeMode(task); });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-1, Ui.dp(42)); l1.topMargin = Ui.dp(14); box.addView(b1, l1);
        TextView b2 = Ui.pill(this, "İptal", 13, Ui.MUTED, Ui.SURFACE, Ui.BORDER); b2.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-1, Ui.dp(40)); l2.topMargin = Ui.dp(8); box.addView(b2, l2); d.show();
    }

    private void requestExitCodeMode() {
        if (!core.codeMode) return;
        if (core.generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        CodeUi.showConfirmDialog(this, "Raphael Code Kapat", "Raphael Code modundan çıkılıp normal sohbete geçilsin mi?", "Çık", "İptal", () -> core.exitCodeMode());
    }
    // ================================================================== küçük yardımcılar
    private void updateSendBtn() {
        if (sendBtn == null) return;
        boolean can = core.generating || input.getText().toString().trim().length() > 0 || !core.atts.isEmpty();
        sendBtn.setImageResource(core.generating ? R.drawable.ic_stop : R.drawable.ic_up);
        sendBtn.setColorFilter(0xFFFFFFFF);
        sendBtn.setBackground(Ui.round(can ? Ui.TEXT : Ui.DISABLED, 20, 0, 0));
    }

    private boolean isAtBottom() {
        View c = scroll.getChildAt(0);
        if (c == null) return true;
        return c.getBottom() - (scroll.getHeight() + scroll.getScrollY()) < Ui.dp(140);
    }

    private void scrollBottom() {
        scroll.post(() -> {
            View c = scroll.getChildAt(0);
            if (c != null) scroll.scrollTo(0, Math.max(0, c.getHeight() - scroll.getHeight()));
        });
    }

    private void copyText(String text, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Raphael", text));
        toast(msg);
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (im != null && f != null) im.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
