package com.cardputer.bleblocks;

/**
 * Quita TODAS las lineas de protocolo (cualquier linea que empiece por ##).
 * Solo deja el cuerpo del archivo.
 *
 * Protocolo en el aire:
 *   ##BLOCKS N / ##TYPE / ##FILE / ##ID / ##CONTINUATION / ##MORE / ##END
 */
public final class BlockAssembler {

    public String fileName = "recv.txt";
    public String fileType = "txt";
    public int expect = 0;
    public int got = 0;
    public boolean complete = false;
    public boolean sawProtocol = false;
    public final StringBuilder body = new StringBuilder();

    public void reset() {
        fileName = "recv.txt";
        fileType = "txt";
        expect = 0;
        got = 0;
        complete = false;
        sawProtocol = false;
        body.setLength(0);
    }

    /** Cualquier linea de control del protocolo empieza por ## */
    public static boolean isProtocolLine(String s) {
        if (s == null) return false;
        s = s.trim();
        return s.startsWith("##");
    }

    public static boolean isMark(String s) {
        return isProtocolLine(s);
    }

    /**
     * Alimenta texto recibido (puede ser un bloque o varios concatenados).
     * Las lineas ##* nunca pasan al body.
     */
    public void feed(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String text = raw.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = text.split("\n", -1);
        boolean more = false;
        int dataLines = 0;

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String s = rawLine.trim();

            // --- protocolo: nunca al body ---
            if (s.startsWith("##")) {
                sawProtocol = true;
                if (s.startsWith("##BLOCKS")) {
                    try {
                        String num = s.length() > 8 ? s.substring(8).trim() : "";
                        if (num.contains(" ")) num = num.split("\\s+")[0];
                        expect = Integer.parseInt(num);
                    } catch (Exception ignored) {}
                } else if (s.startsWith("##TYPE") || s.startsWith("##FMT")) {
                    String t = s.contains(" ") ? s.substring(s.indexOf(' ') + 1).trim() : "";
                    t = BlockSplitter.typeOf("x." + t);
                    if (t != null && !t.isEmpty()) fileType = t;
                } else if (s.startsWith("##FILE") || s.startsWith("##NAME")) {
                    String n = s.contains(" ") ? s.substring(s.indexOf(' ') + 1).trim() : "";
                    String safe = BlockSplitter.sanitizeName(n);
                    if (safe != null) fileName = safe;
                } else if (s.equals("##MORE")) {
                    more = true;
                } else if (s.equals("##END")) {
                    more = false;
                }
                // ##ID, ##CONTINUATION y cualquier otro ##* se ignoran
                continue;
            }

            // --- datos ---
            if (body.length() > 0 || dataLines > 0) body.append('\n');
            body.append(rawLine);
            dataLines++;
        }

        got++;
        if (fileName.indexOf('.') < 0) {
            fileName = fileName + "." + fileType;
        }

        // Completo si el texto no termina en marcador MORE
        String tail = text.trim();
        complete = !tail.endsWith("##MORE");
        // Si no vimos protocolo y hay cuerpo, dar por completo
        if (!sawProtocol && body.length() > 0) {
            complete = true;
        }
        // Si more quedo activo en este feed, no esta completo
        if (more) {
            complete = false;
        }
    }

    /**
     * Pasa de seguridad: elimina cualquier linea residual que empiece por ##
     * (por si llego troceada o de una version antigua del emisor).
     */
    public static String stripProtocolLines(String text) {
        if (text == null || text.isEmpty()) return text;
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int data = 0;
        for (String line : lines) {
            if (line.trim().startsWith("##")) continue;
            if (data > 0) out.append('\n');
            out.append(line);
            data++;
        }
        // quitar newlines finales sobrantes de trozos vacios
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            // dejar al menos un \n final si habia contenido
            if (data <= 1) break;
            // solo recortar si hay muchos vacios al final
            int last = out.lastIndexOf("\n");
            if (last < 0) break;
            String after = out.substring(last + 1);
            if (!after.isEmpty()) break;
            // dejar un unico \n final
            if (out.toString().endsWith("\n\n")) {
                out.setLength(out.length() - 1);
            } else {
                break;
            }
        }
        return out.toString();
    }
}
