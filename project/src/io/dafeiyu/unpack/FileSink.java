package io.dafeiyu.unpack;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 写入应用可访问的普通目录（如 Android/data/包名/files/out） */
public final class FileSink implements Sink {

    private final File root;
    private final String rootPath;

    public FileSink(File root) throws IOException {
        this.root = root;
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("无法创建输出目录: " + root.getAbsolutePath());
        }
        this.rootPath = root.getCanonicalPath();
    }

    private File resolve(String rel) throws IOException {
        File f = new File(root, rel);
        String cp = f.getCanonicalPath();
        if (!cp.equals(rootPath) && !cp.startsWith(rootPath + File.separator)) {
            throw new IOException("路径越界: " + rel);
        }
        return f;
    }

    @Override
    public OutputStream open(String relPath) throws IOException {
        File f = resolve(relPath);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent.getAbsolutePath());
        }
        return new BufferedOutputStream(new FileOutputStream(f), 64 * 1024);
    }

    @Override
    public void mkdir(String relPath) throws IOException {
        File f = resolve(relPath);
        if (!f.exists() && !f.mkdirs()) {
            throw new IOException("无法创建目录: " + f.getAbsolutePath());
        }
    }

    @Override
    public String describe() {
        return root.getAbsolutePath();
    }

    @Override
    public void close() {
    }
}
