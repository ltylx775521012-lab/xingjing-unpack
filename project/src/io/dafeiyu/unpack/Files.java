package io.dafeiyu.unpack;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 文件系统访问：权限申请 + 目录列举 + 常用路径 */
public final class Files {

    private static final String PREF = "xingjing";
    private static final String KEY_LAST_DIR = "last_dir";

    public static final String[] IMAGE_EXT = {
            ".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".heif",
            ".avif", ".tif", ".tiff", ".jfif", ".jpe"
    };

    private Files() {
    }

    // ------------------------------------------------------------ 权限

    public static boolean hasAll(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void request(Activity a, int code) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName()));
                a.startActivityForResult(i, code);
                return;
            } catch (Exception ignored) {
            }
            try {
                a.startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), code);
                return;
            } catch (Exception ignored) {
            }
        } else {
            a.requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, code);
        }
    }

    // ------------------------------------------------------------ 路径

    public static File home() {
        File f = Environment.getExternalStorageDirectory();
        return f != null ? f : new File("/sdcard");
    }

    public static List<File> roots() {
        List<File> out = new ArrayList<File>();
        File home = home();
        out.add(home);
        File storage = new File("/storage");
        File[] kids = storage.listFiles();
        if (kids != null) {
            for (File k : kids) {
                String n = k.getName();
                if (!k.isDirectory() || "emulated".equals(n) || "self".equals(n)) continue;
                if (n.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) out.add(k);
            }
        }
        File dl = new File(home, "Download");
        if (dl.isDirectory()) out.add(dl);
        File dcim = new File(home, "DCIM");
        if (dcim.isDirectory()) out.add(dcim);
        File pics = new File(home, "Pictures");
        if (pics.isDirectory()) out.add(pics);
        File docs = new File(home, "Documents");
        if (docs.isDirectory()) out.add(docs);
        return out;
    }

    public static File lastDir(Context c) {
        String p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST_DIR, null);
        if (p != null) {
            File f = new File(p);
            if (f.isDirectory()) return f;
        }
        File dl = new File(home(), "Download");
        return dl.isDirectory() ? dl : home();
    }

    public static void setLastDir(Context c, File dir) {
        if (dir == null) return;
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_DIR, dir.getAbsolutePath()).apply();
    }

    public static boolean isWritable(File dir) {
        return dir != null && dir.isDirectory() && dir.canWrite();
    }

    // ------------------------------------------------------------ 列举

    public static List<File> list(File dir, boolean dirsOnly, boolean imagesOnly) {
        List<File> out = new ArrayList<File>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                out.add(f);
            } else if (!dirsOnly) {
                if (!imagesOnly || isImage(n)) out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) {
                boolean da = a.isDirectory(), db = b.isDirectory();
                if (da != db) return da ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    public static boolean isImage(String name) {
        String l = name.toLowerCase(Locale.ROOT);
        for (String e : IMAGE_EXT) if (l.endsWith(e)) return true;
        return false;
    }

    public static String size(long b) {
        if (b < 0) return "";
        if (b < 1024) return b + " B";
        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    public static String display(File f) {
        String p = f.getAbsolutePath();
        String home = home().getAbsolutePath();
        if (p.equals(home)) return "内部存储";
        if (p.startsWith(home + "/")) return p.substring(home.length() + 1);
        return p;
    }

    /** 目录下能否创建文件（用于给"保存到此处"置灰） */
    public static boolean canCreate(File dir) {
        return isWritable(dir);
    }

    public static List<String> quickNames(Context c) {
        List<String> out = new ArrayList<String>();
        for (File f : roots()) out.add(f.getAbsolutePath().equals(home().getAbsolutePath())
                ? "内部存储" : f.getName());
        return out;
    }

    public static String[] imageExtArray() {
        return Arrays.copyOf(IMAGE_EXT, IMAGE_EXT.length);
    }
}
