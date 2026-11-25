package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    /** Idle minutes before the clock screensaver; 0 is off. */
    private static final int[] IDLE_MINUTES = {0, 3, 8, 12};
    private final TextView[] idleChoices = new TextView[IDLE_MINUTES.length];
    private TextView wallpaperChoice;
    private TextView barButtonsChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private int idleClockMinutes;
    /** The top bar shape picked here: pill, icon or gear. */
    private String barStyle;
    private static final String[] BAR_STYLES = {Config.BAR_STYLE_PILL, Config.BAR_STYLE_ICON, Config.BAR_STYLE_GEAR};
    private final TextView[] barStyleChoices = new TextView[BAR_STYLES.length];
    /** The glow picked here: true follows the focused app colour, false leaves the wallpaper alone. */
    private boolean ambientGlow;
    private final TextView[] glowChoices = new TextView[2];
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        // The row shows transparency, the renderer takes opacity: read back the pick, and show the theme's own
        // band as its transparency when nothing was picked on the TV.
        int chosenTransparency = choices.getInt(Config.DOCK_OPACITY_KEY, -1);
        dockOpacity = chosenTransparency >= 0 ? chosenTransparency : 100 - config.dockOpacity;
        clockFormat = config.clockFormat;
        idleClockMinutes = config.homeIdleClockMinutes;
        barStyle = choices.getString(Config.BAR_STYLE_KEY,
                config.topMenu ? Config.BAR_STYLE_GEAR
                        : config.iconOnlyButtons ? Config.BAR_STYLE_ICON : Config.BAR_STYLE_PILL);
        int glowPick = choices.getInt(Config.GLOW_KEY, -1);
        ambientGlow = glowPick >= 0 ? glowPick == 1 : config.ambientGlow;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] barIds = {R.id.barPill, R.id.barIcon, R.id.barGear};
        for (int i = 0; i < barIds.length; i++) {
            final String value = BAR_STYLES[i];
            barStyleChoices[i] = findViewById(barIds[i]);
            barStyleChoices[i].setOnClickListener(v -> {
                if (value.equals(barStyle)) return;
                barStyle = value;
                choices.edit().putString(Config.BAR_STYLE_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] glowIds = {R.id.glowOff, R.id.glowFollow};
        for (int i = 0; i < glowIds.length; i++) {
            final boolean value = i == 1;
            glowChoices[i] = findViewById(glowIds[i]);
            glowChoices[i].setOnClickListener(v -> {
                if (ambientGlow == value) return;
                ambientGlow = value;
                choices.edit().putInt(Config.GLOW_KEY, value ? 1 : 0).apply();
                changed();
                refresh();
            });
        }
        int[] idleIds = {R.id.idleOff, R.id.idle3, R.id.idle8, R.id.idle12};
        for (int i = 0; i < idleIds.length; i++) {
            final int value = IDLE_MINUTES[i];
            idleChoices[i] = findViewById(idleIds[i]);
            idleChoices[i].setOnClickListener(v -> {
                if (idleClockMinutes == value) return;
                idleClockMinutes = value;
                choices.edit().putInt(Config.IDLE_CLOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
            reverseRow(glowChoices);
            reverseRow(idleChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        barButtonsChoice = findViewById(R.id.barButtonsChoice);
        barButtonsChoice.setOnClickListener(v -> showBarButtonsPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            SharedPreferences.Editor editor = choices.edit();
            editor.remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY)
                    .remove(Config.BAR_STYLE_KEY)
                    .remove(Config.GLOW_KEY)
                    .remove(Config.IDLE_CLOCK_KEY);
            for (String key : choices.getAll().keySet()) {
                if (key.startsWith(Config.TILE_STYLE_PREFIX)) editor.remove(key);
                if (key.startsWith(Config.PILL_PREFIX)) editor.remove(key);
            }
            editor.apply();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = 100 - current.dockOpacity;
            clockFormat = current.clockFormat;
            idleClockMinutes = current.homeIdleClockMinutes;
            barStyle = current.topMenu ? Config.BAR_STYLE_GEAR
                    : current.iconOnlyButtons ? Config.BAR_STYLE_ICON : Config.BAR_STYLE_PILL;
            ambientGlow = current.ambientGlow;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    /**
     * The wallpaper button opens the folder browser at once: pick a volume, walk into a folder, tap a picture.
     * Nothing is scanned ahead of time - a card full of photos costs seconds to walk, and a list longer than the
     * screen cannot be scrolled with a remote.
     */
    private void showWallpaperPicker() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PICTURE_REQUEST);
            Toast.makeText(this, R.string.wallpaper_need_storage, Toast.LENGTH_LONG).show();
            return;
        }
        final List<File> volumes = volumes();
        if (volumes.isEmpty()) {
            Toast.makeText(this, R.string.wallpaper_no_files, Toast.LENGTH_LONG).show();
            return;
        }
        if (volumes.size() == 1) {
            showFolderDialog(volumes.get(0));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < volumes.size(); i++) {
            labels.add(i == 0 ? getString(R.string.wallpaper_volume_internal)
                    : getString(R.string.wallpaper_volume_external, volumes.get(i).getName()));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_volume_title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> showFolderDialog(volumes.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The card first, then every other readable volume: a USB stick shows up under /storage with its label name. */
    private static List<File> volumes() {
        List<File> out = new ArrayList<>();
        File card = Environment.getExternalStorageDirectory();
        if (card != null && card.isDirectory() && card.canRead()) out.add(card);
        File[] mounts = new File("/storage").listFiles();
        if (mounts != null) {
            for (File mount : mounts) {
                String name = mount.getName();
                if (!mount.isDirectory() || !mount.canRead()) continue;
                if ("emulated".equals(name) || "self".equals(name)) continue;
                // A mount point with nothing in it is an empty slot, not a volume with pictures on it.
                if (mount.listFiles() == null || out.contains(mount)) continue;
                out.add(mount);
            }
        }
        return out;
    }

    /** One level of a folder: its sub-folders first, then its own pictures, newest picture first. */
    private void showFolderDialog(File folder) {
        File[] entries = folder.listFiles();
        List<File> dirs = new ArrayList<>();
        List<File> pictures = new ArrayList<>();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.getName().startsWith(".")) continue;
                if (isSystemFolder(entry)) continue;
                if (entry.isDirectory()) {
                    if (hasVisibleContent(entry)) dirs.add(entry);
                } else if (isPicture(entry)) {
                    pictures.add(entry);
                }
            }
        }
        dirs.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        dirs.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        dirs = pictureHomesFirst(dirs);
        pictures.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (dirs.isEmpty() && pictures.isEmpty()) {
            Toast.makeText(this, R.string.wallpaper_empty_folder, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        List<File> targets = new ArrayList<>();
        for (File dir : dirs) {
            labels.add(dir.getName() + "/");
            targets.add(dir);
        }
        for (File picture : pictures) {
            labels.add(picture.getName());
            targets.add(picture);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(folderTitle(folder))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    File target = targets.get(which);
                    if (target.isDirectory()) showFolderDialog(target);
                    else applyPicture(target);
                })
                .setNegativeButton(android.R.string.cancel, null);
        File parent = parentOf(folder);
        if (parent != null) {
            builder.setNeutralButton(R.string.wallpaper_up_one, (dialog, which) -> showFolderDialog(parent));
        }
        builder.show();
    }

    /** The folder above this one on the same volume, or null at the top, where the volume list takes over. */
    private static File parentOf(File folder) {
        File card = Environment.getExternalStorageDirectory();
        if (card != null && card.equals(folder)) return null;
        File parent = folder.getParentFile();
        if (parent == null) return null;
        String path = parent.getAbsolutePath();
        // Stop at a volume: /storage and / belong to the system, never to the owner's pictures.
        if ("/".equals(path) || "/storage".equals(path)) return null;
        return parent;
    }

    /** "Internal storage / Pictures" rather than the raw /storage/emulated/0 path. */
    private String folderTitle(File folder) {
        File card = Environment.getExternalStorageDirectory();
        String path = folder.getAbsolutePath();
        if (card != null) {
            String base = card.getAbsolutePath();
            if (path.equals(base)) return getString(R.string.wallpaper_volume_internal);
            if (path.startsWith(base + File.separator)) {
                return getString(R.string.wallpaper_volume_internal) + " / "
                        + path.substring(base.length() + 1).replace(File.separatorChar, '/');
            }
        }
        return getString(R.string.wallpaper_volume_external, folder.getName()) + " / "
                + path.replace(File.separatorChar, '/');
    }

   private static final int PICTURE_REQUEST = 0x5741;
    /** The wallpaper Home should swap to in place, so the pick shows up without rebuilding Home. */
    static final String EXTRA_WALLPAPER = "wallpaper";

    /** The app sandbox and the system media folders: none of them holds a picture worth a wallpaper. */
    private static final String[] SYSTEM_FOLDERS =
            {"Android", "Alarms", "Notifications", "Ringtones", "Music", "Podcasts", "Recordings"};

    /** The three folders a photo of the owner is most likely in: they lead the very first list. */
    private static final String[] PICTURE_HOMES = {"Pictures", "DCIM", "Download"};

   /** Pictures, DCIM and Download lead the list: those three are where a photo of the owner's usually sits. */
    private static List<File> pictureHomesFirst(List<File> dirs) {
        List<File> ordered = new ArrayList<>();
        for (String home : PICTURE_HOMES) {
            for (File dir : dirs) {
                if (dir.getName().equalsIgnoreCase(home) && !ordered.contains(dir)) ordered.add(dir);
            }
        }
        for (File dir : dirs) {
            if (!ordered.contains(dir)) ordered.add(dir);
        }
        return ordered;
    }
   /** True when a folder holds a sub-folder or a picture the browser would actually list. */
    private static boolean hasVisibleContent(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            if (entry.getName().startsWith(".")) continue;
            if (entry.isDirectory()) {
                if (!isSystemFolder(entry)) return true;
            } else if (isPicture(entry)) {
                return true;
            }
        }
        return false;
    }
    private static boolean isSystemFolder(File dir) {
        String name = dir.getName();
        for (String known : SYSTEM_FOLDERS) {
            if (known.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** A picture or a film: either one may become the wallpaper. */
    private static boolean isPicture(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return WallpaperRotation.isPicture(name) || WallpaperRotation.isFilm(name);
    }
    /** Copies the picked picture or film into {@code wallpapers/} under a local- name, so a theme image is never overwritten. */
    private void applyPicture(File source) {
        // A film that big would be copied only to end up black: the decoder refuses 4K60 outright, so the pick
        // is turned down here while the person still remembers which file they chose.
        if (WallpaperRotation.film(source) && !WallpaperRotation.lightFilm(source)) {
            Toast.makeText(this, R.string.wallpaper_film_too_big, Toast.LENGTH_LONG).show();
            return;
        }
        File dir = Config.dir(this);
        File target = dir == null ? null : new File(new File(dir, "wallpapers"), "local-" + source.getName());
        if (target == null) {
            Toast.makeText(this, R.string.wallpaper_copy_failed, Toast.LENGTH_LONG).show();
            return;
        }
        File folder = target.getParentFile();
        if (folder != null && !folder.isDirectory()) folder.mkdirs();
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } catch (IOException | SecurityException failed) {
            Toast.makeText(this, R.string.wallpaper_copy_failed, Toast.LENGTH_LONG).show();
            return;
        }
        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, target.getName()).apply();
        // A picture is also stored as the single copy Home draws, so a clean that empties wallpapers/ cannot
        // leave Home blank. A film is already inside the app's own folder and the media player plays it from
        // there: a second copy would only double the megabytes, so the old picture's copy is dropped instead.
        if (WallpaperRotation.film(target)) PrefsBackup.forgetWallpaper(this);
        else PrefsBackup.keepWallpaper(this, target);
        // The opening frame of a new film is worked out right here, in the background, while Home is still coming
        // up behind this page: kept beside the app's own files, it means Home never has to read the film for it,
        // neither on the first return nor on any rebuild after that.
        if (WallpaperRotation.film(target)) {
            int wide = getResources().getDisplayMetrics().widthPixels;
            int tall = getResources().getDisplayMetrics().heightPixels;
            FilmStill.prepare(this, target, wide, tall);
        }
        // The fresh pick replaces whatever was there: only the picture in use stays in the collection, and only
        // its opening frame is kept.
        pruneWallpapers(dir, target.getName());
        FilmStill.prune(this, WallpaperRotation.film(target) ? target : null);
        changed();
        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
        refresh();
        // Home keeps the decoder it opened with, and a new wallpaper (a film especially) changed the file
        // under it: bring Home forward so the fresh pick is on screen instead of waiting for the next launch.
        Intent home = new Intent(this, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        home.putExtra(EXTRA_WALLPAPER, target.getAbsolutePath());
        startActivity(home);
    }

    /** Deletes every file in {@code wallpapers/} except {@code keep} (null = delete them all). */
    private static void pruneWallpapers(File dir, String keep) {
        File folder = dir == null ? null : new File(dir, "wallpapers");
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (keep != null && keep.equals(file.getName())) continue;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** The top-bar buttons with their state, then show / hide for the one picked. Saved as pill:<name>. */
    private void showBarButtonsPicker() {
        Config config = Config.load(this);
        String[] names = {"inputs", "network", "library", "settings", "library_pill"};
        int[] labels = {R.string.bar_inputs, R.string.bar_network, R.string.bar_library,
                R.string.bar_settings, R.string.bar_library_pill};
        boolean[] shown = {config.showInputsPill, config.showNetworkPill,
                config.libraryButton && config.appLibrary, config.showSettingsButton, config.showLibraryPill};
        String[] items = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            items[i] = getString(labels[i]) + " — " + getString(shown[i] ? R.string.bar_show : R.string.bar_hide);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.bar_buttons_label)
                .setItems(items, (dialog, which) ->
                        showBarButtonChoice(names[which], getString(labels[which]), shown[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showBarButtonChoice(String name, String label, boolean shown) {
        String[] options = {getString(R.string.bar_show), getString(R.string.bar_hide)};
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setSingleChoiceItems(options, shown ? 0 : 1, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putInt(Config.PILL_PREFIX + name, which == 0 ? 1 : 0).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** How many top-bar buttons the TV hides, for the row's summary. */
    private int hiddenBarButtons() {
        Config config = Config.load(this);
        int hidden = 0;
        if (!config.showInputsPill) hidden++;
        if (!config.showNetworkPill) hidden++;
        if (!(config.libraryButton && config.appLibrary)) hidden++;
        if (!config.showSettingsButton) hidden++;
        if (!config.showLibraryPill) hidden++;
        return hidden;
    }

    private static int indexOf(List<File> images, String name) {        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        for (int i = 0; i < idleChoices.length; i++) {
            idleChoices[i].setSelected(idleClockMinutes == IDLE_MINUTES[i]);
        }
        for (int i = 0; i < barStyleChoices.length; i++) {
            barStyleChoices[i].setSelected(BAR_STYLES[i].equals(barStyle));
        }
        for (int i = 0; i < glowChoices.length; i++) {
            glowChoices[i].setSelected(ambientGlow == (i == 1));
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        // A saved choice that no longer names a picture (a pick the stock cleaner emptied, or a value an older
        // build stored for a feature since removed) reads as "follow the theme": Home shows the stored copy
        // meanwhile, so the row must not claim a name nothing can restore.
        if (wallpaperCurrent != null && !"none".equals(wallpaperCurrent)) {
            File folder = Config.dir(this) == null ? null : new File(Config.dir(this), "wallpapers");
            File picked = folder == null ? null : new File(folder, wallpaperCurrent);
            File single = Config.dir(this) == null ? null : new File(Config.dir(this), Config.WALLPAPER_NAME);
            if (picked != null && !picked.isFile() && (single == null || !single.isFile())) {
                wallpaperCurrent = null;
            }
        }
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        barButtonsChoice.setText(getString(R.string.bar_buttons_state, hiddenBarButtons()));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(Config.BAR_STYLE_KEY)
                || choices.contains(Config.GLOW_KEY)
                || choices.contains(OpenOnStart.KEY)
                || choices.contains(Config.IDLE_CLOCK_KEY) || Config.hasTileStyleChoices(choices)
                || Config.hasPillChoices(choices);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
