package io.dafeiyu.unpack;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 图片格式转换页（移植自 imgconv 的批量转换逻辑，编码器换成系统 API） */
public class ConvertPane {

    public static final int REQ_IMAGES = 2001;
    public static final int REQ_TREE = 2002;

    private final MainActivity act;
    private final Theme t;
    private final int mode;
    private final int cdp;

    private final List<Uri> uris = new ArrayList<Uri>();
    private final List<String> names = new ArrayList<String>();

    private int fmt = ImageConv.FMT_PNG;
    private int quality = 90;
    private Uri treeUri;
    private String treeLabel = "";
    private File outDir;
    private boolean running;
    private volatile boolean cancel;

    private TextView tvCount, tvPathVal, tvQuality, tvPct, tvCur, tvPrimary, tvEmpty, tvNote, tvClear;
    private ImageView primaryIcon;
    private View primaryBtn, progressCard, resultCard, listCard;
    private LinearLayout qualityRow;
    private LinearLayout listBox, chipsRow;
    private ListView imgList;
    private BaseAdapter imgAdapter;
    private ListView listView;
    private SlimBar bar;
    private SeekBar seek;

    private final View header;

    public ConvertPane(MainActivity act, Theme t, int mode, View header) {
        this.act = act;
        this.t = t;
        this.mode = mode;
        this.header = header;
        this.cdp = mode == 2 ? 28 : 20;
    }

    private int dp(float v) {
        return Ui.dp(act, v);
    }

    // ------------------------------------------------------------------ 构建

    public View build() {
        return mode == 2 ? buildTwoPane() : buildSingle();
    }

    private View buildSingle() {
        LinearLayout shell = Ui.vertical(act);
        LinearLayout head = Ui.vertical(act);
        head.setPadding(dp(20), dp(16), dp(20), 0);
        head.addView(header);
        shell.addView(head);
        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.setClipToPadding(false);

        LinearLayout col = Ui.vertical(act);
        col.setPadding(dp(20), dp(16), dp(20), dp(16));
        col.addView(imagesCard(), top(2));
        col.addView(formatCard(), top(14));
        col.addView(outputCard(), top(14));
        buildProgressCard();
        buildResultCard();
        col.addView(progressCard, top(14));
        col.addView(resultCard, top(14));
        sv.addView(col);

        shell.addView(sv, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = Ui.vertical(act);
        bottom.setPadding(dp(20), dp(8), dp(20), dp(16));
        buildPrimary();
        bottom.addView(primaryBtn);
        shell.addView(bottom);
        applyState();
        return shell;
    }

    private View buildTwoPane() {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(28), dp(16), dp(28), dp(16));

        ScrollView lsv = new ScrollView(act);
        LinearLayout left = Ui.vertical(act);
        left.setPadding(0, 0, 0, dp(8));
        left.addView(header, top(0));
        left.addView(imagesCard(), top(14));
        left.addView(formatCard(), top(14));
        left.addView(outputCard(), top(14));
        buildProgressCard();
        buildResultCard();
        left.addView(progressCard, top(14));
        left.addView(resultCard, top(14));
        buildPrimary();
        left.addView(primaryBtn, top(20));
        lsv.addView(left);
        row.addView(lsv, new LinearLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.MATCH_PARENT));

        buildListCard(true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rp.leftMargin = dp(24);
        row.addView(listCard, rp);
        applyState();
        return row;
    }

    private LinearLayout.LayoutParams top(int v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(v);
        return p;
    }

    private LinearLayout card() {
        LinearLayout c = Ui.vertical(act);
        c.setBackground(Ui.round(act, t.surface, 24));
        if (!t.dark) c.setElevation(dp(1.5f));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private View imagesCard() {
        LinearLayout c = card();
        LinearLayout head = Ui.horizontal(act);
        tvCount = Ui.text(act, 13, t.text2, true);
        tvCount.setText("已选图片");
        head.addView(tvCount, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView clear = Ui.text(act, 13, t.text3, true);
        clear.setText("清空");
        clear.setPadding(dp(10), dp(4), dp(10), dp(4));
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (running) return;
                uris.clear();
                names.clear();
                applyState();
            }
        });
        head.addView(clear);
        this.tvClear = clear;

        TextView add = Ui.text(act, 13, t.accent, true);
        add.setText("+ 添加");
        add.setPadding(dp(10), dp(4), dp(4), dp(4));
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!running) pickImages();
            }
        });
        head.addView(add);
        c.addView(head);

        tvEmpty = Ui.text(act, 12, t.text3, false);
        tvEmpty.setText("支持 jpg / png / webp / bmp / gif / heic");
        tvEmpty.setPadding(0, dp(8), 0, 0);
        c.addView(tvEmpty);

        imgList = new ListView(act);
        imgList.setDivider(null);
        imgList.setDividerHeight(0);
        imgList.setSelector(new android.graphics.drawable.ColorDrawable(0));
        imgList.setPadding(0, dp(6), 0, 0);
        imgList.setClipToPadding(false);
        imgAdapter = new FileAdapter();
        imgList.setAdapter(imgAdapter);
        imgList.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        c.addView(imgList);
        return c;
    }

    private View formatCard() {
        LinearLayout c = card();
        TextView label = Ui.text(act, 13, t.text2, true);
        label.setText("输出格式");
        c.addView(label);

        chipsRow = Ui.horizontal(act);
        chipsRow.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < ImageConv.LABELS.length; i++) {
            final int idx = i;
            TextView chip = Ui.text(act, 13, t.text2, true);
            chip.setText(ImageConv.LABELS[i]);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(36), 1f);
            p.rightMargin = i == ImageConv.LABELS.length - 1 ? 0 : dp(8);
            chip.setLayoutParams(p);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (running) return;
                    fmt = idx;
                    styleChips();
                    applyState();
                }
            });
            chipsRow.addView(chip);
        }
        c.addView(chipsRow);

        qualityRow = Ui.horizontal(act);
        qualityRow.setPadding(0, dp(12), 0, 0);
        TextView ql = Ui.text(act, 12, t.text2, false);
        ql.setText("质量");
        qualityRow.addView(ql);

        seek = new SeekBar(act);
        seek.setMax(99);
        seek.setProgress(quality - 1);
        seek.setProgressTintList(ColorStateList.valueOf(t.accent));
        seek.setThumbTintList(ColorStateList.valueOf(t.accent));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(t.track));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        sp.leftMargin = dp(10);
        seek.setLayoutParams(sp);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
                quality = v + 1;
                tvQuality.setText(String.valueOf(quality));
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        qualityRow.addView(seek);

        tvQuality = Ui.text(act, 12, t.text3, false);
        tvQuality.setText(String.valueOf(quality));
        tvQuality.setPadding(dp(10), 0, 0, 0);
        tvQuality.setMinWidth(dp(28));
        qualityRow.addView(tvQuality);
        c.addView(qualityRow);

        tvNote = Ui.text(act, 11, t.text3, false);
        tvNote.setPadding(0, dp(10), 0, 0);
        tvNote.setText("PNG / BMP 无损；JPG / WEBP 用质量参数，透明区域填白");
        c.addView(tvNote);
        return c;
    }

    private View outputCard() {
        LinearLayout c = card();
        c.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout r = Ui.horizontal(act);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        r.addView(Ui.icon(act, R.drawable.ic_folder, t.text2, 18));
        TextView label = Ui.text(act, 14.5f, t.text, false);
        label.setText("输出位置");
        label.setPadding(dp(12), 0, 0, 0);
        r.addView(label, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvPathVal = Ui.text(act, 12.5f, t.text3, false);
        tvPathVal.setText("应用目录");
        r.addView(tvPathVal);
        ImageView chev = Ui.icon(act, R.drawable.ic_chevron, t.text3, 13);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(13), dp(13));
        cp.leftMargin = dp(6);
        chev.setLayoutParams(cp);
        r.addView(chev);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!running) pickTree();
            }
        });
        c.addView(r);
        return c;
    }

    private void buildListCard(boolean withList) {
        LinearLayout c = card();
        LinearLayout head = Ui.horizontal(act);
        TextView title = Ui.text(act, 13, t.text2, true);
        title.setText("待转换");
        head.addView(title, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cnt = Ui.text(act, 12, t.text3, false);
        cnt.setText("按添加顺序");
        head.addView(cnt);

        c.addView(head);
        if (withList) {
            listView = new ListView(act);
            listView.setDivider(null);
            listView.setDividerHeight(0);
            listView.setSelector(new android.graphics.drawable.ColorDrawable(0));
            listView.setAdapter(new FileAdapter());
            listView.setPadding(0, dp(6), 0, 0);
            listView.setClipToPadding(false);
            listView.setFastScrollEnabled(true);
            c.addView(listView, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            c.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            listBox = Ui.vertical(act);
            c.addView(listBox);
        }
        listCard = c;
    }

    private void buildProgressCard() {
        LinearLayout c = card();
        TextView label = Ui.text(act, 13, t.text2, false);
        label.setText("正在转换");
        c.addView(label);

        LinearLayout row = Ui.horizontal(act);
        tvPct = Ui.text(act, 34, t.text, true);
        tvPct.setText("0%");
        row.addView(tvPct, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvCur = Ui.text(act, 12, t.text3, false);
        tvCur.setSingleLine(true);
        tvCur.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(tvCur);
        c.addView(row, top(4));

        bar = new SlimBar(act, t.track, t.accent);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
        bp.topMargin = dp(14);
        c.addView(bar, bp);
        progressCard = c;
        progressCard.setVisibility(View.GONE);
    }

    private void buildResultCard() {
        LinearLayout c = Ui.horizontal(act);
        c.setBackground(Ui.round(act, t.accentSoft, 18));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.addView(Ui.icon(act, R.drawable.ic_check, t.accent, 16));
        tvNote = Ui.text(act, 12.5f, t.dark ? 0xFFBBD4FF : 0xFF1D4E9E, false);
        tvNote.setPadding(dp(10), 0, 0, 0);
        c.addView(tvNote, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        resultCard = c;
        resultCard.setVisibility(View.GONE);
    }

    private void buildPrimary() {
        LinearLayout b = Ui.horizontal(act);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        primaryIcon = Ui.icon(act, R.drawable.ic_image, t.onAccent, 18);
        tvPrimary = Ui.text(act, 16, t.onAccent, true);
        tvPrimary.setText("开始转换");
        tvPrimary.setPadding(dp(7), 0, 0, 0);
        b.addView(primaryIcon);
        b.addView(tvPrimary);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (running) {
                    cancel = true;
                    tvCur.setText("正在取消…");
                } else {
                    start();
                }
            }
        });
        primaryBtn = b;
        stylePrimary();
    }

    private void stylePrimary() {
        if (running) {
            primaryBtn.setBackground(Ui.ripple(act, Ui.round(act, t.surface2, 26), t.rippleColor));
            tvPrimary.setText("取消转换");
            tvPrimary.setTextColor(t.text2);
            primaryIcon.setVisibility(View.GONE);
        } else if (uris.isEmpty()) {
            primaryBtn.setBackground(Ui.ripple(act, Ui.round(act, t.surface2, 26), t.rippleColor));
            tvPrimary.setText("开始转换");
            tvPrimary.setTextColor(t.text3);
            primaryIcon.setVisibility(View.VISIBLE);
            primaryIcon.setColorFilter(t.text3);
            primaryBtn.setEnabled(false);
        } else {
            primaryBtn.setBackground(Ui.ripple(act, Ui.round(act, t.accent, 26), t.rippleColor));
            tvPrimary.setText("开始转换");
            tvPrimary.setTextColor(t.onAccent);
            primaryIcon.setVisibility(View.VISIBLE);
            primaryIcon.setColorFilter(t.onAccent);
            primaryBtn.setEnabled(true);
        }
    }

    private void styleChips() {
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            TextView chip = (TextView) chipsRow.getChildAt(i);
            boolean on = i == fmt;
            chip.setBackground(Ui.ripple(act,
                    Ui.round(act, on ? t.accent : t.surface2, 18), t.rippleColor));
            chip.setTextColor(on ? t.onAccent : t.text2);
        }
    }

    private void applyState() {
        if (tvCount == null) return;
        tvCount.setText(uris.isEmpty() ? "已选图片" : "已选 " + uris.size() + " 张");
        tvPathVal.setText(outDir != null ? outDir.getName() : (treeUri == null ? "应用目录" : treeLabel));
        boolean lossy = ImageConv.isLossy(fmt);
        qualityRow.setAlpha(lossy ? 1f : 0.35f);
        seek.setEnabled(lossy);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(uris.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (tvClear != null) {
            tvClear.setVisibility(uris.isEmpty() ? View.GONE : View.VISIBLE);
        }
        styleChips();
        stylePrimary();
        renderList();
    }

    private void renderList() {
        if (imgList != null) {
            int n = uris.size();
            int maxRows = mode == 2 ? 3 : 4;
            int rows = Math.max(1, Math.min(maxRows, n));
            ViewGroup.LayoutParams p = imgList.getLayoutParams();
            p.height = dp(rows * 46);
            imgList.setLayoutParams(p);
            imgList.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
            if (imgAdapter != null) imgAdapter.notifyDataSetChanged();
        }
        if (listView != null && listView.getAdapter() != null) {
            ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
        }
    }

    private void removeAt(int i) {
        if (i < 0 || i >= uris.size()) return;
        uris.remove(i);
        names.remove(i);
        applyState();
    }

    private View fileRow(final int i, String name) {
        LinearLayout r = Ui.horizontal(act);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        r.addView(Ui.icon(act, R.drawable.ic_file, t.accent, 15));
        TextView tv = Ui.text(act, 13, t.text, false);
        tv.setText(name);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tv.setPadding(dp(12), 0, 0, 0);
        r.addView(tv, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (running) return true;
                removeAt(i);
                return true;
            }
        });
        return r;
    }

    private class FileAdapter extends BaseAdapter {
        public int getCount() {
            return uris.size();
        }

        public Object getItem(int i) {
            return uris.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View cv, ViewGroup parent) {
            return fileRow(i, names.get(i));
        }
    }

    // ------------------------------------------------------------------ 交互

    public boolean onActivityResult(int req, int res, Intent data) {
        if (req == REQ_IMAGES) {
            if (res != Activity.RESULT_OK || data == null) return true;
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                add(data.getData());
            }
            applyState();
            return true;
        }
        if (req == REQ_TREE) {
            if (res != Activity.RESULT_OK || data == null || data.getData() == null) return true;
            Uri uri = data.getData();
            try {
                act.getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            treeUri = uri;
            treeLabel = treeName(uri);
            applyState();
            return true;
        }
        return false;
    }

    private void add(Uri uri) {
        if (uri == null) return;
        for (Uri u : uris) if (u.equals(uri)) return;
        uris.add(uri);
        names.add(queryName(uri));
    }

    private String queryName(Uri uri) {
        String n = null;
        Cursor c = null;
        try {
            c = act.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (TextUtils.isEmpty(n)) n = uri.getLastPathSegment();
        if (TextUtils.isEmpty(n)) n = "image";
        return n;
    }

    private String treeName(Uri uri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            int i = docId.indexOf(':');
            String p = i >= 0 ? docId.substring(i + 1) : docId;
            if (p.isEmpty()) return "存储根目录";
            int j = p.lastIndexOf('/');
            return j >= 0 ? p.substring(j + 1) : p;
        } catch (Exception e) {
            return "已选择";
        }
    }

    private void pickImages() {
        act.askStorage(new Runnable() {
            public void run() {
                browseImages();
            }
        }, new Runnable() {
            public void run() {
                pickImagesSaf();
            }
        });
    }

    private void browseImages() {
        FileBrowser.show(act, t, FileBrowser.MODE_IMAGES, "选择图片", new FileBrowser.Listener() {
            public void onFiles(java.util.List<File> files) {
                for (File f : files) addFile(f);
                applyState();
            }

            public void onDir(File dir) {
            }

            public void onCancel() {
            }
        });
    }

    private void addFile(File f) {
        if (f == null) return;
        Uri u = Uri.fromFile(f);
        for (Uri x : uris) if (x.equals(u)) return;
        uris.add(u);
        names.add(f.getName());
    }

    private void pickImagesSaf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            act.startActivityForResult(i, REQ_IMAGES);
        } catch (Exception e) {
            act.toastSafely("没有可用的文件选择器");
        }
    }

    private void pickTree() {
        act.askStorage(new Runnable() {
            public void run() {
                browseOut();
            }
        }, new Runnable() {
            public void run() {
                pickTreeSaf();
            }
        });
    }

    private void browseOut() {
        FileBrowser.show(act, t, FileBrowser.MODE_DIR, "选择输出位置", new FileBrowser.Listener() {
            public void onFiles(java.util.List<File> files) {
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

    private void pickTreeSaf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            act.startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            act.toastSafely("没有可用的目录选择器");
        }
    }

    // ------------------------------------------------------------------ 转换

    private void start() {
        if (uris.isEmpty() || running) return;
        final Sink sink;
        try {
            if (outDir != null) {
                sink = new FileSink(new File(outDir, "图片转换"));
            } else if (treeUri != null) {
                sink = new TreeSink(act.getContentResolver(), treeUri, "图片转换");
            } else {
                File base = act.getExternalFilesDir(null);
                if (base == null) base = act.getFilesDir();
                sink = new FileSink(new File(base, "图片转换"));
            }
        } catch (Exception e) {
            act.toastSafely("无法创建输出目录：" + e.getMessage());
            return;
        }
        running = true;
        cancel = false;
        resultCard.setVisibility(View.GONE);
        progressCard.setVisibility(View.VISIBLE);
        bar.set(0f);
        tvPct.setText("0%");
        stylePrimary();

        final String dest = sink.describe();
        final ContentResolver cr = act.getContentResolver();
        final List<Uri> src = new ArrayList<Uri>(uris);
        final List<String> srcNames = new ArrayList<String>(names);
        final int f = fmt;
        final int q = quality;

        new Thread(new Runnable() {
            public void run() {
                int ok = 0, fail = 0;
                String lastErr = null;
                Set<String> used = new HashSet<String>();
                for (int i = 0; i < src.size(); i++) {
                    if (cancel) break;
                    final int n0 = i + 1;
                    final String nm = srcNames.get(i);
                    update(n0, src.size(), nm);
                    Bitmap bmp = null;
                    try {
                        bmp = ImageConv.decode(cr, src.get(i));
                        String outName = uniqueName(nm, f, used);
                        OutputStream os = sink.open(outName);
                        try {
                            ImageConv.encode(bmp, f, q, os);
                        } finally {
                            try {
                                os.close();
                            } catch (IOException ignored) {
                            }
                        }
                        ok++;
                    } catch (Throwable e) {
                        fail++;
                        lastErr = e.getMessage() == null ? e.toString() : e.getMessage();
                    } finally {
                        if (bmp != null) bmp.recycle();
                    }
                }
                try {
                    sink.close();
                } catch (Exception ignored) {
                }
                final int fok = ok;
                final int ffail = fail;
                final String ferr = lastErr;
                final boolean cancelled = cancel;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        running = false;
                        progressCard.setVisibility(View.GONE);
                        stylePrimary();
                        if (cancelled) {
                            act.toastSafely("已取消");
                        } else if (fok == 0) {
                            act.toastSafely("转换失败：" + (ferr == null ? "未知错误" : ferr));
                        } else {
                            String s = "完成 · " + fok + " 张"
                                    + (ffail > 0 ? "，失败 " + ffail : "")
                                    + " → " + dest;
                            tvNote.setText(s);
                            resultCard.setVisibility(View.VISIBLE);
                            act.toastSafely("转换完成");
                        }
                    }
                });
            }
        }, "convert").start();
    }

    private void update(final int done, final int total, final String name) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                tvCur.setText(name);
                tvPct.setText(done + "/" + total);
                bar.set(total <= 0 ? 0f : done / (float) total);
            }
        });
    }

    private String uniqueName(String src, int f, Set<String> used) {
        String base = src;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.length() > 60) base = base.substring(0, 60);
        if (base.isEmpty()) base = "image";
        String name = base + ImageConv.EXTS[f];
        int k = 2;
        while (used.contains(name.toLowerCase(Locale.ROOT))) {
            name = base + "_" + k + ImageConv.EXTS[f];
            k++;
        }
        used.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    public boolean isRunning() {
        return running;
    }

    public void onDestroy() {
        cancel = true;
    }
}
