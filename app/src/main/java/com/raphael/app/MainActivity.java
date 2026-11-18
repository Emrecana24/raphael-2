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

public class MainActivity extends Activity implements WebSession.Listener {
    private static final int REQ_PICK = 11, REQ_WEBFILE = 12, REQ_SAVE_PERM = 13;
    private static final String[] LEVELS = {"", "low", "medium", "high", "xhigh"};
    private static final String[] LEVEL_LABELS = {"Varsayılan", "Low", "Medium", "High", "Extra High"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private ChatStore store;
    private WebSession session;

    // durum
    private Site site = Site.GEMINI;
    private Chat chat = new Chat();
    private String boundChatId = null;
    private Site boundSite = null;
    private boolean generating = false, awaitingStart = false, webMode = false, gotFirstText = false;
    private long genStart = 0;
    private static class Att {
        String id, name, mime, content, thumb, tokens = "", wname = "";
        File file;
        long size;
        boolean text, removed;
        int upState = 0, webIdx = -1;
    }
    private final List<Att> atts = new ArrayList<>();
    private Att activeUpload = null;
    private int uploadGen = 0;
    private List<String> pendingImgs = new ArrayList<>();
    private String stage = "", lastPayload = "";
    private List<WebSession.Upload> lastUps = new ArrayList<>();
    private ValueCallback<Uri[]> webFileCb;
    private File pendingSaveFile;
    private byte[] pendingSaveBytes;
    private String pendingSaveName, pendingSaveMime;
    private View lastErrView;

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
    private String curText = "";
    private LinearLayout curThWrap;
    private TextView curThHdr, curThBody;
    private boolean curThActive = false, curThOpen = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = getSharedPreferences("raphael", MODE_PRIVATE);
        Ui.fontScale = prefs.getFloat("fs", 1f);
        store = new ChatStore(this);
        File upd = new File(getCacheDir(), "up");
        File[] old = upd.listFiles();
        if (old != null) for (File f : old) f.delete();
        site = Site.fromId(prefs.getString("site", "gemini"));
        drawerW = Math.min(Ui.dp(300), (int) (getResources().getDisplayMetrics().widthPixels * 0.85f));

        session = new WebSession(this);
        session.setListener(this);
        session.setUaMode(prefs.getInt("ua", 0));
        pushPrefs();

        buildUi();
        setContentView(root);

        // son sohbeti aç
        List<Chat> all = store.list();
        if (!all.isEmpty() && prefs.getBoolean("openLast", true)) {
            Chat c = store.load(all.get(0).id);
            if (c != null && !c.msgs.isEmpty()) chat = c;
        }
        renderChat();
        updateChip();
        applyBgRender();
        session.open(site, site.newChatUrl);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        session.destroy();
    }

    @Override
    public void onBackPressed() {
        if (drawerScrim.getVisibility() == View.VISIBLE) { closeDrawer(); return; }
        if (webMode) { showWeb(false); return; }
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
        session.attach(webHost);
        root.addView(webPage, new FrameLayout.LayoutParams(-1, -1));

        // ---- Sohbet sayfası (üstte, opak)
        chatPage = new LinearLayout(this);
        chatPage.setOrientation(LinearLayout.VERTICAL);
        chatPage.setBackgroundColor(Ui.BG);
        chatPage.setClickable(true);
        chatPage.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, Ui.dp(52)));

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
            public void afterTextChanged(Editable e) { updateSendBtn(); }
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
        webSiteBtn.setOnClickListener(v -> setSite(site == Site.GEMINI ? Site.AISTUDIO : Site.GEMINI));
        row.addView(webSiteBtn, wl());
        TextView reload = Ui.pill(this, "Yenile", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        reload.setOnClickListener(v -> { session.reload(); invalidateUploads(true); });
        row.addView(reload, wl());
        TextView acct = Ui.pill(this, "Hesap girişi", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        acct.setOnClickListener(v -> { invalidateUploads(false); if (!session.isConnected()) session.open(site, site.newChatUrl); session.loginPage(); });
        row.addView(acct, wl());
        webDisplayBtn = Ui.pill(this, "", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        webDisplayBtn.setOnClickListener(v -> { prefs.edit().putBoolean("bg", !prefs.getBoolean("bg", true)).apply(); applyBgRender(); });
        row.addView(webDisplayBtn, wl());
        webConnBtn = Ui.pill(this, "Bağlantıyı kes", 11.5f, Ui.TEXT, Ui.BUBBLE, 0);
        webConnBtn.setOnClickListener(v -> {
            if (session.isConnected()) { session.disconnect(); boundChatId = null; invalidateUploads(false); }
            else { session.open(site, site.newChatUrl); invalidateUploads(true); }
            updateWebBar();
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
        nl.topMargin = Ui.dp(18); nl.bottomMargin = Ui.dp(12);
        p.addView(nc, nl);

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
            boolean cur = c.id.equals(chat.id);
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
                                        if (c.id.equals(chat.id)) newChat();
                                        refreshDrawer();
                                    }).setNegativeButton("Vazgeç", null).show();
                        }).show();
                return true;
            });
            drawerList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // ================================================================== sohbet yönetimi
    private void newChat() {
        if (generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        chat = new Chat();
        chat.site = site.id;
        boundChatId = null;
        renderChat();
        boolean reloaded = false;
        if (session.isConnected() && !session.isFresh()) { session.newChat(); reloaded = true; }
        else if (!session.isConnected()) { session.open(site, site.newChatUrl); reloaded = true; }
        if (reloaded) invalidateUploads(true);
    }

    private void openChat(String id) {
        if (generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        Chat c = store.load(id);
        if (c == null) return;
        chat = c;
        boundChatId = null;
        renderChat();
        if (!atts.isEmpty() && !session.isFresh() && session.isConnected()) {
            session.open(site, site.newChatUrl);
            invalidateUploads(true);
        }
    }

    private void renderChat() {
        messages.removeAllViews();
        curMd = null; curFoot = null;
        for (Msg m : chat.msgs) messages.addView(m.role.equals(Msg.USER) ? userView(m) : aiView(m));
        welcome.setVisibility(chat.msgs.isEmpty() ? View.VISIBLE : View.GONE);
        titleTv.setText(chat.msgs.isEmpty() ? "Raphael" : chat.title);
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

    // ================================================================== gönderme
    private void onSendClick() {
        if (generating) { stopGen(); return; }
        doSend();
    }

    private void stopGen() {
        if (awaitingStart) {               // henüz siteye gönderilmedi
            session.cancelQueue();
            failAi("İptal edildi.");
            return;
        }
        session.stopGeneration();
        if (curFoot != null) curFoot.setText("Durduruluyor…");
    }

    private String fenceFor(String s) {
        int run = 0, max = 2, cur = 0;
        for (int i = 0; i < s.length(); i++) { if (s.charAt(i) == '`') { cur++; if (cur > max) max = cur; } else cur = 0; }
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < max + 1; i++) f.append('`');
        return f.toString();
    }

    private void doSend() {
        String typed = input.getText().toString().trim();
        if (typed.isEmpty() && atts.isEmpty()) return;
        if (activeUpload != null) { toast("Dosya işlemi sürüyor, bir saniye bekle…"); return; }
        for (Att a : atts) {
            if (!a.text && a.upState == 1) { toast("Dosya siteye yükleniyor, bitmesini bekle…"); return; }
            if (!a.text && a.upState == 3) { toast("Yüklenemeyen dosya var; çarpıdan kaldır veya dokunup tekrar dene"); return; }
        }

        boolean boundOk = chat.id.equals(boundChatId) && boundSite == site && session.isConnected();
        int prior = chat.msgs.size();
        boolean reload = !boundOk && !session.isFresh();

        StringBuilder full = new StringBuilder(), names = new StringBuilder();
        final List<WebSession.Upload> ups = new ArrayList<>();
        List<String> imgNames = new ArrayList<>();
        for (Att a : atts) {
            if (names.length() > 0) names.append(", ");
            names.append(a.name);
            if (!a.tokens.isEmpty()) names.append(" (").append(a.tokens).append(")");
            if (a.text) {
                String f = fenceFor(a.content);
                full.append("Dosya: ").append(a.name).append('\n').append(f).append('\n').append(a.content).append('\n').append(f).append("\n\n");
            } else {
                if (reload || a.upState != 2) ups.add(new WebSession.Upload(a.id, a.name, a.mime, a.file));
                if (a.thumb != null) imgNames.add(a.thumb);
            }
        }
        full.append(typed);
        String shown = (names.length() > 0 ? "📎 " + names + "\n" : "") + typed;
        Msg um = new Msg(Msg.USER, full.toString(), shown, null);
        um.imgs.addAll(imgNames);

        String payload = um.text;
        if (!boundOk && prior > 0) payload = contextPrefix(prior) + payload;

        chat.msgs.add(um);
        if (prior == 0) { chat.title = makeTitle(um.display()); chat.created = System.currentTimeMillis(); }
        chat.site = site.id;
        store.save(chat);

        input.setText("");
        atts.clear();
        refreshAttach();
        welcome.setVisibility(View.GONE);
        titleTv.setText(chat.title);
        messages.addView(userView(um));
        startAi();

        lastPayload = payload;
        lastUps = ups;
        launchSend(reload);
    }

    /** lastPayload / lastUps'u siteye gönderir (ilk gönderim ve "tekrar gönder" için ortak). */
    private void launchSend(boolean reload) {
        final String fp = lastPayload;
        final List<WebSession.Upload> ups = lastUps;
        awaitingStart = true;
        if (reload) session.open(site, site.newChatUrl);
        session.whenUsable(() -> {
            awaitingStart = false;
            boundChatId = chat.id;
            boundSite = site;
            session.sendWithFiles(fp, ups);
        });
        h.postDelayed(() -> {
            if (generating && awaitingStart) {
                session.cancelQueue();
                failAi("Web oturumu hazır olmadı. Web sekmesinden giriş yapıp tekrar dene.");
            }
        }, 80000);
    }

    private String contextPrefix(int upto) {
        List<String> parts = new ArrayList<>();
        int budget = 24000;
        for (int i = upto - 1; i >= 0 && budget > 0; i--) {
            Msg m = chat.msgs.get(i);
            String t = m.text;
            if (t.length() > 7000) t = t.substring(0, 3500) + "\n[...kısaltıldı...]\n" + t.substring(t.length() - 3000);
            String p = (m.role.equals(Msg.USER) ? "[Kullanıcı]\n" : "[Asistan]\n") + t;
            budget -= p.length();
            parts.add(0, p);
        }
        StringBuilder sb = new StringBuilder("Aşağıda bu sohbetin önceki bölümü var. Sadece bağlam olarak kullan, tekrar özetleme.\n\n");
        for (String p : parts) sb.append(p).append("\n\n");
        sb.append("--- Önceki bölümün sonu. Yeni mesajım: ---\n");
        return sb.toString();
    }

    private String makeTitle(String s) {
        String t = s.replace("📎", "").replaceAll("\\s+", " ").trim();
        if (t.length() > 42) t = t.substring(0, 42).trim() + "…";
        return t.isEmpty() ? "Sohbet" : t;
    }

    private void startAi() {
        generating = true;
        gotFirstText = false;
        stage = "";
        curText = "";
        genStart = System.currentTimeMillis();
        curThActive = false; curThOpen = false;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        curThWrap = new LinearLayout(this);
        curThWrap.setOrientation(LinearLayout.VERTICAL);
        curThWrap.setBackground(Ui.round(Ui.CODEBG, 10, Ui.BORDER, 1));
        curThWrap.setPadding(Ui.dp(10), Ui.dp(7), Ui.dp(10), Ui.dp(7));
        curThWrap.setVisibility(View.GONE);
        LinearLayout.LayoutParams thl = new LinearLayout.LayoutParams(-1, -2);
        thl.bottomMargin = Ui.dp(8);
        curThHdr = Ui.text(this, "▸ Düşünüyor…", 11.5f, Ui.MUTED);
        curThWrap.addView(curThHdr, new LinearLayout.LayoutParams(-1, -2));
        curThBody = Ui.text(this, "", 12f, Ui.MUTED);
        curThBody.setLineSpacing(0, 1.25f);
        curThBody.setPadding(0, Ui.dp(6), 0, 0);
        curThBody.setVisibility(View.GONE);
        curThWrap.addView(curThBody, new LinearLayout.LayoutParams(-1, -2));
        final LinearLayout tw = curThWrap; final TextView th = curThHdr, tb = curThBody;
        tw.setOnClickListener(v -> {
            boolean open = tb.getVisibility() != View.VISIBLE;
            tb.setVisibility(open ? View.VISIBLE : View.GONE);
            if (tw == curThWrap) curThOpen = open;
            String ct = th.getText().toString();
            th.setText((open ? "▾ " : "▸ ") + (ct.length() > 2 ? ct.substring(2) : ct));
        });
        col.addView(curThWrap, thl);
        curMd = newMd();
        col.addView(curMd, new LinearLayout.LayoutParams(-1, -2));
        curFoot = Ui.text(this, "Yanıt bekleniyor…", 11.5f, Ui.MUTED);
        col.addView(curFoot, footLp());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(18);
        messages.addView(col, lp);
        updateSendBtn();
        scrollBottom();
        h.removeCallbacks(timerR);
        h.postDelayed(timerR, 1000);
    }

    private final Runnable timerR = new Runnable() {
        @Override public void run() {
            if (!generating || curFoot == null) return;
            long s = (System.currentTimeMillis() - genStart) / 1000;
            String base = !stage.isEmpty() ? stage : awaitingStart ? "Web oturumu hazırlanıyor…" : gotFirstText ? "Yazıyor…" : curThActive ? "Düşünüyor…" : "Yanıt bekleniyor…";
            curFoot.setText(base + " " + s + " sn");
            h.postDelayed(this, 1000);
        }
    };

    private void finishAi(String md) {
        generating = false;
        h.removeCallbacks(timerR);
        Msg am = new Msg(Msg.AI, md, null, site.id);
        am.imgs.addAll(pendingImgs);
        pendingImgs = new ArrayList<>();
        chat.msgs.add(am);
        store.save(chat);
        stage = "";
        if (curFoot != null && curFoot.getParent() instanceof LinearLayout) {
            LinearLayout col = (LinearLayout) curFoot.getParent();
            col.removeView(curFoot);
            if (!am.imgs.isEmpty()) col.addView(imagesRow(am.imgs, false), new LinearLayout.LayoutParams(-1, -2));
            col.addView(doneFooter(md, site.id), footLp());
        }
        if (curThHdr != null && curThWrap != null && curThWrap.getVisibility() == View.VISIBLE) curThHdr.setText((curThOpen ? "▾ " : "▸ ") + "Düşünce");
        curThWrap = null; curThHdr = null; curThBody = null; curThActive = false;
        curMd = null; curFoot = null;
        updateSendBtn();
        pumpPreUpload();
    }

    private void failAi(String err) {
        generating = false;
        awaitingStart = false;
        stage = "";
        pendingImgs = new ArrayList<>();
        h.removeCallbacks(timerR);
        boolean actions = !err.startsWith("İptal");
        String shownErr = actions ? err + "  (Seçenekler için dokun)" : err;
        lastErrView = null;
        if (curMd != null && curText.isEmpty() && curMd.getParent() instanceof LinearLayout) {
            View col = (View) curMd.getParent();
            messages.removeView(col);
            TextView e = Ui.text(this, shownErr, 12.5f, Ui.RED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(14);
            messages.addView(e, lp);
            if (actions) { e.setOnClickListener(v -> errorActions()); lastErrView = e; }
        } else if (curFoot != null) {
            curFoot.setText(shownErr);
            curFoot.setTextColor(Ui.RED);
            if (!curText.isEmpty()) { chat.msgs.add(new Msg(Msg.AI, curText, null, site.id)); store.save(chat); }
            if (actions) { curFoot.setOnClickListener(v -> errorActions()); if (curFoot.getParent() instanceof View) lastErrView = (View) curFoot.getParent(); }
        }
        if (curThHdr != null && curThWrap != null && curThWrap.getVisibility() == View.VISIBLE) curThHdr.setText((curThOpen ? "▾ " : "▸ ") + "Düşünce");
        curThWrap = null; curThHdr = null; curThBody = null; curThActive = false;
        curMd = null; curFoot = null;
        updateSendBtn();
        scrollBottom();
        pumpPreUpload();
    }

    private void errorActions() {
        if (generating) return;
        new AlertDialog.Builder(this).setTitle("Ne yapalım?")
                .setItems(new String[]{"Cevabı web'den çek", "Mesajı tekrar gönder", "Web sekmesini aç"}, (d, w) -> {
                    if (w == 0) recoverFromWeb();
                    else if (w == 1) resendLast();
                    else showWeb(true);
                }).show();
    }

    private void dropErrView() {
        if (lastErrView != null && lastErrView.getParent() == messages) messages.removeView(lastErrView);
        lastErrView = null;
    }

    private void recoverFromWeb() {
        if (generating) return;
        dropErrView();
        startAi();
        awaitingStart = false;
        session.recover();
    }

    private void resendLast() {
        if (generating || lastPayload == null || (lastPayload.isEmpty() && lastUps.isEmpty())) { toast("Tekrar gönderilecek bir şey yok"); return; }
        dropErrView();
        startAi();
        launchSend(false);
    }

    // ================================================================== WebSession.Listener
    @Override public void onStatus(int st, String info) {
        int c = st == WebSession.ST_READY ? Ui.GREEN : st == WebSession.ST_LOADING ? Ui.AMBER : st == WebSession.ST_LOGIN ? Ui.RED : Ui.MUTED;
        String tk = session.tokens();
        String txt = site.label + " · " + info + (site == Site.AISTUDIO && !tk.isEmpty() ? " · " + tk : "");
        statusDot.setBackground(Ui.round(c, 4, 0, 0));
        statusTv.setText(txt);
        webDot.setBackground(Ui.round(c, 4, 0, 0));
        webStatusTv.setText(info);
        updateWebBar();
    }

    @Override public void onReply(String md, boolean done) {
        if (curMd == null) return;
        boolean at = isAtBottom();
        curText = md;
        if (!md.isEmpty()) { gotFirstText = true; stage = ""; if (curThHdr != null && curThWrap != null && curThWrap.getVisibility() == View.VISIBLE) curThHdr.setText((curThOpen ? "▾ " : "▸ ") + "Düşünce"); }
        curMd.setMarkdown(md);
        if (done) { finishAi(md); }
        if (at) scrollBottom();
    }

    @Override public void onReplyError(String err) { if (generating) failAi(err); }

    @Override public void onImages(List<String> names) { pendingImgs = new ArrayList<>(names); }

    @Override public void onStage(String t) { stage = t == null ? "" : t; }

    @Override public void onThinking(String text, boolean active) {
        if (curThWrap == null) return;
        boolean has = text != null && !text.trim().isEmpty();
        if (!has && !active) return;
        boolean at = isAtBottom();
        curThActive = active;
        curThWrap.setVisibility(View.VISIBLE);
        if (has) curThBody.setText(text.trim());
        curThHdr.setText((curThOpen ? "▾ " : "▸ ") + (active && curText.isEmpty() ? "Düşünüyor…" : "Düşünce"));
        if (at) scrollBottom();
    }

    @Override public boolean onFileChooser(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        if (webFileCb != null) webFileCb.onReceiveValue(null);
        webFileCb = cb;
        try { startActivityForResult(p.createIntent(), REQ_WEBFILE); return true; }
        catch (Exception e) { webFileCb = null; return false; }
    }

    @Override public void onNote(String msg) {
        if (msg.startsWith("DOM kaydedildi")) {
            new AlertDialog.Builder(this).setTitle("DOM kaydedildi").setMessage(msg.replace("DOM kaydedildi", "").trim()
                    + "\n\nBu dosyayı DOM Analizcisi modeline yükleyebilirsin.").setPositiveButton("Tamam", null).show();
        } else toast(msg);
    }

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
        webSiteBtn.setText("Kaynak: " + site.label);
        webDisplayBtn.setText("Arka plan görüntü: " + (prefs.getBoolean("bg", true) ? "Açık" : "Kapalı"));
        webConnBtn.setText(session.isConnected() ? "Bağlantıyı kes" : "Bağlan");
    }

    // ================================================================== kaynak / model / düşünme
    private String thinkKey() { return "think_" + site.id; }
    private String thinking() {
        String lv = prefs.getString(thinkKey(), "");
        return (site != Site.AISTUDIO && "xhigh".equals(lv)) ? "" : lv;
    }

    private String maxOutput() { String v = prefs.getString("out_" + site.id, "65536"); return v.isEmpty() ? "65536" : v; }

    private void pushPrefs() { session.setPrefs(thinking(), site.modelPicker ? prefs.getString("model_" + site.id, "") : "", site == Site.AISTUDIO ? maxOutput() : ""); }

    private void updateChip() {
        String lv = thinking();
        int i = 0;
        for (int k = 0; k < LEVELS.length; k++) if (LEVELS[k].equals(lv)) i = k;
        chipTv.setText(site.label + " · " + (i == 0 ? "Otomatik" : LEVEL_LABELS[i]) + "  ▾");
        updateWebBar();
    }

    private void setSite(Site s) {
        if (s == site) return;
        if (generating) { toast("Önce yanıtın bitmesini bekle ya da durdur"); return; }
        site = s;
        prefs.edit().putString("site", s.id).apply();
        boundChatId = null;
        pushPrefs();
        updateChip();
        session.open(s, s.newChatUrl);
        invalidateUploads(true);
    }

    private void setThinking(String lv) {
        prefs.edit().putString(thinkKey(), lv).apply();
        pushPrefs();
        updateChip();
        if (session.isConnected()) session.reapplyPrefs();
    }

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
        if (generating) { toast("Yanıt sürerken değiştirilemez"); return; }
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
            boolean sel = s == site;
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
        int count = site == Site.AISTUDIO ? LEVELS.length : 4;
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

        if (site.modelPicker) {
            TextView mh = Ui.text(this, "Model", 11.5f, Ui.MUTED);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.topMargin = Ui.dp(18);
            c.addView(mh, ml);
            String m = prefs.getString("model_" + site.id, "");
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
        e.setText(prefs.getString("model_" + site.id, ""));
        new AlertDialog.Builder(this).setTitle("AI Studio model adı").setView(e)
                .setPositiveButton("Kaydet", (d, w) -> setModel(e.getText().toString().trim()))
                .setNegativeButton("Vazgeç", null).show();
    }

    private void setModel(String name) {
        prefs.edit().putString("model_" + site.id, name).apply();
        pushPrefs();
        if (session.isConnected()) session.reapplyPrefs();
        toast(name.isEmpty() ? "Model: varsayılan" : "Model: " + name);
    }

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

    private void setMaxOutput(String v) {
        if (v == null || v.isEmpty()) v = "65536";
        String prev = maxOutput();
        prefs.edit().putString("out_" + site.id, v).apply();
        pushPrefs();
        if (session.isConnected()) {
            if ("65536".equals(v) && !"65536".equals(prev)) session.resetOutput();
            else session.reapplyPrefs();
        }
        toast("Output: " + v + " token");
    }

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
                .setPositiveButton("Sil", (d, w) -> { store.deleteAll(); chat = new Chat(); boundChatId = null; renderChat(); })
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
            final List<Att> added = new ArrayList<>();
            for (Uri u : fu) { Att a = readAttachment(u); if (a != null) added.add(a); }
            runOnUiThread(() -> { atts.addAll(added); refreshAttach(); pumpPreUpload(); });
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
    private Att readAttachment(Uri u) {
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

        Att a = new Att();
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

    private void invalidateUploads(boolean restart) {
        uploadGen++;
        activeUpload = null;
        boolean any = false;
        for (Att a : atts) {
            if (!a.text && !a.removed) {
                a.upState = 1; a.tokens = ""; a.wname = ""; a.webIdx = -1;
                any = true;
            }
        }
        if (any) {
            refreshAttach();
            if (restart) pumpPreUpload();
        }
    }

    private void pumpPreUpload() {
        if (activeUpload != null || generating) return;
        Att next = null;
        for (Att a : atts) {
            if (!a.text && !a.removed && a.upState == 1) { next = a; break; }
        }
        if (next == null) return;
        if (!chat.id.equals(boundChatId) && !session.isFresh() && session.isConnected()) {
            invalidateUploads(false);
            session.open(site, site.newChatUrl);
        }
        final Att cur = next;
        final Site upSite = site;
        final int gen = uploadGen;
        activeUpload = cur;
        session.whenUsable(() -> {
            if (gen != uploadGen || site != upSite || cur.removed || !atts.contains(cur)) {
                if (gen == uploadGen && activeUpload == cur) {
                    activeUpload = null;
                    if (cur.removed && cur.file != null) cur.file.delete();
                    pumpPreUpload();
                }
                return;
            }
            session.attachOne(new WebSession.Upload(cur.id, cur.name, cur.mime, cur.file), (ok, tokens, wname, idx, err) -> {
                if (gen != uploadGen) {
                    if (cur.removed && cur.file != null) cur.file.delete();
                    return;
                }
                if (activeUpload == cur) activeUpload = null;
                if (site != upSite) { pumpPreUpload(); return; }
                if (cur.removed || !atts.contains(cur)) {
                    if (ok) session.removeAttached(cur.name, (wname != null && !wname.isEmpty()) ? wname : cur.wname, idx);
                    if (cur.file != null) cur.file.delete();
                    pumpPreUpload();
                    return;
                }
                if (ok) {
                    cur.upState = 2;
                    cur.tokens = tokens == null ? "" : tokens;
                    cur.wname = wname == null ? "" : wname;
                    cur.webIdx = idx;
                } else {
                    cur.upState = 3;
                    toast(cur.name + " yüklenemedi" + (err != null && !err.isEmpty() ? " (" + err + ")" : ""));
                }
                refreshAttach();
                pumpPreUpload();
            });
        });
    }

    private void refreshAttach() {
        attachInner.removeAllViews();
        attachRow.setVisibility(atts.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < atts.size(); i++) {
            final Att a = atts.get(i);
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
                    pumpPreUpload();
                });
            }
            ImageView x = Ui.icon(this, R.drawable.ic_close, 22, Ui.MUTED);
            x.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            x.setOnClickListener(v -> {
                boolean busy = (a == activeUpload);
                a.removed = true;
                int ridx = a.webIdx;
                boolean wasUp = a.upState == 2;
                atts.remove(a);
                if (wasUp) {
                    for (Att o : atts) if (!o.text && o.upState == 2 && ridx >= 0 && o.webIdx > ridx) o.webIdx--;
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

    // ================================================================== küçük yardımcılar
    private void updateSendBtn() {
        if (sendBtn == null) return;
        boolean can = generating || input.getText().toString().trim().length() > 0 || !atts.isEmpty();
        sendBtn.setImageResource(generating ? R.drawable.ic_stop : R.drawable.ic_up);
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
