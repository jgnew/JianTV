package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A copy of the settings and the theme files, kept outside the app's own storage.
 *
 * The stock TV manager's deep clean treats this launcher's data as junk and wipes it: the columns,
 * the wallpaper and every toggle come back as the APK shipped. Both /data/data/com.ts.launcher and
 * Android/data/com.ts.launcher sit inside that clean, so the copy lives in the launcher's own folder
 * on the SD card, which the cleaner leaves alone. The next start puts it back.
 */
public final class PrefsBackup {

    /** Config.SETTINGS_NAME: the shared_preferences file that holds every choice the settings page makes. */
    private static final String PREFS = "launcher_settings";
    /** The folder on the SD card the copy lives in; hidden, so the gallery and the picker skip it. */
    private static final String FOLDER = ".tslauncher";
    private static final String THEME_COPY = "theme";
    /**
     * The picture in use, kept under a name the stock cleaner does not read as a picture.
     *
     * The deep clean deletes *.jpg / *.jpeg from both Android/data and the card, along with the folders
     * they sit in: the settings copy survived the last clean while theme/wallpapers came back empty, so
     * the picture is kept a second time with no image extension and put back by restore().
     */
    /** Written once the card's old folders have been scanned; the scan costs seconds on every start. */
    private static final String MIGRATED = ".migrated";
    private static final String WALLPAPER_COPY = "wallpaper.bin";
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    /** Request code for the storage permission a clean takes away. */
    static final int REQUEST_STORAGE = 41;

    private PrefsBackup() {}

    /**
     * The folder the copies used to live in: inside the app, next to the theme. On the card they survived the
     * stock cleaner's deep clean, but inside the app a clean takes the copy with the originals, so there is no
     * copy any more: keeping one under the theme nested a copy of itself on every start (711 MB on a
     * MiTV-MFTR0). The folder is deleted once here and null returned.
     */
    static File dir(Context ctx) {
        File legacy = new File(ctx.getFilesDir(), FOLDER);
        if (legacy.exists()) wipe(legacy);
        return null;
    }

    /**
     * Puts the settings and the theme files back after the cleaner has wiped them; a no-op when
     * nothing was lost.
     *
     * The keys go in through the SharedPreferences API rather than by copying the file: the remote
     * key services can read the preferences before Home starts, and a cached instance would keep
     * serving the empty values for the rest of the process.
     */
   /**
     * Older copies sat on the card: Android/data/com.ts.launcher/files held config.json, wallpaper.jpg and
     * the pictures, and a hidden .tslauncher folder held the settings copy. Both are moved into the app once,
     * so an upgrade keeps every choice, and the card is not read again afterwards.
     */
    private static void migrateLegacy(Context ctx) {
        if (new File(ctx.getFilesDir(), MIGRATED).isFile()) return;
        File inside = ctx.getFilesDir();
        File live = ctx.getExternalFilesDir(null);
        if (live != null) {
            move(new File(live, Config.FILE_NAME), new File(inside, Config.FILE_NAME));
            move(new File(live, Config.WALLPAPER_NAME), new File(inside, Config.WALLPAPER_NAME));
            File[] pictures = new File(live, "wallpapers").listFiles();
            if (pictures != null) {
                File target = new File(inside, "wallpapers");
                target.mkdirs();
                for (File picture : pictures) move(picture, new File(target, picture.getName()));
            }
            // A build that kept its copy inside Android/data as well left these here.
            File fallback = new File(live, FOLDER);
            if (fallback.isDirectory()) {
                move(new File(fallback, WALLPAPER_COPY), privateCopy(ctx));
                File themed = new File(fallback, THEME_COPY);
                if (themed.isDirectory()) {
                    copyTheme(themed, inside);
                    deleteTree(themed);
                }
            }
        }
        File card = new File("/sdcard/" + FOLDER);
        if (card.isDirectory()) {
            move(new File(card, WALLPAPER_COPY), privateCopy(ctx));
            File cardTheme = new File(card, THEME_COPY);
            if (cardTheme.isDirectory()) copyTheme(cardTheme, inside);
            deleteTree(card);
        }
        write(new File(ctx.getFilesDir(), MIGRATED), new byte[]{'1'});
    }

    /** Takes a file the older build left outside into the app, unless a copy of the same size is there already. */
    private static void move(File from, File to) {
        if (from == null || !from.isFile() || from.length() <= 0) return;
        if (to.isFile() && to.length() == from.length()) {
            from.delete();
            return;
        }
        copy(from, to);
        if (to.isFile() && to.length() == from.length()) from.delete();
    }

    static void restore(Context ctx) {
        // The settings, the theme and a second picture used to be copied into .tslauncher; see dir(). Only the films
        // an older build kept in the app still need dropping.
        new File(ctx.getFilesDir(), "wallpaper-video.mp4").delete();
        new File(ctx.getFilesDir(), "wallpaper-video.bin").delete();
        // The copy folder is never written again, but its empty shell survives beside the settings, and an empty
        // folder still counts as a row in the app's data folder.
        File legacyCopy = new File(ctx.getFilesDir(), FOLDER);
        if (legacyCopy.exists()) {
            wipe(legacyCopy);
        }
        migrateLegacy(ctx);
    }

    /** The picture kept inside the app's own folder, where neither the cleaner nor a card scan looks. */
    private static File privateCopy(Context ctx) {
        return new File(ctx.getFilesDir(), WALLPAPER_COPY);
    }

    /**
     * The copy of the picture in use to fall back to: the app's own folder first, the card's second.
     * Null when neither holds one.
     */
    static File keptWallpaper(Context ctx) {
        File inside = privateCopy(ctx);
        if (inside.isFile() && inside.length() > 0) return inside;
        // dir() only drops the folder the older build kept; it has no copy to look in.
        dir(ctx);
        return null;
    }

    /**
     * Stores the picture the owner just picked, in the app's own folder: the wallpaper Home draws is that
     * copy, so the stock cleaner emptying wallpapers/ cannot leave Home without a picture.
     */
    static void keepWallpaper(Context ctx, File picture) {
        if (picture == null || !picture.isFile() || picture.length() <= 0) return;
        copy(picture, privateCopy(ctx));
    }

    /** True when there is a stored picture and the owner has not switched the wallpaper off. */
    static boolean hasKeptWallpaper(Context ctx) {
        String choice = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(Config.WALLPAPER_CHOICE_KEY, null);
        if (choice == null || WallpaperRotation.CHOICE_NONE.equals(choice)) return false;
        return keptWallpaper(ctx) != null;
    }

    /**
     * Drops the stored picture when the owner goes back to the theme's wallpaper (or switches it off):
     * the copy must not keep outranking what the theme decides. A theme picture is stored again by the
     * next save, so this only clears what the owner had picked.
     */
    static void forgetWallpaper(Context ctx) {
        privateCopy(ctx).delete();
        dir(ctx);
    }

    private static void write(File file, byte[] bytes) {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (IOException ignored) {
            // A lost name costs only the rename: restore() falls back to wallpaper.jpg.
        }
    }

    /**
     * Asks for the storage permission when a clean has taken it away. Clearing an app's data resets
     * the permission along with the files, and the copy on the SD card cannot be read without it, so
     * one Allow tap restores everything on the next start.
     */
    static void ensureAccess(android.app.Activity activity) {
        if (android.os.Build.VERSION.SDK_INT >= 30) return;
        if (activity.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        activity.requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
    }

    /** Restores again once the permission has been answered. */
    static void onAccessAnswered(android.app.Activity activity) {
        restore(activity);
        watch(activity);
        // Home was already built on the defaults; draw it again with the restored choices.
        activity.recreate();
    }

    /** Nothing to keep current: every choice and the picture already live inside the app. */
    static void watch(Context ctx) {
        // Nothing to keep in step: every choice and the picture already live inside the app, and a clean that
        // takes one would take a copy in the same folder with it.
    }

    /**
     * The theme files, minus wallpapers/: the picture in use is kept once, as wallpaper.bin, so a theme
     * push and a wallpaper swap do not leave a growing folder of old pictures on the card.
     */
    private static void copyTheme(File from, File to) {
        if (!from.isDirectory() || from.equals(to)) return;
        // Everything lives inside the app now, so the backup folder sits under the folder that stores the theme.
        // Walking a theme that holds the backup, or the backup of a theme that holds it, wrote one more level on
        // every start: .tslauncher/theme/.tslauncher/... reached 711 MB of the same picture on a MiTV-MFTR0.
        String fromPath = from.getAbsolutePath(), toPath = to.getAbsolutePath();
        if (toPath.startsWith(fromPath + File.separator) || fromPath.startsWith(toPath + File.separator)) return;
        if (!to.isDirectory() && !to.mkdirs()) return;
        File[] children = from.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.equals(to) || "wallpapers".equals(child.getName()) || FOLDER.equals(child.getName())) continue;
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                copyTheme(child, target);
            } else if (child.length() > 0) {
                // Only a changed file is written again: a start must not rewrite the whole theme.
                if (!sameFile(child, target)) copy(child, target);
            }
        }
    }
    private static boolean sameFile(File from, File to) {
        return to.isFile() && to.length() == from.length() && to.lastModified() >= from.lastModified();
    }

    /** Removes a file or a whole folder; a folder the launcher no longer needs must not keep space. */
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static void wipe(File dir) {
        try {
            Runtime.getRuntime().exec(new String[]{"rm", "-rf", dir.getAbsolutePath()}).waitFor();
        } catch (Exception | Error noShell) {
            // fall through to the walk
        }
        if (!dir.exists()) return;
        try {
            deleteTree(dir);
        } catch (Exception | Error tooDeep) {
            // a tree too deep to walk still costs only the folder entry
        }
    }

    private static void copy(File from, File to) {
        if (from.equals(to)) return;
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (IOException ignored) {
            // A copy that fails is worth no more than a missing one: the next save tries again.
        }
    }
}
