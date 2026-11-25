package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The opening frame of a film, kept as a picture beside the app's own files.
 *
 * Home has nothing to show while a hardware decoder is being built — about half a second on this platform — and
 * asking the system media reader for that frame takes seconds more. So the frame is worked out exactly once, when
 * the film becomes the wallpaper, and every later look at it is a file read. Picture and film are kept in the same
 * window-sized, same-colour form so the moment one hands over to the other is a frame-for-frame swap.
 */
final class FilmStill {

    private static final String TAG = "TvHome.Wallpaper";
    /** Bumped when a kept picture would stand for something other than what the film really shows. */
    private static final int VERSION = 10;
    private static final String VERSION_KEY = "still_version";

    private FilmStill() {
    }

    /** The kept picture for one film, named after the film so two films never share one. */
    static File file(Context context, File film) {
        if (context == null || film == null) return null;
        try {
            File dir = context.getFilesDir();
            if (dir == null) return null;
            return new File(dir, "still-" + film.getName() + ".jpg");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** True when a picture of this window size is already kept for the film, read without decoding it. */
    static boolean kept(Context context, File film, int wide, int tall) {
        File kept = file(context, film);
        if (kept == null || !kept.isFile()) return false;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(kept.getPath(), probe);
            if (probe.outWidth != wide || probe.outHeight != tall) return false;
            // A picture kept by an older build was worked out with different colour handling, and it is that one
            // the film would be judged against on every way back: it has to be redone once, and this is the only
            // moment the difference can be noticed.
            return context.getSharedPreferences("wallpaper_spot", Context.MODE_PRIVATE)
                    .getInt(VERSION_KEY, 0) == VERSION;
        } catch (Exception | OutOfMemoryError failure) {
            return false;
        }
    }

    /** The kept picture at this window size, or null when none was kept (or one was kept for another size). */
    static Bitmap read(Context context, File film, int wide, int tall) {
        if (!kept(context, film, wide, tall)) return null;
        File kept = file(context, film);
        if (kept == null) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(kept.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Any picture kept for the film, whatever size it was kept at: only ever a stand-in while the right one is made. */
    static Bitmap readAny(Context context, File film) {
        File kept = file(context, film);
        if (kept == null || !kept.isFile()) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(kept.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** The frame the film was left at, or -1 when nothing is known: the picture and the spot are kept together. */
    static long spot(Context context, File film) {
        if (context == null || film == null) return -1;
        try {
            SharedPreferences prefs = context.getSharedPreferences("wallpaper_spot", Context.MODE_PRIVATE);
            return prefs.getLong("at:" + film.getName(), -1);
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Stores the moment the kept picture stands for, so the film can carry on from exactly that spot. */
    static void remember(Context context, File film, long atUs) {
        if (context == null || film == null) return;
        try {
            long now = Math.max(0, atUs);
            // Leaving and coming back lands on the same keyframe again and again; writing the same value out to
            // the file every time spends flash for nothing.
            if (spot(context, film) == now) return;
            context.getSharedPreferences("wallpaper_spot", Context.MODE_PRIVATE).edit()
                    .putLong("at:" + film.getName(), now).apply();
        } catch (Exception ignored) {
            // a spot that cannot be stored only means the film starts from the picture's own frame
        }
    }

    /** The frame the film was last left at: the opening frame only for a film that has never played. */
    static Bitmap make(Context context, File film, int wide, int tall) {
        long at = spot(context, film);
        return make(context, film, at < 0 ? 0 : at, wide, tall);
    }

    /**
     * One gate for the one frame being worked out: the settings page and Home can ask for the same frame at the
     * same moment, and only ever for the wallpaper in use. A map of per-film gates would grow a row for every film
     * ever picked and never shed one; a single gate is all the concurrency there really is.
     */
    private static final Object WORKING = new Object();

    /**
     * This film's kept frame, worked out at most once.
     *
     * A film made the wallpaper is asked for its frame twice within a second — the settings page starts it and
     * Home, brought forward behind it, starts it again — and each answer used to cost a decode of the same frame.
     * The second caller now waits for the first and then reads the picture instead of decoding it again.
     */
    static Bitmap ensure(Context context, File film, int wide, int tall) {
        if (context == null || film == null) return null;
        Bitmap early = read(context, film, wide, tall);
        if (early != null) return early;
        synchronized (WORKING) {
            Bitmap read = read(context, film, wide, tall);
            if (read != null) return read;
            long at = spot(context, film);
            return make(context, film, at < 0 ? 0 : at, wide, tall);
        }
    }

    /**
     * Opens the film once, for a caller that will ask it for frames again: on this platform the opening costs
     * almost all of the time, and it is the same film every time Home comes back.
     */
    static MediaMetadataRetriever open(File film) {
        if (film == null || !film.isFile()) return null;
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(film.getPath());
            return reader;
        } catch (Exception | OutOfMemoryError failure) {
            try {
                reader.release();
            } catch (Exception ignored) {
                // already gone
            }
            return null;
        }
    }

    /**
     * Works one frame out and keeps it, replacing whatever was kept before: there is only ever one picture per
     * film, and it always stands for the moment stored beside it. Every way back to Home reads it as a file.
     *
     * The frame is asked of the platform's reader, kept beside the app's files, and every later look at it is a
     * file read: nothing is decoded twice for the same film.
     */
    static Bitmap make(Context context, File film, long atUs, int wide, int tall) {
        try {
            return grab(context, film, null, atUs, wide, tall);
        } catch (Exception | OutOfMemoryError failure) {
            Log.w(TAG, "cannot keep a frame of " + film.getName(), failure);
            return null;
        }
    }

    /** The frame the platform reader gives back, on the keyframe the film will start from. */
    static Bitmap grab(Context context, File film, MediaMetadataRetriever reader, long atUs, int wide, int tall) {
        if (film == null || !film.isFile()) return null;
        long at = SystemClock.uptimeMillis();
        long used = atUs;
        Bitmap shot = null;
        boolean own = reader == null;
        if (own) reader = open(film);
        if (reader != null) {
            Time time = new Time(nearestSync(film, atUs));
            used = time.us;
            try {
                // getScaledFrameAtTime is API 27: asked for by name on an older platform it throws at once and
                // the frame below is read unscaled instead, so the guard costs nothing and lint can see it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    shot = reader.getScaledFrameAtTime(time.us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, wide, tall);
                }
            } catch (Exception | LinkageError ignored) {
                shot = null;
            }
            if (shot == null) {
                try {
                    shot = reader.getFrameAtTime(time.us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } catch (Exception | LinkageError ignored) {
                    shot = null;
                }
            }
            if (own) {
                try {
                    reader.release();
                } catch (Exception ignored) {
                    // the platform frees it with the process
                }
            }
        }
        if (shot == null) return null;
        shot = coverScreen(shot, wide, tall);
        keep(context, film, shot);
        remember(context, film, used);
        Log.d(TAG, "frame kept for " + film.getName() + " at " + (used / 1000) + " ms in "
                + (SystemClock.uptimeMillis() - at) + " ms");
        return shot;
    }

    /** The moment a kept frame stands for, kept in a box so the fallback can correct it in place. */
    private static final class Time {
        long us;

        Time(long us) {
            this.us = us;
        }
    }

    /**
     * The keyframe at or before this moment. The platform's frame reader does not say which frame it gave back,
     * so the spot is narrowed to the keyframe that the same request will land on: a picture taken from a keyframe
     * and a film started from that keyframe are the same picture.
     */
    private static long nearestSync(File film, long atUs) {
        if (film == null) return atUs;
        try {
            android.media.MediaExtractor extractor = new android.media.MediaExtractor();
            try {
                extractor.setDataSource(film.getPath());
                int track = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    String mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        track = i;
                        break;
                    }
                }
                if (track < 0) return atUs;
                extractor.selectTrack(track);
                extractor.seekTo(Math.max(0, atUs), android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long found = extractor.getSampleTime();
                return found >= 0 ? found : atUs;
            } finally {
                extractor.release();
            }
        } catch (Exception | LinkageError ignored) {
            return atUs;
        }
    }

    /**
     * Keeps the opening frame in the background: a film made the wallpaper is worked out once, here, rather than
     * on the first trip back to Home where a screen sits waiting for it.
     */
    static void prepare(final Context context, final File film, final int wide, final int tall) {
        if (context == null || film == null) return;
        if (kept(context, film, wide, tall)) return;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                ensure(context, film, wide, tall);
            }
        }, "still maker");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Keeps only the opening frame of the wallpaper in use: a picture kept for a film that is no longer the
     * wallpaper is meat on the app's own storage that nothing will ever read again, and the films themselves are
     * pruned down to the one in use already.
     */
    static void prune(Context context, File keep) {
        if (context == null) return;
        try {
            File dir = context.getFilesDir();
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) return;
            String png = keep == null ? null : "still-" + keep.getName() + ".png";
            String jpg = keep == null ? null : "still-" + keep.getName() + ".jpg";
            for (File file : files) {
                String name = file.getName();
                if (!name.startsWith("still-")) continue;
                if (png != null && (png.equals(name) || jpg.equals(name))) continue;
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) {
            // an unreadable folder only means nothing is pruned this time
        }
        // Every film ever picked used to leave its moment in the preferences for good. Only the wallpaper in use
        // needs one, and a stale row costs the same whether or not the film is still there.
        try {
            SharedPreferences prefs = context.getSharedPreferences("wallpaper_spot", Context.MODE_PRIVATE);
            String mine = keep == null ? null : "at:" + keep.getName();
            SharedPreferences.Editor editor = null;
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith("at:")) continue;
                if (mine != null && mine.equals(key)) continue;
                if (editor == null) editor = prefs.edit();
                editor.remove(key);
            }
            if (editor != null) editor.apply();
        } catch (Exception ignored) {
            // preferences that cannot be read only mean nothing is pruned this time
        }
    }

    /** Same pruning, off the main thread: Home does this on the way up and must not wait for a folder scan. */
    static void pruneLater(final Context context, final File keep) {
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                prune(context, keep);
            }
        }, "still pruner");
        worker.setDaemon(true);
        worker.start();
    }

    /** Scales and crops one frame to cover the window, the way the video layer covers it. */
    private static Bitmap coverScreen(Bitmap shot, int wide, int tall) {
        if (wide <= 0 || tall <= 0) return shot;
        if (shot.getWidth() == wide && shot.getHeight() == tall) return shot;
        float scale = Math.max((float) wide / shot.getWidth(), (float) tall / shot.getHeight());
        int scaledWide = Math.max(1, Math.round(shot.getWidth() * scale));
        int scaledTall = Math.max(1, Math.round(shot.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(shot, scaledWide, scaledTall, true);
        if (scaled != shot) shot.recycle();
        int x = Math.max(0, (scaled.getWidth() - wide) / 2);
        int y = Math.max(0, (scaled.getHeight() - tall) / 2);
        Bitmap cropped = Bitmap.createBitmap(scaled, x, y,
                Math.min(wide, scaled.getWidth()), Math.min(tall, scaled.getHeight()));
        if (cropped != scaled) scaled.recycle();
        return cropped;
    }

    /** Writes the picture beside the app's own files; lossless, so the swap to the film changes nothing. */
    private static void keep(Context context, File film, Bitmap shot) {
        File kept = file(context, film);
        if (kept == null) return;
        FileOutputStream out = null;
        try {
            File parent = kept.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            out = new FileOutputStream(kept);
            // JPEG at the top of its range: a full-screen PNG takes seconds to write on this TV, and the picture
            // only has to survive one hand-over, where this quality is indistinguishable from the frame.
            shot.compress(Bitmap.CompressFormat.JPEG, 96, out);
            // A PNG kept by an older build is dead weight now that the picture is a JPEG.
            File stale = new File(kept.getParentFile(), "still-" + film.getName() + ".png");
            if (stale.isFile()) stale.delete();
            // This picture is now the one this build would work out, so it counts as kept from here on.
            context.getSharedPreferences("wallpaper_spot", Context.MODE_PRIVATE).edit()
                    .putInt(VERSION_KEY, VERSION).apply();
        } catch (Exception | OutOfMemoryError ignored) {
            // a picture that cannot be kept only means the film is read for it again next time
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                    // nothing to flush
                }
            }
        }
    }
}
