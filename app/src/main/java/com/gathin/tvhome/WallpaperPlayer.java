package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final int backgroundColor;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The film behind Home, decoded by hand so that its loop has no gap; null while a picture is shown. */
    private FilmLoop film;

    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    /** When this player was built: a Home that has just started has not drawn yet, which is not a loss. */
    private final long builtAt = android.os.SystemClock.uptimeMillis();
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty() && WallpaperRotation.film(files.get(0))) {
            // A film loops on its own: it owns the screen, so the rotation stays out of the way.
            showFilm(files.get(0));
        } else if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            if (WallpaperRotation.film(first)) {
                showFilm(first);
                return;
            }
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    void start() {
        active = true;
        play();
        update();
    }

    /** True while a picture is on screen; Home rebuilds when it has none and the launcher has a stored copy. */
    /** How long ago this player was built, in milliseconds. */
    long ageMs() {
        return android.os.SystemClock.uptimeMillis() - builtAt;
    }

    boolean showing() {
        return current != null || film != null;
    }

    void stop() {
        // Home pauses once, but the screen going off and a panel coming up can pause it twice in a row, and a
        // second pause must not decode another frame of the film and write another picture.
        if (!active) return;
        active = false;
        // Home is going behind something: the kept picture is brought up to the frame on screen first, so the way
        // back opens on that picture instead of the film's opening frame.
        if (film != null) film.leave();
        else pause();
    }

    /**
     * Home is going behind something else: the frame on screen is copied as the way back while the window is
     * still here. A window that leaves with nothing behind it is snapshotted black, and the way back opens on
     * that black frame before the rebuilt texture has anything to draw.
     */
    void holdPicture() {
        if (film != null) film.leave();
    }

    /**
     * The screen went off: Home is not going anywhere and will come back on the same frame, so there is nothing
     * to bring the kept picture up to. Leaving that picture alone skips a whole frame decode and a picture write
     * every time the television is switched off.
     */
    void sleep() {
        active = false;
        pause();
    }


    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        // The film's decoder is left asleep rather than torn down: rebuilding it costs a black screen.
        if (film != null) {
            pause();
            return;
        }
        if (current == null) return;
        image.setImageDrawable(null);
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
        if (film != null) {
            film.release();
            film = null;
        }
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        if (film != null) return;   // the film loops on its own; there is nothing to rotate
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap) {
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    /**
     * A film as the wallpaper: decoded by hand into two layers, the second one parked on the first frame, so
     * the loop has no gap. The film keeps its own resolution and frame rate; a film the decoder cannot carry
     * falls back to its first frame.
     */
    private void showFilm(File file) {
        int[] shape = WallpaperRotation.filmShape(file);
        if (shape[0] > WallpaperRotation.FILM_MAX_WIDTH || shape[1] > WallpaperRotation.FILM_MAX_HEIGHT
                || shape[2] > WallpaperRotation.FILM_MAX_FPS) {
            Log.w("TvHome.Wallpaper", "Film is " + shape[0] + "x" + shape[1] + "@" + shape[2] + " fps, over "
                    + WallpaperRotation.FILM_MAX_WIDTH + "x" + WallpaperRotation.FILM_MAX_HEIGHT + "@"
                    + WallpaperRotation.FILM_MAX_FPS + ": showing its first frame");
            showStill(file);
            return;
        }
        if (film != null) {
            film.release();
            film = null;
        }
        // A picture the film takes over from goes here: under the video layer it would sit for as long as the film
        // runs, holding a full screen of pixels nobody can see.
        if (current != null) {
            Bitmap gone = current;
            current = null;
            image.setImageDrawable(null);
            image.postDelayed(gone::recycle, 500);
        }
        Log.d("TvHome.Wallpaper", "film wallpaper: " + file.getName());
        // The opening frame is taken care of inside the loop: read back from the kept picture when there is one,
        // worked out off the main thread when there is not, so this screen never waits for a film decode.
        // Frames kept for films that are no longer the wallpaper go with it, in the background.
        FilmStill.pruneLater(root.getContext(), file);
        film = new FilmLoop(root, file);
        displayed = file.getName();
        showCaption(false);
        film.play();
    }

    /**
     * A freshly picked wallpaper while Home is already up: the layer changes here instead of Home being rebuilt,
     * which replays the entrance and leaves the screen blank for seconds. The old decoder and the old picture go
     * first, in that order, so nothing is drawn from a file that is about to be replaced.
     */
    void replace(File file) {
        if (closed || file == null || !file.isFile()) return;
        files.clear();
        files.add(file);
        if (film != null) {
            film.release();
            film = null;
        }
        Bitmap old = current;
        if (old != null) {
            image.setImageDrawable(null);
            current = null;
            image.postDelayed(old::recycle, 500);
        }
        if (WallpaperRotation.film(file)) {
            showFilm(file);
            return;
        }
        // No film is the wallpaper any more, and the loop above has already let go of its views: the picture kept
        // from it is a full screen of pixels that nothing draws, so it goes here rather than waiting for the process.
        FilmLoop.dropHeld();
        if (image.getParent() == null) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        Bitmap bitmap = decode(file);
        if (bitmap != null) {
            show(bitmap);
            displayed = file.getName();
            showCaption(false);
        } else {
            displayed = "";
            root.setBackgroundColor(backgroundColor);
        }
    }

    /** The film's first frame as a picture, for a TV that cannot loop the film itself. */
    private void showStill(File file) {
        // Nothing is playing a film from the kept frames now, so none of them is needed.
        FilmStill.pruneLater(root.getContext(), null);
        Bitmap frame = filmFrame(file);
        if (frame == null) {
            displayed = "";
            root.setBackgroundColor(backgroundColor);
            return;
        }
        if (image.getParent() == null) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        show(frame);
        displayed = file.getName();
        showCaption(false);
    }

    /** One frame of the film, scaled and cropped to cover the screen, as a hardware bitmap. */
    private Bitmap filmFrame(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            retriever.setDataSource(file.getPath());
            frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception | OutOfMemoryError failure) {
            frame = null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // the platform frees it with the process
            }
        }
        if (frame == null) return null;
        try {
            int sourceWidth = frame.getWidth(), sourceHeight = frame.getHeight();
            if (sourceWidth <= 0 || sourceHeight <= 0) return null;
            float scale = Math.max((float) width / sourceWidth, (float) height / sourceHeight);
            Bitmap scaled = Bitmap.createScaledBitmap(frame,
                    Math.max(1, Math.round(sourceWidth * scale)),
                    Math.max(1, Math.round(sourceHeight * scale)), true);
            frame.recycle();
            int x = Math.max(0, (scaled.getWidth() - width) / 2);
            int y = Math.max(0, (scaled.getHeight() - height) / 2);
            Bitmap cropped = Bitmap.createBitmap(scaled, x, y,
                    Math.min(width, scaled.getWidth()), Math.min(height, scaled.getHeight()));
            if (cropped != scaled) scaled.recycle();
            Bitmap gpu = cropped.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return cropped;
            cropped.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }


    /** Home is in front: the film runs, or picks up where it was left. */
    private void play() {
        Log.d("TvHome.Wallpaper", "home start, film=" + (film != null));
        if (film != null) film.play();
    }

    /** Home is behind something else: both decoders sleep instead of working for nobody. */
    private void pause() {
        Log.d("TvHome.Wallpaper", "film asleep, film=" + (film != null));
        if (film != null) film.pause();
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
           options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
