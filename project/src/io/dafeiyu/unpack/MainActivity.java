package io.dafeiyu.unpack;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1001;
    private static final int REQ_TREE = 1002;
    private static final int REQ_STORAGE = 1003;

    private static final int ST_EMPTY = 0, ST_READY = 1, ST_RUNNING = 2;
    private static final int M_COMPACT = 0, M_MEDIUM = 1, M_EXPANDED = 2;

    private static final int BTN_ACCENT = 0, BTN_SECONDARY = 1, BTN_DISABLED = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Theme t;
    private int mode, state = ST_EMPTY;

    private File cacheFile;
    private String archiveName = "";
    private String pwd = "";
    private long archiveSize = -1L;
    private Engine.Format fmt = Engine.Format.UNKNOWN;
    private final List<Engine.Entry> entries = new ArrayList<Engine.Entry>();
    private Uri treeUri;
    private String treeLabel = "";
    private File outDir;
    private Runnable pendingStorage;

    private volatile boolean cancelFlag;
    private volatile boolean busy;

    private TextView tvSub, tvName, tvMeta, tvPathVal, tvPwdVal, tvCount, tvPct, tvSize,
            tvCur, tvPrimary, tvMore, tvEmptyHint, tvResult;
    private View fileEmpty, fileLoaded, fileCard, optionsCard, listCard, progressCard,
            resultCard, primaryBtn;
    private LinearLayout bottomBar;
    private View extractView, convertView;
    private final List<TabBar> tabBars = new ArrayList<TabBar>();
    private ConvertPane conv;
    private int tab = 0;
    private LinearLayout entriesBox;
    private ListView entriesList;
    private ImageView primaryIcon, btnTheme;
    private SlimBar bar;

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        t = Theme.current(this);
        setTheme(t.dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        mode = resolveMode();
        setContentView(buildUi());
        applyState();
        if (savedInstanceState != null) restore(savedInstanceState);
        handleIntent(getIntent());
    }

    private int resolveMode() {
        int w = getResources().getConfiguration().screenWidthDp;
        if (w <= 0) {
            w = (int) (getResources().getDisplayMetrics().widthPixels
                    / getResources().getDisplayMetrics().density);
        }
        if (w >= 840) return M_EXPANDED;
        if (w >= 600) return M_MEDIUM;
        return M_COMPACT;
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (resolveMode() != mode || Theme.resolveDark(this) != t.dark) recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingStorage != null && Files.hasAll(this)) {
            Runnable r = pendingStorage;
            pendingStorage = null;
            r.run();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cacheFile != null && cacheFile.exists()) {
            out.putString("cache", cacheFile.getAbsolutePath());
            out.putString("name", archiveName);
            out.putString("pwd", pwd);
            out.putString("fmt", fmt.name());
            out.putString("tree", treeUri == null ? "" : treeUri.toString());
            out.putString("treeLabel", treeLabel);
        }
    }

    private void restore(Bundle b) {
        String path = b.getString("cache");
        if (TextUtils.isEmpty(path)) return;
        File f = new File(path);
        if (!f.exists()) return;
        cacheFile = f;
        archiveName = b.getString("name", "");
        pwd = b.getString("pwd", "");
        treeLabel = b.getString("treeLabel", "");
        String tree = b.getString("tree", "");
        if (!TextUtils.isEmpty(tree)) treeUri = Uri.parse(tree);
        try {
            fmt = Engine.Format.valueOf(b.getString("fmt", "UNKNOWN"));
        } catch (Exception e) {
            fmt = Engine.detect(archiveName, f);
        }
        state = ST_READY;
        tvName.setText(archiveName);
        tvMeta.setText(metaLine(fmt, f.length(), 0));
        applyState();
        busy = true;
        final String name = archiveName;
        final Engine.Format f0 = fmt;
        final String p0 = pwd;
        final File cf = f;
        new Thread(new Runnable() {
            public void run() {
                final List<Engine.Entry> list;
                try {
                    list = Engine.list(f0, cf, p0);
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            busy = false;
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        entries.clear();
                        entries.addAll(list);
                        tvName.setText(name);
                        tvMeta.setText(metaLine(f0, cf.length(), list.size()));
                        renderEntries();
                        applyState();
                    }
                });
            }
        }, "restore").start();
    }

    // ------------------------------------------------------------------- UI

    private String pathLabel() {
        if (outDir != null) return outDir.getName();
        if (treeUri != null) return treeLabel;
        return state == ST_EMPTY ? "未选择" : "应用目录";
    }

    private void applyState() {
        boolean empty = state == ST_EMPTY;
        boolean running = state == ST_RUNNING;

        fileEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        fileLoaded.setVisibility(empty ? View.GONE : View.VISIBLE);
        listCard.setVisibility(state == ST_READY ? View.VISIBLE : View.GONE);
        progressCard.setVisibility(running ? View.VISIBLE : View.GONE);
        if (tvEmptyHint != null) tvEmptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);

        if (empty) {
            tvPathVal.setText(pathLabel());
            tvPwdVal.setText(pwd.isEmpty() ? "可选" : "已设置");
            setPrimary(BTN_DISABLED, "开始解压", R.drawable.ic_download);
        } else if (running) {
            setPrimary(BTN_SECONDARY, "取消解压", 0);
        } else {
            tvPathVal.setText(pathLabel());
            tvPwdVal.setText(pwd.isEmpty() ? "未设置" : "已设置");
            setPrimary(BTN_ACCENT, "开始解压", R.drawable.ic_download);
        }
        optionsCard.setAlpha(running ? 0.45f : 1f);
        optionsCard.setEnabled(!running);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        conv = new ConvertPane(this, t, mode, makeHeaderWithTabs());
        extractView = (mode == M_EXPANDED) ? buildTwoPane() : buildSingle();
        convertView = conv.build();
        convertView.setVisibility(View.GONE);
        root.addView(extractView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(convertView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    // ------------------------------------------------------------ 页签切换

    private View makeTabs() {
        LinearLayout bar = Ui.horizontal(this);
        bar.setBackground(Ui.round(this, t.surface2, 18));
        bar.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42)));
        TextView a = segment("解压");
        TextView b = segment("图片转换");
        bar.addView(a, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(b, Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        TabBar tb = new TabBar(a, b);
        tabBars.add(tb);
        refreshTabs();
        return bar;
    }

    private TextView segment(String label) {
        TextView tv = Ui.text(this, 14, t.text2, true);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private void selectTab(int i) {
        tab = i;
        if (extractView != null) extractView.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        if (convertView != null) convertView.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        refreshTabs();
    }

    private void refreshTabs() {
        int selBg = t.dark ? 0xFF34343A : 0xFFFFFFFF;
        for (final TabBar tb : tabBars) {
            TextView[] segs = {tb.a, tb.b};
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                boolean on = i == tab;
                segs[i].setBackground(Ui.ripple(this,
                        on ? Ui.round(this, selBg, 15) : null, t.rippleColor));
                segs[i].setTextColor(on ? t.text : t.text2);
                segs[i].setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        selectTab(idx);
                    }
                });
            }
        }
    }

    private static class TabBar {
        final TextView a, b;

        TabBar(TextView a, TextView b) {
            this.a = a;
            this.b = b;
        }
    }

    private LinearLayout headerBlock() {
        LinearLayout row = Ui.horizontal(this);
        LinearLayout col = Ui.vertical(this);
        TextView title = Ui.text(this, 28, t.text, true);
        title.setText("星鲸解压");
        tvSub = Ui.text(this, 13, t.text2, false);
        tvSub.setText("支持 7z · zip · rar · tar · gz · bz2 · xz");
        col.addView(title);
        col.addView(tvSub, topMargin(4));
        row.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnTheme = new ImageView(this);
        btnTheme.setImageResource(themeIcon());
        btnTheme.setColorFilter(t.text2);
        btnTheme.setBackground(Ui.ripple(this, Ui.round(this, t.surface2, 20), t.rippleColor));
        int s = Ui.dp(this, 40);
        btnTheme.setPadding(Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11), Ui.dp(this, 11));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(s, s);
        tp.leftMargin = Ui.dp(this, 12);
        btnTheme.setLayoutParams(tp);
        btnTheme.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showThemeSheet();
            }
        });
        row.addView(btnTheme);
        return row;
    }

    private int themeIcon() {
        int m = Theme.mode(this);
        if (m == Theme.LIGHT) return R.drawable.ic_sun;
        if (m == Theme.DARK) return R.drawable.ic_moon;
        return R.drawable.ic_auto;
    }

    /** 标题 + 页签，两个页面各建一份（View 不能有两个 parent） */
    private View makeHeaderWithTabs() {
        LinearLayout h = Ui.vertical(this);
        h.addView(headerBlock());
        LinearLayout tw = Ui.vertical(this);
        tw.setPadding(0, Ui.dp(this, 12), 0, 0);
        tw.addView(makeTabs());
        h.addView(tw);
        return h;
    }

    private View buildSingle() {
        LinearLayout shell = Ui.vertical(this);

        LinearLayout head = Ui.vertical(this);
        head.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), 0);
        head.addView(makeHeaderWithTabs());
        shell.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);

        LinearLayout wrap = Ui.horizontal(this);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout col = Ui.vertical(this);
        col.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));

        buildFileCard();
        buildOptionsCard();
        buildListCard(false);
        buildProgressCard();
        buildResultCard();

        col.addView(fileCard, topMargin(0));
        col.addView(optionsCard, topMargin(14));
        col.addView(listCard, topMargin(14));
        col.addView(progressCard, topMargin(14));
        col.addView(resultCard, topMargin(14));
        tvEmptyHint = Ui.text(this, 12, t.text3, false);
        tvEmptyHint.setGravity(Gravity.CENTER);
        tvEmptyHint.setText("也可以从文件管理器「用其他应用打开」直接跳转");
        col.addView(tvEmptyHint, topMargin(28));

        int width = mode == M_MEDIUM
                ? Math.min(screenWidth() - Ui.dp(this, 64), Ui.dp(this, 560))
                : ViewGroup.LayoutParams.MATCH_PARENT;
        wrap.addView(col, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(wrap);
        shell.addView(sv, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = Ui.vertical(this);
        bottomBar.setPadding(Ui.dp(this, 20), Ui.dp(this, 8),
                Ui.dp(this, 20), Ui.dp(this, 16));
        buildPrimary();
        bottomBar.addView(primaryBtn);
        shell.addView(bottomBar);
        return shell;
    }

    private View buildTwoPane() {
        LinearLayout row = Ui.horizontal(this);
        row.setGravity(Gravity.TOP);
        row.setPadding(Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 20));

        ScrollView lsv = new ScrollView(this);
        lsv.setClipToPadding(false);
        LinearLayout left = Ui.vertical(this);
        left.setPadding(0, 0, 0, Ui.dp(this, 8));
        left.addView(makeHeaderWithTabs(), topMargin(0));

        buildFileCard();
        buildOptionsCard();
        buildProgressCard();
        buildResultCard();
        left.addView(fileCard, topMargin(22));
        left.addView(optionsCard, topMargin(14));
        left.addView(progressCard, topMargin(14));
        left.addView(resultCard, topMargin(14));

        buildPrimary();
        left.addView(primaryBtn, topMargin(22));
        tvEmptyHint = Ui.text(this, 11.5f, t.text3, false);
        tvEmptyHint.setGravity(Gravity.CENTER);
        tvEmptyHint.setText("也可以从文件管理器「用其他应用打开」直接跳转");
        left.addView(tvEmptyHint, topMargin(18));

        lsv.addView(left);
        row.addView(lsv, new LinearLayout.LayoutParams(Ui.dp(this, 400),
                ViewGroup.LayoutParams.MATCH_PARENT));

        buildListCard(true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rp.leftMargin = Ui.dp(this, 24);
        row.addView(listCard, rp);
        return row;
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, dp);
        return p;
    }

    // ----------------------------------------------------------------- 卡片

    private LinearLayout card() {
        LinearLayout c = Ui.vertical(this);
        c.setBackground(Ui.round(this, t.surface, 24));
        if (!t.dark) c.setElevation(Ui.dp(this, 1.5f));
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        return c;
    }

    private void buildFileCard() {
        LinearLayout c = card();
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));

        LinearLayout empty = Ui.vertical(this);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        ImageView ei = Ui.icon(this, R.drawable.ic_package, t.accent, 26);
        LinearLayout.LayoutParams eip = new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26));
        eip.gravity = Gravity.CENTER_HORIZONTAL;
        ei.setLayoutParams(eip);
        TextView et = Ui.text(this, 16, t.text, true);
        et.setText("选择压缩包");
        et.setGravity(Gravity.CENTER);
        TextView eh = Ui.text(this, 11.5f, t.text3, false);
        eh.setText("从文件管理器中选择，或直接分享到本应用");
        eh.setGravity(Gravity.CENTER);
        empty.addView(ei);
        empty.addView(et, topMargin(8));
        empty.addView(eh, topMargin(6));
        empty.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!busy) pickArchive();
            }
        });

        LinearLayout loaded = Ui.horizontal(this);
        loaded.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        FrameLayout pill = new FrameLayout(this);
        pill.setBackground(Ui.round(this, t.accentSoft, 14));
        ImageView fi = Ui.icon(this, R.drawable.ic_file, t.accent, 22);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22));
        fp.gravity = Gravity.CENTER;
        pill.addView(fi, fp);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        loaded.addView(pill, pp);

        LinearLayout col = Ui.vertical(this);
        col.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        tvName = Ui.text(this, 16, t.text, true);
        tvName.setSingleLine(true);
        tvName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvMeta = Ui.text(this, 12, t.text2, false);
        tvMeta.setSingleLine(true);
        col.addView(tvName);
        col.addView(tvMeta, topMargin(4));
        loaded.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        loaded.addView(Ui.icon(this, R.drawable.ic_chevron, t.text3, 13));
        loaded.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!busy) pickArchive();
            }
        });

        c.addView(empty);
        c.addView(loaded);
        fileEmpty = empty;
        fileLoaded = loaded;
        fileCard = c;
    }

    private void buildOptionsCard() {
        LinearLayout c = card();
        c.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);

        tvPathVal = Ui.text(this, 12.5f, t.text3, false);
        tvPathVal.setSingleLine(true);
        tvPathVal.setEllipsize(TextUtils.TruncateAt.START);
        tvPathVal.setMaxWidth(Ui.dp(this, 140));
        View r1 = optionRow(R.drawable.ic_folder, "解压位置", tvPathVal, new View.OnClickListener() {
            public void onClick(View v) {
                if (!busy) pickTree();
            }
        });

        tvPwdVal = Ui.text(this, 12.5f, t.text3, false);
        View r2 = optionRow(R.drawable.ic_lock, "密码", tvPwdVal, new View.OnClickListener() {
            public void onClick(View v) {
                if (!busy) askPassword();
            }
        });

        c.addView(r1);
        c.addView(Ui.divider(this, t.divider));
        c.addView(r2);
        optionsCard = c;
    }

    private View optionRow(int res, String label, TextView value, View.OnClickListener l) {
        LinearLayout r = Ui.horizontal(this);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));
        r.addView(Ui.icon(this, res, t.text2, 18));
        TextView tv = Ui.text(this, 14.5f, t.text, false);
        tv.setText(label);
        tv.setPadding(Ui.dp(this, 12), 0, 0, 0);
        r.addView(tv, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(value);
        ImageView chev = Ui.icon(this, R.drawable.ic_chevron, t.text3, 13);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(this, 13), Ui.dp(this, 13));
        cp.leftMargin = Ui.dp(this, 6);
        chev.setLayoutParams(cp);
        r.addView(chev);
        r.setOnClickListener(l);
        return r;
    }

    private void buildListCard(boolean withListView) {
        LinearLayout c = card();
        LinearLayout head = Ui.horizontal(this);
        TextView title = Ui.text(this, 13, t.text2, true);
        title.setText("内容预览");
        head.addView(title, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvCount = Ui.text(this, 12, t.text3, false);
        head.addView(tvCount);
        c.addView(head);

        tvMore = Ui.text(this, 11.5f, t.text3, false);
        tvMore.setPadding(0, Ui.dp(this, 10), 0, 0);

        if (withListView) {
            entriesList = new ListView(this);
            entriesList.setDivider(null);
            entriesList.setDividerHeight(0);
            entriesList.setSelector(new ColorDrawable(0));
            entriesList.setBackgroundColor(0);
            entriesList.setAdapter(new EntryAdapter());
            entriesList.setPadding(0, Ui.dp(this, 6), 0, 0);
            entriesList.setClipToPadding(false);
            entriesList.setFastScrollEnabled(true);
            c.addView(entriesList, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            c.addView(tvMore);
            c.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            entriesBox = Ui.vertical(this);
            entriesBox.setPadding(0, Ui.dp(this, 4), 0, 0);
            c.addView(entriesBox);
            c.addView(tvMore);
        }
        listCard = c;
    }

    private void buildProgressCard() {
        LinearLayout c = card();
        TextView label = Ui.text(this, 13, t.text2, false);
        label.setText("正在解压");
        c.addView(label);

        LinearLayout row = Ui.horizontal(this);
        tvPct = Ui.text(this, 34, t.text, true);
        row.addView(tvPct, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvSize = Ui.text(this, 12, t.text3, false);
        row.addView(tvSize);
        c.addView(row, topMargin(4));

        bar = new SlimBar(this, t.track, t.accent);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 7));
        bp.topMargin = Ui.dp(this, 14);
        c.addView(bar, bp);

        LinearLayout foot = Ui.horizontal(this);
        foot.setPadding(0, Ui.dp(this, 12), 0, 0);
        tvCur = Ui.text(this, 12, t.text2, false);
        tvCur.setSingleLine(true);
        tvCur.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        foot.addView(tvCur, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cancel = Ui.text(this, 12.5f, t.accent, true);
        cancel.setText("取消");
        cancel.setPadding(Ui.dp(this, 12), 0, 0, 0);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cancelFlag = true;
                tvCur.setText("正在取消…");
            }
        });
        foot.addView(cancel);
        c.addView(foot);
        progressCard = c;
    }

    private void buildResultCard() {
        LinearLayout c = Ui.horizontal(this);
        c.setBackground(Ui.round(this, t.accentSoft, 18));
        c.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        c.addView(Ui.icon(this, R.drawable.ic_check, t.accent, 16));
        tvResult = Ui.text(this, 12.5f, t.dark ? 0xFFBBD4FF : 0xFF1D4E9E, false);
        tvResult.setPadding(Ui.dp(this, 10), 0, 0, 0);
        c.addView(tvResult, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        resultCard = c;
        resultCard.setVisibility(View.GONE);
    }

    private void buildPrimary() {
        LinearLayout b = Ui.horizontal(this);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        primaryIcon = Ui.icon(this, R.drawable.ic_download, t.onAccent, 18);
        tvPrimary = Ui.text(this, 16, t.onAccent, true);
        tvPrimary.setPadding(Ui.dp(this, 7), 0, 0, 0);
        b.addView(primaryIcon);
        b.addView(tvPrimary);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (state == ST_RUNNING) {
                    cancelFlag = true;
                    tvCur.setText("正在取消…");
                } else {
                    startExtract();
                }
            }
        });
        primaryBtn = b;
        setPrimary(BTN_DISABLED, "开始解压", R.drawable.ic_download);
    }

    private void setPrimary(int style, String label, int icon) {
        int bg;
        int fg;
        if (style == BTN_ACCENT) {
            bg = t.accent;
            fg = t.onAccent;
        } else if (style == BTN_SECONDARY) {
            bg = t.surface2;
            fg = t.text2;
        } else {
            bg = t.surface2;
            fg = t.text3;
        }
        primaryBtn.setBackground(Ui.ripple(this, Ui.round(this, bg, 26), t.rippleColor));
        tvPrimary.setText(label);
        tvPrimary.setTextColor(fg);
        if (icon == 0 || style == BTN_SECONDARY) {
            primaryIcon.setVisibility(View.GONE);
        } else {
            primaryIcon.setVisibility(View.VISIBLE);
            primaryIcon.setImageResource(icon);
            primaryIcon.setColorFilter(fg);
        }
        primaryBtn.setEnabled(style != BTN_DISABLED);
    }

    // ------------------------------------------------------------------ 条目

    private void renderEntries() {
        tvCount.setText(entries.isEmpty() ? "" : "共 " + entries.size() + " 项");
        if (entriesList != null && entriesList.getAdapter() != null) {
            ((BaseAdapter) entriesList.getAdapter()).notifyDataSetChanged();
        }
        if (entriesBox != null) {
            entriesBox.removeAllViews();
            int show = Math.min(5, entries.size());
            for (int i = 0; i < show; i++) entriesBox.addView(entryRow(entries.get(i)));
        }
        if (tvMore != null) {
            if (entriesList != null) {
                tvMore.setVisibility(View.GONE);
            } else {
                int rest = entries.size() - Math.min(5, entries.size());
                tvMore.setVisibility(rest > 0 ? View.VISIBLE : View.GONE);
                tvMore.setText("还有 " + rest + " 项未显示");
            }
        }
    }

    private View entryRow(Engine.Entry e) {
        LinearLayout r = Ui.horizontal(this);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));
        r.addView(Ui.icon(this, e.dir ? R.drawable.ic_folder : R.drawable.ic_file,
                e.dir ? t.text3 : t.accent, 15));
        TextView name = Ui.text(this, 13.5f, t.text, false);
        name.setText(e.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
        r.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView size = Ui.text(this, 11.5f, t.text3, false);
        size.setText(e.dir ? "" : human(e.size));
        r.addView(size);
        return r;
    }

    private class EntryAdapter extends BaseAdapter {
        public int getCount() {
            return entries.size();
        }

        public Object getItem(int i) {
            return entries.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View convertView, ViewGroup parent) {
            return entryRow(entries.get(i));
        }
    }

    // ------------------------------------------------------------------ 交互

    private void pickArchive() {
        if (Files.hasAll(this)) {
            browseArchive();
            return;
        }
        askStorage(new Runnable() {
            public void run() {
                browseArchive();
            }
        }, new Runnable() {
            public void run() {
                pickArchiveSaf();
            }
        });
    }

    private void pickArchiveSaf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            toast("没有可用的文件选择器");
        }
    }

    private void browseArchive() {
        FileBrowser.show(this, t, FileBrowser.MODE_FILE, "选择压缩包", new FileBrowser.Listener() {
            public void onFiles(List<File> files) {
                if (files != null && !files.isEmpty()) loadArchiveFile(files.get(0));
            }

            public void onDir(File dir) {
            }

            public void onCancel() {
            }
        });
    }

    private void pickTree() {
        if (Files.hasAll(this)) {
            browseDir();
            return;
        }
        askStorage(new Runnable() {
            public void run() {
                browseDir();
            }
        }, new Runnable() {
            public void run() {
                pickTreeSaf();
            }
        });
    }

    private void pickTreeSaf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            toast("没有可用的目录选择器");
        }
    }

    private void browseDir() {
        FileBrowser.show(this, t, FileBrowser.MODE_DIR, "选择解压位置", new FileBrowser.Listener() {
            public void onFiles(List<File> files) {
            }

            public void onDir(File dir) {
                outDir = dir;
                treeUri = null;
                applyState();
            }

            public void onCancel() {
            }
        });
    }

    /** 申请「所有文件访问」；拒绝则回落到系统选择器 */
    public void askStorage(final Runnable granted, final Runnable fallback) {
        if (Files.hasAll(this)) {
            granted.run();
            return;
        }
        pendingStorage = granted;
        new AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage("系统自带的文件选择器不好用，可以在应用内直接浏览。\n\n"
                        + "点「去授权」并打开「允许访问所有文件」，之后选压缩包、选解压位置都在应用内完成，"
                        + "文件也能直接存到你指定的文件夹。")
                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Files.request(MainActivity.this, REQ_STORAGE);
                    }
                })
                .setNegativeButton("用系统选择器", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        pendingStorage = null;
                        fallback.run();
                    }
                })
                .show();
    }

    private void askPassword() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setHint("留空表示不使用密码");
        e.setText(pwd);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        box.addView(e);
        new AlertDialog.Builder(this)
                .setTitle("解压密码")
                .setMessage("仅 7z / rar 需要；zip 加密不支持")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        pwd = e.getText().toString();
                        applyState();
                        if (fmt == Engine.Format.SEVEN_Z || fmt == Engine.Format.RAR) reloadEntries();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void reloadEntries() {
        if (cacheFile == null) return;
        final File f = cacheFile;
        final Engine.Format f0 = fmt;
        final String p0 = pwd;
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                final List<Engine.Entry> list;
                try {
                    list = Engine.list(f0, f, p0);
                } catch (Exception ex) {
                    ui.post(new Runnable() {
                        public void run() {
                            busy = false;
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        entries.clear();
                        entries.addAll(list);
                        renderEntries();
                    }
                });
            }
        }, "relist").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_STORAGE) {
            if (Files.hasAll(this) && pendingStorage != null) {
                Runnable r = pendingStorage;
                pendingStorage = null;
                r.run();
            }
            return;
        }
        if (conv != null && conv.onActivityResult(requestCode, resultCode, data)) return;
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == REQ_OPEN) {
            loadArchive(uri);
        } else if (requestCode == REQ_TREE) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            treeUri = uri;
            treeLabel = treeDisplayName(uri);
            applyState();
        }
    }

    private String treeDisplayName(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            int i = docId.indexOf(':');
            String path = i >= 0 ? docId.substring(i + 1) : docId;
            if (path.isEmpty()) return "存储根目录";
            int j = path.lastIndexOf('/');
            return j >= 0 ? path.substring(j + 1) : path;
        } catch (Exception e) {
            return "已选择";
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null || busy) return;
        Uri uri = null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) {
            intent.setAction(null);
            loadArchive(uri);
        }
    }

    // ------------------------------------------------------------------ 载入

    private String queryName(Uri uri) {
        String name = null;
        long size = -1;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (TextUtils.isEmpty(name)) name = uri.getLastPathSegment();
        if (TextUtils.isEmpty(name)) name = "archive.bin";
        archiveSize = size;
        return name;
    }

    private void loadArchive(final Uri uri) {
        if (busy) {
            toast("当前有任务在执行");
            return;
        }
        archiveName = queryName(uri);
        fmt = Engine.Format.UNKNOWN;
        entries.clear();
        renderEntries();
        tvName.setText(archiveName);
        tvMeta.setText("读取中…");
        state = ST_READY;
        applyState();
        resultCard.setVisibility(View.GONE);
        busy = true;

        new Thread(new Runnable() {
            public void run() {
                File tmp = null;
                String err = null;
                Engine.Format f0 = Engine.Format.UNKNOWN;
                List<Engine.Entry> list = new ArrayList<Engine.Entry>();
                try {
                    tmp = copyToCache(uri);
                    f0 = Engine.detect(archiveName, tmp);
                    list = Engine.list(f0, tmp, pwd);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final File ft = tmp;
                final Engine.Format ff = f0;
                final List<Engine.Entry> fl = list;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (ft == null) {
                            state = ST_EMPTY;
                            applyState();
                            toast("读取失败：" + fe);
                            return;
                        }
                        cacheFile = ft;
                        fmt = ff;
                        entries.clear();
                        entries.addAll(fl);
                        tvName.setText(archiveName);
                        tvMeta.setText(metaLine(ff, ft.length(), fl.size()));
                        renderEntries();
                        state = ST_READY;
                        applyState();
                        if (ff == Engine.Format.UNKNOWN) toast("无法识别该格式");
                    }
                });
            }
        }, "loader").start();
    }

    private void loadArchiveFile(final File f) {
        if (busy) {
            toast("当前有任务在执行");
            return;
        }
        archiveName = f.getName();
        fmt = Engine.Format.UNKNOWN;
        entries.clear();
        renderEntries();
        tvName.setText(archiveName);
        tvMeta.setText("读取中…");
        state = ST_READY;
        applyState();
        resultCard.setVisibility(View.GONE);
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                Engine.Format f0 = Engine.Format.UNKNOWN;
                List<Engine.Entry> list = new ArrayList<Engine.Entry>();
                try {
                    f0 = Engine.detect(archiveName, f);
                    list = Engine.list(f0, f, pwd);
                } catch (Exception e) {
                }
                final Engine.Format ff = f0;
                final List<Engine.Entry> fl = list;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        cacheFile = f;
                        archiveSize = f.length();
                        fmt = ff;
                        entries.clear();
                        entries.addAll(fl);
                        tvName.setText(archiveName);
                        tvMeta.setText(metaLine(ff, f.length(), fl.size()));
                        renderEntries();
                        state = ST_READY;
                        applyState();
                        if (ff == Engine.Format.UNKNOWN) toast("无法识别该格式");
                    }
                });
            }
        }, "loader").start();
    }

    private String metaLine(Engine.Format f, long bytes, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.label).append(" · ").append(human(bytes));
        if (count > 0) sb.append(" · ").append(count).append(" 个文件");
        return sb.toString();
    }

    private File copyToCache(Uri uri) throws IOException {
        File dir = getCacheDir();
        File[] old = dir.listFiles();
        if (old != null) {
            for (File o : old) if (o.getName().startsWith("in_")) o.delete();
        }
        File dest = new File(dir, "in_" + System.currentTimeMillis() + ".bin");
        ContentResolver cr = getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("无法读取文件");
        try {
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return dest;
    }

    // ------------------------------------------------------------------ 解压

    private void startExtract() {
        if (cacheFile == null) {
            toast("请先选择压缩包");
            return;
        }
        if (fmt == Engine.Format.UNKNOWN) {
            toast("未识别的格式");
            return;
        }
        final String baseName = stripExt(archiveName);
        final Sink sink;
        try {
            if (outDir != null) {
                sink = new FileSink(new File(outDir, baseName));
            } else if (treeUri != null) {
                sink = new TreeSink(getContentResolver(), treeUri, baseName);
            } else {
                File base = getExternalFilesDir(null);
                if (base == null) base = getFilesDir();
                sink = new FileSink(new File(new File(base, "out"), baseName));
            }
        } catch (Exception e) {
            toast("无法创建输出目录：" + e.getMessage());
            return;
        }

        state = ST_RUNNING;
        cancelFlag = false;
        resultCard.setVisibility(View.GONE);
        bar.set(0f);
        tvPct.setText("0%");
        tvSize.setText("");
        tvCur.setText("准备中…");
        applyState();
        busy = true;

        final Engine.Format f0 = fmt;
        final File src = cacheFile;
        final String p0 = pwd;
        final String dest = sink.describe();

        new Thread(new Runnable() {
            public void run() {
                int files = 0;
                String err = null;
                try {
                    files = Engine.extract(f0, src, p0, sink, new Engine.Progress() {
                        public void onEntry(String name, long done, long total, int n) {
                            postProgress(name, done, total, n);
                        }
                    }, new Engine.Canceller() {
                        public boolean isCancelled() {
                            return cancelFlag;
                        }
                    });
                } catch (Exception e) {
                    err = cancelFlag ? null : (e.getMessage() == null ? e.toString() : e.getMessage());
                } finally {
                    try {
                        sink.close();
                    } catch (Exception ignored) {
                    }
                }
                final int nfiles = files;
                final String ferr = err;
                final boolean cancelled = cancelFlag;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        state = ST_READY;
                        applyState();
                        if (cancelled) {
                            toast("已取消");
                        } else if (ferr != null) {
                            resultCard.setVisibility(View.GONE);
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("解压失败")
                                    .setMessage(ferr + "\n\n常见原因：密码错误、文件损坏、空间不足。")
                                    .setPositiveButton("知道了", null)
                                    .show();
                        } else {
                            tvResult.setText("已完成 · " + nfiles + " 个文件 → " + dest);
                            resultCard.setVisibility(View.VISIBLE);
                            toast("解压完成");
                        }
                    }
                });
            }
        }, "extract").start();
    }

    private void postProgress(final String name, final long done, final long total, final int files) {
        ui.post(new Runnable() {
            public void run() {
                if (total > 0) {
                    float p = done / (float) total;
                    bar.set(p);
                    tvPct.setText(Math.round(p * 100) + "%");
                    tvSize.setText(human(done) + " / " + human(total));
                } else {
                    tvPct.setText(human(done));
                    tvSize.setText("");
                }
                tvCur.setText(name);
            }
        });
    }

    // ------------------------------------------------------------------ 主题

    private void showThemeSheet() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = Ui.vertical(this);
        sheet.setBackground(Ui.round(this, t.dark ? 0xFF1C1C20 : 0xFFFFFFFF, 28));
        sheet.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 24));

        View handle = new View(this);
        handle.setBackground(Ui.round(this, t.text3, 2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, hp);

        TextView title = Ui.text(this, 19, t.text, true);
        title.setText("外观");
        title.setPadding(Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 24), 0);
        sheet.addView(title);

        TextView sub = Ui.text(this, 11.5f, t.text3, false);
        sub.setText("跟随系统时，随系统的深浅色设置自动切换");
        sub.setPadding(Ui.dp(this, 24), Ui.dp(this, 6), Ui.dp(this, 24), 0);
        sheet.addView(sub);

        String[] labels = {"跟随系统", "浅色", "深色"};
        int[] icons = {R.drawable.ic_auto, R.drawable.ic_sun, R.drawable.ic_moon};
        int cur = Theme.mode(this);

        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout row = Ui.horizontal(this);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
            rp.leftMargin = Ui.dp(this, 8);
            rp.rightMargin = Ui.dp(this, 8);
            rp.topMargin = Ui.dp(this, i == 0 ? 14 : 2);
            row.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 16), 0);
            row.setBackground(Ui.ripple(this,
                    i == cur ? Ui.round(this, t.accentSoft, 16) : null, t.rippleColor));

            ImageView ic = Ui.icon(this, icons[i], i == cur ? t.accent : t.text2, 18);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18));
            ip.rightMargin = Ui.dp(this, 14);
            ic.setLayoutParams(ip);
            row.addView(ic);

            TextView tv = Ui.text(this, 15, t.text, i == cur);
            tv.setText(labels[i]);
            row.addView(tv, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (i == cur) row.addView(Ui.icon(this, R.drawable.ic_check, t.accent, 15));

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Theme.setMode(MainActivity.this, idx);
                    d.dismiss();
                    recreate();
                }
            });
            sheet.addView(row, rp);
        }

        d.setContentView(sheet);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(t.dark ? 0.55f : 0.35f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            int width = mode == M_EXPANDED ? Ui.dp(this, 420)
                    : Math.min(screenWidth() - Ui.dp(this, 16), Ui.dp(this, 560));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
    }

    // ------------------------------------------------------------------ 杂项

    private static String stripExt(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String[] multi = {".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz"};
        for (String m : multi) {
            if (lower.endsWith(m)) return name.substring(0, name.length() - m.length());
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String human(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    public void toastSafely(String s) {
        toast(s);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (conv != null) conv.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (tab == 1) {
            if (conv != null && conv.isRunning()) {
                toast("正在转换，请先取消");
                return;
            }
            selectTab(0);
            return;
        }
        if (state == ST_RUNNING) {
            toast("正在解压，请先取消");
            return;
        }
        super.onBackPressed();
    }

}
