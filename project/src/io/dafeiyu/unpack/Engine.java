package io.dafeiyu.unpack;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 解压核心：只依赖 JDK 与三个纯 Java 库（commons-compress / xz / junrar），不涉及任何 native 代码。
 */
public final class Engine {

    public enum Format {
        ZIP("ZIP"), SEVEN_Z("7Z"), RAR("RAR"),
        TAR("TAR"), TAR_GZ("TAR.GZ"), TAR_BZ2("TAR.BZ2"), TAR_XZ("TAR.XZ"),
        GZ("GZ"), BZ2("BZ2"), XZ("XZ"), UNKNOWN("未知");

        public final String label;

        Format(String label) {
            this.label = label;
        }
    }

    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean dir;

        Entry(String name, long size, boolean dir) {
            this.name = name;
            this.size = size;
            this.dir = dir;
        }
    }

    public interface Progress {
        /** total <= 0 表示总量未知 */
        void onEntry(String name, long done, long total, int files);
    }

    public interface Canceller {
        boolean isCancelled();
    }

    private static final int LIST_CAP = 400;
    private static final int BUF = 64 * 1024;

    private Engine() {
    }

    // ---------------------------------------------------------------- detect

    public static Format detect(String name, File f) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) return Format.TAR_GZ;
        if (n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz")) return Format.TAR_BZ2;
        if (n.endsWith(".tar.xz") || n.endsWith(".txz")) return Format.TAR_XZ;
        if (n.endsWith(".tar")) return Format.TAR;
        if (n.endsWith(".7z")) return Format.SEVEN_Z;
        if (n.endsWith(".rar")) return Format.RAR;
        if (n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".xapk")
                || n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".pptx")
                || n.endsWith(".epub") || n.endsWith(".cbz") || n.endsWith(".kmz")) {
            return Format.ZIP;
        }
        if (n.endsWith(".gz")) return Format.GZ;
        if (n.endsWith(".bz2")) return Format.BZ2;
        if (n.endsWith(".xz")) return Format.XZ;

        // 扩展名不可靠时看魔数
        byte[] h = new byte[512];
        int got = 0;
        try (InputStream in = new FileInputStream(f)) {
            got = in.read(h);
        } catch (IOException ignored) {
        }
        if (got >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) return Format.ZIP;
        if (got >= 6 && h[0] == '7' && h[1] == 'z' && h[2] == (byte) 0xBC
                && h[3] == (byte) 0xAF && h[4] == 0x27 && h[5] == 0x1C) return Format.SEVEN_Z;
        if (got >= 7 && h[0] == 'R' && h[1] == 'a' && h[2] == 'r' && h[3] == '!'
                && h[4] == 0x1A && h[5] == 0x07) return Format.RAR;
        if (got >= 2 && (h[0] & 0xFF) == 0x1F && (h[1] & 0xFF) == 0x8B) return Format.GZ;
        if (got >= 3 && h[0] == 'B' && h[1] == 'Z' && h[2] == 'h') return Format.BZ2;
        if (got >= 6 && (h[0] & 0xFF) == 0xFD && h[1] == '7' && h[2] == 'z' && h[3] == 'X'
                && h[4] == 'Z' && h[5] == 0) return Format.XZ;
        if (got >= 262 && h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r') {
            return Format.TAR;
        }
        return Format.UNKNOWN;
    }

    // ----------------------------------------------------------------- list

    public static List<Entry> list(Format fmt, File f, String pw) throws Exception {
        List<Entry> out = new ArrayList<Entry>();
        switch (fmt) {
            case ZIP:
                listZip(f, out);
                break;
            case SEVEN_Z:
                list7z(f, pw, out);
                break;
            case RAR:
                listRar(f, pw, out);
                break;
            case TAR:
            case TAR_GZ:
            case TAR_BZ2:
            case TAR_XZ:
                listTar(fmt, f, out);
                break;
            default:
                break;
        }
        return out;
    }

    private static void listZip(File f, List<Entry> out) throws IOException {
        ZipFile zf = openZip(f);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements() && out.size() < LIST_CAP) {
                ZipEntry e = en.nextElement();
                out.add(new Entry(e.getName(), e.getSize(), e.isDirectory()));
            }
        } finally {
            closeQuietly(zf);
        }
    }

    private static void list7z(File f, String pw, List<Entry> out) throws IOException {
        char[] p = (pw == null || pw.isEmpty()) ? null : pw.toCharArray();
        SevenZFile z = new SevenZFile(f, p);
        try {
            SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null && out.size() < LIST_CAP) {
                out.add(new Entry(e.getName(), e.getSize(), e.isDirectory()));
            }
        } finally {
            closeQuietly(z);
        }
    }

    private static void listRar(File f, String pw, List<Entry> out) throws Exception {
        Archive a = openRar(f, pw);
        try {
            for (FileHeader h : a.getFileHeaders()) {
                if (out.size() >= LIST_CAP) break;
                out.add(new Entry(h.getFileNameString(), h.getFullUnpackSize(), h.isDirectory()));
            }
        } finally {
            closeQuietly(a);
        }
    }

    private static void listTar(Format fmt, File f, List<Entry> out) throws IOException {
        InputStream in = openTarStream(fmt, f);
        try {
            TarArchiveInputStream t = new TarArchiveInputStream(in);
            TarArchiveEntry e;
            while ((e = t.getNextTarEntry()) != null && out.size() < LIST_CAP) {
                out.add(new Entry(e.getName(), e.getSize(), e.isDirectory()));
            }
        } finally {
            closeQuietly(in);
        }
    }

    // -------------------------------------------------------------- extract

    public static int extract(Format fmt, File f, String pw, Sink sink,
                              Progress progress, Canceller canceller) throws Exception {
        Counter c = new Counter(progress, canceller);
        switch (fmt) {
            case ZIP:
                extractZip(f, sink, c);
                break;
            case SEVEN_Z:
                extract7z(f, pw, sink, c);
                break;
            case RAR:
                extractRar(f, pw, sink, c);
                break;
            case TAR:
            case TAR_GZ:
            case TAR_BZ2:
            case TAR_XZ:
                extractTar(fmt, f, sink, c);
                break;
            case GZ:
            case BZ2:
            case XZ:
                extractSingle(fmt, f, sink, c);
                break;
            default:
                throw new IOException("不支持的压缩格式");
        }
        return c.files;
    }

    private static void extractZip(File f, Sink sink, Counter c) throws IOException {
        ZipFile zf = openZip(f);
        try {
            List<ZipEntry> all = new ArrayList<ZipEntry>();
            long total = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                all.add(e);
                if (!e.isDirectory() && e.getSize() > 0) total += e.getSize();
            }
            c.start(total);
            for (ZipEntry e : all) {
                c.checkCancel();
                String rel = sanitize(e.getName());
                if (rel.isEmpty()) continue;
                if (e.isDirectory()) {
                    sink.mkdir(rel);
                    continue;
                }
                InputStream in = zf.getInputStream(e);
                try {
                    OutputStream out = sink.open(rel);
                    try {
                        c.pump(in, out, rel);
                    } finally {
                        closeQuietly(out);
                    }
                } finally {
                    closeQuietly(in);
                }
                c.fileDone();
            }
        } finally {
            closeQuietly(zf);
        }
    }

    private static void extract7z(File f, String pw, Sink sink, Counter c) throws IOException {
        char[] p = (pw == null || pw.isEmpty()) ? null : pw.toCharArray();
        SevenZFile z = new SevenZFile(f, p);
        try {
            List<SevenZArchiveEntry> all = new ArrayList<SevenZArchiveEntry>();
            long total = 0;
            SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null) {
                all.add(e);
                if (!e.isDirectory() && e.getSize() > 0) total += e.getSize();
            }
            c.start(total);
            for (SevenZArchiveEntry en : all) {
                c.checkCancel();
                String rel = sanitize(en.getName());
                if (rel.isEmpty()) continue;
                if (en.isDirectory()) {
                    sink.mkdir(rel);
                    continue;
                }
                InputStream in = z.getInputStream(en);
                try {
                    OutputStream out = sink.open(rel);
                    try {
                        c.pump(in, out, rel);
                    } finally {
                        closeQuietly(out);
                    }
                } finally {
                    closeQuietly(in);
                }
                c.fileDone();
            }
        } finally {
            closeQuietly(z);
        }
    }

    private static void extractRar(File f, String pw, Sink sink, Counter c) throws Exception {
        Archive a = openRar(f, pw);
        try {
            List<FileHeader> hs = a.getFileHeaders();
            long total = 0;
            for (FileHeader h : hs) {
                if (!h.isDirectory() && h.getFullUnpackSize() > 0) total += h.getFullUnpackSize();
            }
            c.start(total);
            for (FileHeader h : hs) {
                c.checkCancel();
                String rel = sanitize(h.getFileNameString());
                if (rel.isEmpty()) continue;
                if (h.isDirectory()) {
                    sink.mkdir(rel);
                    continue;
                }
                OutputStream out = sink.open(rel);
                try {
                    a.extractFile(h, out);
                } finally {
                    closeQuietly(out);
                }
                c.fileDone();
            }
        } finally {
            closeQuietly(a);
        }
    }

    private static void extractTar(Format fmt, File f, Sink sink, Counter c) throws IOException {
        InputStream raw = openTarStream(fmt, f);
        try {
            TarArchiveInputStream t = new TarArchiveInputStream(raw);
            c.start(-1);
            TarArchiveEntry e;
            while ((e = t.getNextTarEntry()) != null) {
                c.checkCancel();
                String rel = sanitize(e.getName());
                if (rel.isEmpty()) continue;
                if (e.isDirectory()) {
                    sink.mkdir(rel);
                    continue;
                }
                OutputStream out = sink.open(rel);
                try {
                    c.pump(t, out, rel);
                } finally {
                    closeQuietly(out);
                }
                c.fileDone();
            }
        } finally {
            closeQuietly(raw);
        }
    }

    private static void extractSingle(Format fmt, File f, Sink sink, Counter c) throws IOException {
        String name = f.getName();
        if (name.endsWith(".bin")) name = "output.bin";
        int dot = name.lastIndexOf('.');
        String outName = dot > 0 ? name.substring(0, dot) : name + ".out";
        String rel = sanitize(outName);
        InputStream raw = new FileInputStream(f);
        InputStream in;
        try {
            switch (fmt) {
                case GZ:
                    in = new GzipCompressorInputStream(new BufferedInputStream(raw, BUF));
                    break;
                case BZ2:
                    in = new BZip2CompressorInputStream(new BufferedInputStream(raw, BUF), true);
                    break;
                case XZ:
                    in = new XZCompressorInputStream(new BufferedInputStream(raw, BUF), true);
                    break;
                default:
                    in = raw;
                    break;
            }
            c.start(-1);
            OutputStream out = sink.open(rel);
            try {
                c.pump(in, out, rel);
            } finally {
                closeQuietly(out);
            }
            c.fileDone();
        } finally {
            closeQuietly(raw);
        }
    }

    // --------------------------------------------------------------- helpers

    private static ZipFile openZip(File f) throws IOException {
        IOException firstError = null;
        // 先按 UTF-8 打开；部分国产压缩包用 GBK 编码文件名且未打 UTF-8 标记，
        // JDK 解码失败会直接抛 ZipException，所以这里逐级回退。
        String[] encodings = {"UTF-8", "GBK"};
        for (String enc : encodings) {
            try {
                ZipFile zf = new ZipFile(f, Charset.forName(enc));
                boolean bad = false;
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (n.indexOf('\uFFFD') >= 0) {
                        bad = true;
                        break;
                    }
                }
                if (!bad) return zf;
                closeQuietly(zf);
            } catch (IOException e) {
                if (firstError == null) firstError = e;
            }
        }
        // 兜底：ISO-8859-1 不会解码失败，至少保证能解压出文件
        try {
            return new ZipFile(f, Charset.forName("ISO-8859-1"));
        } catch (IOException e) {
            throw firstError != null ? firstError : e;
        }
    }

    private static Archive openRar(File f, String pw) throws Exception {
        if (pw == null || pw.isEmpty()) return new Archive(f);
        return new Archive(f, pw);
    }

    private static InputStream openTarStream(Format fmt, File f) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(f), BUF);
        try {
            switch (fmt) {
                case TAR_GZ:
                    return new GzipCompressorInputStream(in);
                case TAR_BZ2:
                    return new BZip2CompressorInputStream(in, true);
                case TAR_XZ:
                    return new XZCompressorInputStream(in, true);
                default:
                    return in;
            }
        } catch (IOException e) {
            closeQuietly(in);
            throw e;
        }
    }

    /** 归一化路径并阻断 ../ 越界 */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String n = raw.replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        for (String seg : n.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) seg = "_";
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    static void closeQuietly(Object c) {
        try {
            if (c instanceof java.io.Closeable) ((java.io.Closeable) c).close();
        } catch (Throwable ignored) {
        }
    }

    private static final class Counter {
        private final Progress p;
        private final Canceller c;
        private long total;
        private long done;
        private long lastReport;
        private String current = "";
        int files;

        Counter(Progress p, Canceller c) {
            this.p = p;
            this.c = c;
        }

        void start(long total) {
            this.total = total;
            this.done = 0;
            this.lastReport = 0;
            if (p != null) p.onEntry("准备中…", 0, total, 0);
        }

        void checkCancel() throws IOException {
            if (c != null && c.isCancelled()) throw new IOException("已取消");
        }

        void pump(InputStream in, OutputStream out, String name) throws IOException {
            current = name;
            byte[] buf = new byte[BUF];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                done += r;
                long now = System.currentTimeMillis();
                if (p != null && now - lastReport > 90) {
                    lastReport = now;
                    p.onEntry(name, done, total, files);
                }
            }
            out.flush();
            if (p != null) p.onEntry(name, done, total, files);
        }

        void fileDone() {
            files++;
            if (p != null) p.onEntry(current, done, total, files);
        }
    }
}
