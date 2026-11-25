package com.gathin.tvhome;

import android.media.MediaMetadataRetriever;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class WallpaperRotation {
    /** Playback orders persisted in config.json (wallpaper_order). */
    static final String ORDER_SEQUENTIAL = "sequential";
    static final String ORDER_SHUFFLE = "shuffle";

    static boolean validOrder(String order) {
        return ORDER_SEQUENTIAL.equals(order) || ORDER_SHUFFLE.equals(order);
    }

    /** A picture small enough to decode into the launcher's own memory on a TV. */
    private static final long IMAGE_LIMIT = 10L * 1024 * 1024;
    /** A film is streamed by the media player, not decoded by us, so it may be far bigger than a picture. */
    private static final long FILM_LIMIT = 512L * 1024 * 1024;

    static List<File> images(File directory) {
        return media(directory, false);
    }

    /** Pictures and films together: what the on-TV wallpaper picker may put on Home. */
    static List<File> media(File directory) {
        return media(directory, true);
    }

    private static List<File> media(File directory, boolean films) {
        List<File> result = new ArrayList<>();
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return result;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName().toLowerCase(Locale.ROOT);
            boolean film = isFilm(name);
            if (!isPicture(name) && !(films && film)) continue;
            long limit = film ? FILM_LIMIT : IMAGE_LIMIT;
            if (entry.isFile() && entry.length() > 0 && entry.length() <= limit) {
                result.add(entry);
                if (result.size() == 100) break;
            }
        }
        return result;
    }

    static boolean isPicture(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp");
    }

    /** A looping wallpaper film: the formats the TV's own media player decodes in hardware. */
    static boolean isFilm(String name) {
        return name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".webm");
    }

    /**
     * The biggest film the TV loops in hardware and still stays cheap to run. Its H.264 decoder reports 135 fps
     * at 1080p (media_codecs_performance.xml) and a budget of 972000 macroblocks per second, so 1080p60 fits
     * with room to spare; 4K only reaches 30 fps, which is why a 4K60 film was refused outright.
     */
    static final int FILM_MAX_WIDTH = 1920;
    static final int FILM_MAX_HEIGHT = 1088;
    static final int FILM_MAX_FPS = 60;

    /** False for a film the TV would have to fight to decode; unreadable shapes count as light and get to try. */
    static boolean lightFilm(File file) {
        int[] shape = filmShape(file);
        return shape[0] <= FILM_MAX_WIDTH && shape[1] <= FILM_MAX_HEIGHT && shape[2] <= FILM_MAX_FPS;
    }

    /** {width, height, fps} of the film, zeros when the platform cannot say. */
    static int[] filmShape(File file) {
        int[] shape = new int[]{0, 0, 0};
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            shape[0] = metaValue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            shape[1] = metaValue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            shape[2] = metaValue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));
        } catch (Exception | OutOfMemoryError ignored) {
            // zeros: the player gets to try
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // the platform frees it with the process
            }
        }
        return shape;
    }

    private static int metaValue(String value) {
        if (value == null) return 0;
        try {
            return (int) Math.round(Float.parseFloat(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static boolean film(File file) {
        return file != null && isFilm(file.getName().toLowerCase(Locale.ROOT));
    }

    static File find(List<File> images, String name) {
        for (File image : images) if (image.getName().equals(name)) return image;
        return null;
    }

    /** The on-TV choice (Launcher settings, Wallpaper) that turns the wallpaper off. */
    static final String CHOICE_NONE = "none";

    /**
     * What an on-TV wallpaper choice puts on Home in place of the theme's rotation: null when the theme decides
     * (no choice, or a picture that is no longer there), an empty list for {@link #CHOICE_NONE}, or the one picture
     * chosen from the theme's collection or its single {@code single} wallpaper.
     */
    static List<File> chosen(List<File> collection, File single, String choice) {
        if (choice == null) return null;
        if (CHOICE_NONE.equals(choice)) return Collections.emptyList();
        File picked = find(collection, choice);
        if (picked == null && single != null && choice.equals(single.getName())) picked = single;
        return picked == null ? null : Collections.singletonList(picked);
    }

    static File pick(List<File> images, String previous, Random random) {
        List<File> candidates = new ArrayList<>(images);
        if (candidates.size() > 1) candidates.removeIf(image -> image.getName().equals(previous));
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * The next picture in the sorted collection, wrapping to the first at the end. The list from
     * {@link #images(File)} is sorted by filename, so this is a stable order across restarts. A
     * previous name that is no longer in the collection starts again at the first picture.
     */
    static File next(List<File> images, String previous) {
        if (images.isEmpty()) return null;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(previous)) {
                return images.get((i + 1) % images.size());
            }
        }
        return images.get(0);
    }

    /** Picks the next picture for {@code order}; an unknown order keeps the historical shuffle. */
    static File pick(List<File> images, String previous, String order, Random random) {
        return ORDER_SEQUENTIAL.equals(order) ? next(images, previous) : pick(images, previous, random);
    }

    static boolean due(long previous, long now, long interval) {
        // The minute tick is a Handler postDelayed, so it drifts by tens of milliseconds and often lands a few
        // hundred short of the interval. Compared exactly, that pass is skipped and the picture waits a whole
        // extra interval - a one-minute collection then changes every two. Two seconds of slack absorbs the
        // drift without letting a genuinely early call advance the picture.
        long slack = Math.min(2_000L, interval / 10);
        return interval > 0 && (previous == 0 || now < previous || now - previous >= interval - slack);
    }
}
