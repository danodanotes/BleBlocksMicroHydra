package com.cardputer.bleblocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * En el aire SI hay bloques:
 *
 * Primer bloque:
 *   ##BLOCKS N
 *   ##TYPE py
 *   ##FILE nombre.py
 *   ##ID 1
 *   ...datos...
 *   ##MORE
 *
 * Siguientes:
 *   ##CONTINUATION
 *   ##ID k
 *   ...datos...
 *   ##MORE
 *
 * Ultimo: sin ##MORE
 *
 * Quien recibe QUITA estas lineas al guardar.
 */
public final class BlockSplitter {
    private BlockSplitter() {}

    public static List<String> split(String src, int maxChars) {
        return split(src, maxChars, null);
    }

    public static List<String> split(String src, int maxChars, String fileName) {
        if (maxChars < 80) maxChars = 80;
        if (maxChars > 4000) maxChars = 4000;
        String text = src.replace("\r\n", "\n").replace("\r", "\n");
        if (!text.isEmpty() && !text.endsWith("\n")) text += "\n";

        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int n = Math.min(maxChars, text.length() - i);
            if (i + n < text.length()) {
                int cut = text.lastIndexOf('\n', i + n);
                if (cut > i) {
                    n = cut - i + 1;
                } else {
                    int nxt = text.indexOf('\n', i + n);
                    if (nxt >= 0) n = nxt - i + 1;
                    else n = text.length() - i;
                }
            }
            chunks.add(text.substring(i, i + n));
            i += n;
        }
        if (chunks.isEmpty()) chunks.add(text);

        String safe = sanitizeName(fileName);
        if (safe == null) safe = "recv.txt";
        String type = typeOf(safe);
        int N = chunks.size();
        List<String> out = new ArrayList<>(N);
        for (int k = 0; k < N; k++) {
            StringBuilder b = new StringBuilder();
            if (k == 0) {
                b.append("##BLOCKS ").append(N).append('\n');
                b.append("##TYPE ").append(type).append('\n');
                b.append("##FILE ").append(safe).append('\n');
                b.append("##ID 1\n");
            } else {
                b.append("##CONTINUATION\n");
                b.append("##ID ").append(k + 1).append('\n');
            }
            b.append(chunks.get(k));
            if (k < N - 1) b.append("##MORE\n");
            out.add(b.toString());
        }
        return out;
    }

    public static String typeOf(String name) {
        if (name == null) return "txt";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "txt";
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.length() == 0 ? "txt" : sb.toString();
    }

    public static String sanitizeName(String name) {
        if (name == null) return null;
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        if (sb.length() == 0) return "recv.txt";
        return sb.toString();
    }
}
