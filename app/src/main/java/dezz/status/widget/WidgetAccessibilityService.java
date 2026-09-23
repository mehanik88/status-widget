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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.shell.PrivilegedShell;

/**
 * Accessibility service with two independent jobs:
 * <p>
 * 1) Report, for each physical display, which app package is currently in the foreground
 * there. Needed because Geely Monjaro head units run 4 displays in parallel: a user-app switch
 * on display 2 must not change overlay visibility on display 1 (and vice versa), but
 * {@link android.app.usage.UsageStatsManager} doesn't expose display IDs — its events are
 * global. {@link AccessibilityWindowInfo} does. Disabling this accessibility service falls
 * back to the original single-display behaviour via {@link android.app.usage.UsageStatsManager}.
 * <p>
 * 2) Drive the "follow wallpaper" window-icon-mode override for {@link WidgetService}: instead
 * of trying to independently re-derive which window is "really" on top and what color the
 * stock status bar decided for it (both proved unreliable across firmware quirks — see git
 * history: absent {@code mCurrentFocus}, stale {@code mObscuringWindow}, non-existent
 * {@code AccessibilityWindowInfo.TYPE_APPLICATION_OVERLAY}, overlay windows invisible to
 * foreground-app tracking...), this simply screenshots the exact pixel region where a stock
 * status bar icon (Bluetooth, chosen because it's always present) is drawn and reads its actual
 * rendered color directly. Whatever produced that color — wallpaper luminance, per-window
 * flags, some other mechanism entirely — doesn't matter: we copy the real, already-correct
 * result instead of re-deriving it.
 */
public class WidgetAccessibilityService extends AccessibilityService {
    private static final String TAG = "WidgetA11yService";

    /** Debounce window for {@link #captureAndSampleIconColor()} — TYPE_WINDOWS_CHANGED can
     * fire in quick bursts (scrolling, keyboard) and a screencap + decode is relatively heavy
     * to do on every single one. */
    private static final long ICON_MODE_DEBOUNCE_MS = 150L;

    /**
     * Probe rectangle over the stock Bluetooth status bar icon, in real device pixels
     * (1440x1920 panel — confirmed by the user from an actual screenshot, not a photo, so no
     * perspective distortion). Bluetooth was chosen because it's always present regardless of
     * connection/signal state (unlike the Wi-Fi/GPS icons, which can visually change shape).
     * <p>
     * CALIBRATION NOTE: estimated from a screenshot, not pixel-measured precisely. If
     * {@code sampledLuminance} in the logs looks implausible (e.g. stuck at a mid-gray value
     * that never moves regardless of visible icon color), this rectangle is probably
     * off-target — see the log line for the actual color sampled and adjust these four
     * constants; a quick way to verify is cropping {@code probe.png} (kept after each capture)
     * to this rectangle and looking at it.
     */
    private static final int PROBE_LEFT = 1258;
    private static final int PROBE_TOP = 32;
    private static final int PROBE_RIGHT = 1282;
    private static final int PROBE_BOTTOM = 56;

    /** Below this average luminance (0-255) the sampled icon reads as "dark" (drawn dark-on-
     * light, i.e. the background behind it is light) → mode 1. Above it, "light" (drawn
     * light-on-dark) → mode 2. */
    private static final int LUMINANCE_THRESHOLD = 128;

    @Nullable
    private static volatile WidgetAccessibilityService instance;

    /** displayId → current foreground package. Updated on every window change event. Used only
     * by the (unrelated) per-app widget-visibility feature — NOT by icon-mode detection. */
    private final Map<Integer, String> foregroundByDisplay = new HashMap<>();

    private final android.os.Handler debounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable captureIconColorRunnable = this::captureAndSampleIconColor;

    /**
     * -1 = unknown (no sample yet, or capture/decode failed), 0 = neutral (unused by the
     * pixel-sampling approach — kept for API compatibility with {@link WidgetService}), 1 =
     * light background (dark icon sampled), 2 = dark background (light icon sampled).
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
        debounceHandler.removeCallbacks(captureIconColorRunnable);
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
        captureAndSampleIconColor();
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

        // Debounced: a screencap+decode is comparatively heavy, and bursts of
        // TYPE_WINDOWS_CHANGED (scrolling, keyboard) would otherwise trigger it far more often
        // than the on-screen icon color could plausibly have changed.
        debounceHandler.removeCallbacks(captureIconColorRunnable);
        debounceHandler.postDelayed(captureIconColorRunnable, ICON_MODE_DEBOUNCE_MS);

        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            widget.onForegroundDisplayMapUpdated();
        }
    }

    /**
     * Screenshots the whole panel via the privileged shell channel, decodes just the probe
     * rectangle over the stock Bluetooth icon, and derives light/dark mode from its actual
     * rendered color. See the class javadoc for why this replaced the earlier
     * dumpsys-window-parsing approach.
     */
    private void captureAndSampleIconColor() {
        File externalDir = getExternalFilesDir(null);
        if (externalDir == null) {
            Log.w(TAG, "captureAndSampleIconColor: getExternalFilesDir(null) returned null, skipping");
            return;
        }
        File probeFile = new File(externalDir, "probe.png");
        String path = probeFile.getAbsolutePath();
        // Written via the app's own external files dir: the root shell (running as a different
        // UID over telnet) can write there freely, and this app can always read its own
        // external files dir back with zero storage permissions, regardless of scoped-storage
        // rules on newer Android versions — sidesteps needing any binary transfer over the
        // (text-oriented) telnet shell channel entirely.
        String cmd = "screencap -p " + path;
        Log.i(TAG, "captureAndSampleIconColor: requesting screencap to " + path);
        PrivilegedShell.get(this).runCommand(cmd, (output, error) -> {
            if (!probeFile.exists() || probeFile.length() == 0) {
                Log.w(TAG, "captureAndSampleIconColor: probe file missing/empty, error=" + error);
                return;
            }
            Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp == null) {
                Log.w(TAG, "captureAndSampleIconColor: BitmapFactory.decodeFile failed for " + path);
                return;
            }
            int mode;
            try {
                mode = sampleIconMode(bmp);
            } finally {
                bmp.recycle();
            }
            Log.i(TAG, "captureAndSampleIconColor: parsed mode=" + mode
                    + " (previous=" + currentWindowIconMode + ")");
            if (mode != currentWindowIconMode) {
                currentWindowIconMode = mode;
                WidgetService widget = WidgetService.getInstance();
                Log.i(TAG, "captureAndSampleIconColor: mode changed, widget instance="
                        + (widget != null));
                if (widget != null) {
                    widget.onWindowIconModeUpdated();
                }
            }
        });
    }

    /**
     * Averages the luminance of every pixel inside {@link #PROBE_LEFT}..{@link #PROBE_BOTTOM}
     * and compares it against {@link #LUMINANCE_THRESHOLD}. Returns -1 if the rectangle falls
     * outside the actual bitmap (wrong resolution assumption / bad calibration).
     */
    private static int sampleIconMode(Bitmap bmp) {
        Rect probe = new Rect(PROBE_LEFT, PROBE_TOP, PROBE_RIGHT, PROBE_BOTTOM);
        Rect bounds = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
        if (!bounds.contains(probe)) {
            Log.w(TAG, "sampleIconMode: probe rect " + probe + " outside bitmap " + bounds
                    + " — PROBE_* constants likely need recalibrating for this resolution");
            return -1;
        }
        long sum = 0;
        int count = 0;
        for (int y = probe.top; y < probe.bottom; y++) {
            for (int x = probe.left; x < probe.right; x++) {
                int px = bmp.getPixel(x, y);
                // Ignore fully/mostly transparent pixels — icons are drawn on a genuinely
                // transparent status bar background in some states, and including them would
                // bias the average toward whatever's behind (defeats the point of sampling the
                // icon's own drawn color specifically).
                if (Color.alpha(px) < 128) continue;
                int luminance = (int) (0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px));
                sum += luminance;
                count++;
            }
        }
        if (count == 0) {
            Log.w(TAG, "sampleIconMode: every sampled pixel was transparent — probe rect likely misses the icon");
            return -1;
        }
        int avgLuminance = (int) (sum / count);
        Log.i(TAG, "sampleIconMode: avgLuminance=" + avgLuminance + " over " + count + " opaque px");
        return avgLuminance < LUMINANCE_THRESHOLD ? 1 : 2;
    }

    @Override
    public void onInterrupt() {
        // No-op — we don't drive any feedback streams.
    }

    /**
     * Refreshes {@link #foregroundByDisplay} from the live list of accessibility windows.
     * Unrelated to icon-mode detection (see {@link #captureAndSampleIconColor}) — this is only
     * for the per-app widget-visibility feature.
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
