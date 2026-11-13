package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.runtime.LinuxRuntime;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LinuxVulkanDriver {
    private static final String TAG = "LinuxVulkanDriver";
    public static final String ENV = "BL_VK_DRIVER";
    // The Mali bridge ("Mali system driver"): chosen as the Linux driver, the session (Steam, gamescope, the desktop)
    // stays on PanVK and only the game processes get this ICD, through droiddeck-game-env.
    public static final String ENV_GAMES = "BL_VK_GAME_DRIVER";
    private static final String HYBRIS_COMMON = "usr/lib/hybris/libhybris-common.so.1";

    public static final String HELP_TEXT =
            "The driver the Steam client, the games it launches and the desktop's programs render on, "
            + "inside the Linux runtime. Imported \"-Linux\" Turnip zips only: these are Linux processes "
            + "and cannot load an Android or Wayland driver. Frames still reach the screen through the "
            + "display driver below. Takes effect at the next session start.";

    private LinuxVulkanDriver() {}

    public static List<String> optionValues(Context context) {
        ArrayList<String> values = new ArrayList<>();
        values.add("");
        values.addAll(new LinuxVulkanDriverManager(context).enumerateInstalledDrivers());
        return values;
    }

    public static boolean isBridge(Context context, String value) {
        return value != null && !value.isEmpty() && new LinuxVulkanDriverManager(context).isBridge(value);
    }

    /** The driver the session itself (Steam, gamescope, the desktop) draws with. With the bridge chosen it is PanVK,
     *  unless the experimental "session on the Mali system driver" setting puts the session on the bridge too. */
    public static String effective(Context context, String value) {
        return effective(context, value, com.droiddeck.launcher.core.DeviceSupport.INSTANCE.mali());
    }

    static String effective(Context context, String value, boolean mali) {
        if (value != null && !value.isEmpty() && !isBridge(context, value)) return value;
        if (isBridge(context, value) && com.droiddeck.launcher.session.SessionPrefs.INSTANCE.bridgeSession(context)
                && !gameDriver(context, value).isEmpty()) {
            Log.i(TAG, "Mali system driver for the session too (experimental setting): " + value);
            return value;
        }
        if (!mali) return "";
        String id = sessionPanvk(context);
        if (!id.isEmpty()) Log.i(TAG, "Mali GPU and no Linux driver chosen; using PanVK " + id);
        return id;
    }

    /** The session's own Linux driver ("" = the runtime's): what the running session was started with, else what a session
     *  started now uses (SessionService). The one answer for status lines and droiddeckctl panvk use. */
    public static String sessionDriver(Context context) {
        return sessionDriver(context, com.droiddeck.launcher.core.DeviceSupport.INSTANCE.mali());
    }

    static String sessionDriver(Context context, boolean mali) {
        if (com.droiddeck.launcher.session.SessionState.INSTANCE.getRunning()) {
            String running = com.droiddeck.launcher.session.SessionState.INSTANCE.getSessionDriver();
            return running == null ? "" : running;
        }
        return effective(context, com.droiddeck.launcher.session.SessionPrefs.INSTANCE.linuxDriver(context, mali), mali);
    }

    /** The PanVK the session uses when no non-bridge Linux driver is chosen ("" without one), see [pickSessionPanvk]. */
    public static String sessionPanvk(Context context) {
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        List<String> panvk = new ArrayList<>();
        Set<String> ours = new HashSet<>();
        for (String id : m.enumerateInstalledDrivers()) {
            if (!m.isPanvk(id)) continue;
            panvk.add(id);
            if (m.isOurPanvk(id)) ours.add(id);
        }
        return pickSessionPanvk(panvk, ours, com.droiddeck.launcher.session.SessionPrefs.INSTANCE.sessionPanvk(context));
    }

    /**
     * Decision D10 (b): a PanVK the user has (an import, or the PanVK_Kbase slot of an older app version) keeps being
     * used, as before; our own Blobferry_PanVK ([ours], by its owner meta) is the session's PanVK only when it is the only
     * one, i.e. on a new install. D10 (c): [preferred] (stored only by droiddeckctl panvk use) wins while it is installed.
     */
    static String pickSessionPanvk(List<String> sortedPanvkIds, Set<String> ours, String preferred) {
        if (preferred != null && sortedPanvkIds.contains(preferred)) return preferred;
        for (String id : sortedPanvkIds) if (!ours.contains(id)) return id;
        return sortedPanvkIds.isEmpty() ? "" : sortedPanvkIds.get(0);
    }

    /** The driver the game processes draw with when it is not the session's: the bridge, or "" for the session's own. */
    public static String gameDriver(Context context, String value) {
        if (!isBridge(context, value)) return "";
        if (!new File(LinuxRuntime.rootDir(context), HYBRIS_COMMON).isFile()) {
            Log.w(TAG, "Mali bridge chosen but libhybris is not in the runtime (" + HYBRIS_COMMON + "); games stay on the session driver");
            return "";
        }
        return value;
    }

    /** The bridge's ICD manifest whenever one is installed and the runtime has libhybris, whatever driver is chosen:
     *  a game's own environment can then pick the bridge or the session's driver (DROIDDECK_GAME_DRIVER, read by
     *  droiddeck-game-env). */
    public static final String ENV_BRIDGE = "BL_VK_BRIDGE_ICD";

    public static String bridgeIcdPath(Context context) {
        if (!new File(LinuxRuntime.rootDir(context), HYBRIS_COMMON).isFile()) return null;
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        if (m.isBridge(LinuxVulkanDriverManager.BUNDLED_BRIDGE_ID)) return m.getIcdPath(LinuxVulkanDriverManager.BUNDLED_BRIDGE_ID);
        for (String id : m.enumerateInstalledDrivers()) if (m.isBridge(id)) return m.getIcdPath(id);
        return null;
    }

    public static String resolveIcdPath(Context context, String value) {
        if (value == null || value.isEmpty()) return null;
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        String icd = m.getIcdPath(value);
        if (icd == null) {
            Log.w(TAG, "imported Linux driver \"" + value + "\" is gone; using the runtime's own driver");
            return null;
        }
        Log.i(TAG, "Linux session draws with imported driver " + value + " (" + icd + ")");
        return icd;
    }
}
