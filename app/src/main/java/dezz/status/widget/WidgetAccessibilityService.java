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
            Pattern.compile("mCurrentFocus=Window\\{[0-9a-fA-F]+\\s+u\\d+\\s+([^}]+)\\}");
    /**
     * Fallback #1 for firmware that doesn't print {@code mCurrentFocus} at all (confirmed on
     * one Cityray build — see git history). "The window currently obscuring the others" is,
     * in practice, the topmost visible one.
     */
    private static final Pattern OBSCURING_WINDOW_PATTERN =
            Pattern.compile("mObscuringWindow=Window\\{[0-9a-fA-F]+\\s+u\\d+\\s+([^}]+)\\}");
    /**
     * Fallback #2: the window currently receiving IME input. Not a perfect proxy for "topmost
     * visible window" (it can lag behind if nothing has focused a text field recently), but
     * better than nothing when neither of the above is present.
     */
    private static final Pattern INPUT_TARGET_PATTERN =
            Pattern.compile("mInputMethodInputTarget in display# \\d+ Window\\{[0-9a-fA-F]+\\s+u\\d+\\s+([^}]+)\\}");
    private static final Pattern WINDOW_HEADER_PATTERN =
            Pattern.compile("Window #\\d+ Window\\{[0-9a-fA-F]+\\s+u\\d+\\s+([^}]+)\\}:");
    private static final Pattern SYSTEM_UI_VIS_PATTERN =
            Pattern.compile("mSystemUiVisibility=0x([0-9a-fA-F]+)");

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
        Log.i(TAG, "fetchAndParseWindowIconMode: requesting dumpsys");
        PrivilegedShell.get(this).runCommand("dumpsys window windows", (output, error) -> {
            if (output == null) {
                Log.w(TAG, "fetchAndParseWindowIconMode: no output, error=" + error);
                return;
            }
            int mode = parseWindowIconMode(output);
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
     * Output format is not strictly standardised across AOSP versions/vendors, and confirmed
     * on a real Cityray build to NOT print {@code mCurrentFocus=} at all. Strategy, in order:
     * <ol>
     *   <li>{@code mCurrentFocus=Window{... title}} — the textbook AOSP field, kept first in
     *       case some firmware still prints it.</li>
     *   <li>{@code mObscuringWindow=Window{... title}} — confirmed present and correct on the
     *       Cityray build that's missing #1: "the window currently obscuring the others" is,
     *       in practice, the topmost visible one.</li>
     *   <li>{@code mInputMethodInputTarget in display# N Window{... title}} — the window
     *       currently receiving IME input. Less precise (can lag if nothing has focused a
     *       text field recently) but a workable last resort.</li>
     * </ol>
     * Whichever title is found is matched against the {@code Window #N Window{... title}:}
     * per-window sections earlier in the dump, and {@code mSystemUiVisibility} is read from
     * inside that specific block. If no title source matches at all, fall back to the last
     * {@code mSystemUiVisibility} value in the whole dump — windows are listed back-to-front
     * on every build seen so far, so the topmost one tends to be last.
     * <p>
     * If this stops matching on a given firmware, log the raw {@code output} once to see the
     * actual layout and adjust the patterns above.
     */
    private static int parseWindowIconMode(String output) {
        List<String> headerTitles = new java.util.ArrayList<>();
        List<int[]> headerSpans = new java.util.ArrayList<>(); // [matchStart, matchEnd]
        Matcher headerMatcher = WINDOW_HEADER_PATTERN.matcher(output);
        while (headerMatcher.find()) {
            headerTitles.add(headerMatcher.group(1));
            headerSpans.add(new int[]{headerMatcher.start(), headerMatcher.end()});
        }

        Matcher focusMatcher = CURRENT_FOCUS_PATTERN.matcher(output);
        String focusTitle = focusMatcher.find() ? focusMatcher.group(1) : null;
        if (focusTitle == null) {
            Matcher obscuringMatcher = OBSCURING_WINDOW_PATTERN.matcher(output);
            focusTitle = obscuringMatcher.find() ? obscuringMatcher.group(1) : null;
        }
        if (focusTitle == null) {
            Matcher inputTargetMatcher = INPUT_TARGET_PATTERN.matcher(output);
            focusTitle = inputTargetMatcher.find() ? inputTargetMatcher.group(1) : null;
        }

        Integer visibility = null;
        if (focusTitle != null) {
            for (int i = 0; i < headerTitles.size(); i++) {
                if (!focusTitle.equals(headerTitles.get(i))) continue;
                int blockStart = headerSpans.get(i)[1];
                int blockEnd = (i + 1 < headerSpans.size()) ? headerSpans.get(i + 1)[0] : output.length();
                String block = output.substring(blockStart, Math.min(blockEnd, output.length()));
                Matcher visMatcher = SYSTEM_UI_VIS_PATTERN.matcher(block);
                if (visMatcher.find()) {
                    visibility = Integer.parseInt(visMatcher.group(1), 16);
                }
                break;
            }
        }

        if (visibility == null) {
            Matcher visMatcher = SYSTEM_UI_VIS_PATTERN.matcher(output);
            int last = -1;
            while (visMatcher.find()) {
                last = Integer.parseInt(visMatcher.group(1), 16);
            }
            if (last == -1) {
                return -1;
            }
            visibility = last;
        }

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
