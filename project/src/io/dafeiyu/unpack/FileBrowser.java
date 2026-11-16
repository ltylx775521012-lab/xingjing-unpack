package io.dafeiyu.unpack;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 应用内文件浏览器，替代系统的 DocumentsUI 选文件 / 选目录 */
public class FileBrowser {

    public static final int MODE_FILE = 0;
    public static final int MODE_IMAGES = 1;
    public static final int MODE_DIR = 2;

    public interface Listener {
        void onFiles(List<File> files);

        void onDir(File dir);

        void onCancel();
    }

    private final Activity act;
    private final Theme t;
    private final int mode;
    private final String title;
    private final Listener listener;

    private Dialog dialog;
    private File dir;
    private List<File> items = new ArrayList<File>();
    private final Set<File> selected = new HashSet<File>();

    private TextView tvPath;
    private TextView tvSelectAll;
    private TextView tvConfirm;
    private TextView tvHint;
    private View confirmBtn;
    private ListView list;
    private Adapter adapter;
    private LinearLayout chips;
    private ImageView upBtn;
    private View loading;

    private FileBrowser(Activity act, Theme t, int mode, String title, Listener l) {
        this.act = act;
        this.t = t;
        this.mode = mode;
        this.title = title;
        this.listener = l;
    }

    public static void show(Activity act, Theme t, int mode, String title, Listener l) {
        new FileBrowser(act, t, mode, title, l).open();
    }

    private int dp(float v) {
        return Ui.dp(act, v);
    }

    // ------------------------------------------------------------------ 建界面

    private void open() {
        dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = Ui.vertical(act);
        root.setBackground(Ui.round(act, t.surface, 24));

        // 顶栏
        LinearLayout head = Ui.horizontal(act);
        head.setPadding(dp(20), dp(16), dp(12), dp(6));
        TextView tvTitle = Ui.text(act, 18, t.text, true);
        tvTitle.setText(title);
        head.addView(tvTitle, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView close = Ui.icon(act, R.drawable.ic_close, t.text2, 20);
        FrameLayout cb = new FrameLayout(act);
        cb.setBackground(Ui.ripple(act, Ui.round(act, t.surface2, 18), t.rippleColor));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(36), dp(36));
        fp.gravity = Gravity.CENTER;
        cb.addView(close, fp);
        cb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cancel();
            }
        });
        if (mode == MODE_IMAGES) {
            tvSelectAll = Ui.text(act, 13, t.accent, true);
            tvSelectAll.setText("全选");
            tvSelectAll.setPadding(dp(8), dp(6), dp(12), dp(6));
            tvSelectAll.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    toggleSelectAll();
                }
            });
            head.addView(tvSelectAll);
        }
        head.addView(cb, new LinearLayout.LayoutParams(dp(36), dp(36)));
        root.addView(head);

        // 路径条
        LinearLayout pathRow = Ui.horizontal(act);
        pathRow.setBackground(Ui.round(act, t.surface2, 14));
        LinearLayout.LayoutParams prp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        prp.leftMargin = dp(16);
        prp.rightMargin = dp(16);
        prp.topMargin = dp(4);
        pathRow.setLayoutParams(prp);
        pathRow.setPadding(dp(4), 0, dp(10), 0);

        upBtn = Ui.icon(act, R.drawable.ic_up, t.text2, 18);
        FrameLayout ub = new FrameLayout(act);
        FrameLayout.LayoutParams ufp = new FrameLayout.LayoutParams(dp(18), dp(18));
        ufp.gravity = Gravity.CENTER;
        ub.addView(upBtn, ufp);
        ub.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        ub.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                goUp();
            }
        });
        pathRow.addView(ub);

        tvPath = Ui.text(act, 12, t.text2, false);
        tvPath.setSingleLine(true);
        tvPath.setEllipsize(TextUtils.TruncateAt.START);
        tvPath.setPadding(dp(6), 0, 0, 0);
        pathRow.addView(tvPath, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(pathRow);

        // 快捷入口
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        chips = Ui.horizontal(act);
        chips.setPadding(dp(16), dp(10), dp(16), dp(2));
        hs.addView(chips);
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 列表
        FrameLayout listWrap = new FrameLayout(act);
        list = new ListView(act);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));
        list.setPadding(0, dp(4), 0, 0);
        list.setClipToPadding(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        listWrap.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loading = Ui.text(act, 12, t.text3, false);
        ((TextView) loading).setText("读取中…");
        ((TextView) loading).setGravity(Gravity.CENTER);
        listWrap.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(listWrap, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部
        LinearLayout foot = Ui.horizontal(act);
        foot.setPadding(dp(16), dp(10), dp(16), dp(16));

        tvHint = Ui.text(act, 11.5f, t.text3, false);
        foot.addView(tvHint, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (mode == MODE_DIR) {
            LinearLayout mk = pill(false, "新建文件夹");
            mk.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    newFolder();
                }
            });
            foot.addView(mk, new LinearLayout.LayoutParams(dp(96), dp(40)));

            confirmBtn = pill(true, "保存到此处");
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(124), dp(40));
            cp.leftMargin = dp(8);
            confirmBtn.setLayoutParams(cp);
            confirmBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (dir == null) return;
                    Files.setLastDir(act, dir);
                    listener.onDir(dir);
                    dialog.dismiss();
                }
            });
            foot.addView(confirmBtn);
        } else if (mode == MODE_IMAGES) {
            LinearLayout folder = pill(false, "整个文件夹");
            folder.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    importFolder();
                }
            });
            foot.addView(folder, new LinearLayout.LayoutParams(dp(108), dp(40)));

            confirmBtn = pill(true, "添加 0 张");
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(112), dp(40));
            cp.leftMargin = dp(8);
            confirmBtn.setLayoutParams(cp);
            tvConfirm = (TextView) ((LinearLayout) confirmBtn).getChildAt(0);
            confirmBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (selected.isEmpty()) {
                        Toast.makeText(act, "先点选图片，或用「整个文件夹」", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    listener.onFiles(new ArrayList<File>(selected));
                    dialog.dismiss();
                }
            });
            foot.addView(confirmBtn);
        } else {
            LinearLayout cancel = pill(false, "取消");
            cancel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cancel();
                }
            });
            foot.addView(cancel, new LinearLayout.LayoutParams(dp(88), dp(40)));
        }
        root.addView(foot);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(t.dark ? 0.55f : 0.35f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            int sw = act.getResources().getDisplayMetrics().widthPixels;
            int sh = act.getResources().getDisplayMetrics().heightPixels;
            int width = Math.min(sw, dp(640));
            w.setLayout(width, Math.min(sh - dp(48), dp(760)));
            w.setGravity(Gravity.CENTER);
        }
        dialog.show();
        go(Files.lastDir(act));
    }

    private LinearLayout pill(boolean accent, String label) {
        LinearLayout box = Ui.horizontal(act);
        box.setGravity(Gravity.CENTER);
        TextView tv = Ui.text(act, 13, accent ? t.onAccent : t.text2, true);
        tv.setText(label);
        box.setBackground(Ui.ripple(act, Ui.round(act, accent ? t.accent : t.surface2, 20),
                t.rippleColor));
        box.addView(tv);
        return box;
    }

    // ------------------------------------------------------------------ 导航

    private void go(File target) {
        if (target == null || !target.isDirectory()) return;
        dir = target;
        tvPath.setText(dir.getAbsolutePath());
        upBtn.setAlpha(hasParent(dir) ? 1f : 0.3f);
        buildChips();
        load();
    }

    private boolean hasParent(File f) {
        File p = f.getParentFile();
        return p != null && p.canRead() && !f.getAbsolutePath().equals("/");
    }

    private void goUp() {
        if (!hasParent(dir)) return;
        go(dir.getParentFile());
    }

    private void buildChips() {
        chips.removeAllViews();
        for (final File r : Files.roots()) {
            TextView c = Ui.text(act, 12, t.text2, false);
            c.setText(r.getAbsolutePath().equals(Files.home().getAbsolutePath())
                    ? "内部存储" : r.getName());
            c.setPadding(dp(12), dp(7), dp(12), dp(7));
            c.setBackground(Ui.ripple(act, Ui.round(act, t.surface2, 14), t.rippleColor));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = dp(8);
            c.setLayoutParams(p);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    go(r);
                }
            });
            chips.addView(c);
        }
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        list.setVisibility(View.INVISIBLE);
        final File d = dir;
        final boolean imagesOnly = mode == MODE_IMAGES;
        new Thread(new Runnable() {
            public void run() {
                final List<File> got = Files.list(d, mode == MODE_DIR, imagesOnly);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        items = got;
                        adapter.notifyDataSetChanged();
                        loading.setVisibility(got.isEmpty() ? View.VISIBLE : View.GONE);
                        if (got.isEmpty()) ((TextView) loading).setText("这里没有可显示的文件");
                        list.setVisibility(View.VISIBLE);
                        updateHint();
                    }
                });
            }
        }, "browse").start();
    }

    private void updateHint() {
        if (mode == MODE_IMAGES) {
            tvHint.setText(selected.isEmpty() ? "点选图片，可多选" : "已选 " + selected.size() + " 张");
            if (tvConfirm != null) tvConfirm.setText("添加 " + selected.size() + " 张");
            if (tvSelectAll != null) tvSelectAll.setText(allSelected() ? "取消全选" : "全选");
        } else if (mode == MODE_FILE) {
            tvHint.setText("点选文件即可打开");
        } else {
            tvHint.setText("当前文件夹：" + (dir == null ? "" : dir.getName()));
        }
    }

    private List<File> currentImages() {
        List<File> out = new ArrayList<File>();
        for (File f : items) {
            if (!f.isDirectory() && Files.isImage(f.getName())) out.add(f);
        }
        return out;
    }

    private boolean allSelected() {
        List<File> imgs = currentImages();
        return !imgs.isEmpty() && selected.containsAll(imgs);
    }

    private void toggleSelectAll() {
        List<File> imgs = currentImages();
        if (imgs.isEmpty()) {
            Toast.makeText(act, "当前目录没有图片", Toast.LENGTH_SHORT).show();
            return;
        }
        if (allSelected()) selected.removeAll(imgs);
        else selected.addAll(imgs);
        adapter.notifyDataSetChanged();
        updateHint();
    }

    private void importFolder() {
        final File base = dir;
        if (base == null) return;
        final android.widget.CheckBox cb = new android.widget.CheckBox(act);
        cb.setText("包含子文件夹");
        cb.setTextColor(t.text);
        cb.setPadding(dp(20), dp(8), dp(20), dp(8));

        new AlertDialog.Builder(act)
                .setTitle("导入整个文件夹")
                .setMessage("把「" + base.getName() + "」里的图片一次性加入")
                .setView(cb)
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        List<File> found = new ArrayList<File>();
                        collect(base, cb.isChecked(), 0, found);
                        if (found.isEmpty()) {
                            Toast.makeText(act, "这个文件夹里没有图片", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        listener.onFiles(found);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void collect(File d, boolean recursive, int depth, List<File> out) {
        if (d == null || depth > 8 || out.size() > 5000) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        List<File> sub = new ArrayList<File>();
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (recursive) sub.add(f);
            } else if (Files.isImage(n)) {
                out.add(f);
            }
        }
        for (File f : sub) collect(f, true, depth + 1, out);
    }

    private void cancel() {
        listener.onCancel();
        dialog.dismiss();
    }

    private void newFolder() {
        final EditText e = new EditText(act);
        e.setSingleLine(true);
        e.setHint("文件夹名称");
        e.setTextSize(15);
        FrameLayout box = new FrameLayout(act);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(act)
                .setTitle("新建文件夹")
                .setView(box)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = e.getText().toString().trim();
                        if (n.isEmpty()) return;
                        File f = new File(dir, n);
                        if (f.exists()) {
                            Toast.makeText(act, "已存在同名文件夹", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (f.mkdirs()) {
                            Toast.makeText(act, "已创建", Toast.LENGTH_SHORT).show();
                            go(f);
                        } else {
                            Toast.makeText(act, "创建失败，无写入权限", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ 列表

    private class Adapter extends BaseAdapter {
        public int getCount() {
            return items.size();
        }

        public Object getItem(int i) {
            return items.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View cv, ViewGroup parent) {
            final File f = items.get(i);
            LinearLayout row = Ui.horizontal(act);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            row.setPadding(dp(20), 0, dp(20), 0);

            boolean isDir = f.isDirectory();
            int icon = isDir ? R.drawable.ic_folder
                    : (Files.isImage(f.getName()) ? R.drawable.ic_image : R.drawable.ic_file);
            row.addView(Ui.icon(act, icon, isDir ? t.text2 : t.accent, 18));

            TextView name = Ui.text(act, 14, t.text, false);
            name.setText(f.getName());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setPadding(dp(12), 0, dp(8), 0);
            row.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (!isDir) {
                TextView sz = Ui.text(act, 11.5f, t.text3, false);
                sz.setText(Files.size(f.length()));
                row.addView(sz);
            }
            if (mode == MODE_IMAGES && !isDir && selected.contains(f)) {
                row.addView(Ui.icon(act, R.drawable.ic_check, t.accent, 16));
            }
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    pick(f);
                }
            });
            return row;
        }
    }

    private void pick(File f) {
        if (f.isDirectory()) {
            go(f);
            return;
        }
        if (mode == MODE_IMAGES) {
            if (selected.contains(f)) selected.remove(f);
            else selected.add(f);
            adapter.notifyDataSetChanged();
            updateHint();
            return;
        }
        if (mode == MODE_FILE) {
            List<File> one = new ArrayList<File>();
            one.add(f);
            Files.setLastDir(act, dir);
            listener.onFiles(one);
            dialog.dismiss();
        }
    }
}
