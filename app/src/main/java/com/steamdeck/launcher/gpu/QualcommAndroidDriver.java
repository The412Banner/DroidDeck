package com.steamdeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import com.steamdeck.launcher.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * Qualcomm's own Adreno Vulkan driver, Android build, as Valve ships it for the Steam Frame's
 * Android side ({@code qualcomm-android-graphics-driver-20250602.1}): {@code vulkan.adreno.so}
 * v786 (built 2024-08-01, compiler E031.47.01.00) with the GPU and compiler libraries it loads.
 * An alternative display driver for the app's compositor, installed as an ordinary AdrenoTools
 * driver in {@link TurnipDriver}'s list.
 *
 * <p>Not the same thing as {@link QualcommLinuxDriver}: that one is glibc and runs inside the
 * runtime; this one is bionic and runs in the app. Fetched the same way - from Valve's server,
 * pinned by sha256 ({@link ValvePackage}), never bundled.
 */
public final class QualcommAndroidDriver {
    private static final String TAG = "QualcommAndroidDriver";

    public static final String ID = "qualcomm-adreno-android-v786";
    public static final String NAME = "Qualcomm Adreno v786 (Android)";
    public static final String VERSION = "v786 (2024-08-01)";
    public static final String URL_STRING = "https://holo-packages.steamos.cloud/archlinux-deckard-hotfixes/release/0.4.x/"
            + "qualcomm-android-graphics-driver-20250602.1-1-any.pkg.tar.zst";
    public static final String SHA256 = "29321f23afa2512b0bb9a94370ca9ec73099e53fb3e7b0cd5611a0d607507d96";
    public static final long SIZE = 12_313_206L;

    private static final String LIBRARY = "vulkan.adreno.so";
    /** What the driver loads beside itself; everything else it needs is the system's. */
    private static final String[] COMPANIONS = {"libgsl.so", "libllvm-glnext.so", "libadreno_utils.so"};
    private static final String VENDOR = "usr/share/guestos/android/vendor/lib64";

    private QualcommAndroidDriver() {}

    /**
     * Download, verify and install into the display driver list. Returns the driver id.
     * @throws IllegalArgumentException with a user-facing reason (checksum mismatch, bad package)
     * @throws IOException on network or disk failures
     */
    public static String install(Context context, QualcommLinuxDriver.Progress progress) throws IOException {
        TurnipDriver td = new TurnipDriver(context);
        if (td.isInstalled(ID)) return ID;
        File contentDir = new File(context.getFilesDir(), "graphics_driver");
        File cache = new File(context.getCacheDir(), "qualcomm-android.pkg.tar.zst");
        File tmp = new File(contentDir, ".tmp-qcom-android-" + System.currentTimeMillis());
        try {
            File unpacked = new File(tmp, "pkg");
            ValvePackage.fetch(URL_STRING, SHA256, SIZE, cache, unpacked,
                    progress == null ? null : progress::onProgress);
            File vendor = new File(unpacked, VENDOR);
            File out = new File(tmp, "out");
            if (!out.mkdirs()) throw new IOException("cannot create " + out);
            File lib = new File(vendor, "hw/" + LIBRARY);
            if (!lib.isFile() || !LinuxVulkanDriverManager.isAarch64Elf(lib)
                    || LinuxVulkanDriverManager.containsAscii(lib, "libc.so.6")) {
                throw new IllegalArgumentException("The package has no Android AArch64 " + LIBRARY + ".");
            }
            if (!lib.renameTo(new File(out, LIBRARY))) throw new IOException("could not place " + LIBRARY);
            for (String name : COMPANIONS) {
                if (!new File(vendor, name).renameTo(new File(out, name))) throw new IOException("could not place " + name);
            }
            JSONObject meta = new JSONObject();
            meta.put("schemaVersion", 1);
            meta.put("name", NAME);
            meta.put("description", "Qualcomm's proprietary Adreno Vulkan driver, Android build, from Valve's Steam Frame packages.");
            meta.put("author", "Qualcomm (packaged by Valve)");
            meta.put("packageVersion", "20250602.1");
            meta.put("vendor", "Qualcomm");
            meta.put("driverVersion", VERSION);
            meta.put("minApi", 28);
            meta.put("libraryName", LIBRARY);
            meta.put("source", URL_STRING);
            meta.put("sha256", SHA256);
            if (!FileUtils.writeString(new File(out, "meta.json"), meta.toString(2))) throw new IOException("cannot write meta.json");
            File dir = new File(contentDir, ID);
            FileUtils.delete(dir);
            if (!out.renameTo(dir)) throw new IOException("cannot move into " + dir);
            Log.i(TAG, "installed " + ID + " -> " + dir);
            return ID;
        } catch (org.json.JSONException e) {
            throw new IOException("manifest write failed: " + e.getMessage());
        } finally {
            FileUtils.delete(tmp);
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
        }
    }
}
