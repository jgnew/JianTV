package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** On-demand installed-app picker. Uses the launcher's glass resources; no backdrop capture or blur. */
final class AppLibraryDialog {
    /**
     * The catalogue, the labels and the icons load here, off the UI thread: each label and icon opens the app's
     * resources, and on a TCL G09 that froze Home for 60-140 ms (2 to 4 janky frames) every time the library opened.
     */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Labels kept between openings (LOADER thread only), until the app is updated. */
    private static final Map<String, Known> KNOWN = new HashMap<>();

    private static final class Known {
        final long updated;
        final String label;

        Known(long updated, String label) {
            this.updated = updated;
            this.label = label;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * Icons kept between openings, like the labels: only the apps that are not on Home are decoded, so this is a
     * handful of drawables, and one already here is drawn again instead of loaded again. An app update replaces
     * its entry, since the icon may have changed with it.
     */
    private static final Map<String, CachedIcon> ICONS = new LinkedHashMap<>();
    /** Entries kept before the oldest go: a launcher left running through many installs must not grow forever. */
    private static final int ICON_LIMIT = 48;

    private static final class CachedIcon {
        final long updated;
        final Drawable drawable;

        CachedIcon(long updated, Drawable drawable) {
            this.updated = updated;
            this.drawable = drawable;
        }
    }

    private static Drawable knownIcon(String pkg, long updated) {
        synchronized (ICONS) {
            CachedIcon entry = ICONS.get(pkg);
            return entry != null && entry.updated == updated ? entry.drawable : null;
        }
    }

    private static void keepIcon(String pkg, long updated, Drawable drawable) {
        synchronized (ICONS) {
            ICONS.put(pkg, new CachedIcon(updated, drawable));
            Iterator<String> oldest = ICONS.keySet().iterator();
            while (ICONS.size() > ICON_LIMIT && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** Memory is short: the kept icons go, and the next opening decodes them again. */
    static void trimIcons() {
        synchronized (ICONS) {
            ICONS.clear();
        }
    }

    private final Set<String> iconsLoading = new HashSet<>();
    private final Runnable dismissed;
    /** Read on LOADER too, so an icon that arrives after the library closed is dropped, not queued. */
    private volatile boolean closed;
    /** Only the latest load fills the catalogue, and it shows the popup if an opening is waiting. */
    private final LibraryLoads loads = new LibraryLoads();
    private final Activity activity;
    private final Predicate<String> onHome;
    private final Runnable changed;
    private final LinearLayout panel;
    private final boolean reducedMotion;
    private final boolean selectionMode;
    /** library_ok_opens without app_library_selection: OK opens every app, Add to Home is in the hold menu. */
    private final boolean okOpens;
    private final boolean animations;
    private final boolean rtl;
    private TextView instructions;
    final LibraryWindow popup;
    private final float density;
    private List<ResolveInfo> apps = new ArrayList<>();
    private String selectedPackage;
    private int selectedPosition;
    private int selectedOffset;
    private final Map<String, Long> installTimes = new HashMap<>();
    /** Package -> lastUpdateTime: the kept icon for a package is dropped when the app it belongs to changed. */
    private final Map<String, Long> updateTimes = new HashMap<>();
    /** Packages installed within the week, worked out once per fill instead of once per card bound. */
    private final Set<String> recentPackages = new HashSet<>();
    private final Map<String, String> labels = new HashMap<>();
    /** package -> rank from library_order.txt, read on LOADER with the catalogue. */
    private final Map<String, Integer> ranks = new HashMap<>();
    /** The card picked up with Move, or null: while set the arrows swap it a place at a time. */
    private String carriedPackage;
    /** Reused by the reveal after each swap; a fresh Rect there was garbage on every arrow press. */
    private final Rect revealRect = new Rect();
    private long catalogTime;
    private PopupWindow actionPopup;
    private RecyclerView catalog;
    /** The adapter built by showCatalog, kept for the one-card swaps of Move. */
    private Catalog adapter;
    /** The package picked up with Move: the arrows swap it a card at a time until OK or Back places it. */
    private FrameLayout shelf;
    private GlassView highlight;
    private SpringAnimation spring;
    private float scrollOffset;
    private boolean highlightShown;
    private final int catalogWidth;

    private void stopHighlight() {
        if (spring != null) {
            spring.cancel();
            spring = null;
        }
        if (catalog != null) catalog.stopScroll();
        // Dropped together: the panel keeps the shelf until the next fill, and the cards hold the icons.
        catalog = null;
        adapter = null;
        shelf = null;
        highlight = null;
        highlightShown = false;
        scrollOffset = 0f;
    }

    /** Reused by every scroll frame: a fresh Rect here was garbage while the shelf was moving. */
    private final Rect cardRect = new Rect();

    private void highlight(View card, boolean animate) {
        if (highlight == null || card.getParent() == null) return;
        Rect rect = cardRect;
        rect.set(0, 0, card.getWidth(), card.getHeight());
        shelf.offsetDescendantRectToMyCoords(card, rect);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        lp.width = rect.width() + dp(12);
        lp.height = rect.height() + dp(12);
        highlight.setLayoutParams(lp);
        highlight.setTranslationY(rect.top - dp(6));
        float x = rect.left - dp(6) + scrollOffset;
        if (!highlightShown || !animate || !animations) {
            spring.cancel();
            highlight.setTranslationX(x - scrollOffset);
        } else spring.animateToFinalPosition(x);
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), 0x18FFFFFF);
        return bg;
    }

    private void resize(int width, int height) {
        popup.size(width, height);
    }

    /** library_excluded_apps (config.xml), never offered in the catalogue; empty by default, so existing
     *  setups are unaffected. See LibraryApps.excludedFromCatalog(). */
    private final Set<String> excludedApps;
    /** hidden_apps: drawn from no tile at all, so the library must not offer them either. */
    private final Set<String> hiddenApps;

    AppLibraryDialog(Activity activity, boolean animations, boolean reducedMotion, boolean selectionMode, boolean okOpens, Predicate<String> onHome, Runnable changed, Runnable dismissed, Set<String> excludedApps, Set<String> hiddenApps) {
        this.activity = activity;
        this.animations = animations;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.reducedMotion = reducedMotion;
        this.selectionMode = selectionMode;
        this.okOpens = okOpens && !selectionMode;
        this.onHome = onHome;
        this.changed = changed;
        this.dismissed = dismissed;
        this.excludedApps = excludedApps;
        this.hiddenApps = hiddenApps;
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackgroundResource(R.drawable.glass_panel);
        View decor = activity.getWindow().getDecorView();
        catalogWidth = Math.min(dp(800), Math.max(dp(200), decor.getWidth() - dp(64)));
        int width = catalogWidth;
        int height = dp(280);
        // No dim behind it: the panel is glass already, and darkening Home under it only made the library look
        // heavier than the tiles it came from. Theme.LibraryDialog turns the dialog's own dim off; LibraryWindow
        // also clears the flag once the window is up.
        popup = new LibraryWindow(panel, width, height);
        popup.setOnDismissListener(d -> {
            closed = true;
            if (carriedPackage != null) {   // closed mid-move: the places already swapped still stand
                saveOrder();
                carriedPackage = null;
            }
            dismissActions();
            stopHighlight();
            iconsLoading.clear();   // the kept icons stay: the next opening draws them straight away
            panel.removeAllViews();   // the card tree goes with the window, icons included
            dismissed.run();
        });
    }

    void close() {
        dismissActions();
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;   // still loading: it never shows
            dismissed.run();
        }
    }

    void show() {
        load(true);
    }

    private void reveal() {
        popup.open();
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 180).start();
    }

    /** Called by the activity's existing package receiver; an uninstall cannot leave a stale action. */
    /** Packages with a leanback entry, from the last refresh. */
    private final java.util.Set<String> tvPackages = new java.util.HashSet<>();

    void refresh() {
        load(false);
    }

    /**
     * Reads the catalogue on LOADER, then fills it on the UI thread. reveal: from opening the library; the popup
     * appears once the latest load has filled it, even when a refresh replaced the opening's own load.
     */
    private void load(boolean reveal) {
        dismissActions();
        int load = loads.start(reveal);
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        LOADER.execute(() -> {
            Map<String, ResolveInfo> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            // Prefer the TV entry when an app declares both categories.
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (!pkg.equals(self) && !hiddenApps.contains(pkg)
                            && !LibraryApps.excludedFromCatalog(excludedApps, pkg)
                            && !unique.containsKey(pkg)) unique.put(pkg, info);
                }
            }
            Map<String, String> names = new HashMap<>();
            Map<String, Long> installed = new HashMap<>();
            Map<String, Long> updatedTimes = new HashMap<>();
            // One binder call for every package: getPackageInfo() per app was one IPC each, sixty of them per open.
            Map<String, PackageInfo> packageInfos = new HashMap<>();
            for (PackageInfo info : pm.getInstalledPackages(0)) packageInfos.put(info.packageName, info);
            for (Map.Entry<String, ResolveInfo> entry : unique.entrySet()) {
                String pkg = entry.getKey();
                long updated = 0;
                PackageInfo info = packageInfos.get(pkg);
                if (info != null) {
                    installed.put(pkg, info.firstInstallTime);
                    updated = info.lastUpdateTime;
                } else {
                    installed.put(pkg, 0L);
                }
                updatedTimes.put(pkg, updated);
                Known known = KNOWN.get(pkg);
                if (known == null || known.updated != updated) {
                    known = new Known(updated, loadLabel(pm, entry.getValue()));
                    KNOWN.put(pkg, known);
                }
                names.put(pkg, known.label);
            }
            KNOWN.keySet().retainAll(unique.keySet());
            // The owner's own order: read here so the fill never touches the file on the UI thread.
            List<String> order = LibraryOrder.load(LibraryOrder.file(activity));
            main.post(() -> {
                if (closed || !loads.isLatest(load)) return;
                tvPackages.clear();
                tvPackages.addAll(tv);
                installTimes.clear();
                installTimes.putAll(installed);
                updateTimes.clear();
                updateTimes.putAll(updatedTimes);
                labels.clear();
                labels.putAll(names);
                ranks.clear();
                ranks.putAll(LibraryOrder.ranks(order));
                fill(new ArrayList<>(unique.values()));
                if (loads.takeReveal()) reveal();
            });
        });
    }

    /**
     * The catalogue's collator, built once. Collator.getInstance() loads the locale's rules from the platform on
     * every call, and the library fills on every Home press, every add and every install.
     */
    private static Collator collator;
    private static Locale collatorLocale;

    private static Collator collator() {
        Locale now = Locale.getDefault();
        Collator mine = collator;
        if (mine == null || !now.equals(collatorLocale)) {
            mine = Collator.getInstance(now);
            collator = mine;
            collatorLocale = now;
        }
        return mine;
    }

    private void fill(List<ResolveInfo> catalogue) {
        if (carriedPackage != null) {   // a rebuild ends Move: the order it set is written first
            saveOrder();
            carriedPackage = null;
        }
        // The library lists only the apps that are not on Home yet: adding a shortcut drops that app from
        // the catalogue on the next fill, hiding it on Home brings it back.
        catalogue.removeIf(app -> onHome.test(app.activityInfo.packageName));
        apps = catalogue;
        catalogTime = System.currentTimeMillis();
        recentPackages.clear();
        for (Map.Entry<String, Long> entry : installTimes.entrySet()) {
            if (LibraryApps.recent(entry.getValue(), catalogTime)) recentPackages.add(entry.getKey());
        }
        Collator collator = collator();
        apps.sort((a, b) -> {
            String pa = a.activityInfo.packageName, pb = b.activityInfo.packageName;
            if (selectionMode) return LibraryApps.compareCatalog(label(a), pa, label(b), pb, collator);
            // The owner's order and nothing else: saved apps in saved order, the rest (never moved yet) after
            // them by label, so a new app stands still until it is moved instead of drifting by usage.
            int saved = LibraryOrder.compare(ranks, pa, pb);
            if (saved != 0) return saved;
            int byLabel = collator.compare(label(a), label(b));
            return byLabel != 0 ? byLabel : pa.compareTo(pb);
        });
        showCatalog();
    }

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = loadLabel(activity.getPackageManager(), app);
        labels.put(pkg, value);
        return value;
    }

    private static String loadLabel(PackageManager pm, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        try { return Cards.cleanLabel(String.valueOf(app.loadLabel(pm)), pkg); }
        catch (RuntimeException unavailable) { return pkg; }
    }

    /** The app's icon, kept between openings: loaded on LOADER once, then set here straight away. */
    private void bindIcon(ImageView icon, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        icon.setTag(pkg);
        final long updated = updateTimes.getOrDefault(pkg, 0L);
        Drawable cached = knownIcon(pkg, updated);
        // A rebind already showing this artwork (Move rebinds every card on each arrow) is left alone:
        // setImageDrawable re-measures the row for nothing.
        if (icon.getDrawable() != cached) icon.setImageDrawable(cached);
        if (cached != null || !iconsLoading.add(pkg)) return;
        PackageManager pm = activity.getPackageManager();
        LOADER.execute(() -> {
            if (closed) return;   // the opening is over: nothing it loads would be shown
            Drawable loaded;
            try { loaded = app.loadIcon(pm); }
            catch (RuntimeException unavailable) { loaded = null; }
            Drawable result = loaded;
            main.post(() -> {
                iconsLoading.remove(pkg);
                if (closed) return;
                Drawable shown = result != null ? result : activity.getDrawable(R.drawable.ic_apps);
                keepIcon(pkg, updated, shown);
                // The view may show another app by now; a view bound to it later takes it from the cache.
                if (pkg.equals(icon.getTag()) && icon.getDrawable() != shown) icon.setImageDrawable(shown);
            });
        });
    }

    private void heading(String text) {
        TextView title = text(text, 18, 0xFFFFFFFF);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), dp(10), dp(12), dp(8));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The hint follows the focused card: setting the same text again would re-layout the whole panel. */
    private void setHint(boolean home) {
        if (instructions == null) return;
        String text = hint(home);
        if (!text.contentEquals(instructions.getText())) instructions.setText(text);
    }

    /** Reuse translated actions instead of shipping another hint in every locale. */
    private String hint(boolean home) {
        if (carriedPackage != null) return activity.getString(R.string.library_move_hint);
        if (selectionMode) return activity.getString(android.R.string.ok) + ": "
                + activity.getString(home ? R.string.library_remove_shortcut : R.string.library_add_home);
        return activity.getString(home || okOpens ? R.string.library_hint_open : R.string.library_hint_add);
    }

    private void showCatalog() {
        stopHighlight();
        resize(catalogWidth, dp(280));
        panel.removeAllViews();
        LinearLayout header = new LinearLayout(activity);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(8), dp(4));
        TextView title = text(activity.getString(R.string.app_library), 22, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        title.setGravity(Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));
        instructions = text(hint(false), 13, 0xBBFFFFFF);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setPadding(dp(12), 0, dp(12), dp(6));
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, dp(26)));
        panel.setFocusableInTouchMode(apps.isEmpty());
        if (apps.isEmpty()) {
            instructions.setVisibility(View.GONE);
            heading(activity.getString(R.string.library_empty));
            panel.setFocusableInTouchMode(true);
            panel.requestFocus();
            return;
        }
        shelf = new FrameLayout(activity);
        // The glass uses physical x coordinates; only the catalogue mirrors its item order.
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(false);
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        catalog = list;
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override protected boolean isLayoutRTL() { return rtl; }
        });
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        adapter = new Catalog();
        list.setAdapter(adapter);
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));
        highlight = new GlassView(activity, null);
        highlight.setRadius(dp(20));
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shelf.addView(highlight, new FrameLayout.LayoutParams(dp(144), dp(180)));
        scrollOffset = 0;
        spring = new SpringAnimation(highlight, new FloatPropertyCompat<View>("libraryScrollX") {
            @Override public float getValue(View view) { return view.getTranslationX() + scrollOffset; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value - scrollOffset); }
        });
        if (reducedMotion) Motion.reduce(spring);
        else spring.setSpring(new SpringForce().setStiffness(460f).setDampingRatio(1f));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollOffset += dx;
                highlight.setTranslationX(highlight.getTranslationX() - dx);
            }
        });
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1));
        int position = Math.min(selectedPosition, apps.size() - 1);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(selectedPackage)) { position = i; break; }
        }
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, selectedOffset);
        final int target = position;
        list.post(() -> {
            if (catalog != list || !popup.isShowing()) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
    }

    private void restoreCardFocus() {
        if (catalog == null || !popup.isShowing()) return;
        catalog.post(() -> {
            if (catalog == null || !popup.isShowing() || actionPopup != null) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void changeHome(ResolveInfo app, boolean add) {
        if (!LibraryApps.save(activity, app.activityInfo.packageName, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (selectionMode) {
            updateSelectionFocus(app, add);
            changed.run();
            fill(new ArrayList<>(apps));
            return;
        }
        selectedOffset = 0;
        if (add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) selectedOffset = offsetOf(holder.itemView);
            // Advance through missing shortcuts instead of following the newly checked app
            // into the Home group. Preserve its screen slot when the catalogue is rebuilt.
            int next = LibraryApps.nextFocus(selectedPosition, apps.size(),
                    index -> !onHome.test(apps.get(index).activityInfo.packageName));
            if (next >= 0) {
                selectedPackage = apps.get(next).activityInfo.packageName;
                selectedPosition = next;
            }
        }
        dismissActions();
        changed.run();
        refresh();
    }

    private void updateSelectionFocus(ResolveInfo app, boolean add) {
        int position = apps.indexOf(app);
        int next = add ? -1 : LibraryApps.nextAfterRemoval(position, apps.size());
        selectedOffset = 0;
        if (!add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(position);
            if (holder != null) selectedOffset = offsetOf(holder.itemView);
        }
        selectedPackage = next >= 0 ? apps.get(next).activityInfo.packageName : app.activityInfo.packageName;
    }

    /** How far a card sits from the row's reading start: the scroll offset that keeps its slot on a rebuild. */
    private int offsetOf(View card) {
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
        return rtl
                ? catalog.getWidth() - catalog.getPaddingRight()
                    - catalog.getLayoutManager().getDecoratedRight(card) - params.rightMargin
                : catalog.getLayoutManager().getDecoratedLeft(card)
                    - params.leftMargin - catalog.getPaddingLeft();
    }

    private void open(ResolveInfo app) {
        close();
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
        if (tvPackages.contains(app.activityInfo.packageName)) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(ResolveInfo app, View anchor) {
        dismissActions();
        selectedPackage = app.activityInfo.packageName;
        String pkg = selectedPackage;
        if (spring != null) spring.cancel();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        PopupWindow menu = new PopupWindow(menuPanel, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(0f);
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            restoreCardFocus();
        });
        boolean home = onHome.test(pkg);
        // Move first: it is the action the menu exists for, and the one the owner reaches for most.
        addAction(rows, R.string.library_move, () -> startMove(app));
        addAction(rows, home ? R.string.library_remove_shortcut : R.string.library_add_home,
                () -> changeHome(app, !home));
        int menuWidth = dp(220);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView title = rows.getChildAt(i).findViewById(R.id.title);
            menuWidth = Math.max(menuWidth, (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + dp(58));
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        menuWidth = Math.min(menuWidth, panel.getWidth() - dp(24));
        menu.setWidth(menuWidth);
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        // The menu hangs off the library's own window: a popup on the activity's decor would be drawn
        // UNDER the library's dialog window and dismissed by the very key-up that opened it.
        View host = panel.getRootView();
        int[] hostLocation = new int[2];
        host.getLocationOnScreen(hostLocation);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        location[0] -= hostLocation[0];
        location[1] -= hostLocation[1];
        int[] panelLocation = new int[2];
        panel.getLocationOnScreen(panelLocation);
        Rect screen = new Rect(panelLocation[0] - hostLocation[0], panelLocation[1] - hostLocation[1],
                panelLocation[0] - hostLocation[0] + panel.getWidth(),
                panelLocation[1] - hostLocation[1] + panel.getHeight());
        int x = Math.max(screen.left + dp(12), Math.min(location[0] + anchor.getWidth() - dp(12), screen.right - menuWidth - dp(12)));
        int y = Math.max(screen.top + dp(12), Math.min(location[1] + dp(8), screen.bottom - menuPanel.getMeasuredHeight() - dp(12)));
        menuPanel.setAlpha(0f);
        menuPanel.setPivotY(0f);
        menuPanel.setScaleY(0.92f);
        menuPanel.setTranslationY(-dp(8));
        menu.showAtLocation(host, Gravity.TOP | Gravity.START, x, y);
        menuPanel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private int indexOfPackage(String pkg) {
        for (int i = 0; i < apps.size(); i++)
            if (apps.get(i).activityInfo.packageName.equals(pkg)) return i;
        return -1;
    }

    /** The whole catalogue as it now stands: what the next opening sorts by. */
    private void saveOrder() {
        List<String> keys = new ArrayList<>(apps.size());
        for (ResolveInfo app : apps) keys.add(app.activityInfo.packageName);
        if (!LibraryOrder.save(LibraryOrder.file(activity), keys))
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_SHORT).show();
    }

    /** Hold menu's Move: the card stays under the focus, the arrows swap it a place at a time, OK puts it down. */
    private void startMove(ResolveInfo app) {
        dismissActions();
        carriedPackage = app.activityInfo.packageName;
        selectedPackage = carriedPackage;
        selectedPosition = Math.max(0, indexOfPackage(carriedPackage));
        if (spring != null) spring.cancel();
        setHint(false);
        if (adapter != null) adapter.notifyItemRangeChanged(0, apps.size());
        settleCarriedAtSelection();
        dimCards();
    }

    /** OK, Back, a rebuild or closing the library: the places already swapped stay, the card is put down. */
    private void endMove() {
        if (carriedPackage == null) return;
        saveOrder();
        carriedPackage = null;
        setHint(false);
        if (adapter != null) adapter.notifyItemRangeChanged(0, apps.size());
        dimCards();
        restoreCardFocus();
    }

    /**
     * One arrow: the carried card and its neighbour trade places; the file is written when the card is put down.
     * <p>
     * The row is pinned to the scroll it already has before the swap is announced. Left alone, a layout that
     * follows the focused card keeps that card at the padding edge, so leaving the first slot dragged the whole
     * row sideways (and reaching the last slot did the mirror of it). Pinned, the card slides one place inside a
     * row that stays put; the only reveal left is for a card that would land outside the row.
     */
    private void moveCarried(int step) {
        if (catalog == null || carriedPackage == null || step == 0) return;
        int from = indexOfPackage(carriedPackage);
        if (from < 0) return;
        int to = from + step;
        if (to < 0 || to >= apps.size()) {   // already the first or last card: nothing moves, the window least
            if (catalog != null) catalog.stopScroll();
            return;
        }
        LinearLayoutManager manager = catalog.getLayoutManager() instanceof LinearLayoutManager
                ? (LinearLayoutManager) catalog.getLayoutManager() : null;
        int anchor = manager == null ? RecyclerView.NO_POSITION : manager.findFirstVisibleItemPosition();
        int anchorOffset = 0;
        if (manager != null && anchor != RecyclerView.NO_POSITION) {
            View first = manager.findViewByPosition(anchor);
            if (first != null) {
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) first.getLayoutParams();
                anchorOffset = rtl
                        ? catalog.getWidth() - catalog.getPaddingRight()
                            - manager.getDecoratedRight(first) - params.rightMargin
                        : manager.getDecoratedLeft(first) - params.leftMargin - catalog.getPaddingLeft();
            }
        }
        Collections.swap(apps, from, to);
        selectedPosition = to;
        selectedPackage = carriedPackage;
        if (adapter != null) adapter.notifyItemMoved(from, to);
        if (manager != null && anchor != RecyclerView.NO_POSITION) {
            manager.scrollToPositionWithOffset(anchor, anchorOffset);   // the next layout keeps the row in place
        }
        settleCarriedAtSelection();
        dimCards();
    }

    private void settleCarriedAtSelection() {
        settleCarried(selectedPosition);
    }

    /** Focus, glass and a minimal reveal for the carried card after a swap: what the Home grid does while reordering. */
    private void settleCarried(final int position) {
        if (catalog == null) return;
        catalog.post(() -> {
            if (catalog == null || carriedPackage == null) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(position);
            if (holder == null) {   // laid out nowhere yet: bring its place into view, then focus it
                catalog.scrollToPosition(position);
                return;
            }
            View card = holder.itemView;
            if (!card.hasFocus()) card.requestFocus();
            // Only a card that really sits outside the row moves the window: asking the parent on every swap
            // let a card that never left the view drag the row anyway.
            if (card.getLeft() < 0 || card.getRight() > catalog.getWidth()) {
                Rect rect = revealRect;
                rect.set(0, 0, card.getWidth(), card.getHeight());
                card.requestRectangleOnScreen(rect, true);
            }
            highlight(card, true);
        });
    }

    /** Cards on screen, dimmed except the one being carried: the cue the Home grid uses while reordering. */
    private void dimCards() {
        if (catalog == null) return;
        for (int i = 0; i < catalog.getChildCount(); i++) {
            View card = catalog.getChildAt(i);
            int pos = catalog.getChildAdapterPosition(card);
            boolean carried = carriedPackage == null || pos == RecyclerView.NO_POSITION || pos >= apps.size()
                    || apps.get(pos).activityInfo.packageName.equals(carriedPackage);
            card.setAlpha(carried ? 1f : 0.45f);
        }
    }

    private void addAction(LinearLayout rows, int label, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(label);
        row.setFocusableInTouchMode(true);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
        row.setOnKeyListener((v, key, event) -> {
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_FIRST_STRONG);
        view.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(activity, R.string.font_medium));
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private final class Catalog extends RecyclerView.Adapter<Row> {

        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(132), dp(170));
            params.setMargins(dp(6), dp(2), dp(6), dp(2));
            row.setLayoutParams(params);
            row.setPadding(dp(10), dp(12), dp(10), dp(10));
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView name = text("", 15, Color.WHITE);
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(44));
            nameParams.topMargin = dp(10);
            content.addView(name, nameParams);
            TextView status = text("", 11, 0xBBFFFFFF);
            status.setId(R.id.status);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            content.addView(status, new LinearLayout.LayoutParams(-1, -2));
            name.setEllipsize(TextUtils.TruncateAt.END);
            // One background per holder: setting it again on every bind allocates a drawable and re-lays the row.
            row.setBackground(surface(0x0CFFFFFF, 18));
            final Row holder = new Row(row, icon, name, status);
            // Built once per holder: the set was allocated on every bind before. They sit on the card inside
            // the row, which holds the focus, while the row still takes a touch tap.
            final View.OnClickListener tap = v -> {
                if (carriedPackage != null) return;   // Move keeps the card: no opening or adding while carrying it
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                ResolveInfo app = apps.get(pos);
                boolean onHomeNow = onHome.test(app.activityInfo.packageName);
                if (LibraryApps.okOpens(selectionMode, okOpens, onHomeNow)) open(app);
                else changeHome(app, !onHomeNow);
            };
            row.setOnClickListener(tap);
            row.setOnLongClickListener(selectionMode ? null : v -> {
                if (carriedPackage != null) return true;   // no second menu while a card is being carried
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                showActions(apps.get(pos), v);
                return true;
            });
            row.setOnFocusChangeListener((v, focused) -> {
                if (!focused) return;
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                String pkg = apps.get(pos).activityInfo.packageName;
                if (carriedPackage != null && !pkg.equals(carriedPackage)) {
                    // Move keeps the picked-up card; any focus that lands elsewhere goes back to it.
                    v.post(AppLibraryDialog.this::settleCarriedAtSelection);
                    return;
                }
                selectedPackage = pkg;
                setHint(onHome.test(pkg));
                // The glass frames the whole card, so it takes the row rather than the view holding the focus.
                v.post(() -> { if (v.hasFocus() && catalog != null) highlight(holder.itemView, true); });
                selectedPosition = pos;
            });
            row.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int index = holder.getBindingAdapterPosition();
                if (carriedPackage != null) {
                    // RTL lays the catalogue out right to left, so the arrows swap the other way round.
                    if (key == KeyEvent.KEYCODE_DPAD_LEFT) { moveCarried(rtl ? 1 : -1); return true; }
                    if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { moveCarried(rtl ? -1 : 1); return true; }
                    if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                            || key == KeyEvent.KEYCODE_NUMPAD_ENTER) { endMove(); return true; }
                    if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) { endMove(); return true; }
                    return true;   // Up, Down and the rest stay put while a card is being carried
                }
                return (key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT) && index == 0
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT) && index == apps.size() - 1);
            });
            return holder;
        }
        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = apps.get(position);
            String pkg = app.activityInfo.packageName;
            holder.itemView.setAlpha(carriedPackage == null || pkg.equals(carriedPackage) ? 1f : 0.45f);
            bindIcon(holder.icon, app);
            // Every card says where it stands: press adds it to Home, hold offers the app's menu.
            int status = !selectionMode && recentPackages.contains(pkg) ? R.string.library_recent : R.string.library_add_home;
            String title = label(app);
            // Move rebinds every card on each arrow, and setText re-lays the row out: set only what changed.
            if (!title.contentEquals(holder.title.getText())) holder.title.setText(title);
            if (holder.statusRes != status) {
                holder.statusRes = status;
                holder.status.setText(status);
            }
        }
        @Override public int getItemCount() { return apps.size(); }
    }

    /**
     * The catalogue's window. A Dialog rather than a PopupWindow: a popup closes itself on Back before the
     * card under the focus can answer, while a dialog's Back is ours to answer, so Move ends and the catalogue
     * stays. No title, no animation, transparent background: the panel supplies the glass, and nothing dims
     * Home behind it.
     */
    private final class LibraryWindow extends Dialog {
        private int width;
        private int height;
        /** The panel: it needs an exact height of its own, or the weighted shelf inside it collapses. */
        private final View content;

        LibraryWindow(View content, int width, int height) {
            super(activity, R.style.Theme_LibraryDialog);
            this.content = content;
            this.width = width;
            this.height = height;
            // The dialog theme still lays out a (empty) title bar over the content: asking for its height in dp
            // would then cut the cards' bottom line. No title, and the panel gets the whole window.
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            // FrameLayout.LayoutParams: the dialog's content is a FrameLayout, which measures children
            // with margins; a plain LayoutParams there throws ClassCastException.
            setContentView(content, new FrameLayout.LayoutParams(width, height));
            setCancelable(true);
            setCanceledOnTouchOutside(true);
            Window window = getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setWindowAnimations(0);
            window.setGravity(Gravity.CENTER);
        }

        void size(int width, int height) {
            if (this.width == width && this.height == height) return;   // the fill asks for the same box
            this.width = width;
            this.height = height;
            content.setLayoutParams(new FrameLayout.LayoutParams(width, height));
            if (isShowing()) layout();
        }

        /** Shows first, then sizes: a dialog only keeps setLayout once its window is up. */
        void open() {
            show();
            layout();
        }

        private void layout() {
            Window window = getWindow();
            if (window == null) return;
            // One params write for size, place and dim alike: the theme's backgroundDimEnabled only reaches the
            // window when it is added, i.e. in show(), so the flag is dropped here too and Home stays undimmed
            // even on a build that ignores the theme attribute.
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = width;
            lp.height = height;
            lp.gravity = Gravity.CENTER;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = 0f;
            window.setAttributes(lp);
        }

        /** Back puts the carried card down and leaves the catalogue open; otherwise it closes as usual. */
        @Override public void onBackPressed() {
            if (carriedPackage != null) {
                endMove();
                return;
            }
            super.onBackPressed();
        }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView status;
        /** The status string on screen, as a resource id: a rebind skips setText when it is the same one. */
        int statusRes;

        Row(View view, ImageView icon, TextView title, TextView status) {
            super(view);
            this.icon = icon;
            this.title = title;
            this.status = status;
        }
    }
}
