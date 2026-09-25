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
 * history: absent {@code mCurrentFocus}, stale {@code mObscuringWindow}, overlay windows
 * invisible to foreground-app tracking...), this reads the actual rendered color of a real
 * stock status bar icon (Bluetooth, chosen because it's always present) directly off the
 * live framebuffer. Whatever produced that color — wallpaper luminance, per-window flags, some
 * other mechanism entirely — doesn't matter: we copy the real, already-correct result instead
 * of re-deriving it.
 * <p>
 * Getting that single pixel is done with a single lightweight shell one-liner, no screenshot
 * file involved:
 * <pre>{@code
 * screencap|dd bs=<offset> skip=1|dd bs=4 count=1|od -An -tu1|sed 's/^/PXVAL:/'
 * }</pre>
 * {@code screencap} with no {@code -p} dumps the raw framebuffer (16-byte header — width,
 * height, format, dataspace, each a little-endian int32 — confirmed on this exact firmware,
 * followed by row-major RGBA8888 pixel data) straight to its stdout with no PNG encoding at
 * all; the first {@code dd} skips straight to the byte offset of the one pixel we care about
 * in a single block read (a byte-at-a-time {@code skip} was tried first and stalls badly
 * piping from a live process — this is why it's split into two {@code dd} calls, not one with
 * both {@code bs} and {@code skip} together), the second {@code dd} takes just its 4 bytes
 * (R,G,B,A), {@code od} turns those into plain decimal text, and {@code sed} tags the result
 * line with a {@code PXVAL:} marker. The command is kept this terse — no spaces around pipes,
 * no {@code 2>/dev/null} redirects — because a longer, more readable version of it was
 * confirmed to line-wrap in the interactive telnet terminal, and that wrap's echo (cursor
 * control sequences included) got captured as part of the "output" right along with the real
 * result; the marker means {@link #parsePixelMode} doesn't need the whole output to be clean,
 * just finds that tag and reads the numbers after it. The whole point of this approach: the
 * result is a handful of short text numbers, which is exactly what a text-oriented telnet
 * shell channel is fine with — unlike a PNG file, which needs writing to disk, reading back,
 * and decoding, and was confirmed to be the dominant cost of the earlier screenshot-based
 * approach (not the network round trip). This is cheap enough that it doesn't need a kept-open
 * connection either — plain {@link PrivilegedShell#runCommand} (which opens and closes a fresh
 * connection per call) is fine, and leaves the telnet channel free for other apps using it in
 * between captures.
 */
public class WidgetAccessibilityService extends AccessibilityService {
    private static final String TAG = "WidgetA11yService";

    /** Debounce window for {@link #captureAndSampleIconColor()} — TYPE_WINDOWS_CHANGED can
     * fire in quick bursts (scrolling, keyboard) and there's no need to run a fresh pixel probe
     * on every single one. */
    private static final long ICON_MODE_DEBOUNCE_MS = 150L;

    /**
     * A point deep inside the stock Bluetooth icon's stroke, in real device pixels (confirmed
     * 1440x1920 panel). Measured directly from an actual uncompressed screenshot: found the
     * darkest pixel cluster in the icon's bounding box, then eroded that mask by one pixel so
     * the chosen point sits well inside the stroke's thickness rather than on its edge — keeps
     * the probe robust to a pixel or two of calibration drift, since the stroke has real width
     * around this point in every direction, not just at this exact coordinate. Bluetooth was
     * chosen because it's always present regardless of connection/signal state (unlike the
     * Wi-Fi/GPS icons, which can visually change shape).
     */
    private static final int PROBE_X = 1269;
    private static final int PROBE_Y = 47;

    /** Panel width in pixels — needed to convert (x,y) into a byte offset into the raw
     * framebuffer dump (row-major, so offset = header + (y*width + x) * bytesPerPixel). */
    private static final int SCREEN_WIDTH = 1440;

    /** Raw {@code screencap} (no {@code -p}) header size in bytes on this firmware — confirmed
     * by manually dumping the first 16 bytes and reading them as four little-endian int32
     * fields (width, height, format, dataspace): {@code 1440 1920 1 1}. Some Android versions
     * omit the trailing dataspace field (12-byte header) — if this ever needs recalibrating for
     * a different firmware build, dump the first 16 bytes the same way and check whether the
     * third field (pixel format, normally 1 = RGBA_8888) repeats correctly at byte 12 (16-byte
     * header) or byte 8 (12-byte header).
     */
    private static final int RAW_HEADER_BYTES = 16;

    private static final int BYTES_PER_PIXEL = 4; // RGBA_8888

    /** Below this luminance (0-255) the sampled pixel reads as a dark stroke (drawn dark-on-
     * light, i.e. the background behind the status bar is light) → mode 1. Above it, a light
     * stroke (drawn light-on-dark) → mode 2. Safe to use a plain midpoint threshold here
     * because the probe point is confirmed to sit deep inside the icon's own stroke, not in a
     * gap showing background through — see {@link #PROBE_X}/{@link #PROBE_Y}. */
    private static final int LUMINANCE_THRESHOLD = 128;

    @Nullable
    private static volatile WidgetAccessibilityService instance;

    /** displayId → current foreground package. Updated on every window change event. Used only
     * by the (unrelated) per-app widget-visibility feature — NOT by icon-mode detection. */
    private final Map<Integer, String> foregroundByDisplay = new HashMap<>();

    private final android.os.Handler debounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable captureIconColorRunnable = this::captureAndSampleIconColor;

    /**
     * -1 = unknown (no sample yet, or capture/parse failed), 0 = neutral (unused by the
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

        // Debounced: bursts of TYPE_WINDOWS_CHANGED (scrolling, keyboard) would otherwise
        // trigger a fresh pixel probe far more often than the on-screen icon color could
        // plausibly have changed.
        debounceHandler.removeCallbacks(captureIconColorRunnable);
        debounceHandler.postDelayed(captureIconColorRunnable, ICON_MODE_DEBOUNCE_MS);

        WidgetService widget = WidgetService.getInstance();
        if (widget != null) {
            widget.onForegroundDisplayMapUpdated();
        }
    }

    /**
     * Runs the {@code screencap | dd | dd | od} one-liner (see the class javadoc for the full
     * breakdown) and derives light/dark mode from the resulting pixel's luminance.
     */
    private void captureAndSampleIconColor() {
        long offset = (long) RAW_HEADER_BYTES + ((long) PROBE_Y * SCREEN_WIDTH + PROBE_X) * BYTES_PER_PIXEL;
        // Kept as short and marker-tagged as possible: a longer, spaced-out version of this
        // command (with "2>/dev/null" redirects and spaces around every pipe) was confirmed to
        // wrap across lines in the interactive telnet terminal, and that line-wrap echo (with
        // its backspace/cursor-control sequences) ended up captured as part of the "output"
        // instead of the actual result. The "PXVAL:" marker means the parser below doesn't
        // need the whole output to be clean — it just finds this exact tag and reads the
        // numbers right after it, ignoring whatever command-echo noise precedes it.
        String cmd = "screencap|dd bs=" + offset + " skip=1|dd bs=" + BYTES_PER_PIXEL
                + " count=1|od -An -tu1|sed 's/^/PXVAL:/'";
        PrivilegedShell.get(this).runCommand(cmd, (output, error) -> {
            if (output == null) {
                Log.w(TAG, "captureAndSampleIconColor: no output, error=" + error);
                return;
            }
            int mode = parsePixelMode(output);
            Log.i(TAG, "captureAndSampleIconColor: parsed mode=" + mode
                    + " (previous=" + currentWindowIconMode + ") raw=[" + output.trim() + "]");
            if (mode == -1) return;
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
     * Finds the {@code PXVAL:} marker the shell command tags its real result with (see
     * {@link #captureAndSampleIconColor}) and parses the plain decimal bytes right after it —
     * e.g. {@code "PXVAL: 250 252 255 255"} for R,G,B,A — ignoring anything before the marker
     * (confirmed: the interactive telnet terminal can echo the command itself, with
     * line-wrap/cursor-control noise, into the captured output — the marker means that noise
     * never needs to be cleaned up, just skipped past). Derives light/dark mode from the
     * luminance of the first three numbers found (RGB, alpha unused).
     */
    private static int parsePixelMode(String output) {
        int markerIdx = output.indexOf("PXVAL:");
        if (markerIdx == -1) {
            Log.w(TAG, "parsePixelMode: no PXVAL marker in output: [" + output.trim() + "]");
            return -1;
        }
        String tail = output.substring(markerIdx + "PXVAL:".length()).trim();
        String[] parts = tail.split("\\s+");
        if (parts.length < 3) {
            Log.w(TAG, "parsePixelMode: unexpected data after marker: [" + tail + "]");
            return -1;
        }
        try {
            int r = Integer.parseInt(parts[0]);
            int g = Integer.parseInt(parts[1]);
            int b = Integer.parseInt(parts[2]);
            int luminance = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            return luminance < LUMINANCE_THRESHOLD ? 1 : 2;
        } catch (NumberFormatException e) {
            Log.w(TAG, "parsePixelMode: failed to parse numbers from [" + tail + "]", e);
            return -1;
        }
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
