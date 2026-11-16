package io.dafeiyu.unpack;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;

/** 通过 SAF（ACTION_OPEN_DOCUMENT_TREE）写入用户选择的任意目录 */
public final class TreeSink implements Sink {

    private final ContentResolver cr;
    private final Uri treeUri;
    private final HashMap<String, Uri> dirCache = new HashMap<String, Uri>();
    private final String label;

    public TreeSink(ContentResolver cr, Uri treeUri, String baseName) throws IOException {
        this.cr = cr;
        this.treeUri = treeUri;
        Uri root = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        String safeBase = Engine.sanitize(baseName);
        if (safeBase.isEmpty()) safeBase = "解压结果";
        Uri base = createDir(root, safeBase);
        dirCache.put("", base);
        this.label = treeUri.toString() + " / " + safeBase;
    }

    private Uri dir(String rel) throws IOException {
        if (rel == null || rel.isEmpty()) return dirCache.get("");
        Uri hit = dirCache.get(rel);
        if (hit != null) return hit;
        int i = rel.lastIndexOf('/');
        String parentRel = i >= 0 ? rel.substring(0, i) : "";
        String seg = i >= 0 ? rel.substring(i + 1) : rel;
        Uri parent = dir(parentRel);
        Uri created = createDir(parent, seg);
        dirCache.put(rel, created);
        return created;
    }

    private Uri createDir(Uri parent, String name) throws IOException {
        Uri doc = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (doc == null) {
            Uri existing = findChild(parent, name);
            if (existing != null) return existing;
            throw new IOException("无法创建目录: " + name);
        }
        return doc;
    }

    private Uri findChild(Uri parent, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(parent));
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c == null) return null;
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    @Override
    public OutputStream open(String relPath) throws IOException {
        int i = relPath.lastIndexOf('/');
        String parentRel = i >= 0 ? relPath.substring(0, i) : "";
        String name = i >= 0 ? relPath.substring(i + 1) : relPath;
        Uri parent = dir(parentRel);

        Uri doc = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name);
        if (doc == null) {
            Uri existing = findChild(parent, name);
            if (existing != null) {
                DocumentsContract.deleteDocument(cr, existing);
                doc = DocumentsContract.createDocument(cr, parent, "application/octet-stream", name);
            }
        }
        if (doc == null) throw new IOException("无法创建文件: " + name);

        OutputStream os = cr.openOutputStream(doc, "w");
        if (os == null) throw new IOException("无法写入: " + name);
        return new BufferedOutputStream(os, 64 * 1024);
    }

    @Override
    public void mkdir(String relPath) throws IOException {
        dir(relPath);
    }

    @Override
    public String describe() {
        return label;
    }

    @Override
    public void close() {
    }
}
