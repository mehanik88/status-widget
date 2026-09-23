/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dezz.status.widget.shell.PrivilegedShell;

/**
 * Accessibility service whose sole purpose is to report, for each physical display, which
 * app package is currently in the foreground there. Needed because Geely Monjaro head units
 * run 4 displays in parallel: a user-app switch on display 2 must not change overlay
 * visibility on display 1 (and vice versa), but {@link android.app.usage.UsageStatsManager}
 * doesn't expose display IDs — its events are global. {@link AccessibilityWindowInfo} does.
 * <p>
 * The service is intentionally a thin reporter: it tracks the active package per display
 * and notifies the {@link WidgetService} singleton, which decides what to do based on the
 * display its own overlay window lives on. Disabling this accessibility service falls back
 * to the original single-display behaviour via {@link android.app.usage.UsageStatsManager}.
 */
public class WidgetAccessibilityService extends AccessibilityService {
    private static final String TAG = "WidgetA11yService";

    /** Debounce window for {@link #fetchAndParseWindowIconMode()} — TYPE_WINDOWS_CHANGED can
     * fire in quick bursts (scrolling, keyboard) and dumpsys is relatively heavy to run on
     * every single one. */
    private static final long ICON_MODE_DEBOUNCE_MS = 150L;

    private static final Pattern CURRENT_FOCUS_PATTERN =
            Pattern.compile("mCurrentFocus=Window\\{([0-9a-fA-F]+)\\s+u\\d+\\s+[^}]+\\}");
    /**
     * Fallback #1 for firmware that doesn't print {@code mCurrentFocus} at all (confirmed on
     * one Cityray build — see git history). "The window currently obscuring the others" is,
     * in practice, the topmost visible one.
     */
    private static final Pattern OBSCURING_WINDOW_PATTERN =
            Pattern.compile("mObscuringWindow=Window\\{([0-9a-fA-F]+)\\s+u\\d+\\s+[^}]+\\}");
    /**
     * Fallback #2: the window currently receiving IME input. Not a perfect proxy for "topmost
     * visible window" (it can lag behind if nothing has focused a text field recently), but
     * better than nothing when neither of the above is present.
     */
    private static final Pattern INPUT_TARGET_PATTERN =
            Pattern.compile("mInputMethodInputTarget in display# \\d+ Window\\{([0-9a-fA-F]+)\\s+u\\d+\\s+[^}]+\\}");
    /**
     * Captures the window's identity hash — NOT its title. Several system windows share the
     * exact same title (e.g. multiple windows named plain "android" — confirmed in a real
     * dump), so matching by title can silently pick the wrong block; the hash is unique per
     * window instance and always matches the one in {@link #CURRENT_FOCUS_PATTERN} et al. for
     * the same window.
     */
    private static final Pattern WINDOW_HEADER_PATTERN =
            Pattern.compile("Window #\\d+ Window\\{([0-9a-fA-F]+)\\s+u\\d+\\s+[^}]+\\}:");
    private static final Pattern SYSTEM_UI_VIS_PATTERN =
            Pattern.compile("mSystemUiVisibility=0x([0-9a-fA-F]+)");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("package=(\\S+)");
    private static final Pattern DISPLAY_ID_PATTERN =
            Pattern.compile("mDisplayId=(\\d+)");

    @Nullable
    private static volatile WidgetAccessibilityService instance;

    /** displayId → current foreground package. Updated on every window change event. */
    private final Map<Integer, String> foregroundByDisplay = new HashMap<>();

    private final android.os.Handler debounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable dumpWindowIconModeRunnable = this::fetchAndParseWindowIconMode;

    /**
     * -1 = unknown (no data yet, or parsing failed), 0 = neutral, 1 = light background,
     * 2 = dark background. Same three values
     * {@code com.geely.systemui.plugin.statusbar.StatusBarView#setIconMode} uses, read from
     * the currently-focused window's {@code systemUiVisibility} flags — this is the
     * per-window override the stock status bar applies on top of the wallpaper-luminance
     * default (see {@link WidgetService}'s "follow wallpaper" theme mode).
     */
    private volatile int currentWindowIconMode = -1;

    public int getCurrentWindowIconMode() {
        return currentWindowIconMode;
    }

    @Nullable
    public static WidgetAccessibilityService getInstance() {
        return instance;
    }

    /**
     * @param displayId numeric display ID (matches {@link android.view.Display#getDisplayId()}).
     * @return foreground package on that display, or {@code null} if we haven't seen one
     *         (display absent / no window event observed yet).
     */
    @Nullable
    public String getForegroundPackageOnDisplay(int displayId) {
        synchronized (foregroundByDisplay) {
            return foregroundByDisplay.get(displayId);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        debounceHandler.removeCallbacks(dumpWindowIconModeRunnable);
        currentWindowIconMode = -1;
        synchronized (foregroundByDisplay) {
            foregroundByDisplay.clear();
        }
        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            // Falling out of accessibility-driven tracking — let WidgetService refresh the
            // tracking pipeline (which falls back to UsageStatsManager polling).
            widget.onForegroundTrackingPathChanged();
        }
        super.onDestroy();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Initial seed: walk all windows currently known to the accessibility framework and
        // remember the per-display foreground packages. Otherwise the first event-driven
        // update would have to wait for a real window change.
        seedFromCurrentWindows();
        Log.i(TAG, "Connected. Seeded " + foregroundByDisplay.size() + " display(s).");
        fetchAndParseWindowIconMode();
        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            widget.onForegroundTrackingPathChanged();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        Log.i(TAG, "onAccessibilityEvent: type=" + AccessibilityEvent.eventTypeToString(type)
                + " pkg=" + event.getPackageName());
        // After any window state change, re-scan: the event itself carries one package, but
        // we want a coherent snapshot of every display, not just the one that changed. Cheap
        // — there are typically only a handful of accessibility windows in total.
        seedFromCurrentWindows();

        // Debounced: dumpsys is comparatively heavy, and bursts of TYPE_WINDOWS_CHANGED
        // (scrolling, keyboard) would otherwise trigger it far more often than the icon
        // mode could plausibly have changed.
        debounceHandler.removeCallbacks(dumpWindowIconModeRunnable);
        debounceHandler.postDelayed(dumpWindowIconModeRunnable, ICON_MODE_DEBOUNCE_MS);

        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            widget.onForegroundDisplayMapUpdated();
        }
    }

    /**
     * Runs {@code dumpsys window windows} through the same privileged shell channel used
     * elsewhere in the app and parses out the focused window's systemUiVisibility flags. See
     * {@link #parseWindowIconMode} for the parsing strategy and its caveats.
     */
    private void fetchAndParseWindowIconMode() {
        WidgetService widgetForDisplay = WidgetService.getInstance();
        int displayId = widgetForDisplay != null
                ? widgetForDisplay.currentOverlayDisplayId()
                : android.view.Display.DEFAULT_DISPLAY;
        String foregroundPkg = getTopVisiblePackageForIconMode(displayId);
        Log.i(TAG, "fetchAndParseWindowIconMode: requesting dumpsys, displayId=" + displayId
                + ", foregroundPkg=" + foregroundPkg);
        PrivilegedShell.get(this).runCommand("dumpsys window windows", (output, error) -> {
            if (output == null) {
                Log.w(TAG, "fetchAndParseWindowIconMode: no output, error=" + error);
                return;
            }
            int mode = parseWindowIconMode(output, foregroundPkg, displayId);
            Log.i(TAG, "fetchAndParseWindowIconMode: parsed mode=" + mode
                    + " (previous=" + currentWindowIconMode + "), output length=" + output.length());
            if (mode != currentWindowIconMode) {
                currentWindowIconMode = mode;
                WidgetService widget = WidgetService.getInstance();
                Log.i(TAG, "fetchAndParseWindowIconMode: mode changed, widget instance="
                        + (widget != null));
                if (widget != null) {
                    widget.onWindowIconModeUpdated();
                }
            }
        });
    }

    /**
     * Parses {@code dumpsys window windows} output for the systemUiVisibility flags of the
     * currently focused/topmost window — the same two bits (0x2000 = light, 0x4000 = dark)
     * that {@code com.geely.systemui.plugin.statusbar.StatusBarView#onWindowChange} reads to
     * decide whether *its own* icons should be light or dark for whatever app is currently on
     * screen.
     * <p>
     * Primary strategy: use {@code foregroundPkg} (from this service's own
     * accessibility-derived {@link #foregroundByDisplay}, already relied on elsewhere and
     * confirmed reliable) to find the window block for that package that is actually
     * {@code isOnScreen=true}/{@code isVisible=true} right now, and read
     * {@code mSystemUiVisibility} from inside it.
     * <p>
     * Fallback, for when {@code foregroundPkg} is unknown (e.g. first run before any window
     * event): the same hash-based lookup as before
     * ({@code mCurrentFocus}/{@code mObscuringWindow}/{@code mInputMethodInputTarget}
     * matched against {@code Window #N Window{hash u0 ...}:} headers by identity hash, never
     * by title — several windows can legitimately share the same title, e.g. multiple windows
     * plainly named "android"). Confirmed on a real Cityray build that {@code mCurrentFocus} is
     * absent entirely and {@code mObscuringWindow} can stay pinned to a stale window hash
     * indefinitely — this fallback is a second opinion, not the primary source of truth
     * anymore.
     * <p>
     * Last resort if nothing above matches: the last {@code mSystemUiVisibility} value in the
     * whole dump — windows are listed back-to-front on every build seen so far, so the topmost
     * one tends to be last.
     * <p>
     * If this stops matching on a given firmware, the {@code matchedVia}/{@code visibility}
     * log line shows exactly which strategy fired and what it read.
     */
    private static int parseWindowIconMode(String output, @Nullable String foregroundPkg, int displayId) {
        List<String> headerHashes = new java.util.ArrayList<>();
        List<int[]> headerSpans = new java.util.ArrayList<>(); // [matchStart, matchEnd]
        Matcher headerMatcher = WINDOW_HEADER_PATTERN.matcher(output);
        while (headerMatcher.find()) {
            headerHashes.add(headerMatcher.group(1));
            headerSpans.add(new int[]{headerMatcher.start(), headerMatcher.end()});
        }

        Integer visibility = null;
        String matchedVia = null;

        // Primary strategy: find the window block that (a) belongs to the foreground package
        // — taken from the independently-verified, already-working accessibility-derived
        // foregroundByDisplay map, NOT from any of the dumpsys text fields below, which have
        // been confirmed unreliable on at least one real Cityray build (mObscuringWindow can
        // stay pinned to a stale window hash indefinitely) — and (b) is actually on screen and
        // visible right now, not just any window belonging to that package (an app can have
        // several: a backgrounded previous activity, a notification-listener window, etc).
        if (foregroundPkg != null) {
            for (int i = 0; i < headerSpans.size(); i++) {
                int blockStart = headerSpans.get(i)[1];
                int blockEnd = (i + 1 < headerSpans.size()) ? headerSpans.get(i + 1)[0] : output.length();
                String block = output.substring(blockStart, Math.min(blockEnd, output.length()));
                Matcher pkgMatcher = PACKAGE_PATTERN.matcher(block);
                if (!pkgMatcher.find() || !foregroundPkg.equals(pkgMatcher.group(1))) continue;
                Matcher displayMatcher = DISPLAY_ID_PATTERN.matcher(block);
                // Multi-display head units can run the same package as separate task
                // instances on different displays at once — without this check we could match
                // the right package on the WRONG display and read that display's flags instead.
                if (displayMatcher.find() && Integer.parseInt(displayMatcher.group(1)) != displayId) continue;
                if (!block.contains("isOnScreen=true") || !block.contains("isVisible=true")) continue;
                Matcher visMatcher = SYSTEM_UI_VIS_PATTERN.matcher(block);
                if (visMatcher.find()) {
                    visibility = Integer.parseInt(visMatcher.group(1), 16);
                    matchedVia = "foregroundPkg=" + foregroundPkg;
                    break;
                }
            }
        }

        // Fallback: the hash-based lookups (mCurrentFocus / mObscuringWindow /
        // mInputMethodInputTarget). Kept as a second opinion for cases the primary strategy
        // above can't cover (foregroundPkg not known yet on first run, etc).
        if (visibility == null) {
            Matcher focusMatcher = CURRENT_FOCUS_PATTERN.matcher(output);
            String focusHash = focusMatcher.find() ? focusMatcher.group(1) : null;
            if (focusHash == null) {
                Matcher obscuringMatcher = OBSCURING_WINDOW_PATTERN.matcher(output);
                focusHash = obscuringMatcher.find() ? obscuringMatcher.group(1) : null;
            }
            if (focusHash == null) {
                Matcher inputTargetMatcher = INPUT_TARGET_PATTERN.matcher(output);
                focusHash = inputTargetMatcher.find() ? inputTargetMatcher.group(1) : null;
            }
            if (focusHash != null) {
                for (int i = 0; i < headerHashes.size(); i++) {
                    if (!focusHash.equals(headerHashes.get(i))) continue;
                    int blockStart = headerSpans.get(i)[1];
                    int blockEnd = (i + 1 < headerSpans.size()) ? headerSpans.get(i + 1)[0] : output.length();
                    String block = output.substring(blockStart, Math.min(blockEnd, output.length()));
                    Matcher visMatcher = SYSTEM_UI_VIS_PATTERN.matcher(block);
                    if (visMatcher.find()) {
                        visibility = Integer.parseInt(visMatcher.group(1), 16);
                        matchedVia = "hash=" + focusHash;
                    }
                    break;
                }
            }
        }

        // Last resort: last mSystemUiVisibility value in the whole dump — windows are listed
        // back-to-front on every build seen so far, so the topmost one tends to be last.
        if (visibility == null) {
            Matcher visMatcher = SYSTEM_UI_VIS_PATTERN.matcher(output);
            int last = -1;
            while (visMatcher.find()) {
                last = Integer.parseInt(visMatcher.group(1), 16);
            }
            if (last == -1) {
                Log.i(TAG, "parseWindowIconMode: no match at all (foregroundPkg=" + foregroundPkg + ")");
                return -1;
            }
            visibility = last;
            matchedVia = "last-in-dump";
        }

        Log.i(TAG, "parseWindowIconMode: matchedVia=" + matchedVia + " visibility=0x"
                + Integer.toHexString(visibility));
        boolean isLight = (visibility & 0x2000) != 0; // View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        boolean isDark = (visibility & 0x4000) != 0;  // vendor dark-status-bar bit
        if (isLight) return 1;
        if (isDark) return 2;
        return 0;
    }

    @Override
    public void onInterrupt() {
        // No-op — we don't drive any feedback streams.
    }

    /**
     * Refreshes {@link #foregroundByDisplay} from the live list of accessibility windows.
     * <p>
     * On API 30+ we use {@link #getWindowsOnAllDisplays()} which returns a
     * {@code SparseArray<List<AccessibilityWindowInfo>>} keyed by display ID. Below that we
     * fall back to {@link #getWindows()} (single-display only) — the per-display behaviour
     * matters only on multi-display devices, which all run Android 10+/Auto so this fallback
     * is just for completeness.
     */
    private void seedFromCurrentWindows() {
        Map<Integer, String> next = new HashMap<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            for (int i = 0; i < all.size(); i++) {
                int displayId = all.keyAt(i);
                String pkg = topApplicationPackage(all.valueAt(i));
                if (pkg != null) next.put(displayId, pkg);
            }
        } else {
            List<AccessibilityWindowInfo> windows = getWindows();
            String pkg = topApplicationPackage(windows);
            if (pkg != null) next.put(android.view.Display.DEFAULT_DISPLAY, pkg);
        }
        synchronized (foregroundByDisplay) {
            foregroundByDisplay.clear();
            foregroundByDisplay.putAll(next);
        }
    }

    /**
     * Broader than {@link #topApplicationPackage}: also considers
     * {@code TYPE_APPLICATION_OVERLAY} windows — launcher-style menu overlays (confirmed:
     * GInputBridge's own app drawer draws itself this way, same pattern this app's own status
     * bar overlay uses) are invisible to the stricter {@code TYPE_APPLICATION}-only filter used
     * for foreground-app tracking elsewhere, so while such an overlay is on screen this method
     * would otherwise keep reporting whatever real Activity is still running underneath it —
     * which is exactly what caused icon-mode detection to silently use a stale/wrong package.
     * The real system status bar doesn't have this blind spot (it reacts to whatever is
     * topmost regardless of window type), so this method exists specifically to match that.
     * <p>
     * Excludes this app's own package so we never mistake our own status-bar overlay window for
     * the foreground content.
     */
    @Nullable
    private String topVisibleWindowPackageForIconMode(@Nullable List<AccessibilityWindowInfo> windows) {
        if (windows == null) return null;
        String ownPackage = getPackageName();
        AccessibilityWindowInfo best = null;
        int bestLayer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            int type = w.getType();
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION
                    && type != AccessibilityWindowInfo.TYPE_APPLICATION_OVERLAY) continue;
            int layer = w.getLayer();
            if (layer <= bestLayer) continue;
            android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            try {
                CharSequence pkg = root.getPackageName();
                if (pkg == null || ownPackage.equals(pkg.toString())) continue;
                bestLayer = layer;
                best = w;
            } finally {
                root.recycle();
            }
        }
        if (best == null) return null;
        android.view.accessibility.AccessibilityNodeInfo root = best.getRoot();
        if (root == null) return null;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } finally {
            root.recycle();
        }
    }

    /**
     * Same display-selection logic as {@link #seedFromCurrentWindows} (API 30+ per-display via
     * {@link #getWindowsOnAllDisplays()}, older API single-display via {@link #getWindows()}),
     * but using {@link #topVisibleWindowPackageForIconMode} instead of the stricter
     * {@link #topApplicationPackage} — see that method's javadoc for why.
     */
    @Nullable
    private String getTopVisiblePackageForIconMode(int displayId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.util.SparseArray<List<AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            return topVisibleWindowPackageForIconMode(all.get(displayId));
        }
        return topVisibleWindowPackageForIconMode(getWindows());
    }

    /**
     * Picks the topmost application window (not system / IME / accessibility overlay) from a
     * list of {@link AccessibilityWindowInfo}, and returns its package name via the root
     * AccessibilityNodeInfo. Higher layer = newer, so we walk in descending z-order.
     */
    @Nullable
    private static String topApplicationPackage(@Nullable List<AccessibilityWindowInfo> windows) {
        if (windows == null) return null;
        AccessibilityWindowInfo best = null;
        int bestLayer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            int layer = w.getLayer();
            if (layer > bestLayer) {
                bestLayer = layer;
                best = w;
            }
        }
        if (best == null) return null;
        android.view.accessibility.AccessibilityNodeInfo root = best.getRoot();
        if (root == null) return null;
        try {
            CharSequence pkg = root.getPackageName();
            return pkg == null ? null : pkg.toString();
        } finally {
            root.recycle();
        }
    }
}
