package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import com.droiddeck.launcher.runtime.LinuxRuntime;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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

    public static String optionLabel(Context context, String value) {
        if (value == null || value.isEmpty()) return "Runtime default (built into the runtime)";
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        if (!m.isInstalled(value)) return value + " (imported, missing - uses the runtime default)";
        String ver = m.getDriverVersion(value);
        return m.getDriverName(value) + (ver.isEmpty() ? "" : " " + ver) + " (imported)";
    }

    public static boolean isBridge(Context context, String value) {
        return value != null && !value.isEmpty() && new LinuxVulkanDriverManager(context).isBridge(value);
    }

    /** The driver the session itself (Steam, gamescope, the desktop) draws with. */
    public static String effective(Context context, String value) {
        if (value != null && !value.isEmpty() && !isBridge(context, value)) return value;
        if (!com.droiddeck.launcher.core.DeviceSupport.INSTANCE.mali()) return "";
        LinuxVulkanDriverManager m = new LinuxVulkanDriverManager(context);
        for (String id : m.enumerateInstalledDrivers()) {
            if (m.isPanvk(id)) {
                Log.i(TAG, "Mali GPU and no Linux driver chosen; using imported PanVK " + id);
                return id;
            }
        }
        return "";
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
