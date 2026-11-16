package io.dafeiyu.unpack;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 纯系统 API 的图片转换：解码走 BitmapFactory，编码走 Bitmap.compress（BMP 自己写） */
public final class ImageConv {

    public static final int FMT_PNG = 0;
    public static final int FMT_JPG = 1;
    public static final int FMT_WEBP = 2;
    public static final int FMT_BMP = 3;

    public static final String[] LABELS = {"PNG", "JPG", "WEBP", "BMP"};
    public static final String[] EXTS = {".png", ".jpg", ".webp", ".bmp"};

    /** 只有 JPG / WEBP 是有损的，质量滑块才对它生效 */
    public static boolean isLossy(int fmt) {
        return fmt == FMT_JPG || fmt == FMT_WEBP;
    }

    /** 解码 + 按 EXIF 摆正方向 */
    public static Bitmap decode(ContentResolver cr, Uri uri) throws IOException {
        Bitmap bmp = null;
        try {
            bmp = decodeFull(cr, uri, 1);
        } catch (OutOfMemoryError oom) {
            bmp = null;
        }
        if (bmp == null) {
            try {
                bmp = decodeFull(cr, uri, 2);
            } catch (OutOfMemoryError oom) {
                throw new IOException("图片过大，内存不足");
            }
        }
        if (bmp == null) throw new IOException("无法解码该图片");
        int rot = rotation(cr, uri);
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }
        return bmp;
    }

    private static Bitmap decodeFull(ContentResolver cr, Uri uri, int sample) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("无法读取文件");
        try {
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) throw new IOException("无法解码该图片");
            return b;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int rotation(ContentResolver cr, Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return 0;
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable e) {
            return 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 编码到 out；JPG / BMP 没有 alpha，需要白底压平 */
    public static void encode(Bitmap src, int fmt, int quality, OutputStream out) throws IOException {
        Bitmap target = src;
        boolean flattened = false;
        if (fmt == FMT_JPG || fmt == FMT_BMP) {
            target = flatten(src, Color.WHITE);
            flattened = true;
        }
        try {
            boolean ok;
            switch (fmt) {
                case FMT_PNG:
                    ok = target.compress(Bitmap.CompressFormat.PNG, 100, out);
                    break;
                case FMT_JPG:
                    ok = target.compress(Bitmap.CompressFormat.JPEG, clamp(quality), out);
                    break;
                case FMT_WEBP:
                    ok = compressWebp(target, clamp(quality), out);
                    break;
                case FMT_BMP:
                    writeBmp(target, out);
                    ok = true;
                    break;
                default:
                    throw new IOException("不支持的输出格式");
            }
            if (!ok) throw new IOException("编码失败");
        } finally {
            if (flattened && target != src) target.recycle();
        }
    }

    private static int clamp(int q) {
        return Math.max(1, Math.min(100, q));
    }

    private static boolean compressWebp(Bitmap b, int q, OutputStream out) {
        if (Build.VERSION.SDK_INT >= 30) {
            return b.compress(Bitmap.CompressFormat.WEBP_LOSSY, q, out);
        }
        return b.compress(Bitmap.CompressFormat.WEBP, q, out);
    }

    /** 透明区域铺底色 */
    public static Bitmap flatten(Bitmap src, int bgColor) {
        if (!src.hasAlpha()) {
            return src.getConfig() == Bitmap.Config.ARGB_8888 ? src : src.copy(Bitmap.Config.ARGB_8888, false);
        }
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(bgColor);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, 0, 0, p);
        return out;
    }

    private static void writeBmp(Bitmap bmp, OutputStream os) throws IOException {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int rowSize = (w * 3 + 3) & ~3;
        int dataSize = rowSize * h;
        int fileSize = 54 + dataSize;

        ByteArrayOutputStream hdr = new ByteArrayOutputStream(54);
        le16(hdr, 0x4D42);
        le32(hdr, fileSize);
        le16(hdr, 0);
        le16(hdr, 0);
        le32(hdr, 54);
        le32(hdr, 40);
        le32(hdr, w);
        le32(hdr, h);
        le16(hdr, 1);
        le16(hdr, 24);
        le32(hdr, 0);
        le32(hdr, dataSize);
        le32(hdr, 2835);
        le32(hdr, 2835);
        le32(hdr, 0);
        le32(hdr, 0);
        os.write(hdr.toByteArray());

        byte[] row = new byte[rowSize];
        int[] pixels = new int[w];
        for (int y = h - 1; y >= 0; y--) {
            bmp.getPixels(pixels, 0, w, 0, y, w, 1);
            int p = 0;
            for (int x = 0; x < w; x++) {
                int c = pixels[x];
                row[p++] = (byte) (c & 0xFF);
                row[p++] = (byte) ((c >> 8) & 0xFF);
                row[p++] = (byte) ((c >> 16) & 0xFF);
            }
            while (p < rowSize) row[p++] = 0;
            os.write(row);
        }
        os.flush();
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }
}
