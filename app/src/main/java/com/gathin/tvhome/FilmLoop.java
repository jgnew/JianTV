package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * A film as the wallpaper, decoded by hand.
 *
 * The reason the picture used to stand still at the loop was that every way of looping restarted a decoder: two
 * layers swapped, or a decoder was seeked back to the film's start, and the screen sat on the last frame while
 * that happened. A single decoder cannot be configured onto the same layer twice, so the loop is done in the
 * stream instead: while the tail of the film is playing, the head of the next pass is already read into memory
 * by a second extractor, and the moment the current one runs out those packets are fed straight into the same
 * decoder. The decoder never stops, never flushes and never rebuilds, so there is no moment at which the layer
 * has nothing new to show.
 *
 * One layer, one decoder: the film keeps its own resolution and frame rate, nothing is transcoded, no frame is
 * dropped, and the compositor has a single video layer to mix over the window.
 */
final class FilmLoop {
    private static final String TAG = "TvHome.Wallpaper";
    /** Frames taken from the film's head ahead of time; four frames of slack is a whole hand-over's worth. */
    private static final int HEAD_FRAMES = 4;
    private static final int HEAD_BYTES = 1 << 20;
    /**
     * How long the film waits before it copies the screen. The window is still being drawn while Home leaves it,
     * and reading the screen costs the compositor a whole frame: taken in that same turn it eats frames of the
     * leaving animation, taken a moment later it costs nothing anybody can see.
     */
    private static final long HOLD_MS = 220;

    private Runnable pendingHold;
    /** When the window was last left: the copy and the layer's going are timed against it. */
    private volatile long leftAt;
    /**
     * The last picture this film was left at, kept for the whole process. Home is rebuilt on returning from an
     * app, and the new window wants something to stand in: the film's opening frame is a different moment from
     * the one it carries on at, so the way back would show the opening and then jump; this picture is that moment.
     */
    private static Bitmap heldStill;
    /** Which film that picture belongs to: a picture of another wallpaper is worse than none at all. */
    private static String heldStillFor;

    private final FrameLayout root;
    private final File file;
    private final TextureView view;
    /** The film's first frame as a small picture, shown over the black while the decoder is being built. The
     *  video layer sits below the window, so an ordinary view covers it; once frames are coming out it goes
     *  away and costs nothing. */
    private ImageView poster;
    private volatile boolean covered = true;
    /** The frame on screen has been copied for this departure: the way back is set, nothing to copy again. */
    private volatile boolean held;
    /** The window size screenSize() last worked out: the fallback decode below needs it without another call. */
    private int paneWide = 1;
    private int paneTall = 1;

    private volatile Surface surface;
    private volatile boolean wanted;
    private volatile boolean closed;

    private Thread thread;
    private Thread preparer;

    /** Read by the loop thread and cleared by stop() on the main thread: volatile so a let-go of it is seen. */
    private volatile MediaCodec codec;
    /** The layer the running decoder was configured with: a layer that comes back is pointed at it in one call. */
    private Surface codecSurface;
    /** Cleared by stop() on the main thread too: volatile keeps the loop thread from reading a released one. */
    private volatile MediaExtractor live;
    /** A reader that has run through the film and is free for the next head: reusing it skips parsing the
     *  film's index again, which is the most expensive part of opening the head, every ten seconds and every
     *  time Home comes back from behind another app. */
    private MediaExtractor idle;
    /** The track the first parse settled on; every reused reader opens the same one. */
    private MediaFormat outline;
    /** The head of the coming pass, handed over as soon as the current one runs out; null while being built. */
    private volatile Head next;
    private volatile boolean building;
    private volatile Head pending;
    private int pendingAt;
    /** An input slot taken from the decoder but not filled yet: a slot taken and dropped is gone for good, and
     *  a few of those stall the decoder until the picture stops. */
    private int spareIndex = -1;
    /** Timestamps fed to the decoder, one frame apart and never going backwards, so a loop is invisible to it. */
    private long feedPts;
    private long frameUs;
    private long due;
    private boolean running;
    private long lastFrameNs;
    /** One scratch buffer for reading the film's head: a fresh direct buffer for every pass would never be
     *  reclaimed, and only one head is ever built at a time. */
    private static ByteBuffer scratch;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    /** The moment the kept picture stands for: the film carries on from here instead of from its opening frame. */
    private final long startUs;
    /** Only the very first decoder starts from the kept moment; later ones carry on from where the film was. */
    private boolean firstHead = true;
    private long durationUs;
    /** Frames put out since the film was opened, which is where the picture is taken from when Home is left. */
    private long shownFrames;

    FilmLoop(FrameLayout root, File file) {
        this.root = root;
        this.file = file;
        long at = FilmStill.spot(root.getContext(), file);
        startUs = at < 0 ? 0 : at;
        view = new TextureView(root.getContext());
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Drawn by the window like any other view. A SurfaceView is a layer of its own that the window tears
        // down on the way out and rebuilds on the way back, and that fresh, empty layer is what the compositor
        // clears to black before the decoder's first frame lands in it. The texture lives inside the window's
        // own buffer, beside the kept picture, so the way back shows the picture straight away.
        view.setOpaque(true);
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                synchronized (FilmLoop.this) {
                    surface = new Surface(st);
                    FilmLoop.this.notifyAll();
                }
                Log.d(TAG, "layer has its surface");
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                synchronized (FilmLoop.this) {
                    Surface gone = surface;
                    surface = null;
                    if (gone != null) gone.release();
                    FilmLoop.this.notifyAll();
                }
                // Handing the texture back lets the window build its next one; the kept picture holds the
                // screen while that happens.
                Log.d(TAG, "layer gone at " + (leftAt > 0 ? (SystemClock.uptimeMillis() - leftAt) : 0)
                        + " ms after leaving");
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        root.addView(view, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The window's own background has to stay clear for the video layer to show through; the poster is a
        // separate view above it instead, so the black gap while a decoder is rebuilt shows the first frame.
        root.setBackground(null);
        int[] pane = screenSize();
        Bitmap still = pullHeldStill(file, pane[0], pane[1]);
        if (still == null) still = firstFrame();
        if (still != null) showPoster(still);
        else buildPosterLater();
        Log.d(TAG, "film loop built: " + file.getName());
        thread = new Thread(new Runnable() {
            @Override public void run() {
                loop();
            }
        }, "film decoder");
        thread.setDaemon(true);
        thread.start();
    }

    void play() {
        synchronized (this) {
            wanted = true;
            held = false;
            notifyAll();
        }
    }

    void pause() {
        // The decoder simply stops being fed, so the layer keeps the frame it was on and Home is left looking at
        // the moment it was actually at. Covering here would be wrong: the picture is the film's first frame, so
        // stepping out to an app would throw the wallpaper back to the start while Home is still on screen.
        synchronized (this) {
            wanted = false;
            notifyAll();
        }
    }

    /**
     * Home is on its way out: the picture kept beside the film is brought up to the moment on screen, so the way
     * back reads that frame rather than the film's opening one. The layer goes on showing its last frame while
     * this runs, and the new picture only replaces the kept one once it is ready.
     */
    void leave() {
        if (held) {
            // A second pause in a row (the screen going off while Home is already behind something): the picture
            // is up and the film is stopped, so there is nothing to copy and nothing to decode.
            pause();
            return;
        }
        final long at = positionUs();
        pause();
        leftAt = SystemClock.uptimeMillis();
        final Surface layer = surface;
        screenSize();
        final int wide = paneWide;
        final int tall = paneTall;
        // A copy is already waiting for this departure (Home pauses twice in a row): the film is stopped, so
        // the moment it stands for has not moved, and asking again would only push the picture further out.
        if (pendingHold != null) return;
        pendingHold = new Runnable() {
            @Override public void run() {
                pendingHold = null;
                // Home taken away and brought straight back inside this wait: there is nothing to stand in for,
                // and the copy would only freeze the frame the film has just gone back to.
                if (closed || wanted) return;
                Surface now = surface;
                if (layer == null || now == null || now != layer) {
                    // The layer was torn down and built again inside this wait, so it is empty: a copy of it is a
                    // black picture, which is the frame this whole design exists to keep off the screen.
                    readAgain(at, wide, tall);
                    return;
                }
                Bitmap shot = null;
                try {
                    shot = view.getBitmap();
                } catch (Throwable refused) {
                    Log.d(TAG, "screen copy refused: " + refused);
                }
                if (shot != null) {
                    showHeld(shot, at);
                    return;
                }
                // The texture had nothing to give: read the frame out of the film instead, off this thread.
                readAgain(at, wide, tall);
            }
        };
        // The window is drawn for a while after Home leaves it, so the screen is read once that is over rather
        // than in the same turn: a copy taken there costs the animation the frames it is shown in. The picture is
        // still up long before the window goes away, which is all the way back needs.
        root.postDelayed(pendingHold, HOLD_MS);
    }

    /** Puts a copied frame up as the kept picture, above the film. */
    private void showHeld(Bitmap shot, long at) {
        held = true;
        covered = true;
        Bitmap previous = opening;
        opening = shot;
        heldStill = shot;
        heldStillFor = file.getAbsolutePath();
        if (poster != null) {
            poster.animate().cancel();
            poster.setAlpha(1f);
            poster.setImageBitmap(shot);
            poster.setVisibility(View.VISIBLE);
        }
        applyWindowStill();
        if (previous != null && previous != shot) releaseLater(previous);
        Log.d(TAG, "still held at " + (at / 1000) + " ms");
    }

    /** Lets go of the picture an earlier departure left, once the frame that showed it has gone by. */
    private void releaseLater(final Bitmap gone) {
        root.postDelayed(new Runnable() {
            @Override public void run() {
                if (gone.isRecycled()) return;
                // The picture kept for the next window is not let go of here, whatever replaced it.
                if (gone == heldStill) return;
                try {
                    gone.recycle();
                } catch (Throwable stillDrawn) {
                    // held elsewhere: the collector is the safer owner
                }
            }
        }, 300);
    }

    /** The frame read out of the film itself: a second decoder, for the moment the texture has nothing to copy. */
    private void readAgain(final long at, final int wide, final int tall) {
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap shot = FilmStill.make(root.getContext(), file, at, wide, tall);
                if (shot == null) return;
                root.post(new Runnable() {
                    @Override public void run() {
                        if (closed || wanted) {
                            // Home is already back and the film is drawing again: this frame arrived late, and
                            // putting it over the film would freeze the screen for a moment.
                            shot.recycle();
                            return;
                        }
                        showHeld(shot, at);
                    }
                });
            }
        }, "still holder");
        worker.setDaemon(true);
        worker.start();
    }

    /** Where the picture on screen is in the film: frames put out since the film was opened, wrapped at its end. */
    private long positionUs() {
        if (frameUs <= 0) return startUs;
        long pos = startUs + Math.max(0, shownFrames) * frameUs;
        if (durationUs > 0) pos %= durationUs;
        return Math.max(0, pos);
    }

    void release() {
        synchronized (this) {
            closed = true;
            wanted = false;
            notifyAll();
        }
        stop();
        if (pendingHold != null) {
            root.removeCallbacks(pendingHold);
            pendingHold = null;
        }
        clearWindowStill();
        if (poster != null) {
            root.removeView(poster);
            poster.setImageDrawable(null);
            poster = null;
        }
        if (view != null) root.removeView(view);
        Log.d(TAG, "film loop released at " + (leftAt > 0 ? (SystemClock.uptimeMillis() - leftAt) : 0)
                + " ms after leaving");
    }

    private void loop() {
        int failures = 0;
        while (!closed) {
            Surface layer = surface;
            if (layer == null) {
                // The window has gone. The decoder is kept instead of torn down: it is already configured and its
                // next layer is one call away, where building a decoder again costs a quarter of a second of the
                // kept picture instead of the film.
                idle(1_000);
                continue;
            }
            if (codec != null && codecSurface != layer) {
                if (!moveTo(layer)) continue;
            }
            if (!wanted) {
                // Behind the settings or the app drawer: nothing to decode for nobody. Waiting here rather than
                // waking every forty milliseconds keeps this thread out of the wake-up count for hours at a time;
                // the wake that ends it comes from play().
                idle(1_000);
                continue;
            }
            if (codec == null) {
                if (!start()) {
                    // A film this decoder cannot carry would otherwise be parsed again every thirty milliseconds
                    // for as long as Home is up. Backing off keeps the retry cheap and still lets it recover.
                    failures++;
                    idle(failures < 3 ? (failures == 1 ? 200 : 600) : 5_000);
                    continue;
                }
                failures = 0;
            }
            if (!running) {
                // Coming back after a pause: the next frame goes out at once instead of catching up on the
                // frames that were not shown meanwhile.
                due = 0;
                lastFrameNs = 0;
                running = true;
            }
            try {
                step();
            } catch (RuntimeException tornDown) {
                // Whatever this decoder throws would otherwise end the loop thread and leave the film stopped
                // for good. Let go of it and let the next turn build one: the kept picture stands in meanwhile.
                Log.w(TAG, "a frame failed on the decoder, rebuilding it", tornDown);
                failures++;
                stop();
                idle(failures < 3 ? 200 : 1_000);
            }
        }
        stop();
    }

    /**
     * Sleeps until something changes, and no longer than this in case a wake-up is missed.
     *
     * The thread used to wake twenty-five times a second all the while Home sat behind something: cheap per
     * wake-up, but over an evening it is hundreds of thousands of them, and each one keeps the processor out of
     * the deeper idle states the television could otherwise reach.
     */
    private void idle(long ms) {
        synchronized (this) {
            if (closed || (wanted && surface != null)) return;
            try {
                wait(ms);
            } catch (InterruptedException ignored) {
                // a release is noticed on the next turn
            }
        }
    }

    /**
     * Points the decoder that is already running at the layer that has just come back, so the film carries on
     * where it was instead of being rebuilt. A decoder that cannot move is dropped, and the next turn builds one.
     */
    private boolean moveTo(Surface layer) {
        MediaCodec decoder = codec;
        if (decoder == null) return true;
        try {
            decoder.setOutputSurface(layer);
            codecSurface = layer;
            Log.d(TAG, "decoder moved to the layer that came back");
            return true;
        } catch (Exception failure) {
            Log.w(TAG, "this decoder cannot move to the layer that came back", failure);
            stop();
            return false;
        }
    }

    /** Builds the decoder and the first head, then feeds it: the first frame is out as soon as one is decoded. */
    private boolean start() {
        long from = firstHead ? startUs : positionUs();
        firstHead = false;
        Head head = buildHead(from);
        if (head == null) {
            Log.w(TAG, "this film cannot be decoded by hand: " + file.getName());
            return false;
        }
        try {
            MediaFormat format = head.format;
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, surface, null, 0);
            codec.start();
            codecSurface = surface;
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                int fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                if (fps > 0 && fps <= 120) frameUs = 1_000_000L / fps;
            }
            if (frameUs <= 0) {
                frameUs = head.count > 1 ? Math.max(1L, head.pts[1] - head.pts[0]) : 16_667L;
            }
            live = head.extractor;
            head.extractor = null;
            pending = head;
            pendingAt = 0;
            feedPts = 0;
            spareIndex = -1;
            Log.d(TAG, "film decoded here: " + file.getName());
            requestNext();
            return true;
        } catch (Exception failure) {
            Log.w(TAG, "this film cannot be decoded by hand", failure);
            // The extractor keeps a file handle and the film's index alive: dropping it here without releasing
            // it would leak one handle every time a film fails to open.
            if (head != null && head.extractor != null) {
                try {
                    head.extractor.release();
                } catch (Exception ignored) {
                    // already gone
                }
                head.extractor = null;
            }
            stop();
            return false;
        }
    }

    /** One turn of the loop: keep the decoder fed, take one frame out of it, keep the pace. */
    private void step() {
        // Taken once for the whole turn: the main thread can let go of the decoder between here and the calls
        // below, and a field that turns null half-way through is a crash rather than a skipped frame.
        MediaCodec c = codec;
        if (c == null) return;
        // Fill the decoder's inputs rather than drip one packet per frame: it needs a few frames in hand before
        // it can hand frames out, and a starved decoder stops showing anything at all.
        for (int i = 0; i < 8 && feedOne(); i++) { }
        // Wait for the next frame instead of polling on a fixed short timeout: a three-millisecond poll wakes the
        // thread hundreds of times a second and burns more power than the decoding itself. Waiting until the
        // frame is due means one wake-up per frame, and the frame still comes out on time.
        long at = System.nanoTime();
        long timeout = 4_000L;
        if (due > 0) {
            timeout = (due - at) / 1000L;
            if (timeout < 500L) timeout = 500L;
            if (timeout > 17_000L) timeout = 17_000L;
        }
        int index;
        try {
            index = c.dequeueOutputBuffer(info, (int) timeout);
        } catch (IllegalStateException ignored) {
            return;
        }
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            resize();
            return;
        }
        if (index < 0) {
            // Nothing to show yet. If the frame is already overdue, wait a moment instead of spinning on it.
            if (due > 0 && System.nanoTime() - due > 5_000_000L) SystemClock.sleep(2);
            return;
        }
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            c.releaseOutputBuffer(index, false);
            return;
        }
        // Hold this frame until the moment it is due, then put it out. The dequeue above already waited for it,
        // so this normally costs nothing; it only matters when the decoder had the frame ready ahead of time.
        // Waiting here and not after the frame is what keeps this to one wake-up per frame: waiting afterwards
        // would only make the next dequeue return at once.
        long shown = System.nanoTime();
        if (due == 0 || shown - due > frameUs * 2000L) due = shown;
        long wait = due - shown;
        if (wait > 2_000_000L) SystemClock.sleep(wait / 1_000_000L - 1L);
        while (due - System.nanoTime() > 1_000_000L && wanted && !closed) SystemClock.sleep(1);
        try {
            c.releaseOutputBuffer(index, true);
        } catch (IllegalStateException gone) {
            // The layer went away under the decoder between the frame and this call; the next turn points the
            // decoder at the layer that comes back.
            return;
        }
        shownFrames++;
        if (covered) uncover();
        due += frameUs * 1000L;

        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            long gap = now - lastFrameNs;
            if (gap > 120_000_000L) {
                // Only a real stall is worth a line: the first frames after Home comes back are spread out by the
                // poster's fade and cost nothing, and writing one line for each of them is noise in the log.
                Log.d(TAG, "frame held up for " + (gap / 1_000_000L) + " ms");
            }
        }
        lastFrameNs = now;
    }

    /**
     * Feeds one packet and says whether another may follow. While the current pass is still playing these come
     * from the file; when it runs out, the next pass's head is fed instead, so the decoder sees one continuous
     * stream and the picture never runs dry. After the head the same extractor carries on reading, and a fresh
     * head is prepared in the background.
     *
     * The timestamp given to the decoder is the film's own pace continued across the loop, not the packet's: a
     * timestamp that jumps back to the start can make a hardware decoder stop handing frames out.
     */
    private boolean feedOne() {
        // Same reason as step(): one read of the field for the whole call, so a decoder released meanwhile is a
        // "no packet this turn" rather than a null dereference.
        MediaCodec c = codec;
        if (c == null) return false;
        if (spareIndex < 0) {
            try {
                spareIndex = c.dequeueInputBuffer(0);
            } catch (IllegalStateException ignored) {
                return false;
            }
            if (spareIndex < 0) return false;
        }
        ByteBuffer buffer = c.getInputBuffer(spareIndex);
        if (buffer == null) return false;
        Head head = pending;
        if (head != null) {
            if (pendingAt >= head.count) {
                finishHead();
                return true;
            }
            byte[] data = head.data[pendingAt];
            pendingAt++;
            buffer.clear();
            buffer.put(data, 0, data.length);
            return queue(c, data.length);
        }
        if (live == null) {
            openHead();
            return true;
        }
        buffer.clear();
        int size;
        try {
            size = live.readSampleData(buffer, 0);
        } catch (Exception ignored) {
            size = -1;
        }
        if (size < 0) {
            // The film has run out. The head of the next pass goes in straight away, from memory, so that the
            // decoder still has work to do and the layer is never left without a frame to show.
            openHead();
            return true;
        }
        try {
            live.advance();
        } catch (Exception ignored) {
            // the next read will report the end
        }
        return queue(c, size);
    }

    private boolean queue(MediaCodec c, int size) {
        try {
            c.queueInputBuffer(spareIndex, 0, size, feedPts, 0);
        } catch (IllegalStateException ignored) {
            return false;
        }
        spareIndex = -1;
        feedPts += frameUs;
        return true;
    }

    /** Hands the next pass's head to the feeder, taking the one prepared in the background when it is ready. */
    private void openHead() {
        Head head = next;
        if (head == null) {
            if (building) return;
            head = buildHead(0);
            if (head == null) return;
        }
        next = null;
        building = false;
        // The head that was mid-feed may still own a reader; the one that has just run dry goes back to the
        // spare slot instead of being closed, so the next head can reuse it rather than parse the film anew.
        Head carried = pending;
        MediaExtractor spent = live;
        pending = null;
        pendingAt = 0;
        live = null;
        if (carried != null && carried.extractor != null) {
            live = carried.extractor;
            carried.extractor = null;
        }
        keep(spent);
        pending = head;
        pendingAt = 0;
        requestNext();
    }

    /** Parks a reader for reuse, or closes it when there is already one waiting. */
    private void keep(MediaExtractor reader) {
        if (reader == null) return;
        if (idle == null && reader != live) {
            idle = reader;
            return;
        }
        try {
            reader.release();
        } catch (Exception ignored) {
            // already gone
        }
    }

    /** The head has been fed: its extractor is already reading at the frame just after it, so it becomes live. */
    private void finishHead() {
        Head head = pending;
        pending = null;
        pendingAt = 0;
        if (head == null) return;
        if (head.extractor != null) {
            if (live != null) keep(live);
            live = head.extractor;
            head.extractor = null;
        }
    }

    /** Reads the film's opening frames into memory, on its own thread, while the current pass plays. */
    private void requestNext() {
        if (closed || building || next != null) return;
        building = true;
        preparer = new Thread(new Runnable() {
            @Override public void run() {
                Head head = buildHead(0);
                if (head == null) {
                    building = false;
                    return;
                }
                if (closed) {
                    // The film was closed while this was being read: the result would sit here holding a file
                    // handle and its index, so it goes back instead of being kept as a cache nobody will use.
                    if (head.extractor != null) {
                        try {
                            head.extractor.release();
                        } catch (Exception ignored) {
                            // already gone
                        }
                        head.extractor = null;
                    }
                    building = false;
                    return;
                }
                next = head;
                building = false;
            }
        }, "film head");
        preparer.setDaemon(true);
        preparer.start();
    }

    /**
     * A few frames from the film's head, held as bytes plus their timestamps, and the extractor left reading at
     * the frame just after them. Feeding from memory is what makes the hand-over cost nothing.
     */
    private static final class Head {
        byte[][] data = new byte[HEAD_FRAMES][];
        long[] pts = new long[HEAD_FRAMES];
        int count;
        MediaExtractor extractor;
        MediaFormat format;
    }

    private Head buildHead(long from) {
        MediaExtractor source = idle;
        idle = null;
        boolean opened = source != null;
        if (source == null) source = new MediaExtractor();
        Head head = new Head();
        try {
            MediaFormat format;
            if (opened) {
                format = outline;
                if (format == null) throw new IllegalStateException("no video track");
            } else {
                source.setDataSource(file.getPath());
                format = null;
                int track = -1;
                for (int i = 0; i < source.getTrackCount(); i++) {
                    MediaFormat candidate = source.getTrackFormat(i);
                    String mime = candidate.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        track = i;
                        format = candidate;
                        break;
                    }
                }
                if (format == null) throw new IllegalStateException("no video track");
                source.selectTrack(track);
                outline = format;
            }
            if (durationUs <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) {
                long length = format.getLong(MediaFormat.KEY_DURATION);
                if (length > 0) durationUs = length;
            }
            head.format = format;
            // The first pass opens where the kept picture stands, so the film and the picture are one picture;
            // every pass after that opens the film's head, which is what the loop hands over from.
            source.seekTo(Math.max(0, from), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            if (scratch == null) scratch = ByteBuffer.allocateDirect(HEAD_BYTES);
            for (int i = 0; i < HEAD_FRAMES; i++) {
                scratch.clear();
                int size = source.readSampleData(scratch, 0);
                if (size <= 0) break;
                byte[] copy = new byte[size];
                scratch.position(0);
                scratch.limit(size);
                scratch.get(copy);
                head.data[head.count] = copy;
                head.pts[head.count] = source.getSampleTime();
                head.count++;
                source.advance();
            }
            head.extractor = source;
            return head;
        } catch (Exception failure) {
            Log.w(TAG, "cannot read this film's head", failure);
            try {
                source.release();
            } catch (Exception ignored) {
                // nothing selected
            }
            return null;
        }
    }

    /**
     * Lets go of the picture this film was last left at. Only for when no film is the wallpaper any more: while one
     * is, that picture is what the way back shows, and the caller must have released the loop first so nothing is
     * still drawing it.
     */
    static void dropHeld() {
        Bitmap kept = heldStill;
        heldStill = null;
        heldStillFor = null;
        if (kept == null || kept.isRecycled()) return;
        try {
            kept.recycle();
        } catch (Throwable stillDrawn) {
            // held elsewhere: the collector is the safer owner
        }
    }

    /**
     * The picture this film was last left at, when it belongs to the same film. A picture belonging to another
     * wallpaper is worse than none at all, and that one is let go of here so it does not stay on the heap.
     */
    private static Bitmap pullHeldStill(File film, int wide, int tall) {
        Bitmap kept = heldStill;
        if (kept == null) return null;
        String path = film.getAbsolutePath();
        if (heldStillFor != null && heldStillFor.equals(path) && !kept.isRecycled()
                && kept.getWidth() == wide && kept.getHeight() == tall) return kept;
        heldStill = null;
        heldStillFor = null;
        if (!kept.isRecycled()) {
            try {
                kept.recycle();
            } catch (Throwable stillDrawn) {
                // held elsewhere: the collector is the safer owner
            }
        }
        return null;
    }

    /** The film's opening frame, kept beside the app's own files the first time this film became the wallpaper. */
    private Bitmap firstFrame() {
        long at = SystemClock.uptimeMillis();
        int[] screen = screenSize();
        // Home is sometimes rebuilt while it sat behind another app, and this runs on the main thread before the
        // window is drawn: asking the system media reader for a frame here costs seconds on this platform, and
        // every one of them is a screen with no wallpaper on it. The frame was worked out once, when this film
        // became the wallpaper, so the usual rebuild is a file read.
        Bitmap kept = FilmStill.read(root.getContext(), file, screen[0], screen[1]);
        if (kept != null) {
            Log.d(TAG, "poster from the kept picture in " + (SystemClock.uptimeMillis() - at) + " ms");
            return kept;
        }
        // Nothing kept at this window size. An older, smaller picture still beats a blank window while the right
        // one is being made in the background; the main thread never reads the film itself.
        Bitmap stand = FilmStill.readAny(root.getContext(), file);
        if (stand == null) return null;
        Log.d(TAG, "poster from an older kept picture in " + (SystemClock.uptimeMillis() - at) + " ms");
        // The stand-in is a picture the film will be judged against until the right one arrives, so the right one
        // is worked out now rather than at the next return.
        buildPosterLater();
        return stand;
    }

    /** The opening frame has not been worked out yet: do it off the main thread, then put it up. */
    private void buildPosterLater() {
        final int[] screen = screenSize();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                // The settings page may already be working out this same frame; that one is waited for and read
                // back rather than decoded a second time.
                final Bitmap shot = FilmStill.ensure(root.getContext(), file, screen[0], screen[1]);
                if (shot == null) return;
                root.post(new Runnable() {
                    @Override public void run() {
                        if (!closed) showPoster(shot);
                    }
                });
            }
        }, "poster maker");
        worker.setDaemon(true);
        worker.start();
    }

    private Bitmap opening;

    /** Puts the opening frame above the video layer, hidden while the film is already being drawn. */
    private void showPoster(Bitmap still) {
        if (closed) return;
        // The picture this window was left at is already up: the opening frame is another moment of the film, and
        // swapping it in would show the opening and then jump back to the frame the film plays on from.
        if (held) return;
        opening = still;
        if (poster != null) {
            // A stand-in is already up: the right frame takes its place without disturbing its alpha or
            // visibility, so the swap is invisible even mid-fade.
            poster.setImageBitmap(still);
            if (covered) applyWindowStill();
            return;
        }
        poster = new ImageView(root.getContext());
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        poster.setImageBitmap(still);
        root.addView(poster, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        poster.setVisibility(covered ? View.VISIBLE : View.GONE);
        if (covered) applyWindowStill();
    }

    /**
     * The window's own background while the film is not drawing: the opening frame. Home is drawn again when it
     * comes back in front, and the window exists a moment before its first composition — with nothing behind it
     * that moment is black. Set while Home is away, cleared once the film takes over, so playback pays nothing.
     */
    private void applyWindowStill() {
        if (opening == null) return;
        Context context = root.getContext();
        if (!(context instanceof Activity)) return;
        try {
            ((Activity) context).getWindow().setBackgroundDrawable(
                    new BitmapDrawable(context.getResources(), opening));
        } catch (Exception ignored) {
            // the window is gone already
        }
    }

    private void clearWindowStill() {
        Context context = root.getContext();
        if (!(context instanceof Activity)) return;
        try {
            ((Activity) context).getWindow().setBackgroundDrawable(null);
        } catch (Exception ignored) {
            // the window is gone already
        }
    }

    /** The window's own size: a poster of exactly this size needs no scaling at the moment it is shown. */
    private int[] screenSize() {
        int wide = root.getWidth(), tall = root.getHeight();
        if (wide <= 0 || tall <= 0) {
            DisplayMetrics metrics = root.getContext().getResources().getDisplayMetrics();
            wide = metrics.widthPixels;
            tall = metrics.heightPixels;
        }
        paneWide = Math.max(1, wide);
        paneTall = Math.max(1, tall);
        return new int[]{paneWide, paneTall};
    }

    /** Shows the poster again: the layer has no picture, so the first frame stands in instead of black. */
    private void cover() {
        if (covered) return;
        covered = true;
        Log.d(TAG, "poster covers the gap");
        Runnable change = new Runnable() {
            @Override public void run() {
                if (poster == null) return;
                poster.animate().cancel();
                poster.setAlpha(1f);
                poster.setVisibility(View.VISIBLE);
                applyWindowStill();
            }
        };
        // Pausing happens on the main thread and must take effect before the window leaves, or the snapshot that
        // stands in for Home on the way back is taken without it; other callers come from the decoder's thread.
        if (Looper.myLooper() == Looper.getMainLooper()) change.run();
        else root.post(change);
    }

    /** Frames are coming out: the poster steps aside and stops being drawn at all. */
    private void uncover() {
        // Home has just been left: the poster stays up on purpose, or the window goes back to having nothing to
        // show and the snapshot taken on the way out would be black. A frame that is already on its way out still
        // lands underneath it, so nothing is lost.
        if (!covered || !wanted) return;
        covered = false;
        Log.d(TAG, "poster steps aside");
        // The frame has just been handed to the layer, but the display only shows it on the next composition.
        // Hiding the poster in the same turn would expose the layer for a frame that is not on screen yet. Once
        // it is up, the poster fades instead of snapping: the kept picture is a frame of the film, not the film
        // itself, and anything the two do not agree on is washed out over these 140 ms rather than read as a jump.
        root.postDelayed(new Runnable() {
            @Override public void run() {
                if (poster == null || covered) return;
                poster.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
                    @Override public void run() {
                        if (poster != null && !covered) {
                            poster.setVisibility(View.GONE);
                            poster.setAlpha(1f);
                            clearWindowStill();
                        }
                    }
                }).start();
            }
        }, 32);
    }

    private void resize() {
        try {
            MediaFormat format = codec.getOutputFormat();
            final int width = format.getInteger(MediaFormat.KEY_WIDTH);
            final int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (width > 0 && height > 0) {
                view.post(new Runnable() {
                    @Override public void run() {
                        // The texture is given the film's own size: every frame of a smaller film is not pushed
                        // through a scaling pass on an already busy GPU, and the decoder does not renegotiate its
                        // buffers while playing, which is felt as a stutter.
                        SurfaceTexture texture = view.getSurfaceTexture();
                        if (texture != null) texture.setDefaultBufferSize(width, height);
                    }
                });
            }
        } catch (Exception ignored) {
            // some decoders never say; the layer still fills the screen by its crop
        }
    }

    private void stop() {
        running = false;
        spareIndex = -1;
        feedPts = 0;
        // A head that was read ahead but never played is a cache, not garbage: it holds an extractor with a file
        // handle and the film's index, and dropping it without releasing those leaves them open for good.
        Head queued = next;
        next = null;
        building = false;
        if (queued != null && queued.extractor != null) {
            try {
                queued.extractor.release();
            } catch (Exception ignored) {
                // already gone
            }
            queued.extractor = null;
        }
        Head head = pending;
        pending = null;
        pendingAt = 0;
        if (head != null && head.extractor != null) {
            try {
                head.extractor.release();
            } catch (Exception ignored) {
                // already gone
            }
        }
        if (live != null) {
            try {
                live.release();
            } catch (Exception ignored) {
                // already gone
            }
            live = null;
        }
        MediaExtractor spare = idle;
        idle = null;
        if (spare != null) {
            try {
                spare.release();
            } catch (Exception ignored) {
                // already gone
            }
        }
        outline = null;
        MediaCodec decoder = codec;
        codec = null;
        codecSurface = null;
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Exception ignored) {
                // not started
            }
            try {
                decoder.release();
            } catch (Exception ignored) {
                // already gone
            }
        }
        // Whatever is on screen now stays as it is: the next decoder takes a moment to produce a frame, and
        // the first frame is a better thing to look at than the window's empty background.
        cover();
    }
}
