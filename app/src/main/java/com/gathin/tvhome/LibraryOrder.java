package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner's own catalogue order, set with the library's Move action and kept in library_order.txt.
 *
 * Packages in this file come first, in file order; the rest follow by label, then package. Apps uninstalled
 * or added to Home simply drop out of the catalogue and keep their line until the owner moves something
 * line until the owner moves something again, which rewrites it with the catalogue as it then stands.
 *
 * Format, UTF-8 text: {@code tvhome-library 1}, then one package name per line. A file that does not decode is
 * ignored, never deleted: a half-written or hand-edited order must not take the catalogue down with it.
 */
final class LibraryOrder {

    private static final String TAG = "TvHome.LibraryOrder";
    static final String FILE_NAME = "library_order.txt";
    private static final String MAGIC = "tvhome-library 1";
    static final int MAX = 256;

    private LibraryOrder() { }

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** The saved order, or an empty list when there is none or it does not decode. */
    static List<String> load(File file) {
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return Collections.emptyList();
        try {
            return decode(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot read " + file.getName() + ": " + e);
            return Collections.emptyList();
        }
    }

    /** The order in this text; empty (never partial) when it is not one: wrong header, blank or duplicate line. */
    static List<String> decode(String text) {
        if (text == null) return Collections.emptyList();
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].equals(MAGIC)) return Collections.emptyList();
        List<String> keys = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String key = lines[i];
            if (key.isEmpty()) {
                if (i == lines.length - 1) break;   // the trailing newline
                return Collections.emptyList();
            }
            if (!LibraryApps.validPackage(key) || keys.contains(key) || keys.size() >= MAX)
                return Collections.emptyList();
            keys.add(key);
        }
        return keys;
    }

    /** One package per line. Invalid names are dropped rather than failing the save. */
    static String encode(List<String> keys) {
        StringBuilder text = new StringBuilder(MAGIC).append('\n');
        int written = 0;
        for (String key : keys) {
            if (!LibraryApps.validPackage(key) || written >= MAX) continue;
            text.append(key).append('\n');
            written++;
        }
        return text.toString();
    }

    /** Writes the order atomically. False (and a log line) when the write failed. */
    static boolean save(File file, List<String> keys) {
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(encode(keys).getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot write " + file.getName() + ": " + e);
            if (out != null) af.failWrite(out);
            return false;
        }
    }

    /** Rank of each saved package (0 = first). Packages without a rank sort after every ranked one. */
    static Map<String, Integer> ranks(List<String> keys) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) result.put(keys.get(i), i);
        return result;
    }

    /** Sort step for two catalogue apps: saved ones first in saved order, then 0 to fall through to the usual rule. */
    static int compare(Map<String, Integer> ranks, String packageA, String packageB) {
        Integer a = ranks.get(packageA), b = ranks.get(packageB);
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return Integer.compare(a, b);
    }
}
