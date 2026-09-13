package com.cardputer.bleblocks;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class FileSaver {
    private FileSaver() {}

    public static String saveUtf8(Context ctx, String name, String body) throws Exception {
        name = BlockSplitter.sanitizeName(name);
        String mime = mimeOf(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("MediaStore insert");
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("no stream");
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, v, null, null);
            return "Descargas/" + name;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        File f = unique(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return f.getAbsolutePath();
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 2;
        while (true) {
            f = new File(dir, base + "_" + i + ext);
            if (!f.exists()) return f;
            i++;
        }
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".py") || n.endsWith(".pyw")) return "text/x-python";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".xml")) return "text/xml";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".toml") || n.endsWith(".ini") || n.endsWith(".cfg")) return "text/plain";
        return "text/plain";
    }
}
