package io.dafeiyu.unpack;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 缩略图解码 + 内存缓存（后台线程，主线程回调） */
public final class Thumbs {

    public interface Callback {
        void onLoaded(String path, Bitmap bmp);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static LruCache<String, Bitmap> cache;

    private Thumbs() {
    }

    private static synchronized LruCache<String, Bitmap> cache() {
        if (cache == null) {
            int max = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8);
            cache = new LruCache<String, Bitmap>(max) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
        }
        return cache;
    }

    public static void load(final File f, final int px, final Callback cb) {
        final String key = f.getAbsolutePath() + "@" + px + "@" + f.lastModified();
        Bitmap hit = cache().get(key);
        if (hit != null) {
            cb.onLoaded(f.getAbsolutePath(), hit);
            return;
        }
        POOL.execute(new Runnable() {
            public void run() {
                final Bitmap b = decode(f, px);
                if (b == null) return;
                cache().put(key, b);
                UI.post(new Runnable() {
                    public void run() {
                        cb.onLoaded(f.getAbsolutePath(), b);
                    }
                });
            }
        });
    }

    private static Bitmap decode(File f, int px) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

            int sample = 1;
            while (probe.outWidth / (sample * 2) >= px && probe.outHeight / (sample * 2) >= px) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clear() {
        LruCache<String, Bitmap> c = cache();
        if (c != null) c.evictAll();
    }
}
