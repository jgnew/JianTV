package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.ChangedPackages;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.tv.TvInputInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.UserManager;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The calls the launcher makes that only arrived after minSdk 23. Each keeps its original path from API 24 or 26 up and
 * does what 6.0 can below, so a build for 6.0 is the same code on the TV this was written on (API 28).
 */
final class ApiCompat {
    private ApiCompat() {}

    /** Direct boot, and with it the locked-then-unlocked user, arrived with API 24; before that a user is unlocked. */
    static boolean userUnlocked(Context context) {
        if (Build.VERSION.SDK_INT >= 24) {
            UserManager users = context.getSystemService(UserManager.class);
            return users == null || users.isUserUnlocked();
        }
        return true;
    }

    /** Leaves the accessibility service: 7.0 up can drop it, 6.0 keeps it bound until the user turns it off. */
    static void stepOut(AccessibilityService service) {
        if (Build.VERSION.SDK_INT >= 24) service.disableSelf();
    }

    /** Views drawn as one bitmap: without this every fade renders through a full-screen offscreen buffer first. */
    static void noOverlappingRendering(View view) {
        if (Build.VERSION.SDK_INT >= 24) view.forceHasOverlappingRendering(false);
    }

    /** The network in use: 6.0 has no default-network callback, so any network that reports internet does. */
    static void registerDefaultNetwork(ConnectivityManager manager, ConnectivityManager.NetworkCallback callback) {
        if (Build.VERSION.SDK_INT >= 24) {
            manager.registerDefaultNetworkCallback(callback);
            return;
        }
        manager.registerNetworkCallback(new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback);
    }

    /** The first UI locale: 6.0 keeps the one in configuration.locale. */
    @SuppressWarnings("deprecation")
    static Locale firstLocale(Configuration configuration) {
        if (Build.VERSION.SDK_INT >= 24) return configuration.getLocales().get(0);
        return configuration.locale;
    }

    /** Inputs hidden in Settings > Inputs: that setting arrived with 7.0, so 6.0 hides nothing. */
    static boolean inputHidden(TvInputInfo info, Context context) {
        return Build.VERSION.SDK_INT >= 24 && info.isHidden(context);
    }

    /** The name given to an input in Settings > Inputs (7.0 up); on 6.0 the input keeps its own label. */
    static CharSequence customLabel(TvInputInfo info, Context context) {
        return Build.VERSION.SDK_INT >= 24 ? info.loadCustomLabel(context) : null;
    }

    /** PackageManager's own change sequence (8.0 up); before that the install broadcasts alone mark the tiles stale. */
    static boolean packagesChanged(PackageManager manager, int since) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                ChangedPackages changed = manager.getChangedPackages(since);
                return changed != null && !changed.getPackageNames().isEmpty();
            } catch (LinkageError early) {
                // A 6.0 ART that resolves the call while verifying: no sequence to consult, the broadcasts carry it.
                return false;
            }
        }
        return false;
    }

    static int packageSequence(PackageManager manager, int since) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                ChangedPackages changed = manager.getChangedPackages(since);
                return changed != null ? changed.getSequenceNumber() : since;
            } catch (LinkageError early) {
                return since;
            }
        }
        return since;
    }

    /** java.nio.file arrived with 8.0: read the file the way 6.0 can, as UTF-8. */
    static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    /** A bitmap the GPU keeps in graphics memory (8.0 up); null below, where the caller keeps the ARGB_8888 copy. */
    static Bitmap hardwareBitmap(Bitmap bitmap) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return bitmap.copy(Bitmap.Config.HARDWARE, false);
            } catch (LinkageError early) {
                return null;
            }
        }
        return null;
    }
}
