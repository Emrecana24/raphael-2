package com.raphael.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Kütüphanesiz minik FileProvider: paylaşım (imgs/export) ve sitenin okuyacağı yükleme dosyaları (up). */
public class FileProv extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri u) {
        List<String> seg = u.getPathSegments();
        if (seg.size() != 2) return null;
        String name = new File(seg.get(1)).getName();
        File base;
        switch (seg.get(0)) {
            case "imgs": base = new File(getContext().getFilesDir(), "imgs"); break;
            case "export": base = new File(getContext().getCacheDir(), "export"); break;
            case "up": base = new File(getContext().getCacheDir(), "up"); break;
            default: return null;
        }
        return new File(base, name);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) {
        File f = resolve(uri);
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if (f != null && f.exists()) {
            String n = f.getName();
            int i = n.indexOf('_', n.indexOf('_') + 1);       // up/<id>_<ad> -> ad
            if (uri.getPathSegments().get(0).equals("up") && n.indexOf('_') > 0) n = n.substring(n.indexOf('_') + 1);
            c.addRow(new Object[]{n, f.length()});
        }
        return c;
    }

    @Override public String getType(Uri uri) {
        File f = resolve(uri);
        if (f == null) return null;
        String n = f.getName().toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
